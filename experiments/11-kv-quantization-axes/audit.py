"""Recompute from archived integer codes/scales, without importing implementation."""
import hashlib,json,pathlib,sys
import numpy as np
H=pathlib.Path(__file__).resolve().parent;D=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else H/'evidence'
M=json.loads((D/'manifest.json').read_text());rows=json.loads((D/'results.json').read_text())
for name,sha in M['sources'].items():assert hashlib.sha256((H/name).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==sha,name
for name,sha in M['files'].items():assert hashlib.sha256((D/name).read_bytes()).hexdigest()==sha,name
def calculate(q,k,v):
    s=np.einsum('qd,nd->qn',q.astype('float64'),k.astype('float64'))/np.sqrt(q.shape[1]);s-=np.max(s,axis=1)[:,None]
    p=np.exp(s);p/=np.sum(p,axis=1)[:,None]
    return np.einsum('qn,nd->qd',p,v.astype('float64'))
assert len(rows)==162 and len({r['id'] for r in rows})==162
with np.load(D/'arrays.npz',allow_pickle=False) as z:
    for r in rows:
        name=r['input'];prefix=r['id'];q=z[name+'_q'];k=z[name+'_k'];v=z[name+'_v'];ref=calculate(q,k,v)
        np.testing.assert_allclose(ref,z[name+'_ref'],rtol=1e-12,atol=1e-12)
        reconstructed=[];size=0
        for component,original,axis in [('k',k,M['protocol']['methods'][r['method']][0]),('v',v,M['protocol']['methods'][r['method']][1])]:
            if component.upper() in r['target']:
                code=z[prefix+'_'+component+'_codes'];scale=z[prefix+'_'+component+'_scale']
                expected_shape={'tensor':(1,1),'row':(r['n'],1),'channel':(1,32)}[axis]
                assert code.dtype==np.int8 and scale.dtype==np.float32 and scale.shape==expected_shape
                restored=code.astype('float64')*scale
                assert np.all(np.abs(restored-original)<=scale.astype('float64')*.5001+1e-6)
                reconstructed.append(restored);size+=code.nbytes+scale.nbytes
            else: reconstructed.append(original);size+=original.nbytes
        out=calculate(q,*reconstructed);np.testing.assert_allclose(out,z[prefix+'_out'],rtol=1e-12,atol=1e-12)
        assert np.isclose(np.max(np.abs(out-ref)),r['max_abs'],rtol=1e-10,atol=1e-12)
        assert np.isclose(np.linalg.norm(out-ref)/np.linalg.norm(ref),r['relative_l2'],rtol=1e-10,atol=1e-12)
        assert size==r['payload_bytes'] and k.nbytes+v.nbytes==r['baseline_bytes']
print('PASS: 162 archived comparisons, reconstructed outputs, quantization bounds, scale overhead and hashes')
