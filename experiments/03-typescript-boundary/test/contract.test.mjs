import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { parseClassification, validateClassification, unsafeCast, unwrapOneJsonFence } from "../src/classification.ts";
const fixtures=JSON.parse(fs.readFileSync(new URL("../fixtures.json",import.meta.url)));
for(const f of fixtures)test(f.id,()=>{
  if(f.expectedAccepted)assert.ok(parseClassification(f.input));
  else assert.throws(()=>parseClassification(f.input));
});
test("compile-time cast admits numeric tags; runtime validation rejects",()=>{
  const raw='{"category":"llm","tags":[12]}';
  assert.equal(unsafeCast(raw).tags[0],12);
  assert.throws(()=>parseClassification(raw));
});
test("JSON.parse destroys duplicate-key evidence before object validation",()=>{
  const raw='{"category":"java","category":"llm","tags":["Token"]}';
  assert.equal(validateClassification(JSON.parse(raw)).category,"llm");
  assert.throws(()=>parseClassification(raw),/duplicate_field/);
});
test("validated output is a detached immutable snapshot",()=>{
  const input={category:"llm",tags:["Token"]};
  const output=validateClassification(input);
  input.tags[0]="Changed";
  assert.equal(output.tags[0],"Token");
  assert.throws(()=>output.tags.push("Changed"),TypeError);
});
test("single-fence adaptation remains explicit and does not bypass validation",()=>{
  assert.equal(parseClassification(unwrapOneJsonFence('\x60\x60\x60json\n{"category":"llm","tags":["Token"]}\n\x60\x60\x60')).category,"llm");
  assert.throws(()=>parseClassification(unwrapOneJsonFence('Here:\n\x60\x60\x60json\n{"category":"llm","tags":["Token"]}\n\x60\x60\x60')));
});
test("wrong semantic label is outside this contract",()=>{
  assert.equal(parseClassification('{"category":"llm","tags":["炒饭"]}').category,"llm");
});
