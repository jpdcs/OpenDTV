import os,sys,json
sys.path.insert(0,'.')
from gen import *
def ser(o):
    k=o[0]
    if k=='W': return 'W%d:%x:%s'%(o[1],o[2],o[3])
    if k=='M': return 'M%d:%x:%02x:%02x'%(o[1],o[2],o[3],o[4])
    if k=='V': return 'V%d'%o[1]
    if k=='P': return 'P%d:%x'%(o[1],o[2])
    if k=='Q': return 'Q%d:%x'%(o[1],o[2])
    if k=='I': return 'I%d:%x:%s'%(o[1],o[2],o[3])
    raise Exception(o)
def keep(o): return o[0] in ('W','M','V','P','Q','I')
init={}; tune={}
for clk in (0,1):
    o,_,_=program(clk)
    ia=[i for i,x in enumerate(o) if x[0]=='R' and x[1]==8 and x[2]==0xec86][0]
    ib=[i for i,x in enumerate(o) if x[0]=='W' and x[1]==8 and x[2]==0xed81][0]
    ie=[i for i,x in enumerate(o) if x[0]=='W' and x[1]==8 and x[2]==0xec4c][0]
    oo=[]
    for x in o:
        oo.append(('Q',8,0x4d) if (x[0]=='R' and x[1]==8 and x[2]==0x4d) else x)
        if x[0]=='R' and x[1]==8 and x[2]==0x4d: oo.append(('I',8,0x4e,'01'))
    j=[i for i,x in enumerate(oo) if x[0]=='W' and x[1]==8 and x[2]==0 and x[3]=='01'][0]
    oo.insert(j,('I',8,0x4e,'00'))
    o=oo
    ie=[i for i,x in enumerate(o) if x[0]=='W' and x[1]==8 and x[2]==0xec4c][0]
    ia=[i for i,x in enumerate(o) if x[0]=='R' and x[1]==8 and x[2]==0xec86][0]
    ib=[i for i,x in enumerate(o) if x[0]=='W' and x[1]==8 and x[2]==0xed81][0]
    A=[x for x in o[ie+1:ia] if keep(x)]
    B=[x for x in o[ib+1:] if keep(x)]
    init[clk]=(A,B)
    t,_,_=program(clk,497143000)
    tp=t[len(o)-2:]
    ic=[i for i,x in enumerate(tp) if x[0]=='W' and x[1]==8 and x[2]==0xfb25][0]
    pre=[x for x in tp[:ic] if keep(x) and not (x[0]=='W' and x[2]==0x4b)]
    tune[clk]=pre
    print(clk,len(A),len(B),len(pre))
bands=json.load(open(os.environ.get('OPENDTV_BANDS','bands.json')))
CLS=os.environ.get('OPENDTV_CLASS','It9175Data')
LIBNAME=os.path.basename(os.environ.get('OPENDTV_LIB','libpt275aDrv.so'))
def jarr(name,lst):
    s='    static final String[] %s = {\n'%name
    s+=''.join('        "%s",\n'%x for x in lst)
    return s+'    };\n'
out=['package ph.opendtv.source;','',
'/** GENERATED from the vendor '+LIBNAME+' run under emulation (see tools/emu). Do not edit by hand.',
' * Op syntax: Wp:addr:hex = write; Mp:addr:mask:val = read-modify-write ((r&mask)|val); Vp = fw-version query;',
' * Pp:addr = poll until reg==0. p = 0 (LINK) or 8 (OFDM). Index = LINK 0xd800 low nibble (clock). */',
'final class '+CLS+' {','    private '+CLS+'() {}','']
for clk in (0,1):
    out.append(jarr('INIT_A%d'%clk,[ser(x) for x in init[clk][0]]))
    out.append(jarr('INIT_B%d'%clk,[ser(x) for x in init[clk][1]]))
    out.append(jarr('TUNE_PRE%d'%clk,[ser(x) for x in tune[clk]]))
for d in ('0','1'):
    flat=[]
    for f,r in bands[d]: flat.append('%d,%s'%(f,','.join(str(x) for x in r)))
    out.append('    /** band rows for OFDM 0x1dc=%s: startKHz, fb25, fbb5, fbb6, fbb7, fbb9a, fbb9b, fbba */\n    static final int[][] BANDS%s = {\n'%(d,d)+''.join('        {%s},\n'%x for x in flat)+'    };\n')
out.append('}')
open(CLS+'.java','w').write('\n'.join(out))
