"""Source audit must detect code changes, not just validate evidence files."""
import hashlib,tempfile,unittest
from pathlib import Path
from audit import verify_sources

class SourceAudit(unittest.TestCase):
 def test_source_match_then_mutation(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p=root/'code.py';p.write_bytes(b'print(1)\r\n')
   m={'sources':{'code.py':hashlib.sha256(b'print(1)\n').hexdigest()}}
   verify_sources(m,root)
   p.write_bytes(b'print(2)\n')
   with self.assertRaisesRegex(ValueError,'Source mismatch'):verify_sources(m,root)
 def test_manifest_requires_sources(self):
  with self.assertRaises(ValueError):verify_sources({})
 def test_missing_and_escaped_paths_are_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   for name in ['missing.py','../outside.py']:
    with self.assertRaises(ValueError):verify_sources({'sources':{name:'0'*64}},Path(d))
if __name__=='__main__':unittest.main()
