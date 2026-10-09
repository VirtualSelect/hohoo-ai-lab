"""Independent checks of saved HTTP outcomes, effect counts and provenance."""
import hashlib,json,pathlib,sys
H=pathlib.Path(__file__).resolve().parent
D=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else H/'evidence'
manifest=json.loads((D/'manifest.json').read_text())
for file,sha in manifest['files'].items(): assert hashlib.sha256((D/file).read_bytes()).hexdigest()==sha,file
for file,sha in manifest['sources'].items(): assert hashlib.sha256((H/file).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==sha,file
rows=json.loads((D/'results.json').read_text()); cases={r['case']:r for r in rows}
assert set(cases)==set(manifest['protocol']['cases']) and len(rows)==7
for name,count in [('unkeyed_lost_reply',2),('keyed_lost_reply',1),('concurrent_duplicate',1),('body_conflict',1),('restart_after_reply',2),('crash_after_effect',2),('capacity_and_input',64)]: assert cases[name]['counter']==count,name
for name in ('unkeyed_lost_reply','keyed_lost_reply'):
    a,b=cases[name]['calls']; assert 'error' in a and b['status']==200
    assert b['body']==str(cases[name]['counter'])
assert cases['keyed_lost_reply']['calls'][1]['replay']=='true'
c=casses=cases['concurrent_duplicate']['calls']
assert len(c)==12 and all(x['status']==200 and x['body']=='1' for x in c)
assert sum(x['replay']=='false' for x in c)==1
assert [x['status'] for x in cases['body_conflict']['calls']]==[200,409]
for name in ('restart_after_reply','crash_after_effect'):
    assert cases[name]['calls'][1]['body']=='2' and cases[name]['calls'][1]['replay']=='false'
assert cases['crash_after_effect']['crash_exit_code']==23 and 'error' in cases['crash_after_effect']['calls'][0]
c=cases['capacity_and_input']['calls']; assert [x['status'] for x in c[:2]]==[400,413]
assert all(x['status']==200 for x in c[2:66]); assert c[-2]['status']==429 and c[-1]['body']=='1' and c[-1]['replay']=='true'
print('PASS: 7 cases, HTTP semantics, crash window, capacity, source and evidence hashes')
