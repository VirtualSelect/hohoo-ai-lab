import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import path from 'node:path';
const dir=process.argv[2]||'evidence/20260928-l1';
const read=f=>JSON.parse(fs.readFileSync(path.join(dir,f),'utf8'));
// Gson preserves the double-valued temperature as 0.0 in request bytes.
const requestBytes=r=>JSON.stringify(r).replace('"temperature":0,','"temperature":0.0,');
const hash=v=>crypto.createHash('sha256').update(v).digest('hex');
const plan=read('plan.json'),cases=read('cases.json'),protocol=read('protocol.json'),summary=read('summary.json');
assert.equal(plan.length,48);
assert.equal(new Set(plan.map(j=>[j.case,j.repeat,j.condition].join('/'))).size,48);
for(const c of cases){
 const jobs=plan.filter(j=>j.case===c.id);
 const lines=j=>j.request.messages[1].content.split('<records>\n')[1].split('\n</records>')[0].split('\n');
 const positive=jobs.filter(j=>j.condition!=='absent'),reference=lines(positive[0]).sort();
 for(const j of positive){
  const list=lines(j);assert.equal(list.length,61);
  assert.ok(list[{beginning:0,middle:30,end:60}[j.condition]].includes(c.answer));
  assert.equal(list.filter(l=>l.includes(c.answer)).length,1);
  assert.deepEqual([...list].sort(),reference);
 }
 for(const j of jobs.filter(j=>j.condition==='absent'))assert.ok(!lines(j).join('\n').includes(c.answer));
}
const files=fs.readdirSync(dir).filter(f=>/^attempt-\d+.json$/.test(f)).sort();
const counts={},usage={prompt_tokens:0,completion_tokens:0,total_tokens:0};
let available=0,consecutive=0;
files.forEach((f,i)=>{
 const r=read(f),j=plan[i];assert.equal(r.index,i+1);
 for(const k of ['case','repeat','condition'])assert.equal(r[k],j[k]);
 assert.equal(r.requestSha256,hash(requestBytes(j.request)));
 assert.equal(r.requestCharacters,requestBytes(j.request).length);
 if(r.httpStatus===200&&r.finishReason==='stop'){
  const s=r.content.trim(),expected=cases.find(c=>c.id===r.case).answer;
  const score=s==='UNKNOWN'?(r.condition==='absent'?'correct_abstention':'abstention'):!/^[A-Z]{2}-[0-9]{4}$/.test(s)?'format_error':r.condition!=='absent'&&s===expected?'correct':'incorrect';
  assert.equal(r.outcome,score);available++;consecutive=0;
  for(const k of Object.keys(usage))usage[k]+=r.usage?.[k]||0;
 }else consecutive++;
 assert.ok(i===files.length-1||consecutive<protocol.consecutiveFailureLimit);
 counts[r.condition]??={};counts[r.condition][r.outcome]=(counts[r.condition][r.outcome]||0)+1;
});
assert.equal(summary.attempted,files.length);assert.deepEqual(counts,summary.conditions);
assert.equal(summary.stoppedEarly,files.length<48);
if(summary.stoppedEarly)assert.equal(consecutive,3);
console.log(JSON.stringify({planned:48,attempted:files.length,available,notAttempted:48-files.length,counts,returnedUsageOnly:usage},null,2));
