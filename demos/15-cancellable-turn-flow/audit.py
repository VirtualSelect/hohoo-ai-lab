"""Reconcile fixture observations against complete journal frames and frozen input expectations."""
import argparse
import base64
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def check(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def audit(output, verify_sources=False):
    manifest = read(output / "manifest.json")
    for name, expected in manifest["files"].items():
        check(Path(name).name == name, "nonlocal artifact path")
        check(hashlib.sha256((output / name).read_bytes()).hexdigest() == expected, "artifact hash: " + name)
    if verify_sources:
        for name, expected in manifest["sources"].items():
            path = (ROOT / name).resolve()
            check(ROOT.resolve() in path.parents, "nonlocal source")
            check(hashlib.sha256(path.read_bytes()).hexdigest() == expected, "source hash: " + name)
    protocol = read(output / "protocol.json")
    rows = read(output / "results.json")
    expected = {case["name"]: case for case in protocol["cases"]}
    actual = {row["name"]: row for row in rows}
    check(len(rows) == len(actual) and set(expected) == set(actual), "missing or duplicate case")
    total_turns = 0
    for name, spec in expected.items():
        row = actual[name]
        for field in ("receivedRequests", "acceptedConnections", "outcome"):
            check(row[field] == spec[field], name + ": " + field)
        check(row["cancelledAtObservation"] == (name not in ("complete", "old-lease")),
              name + ": wrong request cancellation state")
        check(row["receivedRequests"] == len(row["requests"]), name + ": request frames")
        check(row["serverWorkSteps"] == 2 * row["receivedRequests"], name + ": bounded server work")
        check(row["workerTerminated"] and row["serverTerminated"], name + ": leaked thread")
        check(row["socketClosed"] is True if row["socketCreated"] else row["socketClosed"] is None,
              name + ": socket ownership")
        check(row["journal"] == name + ".log", "journal path")
        raw = (output / row["journal"]).read_bytes()
        check(raw.endswith(b"\n"), name + ": incomplete final record")
        turns = []
        for line in raw.splitlines():
            fields = line.split(b" ")
            check(len(fields) == 2, "journal framing")
            payload = base64.b64decode(fields[0], validate=True)
            check(hashlib.sha256(payload).hexdigest().encode("ascii") == fields[1], "journal checksum")
            turns.append(json.loads(payload.decode("utf-8")))
        check(len(turns) == spec["storedTurns"], name + ": committed turn count")
        check(turns[0] == {"id": "seed", "question": "seed question", "answer": "seed answer"}, name + ": old history changed")
        check(len({t["id"] for t in turns}) == len(turns), name + ": duplicate committed ID")
        for turn in turns[1:]:
            check(turn["answer"] == "fixture:" + turn["question"], name + ": partial or mismatched reply")
        for request in row["requests"]:
            roles = [message["role"] for message in request["messages"]]
            check(roles[0] == "system" and roles[-1] == "user", name + ": request boundaries")
            check(roles[1:-1] == ["user", "assistant"] * ((len(roles)-2)//2), name + ": partial history")
        total_turns += len(turns)
    check(actual["flag-only"]["workerExitedAtControlObservation"] is False, "flag-only control lost")
    check(actual["flag-only"]["controlObservationMs"] == 100, "control interval changed")
    for name in ("flag-only", "close-reading"):
        check(actual[name]["workerExitedBeforeServerRelease"] is True, name + ": no independent owner exit")
    check(actual["before-commit"]["completeCandidate"] is True, "no complete candidate before cancellation")
    check(actual["old-lease"]["newLeaseUnaffected"] is True, "old lease affected a new request")
    check(actual["before-start"]["socketCreated"] is False, "pre-cancel created a resource")
    for name in ("before-register", "after-register"):
        check(actual[name]["socketCreated"] is True, name + ": registration race was not exercised")
    return {
        "scope": "Controlled loopback handoffs, not a cancellation SLA or model/billing result",
        "cases": len(rows), "receivedRequests": sum(row["receivedRequests"] for row in rows),
        "seededTurns": len(rows), "storedTurns": total_turns,
        "newTurns": total_turns - len(rows), "sourceHashesChecked": verify_sources,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--verify-sources", action="store_true")
    args = parser.parse_args()
    print(json.dumps(audit(args.output, args.verify_sources), indent=2))
