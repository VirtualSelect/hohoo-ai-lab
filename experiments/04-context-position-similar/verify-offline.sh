#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
if [[ -z "${LAB_CLASSPATH:-}" ]]; then
  mvn -q compile
  run_java() { mvn -q exec:java "-Dexec.args=$*"; }
else
  run_java() { java -cp "$LAB_CLASSPATH" com.hohoo.ailab.context.ContextExperiment "$@"; }
fi
run_java --self-test
run_java --dry-run
node --test audit.test.mjs
run_java --prepare "$tmp/prepared"
node audit.mjs "$tmp/prepared" --prepared
run_java --verify-prepared "$tmp/prepared"
cp -R "$tmp/prepared" "$tmp/tampered"
node -e 'const fs=require("node:fs");const p=process.argv[1];const v=JSON.parse(fs.readFileSync(p));v.version++;fs.writeFileSync(p,JSON.stringify(v));' "$tmp/tampered/protocol.json"
if run_java --verify-prepared "$tmp/tampered" >"$tmp/tamper.log" 2>&1; then
  echo 'ERROR: tampered preparation was accepted' >&2; exit 1
fi
grep -q prepared_hash "$tmp/tamper.log"
echo 'PASS tampered preparation rejected before any model request'
# Remove the count approval in a subshell; --run must fail before reading a key.
if (unset LAB_APPROVED_MAX_REQUESTS; run_java --run "$tmp/forbidden-live") >"$tmp/gate.log" 2>&1; then
  echo 'ERROR: unapproved live run was accepted' >&2; exit 1
fi
grep -q explicit_request_budget_required "$tmp/gate.log"
[[ ! -e "$tmp/forbidden-live" ]]
echo 'PASS unapproved live run blocked before evidence creation'

run_java --simulate "$tmp/simulated"
node audit.mjs "$tmp/simulated" --simulated
printf '\nOffline verification only. No model requests were sent.\n'
