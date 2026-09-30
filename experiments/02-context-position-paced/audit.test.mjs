import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
import test from 'node:test';
const source=new URL('./evidence/20260930-agnes3-live/',import.meta.url);
const audit=new URL('./audit.mjs',import.meta.url);
for(const [name,mutate] of [
 ['original',null],
 ['wrong model',r=>{r.model='unexpected-model';}],
 ['wrong score',r=>{r.outcome='incorrect';}],
 ['wrong usage',r=>{r.usage.total_tokens++;}],
 ['changed request hash',r=>{r.requestSha256='0'.repeat(64);}],
]) test('offline evidence audit: '+name,()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'hohoo-l1-audit-'));
 try {
  fs.cpSync(source,dir,{recursive:true});
  if(mutate){const p=path.join(dir,'attempt-01.json');const r=JSON.parse(fs.readFileSync(p,'utf8'));mutate(r);fs.writeFileSync(p,JSON.stringify(r));}
  const result=spawnSync(process.execPath,[audit.pathname.replace(/^\/(\w:)/,'$1'),dir],{encoding:'utf8'});
  if(mutate)assert.notEqual(result.status,0,name);else assert.equal(result.status,0,result.stderr);
 } finally {
  const resolved=path.resolve(dir),base=path.resolve(os.tmpdir())+path.sep;
  assert.ok(resolved.startsWith(base)&&path.basename(resolved).startsWith('hohoo-l1-audit-'));
  fs.rmSync(resolved,{recursive:true});
 }
});
