"""Independent BM25/metric audit plus source and result integrity."""
import collections,hashlib,json,math,re,sys
from pathlib import Path
here=Path(__file__).resolve().parent;root=Path(sys.argv[1]);m=json.loads((root/'manifest.json').read_text());r=json.loads((root/'results.json').read_text());f=json.loads((here/'corpus.json').read_text())
def sha(p):return hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
for name,want in m['sources'].items():assert sha(here/name)==want,name
assert sha(root/'results.json')==m['resultsSha256']
assert r['networkRequests']==0 and len(r['checks'])==33 and all(c['passed'] for c in r['checks'])
assert r['checks'][0]['observed']==['fast:COMMITTED','slow:STALE']
tokens=lambda t:re.findall('[a-z0-9]+',t.lower())
words=[tokens(d['text']) for d in f['documents']];avg=sum(map(len,words))/len(words);df=collections.Counter(t for w in words for t in set(w))
for query,row in zip(f['queries'],r['retrieval']):
    assert query['id']==row['id'] and query['gold']==row['gold'] and query['query']==row['query']
    scores=[]
    for doc,w in zip(f['documents'],words):
        score=0
        for t in set(tokens(query['query'])):
            tf=w.count(t)
            if tf:score+=math.log(1+(len(words)-df[t]+.5)/(df[t]+.5))*(tf*2.2)/(tf+1.2*(.25+.75*len(w)/avg))
        if score>0:scores.append((doc['id'],score))
    scores=sorted(scores,key=lambda x:(-x[1],x[0]))[:3]
    assert row['hits']==[x[0] for x in scores]
    assert all(abs(a-b[1])<1e-12 for a,b in zip(row['scores'],scores))
    assert row['hitAt1']==bool(query['gold'] and scores and scores[0][0]==query['gold'])
    assert row['hitAt3']==bool(query['gold'] and query['gold'] in row['hits'])
answerable=[x for x in r['retrieval'] if x['gold'] is not None];unknown=[x for x in r['retrieval'] if x['gold'] is None]
result=dict(passed=True,checks=len(r['checks']),queries=len(r['retrieval']),answerable=len(answerable),hitAt1=sum(x['hitAt1'] for x in answerable),hitAt3=sum(x['hitAt3'] for x in answerable),unanswerable=len(unknown),unanswerable_with_hits=sum(bool(x['hits']) for x in unknown),networkRequests=0)
(root/'audit.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
