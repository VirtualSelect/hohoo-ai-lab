import argparse,hashlib,json
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
def audit(out):
 m=json.loads((out/'manifest.json').read_text('utf8'));p=json.loads((HERE/'protocol.json').read_text('utf8'));r=json.loads((out/'results.json').read_text('utf8'))
 for name,want in m['sources'].items():assert hashlib.sha256((ROOT/name).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==want,name
 for name,want in m['files'].items():assert hashlib.sha256((out/name).read_bytes()).hexdigest()==want,name
 assert len(r['cases'])==len(p['expected'])==22
 assert {x['case'] for x in r['cases']}==set(p['expected'])
 for x in r['cases']:
  assert x['status']==p['expected'][x['case']]
  assert x['committed']==(x['status']=='COMPLETE')
  assert x['historySize']==(4 if x['committed'] else 2)
  if x['committed']:assert x['preview']=='你好，Java 🌱'
 assert r['providerRequests']==0 and r['loopbackRequests']==22
 raw=(out/'one-byte.sse').read_bytes();naive=''.join(bytes([b]).decode('utf8',errors='replace') for b in raw)
 assert raw.decode('utf8') != naive and '你好，' in raw.decode('utf8') and '\ufffd' in naive
 return dict(valid=True,cases=22,complete=6,refused=16,naiveReplacementCharacters=naive.count('\ufffd'),providerRequests=0)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('out',type=Path);a=p.parse_args();r=audit(a.out);(a.out/'audit.json').write_text(json.dumps(r,indent=2)+'\n',encoding='utf8');print(r)
