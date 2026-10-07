import argparse,hashlib,importlib.util,json,os,platform,random,subprocess,time
from pathlib import Path
import numpy as np
from mechanics import dense,tiled,wrong_tile_average,quantize,restore
H=Path(__file__).resolve().parent;R=H.parents[1];P=json.loads((H/'protocol.json').read_text())
spec=importlib.util.spec_from_file_location('cache_reference',H.parent/'06-prefix-cache/prefix_cache.py');ref=importlib.util.module_from_spec(spec);spec.loader.exec_module(ref)
def sha(p):return hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
def timed(f):
 t=time.perf_counter_ns();value=f();return value,time.perf_counter_ns()-t
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);out=p.parse_args().out;out.mkdir(parents=True,exist_ok=False)
 arrays={};rows=[];timer=[];rng_order=random.Random(107)
 for seed in P['seeds']:
  rng=np.random.default_rng(seed)
  for n in P['lengths']:
   q=rng.normal(size=(8,32));k=rng.normal(size=(n,32));v=rng.normal(size=(n,32));key=f'{seed}_{n}';a=dense(q,k,v);b=tiled(q,k,v);bad=wrong_tile_average(q,k,v,32)
   for name,value in [('q',q),('k',k),('v',v),('dense',a),('tiled',b),('bad',bad)]:arrays[key+'_'+name]=value
   rows.append(dict(kind='tile',key=key,seed=seed,n=n,error=float(np.max(abs(a-b))),negative_error=float(np.max(abs(a-bad))),dense_score_bytes=8*n*8,tile_score_bytes=8*32*8))
   funcs={'dense':lambda:dense(q,k,v),'tiled':lambda:tiled(q,k,v)}
   for _ in range(P['warmup']):
    for f in funcs.values():f()
   for rep in range(P['timing_repeats']):
    order=list(funcs);rng_order.shuffle(order)
    for mode in order:_,ns=timed(funcs[mode]);timer.append(dict(kind='tile',key=key,repeat=rep,mode=mode,ns=ns))
   for outlier in [False,True]:
    kk=k.copy();vv=v.copy()
    if outlier:kk[0,0]*=40;vv[0,0]*=40
    base=dense(q,kk,vv);sub=key+('_outlier' if outlier else '_normal');arrays[sub+'_k']=kk;arrays[sub+'_v']=vv;arrays[sub+'_reference']=base
    for mode in ['tensor','row']:
     pk=quantize(kk,mode);pv=quantize(vv,mode);result=dense(q,restore(pk),restore(pv));prefix=sub+'_'+mode
     for name,value in [('kq',pk[0]),('ks',pk[1]),('vq',pv[0]),('vs',pv[1]),('out',result)]:arrays[prefix+'_'+name]=value
     rows.append(dict(kind='quant',key=prefix,source=sub,qkey=key+'_q',seed=seed,n=n,outlier=outlier,mode=mode,error=float(np.max(abs(result-base))),rmse=float(np.sqrt(np.mean((result-base)**2))),bytes=sum(x.nbytes for x in pk+pv),float32_bytes=(kk.size+vv.size)*4))
 # Isolated, always-hit continuation. This does not replace the prior mixed/cold workload.
 profile=[]
 for seed in P['seeds']:
  model=ref.Decoder(seed);tokens=list(range(1,13));_,cache=model.chunk(tokens[:8]);identity=ref.signature(model,None)
  for array in [model.embedding]+[w for layer in model.weights for w in layer]:array.flags.writeable=False
  store={('owner',identity,tuple(tokens[:8])):cache};expected=model.full(tokens)[0][-4:]
  def query(mode):
   start=time.perf_counter_ns();stages={}
   if mode=='full':result,stages['compute_ns']=timed(lambda:model.full(tokens)[0][-4:])
   else:
    sig,stages['signature_ns']=timed(lambda:ref.signature(model,None) if mode=='rehash' else identity)
    hit,stages['lookup_ns']=timed(lambda:store[('owner',sig,tuple(tokens[:8]))])
    copy,stages['copy_ns']=timed(lambda:dict(next_position=hit['next_position'],layers=[tuple(a.copy() for a in layer) for layer in hit['layers']]))
    result,stages['compute_ns']=timed(lambda:model.chunk(tokens[8:],cache=copy)[0])
   total=time.perf_counter_ns()-start;error=float(np.max(abs(result-expected)));assert error<1e-12
   return dict(mode=mode,total_ns=total,residual_ns=total-sum(stages.values()),error=error,**stages)
  for _ in range(5):
   for mode in ['full','rehash','frozen']:query(mode)
  for rep in range(P['profile_repeats']):
   order=['full','rehash','frozen'];rng_order.shuffle(order)
   for mode in order:profile.append(dict(seed=seed,repeat=rep,**query(mode)))
 np.savez_compressed(out/'arrays.npz',**arrays)
 for name,value in [('results.json',rows),('timings.json',timer),('profile.json',profile)]: (out/name).write_text(json.dumps(value,indent=2)+'\n')
 sources=list(H.glob('*.py'))+[H/'protocol.json',H.parent/'05-attention-lab/attention.py',H.parent/'06-prefix-cache/prefix_cache.py']
 manifest=dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),python=platform.python_version(),numpy=np.__version__,platform=platform.platform(),threads={k:os.environ.get(k) for k in ['OPENBLAS_NUM_THREADS','OMP_NUM_THREADS','MKL_NUM_THREADS']},numpy_config=np.__config__.CONFIG,protocol=P,sources={p.relative_to(R).as_posix():sha(p) for p in sources},files={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir()})
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(dict(comparisons=len(rows),timings=len(timer),profile_queries=len(profile),arrays=len(arrays)))
if __name__=='__main__':main()
