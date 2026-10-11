"""Independent Python frame decoder; does not call Java recovery code."""
import hashlib,json,pathlib,struct,sys,zlib
H=pathlib.Path(__file__).resolve().parent
def decode(path):
    data=path.read_bytes();rows=[];offset=0
    while offset<len(data):
        magic,length,crc=struct.unpack_from('>III',data,offset);offset+=12
        assert magic==0x484f484f and 0<length<=65536
        payload=data[offset:offset+length];offset+=length
        assert len(payload)==length and zlib.crc32(payload)==crc
        pos=0;strings=[]
        for _ in range(3):
            n=struct.unpack_from('>H',payload,pos)[0];pos+=2
            # Frozen protocol uses ASCII fixtures; general modified UTF-8 is outside this auditor.
            strings.append(payload[pos:pos+n].decode('ascii'));pos+=n
        result=struct.unpack_from('>I',payload,pos)[0]
        assert pos+4==length and result==len(rows)+1
        key,digest,body=strings;assert digest==hashlib.sha256(body.encode()).hexdigest()
        assert key not in [r[0] for r in rows];rows.append((key,body,result))
    return rows
def main():
    root=pathlib.Path(sys.argv[1]);m=json.loads((root/'manifest.json').read_text())
    for f,h in m['sources'].items():assert hashlib.sha256((H/f).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==h,f
    for f,h in m['files'].items():assert hashlib.sha256((root/f).read_bytes()).hexdigest()==h,f
    rows=json.loads((root/'results.json').read_text());assert len(rows)==17
    for r in rows:
        if r['section']=='ledger':
            if 'count' in r:assert r['count']==1
            if 'rejected' in r:assert r['rejected']
            if r['case']=='corrupt':assert r['unchanged']
        else:
            d=root/(('recover-' if r['section']=='history' else '')+r['case'])
            effects=decode(d/'receiver.wal');receipts=decode(d/'receipts.wal');history=decode(d/'history.wal')
            assert len(effects)==r['effects'] and len(receipts)==r['receipts'] and len(history)==r['history']
            if r['case']=='receiver_conflict':assert len(effects)==1 and len(history)==len(receipts)==0
            else:
                n=2 if r['case']=='two_intents' else 1
                assert len(history)==len(receipts)==n and len(effects)==(2 if r['case']=='unkeyed_delivery' else n)
                for intent,receipt,turn in zip(decode(d/'intents.wal'),receipts,history):
                    assert intent[0]==receipt[0]==turn[0] and turn[1]==intent[1]+' => '+receipt[1]
    for p in root.rglob('*.wal'):
        if p.name!='corrupt.wal':decode(p)
    calls=json.loads((root/'processes.json').read_text())
    assert sum(c['exit']==23 for c in calls)==9
    assert any(c['exit'] not in (0,23) for c in calls)
    print(f'PASS: {len(rows)} scenarios, {len(calls)} child-process records, independent journal and recovery audit')
if __name__=='__main__':main()
