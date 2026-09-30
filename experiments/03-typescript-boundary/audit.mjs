import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import assert from 'node:assert/strict';
const dir=process.argv[2];if(!dir)throw Error('node audit.mjs evidence/<run>');
const report=JSON.parse(fs.readFileSync(path.join(dir,'report.json'))),fixture=JSON.parse(fs.readFileSync(path.join(dir,'fixtures.json')));
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
function check(file,expected){const b=fs.readFileSync(file);const lf=b.toString('utf8').replace(/\r\n/g,'\n');assert.ok([hash(b),hash(lf),hash(lf.replace(/\n/g,'\r\n'))].includes(expected),file);}
for(const[f,h]of Object.entries(report.sourceSha256))check(f,h);
check('../../demos/04-structured-output/src/main/java/com/hohoo/ailab/structured/Classification.java',report.javaValidatorSha256);
check(report.replay.source,report.replay.sourceSha256);
assert.equal(report.rows.length,fixture.length);assert.equal(new Set(report.rows.map(r=>r.id)).size,fixture.length);
for(const row of report.rows){const f=fixture.find(f=>f.id===row.id);assert.ok(f);assert.equal(row.expectedAccepted,f.expectedAccepted);assert.equal(row.wireAccepted,f.expectedAccepted);assert.equal(row.javaAccepted,f.expectedAccepted);}
assert.deepEqual(report.counts,{fixtures:fixture.length,castFalseAccepts:report.rows.filter(r=>r.castAccepted&&!r.expectedAccepted).length,objectFalseAccepts:report.rows.filter(r=>r.objectAccepted&&!r.expectedAccepted).length,wireJavaAgreements:report.rows.filter(r=>r.wireAccepted===r.javaAccepted).length});
console.log(JSON.stringify({verified:report.rows.length,hashes:'verified; only LF/CRLF checkout normalization allowed',...report.counts}));
