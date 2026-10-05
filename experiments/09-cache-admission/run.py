import argparse,hashlib,json,platform,subprocess,time,random,itertools,os
from pathlib import Path
import numpy as np
from cache import Store,Decoder
H=Path(__file__).resolve().parent;R=H.parents[1];P=json.loads((H/'protocol.json').read_text('utf8'))
def save(p,v):p.write_text(json.dumps(v,indent=2,allow_nan=False)+'\n',encoding='utf8')
def trace(name):
 for i in range(P['requests']):
  prefix=list(range(13,21)) if name=='alternating-branches' and i%2 else list(range(1,9))
  yield 'owner-'+str(i%3) if name=='three-scopes' else 'owner',prefix+[21,22,23,24+i%6]
def execute(config,repeat,order,arrays):
 seed,budget,name,policy=config;model=Decoder(seed);store=Store(budget,policy=policy);rows=[]
 for i,(scope,tokens) in enumerate(trace(name)):
  t=time.perf_counter_ns();states,info=store.query(scope,model,tokens);elapsed=time.perf_counter_ns()-t
  fresh,_=model.full(tokens);error=float(np.max(np.abs(states-fresh[info['cut']:])));assert error<P['tolerance']
  key=f'{seed}_{budget}_{name}_{policy}_{i}'
  if repeat==0:arrays[key+'_fresh']=fresh[info['cut']:];arrays[key+'_reuse']=states
  rows.append(dict(key=key,repeat=repeat,order=order,seed=seed,budget=budget,trace=name,policy=policy,index=i,scope=scope,tokens=tokens,elapsed_ns=elapsed,max_error=error,**info))
 return rows
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);out=p.parse_args().out;out.mkdir(parents=True,exist_ok=False)
 configs=list(itertools.product(P['seeds'],P['budgets'],P['traces'],P['policies']));rng=random.Random(P['order_seed']);warm=configs[:];rng.shuffle(warm)
 for i,c in enumerate(warm):execute(c,-1,i,{})
 rows=[];arrays={};order=0
 for repeat in range(P['repeats']):
  block=configs[:];rng.shuffle(block)
  for c in block:rows.extend(execute(c,repeat,order,arrays));order+=1
 np.savez_compressed(out/'arrays.npz',**arrays);save(out/'results.json',rows)
 sources=list(H.glob('*.py'))+[H/'protocol.json',H.parent/'05-attention-lab/attention.py',H.parent/'06-prefix-cache/prefix_cache.py']
 save(out/'manifest.json',dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),python=platform.python_version(),numpy=np.__version__,platform=platform.platform(),processor=platform.processor(),timer=time.get_clock_info('perf_counter').__dict__,thread_environment={k:os.environ.get(k) for k in ['OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS']},numpy_config=np.__config__.CONFIG,protocol=P,warmup_requests=len(configs)*12,sources={p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for p in sources},files={n:hashlib.sha256((out/n).read_bytes()).hexdigest() for n in ['results.json','arrays.npz']}))
 print(dict(measured_requests=len(rows),saved_arrays=len(arrays),max_error=max(x['max_error'] for x in rows)))
if __name__=='__main__':main()
