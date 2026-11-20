import os,sys,json
sys.path.insert(0,'.')
from bands import make
def rowbytes(k):
    d=dict((a,v) for a,v in k)
    g=lambda a,i=0: int(d[a][i],16) if a in d and len(d[a])>i else -1
    return (g(0xfb25),g(0xfbb5),g(0xfbb6),g(0xfbb7),g(0xfbb9,0),g(0xfbb9,1),g(0xfbba))
out={}
for d1 in (0,1):
    row=make(d1)
    cache={}
    def r(f):
        if f not in cache: cache[f]=rowbytes(row(f))
        return cache[f]
    lo,hi=40000,1100000; step=100
    pts=list(range(lo,hi+1,step)); trans=[(lo,r(lo))]
    prev=r(lo); pf=lo
    for f in pts[1:]:
        cur=r(f)
        if cur!=prev:
            a,b=pf,f
            while b-a>1:
                m=(a+b)//2
                if r(m)==prev: a=m
                else: b=m
            trans.append((b,cur)); prev=cur
        pf=f
    out[d1]=trans
    print(d1,len(trans))
json.dump(out,open(os.environ.get('OPENDTV_BANDS','bands.json'),'w'))
