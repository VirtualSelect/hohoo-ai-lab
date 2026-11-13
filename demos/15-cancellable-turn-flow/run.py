"""Compile the existing journal/coordinator/SSE reader and run owned cancellation fixtures."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

HOME = Path(__file__).resolve().parent
ROOT = HOME.parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--gson", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    output = args.out.resolve()
    if output.exists():
        parser.error("output exists; choose a new directory")
    suffix = ".exe" if os.name == "nt" else ""
    java = args.java_home / ("bin/java" + suffix)
    javac = args.java_home / ("bin/javac" + suffix)
    for dependency in (java, javac, args.gson):
        if not dependency.is_file():
            parser.error("missing local dependency: " + str(dependency))
    original = ROOT / "demos/11-bounded-assistant/src/main/java/com/hohoo/ailab/bounded"
    stream = ROOT / "demos/10-transport-cancellation/src"
    sources = [original / "ContextBudget.java", original / "TurnJournal.java",
               ROOT / "demos/14-turn-flow/TurnFlow.java",
               stream / "main/java/com/hohoo/ailab/stream/StreamReader.java",
               stream / "test/java/com/hohoo/ailab/stream/JsonLexicalRegression.java"]
    sources += sorted(HOME.glob("*.java"))
    tracked = sources + [HOME / "run.py", HOME / "audit.py", HOME / "protocol.json"]
    frozen = {p.relative_to(ROOT).as_posix(): sha(p) for p in tracked}
    protocol = (HOME / "protocol.json").read_bytes()
    build = HOME / "target/classes"
    build.mkdir(parents=True, exist_ok=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(javac), "-encoding", "UTF-8", "-cp", str(args.gson), "-d", str(build)]
                   + [str(p) for p in sources], check=True)
    command = [str(java), "-cp", str(build) + os.pathsep + str(args.gson)]
    lexical = subprocess.check_output(command + ["com.hohoo.ailab.stream.JsonLexicalRegression"], text=True)
    subprocess.run(command + ["com.hohoo.ailab.bounded.CancellationChecks", str(output)], check=True)
    if frozen != {p.relative_to(ROOT).as_posix(): sha(p) for p in tracked}:
        raise RuntimeError("source changed during execution")
    (output / "protocol.json").write_bytes(protocol)
    (output / "lexical-regression.txt").write_text(lexical, encoding="utf-8")
    manifest = {
        "sourceState": "uncommitted local extension, not a published fixed revision",
        "sourceBaseCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "completedAt": datetime.now(timezone.utc).isoformat(),
        "java": subprocess.check_output([str(java), "-version"], stderr=subprocess.STDOUT, text=True).strip(),
        "platform": platform.platform(), "gsonSha256": sha(args.gson), "sources": frozen,
        "files": {p.name: sha(p) for p in sorted(output.iterdir()) if p.is_file()},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    subprocess.run([sys.executable, str(HOME / "audit.py"), str(output), "--verify-sources"], check=True)


if __name__ == "__main__":
    main()
