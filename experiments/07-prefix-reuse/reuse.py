"""Longest identical token-prefix reuse in a small, untrained decoder."""
import importlib.util
from pathlib import Path
import numpy as np
P=Path(__file__).resolve().parent.parent/'06-prefix-cache/prefix_cache.py'
spec=importlib.util.spec_from_file_location('prefix_reference',P)
ref=importlib.util.module_from_spec(spec);spec.loader.exec_module(ref)
Decoder,signature=ref.Decoder,ref.signature

def common(a,b):
    n=0
    for x,y in zip(a,b):
        if x!=y:break
        n+=1
    return n

class Store:
    def __init__(self,capacity=2):
        if type(capacity) is not int or capacity<1:raise ValueError('capacity')
        self.capacity=capacity;self.entries=[]
    def remember(self,scope,model,tokens,window=None):
        if not isinstance(scope,str) or not scope:raise ValueError('scope')
        _,cache=model.chunk(tokens,window=window)
        key=(scope,signature(model,window),tuple(int(x) for x in tokens))
        self.entries=[e for e in self.entries if e[:3]!=key]
        self.entries.append((*key,cache));self.entries=self.entries[-self.capacity:]
    def query(self,scope,model,tokens,window=None,wrong_position=False):
        if not isinstance(scope,str) or not scope:raise ValueError('scope')
        model.inputs(tokens,np.arange(len(tokens)))
        identity=signature(model,window);cut=0;best=None
        for e in reversed(self.entries):
            if e[:2]!=(scope,identity):continue
            k=min(common(e[2],tokens),len(tokens)-1)
            # An evicted sliding cache cannot reconstruct an arbitrary old prefix.
            if window is not None and k!=len(e[2]):k=0
            if k>cut:cut,best=k,e
        cache=None
        if best is not None:
            if window is None:
                cache={'next_position':best[3]['next_position'] if wrong_position else cut,
                       'layers':[(k[:cut].copy(),v[:cut].copy(),p[:cut].copy()) for k,v,p in best[3]['layers']]}
            else:
                cache={'next_position':cut,'layers':[(k.copy(),v.copy(),p.copy()) for k,v,p in best[3]['layers']]}
        model.reset();out,_=model.chunk(tokens[cut:],cache=cache,window=window)
        return out,dict(cut=cut,projected_rows=model.projected_rows)
