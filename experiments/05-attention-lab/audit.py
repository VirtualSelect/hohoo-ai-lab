"""Artifact audit uses stored arrays, not the decoder implementation."""
import hashlib,json,sys
from pathlib import Path
import numpy as np

root=Path(sys.argv[1]);manifest=json.loads((root/'manifest.json').read_text());p=manifest['protocol']
for name,want in json.loads((root/'sha256.json').read_text()).items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest()==want,name
here=Path(__file__).resolve().parent
for name,want in manifest['sources'].items():
    assert hashlib.sha256((here/name).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==want,name
a=np.load(root/'arrays.npz',allow_pickle=False);r=json.loads((root/'results.json').read_text());n,d,L=p['length'],p['dimension'],p['layers']
pairs={'causal_future_error':('full','masked',12),'unmasked_future_change':('unmasked','perturbed_unmasked',12),
    'prefix_error':('prefix','full',None),'cache_error':('cached','full',None),'chunk_error':('chunked','full',None),
    'wrong_chunk_mask_error':('wrong','good',None),'sliding_error':('sliding_cached','sliding',None),
    'reset_position_error':('reset','sliding',None),'cropped_recompute_error':('cropped','sliding',None)}
assert len(r)==len(p['seeds']) and [x['seed'] for x in r]==p['seeds']
for row in r:
    prefix='s'+str(row['seed'])+'_'
    for metric,(left,right,end) in pairs.items():
        measured=float(np.max(np.abs(a[prefix+left][:end]-a[prefix+right][:end])))
        assert abs(row[metric]-measured)<1e-15,(metric,row[metric],measured)
    att=a[prefix+'attention'];assert np.count_nonzero(np.triu(att,1))==0
    np.testing.assert_allclose(att.sum(axis=1),np.ones(n),atol=1e-12)
    assert row['prefix_projected_rows']==3*L*n*(n+1)//2
    assert row['cached_projected_rows']==3*L*n
    assert row['prefix_score_elements']==L*sum(i*i for i in range(1,n+1))
    assert row['cached_score_elements']==L*n*(n+1)//2
    assert row['full_kv_bytes']==2*L*n*d*8
    assert row['window_kv_bytes']==2*L*p['window']*d*8
    for key in ('causal_future_error','prefix_error','cache_error','chunk_error','sliding_error'):assert row[key]<p['tolerance']
print(json.dumps({'seeds':len(r),'array_entries':len(a.files),'metrics_recomputed':len(r)*len(pairs),'passed':True}))
