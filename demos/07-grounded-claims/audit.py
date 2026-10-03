import argparse,hashlib,json
from pathlib import Path
HERE=Path(__file__).resolve().parent
def normalized(p):return hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
def audit(out):
    m=json.loads((out/'manifest.json').read_text('utf8'));r=json.loads((out/'results.json').read_text('utf8'))
    for n,h in m['sources'].items():assert normalized(HERE/n)==h
    assert normalized(out/'results.json')==m['resultsSha256']
    f=json.loads((HERE/'fixtures.json').read_text('utf8'));rows={x['id']:x for x in r['cases']}
    assert len(rows)==len(r['cases'])==len(f['cases'])
    sources={s['id']:s for s in f['sources']};false_accepts=0;positive=0
    for c in f['cases']:
        row=rows[c['id']];assert row['actual']==c['expected'];positive+=c['expected']=='ACCEPT'
        if c['expected']=='INVALID_JSON':assert not row['citationOnly'] and row['accepted']=={};continue
        claims=json.loads(c['raw'])['claims'];q=c['request']
        citation=bool(claims) and all(x['doc'] in q['supplied'] and x['doc'] in sources and x['quote'] in sources[x['doc']]['text'] for x in claims)
        assert citation==row['citationOnly'];false_accepts+=citation and c['expected']!='ACCEPT'
        if c['expected']=='ACCEPT':
            expected={k:sources['prod-v2']['facts'][k] for k in q['fields']};assert row['accepted']==expected
        else:assert row['accepted']=={}
    assert (positive,false_accepts)==(r['positiveCases'],r['citationOnlyFalseAccepts'])
    return dict(passed=True,cases=len(rows),positive=positive,citation_only_false_accepts=false_accepts,networkRequests=r['networkRequests'])
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);a=p.parse_args();result=audit(a.out)
    (a.out/'audit.json').write_text(json.dumps(result)+'\n',encoding='utf8',newline='\n');print(result)
