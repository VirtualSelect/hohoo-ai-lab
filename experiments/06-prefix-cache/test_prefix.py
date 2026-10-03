import unittest
import numpy as np
from prefix_cache import Decoder,PrefixStore
class Tests(unittest.TestCase):
    def setUp(self):self.m=Decoder();self.s=PrefixStore();self.s.remember('a',self.m,[1,2,3])
    def call(self,scope='a',prefix=None,suffix=None,window=None):
        return self.s.continue_from(scope,self.m,prefix or [1,2,3],suffix or [4,5],window)
    def test_valid_and_independent_branches(self):
        for suffix in ([4,5],[6,7],[4,5]):
            out,meta=self.call(suffix=suffix);self.assertTrue(meta['reused'])
            np.testing.assert_allclose(out,self.m.full([1,2,3]+suffix)[0][-2:],rtol=0,atol=1e-12)
    def test_edit_miss(self):self.assertFalse(self.call(prefix=[1,9,3])[1]['reused'])
    def test_model_change_miss(self):
        self.m.weights[0][0][0,0]+=.01;self.assertFalse(self.call()[1]['reused'])
    def test_window_miss(self):self.assertFalse(self.call(window=2)[1]['reused'])
    def test_scope_miss(self):self.assertFalse(self.call(scope='b')[1]['reused'])
    def test_float_ids_do_not_alias(self):
        with self.assertRaises(ValueError):self.call(prefix=[1.,2.,3.])
    def test_bounded_fifo(self):
        self.s.remember('b',self.m,[1,2,3]);self.s.remember('c',self.m,[1,2,3])
        self.assertFalse(self.call()[1]['reused']);self.assertEqual(len(self.s.entries),2)
    def test_invalid_scope(self):
        with self.assertRaises(ValueError):self.call(scope='')
if __name__=='__main__':unittest.main()
