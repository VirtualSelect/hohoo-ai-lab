import unittest
import numpy as np
from quantization import pack,unpack,attention
class QuantizationTests(unittest.TestCase):
    def test_zero(self):
        for axis in ('tensor','row','channel'):
            np.testing.assert_array_equal(unpack(pack(np.zeros((3,4)),axis)),np.zeros((3,4)))
    def test_axis_shapes_and_error(self):
        x=np.array([[1.,3.,900.],[-2.,5.,-700.]],dtype=np.float32)
        for mode,shape in [('tensor',(1,1)),('row',(2,1)),('channel',(1,3))]:
            pair=pack(x,mode);self.assertEqual(pair[1].shape,shape)
            self.assertTrue(np.all(abs(unpack(pair)-x)<=pair[1]*.5001))
    def test_invalid(self):
        for x,axis in [(np.zeros(3),'row'),(np.array([[np.nan]]),'tensor'),(np.zeros((2,2)),'other')]:
            with self.assertRaises(ValueError):pack(x,axis)
    def test_constant_values_survive_attention(self):
        q=np.ones((2,4));k=np.arange(12).reshape(3,4);v=np.full((3,4),2.)
        np.testing.assert_allclose(attention(q,k,v),np.full((2,4),2.))
if __name__=='__main__':unittest.main()
