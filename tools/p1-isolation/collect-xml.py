"""Archive fresh Surefire XML and count unique fully qualified test cases.

No stale report is inferred to pass. Duplicate cases with conflicting outcomes fail.
"""
import argparse
import datetime as dt
import hashlib
import json
import re
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--repo", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--since", help="ISO UTC timestamp for this verified build")
parser.add_argument("--side", choices=["ai", "platform"])
args = parser.parse_args()
repo = args.repo.resolve()
out = args.output.resolve()
if out == repo or repo in out.parents:
    raise SystemExit("Evidence must be outside the checkout")
out.mkdir(parents=True, exist_ok=True)
since = dt.datetime.fromisoformat(args.since.replace("Z", "+00:00")).timestamp() if args.since else 0
roots = [repo / "services" / args.side] if args.side else [repo / "services" / s for s in ("ai", "platform")]
cases, reports, stale, duplicates = {}, [], [], []
for root in roots:
    for path in sorted(root.rglob("TEST-*.xml")):
        if path.parent.name != "surefire-reports" or path.parent.parent.name != "target":
            continue
        relative = path.relative_to(repo).as_posix()
        if path.stat().st_mtime < since:
            stale.append(relative)
            continue
        module = path.parent.parent.parent.relative_to(repo).as_posix()
        tree = ET.parse(path).getroot()
        target = out / "xml" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        reports.append({"path": relative, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                        "mtimeUtc": dt.datetime.fromtimestamp(path.stat().st_mtime, dt.timezone.utc).isoformat()})
        for case in tree.iter("testcase"):
            class_name = case.attrib.get("classname", "")
            if not re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", class_name):
                class_name = tree.attrib["name"]
            key = (class_name, case.attrib["name"])
            nodes = [(tag, case.find(tag)) for tag in ("failure", "error", "skipped")]
            outcome = next((tag for tag, node in nodes if node is not None), "passed")
            detail = next((node.attrib.get("message", "") or (node.text or "") for tag, node in nodes if node is not None), "")
            row = {"class": key[0], "name": key[1], "module": module, "outcome": outcome, "detail": detail}
            if key in cases:
                if cases[key]["outcome"] != outcome:
                    raise SystemExit(f"Conflicting duplicate test outcome: {key}")
                duplicates.append({"class": key[0], "name": key[1], "report": relative})
            else:
                cases[key] = row

def count(rows):
    rows = list(rows)
    return {"tests": len(rows), **{tag: sum(r["outcome"] == tag for r in rows) for tag in ("passed", "failure", "error", "skipped")}}

result = {"since": args.since, "side": args.side, "summary": count(cases.values()),
          "p1": count(r for r in cases.values() if r["class"].split(".")[-1].startswith("P1")),
          "modules": {m: count(r for r in cases.values() if r["module"] == m) for m in sorted({r["module"] for r in cases.values()})},
          "skipped": [r for r in cases.values() if r["outcome"] == "skipped"],
          "reports": reports, "excludedStale": stale, "duplicates": duplicates}
(out / "test-summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
(out / "skipped.md").write_text("\n".join(f'- `{r["class"]}#{r["name"]}`: {r["detail"] or "XML contains no reason"}' for r in result["skipped"]), encoding="utf-8")
print(json.dumps({"summary": result["summary"], "p1": result["p1"], "xml": len(reports), "excludedStale": len(stale)}, ensure_ascii=False))
if not reports or result["summary"]["failure"] or result["summary"]["error"]:
    raise SystemExit(1)
