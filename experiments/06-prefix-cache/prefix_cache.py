"""Bounded in-process prefix reuse over the existing educational decoder."""
import hashlib,importlib.util,json
from pathlib import Path
import numpy as np
SOURCE=Path(__file__).resolve().parent.parent/'05-attention-lab/attention.py'
spec=importlib.util.spec_from_file_location('attention_reference',SOURCE)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
Decoder=module.Decoder

def signature(model, window):
    h=hashlib.sha256()
    h.update(SOURCE.read_bytes().replace(b'\r\n',b'\n'))
    h.update(json.dumps(dict(d=model.d,layers=model.layers,window=window,position='absolute-sinusoidal-v1'),sort_keys=True).encode())
    for array in [model.embedding]+[w for layer in model.weights for w in layer]:
        h.update(str((array.dtype.str,array.shape)).encode());h.update(array.tobytes())
    return h.hexdigest()

class PrefixStore:
    def __init__(self, capacity=2):
        if type(capacity) is not int or capacity<1:raise ValueError('capacity')
        self.capacity=capacity;self.entries=[]

    def remember(self, scope, model, tokens, window=None):
        if not isinstance(scope,str) or not scope:raise ValueError('scope')
        # Always compute from owned inputs; callers cannot inject an arbitrary cache.
        _,cache=model.chunk(tokens,window=window)
        entry=(scope,signature(model,window),tuple(int(x) for x in tokens),cache)
        self.entries=[e for e in self.entries if e[:3]!=entry[:3]]
        self.entries.append(entry);self.entries=self.entries[-self.capacity:]

    def continue_from(self, scope, model, prefix, suffix, window=None):
        if not isinstance(scope,str) or not scope:raise ValueError('scope')
        # Validation occurs even on cache hits (floats must not alias integer IDs).
        model.inputs(prefix,np.arange(len(prefix)))
        model.inputs(suffix,np.arange(len(prefix),len(prefix)+len(suffix)))
        wanted=(scope,signature(model,window),tuple(int(x) for x in prefix))
        match=next((e for e in reversed(self.entries) if e[:3]==wanted),None)
        model.reset()
        if match:
            out,_=model.chunk(suffix,cache=match[3],window=window)
            return out,dict(reused=True,projected_rows=model.projected_rows)
        out,_=model.chunk(list(prefix)+list(suffix),window=window)
        return out[-len(suffix):],dict(reused=False,projected_rows=model.projected_rows)
