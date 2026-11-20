"""Extract the IT9175 8051 firmware + partition table from a vendor driver .so and write it as a Java class.

Usage: python gen_fw.py <libXXXDrv.so> <JavaClassName>
The image is a run of chunks '03 <core> 00 <n>', n x {addr_hi, addr_lo, len}, then the data; right after it the
.so holds the partition table (n x {u64 type=1, u64 length}) that Standard_loadFirmware sends, one command 0x29
per entry. The output has the same shape as It9175Firmware.java (one hex line per chunk).
"""
import os, struct, sys


def parse_chunk(d, i):
    if i + 4 > len(d) or d[i] != 3 or d[i + 1] not in (0, 1) or d[i + 2] != 0 or d[i + 3] == 0:
        return None
    n, p = d[i + 3], i + 4
    lens = []
    for _ in range(n):
        if p + 3 > len(d) or d[p + 2] == 0: return None
        lens.append(d[p + 2]); p += 3
    p += sum(lens)
    return p if p <= len(d) else None


def find_image(d):
    best = None
    i = 0
    while i < len(d):
        j, n = i, 0
        while True:
            k = parse_chunk(d, j)
            if k is None: break
            j, n = k, n + 1
        if n >= 20 and (best is None or j - i > best[1] - best[0]):
            best = (i, j)
        i = j if n >= 20 else i + 1
    return best


def find_partitions(d, start, total):
    p = (start + 7) & ~7
    for p in range(p, p + 64, 8):
        lens, q = [], p
        while q + 16 <= len(d):
            t, l = struct.unpack_from('<QQ', d, q)
            if t != 1 or not 0 < l <= 0x100: break
            lens.append(l); q += 16
        if lens and sum(lens) == total: return lens
    raise SystemExit('partition table not found')


def main():
    lib, cls = sys.argv[1], sys.argv[2]
    d = open(lib, 'rb').read()
    a, b = find_image(d)
    img = d[a:b]
    chunks = find_partitions(d, b, len(img))
    name = os.path.basename(lib)
    out = ['package ph.opendtv.source;', '',
           '/**',
           ' * Firmware image for the ITE IT9175, extracted from the vendor %s (Android arm64) by tools/emu/gen_fw.py:' % name,
           ' * the %d-byte 8051 image in .data at file offset 0x%x plus its partition table (%d entries, every one' % (len(img), a, len(chunks)),
           ' * type 1 = raw chunk sent with command 0x29). Property of ITE Tech.; extracted for interoperability with the',
           " * owner's own device. GENERATED, do not edit by hand.",
           ' */',
           'final class %s {' % cls,
           '    private %s() {}' % cls, '',
           '    /** Chunk sizes in download order (sum = %d). */' % len(img),
           '    static final int[] CHUNKS = {']
    for i in range(0, len(chunks), 16):
        out.append('        ' + ', '.join(str(x) for x in chunks[i:i + 16]) + ',')
    out += ['    };', '', '    static final String HEX =']
    off, lines = 0, []
    for n in chunks:
        lines.append('        "%s"' % img[off:off + n].hex()); off += n
    out.append(' +\n'.join(lines) + ';')
    out.append('}')
    open(cls + '.java', 'w', newline='\n').write('\n'.join(out) + '\n')
    print('%s: image 0x%x..0x%x (%d bytes), %d chunks' % (cls, a, b, len(img), len(chunks)))


if __name__ == '__main__':
    main()
