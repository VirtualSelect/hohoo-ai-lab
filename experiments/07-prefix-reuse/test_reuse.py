import unittest
import numpy as np
from reuse import Store,Decoder
class Tests(unittest.TestCase):
 def test_middle(self):
  m=Decoder();s=Store();s.remember('x',m,[1,2,3,4]);out,i=s.query('x',m,[1,2,9,4,5]);fresh,_=m.full([1,2,9,4,5]);np.testing.assert_allclose(out,fresh[2:],atol=1e-12);self.assertEqual(i['cut'],2)
 def test_singleton(self):
  m=Decoder();s=Store();s.remember('x',m,[1,2,3]);_,i=s.query('x',m,[1]);self.assertEqual(i['cut'],0)
 def test_branches_own_cache(self):
  m=Decoder();s=Store();s.remember('x',m,[1,2,3,4]);before=s.entries[0][3]['layers'][0][0].copy();s.query('x',m,[1,2,9]);np.testing.assert_array_equal(before,s.entries[0][3]['layers'][0][0])
 def test_scope_and_weights(self):
  m=Decoder();s=Store();s.remember('x',m,[1,2,3]);self.assertEqual(s.query('y',m,[1,2,3,4])[1]['cut'],0);m.embedding[1,0]+=.1;self.assertEqual(s.query('x',m,[1,2,3,4])[1]['cut'],0)
 def test_window_partial_fallback(self):
  m=Decoder();s=Store();s.remember('x',m,[1,2,3,4,5],2);self.assertEqual(s.query('x',m,[1,2,9,4,5,6],2)[1]['cut'],0);self.assertEqual(s.query('x',m,[1,2,3,4,5,6],2)[1]['cut'],5)
 def test_capacity(self):
  m=Decoder();s=Store(1);s.remember('x',m,[1]);s.remember('x',m,[2]);self.assertEqual(len(s.entries),1);self.assertEqual(s.query('x',m,[1,3])[1]['cut'],0)
 def test_invalid_ids(self):
  m=Decoder();s=Store();s.remember('x',m,[1]);
  for q in ([1.0,2.0],[],[99]):
   with self.assertRaises(ValueError):s.query('x',m,q)
if __name__=='__main__':unittest.main()
