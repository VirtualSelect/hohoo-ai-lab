import unittest
import numpy as np
from cache import Store,Decoder
class TestAdmission(unittest.TestCase):
 def test_policy_rejected(self):
  with self.assertRaises(ValueError):Store(8192,policy='bogus')
 def test_exact_byte_boundary(self):
  for capacity,expected in [(4223,0),(4224,8)]:
   m=Decoder(7);s=Store(capacity,policy='longest');s.query('a',m,list(range(1,13)));_,r=s.query('a',m,list(range(1,13)));self.assertEqual(r['cut'],expected)
 def test_scope_isolation(self):
  m=Decoder(7);s=Store(16384,policy='longest');s.query('a',m,list(range(1,13)));_,r=s.query('b',m,list(range(1,13)));self.assertEqual(r['cut'],0)
 def test_model_identity(self):
  s=Store(16384,policy='longest');s.query('a',Decoder(7),list(range(1,13)));_,r=s.query('a',Decoder(11),list(range(1,13)));self.assertEqual(r['cut'],0)
 def test_no_alias_on_return(self):
  m=Decoder(7);s=Store(16384,policy='longest');x,_=s.query('a',m,list(range(1,13)));x[:]=999;got,r=s.query('a',m,list(range(1,13)));fresh,_=m.full(list(range(1,13)));np.testing.assert_allclose(got,fresh[r['cut']:],atol=1e-12)
 def test_longest_admits_one(self):
  s=Store(16384,policy='longest');s.query('a',Decoder(7),list(range(1,13)));self.assertEqual([len(k[2]) for k in s.entries],[8])
if __name__=='__main__':unittest.main()
