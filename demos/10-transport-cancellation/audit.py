import json,hashlib,sys,statistics
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def verify_sources(manifest,root=ROOT):
 sources=manifest.get('sources')
 if not isinstance(sources,dict) or not sources:raise ValueError('Missing source fingerprints')
 for name,expected in sources.items():
  target=(root/name).resolve()
  if not target.is_relative_to(root.resolve()) or not target.is_file():raise ValueError('Invalid source path: '+name)
  actual=hashlib.sha256(target.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
  if actual!=expected:raise ValueError('Source mismatch; use the evidence-pinned revision: '+name)

def audit(out):
 m=json.loads((out/'manifest.json').read_text('utf8'));r=json.loads((out/'results.json').read_text('utf8'))
 verify_sources(m)
 for n,h in m['files'].items():assert hashlib.sha256((out/n).read_bytes()).hexdigest()==h
 assert len(r)==30 and len({(x['condition'],x['policy'],x['repeat']) for x in r})==30
 for x in r:
  normal=x['condition']=='complete';assert len(x['session']['history'])==(2 if normal else 0)
  assert x['session']['preview']=='' and x['exitMs']>=0
  if normal:assert x['outcome']=='COMMITTED' and x['actionMs'] is None
  else:assert x['futureCancelledAtAction'] and x['afterActionMs']>=0
  assert x['serverWorkSteps']==(8 if x['condition'].endswith('dripping') else 0)
 stats=[]
 for c in sorted({x['condition'] for x in r}-{'complete'}):
  for p in ['interrupt-only','close-socket']:
   group=[x for x in r if (x['condition'],x['policy'])==(c,p)];v=[x['afterActionMs'] for x in group]
   stats.append(dict(condition=c,policy=p,median_ms=statistics.median(v),min_ms=min(v),max_ms=max(v),outcomes=[x['outcome'] for x in group],still_running=sum(not x['workerExitedAtAction'] for x in group)))
 return dict(runs=len(r),normal_commits=6,cancelled_history_commits=0,timing=stats)
if __name__=='__main__':
 out=Path(sys.argv[1]);a=audit(out);(out/'audit.json').write_text(json.dumps(a,indent=2)+'\n',encoding='utf8');print(json.dumps(a,indent=2))
