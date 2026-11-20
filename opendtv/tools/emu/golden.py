import os,sys,struct,random
sys.path.insert(0,'.')
from run_tune import build
from fakedev import FakeDev
TG=[(0,0xd8c8),(0,0xd8c9),(0,0xd8bc),(0,0xd8bd),(0,0xe00c),(8,0xf5ca),(8,0xf715),(0,0xf41f),(8,0xf990),(0,0xf41a),(0,0xf3da)]
def pseudo(p,a): return (a*131+p*17+7)&0xff
class SD(FakeDev):
    expl={}; tg=set()
    def rd(self,proc,addr):
        k=(proc,addr)
        if k in self.expl: return self.expl[k]
        if k in self.tg: return pseudo(proc,addr)
        return 0
def collect_targets():
    # every register ever read-then-written in init/tune/pid flows: discover with sticky run
    import gen
    s=set()
    for clk in (0,1):
        for d1 in (0,1):
            t,_=gen.run(clk,{},497143000,d1,0)
            for i in range(len(t)-1):
                if t[i][0]=='R' and t[i+1][0]=='W' and t[i][1:4]==t[i+1][1:4] and t[i][3]==1: s.add((t[i][1],t[i][2]))
    s|={(8,0xf993),(8,0xf992),(0,0xd8d0),(0,0xd8d1),(0,0xd8cf),(8,0xf999)}
    return s
def vendor(sc,targets,expl_extra={}):
    clk,d1,ec86,ed03,ed23,ed81,ec4c,r4d,freq=sc
    SD.tg=set(targets)-{(0,0xd800),(8,0x1dc),(8,0xec86),(0,0x1222),(0,0x1223),(8,0xec4c),(8,0xed81),(8,0x4d),(8,0x1c6)}
    SD.expl={(0,0xd800):clk,(0,0x1222):1,(0,0x1223):0x75,(0,0x1224):0x91,(8,0xec86):ec86,(8,0xed03):ed03,
             (8,0xed23):ed23&0xff,(8,0xed24):ed23>>8,(8,0xed81):ed81,(8,0xec4c):ec4c,(8,0x1dc):d1,(8,0x1c6):1,(8,0x4d):r4d}
    e,dev,ops=build({})
    dev.__class__=SD
    r=e.call(e.syms['lib275a_Init'],[ops]); assert r==0,r
    e.call(e.syms['lib275a_PIDOnOff'],[0]); e.call(e.syms['lib275a_set_oneseg'],[0])
    p=e.h_malloc(64); e.uc.mem_write(p,struct.pack('<IIII',freq*1000,0,2,0))
    assert e.call(e.syms['lib275a_SetPara'],[p])==0
    tr=dev.trace
    st=[i for i,t in enumerate(tr) if t[0]=='W' and t[1]==8 and t[2]==0xec4c][0]
    out=[]
    for t in tr[st+1:]:
        if t[0]=='W': out.append('W %d %x %s'%(t[1],t[2],t[4]))
        elif t[0]=='C' and t[2]=='0x22': out.append('C %d 22'%t[1])
    return out
if __name__=='__main__':
    tg=sorted(collect_targets())
    random.seed(5)
    lines=['T %d %x'%t for t in tg]
    n=int(sys.argv[1]) if len(sys.argv)>1 else 40
    for i in range(n):
        sc=(random.choice((0,1)),random.choice((0,1)),random.choice((0,1,2)),random.randint(0,9),random.randint(500,30000),random.randint(0,255),random.randint(0,255),random.choice((0,0,1)),
            random.choice([473143+6000*k for k in range(38)]+[random.randint(100000,860000)]))
        if i==0: sc=(1,0,0,2,4000,0,0xa8,0,497143)
        ops=vendor(sc,tg)
        lines.append('S '+' '.join(map(str,sc))); lines+=['O '+o for o in ops]; lines.append('E')
    open(os.environ.get('OPENDTV_GOLDEN','golden.txt'),'w').write('\n'.join(lines)+'\n')
    print('ok',len(lines))
