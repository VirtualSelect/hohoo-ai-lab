"""Axis-explicit INT8 teaching implementation; not a production KV-cache kernel."""
import numpy as np

def pack(x, axis):
    if axis not in ('tensor','row','channel') or x.ndim != 2 or not np.isfinite(x).all():
        raise ValueError('finite matrix and tensor/row/channel axis required')
    reduction={'tensor':None,'row':1,'channel':0}[axis]
    scale=np.max(np.abs(x),axis=reduction,keepdims=True)/127
    scale=np.where(scale==0,1,scale).astype(np.float32)
    codes=np.clip(np.rint(x/scale),-127,127).astype(np.int8)
    return codes,scale

def unpack(pair): return pair[0].astype(np.float64)*pair[1]

def attention(q,k,v):
    score=q.astype(np.float64)@k.astype(np.float64).T/np.sqrt(q.shape[1])
    weights=np.exp(score-score.max(axis=1,keepdims=True)); weights/=weights.sum(axis=1,keepdims=True)
    return weights@v.astype(np.float64)
