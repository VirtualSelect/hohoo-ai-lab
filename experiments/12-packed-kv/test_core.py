import unittest
import numpy as np
from core import encode,decode,payload,attention,block_attention
class CodecTests(unittest.TestCase):
    def test_partial_groups_and_nibbles(self):
        x=np.arange(35*13,dtype=np.float32).reshape(35,13)/50-4
        for bits in (4,8):
            for transpose in (False,True):
                p=encode(x,8,bits,transpose);y=decode(p)
                self.assertEqual(y.shape,x.shape)
                self.assertLessEqual(np.max(abs(x-y)),p['scale'].max()/2+1e-6)
                self.assertEqual(p['data'].dtype,np.uint8)
    def test_zero_and_constant(self):
        for value in (0.,-7.,2.):
            x=np.full((32,32),value,dtype=np.float32)
            np.testing.assert_allclose(decode(encode(x,32,4)),x)
    def test_reject_nonfinite(self):
        with self.assertRaises(ValueError):encode(np.array([[np.nan]]),8,4)
        with self.assertRaises(ValueError):encode(np.zeros((2,2)),7,4)
    def test_block_merge(self):
        rng=np.random.default_rng(91);q=rng.normal(size=(4,32));k=rng.normal(size=(96,32));v=rng.normal(size=(96,32))
        blocks=[(encode(k[i:i+32],32,4,True),encode(v[i:i+32],32,4)) for i in range(0,96,32)]
        ref,_=attention(q,np.concatenate([decode(a) for a,b in blocks]),np.concatenate([decode(b) for a,b in blocks]))
        np.testing.assert_allclose(block_attention(q,blocks),ref,rtol=1e-12,atol=1e-12)
if __name__=='__main__':unittest.main()
