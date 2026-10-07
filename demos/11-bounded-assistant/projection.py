"""Run the storage/context integration scenario, or audit its persisted log."""
import argparse, base64, hashlib, json, os, subprocess
from pathlib import Path
H = Path(__file__).resolve().parent
R = H.parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--java-home', type=Path)
parser.add_argument('--gson', type=Path)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--audit', action='store_true')
args = parser.parse_args()
out = args.out.resolve()
sha = lambda p: hashlib.sha256(p.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
if not args.audit:
    if not args.java_home or not args.gson:
        parser.error('--java-home and --gson are required for execution')
    sources = [H / 'src/main/java/com/hohoo/ailab/bounded' / (n + '.java')
               for n in ['ContextBudget', 'TurnJournal', 'ProjectionScenario']]
    classes = H / 'target/projection-classes'
    classes.mkdir(parents=True, exist_ok=True)
    out.parent.mkdir(parents=True, exist_ok=True)
    suffix = '.exe' if os.name == 'nt' else ''
    subprocess.run([str(args.java_home / ('bin/javac' + suffix)), '-encoding', 'UTF-8',
                    '-cp', str(args.gson), '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([str(args.java_home / ('bin/java' + suffix)), '-cp',
                    str(classes) + os.pathsep + str(args.gson),
                    'com.hohoo.ailab.bounded.ProjectionScenario', str(out)], check=True)
    manifest = dict(commit=subprocess.check_output(['git','rev-parse','HEAD'], cwd=R, text=True).strip(),
        sources={p.relative_to(R).as_posix(): sha(p) for p in sources + [Path(__file__)]},
        files={p.name: sha(p) for p in out.iterdir() if p.is_file()})
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf8')
manifest = json.loads((out / 'manifest.json').read_text())
for name, digest in manifest['sources'].items():
    assert sha(R / name) == digest, name
for name, digest in manifest['files'].items():
    assert sha(out / name) == digest, name
report = json.loads((out / 'projection.json').read_text())
assert report['narrow_kept_turns'] == 1 and report['reopened_turns'] == 3
assert not report['old_fact_in_narrow_request'] and report['old_fact_in_wide_request']
assert report['projection_preserved_file_bytes'] and report['rejected_request_preserved_file_bytes']
turns = []
for line in (out / 'conversation.log').read_text().splitlines():
    payload, digest = line.split(' ')
    raw = base64.b64decode(payload)
    assert hashlib.sha256(raw).hexdigest() == digest
    turns.append(json.loads(raw))
assert [x['id'] for x in turns] == ['one', 'two', 'three']
assert 'ORCHID-17' in turns[0]['question']
print('Projection audit passed: full history survives narrowing, commit, reopen and rejection.')
