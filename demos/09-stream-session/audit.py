import argparse,hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def audit(out):
 m=json.loads((out/'manifest.json').read_text('utf8'));result=json.loads((out/'results.json').read_text('utf8'));rows=result['cases']
 for p,h in m['sources'].items():assert hashlib.sha256((ROOT/p).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==h,p
 for p,h in m['files'].items():assert hashlib.sha256((out/p).read_bytes()).hexdigest()==h,p
 assert len(rows)==18 and len({(r['case'],r['policy']) for r in rows})==18
 failures=[]
 for r in rows:
  n=r['case'];b=n.startswith('supersede') or n in ('late-preview','late-error')
  expected=['question-B','answer-B'] if b else ['question-A','answer-A'] if n in ('normal','duplicate-complete') else []
  # Reconstruct every observed mutation; a stale preview is a failure even if later overwritten.
  active=None;history=[];preview='';valid=True
  for e in r['events']:
   k=e['kind'];tid=e['ticket'];res=e['result']
   if k=='begin' and res=='STARTED':active=tid;preview=''
   if k in ('cancel','clear'):active=None;preview='';history=[] if k=='clear' else history
   if k=='preview' and res=='ACCEPTED':
    valid &= active==tid;preview=e['preview']
   if k=='complete' and res=='COMMITTED':
    valid &= active==tid;history+=['question-'+tid,'answer-'+tid];active=None;preview=''
   if k=='fail' and res=='FAILED':valid &= active==tid;active=None;preview=''
   assert e['history']==history and e['preview']==preview
  assert history==r['history'] and preview==r['preview']
  valid=bool(valid and history==expected and preview=='')
  if r['policy']=='owned':assert valid,r['case']
  elif not valid:failures.append(n)
 assert set(failures)=={'cancel','clear','supersede-old-first','supersede-new-first','late-preview','duplicate-complete','late-error'}
 return dict(valid=True,schedules=18,owned_pass=9,naive_fail=7,contract_groups=result['contract_checks'],failed_controls=failures)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('out',type=Path);o=p.parse_args().out;r=audit(o);(o/'audit.json').write_text(json.dumps(r,indent=2)+'\n',encoding='utf8');print(r)
