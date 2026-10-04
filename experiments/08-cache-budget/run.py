import argparse,hashlib,json,platform,subprocess
from pathlib import Path
import numpy as np
from cache import Store,Decoder
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1];P=json.loads((HERE/'protocol.json').read_text('utf8'))
def save(p,v):p.write_text(json.dumps(v,indent=2,allow_nan=False)+'\n',encoding='utf8')
def trace(name):
 for i in range(P['requests']):
  prefix=list(range(1,9));scope='owner'
  if name=='alternating-branches' and i%2:prefix=list(range(13,21))
  if name=='three-scopes':scope='owner-'+str(i%3)
  yield scope,prefix+[21,22,23,24+i%6]
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);out=p.parse_args().out;out.mkdir(parents=True,exist_ok=False);rows=[];arrays={}
 for seed in P['seeds']:
  for budget in P['budgets']:
   for name in P['traces']:
    model=Decoder(seed);store=Store(budget)
    for i,(scope,tokens) in enumerate(trace(name)):
     model.reset();fresh,_=model.full(tokens);full_rows=model.projected_rows;full_scores=model.score_elements
     reuse,info=store.query(scope,model,tokens);key=f'{seed}_{budget}_{name}_{i}'
     arrays[key+'_fresh']=fresh[info['cut']:];arrays[key+'_reuse']=reuse
     rows.append(dict(key=key,seed=seed,budget=budget,trace=name,index=i,scope=scope,tokens=tokens,fresh_rows=full_rows,fresh_scores=full_scores,max_error=float(np.max(np.abs(fresh[info['cut']:]-reuse))),**info))
 np.savez_compressed(out/'arrays.npz',**arrays);save(out/'results.json',rows)
 sources=list(HERE.glob('*.py'))+[HERE/'protocol.json',HERE.parent/'05-attention-lab/attention.py',HERE.parent/'06-prefix-cache/prefix_cache.py']
 save(out/'manifest.json',dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),python=platform.python_version(),numpy=np.__version__,protocol=P,sources={s.relative_to(ROOT).as_posix():hashlib.sha256(s.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for s in sources},files={n:hashlib.sha256((out/n).read_bytes()).hexdigest() for n in ('arrays.npz','results.json')}))
 print(dict(requests=len(rows),arrays=len(arrays),max_error=max(r['max_error'] for r in rows)))
if __name__=='__main__':main()
