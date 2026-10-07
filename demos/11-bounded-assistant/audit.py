import argparse,hashlib,json,base64
from pathlib import Path
R=Path(__file__).resolve().parents[2];p=argparse.ArgumentParser();p.add_argument('directory',type=Path);out=p.parse_args().directory;m=json.loads((out/'manifest.json').read_text());sha=lambda p:hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
for name,h in m['sources'].items():assert sha(R/name)==h,name
for name,h in m['files'].items():assert sha(out/name)==h,name
rows=json.loads((out/'results.json').read_text());assert len(rows)==22 and all(r['passed'] for r in rows)
for row in json.loads((out/'crashes.json').read_text()):
 lines=(out/(row['mode']+'.log')).read_text().splitlines();assert len(lines)==row['recovered_turns']
 for line in lines:
  payload,checksum=line.split(' ');raw=base64.b64decode(payload);assert hashlib.sha256(raw).hexdigest()==checksum;assert set(json.loads(raw))=={'id','question','answer'}
print(json.dumps({'cases':len(rows),'crash_processes':3,'integrity':'source/file hashes and recovered journal checksums verified'}))
