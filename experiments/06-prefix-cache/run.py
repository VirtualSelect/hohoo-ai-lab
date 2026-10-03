import argparse,hashlib,json,platform,subprocess
from datetime import datetime,timezone
from pathlib import Path
import numpy as np
from prefix_cache import Decoder,PrefixStore,SOURCE
HERE=Path(__file__).resolve().parent;P=json.loads((HERE/'protocol.json').read_text('utf8'))
def save(p,v):p.write_text(json.dumps(v,indent=2,allow_nan=False)+'\n',encoding='utf8',newline='\n')
def main():
    p=argparse.ArgumentParser();p.add_argument('--out',required=True,type=Path);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
    paths=list(HERE.glob('*.py'))+[HERE/'protocol.json',SOURCE]
    save(a.out/'manifest.json',dict(at=datetime.now(timezone.utc).isoformat(),python=platform.python_version(),numpy=np.__version__,protocol=P,
        commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=HERE,text=True).strip(),
        dirtyBeforeRun=bool(subprocess.check_output(['git','status','--porcelain'],cwd=HERE,text=True).strip()),
        sources={str(x.relative_to(HERE.parent.parent)).replace('\\','/'):hashlib.sha256(x.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for x in paths}))
    rows=[];arrays={}
    for seed in P['seeds']:
        origin=Decoder(seed);_,old=origin.chunk(P['prefix'])
        for case in P['cases']:
            prefix=P['prefix'].copy();suffix=P['suffix'].copy();scope='reader-a';window=None;model=Decoder(seed)
            if case=='edited-prefix':prefix[2]=20
            if case=='different-weights':model.weights[0][1][0,0]+=.5
            if case=='different-window':window=4
            if case=='different-scope':scope='reader-b'
            if case=='new-suffix':suffix=[20,21,22,23]
            store=PrefixStore();store.remember('reader-a',origin,P['prefix'])
            ref=model.full(prefix+suffix,window=window)[0][-len(suffix):]
            bad,_=model.chunk(suffix,cache=old,window=window)
            good,meta=store.continue_from(scope,model,prefix,suffix,window)
            stem=f'{seed}__{case}'
            for name,v in [('fresh',ref),('unchecked',bad),('guarded',good)]:arrays[stem+'__'+name]=v
            rows.append(dict(seed=seed,case=case,**meta,unchecked_error=float(abs(bad-ref).max()),guarded_error=float(abs(good-ref).max())))
    np.savez_compressed(a.out/'arrays.npz',**arrays);save(a.out/'results.json',rows)
    save(a.out/'sha256.json',{n:hashlib.sha256((a.out/n).read_bytes()).hexdigest() for n in ('arrays.npz','results.json','manifest.json')})
    print(json.dumps(rows),flush=True)
if __name__=='__main__':main()
