import os, sys, struct
sys.path.insert(0,'.')
from emu import *
from fakedev import FakeDev
# Vendor library to emulate and its export prefix. Defaults: libpt275aDrv.so (048d:275a / 275b).
# For the PT275C (048d:e275) use the vendor's libPTx1Drv.so: OPENDTV_PREFIX=libPT917x_
LIB=os.environ.get('OPENDTV_LIB','/mnt/user-data/uploads/libpt275aDrv.so')
PREFIX=os.environ.get('OPENDTV_PREFIX','lib275a_')
def build(defaults=None):
    dev=FakeDev()
    dev.defaults[(0,0x1222)]=1; dev.defaults[(0,0x1223)]=0x75; dev.defaults[(0,0x1224)]=0x91
    if defaults: dev.defaults.update(defaults)
    e=Emu(LIB,dev); e.verbose=False
    # the scripts call the lib275a_* names; alias them to the library's own prefix
    for n,v in list(e.syms.items()):
        if n.startswith(PREFIX): e.syms.setdefault('lib275a_'+n[len(PREFIX):],v)
    ops=e.h_malloc(0x100)
    tbl=[e.make_hook('ops_init',lambda *a:0),0,e.make_hook('ops_getfd',lambda *a:7),0,0,e.make_hook('ops_post',lambda *a:0)]
    for i,v in enumerate(tbl): e.uc.mem_write(ops+i*8,struct.pack('<Q',v))
    return e,dev,ops
if __name__=='__main__':
    e,dev,ops=build()
    print('init',e.call(e.syms['lib275a_Init'],[ops]))
    n0=len(dev.trace)
    p=e.h_malloc(64); e.uc.mem_write(p,struct.pack('<IIII',497143000,2,0,0))
    print('setpara',e.call(e.syms['lib275a_SetPara'],[p]))
    for t in dev.trace[n0:]: print(t)
    n1=len(dev.trace)
    q=e.h_malloc(16)
    print('islock',e.call(e.syms['lib275a_IsLock'],[q]),struct.unpack('<I',bytes(e.uc.mem_read(q,4))))
    for t in dev.trace[n1:]: print(t)
