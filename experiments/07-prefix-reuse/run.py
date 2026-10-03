import argparse,hashlib,json,platform,subprocess
from pathlib import Path
import numpy as np
from reuse import Store,Decoder
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
P=json.loads((HERE/'protocol.json').read_text('utf8'))
def save(p,v):p.write_text(json.dumps(v,indent=2,allow_nan=False)+'\n',encoding='utf8')
def case(name):
    a=list(range(1,9));b=[1,2,3,4,5,20,21,22];q=list(range(1,13));scope='owner';window=None
    if name=='edit-first':q[0]=20
    if name in ('edit-middle','window-edit'):q[4]=20
    if name=='edit-last':q[7]=22
    if name=='shorter':q=q[:4]
    if name=='branch-two':q=b+[9,10,11,12]
    if name=='different-scope':scope='another-owner'
    if name.startswith('window-'):window=4
    return a,b,q,scope,window
def main():
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);out=p.parse_args().out;out.mkdir(parents=True,exist_ok=False)
    rows=[];arrays={}
    for seed in P['seeds']:
        for name in P['conditions']:
            a,b,q,scope,window=case(name);model=Decoder(seed);store=Store(2)
            store.remember('owner',model,a,window);store.remember('owner',model,b,window)
            full,_=model.full(q,window=window)
            right,info=store.query(scope,model,q,window)
            wrong,bad=store.query(scope,model,q,window,wrong_position=True)
            reference=full[info['cut']:];key=f'{seed}__{name}'
            for kind,value in [('fresh',reference),('reuse',right),('wrong',wrong)]:arrays[key+'__'+kind]=value
            rows.append(dict(seed=seed,condition=name,tokens=q,scope=scope,window=window,**info,
                fresh_projected_rows=6*len(q),max_error=float(np.max(np.abs(reference-right))),wrong_error=float(np.max(np.abs(reference-wrong)))))
    np.savez_compressed(out/'arrays.npz',**arrays);save(out/'results.json',rows)
    sources=list(HERE.glob('*.py'))+[HERE/'protocol.json',HERE.parent/'05-attention-lab/attention.py',HERE.parent/'06-prefix-cache/prefix_cache.py']
    save(out/'manifest.json',dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),python=platform.python_version(),numpy=np.__version__,
        sources={s.relative_to(ROOT).as_posix():hashlib.sha256(s.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for s in sources},
        files={n:hashlib.sha256((out/n).read_bytes()).hexdigest() for n in ('arrays.npz','results.json')}))
    print(json.dumps(dict(cases=len(rows),max_error=max(r['max_error'] for r in rows),wrong_max=max(r['wrong_error'] for r in rows))))
if __name__=='__main__':main()
