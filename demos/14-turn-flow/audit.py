"""Independently validate recorded HTTP payloads, complete journal frames and hashes."""
import argparse
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def check(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def audit(output, verify_sources=False):
    manifest = read_json(output / "manifest.json")
    for name, expected in manifest["files"].items():
        check(Path(name).name == name, "non-local artifact path")
        check(hashlib.sha256((output / name).read_bytes()).hexdigest() == expected, "artifact hash mismatch: " + name)
    if verify_sources:
        for name, expected in manifest["sources"].items():
            source = (ROOT / name).resolve()
            check(ROOT.resolve() in source.parents, "non-local source path")
            check(hashlib.sha256(source.read_bytes()).hexdigest() == expected, "source hash mismatch: " + name)
    protocol = read_json(output / "protocol.json")
    results = read_json(output / "results.json")
    requests = read_json(output / "requests.json")
    expected = {row["name"]: row for row in protocol["cases"]}
    observed = {row["name"]: row for row in results}
    check(len(observed) == len(results) and set(expected) == set(observed), "missing or duplicate case")
    request_counts = Counter(request["case"] for request in requests)
    check(set(request_counts) <= set(expected), "unclassified HTTP request")
    records = {}
    for name, spec in expected.items():
        result = observed[name]
        check(result["httpRequests"] == request_counts[name] == spec["httpRequests"], "request count: " + name)
        check(result["journal"] == name + ".log", "unexpected journal path")
        raw = (output / result["journal"]).read_bytes()
        check(not raw or raw.endswith(b"\n"), "incomplete journal tail: " + name)
        turns = []
        for line in raw.splitlines():
            fields = line.split(b" ")
            check(len(fields) == 2, "journal framing")
            payload = base64.b64decode(fields[0], validate=True)
            check(hashlib.sha256(payload).hexdigest().encode("ascii") == fields[1], "journal checksum")
            turn = json.loads(payload.decode("utf-8"))
            check(set(turn) == {"id", "question", "answer"}, "journal schema")
            check(all(isinstance(v, str) and v.strip() for v in turn.values()), "incomplete turn")
            turns.append(turn)
        check(len({t["id"] for t in turns}) == len(turns), "duplicate committed ID")
        check(result["committedTurns"] == len(turns) == spec["committedTurns"], "journal count: " + name)
        if spec.get("seededTurns") == 1:
            check(turns[0] == {"id": "seed", "question": "seed question", "answer": "seed answer"},
                  "pre-existing turn changed: " + name)
        records[name] = turns
    for request in requests:
        messages = request["payload"]["messages"]
        roles = [message["role"] for message in messages]
        check(roles[0] == "system" and roles[-1] == "user", "request boundaries")
        check(roles[1:-1] == ["user", "assistant"] * ((len(roles) - 2) // 2), "partial historical turn")
        check(request["bytes"] <= 2048, "request exceeds integration limit")
    second = [r for r in requests if r["case"] == "two-turns"][1]["payload"]["messages"]
    check(second[1]["content"] == "alpha" and second[2]["content"] == "fixture:alpha", "history missing on second request")
    projected = [r for r in requests if r["case"] == "project-not-delete"][0]["payload"]["messages"]
    check([m["content"] for m in projected[1:]] == ["recent fact", "recent answer", "new question"], "unexpected projection")
    check(records["project-not-delete"][0]["id"] == "old", "projection deleted persistent history")
    check(observed["reopen-replay-conflict"]["outcome"] == "replayed-and-conflict-rejected", "reopen replay not checked")
    return {
        "scope": "Local HTTP fixtures and complete journal records; not a model benchmark or crash/power-loss proof.",
        "cases": len(results),
        "acceptedHttpRequests": len(requests),
        "storedRecordsIncludingSeeds": sum(map(len, records.values())),
        "seededRecords": sum(row["seededTurns"] for row in expected.values()) if all("seededTurns" in row for row in expected.values()) else None,
        "sourceHashesChecked": verify_sources,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--verify-sources", action="store_true")
    args = parser.parse_args()
    print(json.dumps(audit(args.output, args.verify_sources), indent=2))
