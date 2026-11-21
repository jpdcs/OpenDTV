import sys, struct
from unicorn import *
from unicorn.arm64_const import *
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection

BASE = 0x40000000
HOOK = 0x10000000
HEAP = 0x20000000
STACK = 0x7ff00000
STACK_SIZE = 0x200000

class Emu:
    def __init__(self, path, dev):
        self.dev = dev
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        uc = self.uc
        self.f = open(path, 'rb'); elf = ELFFile(self.f)
        self.elf = elf
        uc.mem_map(BASE, 0x80000)
        for seg in elf.iter_segments():
            if seg['p_type'] == 'PT_LOAD':
                data = seg.data()
                uc.mem_write(BASE + seg['p_vaddr'], data)
        uc.mem_map(HOOK, 0x10000)
        uc.mem_write(HOOK, b'\xc0\x03\x5f\xd6' * (0x10000 // 4))
        uc.mem_map(HEAP, 0x4000000)
        self.heap_ptr = HEAP + 0x1000
        uc.mem_map(STACK, STACK_SIZE)
        self.hooks = {}
        self.hook_names = {}
        self.nhooks = 0
        self.canary = HEAP + 0x100
        uc.mem_write(self.canary, struct.pack('<Q', 0x1122334455667788))
        # newer NDK builds read the stack canary from TLS ([TPIDR_EL0 + 0x28]) instead of __stack_chk_guard
        self.tls = HEAP + 0x200
        uc.mem_write(self.tls + 0x28, struct.pack('<Q', 0x1122334455667788))
        uc.reg_write(UC_ARM64_REG_TPIDR_EL0, self.tls)
        self.syms = {}
        dynsym = elf.get_section_by_name('.dynsym')
        for s in dynsym.iter_symbols():
            if s['st_shndx'] != 'SHN_UNDEF' and s.name:
                self.syms[s.name] = s['st_value']
        # relocations
        for secname in ('.rela.dyn', '.rela.plt'):
            sec = elf.get_section_by_name(secname)
            for r in sec.iter_relocations():
                off = r['r_offset']; t = r['r_info_type']
                if t == 1027:  # RELATIVE
                    uc.mem_write(BASE + off, struct.pack('<Q', BASE + r['r_addend']))
                elif t in (1025, 1026, 257):
                    sym = dynsym.get_symbol(r['r_info_sym']).name
                    if sym == '__stack_chk_guard':
                        uc.mem_write(BASE + off, struct.pack('<Q', self.canary))
                    else:
                        uc.mem_write(BASE + off, struct.pack('<Q', self.hook_addr(sym)))
                else:
                    print('unhandled reloc', t)
        uc.hook_add(UC_HOOK_CODE, self.code_hook, begin=HOOK, end=HOOK + 0x10000)
        self.log = []
        self.ncalls = 0

    def hook_addr(self, name):
        for a, n in self.hook_names.items():
            if n == name: return a
        a = HOOK + self.nhooks * 16
        self.nhooks += 1
        self.hook_names[a] = name
        return a

    def make_hook(self, name, fn):
        a = self.hook_addr(name)
        self.hooks[a] = fn
        return a

    def code_hook(self, uc, address, size, user):
        name = self.hook_names.get(address)
        if name is None: return
        fn = self.hooks.get(address)
        if fn is None:
            fn = getattr(self, 'h_' + name.strip('_'), None)
        if fn is None:
            print('UNHANDLED HOOK', name); uc.emu_stop(); return
        args = [uc.reg_read(UC_ARM64_REG_X0 + i) for i in range(8)]
        ret = fn(*args)
        if ret is None: ret = 0
        uc.reg_write(UC_ARM64_REG_X0, ret & 0xFFFFFFFFFFFFFFFF)

    # --- libc hooks
    def cstr(self, a):
        out = bytearray()
        while True:
            b = self.uc.mem_read(a, 1)[0]
            if b == 0: break
            out.append(b); a += 1
        return out.decode('latin1')
    def h_malloc(self, n, *a):
        p = self.heap_ptr; self.heap_ptr = (self.heap_ptr + n + 15) & ~15
        return p
    def h_free(self, *a): return 0
    def h_memset(self, p, c, n, *a):
        self.uc.mem_write(p, bytes([c & 0xFF]) * n); return p
    def h_memcpy(self, d, s, n, *a):
        self.uc.mem_write(d, bytes(self.uc.mem_read(s, n))); return d
    def h_printf(self, *a):
        if self.verbose: print('printf:', self.cstr(a[0]))
        return 0
    verbose = False
    def h_puts(self, p, *a):
        if self.verbose: print('puts:', self.cstr(p))
        return 0
    def h_vsprintf(self, buf, *a):
        self.uc.mem_write(buf, b'\0'); return 0
    def h_vasprintf(self, pp, *a):
        p = self.h_malloc(2); self.uc.mem_write(p, b'\0'); self.uc.mem_write(pp, struct.pack('<Q', p)); return 0
    def h_android_log_print(self, *a): return 0
    def h_usleep(self, us, *a):
        self.dev.vt += us; return 0
    def h_time(self, *a): return 1700000000
    def h_rand(self, *a):
        self.rnd = (getattr(self, 'rnd', 12345) * 1103515245 + 12345) & 0x7fffffff; return self.rnd
    def h_cxa_atexit(self, *a): return 0
    def h_cxa_finalize(self, *a): return 0
    def h_stack_chk_fail(self, *a):
        print('STACK CHK FAIL'); self.uc.emu_stop()
    def h_ioctl(self, fd, req, arg, *a):
        if req & 0xFFFFFFFF == 0xC0185502:
            ep, ln, to, _pad, data = struct.unpack('<IIIIQ', bytes(self.uc.mem_read(arg, 24)))
            if ep & 0x80:
                buf = self.dev.bulk_in(ep, ln)
                if buf is None: return (-110) & 0xFFFFFFFF
                self.uc.mem_write(data, buf)
                return len(buf)
            else:
                payload = bytes(self.uc.mem_read(data, ln))
                self.dev.bulk_out(ep, payload)
                return ln
        return 0

    # --- running
    def call(self, addr, args=(), maxins=50_000_000):
        uc = self.uc
        sp = STACK + STACK_SIZE - 0x1000
        uc.reg_write(UC_ARM64_REG_SP, sp)
        for i, v in enumerate(args):
            uc.reg_write(UC_ARM64_REG_X0 + i, v & 0xFFFFFFFFFFFFFFFF)
        ret_addr = HOOK + 0xF000
        uc.reg_write(UC_ARM64_REG_X30, ret_addr)
        self.ret_addr = ret_addr
        # stop at ret_addr
        h = uc.hook_add(UC_HOOK_CODE, lambda u, a, s, ud: u.emu_stop() if a == ret_addr else None, begin=ret_addr, end=ret_addr + 4)
        try:
            uc.emu_start(BASE + addr, ret_addr, count=maxins)
        finally:
            uc.hook_del(h)
        return uc.reg_read(UC_ARM64_REG_X0)

    def sym(self, name): return BASE + self.syms[name]
