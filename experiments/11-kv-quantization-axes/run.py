import argparse,hashlib,json,pathlib,platform,subprocess,sys
import numpy as np
from quantization import pack,unpack,attention
H=pathlib.Path(__file__).resolve().parent; ROOT=H.parents[1]
P=json.loads((H/'protocol.json').read_text())

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out',type=pathlib.Path,default=H/'evidence');args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    rows=[]; arrays={}
    for seed in P['seeds']:
        for n in P['lengths']:
            rng=np.random.default_rng(seed*10000+n)
            q=rng.normal(size=(P['queries'],P['d'])).astype(np.float32)
            basek=rng.normal(size=(n,P['d'])).astype(np.float32);basev=rng.normal(size=(n,P['d'])).astype(np.float32)
            for condition in P['conditions']:
                k=basek.copy();v=basev.copy()
                if condition=='key_channel_outlier': k[:,0]*=P['outlier_factor']
                if condition=='value_token_outlier': v[0,:]*=P['outlier_factor']
                name=f's{seed}_n{n}_{condition}';ref=attention(q,k,v)
                arrays.update({name+'_q':q,name+'_k':k,name+'_v':v,name+'_ref':ref})
                for method,(ka,va) in P['methods'].items():
                    kp=pack(k,ka);vp=pack(v,va)
                    for target in P['targets']:
                        usek='K' in target;usev='V' in target
                        output=attention(q,unpack(kp) if usek else k,unpack(vp) if usev else v)
                        prefix=name+'_'+method+'_'+target
                        arrays[prefix+'_out']=output
                        for component,pair,use in [('k',kp,usek),('v',vp,usev)]:
                            if use: arrays[prefix+'_'+component+'_codes']=pair[0];arrays[prefix+'_'+component+'_scale']=pair[1]
                        size=(sum(a.nbytes for a in kp) if usek else k.nbytes)+(sum(a.nbytes for a in vp) if usev else v.nbytes)
                        rows.append(dict(id=prefix,input=name,seed=seed,n=n,condition=condition,method=method,target=target,max_abs=float(np.max(np.abs(output-ref))),relative_l2=float(np.linalg.norm(output-ref)/np.linalg.norm(ref)),payload_bytes=size,baseline_bytes=k.nbytes+v.nbytes))
    np.savez_compressed(args.out/'arrays.npz',**arrays)
    (args.out/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
    sources={p.name:hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for p in H.iterdir() if p.suffix in ('.py','.json')}
    files={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [args.out/'results.json',args.out/'arrays.npz']}
    manifest=dict(protocol=P,source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),platform=platform.platform(),python=sys.version,numpy=np.__version__,sources=sources,files=files)
    (args.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'{len(rows)} comparisons saved to {args.out}')
if __name__=='__main__':main()
