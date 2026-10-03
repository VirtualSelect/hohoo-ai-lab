import argparse,hashlib,json
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parents[2]
def audit(out):
    read=lambda n:json.loads((out/n).read_text('utf8'))
    hashes=read('sha256.json')
    assert set(hashes)=={'arrays.npz','results.json','manifest.json'}
    for n,h in hashes.items():assert hashlib.sha256((out/n).read_bytes()).hexdigest()==h
    manifest=read('manifest.json');p=manifest['protocol'];rows=read('results.json')
    for n,h in manifest['sources'].items():assert hashlib.sha256((ROOT/n).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==h
    expected={(s,c) for s in p['seeds'] for c in p['cases']}
    assert len(rows)==len(expected) and {(r['seed'],r['case']) for r in rows}==expected
    with np.load(out/'arrays.npz',allow_pickle=False) as arrays:
        assert len(arrays.files)==3*len(rows)
        for r in rows:
            stem=f"{r['seed']}__{r['case']}__";ref=arrays[stem+'fresh']
            assert ref.shape==(4,16) and np.isfinite(ref).all()
            for name in ('unchecked','guarded'):
                x=arrays[stem+name];assert x.shape==ref.shape and np.isfinite(x).all()
                error=float(np.max(np.abs(x-ref)))
                assert abs(error-r[name+'_error'])<1e-15
                if name=='guarded':assert error<=p['tolerance']
            reuse=r['case'] in ('same-prefix','new-suffix')
            assert r['reused']==reuse and r['projected_rows']==(24 if reuse else 72)
    return dict(passed=True,cases=len(rows),arrays=3*len(rows),metrics_recomputed=2*len(rows))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);a=p.parse_args();result=audit(a.out)
    (a.out/'audit.json').write_text(json.dumps(result)+'\n',encoding='utf8',newline='\n');print(result)
