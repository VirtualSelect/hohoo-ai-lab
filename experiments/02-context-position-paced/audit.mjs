import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import path from 'node:path';
const dir=process.argv[2];
if(!dir)throw new Error('Usage: node audit.mjs <evidence-directory>');
const read=f=>JSON.parse(fs.readFileSync(path.join(dir,f),'utf8'));
const bytes=r=>JSON.stringify(r).replace('"temperature":0,','"temperature":0.0,');
const hash=v=>crypto.createHash('sha256').update(v).digest('hex');
const plan=read('plan.json'),cases=read('cases.json'),protocol=read('protocol.json');
assert.equal(plan.length,24);
assert.equal(new Set(plan.map(j=>[j.case,j.distractors,j.condition].join('/'))).size,24);
for(const c of cases)for(const n of protocol.lengths){
 const jobs=plan.filter(j=>j.case===c.id&&j.distractors===n);
 const lines=j=>j.request.messages[1].content.split('<records>\n')[1].split('\n</records>')[0].split('\n');
 const positive=jobs.filter(j=>j.condition!=='absent'),reference=lines(positive[0]).sort();
 for(const j of positive){
  const list=lines(j);
  assert.equal(list.length,n+1);
  assert.ok(list[{beginning:0,middle:n/2,end:n}[j.condition]].includes(c.answer));
  assert.equal(list.filter(l=>l.includes(c.answer)).length,1);
  assert.deepEqual([...list].sort(),reference);
 }
 const absent=lines(jobs.find(j=>j.condition==='absent')).join('\n');
 assert.ok(!absent.includes(c.answer)&&!absent.includes(c.project));
}
if(process.argv.includes('--prepared')){
 assert.equal(read('manifest.json').kind,'offline-preparation; no model requests');
 assert.equal(fs.readdirSync(dir).filter(f=>/^attempt-/.test(f)).length,0);
 console.log(JSON.stringify({kind:'offline-preparation',planned:plan.length,requestsSent:0,cases:cases.length,lengths:protocol.lengths,offlineChecks:read('manifest.json').offlineChecks}));process.exit(0);
}
const summary=read('summary.json');
const files=fs.readdirSync(dir).filter(f=>/^attempt-\d+.json$/.test(f)).sort();
const counts={},usage={prompt_tokens:0,completion_tokens:0,total_tokens:0};
let available=0,failures=0,previous;
const records=files.map((f,i)=>{
 const r=read(f),j=plan[i];assert.equal(r.index,i+1);
 for(const k of ['case','block','condition','distractors'])assert.equal(r[k],j[k]);
 assert.equal(r.requestSha256,hash(bytes(j.request)));
 assert.equal(r.requestCharacters,bytes(j.request).length);
 if(previous)assert.ok(Date.parse(r.startedAt)-Date.parse(previous.startedAt)-previous.elapsedMs>=protocol.minPauseMs-30,'pacing gap');
 if(r.httpStatus===200&&r.finishReason==='stop'&&r.outcome!=='protocol_error'){
  const s=r.content.trim(),expected=cases.find(c=>c.id===r.case).answer;
  const score=s==='UNKNOWN'?(r.condition==='absent'?'correct_abstention':'abstention'):!/^[A-Z]{2}-[0-9]{4}$/.test(s)?'format_error':r.condition!=='absent'&&s===expected?'correct':'incorrect';
  assert.equal(r.outcome,score);available++;failures=0;
  for(const k of Object.keys(usage))usage[k]+=r.usage?.[k]||0;
 }else failures++;
 assert.ok(i===files.length-1||(![429,401,403].includes(r.httpStatus)&&failures<protocol.consecutiveFailureLimit),'stop was ignored');
 const g=r.distractors+'/'+r.condition;
 counts[g]??={};counts[g][r.outcome]=(counts[g][r.outcome]||0)+1;
 previous=r;return r;
});
const complete=[1,2,3,4,5,6].filter(b=>{
 const rows=records.filter(r=>r.block===b);
 return rows.length===4&&rows.every(r=>!['transport_error','protocol_error'].includes(r.outcome));
});
assert.deepEqual(complete,summary.completeBlocks);
assert.equal(summary.attempted,files.length);assert.deepEqual(counts,summary.conditions);
assert.equal(summary.stoppedEarly,files.length<24);
if(summary.stoppedEarly)assert.ok([429,401,403].includes(records.at(-1)?.httpStatus)||failures>=protocol.consecutiveFailureLimit);
const report={planned:24,attempted:files.length,available,notAttempted:24-files.length,completeBlocks:complete,counts,returnedUsageOnly:usage};
console.log(JSON.stringify(report,null,2));
