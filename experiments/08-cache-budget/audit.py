import argparse,hashlib,json
from collections import OrderedDict
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parents[2]
def audit(out):
 m=json.loads((out/'manifest.json').read_text('utf8'));rows=json.loads((out/'results.json').read_text('utf8'));arrays=np.load(out/'arrays.npz',allow_pickle=False)
 for p,h in m['sources'].items():assert hashlib.sha256((ROOT/p).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==h,p
 for p,h in m['files'].items():assert hashlib.sha256((out/p).read_bytes()).hexdigest()==h,p
 assert len(rows)==324 and len(arrays.files)==648
 totals=[]
 for offset in range(0,len(rows),12):
  group=rows[offset:offset+12];cache=OrderedDict();total=dict(seed=group[0]['seed'],budget=group[0]['budget'],trace=group[0]['trace'],fresh_rows=0,projected_rows=0,hits=0,evictions=0,copy_bytes=0)
  for i,r in enumerate(group):
   assert r['index']==i and all(r[k]==total[k] for k in ('seed','budget','trace'))
   matches=[k for k in cache if k[0]==r['scope'] and k[1]==tuple(r['tokens'][:len(k[1])]) and len(k[1])<len(r['tokens'])];best=max(matches,key=lambda k:len(k[1])) if matches else None;cut=len(best[1]) if best else 0
   assert cut==r['cut'];copied=528*cut
   if best:cache.move_to_end(best)
   events=[]
   for end in range(cut+4,12,4):
    key=(r['scope'],tuple(r['tokens'][:end]));cost=528*end
    if cost>r['budget']:events.append(dict(type='skip',prefix=list(key[1]),bytes=cost));continue
    if key in cache:cache.move_to_end(key);continue
    while sum(cache.values())+cost>r['budget']:
     old,v=cache.popitem(last=False);events.append(dict(type='evict',scope=old[0],prefix=list(old[1]),bytes=v))
    cache[key]=cost;copied+=cost;events.append(dict(type='admit',scope=key[0],prefix=list(key[1]),bytes=cost))
   assert events==r['events'] and copied==r['explicit_copy_bytes'];assert sum(cache.values())==r['resident_bytes']<=r['budget']
   assert r['projected_rows']==6*(12-cut) and r['fresh_rows']==72
   error=float(np.max(np.abs(arrays[r['key']+'_fresh']-arrays[r['key']+'_reuse'])));assert error==r['max_error'] and error<1e-12
   total['fresh_rows']+=72;total['projected_rows']+=r['projected_rows'];total['hits']+=int(cut>0);total['evictions']+=sum(e['type']=='evict' for e in events);total['copy_bytes']+=copied
  totals.append(total)
 return dict(valid=True,requests=324,arrays=648,traces=27,max_error=max(r['max_error'] for r in rows),totals=totals)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('out',type=Path);o=p.parse_args().out;r=audit(o);(o/'audit.json').write_text(json.dumps(r,indent=2)+'\n',encoding='utf8');print({k:v for k,v in r.items() if k!='totals'})
