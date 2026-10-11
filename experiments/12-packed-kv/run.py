import argparse,hashlib,json,pathlib,platform,subprocess,sys,time
import numpy as np
from core import encode,decode,payload,attention,block_attention
H=pathlib.Path(__file__).resolve().parent;ROOT=H.parents[1]
P=json.loads((H/'protocol.json').read_text())

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out',type=pathlib.Path,required=True);args=parser.parse_args()
    args.out.mkdir(parents=True,exist_ok=False);arrays={};rows=[];times=[]
    def save(name,p):
        for key in ('data','scale','shape'):arrays[name+'_'+key]=p[key]
    for seed in P['seeds']:
      for n in P['lengths']:
        rng=np.random.default_rng(seed*10000+n)
        baseq=rng.normal(size=(P['queries'],P['d'])).astype(np.float32)
        basek=rng.normal(size=(n,P['d'])).astype(np.float32);v=rng.normal(size=(n,P['d'])).astype(np.float32)
        for condition in P['conditions']:
            q=baseq.copy();k=basek.copy()
            if condition!='clean':
                q[:,0]=6;k[:,0]=0
                k[slice(0,16) if condition=='early_focus' else slice(n-16,n),0]=4
            name=f's{seed}_n{n}_{condition}';reference,weights=attention(q,k,v)
            arrays.update({name+'_q':q,name+'_k':k,name+'_v':v,name+'_reference':reference})
            for group in P['groups']:
              for bits in P['bits']:
                kp=encode(k,group,bits,True);vp=encode(v,group,bits)
                prefix=f'{name}_g{group}_b{bits}';save(prefix+'_k',kp);save(prefix+'_v',vp)
                output,_=attention(q,decode(kp),decode(vp));arrays[prefix+'_out']=output
                rows.append(dict(section='groups',id=prefix,input=name,n=n,condition=condition,group=group,bits=bits,relative_l2=float(np.linalg.norm(output-reference)/np.linalg.norm(reference)),bytes=payload(kp)+payload(vp),baseline_bytes=k.nbytes+v.nbytes))
            for residual in P['residual_tokens']:
                cut=n-residual;kp=encode(k[:cut],32,4,True);vp=encode(v[:cut],32,4)
                output,_=attention(q,np.concatenate((decode(kp),k[cut:])),np.concatenate((decode(vp),v[cut:])))
                prefix=f'{name}_r{residual}';save(prefix+'_k',kp);save(prefix+'_v',vp);arrays[prefix+'_out']=output
                rows.append(dict(section='residual',id=prefix,input=name,n=n,condition=condition,residual=residual,attention_mass=float(weights[:,cut:].sum(axis=1).mean()),relative_l2=float(np.linalg.norm(output-reference)/np.linalg.norm(reference)),bytes=payload(kp)+payload(vp)+k[cut:].nbytes+v[cut:].nbytes,baseline_bytes=k.nbytes+v.nbytes))
            blocks=[(encode(k[i:i+32],32,4,True),encode(v[i:i+32],32,4)) for i in range(0,n,32)]
            packed_k=encode(k,32,4,True);packed_v=encode(v,32,4)
            dense=lambda:attention(q,decode(packed_k),decode(packed_v))[0]
            tiled=lambda:block_attention(q,blocks)
            full=lambda:attention(q,k,v)[0]
            a=dense();b=tiled();assert np.max(np.abs(a-b))<P['tolerance']
            arrays[name+'_blocked']=b
            rows.append(dict(section='timing',input=name,n=n,condition=condition,equivalence_max_abs=float(np.max(np.abs(a-b))),dense_decoded_kv_bytes=2*n*P['d']*4,block_decoded_kv_bytes=2*32*P['d']*4,dense_score_bytes=P['queries']*n*8,block_score_bytes=P['queries']*32*8))
            funcs={'float32_cache':full,'decode_dense':dense,'decode_blocks':tiled}
            for _ in range(P['warmups']):
                for fn in funcs.values():fn()
            for repeat in range(P['timing_rounds']):
                # Rotate order, retain every raw observation; no best-of selection.
                keys=list(funcs);keys=keys[repeat%3:]+keys[:repeat%3]
                for method in keys:
                    start=time.perf_counter_ns();funcs[method]();elapsed=time.perf_counter_ns()-start
                    times.append(dict(input=name,n=n,method=method,round=repeat,nanoseconds=elapsed))
    np.savez_compressed(args.out/'arrays.npz',**arrays)
    for file,obj in [('results.json',rows),('timings.json',times)]:
        (args.out/file).write_text(json.dumps(obj,indent=2)+'\n')
    sources={p.name:hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for p in H.iterdir() if p.suffix in ('.py','.json','.txt')}
    files={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in args.out.iterdir()}
    manifest=dict(protocol=P,source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),platform=platform.platform(),python=sys.version,numpy=np.__version__,sources=sources,files=files)
    (args.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(f'{len(rows)} comparisons; {len(times)} timings')
if __name__=='__main__':main()
