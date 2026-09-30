import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

// An independent Node implementation. It never imports or executes the Java runner.
export const CONDITIONS = ['beginning', 'middle', 'end', 'absent'];
export const SIMILARITIES = ['low', 'high'];
export const ORDERS = [
  ['beginning', 'middle', 'absent', 'end'],
  ['middle', 'end', 'beginning', 'absent'],
  ['end', 'absent', 'middle', 'beginning'],
  ['absent', 'beginning', 'end', 'middle'],
];
const VALID = new Set(['correct', 'incorrect', 'abstention', 'correct_abstention', 'format_error']);
const FAILURES = new Set(['protocol_error', 'transport_error']);
const CODE = /^[A-Z]{2}-[0-9]{4}$/;
const PLACEHOLDER = '独立补充归档项目';
const mean = xs => xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null;
const sorted = xs => [...xs].sort((a, b) => typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b)));
export const sha256 = value => crypto.createHash('sha256').update(value).digest('hex');
export const javaTrim = value => value.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '');
export function score(content, expected, absent) {
  assert.equal(typeof content, 'string', 'response content is a string');
  const s = javaTrim(content);
  if (s === 'UNKNOWN') return absent ? 'correct_abstention' : 'abstention';
  if (!CODE.test(s)) return 'format_error';
  return !absent && s === expected ? 'correct' : 'incorrect';
}
// Gson JsonElement.toString() serializes this frozen request's double as 0.0.
export function requestBytes(request) {
  assert.equal(request.temperature, 0, 'this protocol freezes temperature=0');
  return JSON.stringify(request).replace('"temperature":0,', '"temperature":0.0,')
    .replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
}
export function parseRecords(request) {
  assert.equal(request.messages?.length, 2, 'independent two-message request');
  assert.equal(request.messages[0].role, 'system');
  assert.equal(request.messages[1].role, 'user');
  const body = request.messages[1].content;
  assert.equal(typeof body, 'string');
  const sections = body.split('<records>\n');
  assert.equal(sections.length, 2, 'one records opening delimiter');
  const tail = sections[1].split('\n</records>');
  assert.equal(tail.length, 2, 'one records closing delimiter');
  const lines = tail[0].split('\n');
  const records = lines.map(line => {
    const match = /^(.+)：交接编号为 ([A-Z]{2}-[0-9]{4})。$/.exec(line);
    assert.ok(match, 'uniform record template and code format');
    return { name: match[1], code: match[2] };
  });
  return { lines, records, before: sections[0], after: tail[1], text: tail[0] };
}
const recordLine = (name, code) => `${name}：交接编号为 ${code}。`;
const background = (c, n, similarity) => {
  const slots = Array.from({ length: 6 }, (_, k) => Math.floor((2 * k + 1) * n / 12));
  return Array.from({ length: n }, (_, i) => {
    const slot = slots.indexOf(i);
    return recordLine(slot >= 0 ? c[similarity === 'high' ? 'nearNames' : 'lowNames'][slot] : `归档${String(i).padStart(4, '0').split('').map(d => '零一二三四五六七八九'[Number(d)]).join('')}项目`, c.codes[i]);
  });
};
const targetPosition = (condition, n) => condition === 'beginning' ? 0 : condition === 'end' ? n : n / 2;
const tuple = j => [j.case, j.distractors, j.similarity, j.condition].join('/');

// java.util.Random's published 48-bit LCG plus Collections.shuffle's backward swaps.
export function shuffledCaseIds(ids, seed) {
  const mask = (1n << 48n) - 1n, multiplier = 0x5deece66dn;
  let state = (BigInt(seed) ^ multiplier) & mask;
  const next31 = () => { state = (state * multiplier + 11n) & mask; return Number(state >> 17n); };
  const nextInt = bound => {
    if ((bound & -bound) === bound) return Math.floor(bound * next31() / 2147483648);
    let bits, value;
    do { bits = next31(); value = bits % bound; } while (bits - value + bound - 1 >= 2147483648);
    return value;
  };
  const result = [...ids];
  for (let i = result.length; i > 1; i--) { const other = nextInt(i); [result[i - 1], result[other]] = [result[other], result[i - 1]]; }
  return result;
}

export function auditMaterials(protocol, cases, plan) {
  assert.ok(protocol.id && Number.isSafeInteger(protocol.version), 'versioned protocol');
  assert.equal(protocol.model, 'agnes-3.0-flash', 'frozen model');
  assert.equal(protocol.endpoint, 'https://apihub.agnes-ai.com/v1/chat/completions', 'frozen endpoint');
  assert.equal(protocol.temperature, 0);
  assert.equal(protocol.maxTokens, 1024);
  assert.deepEqual(protocol.conditions, CONDITIONS);
  assert.deepEqual(protocol.similarities, SIMILARITIES);
  assert.ok(Array.isArray(protocol.lengths) && protocol.lengths.length > 0);
  assert.equal(new Set(protocol.lengths).size, protocol.lengths.length, 'unique lengths');
  assert.ok(protocol.lengths.every(n => Number.isSafeInteger(n) && n >= 6 && n % 2 === 0), 'even lengths allow exact middle');
  assert.ok(Number.isSafeInteger(protocol.minPauseMs) && protocol.minPauseMs >= 20000, 'at least 20s pause');
  assert.equal(protocol.consecutiveFailureLimit, 2);
  assert.ok(Number.isSafeInteger(protocol.orderSeed), 'frozen order seed');
  assert.ok(cases.length > 0 && cases.length % 4 === 0, 'case count supports balanced four-order design');
  assert.equal(new Set(cases.map(c => c.id)).size, cases.length, 'unique case IDs');
  assert.equal(new Set(cases.map(c => c.project)).size, cases.length, 'unique target projects');
  assert.equal(new Set(cases.map(c => c.answer)).size, cases.length, 'unique target answers');
  const maxLength = Math.max(...protocol.lengths);
  for (const c of cases) {
    assert.ok(typeof c.id === 'string' && c.id && !c.id.includes('/'));
    assert.equal(typeof c.project, 'string');
    assert.equal(c.project.length, PLACEHOLDER.length, 'target and placeholder equal UTF-16 length');
    assert.ok(CODE.test(c.answer), 'target code format');
    assert.equal(c.nearNames?.length, 6, 'six near names');
    assert.equal(c.lowNames?.length, 6, 'six unrelated matched names');
    const names = [c.project, ...c.nearNames, ...c.lowNames, PLACEHOLDER];
    assert.equal(new Set(names).size, names.length, 'distinct full project names');
    for (const name of names) {
      assert.equal(name.length, c.project.length, 'matched project-name character length');
      assert.equal(Buffer.byteLength(name), Buffer.byteLength(c.project), 'matched project-name UTF-8 length');
      assert.ok(!/[\n\r<>：]/.test(name), 'project names cannot alter record structure');
    }
    assert.ok(c.codes?.length >= maxLength + 1, 'complete frozen distractor code pool');
    assert.ok(c.codes.every(code => CODE.test(code)), 'background code format');
    assert.equal(new Set([c.answer, ...c.codes]).size, c.codes.length + 1, 'distinct codes, no target-code leak');
  }
  const planned = cases.length * protocol.lengths.length * SIMILARITIES.length * CONDITIONS.length;
  assert.equal(protocol.maxRequests, planned, 'dynamic protocol budget equals factorial matrix');
  assert.equal(plan.length, planned, 'planned request budget');
  assert.equal(new Set(plan.map(tuple)).size, planned, 'unique matrix cells');
  const caseMap = new Map(cases.map(c => [c.id, c]));
  const expectedOrder = [], orderedIds = shuffledCaseIds(cases.map(c => c.id), protocol.orderSeed);
  let expectedBlock = 0, expectedPair = 0;
  for (let i = 0; i < orderedIds.length; i++) for (const n of protocol.lengths) {
    expectedPair++;
    for (const similarity of i % 2 ? ['high', 'low'] : ['low', 'high']) {
      expectedBlock++;
      for (const condition of ORDERS[i % 4]) expectedOrder.push({ case: orderedIds[i], distractors: n, similarity, condition, block: expectedBlock, pair: expectedPair });
    }
  }
  assert.deepEqual(plan.map(({ case: id, distractors, similarity, condition, block, pair }) => ({ case: id, distractors, similarity, condition, block, pair })), expectedOrder, 'independent seeded shuffle and complete request ordering');
  const blocks = new Map(), pairs = new Map();
  let commonSystem, commonBefore, questionByCase = new Map();
  for (const j of plan) {
    const c = caseMap.get(j.case);
    assert.ok(c, 'known case');
    assert.ok(protocol.lengths.includes(j.distractors), 'frozen length');
    assert.ok(SIMILARITIES.includes(j.similarity));
    assert.ok(CONDITIONS.includes(j.condition));
    assert.ok(Number.isSafeInteger(j.block) && j.block > 0, 'positive block ID');
    assert.ok(Number.isSafeInteger(j.pair) && j.pair > 0, 'positive pair ID');
    const r = j.request;
    assert.deepEqual(Object.keys(r).sort(), ['max_tokens', 'messages', 'model', 'temperature'], 'no unregistered request parameters');
    assert.equal(r.model, protocol.model);
    assert.equal(r.temperature, protocol.temperature);
    assert.equal(r.max_tokens, protocol.maxTokens);
    const parsed = parseRecords(r);
    assert.equal(typeof r.messages[0].content, 'string');
    assert.ok(r.messages[0].content.length > 0);
    commonSystem ??= r.messages[0].content;
    commonBefore ??= parsed.before;
    assert.equal(r.messages[0].content, commonSystem, 'constant system prompt');
    assert.equal(parsed.before, commonBefore, 'constant preamble');
    assert.ok(parsed.after.includes(c.project), 'question asks full target name after records');
    assert.ok(!parsed.before.includes(c.project) && !parsed.before.includes(c.answer), 'no target-specific preamble');
    if (!questionByCase.has(c.id)) questionByCase.set(c.id, parsed.after);
    assert.equal(parsed.after, questionByCase.get(c.id), 'constant question across conditions');
    const n = j.distractors, absent = j.condition === 'absent', pos = targetPosition(j.condition, n);
    const expected = background(c, n, j.similarity);
    expected.splice(pos, 0, recordLine(absent ? PLACEHOLDER : c.project, absent ? c.codes[n] : c.answer));
    assert.deepEqual(parsed.lines, expected, 'exact background order, position, six-name substitution, placeholder, and frozen codes');
    assert.equal(parsed.records.length, n + 1, 'N background plus one target or placeholder');
    assert.equal(new Set(parsed.lines.map(line => line.length)).size, 1, 'uniform record character width');
    assert.equal(new Set(parsed.lines.map(line => Buffer.byteLength(line))).size, 1, 'uniform record UTF-8 width');
    assert.equal(new Set(parsed.records.map(x => x.code)).size, n + 1, 'unique record codes');
    assert.equal(j.targetLine, absent ? -1 : pos, 'recorded target index');
    assert.equal(parsed.records.filter(x => x.name === c.project).length, absent ? 0 : 1);
    assert.equal(r.messages.map(m => m.content).join('\n').split(c.answer).length - 1, absent ? 0 : 1, 'target code appears once, or is absent everywhere');
    assert.equal(parsed.records.filter(x => c.nearNames.includes(x.name)).length, j.similarity === 'high' ? 6 : 0);
    const wire = requestBytes(r);
    if (j.requestSha256 !== undefined) assert.equal(j.requestSha256, sha256(wire), 'planned request hash');
    if (j.requestCharacters !== undefined) assert.equal(j.requestCharacters, wire.length, 'planned UTF-16 character count');
    if (j.requestBytes !== undefined) assert.equal(j.requestBytes, Buffer.byteLength(wire), 'planned UTF-8 byte count');
    if (j.expected !== undefined) assert.equal(j.expected, absent ? 'UNKNOWN' : c.answer);
    if (!blocks.has(j.block)) blocks.set(j.block, []);
    blocks.get(j.block).push(j);
    if (!pairs.has(j.pair)) pairs.set(j.pair, []);
    pairs.get(j.pair).push(j);
  }
  assert.equal(blocks.size, planned / 4, 'four-request blocks');
  assert.equal(pairs.size, planned / 8, 'eight-request low/high pairs');
  for (let index = 0; index < plan.length; index += 4) {
    const group = plan.slice(index, index + 4), first = group[0];
    assert.equal(first.block, index / 4 + 1, 'contiguous sequential blocks');
    assert.ok(group.every(j => j.block === first.block && j.case === first.case && j.distractors === first.distractors && j.similarity === first.similarity && j.pair === first.pair));
    assert.ok(ORDERS.some(order => JSON.stringify(order) === JSON.stringify(group.map(j => j.condition))), 'Williams balanced condition order');
  }
  const pairFirst = [];
  for (let index = 0; index < plan.length; index += 8) {
    const group = plan.slice(index, index + 8), first = group[0];
    assert.equal(first.pair, index / 8 + 1, 'contiguous sequential pairs');
    assert.ok(group.every(j => j.pair === first.pair && j.case === first.case && j.distractors === first.distractors));
    assert.notEqual(group[0].similarity, group[4].similarity, 'paired low/high blocks');
    for (const condition of CONDITIONS) {
      const [low, high] = SIMILARITIES.map(s => group.find(j => j.similarity === s && j.condition === condition));
      const a = parseRecords(low.request), b = parseRecords(high.request);
      assert.equal(requestBytes(low.request).length, requestBytes(high.request).length, 'matched low/high request characters');
      assert.equal(Buffer.byteLength(requestBytes(low.request)), Buffer.byteLength(requestBytes(high.request)), 'matched low/high request bytes');
      assert.deepEqual(a.records.map(x => x.code), b.records.map(x => x.code), 'matched low/high code pool');
      assert.equal(a.lines.filter((line, i) => line !== b.lines[i]).length, 6, 'only six project names differ');
    }
    pairFirst.push(first);
  }
  for (const n of protocol.lengths) {
    const group = pairFirst.filter(j => j.distractors === n);
    assert.equal(group.filter(j => j.similarity === 'low').length, cases.length / 2, 'balanced similarity block order per length');
    for (const similarity of SIMILARITIES) {
      const groups = [...blocks.values()].filter(g => g[0].distractors === n && g[0].similarity === similarity);
      for (const order of ORDERS) assert.equal(groups.filter(g => JSON.stringify(g.map(j => j.condition)) === JSON.stringify(order)).length, cases.length / 4, 'balanced condition order per stratum');
    }
  }
  if (protocol.lengths.length === 2) for (let i = 1; i < pairFirst.length; i++) assert.notEqual(pairFirst[i - 1].distractors, pairFirst[i].distractors, 'alternating material lengths');
  return { planned, blocks, pairs, caseMap };
}

function percentile(values, p) {
  const position = (values.length - 1) * p, lo = Math.floor(position), fraction = position - lo;
  return values[lo] + (values[Math.ceil(position)] - values[lo]) * fraction;
}
export function clusterBootstrap(values, samples = 10000, seed = 20260930) {
  if (values.length < 2) return null;
  assert.ok(Number.isSafeInteger(samples) && samples >= 1000 && samples <= 1000000, 'bootstrap sample count');
  assert.ok(Number.isSafeInteger(seed), 'bootstrap seed');
  // Fully specified deterministic xorshift32; sampling unit is a whole case.
  let state = seed >>> 0 || 0x9e3779b9;
  const uniform = () => { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return (state >>> 0) / 4294967296; };
  const estimates = Array.from({ length: samples }, () => mean(Array.from({ length: values.length }, () => values[Math.floor(uniform() * values.length)]))).sort((a, b) => a - b);
  return { lower: percentile(estimates, 0.025), upper: percentile(estimates, 0.975), confidence: 0.95,
    method: 'percentile case-cluster bootstrap; resample whole cases including all lengths and conditions', samples, seed, rng: 'xorshift32' };
}

export function analyze(protocol, cases, plan, records) {
  const byIndex = new Map(records.map(r => [r.index, r]));
  const groups = new Map();
  for (let i = 0; i < plan.length; i++) {
    const j = plan[i];
    if (!groups.has(j.block)) groups.set(j.block, []);
    groups.get(j.block).push({ job: j, record: byIndex.get(i + 1) });
  }
  const completeBlocks = [], excludedBlocks = [];
  for (const [block, rows] of groups) {
    const reasons = {};
    for (const { record } of rows) {
      const reason = !record ? 'not_attempted' : !VALID.has(record.outcome) ? record.outcome : null;
      if (reason) reasons[reason] = (reasons[reason] || 0) + 1;
    }
    if (!Object.keys(reasons).length) completeBlocks.push(block);
    else excludedBlocks.push({ block, case: rows[0].job.case, distractors: rows[0].job.distractors, similarity: rows[0].job.similarity, reasons });
  }
  const complete = new Set(completeBlocks), pairs = new Map();
  for (const j of plan) if (!pairs.has(j.pair)) pairs.set(j.pair, plan.filter(x => x.pair === j.pair));
  const completePairs = [], excludedPairs = [];
  for (const [pair, jobs] of pairs) {
    const blockIds = [...new Set(jobs.map(j => j.block))];
    if (!blockIds.every(b => complete.has(b))) {
      excludedPairs.push({ pair, case: jobs[0].case, distractors: jobs[0].distractors, excludedBlocks: blockIds.filter(b => !complete.has(b)) });
      continue;
    }
    const losses = {};
    for (const similarity of SIMILARITIES) {
      const accuracy = Object.fromEntries(jobs.filter(j => j.similarity === similarity && j.condition !== 'absent').map(j => {
        const index = plan.indexOf(j) + 1;
        return [j.condition, byIndex.get(index).outcome === 'correct' ? 1 : 0];
      }));
      losses[similarity] = (accuracy.beginning + accuracy.end) / 2 - accuracy.middle;
    }
    completePairs.push({ pair, case: jobs[0].case, distractors: jobs[0].distractors, middleLossLow: losses.low, middleLossHigh: losses.high, difference: losses.high - losses.low });
  }
  const perCase = cases.map(c => {
    const pairs = completePairs.filter(p => p.case === c.id);
    return { case: c.id, completeLengths: pairs.map(p => p.distractors), missingLengths: protocol.lengths.filter(n => !pairs.some(p => p.distractors === n)), pairs,
      difference: mean(pairs.map(p => p.difference)), eligibleForPrimary: pairs.length === protocol.lengths.length };
  });
  const primaryCases = perCase.filter(c => c.eligibleForPrimary), values = primaryCases.map(c => c.difference);
  const estimate = mean(values);
  const ci = clusterBootstrap(values, protocol.bootstrapSamples ?? 10000, protocol.bootstrapSeed ?? 20260930);
  const threshold = protocol.practicalThreshold ?? 0.1;
  const usedBlocks = new Set(plan.filter(j => primaryCases.some(c => c.case === j.case)).map(j => j.block));
  const primaryRows = records.filter(r => usedBlocks.has(r.block));
  const ceiling = primaryRows.length > 0 && primaryRows.filter(r => r.condition !== 'absent').every(r => r.outcome === 'correct');
  const interpretation = estimate === null ? 'no_complete_cases' : ceiling ? 'ceiling_on_these_materials' : ci === null ? 'insufficient_independent_cases' : estimate >= threshold && ci.lower > 0 ? 'supports_hypothesis_on_these_materials' : 'insufficient_evidence';
  const cells = {};
  for (const j of plan) {
    const key = [j.distractors, j.similarity, j.condition].join('/');
    cells[key] ??= { planned: 0, attempted: 0, valid: 0, correct: 0, completeBlockValid: 0, completeBlockCorrect: 0, outcomes: {} };
    cells[key].planned++;
  }
  for (const r of records) {
    const cell = cells[[r.distractors, r.similarity, r.condition].join('/')];
    cell.attempted++; cell.outcomes[r.outcome] = (cell.outcomes[r.outcome] || 0) + 1;
    if (VALID.has(r.outcome)) {
      cell.valid++; const good = ['correct', 'correct_abstention'].includes(r.outcome);
      if (good) cell.correct++;
      if (complete.has(r.block)) { cell.completeBlockValid++; if (good) cell.completeBlockCorrect++; }
    }
  }
  return { completeBlocks: sorted(completeBlocks), excludedBlocks, completePairs, excludedPairs, cells, perCase,
    primary: { metric: 'mean_case(mean_length(middle_loss_high-middle_loss_low))', eligibility: 'both low/high blocks complete at every planned length', independentCases: values.length,
      excludedCases: perCase.filter(c => !c.eligibleForPrimary).map(c => c.case), estimate, confidenceInterval: ci, practicalThreshold: threshold,
      practicalThresholdMet: estimate !== null && estimate >= threshold, ceiling, interpretation },
    secondaryAvailablePairs: { label: 'descriptive only; incomplete cases/lengths may change weighting', pairs: completePairs.length,
      equalPairEstimate: mean(completePairs.map(p => p.difference)), equalAvailableCaseEstimate: mean(perCase.filter(c => c.difference !== null).map(c => c.difference)) },
    caution: 'The sampling units are lexical/code instances from one template family, not diverse task structures. Case-cluster intervals are coarse and describe only these materials; no 10-point power claim. Missing blocks may be selective. No inference about general model rankings or attention mechanisms.' };
}

export function auditEvidence(dir, { prepared = false, simulated = false } = {}) {
  assert.ok(!(prepared && simulated), 'choose prepared or simulated, not both');
  const read = file => JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8'));
  const protocol = read('protocol.json'), cases = read('cases.json'), plan = read('plan.json'), manifest = read('manifest.json');
  const material = auditMaterials(protocol, cases, plan);
  for (const file of ['protocol.json', 'cases.json', 'plan.json']) {
    const raw = fs.readFileSync(path.join(dir, file));
    assert.equal(manifest.evidenceSha256?.[file], sha256(raw), `frozen evidence hash: ${file}`);
    assert.equal(manifest.evidenceLfSha256?.[file], sha256(raw.toString('utf8').replace(/\r\n?/g, '\n')), `LF-normalized evidence hash: ${file}`);
  }
  assert.ok(Number.isSafeInteger(manifest.offlineChecks) && manifest.offlineChecks > 0, 'offline checks recorded');
  const files = fs.readdirSync(dir).filter(f => /^attempt-/.test(f));
  assert.ok(files.every(f => /^attempt-\d+\.json$/.test(f)), 'only numbered attempt files');
  files.sort((a, b) => Number(a.match(/\d+/)[0]) - Number(b.match(/\d+/)[0]));
  if (prepared) {
    assert.equal(manifest.kind, 'offline-preparation; no model requests');
    assert.equal(files.length, 0, 'preparation sends no requests');
    return { kind: 'offline-preparation; no model requests', planned: plan.length, requestsSent: 0, cases: cases.length, lengths: protocol.lengths, blocks: material.blocks.size, pairs: material.pairs.size, offlineChecks: manifest.offlineChecks, materialIntegrity: 'passed' };
  }
  if (simulated) assert.equal(manifest.kind, 'simulated-offline; no model requests');
  else assert.ok(manifest.kind === 'live-model-run' && !manifest.simulated, 'offline/simulated artifacts or unknown manifest kinds cannot be audited as live evidence');
  const summary = read('summary.json');
  assert.ok(files.length > 0 && files.length <= protocol.maxRequests, 'request budget');
  const records = [], gaps = [], counts = {}, outcomeCounts = {};
  const usage = { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 };
  let usageAvailable = 0, usageUnknown = 0, failures = 0, previous;
  for (let i = 0; i < files.length; i++) {
    const r = read(files[i]), j = plan[i], c = material.caseMap.get(j.case);
    assert.equal(Number(files[i].match(/\d+/)[0]), i + 1, 'contiguous attempt filenames');
    assert.equal(r.index, i + 1, 'contiguous attempt indices');
    for (const key of ['case', 'block', 'pair', 'condition', 'distractors', 'similarity', 'targetLine']) assert.equal(r[key], j[key], `attempt copies planned ${key}`);
    assert.equal(Boolean(r.simulated), simulated, 'explicit separation of simulated and live evidence');
    const wire = requestBytes(j.request);
    assert.equal(r.requestSha256, sha256(wire), 'attempt request equals frozen preparation');
    assert.equal(r.requestCharacters, wire.length, 'UTF-16 character count');
    assert.equal(r.requestBytes, Buffer.byteLength(wire), 'UTF-8 byte count');
    assert.ok(Number.isFinite(Date.parse(r.startedAt)), 'parseable start time');
    assert.ok(Number.isSafeInteger(r.elapsedMs) && r.elapsedMs >= 0, 'nonnegative elapsed milliseconds');
    if (previous) {
      const gap = Date.parse(r.startedAt) - Date.parse(previous.startedAt) - previous.elapsedMs;
      gaps.push(gap); if (!simulated) assert.ok(gap >= protocol.minPauseMs - 30, 'minimum end-to-start pacing gap');
    }
    assert.ok(VALID.has(r.outcome) || FAILURES.has(r.outcome), 'recognized outcome');
    if (VALID.has(r.outcome)) {
      assert.equal(r.httpStatus, 200, 'usable response HTTP status');
      assert.equal(r.finishReason, 'stop', 'usable response finish reason');
      assert.equal(r.model, protocol.model, 'usable response returned model');
      assert.equal(r.returnedRole, 'assistant', 'usable response role');
      assert.equal(r.outcome, score(r.content, c.answer, r.condition === 'absent'), 'independent exact Java-trim scoring');
      const chosen = javaTrim(r.content), parsed = parseRecords(j.request);
      const wrongDistractor = r.outcome === 'incorrect' && parsed.records.some(x => x.code === chosen && x.name !== c.project);
      assert.equal(r.wrongDistractorCode, wrongDistractor, 'wrong-distractor diagnostic');
      failures = 0;
    } else {
      failures++;
      if (r.outcome === 'protocol_error') assert.equal(r.httpStatus, 200, 'protocol errors follow HTTP 200');
      if (r.httpStatus !== undefined && r.httpStatus !== null) assert.ok(Number.isSafeInteger(r.httpStatus) && r.httpStatus >= 100 && r.httpStatus <= 599);
    }
    assert.ok(Object.hasOwn(r, 'usage'), 'unknown usage is explicitly null');
    if (r.usage === null) usageUnknown++;
    else {
      assert.equal(typeof r.usage, 'object');
      for (const key of Object.keys(usage)) { assert.ok(Number.isSafeInteger(r.usage[key]) && r.usage[key] >= 0, 'reported usage is nonnegative integer'); usage[key] += r.usage[key]; }
      assert.equal(r.usage.total_tokens, r.usage.prompt_tokens + r.usage.completion_tokens, 'usage sum');
      usageAvailable++;
    }
    assert.ok(i === files.length - 1 || (![429, 401, 403].includes(r.httpStatus) && failures < protocol.consecutiveFailureLimit), 'stop policy was ignored');
    const group = [r.distractors, r.similarity, r.condition].join('/');
    counts[group] ??= {}; counts[group][r.outcome] = (counts[group][r.outcome] || 0) + 1;
    outcomeCounts[r.outcome] = (outcomeCounts[r.outcome] || 0) + 1;
    records.push(r); previous = r;
  }
  const analysis = analyze(protocol, cases, plan, records);
  assert.equal(summary.planned, plan.length, 'summary planned budget');
  assert.equal(summary.attempted, files.length, 'summary attempt count');
  assert.equal(summary.stoppedEarly, files.length < plan.length, 'summary stopped-early flag');
  assert.deepEqual(summary.completeBlocks, analysis.completeBlocks, 'summary complete blocks');
  assert.deepEqual(summary.conditions, counts, 'summary condition counts');
  if (summary.incompleteAttemptedBlocks !== undefined) assert.deepEqual(summary.incompleteAttemptedBlocks, analysis.excludedBlocks.filter(b => records.some(r => r.block === b.block)).map(b => b.block), 'summary incomplete attempted blocks');
  if (summary.stoppedEarly) assert.ok([429, 401, 403].includes(records.at(-1).httpStatus) || failures >= protocol.consecutiveFailureLimit, 'early stop must match frozen stop rule');
  assert.ok(Number.isFinite(Date.parse(summary.finishedAt)), 'parseable finish time');
  assert.ok(Date.parse(summary.finishedAt) >= Date.parse(records.at(-1).startedAt) + records.at(-1).elapsedMs - 2, 'summary finish follows last request');
  const available = records.filter(r => VALID.has(r.outcome)).length;
  return { kind: simulated ? 'simulated-offline; no model requests' : 'live-evidence-audit', model: protocol.model, protocolId: protocol.id,
    planned: plan.length, attempted: records.length, available, notAttempted: plan.length - records.length,
    completePairCount: analysis.completePairs.length, outcomeCounts, wrongDistractorCodeCount: records.filter(r => r.wrongDistractorCode === true).length,
    returnedUsageOnly: { knownTotals: usageAvailable ? usage : null, recordsWithUsage: usageAvailable, recordsWithUnknownUsage: usageUnknown, isBillingEstimate: false },
    minimumObservedPauseMs: gaps.length ? Math.min(...gaps) : null, pacingValidation: simulated ? 'not_assessed_simulation' : 'passed', startedAt: records[0].startedAt, finishedAt: summary.finishedAt,
    materialIntegrity: 'passed', frozenPlanAttemptRequestHashes: 'matched', ...analysis };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2), dir = args.find(arg => !arg.startsWith('--'));
    assert.ok(dir && args.every(arg => arg === dir || ['--prepared', '--simulated'].includes(arg)), 'Usage: node audit.mjs <evidence-directory> [--prepared|--simulated]');
    console.log(JSON.stringify(auditEvidence(dir, { prepared: args.includes('--prepared'), simulated: args.includes('--simulated') }), null, 2));
  } catch (error) { console.error(`Audit failed: ${error.message}`); process.exitCode = 1; }
}
