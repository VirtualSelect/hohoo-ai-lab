import unittest
import numpy as np
from attention import Decoder, kv_bytes

class Contracts(unittest.TestCase):
    def test_hand_attention(self):
        m=Decoder(d=2,layers=1);q=np.zeros((2,2));k=np.zeros((2,2));v=np.array([[2.,4.],[6.,8.]])
        out,p=m.attend(q,k,v,np.arange(2),np.arange(2))
        np.testing.assert_allclose(out,[[2,4],[4,6]])
        np.testing.assert_allclose(p,[[1,0],[.5,.5]])
    def test_numerically_stable(self):
        m=Decoder(d=2,layers=1)
        out,p=m.attend(np.full((1,2),10000.),np.full((2,2),10000.),np.ones((2,2)),np.array([1]),np.arange(2))
        self.assertTrue(np.isfinite(out).all());np.testing.assert_allclose(p,[[.5,.5]])
    def test_all_masked_rejected(self):
        m=Decoder();a=np.ones((1,16))
        with self.assertRaises(ValueError):m.attend(a,a,a,np.array([0]),np.array([2]))
    def test_tokens_checked(self):
        for ids in ([],[-1],[32],[1.2]):
            with self.assertRaises(ValueError):Decoder().full(ids)
    def test_window_checked(self):
        for w in (0,-1,1.5):
            with self.assertRaises(ValueError):Decoder().full([1],window=w)
    def test_prefix_and_chunks_many_shapes(self):
        for seed in (3,7,19):
            for n in (1,2,8,17):
                for window in (None,1,4):
                    m=Decoder(seed);ids=np.arange(n)%32;ref=m.full(ids,window=window)[0];cache=None;result=[]
                    for start in range(0,n,3):
                        out,cache=m.chunk(ids[start:start+3],cache,window=window);result.extend(out)
                    np.testing.assert_allclose(result,ref,atol=1e-12,rtol=0)
                    self.assertEqual(cache['next_position'],n)
                    self.assertEqual(kv_bytes(cache),2*2*min(n,window or n)*16*8)
    def test_future_perturbation(self):
        m=Decoder();a=m.full([1,2,3,4])[0];b=m.full([1,2,7,8])[0]
        np.testing.assert_allclose(a[:2],b[:2],atol=1e-12)
    def test_wrong_offset_detected(self):
        m=Decoder();_,c=m.chunk([1,2,3]);a,_=m.chunk([4,5],c);b,_=m.chunk([4,5],c,wrong_mask=True)
        self.assertGreater(np.max(np.abs(a-b)),1e-4)
    def test_cache_not_mutated(self):
        m=Decoder();_,c=m.chunk([1,2]);before=c['layers'][0][0].copy();m.chunk([3],c)
        np.testing.assert_array_equal(c['layers'][0][0],before);self.assertEqual(c['next_position'],2)

if __name__=='__main__':unittest.main()
