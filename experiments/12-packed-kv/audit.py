"""Recompute from packed bytes without importing core.py."""
import hashlib,json,pathlib,sys
import numpy as np
H=pathlib.Path(__file__).resolve().parent
def unpack(a,name,group,bits,transpose):
    n,d=map(int,a[name+'_shape']);rows,width=(d,n) if transpose else (n,d)
    data=a[name+'_data'];scale=a[name+'_scale'];limit=2**(bits-1)-1
    count=rows*((width+group-1)//group)*group
    codes=np.array([(int(data[i//2])>>(4*(i%2)))&15 for i in range(count)],dtype=np.int16) if bits==4 else data.astype(np.int16)
    assert np.all(codes<=2*limit)
    out=((codes-limit).reshape(rows,-1,group)*scale[...,None]).reshape(rows,-1)[:,:width].astype(np.float32)
    return out.T if transpose else out
def attention(q,k,v):
    scores=np.einsum('qd,nd->qn',q.astype(float),k.astype(float))/np.sqrt(k.shape[1])
    w=np.exp(scores-np.max(scores,axis=1)[:,None]);w/=w.sum(axis=1)[:,None]
    return np.einsum('qn,nd->qd',w,v.astype(float)),w
def main():
    root=pathlib.Path(sys.argv[1]);m=json.loads((root/'manifest.json').read_text())
    for f,h in m['sources'].items():assert hashlib.sha256((H/f).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==h,f
    for f,h in m['files'].items():assert hashlib.sha256((root/f).read_bytes()).hexdigest()==h,f
    a=np.load(root/'arrays.npz');rows=json.loads((root/'results.json').read_text());assert len(rows)==144
    for r in rows:
        name=r['input'];q,k,v=[a[name+'_'+key] for key in ('q','k','v')];ref,w=attention(q,k,v)
        np.testing.assert_allclose(ref,a[name+'_reference'],rtol=1e-10,atol=1e-10)
        if r['section']=='timing':
            np.testing.assert_allclose(a[name+'_blocked'],a[name+'_g32_b4_out'],rtol=1e-10,atol=1e-10)
            assert r['block_decoded_kv_bytes']==8192 and r['equivalence_max_abs']<1e-10
            continue
        bits=r.get('bits',4);group=r.get('group',32);prefix=r['id']
        dk=unpack(a,prefix+'_k',group,bits,True);dv=unpack(a,prefix+'_v',group,bits,False)
        size=sum(a[prefix+'_'+component+'_'+field].nbytes for component in ('k','v') for field in ('data','scale','shape'))
        if r['section']=='residual':
            cut=len(k)-r['residual'];dk=np.concatenate((dk,k[cut:]));dv=np.concatenate((dv,v[cut:]));size+=k[cut:].nbytes+v[cut:].nbytes
            assert abs(w[:,cut:].sum(axis=1).mean()-r['attention_mass'])<1e-12
        out,_=attention(q,dk,dv);np.testing.assert_allclose(out,a[prefix+'_out'],rtol=1e-10,atol=1e-10)
        assert abs(np.linalg.norm(out-ref)/np.linalg.norm(ref)-r['relative_l2'])<1e-10
        assert size==r['bytes'] and k.nbytes+v.nbytes==r['baseline_bytes']
    times=json.loads((root/'timings.json').read_text());assert len(times)==810
    assert all(t['nanoseconds']>0 for t in times)
    assert len({(t['input'],t['round'],t['method']) for t in times})==810
    print('PASS: 144 comparisons and 810 raw timing observations; packed bytes, scales, outputs and accounting verified')
if __name__=='__main__':main()
