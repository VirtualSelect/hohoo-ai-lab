import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import { parseClassification, validateClassification, unsafeCast, unwrapOneJsonFence } from "./src/classification.ts";
const here=path.dirname(fileURLToPath(import.meta.url));
process.chdir(here);
const out=process.argv[2];
if(!out)throw Error("Usage: npm run experiment -- evidence/new-directory");
fs.mkdirSync(out,{recursive:true});
if(fs.readdirSync(out).length)throw Error("Evidence directory must be empty");
const hash=file=>crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
const fixtures=JSON.parse(fs.readFileSync("fixtures.json","utf8"));
const root=path.resolve(here,"../..");
const javaHome=process.env.JAVA_HOME;
const bin=name=>javaHome?path.join(javaHome,"bin",name+(process.platform==="win32"?".exe":"")):name;
const gson=path.join(os.homedir(),".m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar");
const classes=path.join(root,"demos/04-structured-output/target/classes");
fs.mkdirSync(".cache/java",{recursive:true});
const cp=[classes,gson].join(path.delimiter);
execFileSync(bin("javac"),["-encoding","UTF-8","-cp",cp,"-d",".cache/java","java/ContractProbe.java"],{stdio:"pipe"});
const java=JSON.parse(execFileSync(bin("java"),["-cp",[cp,path.resolve(".cache/java")].join(path.delimiter),"ContractProbe","fixtures.json"],{encoding:"utf8"}));
function accept(fn,raw){try{fn(raw);return true;}catch{return false;}}
const rows=fixtures.map(f=>({
  id:f.id,expectedAccepted:f.expectedAccepted,
  castAccepted:accept(unsafeCast,f.input),
  objectAccepted:accept(raw=>validateClassification(JSON.parse(raw)),f.input),
  wireAccepted:accept(parseClassification,f.input),
  javaAccepted:java.rows.find(r=>r.id===f.id).accepted
}));
for(const r of rows){assert.equal(r.wireAccepted,r.expectedAccepted,r.id);assert.equal(r.javaAccepted,r.expectedAccepted,r.id);}
const savedFile=path.join(root,"demos/04-structured-output/evidence/live-20260928/live-1.json");
const saved=JSON.parse(fs.readFileSync(savedFile,"utf8"));
const replay={kind:"offline replay of saved real response, no API call",source:"../../demos/04-structured-output/evidence/live-20260928/live-1.json",
 sourceSha256:hash(savedFile),sourceModel:saved.response.model,strictAccepted:accept(parseClassification,saved.response.content),
 adaptedAccepted:accept(raw=>parseClassification(unwrapOneJsonFence(raw)),saved.response.content)};
const files=["fixtures.json","src/classification.ts","src/type-contract.ts","java/ContractProbe.java","run.mjs","package.json","package-lock.json","tsconfig.json"];
const report={kind:"synthetic contract differential test + historical response replay; not model benchmark",
 at:new Date().toISOString(),node:process.version,typescript:JSON.parse(fs.readFileSync("node_modules/typescript/package.json")).version,
 java:java.javaVersion,
 commit:execFileSync("git",["rev-parse","HEAD"],{encoding:"utf8"}).trim(),
 sourceSha256:Object.fromEntries(files.map(f=>[f,hash(f)])),
 javaValidatorSha256:hash(path.join(root,"demos/04-structured-output/src/main/java/com/hohoo/ailab/structured/Classification.java")),
 counts:{fixtures:rows.length,castFalseAccepts:rows.filter(r=>r.castAccepted&&!r.expectedAccepted).length,
 objectFalseAccepts:rows.filter(r=>r.objectAccepted&&!r.expectedAccepted).length,
 wireJavaAgreements:rows.filter(r=>r.wireAccepted===r.javaAccepted).length},rows,replay};
fs.writeFileSync(path.join(out,"report.json"),JSON.stringify(report,null,2)+"\n");
fs.copyFileSync("fixtures.json",path.join(out,"fixtures.json"));
console.log(JSON.stringify({counts:report.counts,replay},null,2));
