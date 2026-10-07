import argparse,hashlib,json
from pathlib import Path
import numpy as np
R=Path(__file__).resolve().parents[2]
def attention(q,k,v):
 scores=np.matmul(q,np.transpose(k))/np.sqrt(q.shape[1]);scores-=np.max(scores,axis=1)[:,None];weights=np.exp(scores);weights/=weights.sum(axis=1)[:,None];return np.matmul(weights,v)
p=argparse.ArgumentParser();p.add_argument('directory',type=Path);out=p.parse_args().directory;m=json.loads((out/'manifest.json').read_text());a=np.load(out/'arrays.npz');rows=json.loads((out/'results.json').read_text())
for name,h in m['sources'].items():assert hashlib.sha256((R/name).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==h,name
for name,h in m['files'].items():assert hashlib.sha256((out/name).read_bytes()).hexdigest()==h,name
for r in rows:
 key=r['key']
 if r['kind']=='tile':
  base=attention(a[key+'_q'],a[key+'_k'],a[key+'_v']);assert np.max(abs(base-a[key+'_dense']))<1e-12
  assert np.max(abs(base-a[key+'_tiled']))<1e-12
  assert abs(np.max(abs(base-a[key+'_bad']))-r['negative_error'])<1e-12
 else:
  base=attention(a[r['qkey']],a[r['source']+'_k'],a[r['source']+'_v']);got=attention(a[r['qkey']],a[key+'_kq'].astype(float)*a[key+'_ks'],a[key+'_vq'].astype(float)*a[key+'_vs'])
  assert np.max(abs(got-a[key+'_out']))<1e-12;assert abs(np.max(abs(got-base))-r['error'])<1e-12
  assert sum(a[key+'_'+k].nbytes for k in ['kq','ks','vq','vs'])==r['bytes']
profile=json.loads((out/'profile.json').read_text());assert len(profile)==909
for r in profile:
 stages=sum(v for k,v in r.items() if k.endswith('_ns') and k not in ['total_ns','residual_ns']);assert stages+r['residual_ns']==r['total_ns'];assert r['error']<1e-12
summary={mode:{k:float(np.median([r.get(k,0) for r in profile if r['mode']==mode]))/1000 for k in ['total_ns','signature_ns','lookup_ns','copy_ns','compute_ns','residual_ns']} for mode in ['full','rehash','frozen']}
print(json.dumps({'comparisons':len(rows),'profile_queries':len(profile),'profile_median_us':summary,'tile_max_error':max(r['error'] for r in rows if r['kind']=='tile'),'quant_max_error':max(r['error'] for r in rows if r['kind']=='quant')},indent=2))
