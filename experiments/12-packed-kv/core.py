"""Packed symmetric 4/8-bit KV teaching codec; NumPy CPU, not a fused kernel."""
import numpy as np

def encode(x, group, bits, transpose=False):
    x=np.asarray(x,dtype=np.float32)
    if x.ndim!=2 or not np.isfinite(x).all() or group not in (8,32) or bits not in (4,8):
        raise ValueError('finite matrix, group 8/32, bits 4/8 required')
    y=x.T if transpose else x
    count=(y.shape[1]+group-1)//group
    padded=np.pad(y,((0,0),(0,count*group-y.shape[1]))).reshape(y.shape[0],count,group)
    limit=2**(bits-1)-1
    scale=np.max(np.abs(padded),axis=2)/limit
    scale=np.where(scale==0,1,scale).astype(np.float32)
    signed=np.clip(np.rint(padded/scale[:,:,None]),-limit,limit).astype(np.int16)
    codes=(signed+limit).astype(np.uint8).ravel()
    if bits==4:
        codes=np.pad(codes,(0,len(codes)%2));codes=codes[::2] | (codes[1::2]<<4)
    return dict(data=codes,scale=scale,shape=np.array(x.shape,dtype=np.int32),bits=bits,group=group,transpose=transpose)

def decode(p):
    bits=p['bits'];group=p['group'];n,d=map(int,p['shape']);rows,width=(d,n) if p['transpose'] else (n,d)
    codes=p['data']
    if bits==4:codes=np.column_stack((codes&15,codes>>4)).ravel()
    values=(codes.astype(np.int16)-(2**(bits-1)-1)).reshape(rows,-1,group)*p['scale'][:,:,None]
    values=values.reshape(rows,-1)[:,:width].astype(np.float32)
    return values.T if p['transpose'] else values

def payload(p):return p['data'].nbytes+p['scale'].nbytes+p['shape'].nbytes

def attention(q,k,v):
    score=q.astype(np.float64)@k.astype(np.float64).T/np.sqrt(q.shape[1])
    weights=np.exp(score-score.max(axis=1,keepdims=True));weights/=weights.sum(axis=1,keepdims=True)
    return weights@v.astype(np.float64),weights

def block_attention(q,blocks):
    """Merge independently decoded blocks with running softmax statistics."""
    q=q.astype(np.float64);m=np.full(len(q),-np.inf);z=np.zeros(len(q));numerator=np.zeros_like(q)
    for kp,vp in blocks:
        k,v=decode(kp),decode(vp)
        score=q@k.astype(np.float64).T/np.sqrt(q.shape[1]);next_m=np.maximum(m,score.max(axis=1))
        prior=np.exp(m-next_m);p=np.exp(score-next_m[:,None])
        numerator=prior[:,None]*numerator+p@v.astype(np.float64);z=prior*z+p.sum(axis=1);m=next_m
    return numerator/z[:,None]
