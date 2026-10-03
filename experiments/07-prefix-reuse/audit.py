"""Independent metric/cut/operation audit; no Store import."""
import argparse,hashlib,json
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parents[2]
EXPECTED=dict(append=8,**{'edit-first':0,'edit-middle':4,'edit-last':7,'shorter':3,'branch-two':8,'different-scope':0,'window-edit':0,'window-append':8})
def audit(out):
    m=json.loads((out/'manifest.json').read_text());rows=json.loads((out/'results.json').read_text());a=np.load(out/'arrays.npz',allow_pickle=False)
    for p,h in m['sources'].items():assert hashlib.sha256((ROOT/p).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==h,p
    for p,h in m['files'].items():assert hashlib.sha256((out/p).read_bytes()).hexdigest()==h,p
    assert len(rows)==27 and len(a.files)==81
    assert {(r['seed'],r['condition']) for r in rows}=={(s,c) for s in (7,11,23) for c in EXPECTED}
    for r in rows:
        k=f"{r['seed']}__{r['condition']}";fresh=a[k+'__fresh'];assert np.isfinite(fresh).all()
        assert r['cut']==EXPECTED[r['condition']]
        assert fresh.shape==(len(r['tokens'])-r['cut'],16)
        assert r['projected_rows']==6*(len(r['tokens'])-r['cut'])
        for field,kind in [('max_error','reuse'),('wrong_error','wrong')]:
            value=float(np.max(np.abs(fresh-a[k+'__'+kind])));assert value==r[field]
        assert r['max_error']<=1e-12
        if r['condition'] in ('edit-middle','edit-last','shorter'):assert r['wrong_error']>1e-6
        else:assert r['wrong_error']<=1e-12
    return dict(valid=True,cases=27,arrays=81,recomputed_errors=54,partial_position_failures=9,max_error=max(r['max_error'] for r in rows))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);out=p.parse_args().out;r=audit(out);(out/'audit.json').write_text(json.dumps(r,indent=2)+'\n');print(r)
