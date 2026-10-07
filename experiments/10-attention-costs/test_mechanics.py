import unittest
import numpy as np
from mechanics import dense,tiled,quantize,restore,wrong_tile_average
class Kernels(unittest.TestCase):
 def test_uneven_blocks_and_extreme_scores(self):
  rng=np.random.default_rng(12)
  for n in [1,7,65]:
   q=rng.normal(size=(3,8))*100;k=rng.normal(size=(n,8));v=rng.normal(size=(n,8))
   for b in [1,4,32]:np.testing.assert_allclose(dense(q,k,v),tiled(q,k,v,b),atol=1e-12,rtol=1e-12)
 def test_zero_quantization(self):
  for mode in ['tensor','row']:np.testing.assert_array_equal(restore(quantize(np.zeros((3,4)),mode)),0)
 def test_quantization_bound(self):
  x=np.random.default_rng(8).normal(size=(8,4));q,s=quantize(x,'row');self.assertTrue(np.all(abs(restore((q,s))-x)<=s/2+1e-12))
 def test_negative_control(self):
  q=np.ones((1,1));k=np.array([[0.],[10.],[20.]]);v=np.array([[1.],[3.],[5.]])
  self.assertGreater(np.max(abs(dense(q,k,v)-wrong_tile_average(q,k,v,2))),.1)
if __name__=='__main__':unittest.main()
