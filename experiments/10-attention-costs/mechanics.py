"""NumPy teaching kernels, NOT FlashAttention or KIVI implementations."""
import numpy as np
def dense(q,k,v):
 s=q@k.T/np.sqrt(q.shape[1]);p=np.exp(s-s.max(axis=1,keepdims=True));return p/p.sum(axis=1,keepdims=True)@v
def tiled(q,k,v,block=32):
 if block<1:raise ValueError('positive block required')
 m=np.full((len(q),1),-np.inf);l=np.zeros_like(m);acc=np.zeros((len(q),v.shape[1]))
 for start in range(0,len(k),block):
  scores=q@k[start:start+block].T/np.sqrt(q.shape[1]);new=np.maximum(m,scores.max(axis=1,keepdims=True));rescale=np.exp(m-new);p=np.exp(scores-new)
  acc=acc*rescale+p@v[start:start+block];l=l*rescale+p.sum(axis=1,keepdims=True);m=new
 return acc/l
def wrong_tile_average(q,k,v,block):
 return np.mean([dense(q,k[i:i+block],v[i:i+block]) for i in range(0,len(k),block)],axis=0)
def quantize(x,mode):
 axis=None if mode=='tensor' else 1
 scale=np.max(np.abs(x),axis=axis,keepdims=True)/127
 scale=np.where(scale==0,1.,scale).astype(np.float32)
 return np.clip(np.rint(x/scale),-127,127).astype(np.int8),scale
def restore(pair):return pair[0].astype(np.float64)*pair[1]
