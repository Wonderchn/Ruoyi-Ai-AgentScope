#!/usr/bin/env python3
"""Write and verify machine-readable native runtime verdicts."""
import argparse
import json
from pathlib import Path

REQUIRED = {
    "N1-same-key-same-body-replay", "N1-db-single-acceptance",
    "N1-same-key-different-body-409", "N1-unauthenticated-401",
    "N2-worker-takeover", "N3-sse-cross-instance-replay",
}


def record(path, status, case, message):
    verdict = {"true": True, "false": False, "null": None}[status]
    row = {"case": case, "pass": verdict, "reason" if verdict is None else "detail": message}
    with Path(path).open("a", encoding="utf-8") as output:
        output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def validate(path, passed, failed, not_run):
    rows = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines()]
    cases = {}
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("case"), str) or "pass" not in row:
            raise ValueError("malformed runtime verdict")
        if row["pass"] is not None and type(row["pass"]) is not bool:
            raise ValueError("runtime verdict must be boolean or null")
        field = "reason" if row["pass"] is None else "detail"
        if not isinstance(row.get(field), str) or not row[field]:
            raise ValueError("runtime verdict needs its detail or NOT_RUN reason")
        if row["case"] in cases:
            raise ValueError("duplicate runtime case")
        cases[row["case"]] = row["pass"]
    observed = (sum(value is True for value in cases.values()),
                sum(value is False for value in cases.values()),
                sum(value is None for value in cases.values()))
    if observed != (passed, failed, not_run):
        raise ValueError("runtime summary does not match individual verdicts")
    if any(cases.get(case) is not True for case in REQUIRED):
        raise ValueError("a mandatory N1/N2/N3 case is absent or did not pass")
    return observed


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    append = commands.add_parser("record")
    append.add_argument("path", type=Path)
    append.add_argument("status", choices=("true", "false", "null"))
    append.add_argument("case")
    append.add_argument("message")
    verify = commands.add_parser("verify")
    verify.add_argument("path", type=Path)
    verify.add_argument("passed", type=int)
    verify.add_argument("failed", type=int)
    verify.add_argument("not_run", type=int)
    args = parser.parse_args()
    if args.command == "record":
        record(args.path, args.status, args.case, args.message)
    else:
        validate(args.path, args.passed, args.failed, args.not_run)
        print(f"Runtime evidence valid: PASS={args.passed} FAIL={args.failed} NOT_RUN={args.not_run}")
