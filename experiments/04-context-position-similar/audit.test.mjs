import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import test from 'node:test';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { auditEvidence, auditMaterials, analyze, clusterBootstrap, javaTrim, score, sha256, shuffledCaseIds } from './audit.mjs';

// All fixtures below are independently constructed and temporary. No API calls,
// prior live evidence, or Java-generated plans are needed for these tests.
const conditions = ['beginning', 'middle', 'end', 'absent'];
const orders = ['beginning middle absent end', 'middle end beginning absent', 'end absent middle beginning', 'absent beginning end middle'].map(s => s.split(' '));
const digits = '零一二三四五六七八九';
function fixture({ count = 8, lengths = [6, 12], live = false } = {}) {
  const protocol = { id: 'test-only', version: 1, model: 'agnes-3.0-flash', endpoint: 'https://apihub.agnes-ai.com/v1/chat/completions',
    temperature: 0, maxTokens: 1024, maxRequests: count * lengths.length * 8, minPauseMs: 20000,
    consecutiveFailureLimit: 2, lengths, similarities: ['low', 'high'], conditions, orderSeed: 20260930,
    bootstrapSeed: 41, bootstrapSamples: 1000, practicalThreshold: 0.1 };
  const cases = Array.from({ length: count }, (_, i) => ({ id: `T${i + 1}`, project: `松塔${digits[i]}站试验项目`, answer: `AA-${String(i * 100).padStart(4, '0')}`,
    nearNames: [`松塔${digits[i]}港试验项目`, `松塔${digits[i]}城试验项目`, `${digits[i]}站松塔试验项目`, `试验松塔${digits[i]}站项目`, `松塔${digits[i]}站勘探项目`, `松塔${digits[i]}站测绘项目`],
    lowNames: ['独立甲乙归档项目', '独立丙丁归档项目', '独立戊己归档项目', '独立庚辛归档项目', '独立壬癸归档项目', '独立子丑归档项目'],
    codes: Array.from({ length: Math.max(...lengths) + 1 }, (_, k) => `AA-${String(i * 100 + k + 1).padStart(4, '0')}`),
  }));
  const plan = [];
  let block = 0, pair = 0;
  for (let ci = 0; ci < count; ci++) {
    const c = cases[(count === 8 ? [3, 1, 2, 7, 0, 6, 5, 4] : [3, 0, 1, 2])[ci]];
    for (const n of lengths) {
      pair++;
      for (const similarity of ci % 2 ? ['high', 'low'] : ['low', 'high']) {
        block++;
        for (const condition of orders[ci % 4]) {
          const lines = Array.from({ length: n }, (_, k) => {
            const slot = Array.from({ length: 6 }, (_, d) => Math.floor((2 * d + 1) * n / 12)).indexOf(k);
            const name = slot < 0 ? `归档${String(k).padStart(4, '0').replace(/[0-9]/g, d => digits[+d])}项目` : c[similarity === 'high' ? 'nearNames' : 'lowNames'][slot];
            return `${name}：交接编号为 ${c.codes[k]}。`;
          });
          const pos = condition === 'beginning' ? 0 : condition === 'end' ? n : n / 2;
          lines.splice(pos, 0, `${condition === 'absent' ? '独立补充归档项目' : c.project}：交接编号为 ${condition === 'absent' ? c.codes[n] : c.answer}。`);
          const request = { model: protocol.model, temperature: 0, max_tokens: 1024, messages: [
            { role: 'system', content: '按完整项目名检索。只回答编号或UNKNOWN。' },
            { role: 'user', content: `虚构记录。\n<records>\n${lines.join('\n')}\n</records>\n问题：${c.project}的交接编号是什么？` },
          ] };
          plan.push({ case: c.id, block, pair, distractors: n, similarity, condition, targetLine: condition === 'absent' ? -1 : pos, request });
        }
      }
    }
  }
  const records = plan.map((job, i) => {
    const c = cases.find(c => c.id === job.case);
    const wire = JSON.stringify(job.request).replace('"temperature":0,', '"temperature":0.0,');
    const { request, ...attrs } = job;
    return { index: i + 1, ...attrs, requestSha256: sha256(wire), requestCharacters: wire.length, requestBytes: Buffer.byteLength(wire),
      startedAt: new Date(Date.UTC(2026, 8, 30) + i * 20001).toISOString(), elapsedMs: 1, httpStatus: 200,
      model: protocol.model, returnedRole: 'assistant', finishReason: 'stop', content: job.condition === 'absent' ? 'UNKNOWN' : c.answer,
      usage: null, outcome: job.condition === 'absent' ? 'correct_abstention' : 'correct', wrongDistractorCode: false, ...(live ? {} : { simulated: true }) };
  });
  return { protocol, cases, plan, records, live };
}
function summary(data) {
  const groups = {}, blockRows = new Map();
  for (const row of data.records) {
    const k = `${row.distractors}/${row.similarity}/${row.condition}`;
    groups[k] ??= {}; groups[k][row.outcome] = (groups[k][row.outcome] || 0) + 1;
    if (!blockRows.has(row.block)) blockRows.set(row.block, []);
    blockRows.get(row.block).push(row);
  }
  const complete = ([, rows]) => rows.length === 4 && rows.every(r => !['protocol_error', 'transport_error'].includes(r.outcome));
  return { planned: data.plan.length, attempted: data.records.length, stoppedEarly: data.records.length < data.plan.length,
    conditions: groups, completeBlocks: [...blockRows].filter(complete).map(([b]) => b),
    incompleteAttemptedBlocks: [...blockRows].filter(x => !complete(x)).map(([b]) => b),
    finishedAt: new Date(Date.parse(data.records.at(-1).startedAt) + 1).toISOString() };
}
function writeFixture(data, { prepared = false } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hohoo-l1v3-node-test-'));
  const write = (name, value) => fs.writeFileSync(path.join(dir, name), `${JSON.stringify(value, null, 2)}\n`);
  for (const name of ['protocol', 'cases', 'plan']) write(`${name}.json`, data[name]);
  const hashes = Object.fromEntries(['protocol.json', 'cases.json', 'plan.json'].map(name => [name, sha256(fs.readFileSync(path.join(dir, name)))]));
  write('manifest.json', { kind: prepared ? 'offline-preparation; no model requests' : data.live ? 'live-model-run' : 'simulated-offline; no model requests',
    offlineChecks: 10, evidenceSha256: hashes, evidenceLfSha256: hashes });
  if (!prepared) {
    data.records.forEach((r, i) => write(`attempt-${String(i + 1).padStart(2, '0')}.json`, r));
    write('summary.json', summary(data));
  }
  return dir;
}
function withFixture(data, fn, options) {
  const dir = writeFixture(data, options);
  try { return fn(dir); }
  finally { assert.ok(path.basename(dir).startsWith('hohoo-l1v3-node-test-')); fs.rmSync(dir, { recursive: true }); }
}
function failure(r, code = 500) {
  r.outcome = 'transport_error'; r.httpStatus = code; r.errorCode = `http_${code}`;
  for (const key of ['content', 'model', 'returnedRole', 'finishReason', 'wrongDistractorCode']) delete r[key];
}

for (const [content, absent, expected] of [
  [' AA-1234\n', false, 'correct'], ['\u0000\tAA-1234\u001f', false, 'correct'],
  ['\u00a0AA-1234\u00a0', false, 'format_error'], ['\ufeffAA-1234', false, 'format_error'],
  ['AA-1234\u2003', false, 'format_error'], ['UNKNOWN', false, 'abstention'], [' UNKNOWN\r', true, 'correct_abstention'],
  ['AA-1234', true, 'incorrect'], ['AA-4321', false, 'incorrect'], ['Answer: AA-1234', false, 'format_error'],
  ['aa-1234', false, 'format_error'], ['', false, 'format_error'],
]) test(`exact Java-trim scoring: ${JSON.stringify(content)} / absent=${absent}`, () => assert.equal(score(content, 'AA-1234', absent), expected));
test('Java trim preserves non-ASCII spaces that JavaScript trim removes', () => assert.notEqual(javaTrim('\u00a0UNKNOWN'), '\u00a0UNKNOWN'.trim()));

test('128 independent synthetic requests, 32 blocks, 16 pairs, eight case clusters', () => withFixture(fixture(), dir => {
  const result = auditEvidence(dir, { simulated: true });
  assert.equal(result.kind, 'simulated-offline; no model requests');
  assert.equal(result.attempted, 128); // catches lexicographic attempt-100 ordering
  assert.equal(result.completeBlocks.length, 32);
  assert.equal(result.completePairCount, 16);
  assert.equal(result.primary.independentCases, 8);
  assert.equal(result.primary.estimate, 0);
  assert.equal(result.primary.interpretation, 'ceiling_on_these_materials');
  assert.deepEqual([result.primary.confidenceInterval.lower, result.primary.confidenceInterval.upper], [0, 0]);
  assert.equal(result.returnedUsageOnly.knownTotals, null);
  assert.equal(result.returnedUsageOnly.recordsWithUnknownUsage, 128);
  assert.equal(result.pacingValidation, 'not_assessed_simulation');
}));
test('dynamic smaller matrix has 32 requests and no model calls', () => withFixture(fixture({ count: 4, lengths: [12] }), dir => {
  const result = auditEvidence(dir, { prepared: true });
  assert.equal(result.planned, 32); assert.equal(result.requestsSent, 0); assert.equal(result.pairs, 4);
}, { prepared: true }));
test('prepared CLI emits parseable explicitly offline result', () => withFixture(fixture(), dir => {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('./audit.mjs', import.meta.url)), dir, '--prepared'], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr); assert.equal(JSON.parse(result.stdout).requestsSent, 0);
}, { prepared: true }));
test('simulation cannot masquerade as a live run', () => withFixture(fixture(), dir => assert.throws(() => auditEvidence(dir), /offline\/simulated/)));

for (const [name, mutate, pattern] of [
  ['budget differs from factorial matrix', d => d.protocol.maxRequests--, /budget/],
  ['duplicate matrix cell', d => d.plan[1] = structuredClone(d.plan[0]), /unique matrix/],
  ['changed code pool', d => d.plan[0].request.messages[1].content = d.plan[0].request.messages[1].content.replace(d.cases.find(c => c.id === d.plan[0].case).codes[0], 'ZZ-9999'), /frozen codes/],
  ['high similarity missing near name', d => { const j = d.plan.find(j => j.similarity === 'high'); j.request.messages[1].content = j.request.messages[1].content.replace(d.cases.find(c => c.id === j.case).nearNames[0], d.cases.find(c => c.id === j.case).lowNames[0]); }, /frozen codes/],
  ['wrong target index', d => d.plan[0].targetLine++, /target index/],
  ['extra request parameter', d => d.plan[0].request.stream = true, /unregistered/],
  ['changed target placement', d => { const j = d.plan[0], rows = j.request.messages[1].content.split('\n'); [rows[2], rows[3]] = [rows[3], rows[2]]; j.request.messages[1].content = rows.join('\n'); }, /frozen codes/],
  ['target leaks in absent question suffix', d => { const j = d.plan.find(j => j.condition === 'absent'); j.request.messages[1].content += d.cases.find(c => c.id === j.case).answer; }, /constant question|target code/],
  ['unequal-name widths', d => d.cases[0].lowNames[0] = '普通项目', /character length/],
  ['background code duplicates target', d => d.cases[0].codes[0] = d.cases[0].answer, /distinct codes/],
]) test(`reject material mutation: ${name}`, () => {
  const data = fixture(); mutate(data); assert.throws(() => auditMaterials(data.protocol, data.cases, data.plan), pattern);
});

for (const [name, mutate, pattern] of [
  ['request hash', r => r.requestSha256 = '0'.repeat(64), /request equals/],
  ['UTF-8 byte count', r => r.requestBytes++, /UTF-8/],
  ['UTF-16 count', r => r.requestCharacters++, /UTF-16/],
  ['returned model', r => r.model = 'another-model', /returned model/],
  ['returned role', r => r.returnedRole = 'user', /response role/],
  ['truncation', r => r.finishReason = 'length', /finish reason/],
  ['status', r => r.httpStatus = 500, /HTTP status/],
  ['score', r => r.outcome = 'incorrect', /scoring/],
  ['JavaScript-trim mismatch', r => r.content = `\u00a0${r.content}\u00a0`, /scoring/],
  ['false distractor diagnosis', r => r.wrongDistractorCode = true, /distractor diagnostic/],
  ['usage arithmetic', r => r.usage = { prompt_tokens: 1, completion_tokens: 1, total_tokens: 3 }, /usage sum/],
  ['negative usage', r => r.usage = { prompt_tokens: -1, completion_tokens: 1, total_tokens: 0 }, /nonnegative integer/],
  ['fractional usage', r => r.usage = { prompt_tokens: 1.1, completion_tokens: 1, total_tokens: 2.1 }, /nonnegative integer/],
  ['missing usage field', r => r.usage = { prompt_tokens: 1, total_tokens: 1 }, /nonnegative integer/],
  ['invalid time', r => r.startedAt = 'yesterday', /start time/],
  ['negative elapsed', r => r.elapsedMs = -1, /elapsed/],
]) test(`reject response mutation: ${name}`, () => {
  const data = fixture(); mutate(data.records[0]); withFixture(data, dir => assert.throws(() => auditEvidence(dir, { simulated: true }), pattern));
});

test('distractor diagnostic uses actual presented background code', () => {
  const data = fixture(); const r = data.records[0];
  r.content = data.cases.find(c => c.id === r.case).codes[0]; r.outcome = 'incorrect'; r.wrongDistractorCode = true;
  withFixture(data, dir => assert.equal(auditEvidence(dir, { simulated: true }).wrongDistractorCodeCount, 1));
});
test('known and unknown usage are separately counted', () => {
  const data = fixture(); data.records[0].usage = { prompt_tokens: 5, completion_tokens: 2, total_tokens: 7 };
  withFixture(data, dir => assert.deepEqual(auditEvidence(dir, { simulated: true }).returnedUsageOnly,
    { knownTotals: { prompt_tokens: 5, completion_tokens: 2, total_tokens: 7 }, recordsWithUsage: 1, recordsWithUnknownUsage: 127, isBillingEstimate: false }));
});
test('mutating a frozen file without its manifest hash is detected', () => withFixture(fixture(), dir => {
  const p = path.join(dir, 'plan.json'); fs.appendFileSync(p, ' ');
  assert.throws(() => auditEvidence(dir, { simulated: true }), /frozen evidence hash/);
}));
test('live pacing measures completion to next start', () => {
  const data = fixture({ live: true });
  withFixture(data, dir => assert.equal(auditEvidence(dir).minimumObservedPauseMs, 20000));
  data.records[1].startedAt = data.records[0].startedAt;
  withFixture(data, dir => assert.throws(() => auditEvidence(dir), /pacing gap/));
});
for (const status of [401, 403, 429]) test(`HTTP ${status} must stop immediately`, () => {
  const data = fixture(); failure(data.records[0], status);
  withFixture(data, dir => assert.throws(() => auditEvidence(dir, { simulated: true }), /stop policy/));
  data.records = data.records.slice(0, 1);
  withFixture(data, dir => {
    const report = auditEvidence(dir, { simulated: true });
    assert.equal(report.available, 0); assert.equal(report.primary.estimate, null);
    assert.equal(report.completePairCount, 0); assert.equal(report.notAttempted, 127);
  });
});
test('two consecutive failures stop, a valid wrong answer resets the count', () => {
  const data = fixture(); failure(data.records[0]); failure(data.records[1]);
  withFixture(data, dir => assert.throws(() => auditEvidence(dir, { simulated: true }), /stop policy/));
  data.records = data.records.slice(0, 2);
  withFixture(data, dir => assert.equal(auditEvidence(dir, { simulated: true }).outcomeCounts.transport_error, 2));
  const reset = fixture(); failure(reset.records[0]);
  reset.records[1].content = 'nonsense'; reset.records[1].outcome = 'format_error';
  failure(reset.records[2]); failure(reset.records[3]); reset.records = reset.records.slice(0, 4);
  withFixture(reset, dir => assert.equal(auditEvidence(dir, { simulated: true }).available, 1));
});
test('arbitrary early stopping is rejected', () => {
  const data = fixture(); data.records = data.records.slice(0, 8);
  withFixture(data, dir => assert.throws(() => auditEvidence(dir, { simulated: true }), /early stop/));
});
test('a partial final case is excluded from primary but complete pairs remain descriptive', () => {
  const data = fixture(); const finalCaseStart = data.plan.length - 16;
  failure(data.records[finalCaseStart + 8], 429);
  data.records = data.records.slice(0, finalCaseStart + 9);
  withFixture(data, dir => {
    const result = auditEvidence(dir, { simulated: true });
    assert.equal(result.primary.independentCases, 7); assert.equal(result.completePairCount, 15);
    assert.deepEqual(result.primary.excludedCases, ['T5']);
    assert.equal(result.perCase.find(c => c.case === 'T5').pairs.length, 1);
    assert.deepEqual(result.perCase.find(c => c.case === 'T5').missingLengths, [12]);
    assert.ok(result.excludedBlocks.some(b => b.reasons.transport_error === 1));
  });
});
test('high-specific middle failures produce the preregistered +1 interaction', () => {
  const data = fixture();
  for (const r of data.records) if (r.similarity === 'high' && r.condition === 'middle') { r.content = 'UNKNOWN'; r.outcome = 'abstention'; }
  withFixture(data, dir => {
    const result = auditEvidence(dir, { simulated: true });
    assert.equal(result.primary.estimate, 1); assert.equal(result.primary.ceiling, false);
    assert.deepEqual([result.primary.confidenceInterval.lower, result.primary.confidenceInterval.upper], [1, 1]);
    assert.ok(result.perCase.every(c => c.difference === 1));
    assert.equal(result.primary.interpretation, 'supports_hypothesis_on_these_materials');
    assert.equal(result.cells['6/high/middle'].correct, 0); assert.equal(result.cells['6/high/middle'].valid, 8);
  });
});
test('absent mistakes do not enter position interaction but remain counted', () => {
  const data = fixture();
  for (const r of data.records) if (r.condition === 'absent') {
    r.content = data.cases.find(c => c.id === r.case).answer; r.outcome = 'incorrect';
  }
  const result = analyze(data.protocol, data.cases, data.plan, data.records);
  assert.equal(result.primary.estimate, 0); assert.equal(result.completePairs.length, 16);
  assert.equal(result.cells['6/high/absent'].correct, 0);
});
test('bootstrap is deterministic and does not pretend one case supports an interval', () => {
  assert.equal(clusterBootstrap([1]), null);
  assert.deepEqual(clusterBootstrap([-1, 0, 0.5, 1], 1000, 41), clusterBootstrap([-1, 0, 0.5, 1], 1000, 41));
});

test('Java shuffle seed reconstruction matches an independently generated Java reference', () => {
  assert.deepEqual(shuffledCaseIds(['S1', 'S2', 'S3', 'S4', 'S5', 'S6', 'S7', 'S8'], 20260930), ['S4', 'S2', 'S3', 'S8', 'S1', 'S7', 'S6', 'S5']);
});
test('changing the order seed without regenerating the plan is rejected', () => {
  const data = fixture(); data.protocol.orderSeed++;
  assert.throws(() => auditMaterials(data.protocol, data.cases, data.plan), /seeded shuffle/);
});

test('the target answer cannot leak through the system message', () => {
  const data = fixture();
  for (const job of data.plan) job.request.messages[0].content += data.cases[0].answer;
  assert.throws(() => auditMaterials(data.protocol, data.cases, data.plan), /target code/);
});
test('unknown usage must be represented explicitly as null', () => {
  const data = fixture(); delete data.records[0].usage;
  withFixture(data, dir => assert.throws(() => auditEvidence(dir, { simulated: true }), /explicitly null/));
});
