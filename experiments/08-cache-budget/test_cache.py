import unittest
import numpy as np
from cache import Store,Decoder,size
class Contracts(unittest.TestCase):
 def setUp(self):self.m=Decoder(7);self.t=list(range(1,13))
 def test_budget(self):
  for n in (0,4096,8192,32768):
   s=Store(n);s.query('a',self.m,self.t);_,r=s.query('a',self.m,self.t);self.assertLessEqual(s.bytes,n);self.assertEqual(r['cut'],0 if n==0 else 4 if n==4096 else 8)
 def test_scope(self):
  s=Store(8192);s.query('a',self.m,self.t);_,r=s.query('b',self.m,self.t);self.assertEqual(r['cut'],0)
 def test_weights(self):
  s=Store(8192);s.query('a',self.m,self.t);self.m.embedding[1,0]+=.1;_,r=s.query('a',self.m,self.t);self.assertEqual(r['cut'],0)
 def test_owned_arrays(self):
  s=Store(8192);s.query('a',self.m,self.t);out,_=s.query('a',self.m,self.t);out[:]=100;out,_=s.query('a',self.m,self.t);full,_=self.m.full(self.t);np.testing.assert_allclose(out,full[8:],atol=1e-12)
 def test_short(self):
  s=Store(8192);s.query('a',self.m,self.t);out,r=s.query('a',self.m,[1,2]);self.assertEqual(r['cut'],0);self.assertEqual(out.shape,(2,16))
 def test_validation(self):
  for n in (-1,True,1.5):
   with self.assertRaises(ValueError):Store(n)
  with self.assertRaises(ValueError):Store(1).query('',self.m,self.t)
 def test_cost(self):
  _,c=self.m.chunk(self.t[:4]);self.assertEqual(size(c),2112)
if __name__=='__main__':unittest.main()
