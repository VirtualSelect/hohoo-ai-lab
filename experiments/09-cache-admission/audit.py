import hashlib,json,sys,statistics
from collections import OrderedDict,defaultdict
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parents[2]
def verify_sources(manifest,root=ROOT):
 sources=manifest.get('sources')
 if not isinstance(sources,dict) or not sources:raise ValueError('Missing source fingerprints')
 for name,expected in sources.items():
  target=(root/name).resolve()
  if not target.is_relative_to(root.resolve()) or not target.is_file():raise ValueError('Invalid source path: '+name)
  actual=hashlib.sha256(target.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
  if actual!=expected:raise ValueError('Source mismatch; use the evidence-pinned revision: '+name)

def audit(out):
 manifest=json.loads((out/'manifest.json').read_text('utf8'));rows=json.loads((out/'results.json').read_text('utf8'));arrays=np.load(out/'arrays.npz');groups=defaultdict(list)
 verify_sources(manifest)
 for n,h in manifest['files'].items():assert hashlib.sha256((out/n).read_bytes()).hexdigest()==h
 assert len(rows)==4860 and len(arrays.files)==1944
 for r in rows:groups[(r['seed'],r['budget'],r['trace'],r['policy'],r['repeat'])].append(r)
 assert len(groups)==405
 totals=[]
 for (seed,budget,trace,policy,repeat),seq in groups.items():
  assert [r['index'] for r in seq]==list(range(12));lru=OrderedDict();evictions=0
  for r in seq:
   # Independent token/scope LRU replay; each stored prefix costs 528 bytes/token.
   matches=[k for k in lru if k[0]==r['scope'] and list(k[1])==r['tokens'][:len(k[1])]]
   best=max(matches,key=lambda k:len(k[1]),default=None);cut=len(best[1]) if best else 0;copied=528*cut
   if best:lru.move_to_end(best)
   if policy!='none':
    for end in [4,8]:
     if end<=cut or (policy=='longest' and end!=8):continue
     key=(r['scope'],tuple(r['tokens'][:end]));cost=528*end
     if cost>budget:continue
     if key in lru:lru.move_to_end(key);continue
     while sum(lru.values())+cost>budget:lru.popitem(last=False);evictions+=1
     lru[key]=cost;copied+=cost
   assert r['cut']==cut and r['projected_rows']==6*(12-cut)
   assert r['resident_bytes']==sum(lru.values())<=budget and r['explicit_copy_bytes']==copied
   assert r['elapsed_ns']>0 and r['max_error']<1e-12
   if repeat==0:assert abs(float(np.max(np.abs(arrays[r['key']+'_fresh']-arrays[r['key']+'_reuse'])))-r['max_error'])<1e-25
  totals.append(dict(seed=seed,budget=budget,trace=trace,policy=policy,repeat=repeat,elapsed_ms=sum(r['elapsed_ns'] for r in seq)/1e6,projected_rows=sum(r['projected_rows'] for r in seq),copy_bytes=sum(r['explicit_copy_bytes'] for r in seq),evictions=evictions,hits=sum(r['cut']>0 for r in seq)))
 summary=[]
 for b,t,p in sorted({(x['budget'],x['trace'],x['policy']) for x in totals}):
  g=[x for x in totals if (x['budget'],x['trace'],x['policy'])==(b,t,p)];v=[x['elapsed_ms'] for x in g]
  assert len({x['projected_rows'] for x in g})==1
  summary.append(dict(budget=b,trace=t,policy=p,median_ms=statistics.median(v),min_ms=min(v),max_ms=max(v),projected_rows=g[0]['projected_rows'],hits=g[0]['hits'],evictions=g[0]['evictions'],copy_bytes=g[0]['copy_bytes']))
 return dict(requests=len(rows),arrays=len(arrays.files),warmup_requests=manifest['warmup_requests'],max_error=max(r['max_error'] for r in rows),summary=summary)
if __name__=='__main__':
 out=Path(sys.argv[1]);a=audit(out);(out/'audit.json').write_text(json.dumps(a,indent=2)+'\n',encoding='utf8');print(json.dumps(a,indent=2))
