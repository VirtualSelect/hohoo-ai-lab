"""Byte-bounded, full-prefix checkpoints. Not shared pages or a production KV engine."""
import importlib.util
from collections import OrderedDict
from pathlib import Path
import numpy as np
p=Path(__file__).resolve().parent.parent/'06-prefix-cache/prefix_cache.py'
s=importlib.util.spec_from_file_location('l7_reference',p);ref=importlib.util.module_from_spec(s);s.loader.exec_module(ref)
Decoder,signature=ref.Decoder,ref.signature
def size(cache):return sum(a.nbytes for layer in cache['layers'] for a in layer)
def copy(cache):return dict(next_position=cache['next_position'],layers=[tuple(a.copy() for a in layer) for layer in cache['layers']])
class Store:
 def __init__(self,budget,stride=4):
  if type(budget) is not int or budget<0 or type(stride) is not int or stride<1:raise ValueError('budget/stride')
  self.budget,self.stride=budget,stride;self.entries=OrderedDict();self.bytes=0
 def query(self,scope,model,tokens):
  if not isinstance(scope,str) or not scope:raise ValueError('scope')
  model.inputs(tokens,np.arange(len(tokens)));identity=signature(model,None);cut=0;best=None;events=[];copied=0
  for key in self.entries:
   k=len(key[2])
   if key[:2]==(scope,identity) and cut<k<len(tokens) and key[2]==tuple(tokens[:k]):cut,best=k,key
  cache=None
  if best is not None:
   cache=copy(self.entries[best]);copied+=size(cache);self.entries.move_to_end(best)
  model.reset();pieces=[];pos=cut
  ends=list(range(((cut//self.stride)+1)*self.stride,len(tokens),self.stride))+[len(tokens)]
  for end in ends:
   states,cache=model.chunk(tokens[pos:end],cache=cache);pieces.append(states);pos=end
   if end==len(tokens):continue
   key=(scope,identity,tuple(tokens[:end]));cost=size(cache)
   if cost>self.budget:events.append(dict(type='skip',prefix=list(key[2]),bytes=cost));continue
   if key in self.entries:self.entries.move_to_end(key);continue
   while self.bytes+cost>self.budget:
    old,value=self.entries.popitem(last=False);self.bytes-=size(value);events.append(dict(type='evict',scope=old[0],prefix=list(old[2]),bytes=size(value)))
   self.entries[key]=copy(cache);copied+=cost;self.bytes+=cost;events.append(dict(type='admit',scope=scope,prefix=list(key[2]),bytes=cost))
  assert self.bytes==sum(size(v) for v in self.entries.values())<=self.budget
  return np.concatenate(pieces),dict(cut=cut,projected_rows=model.projected_rows,score_elements=model.score_elements,resident_bytes=self.bytes,explicit_copy_bytes=copied,events=events)
