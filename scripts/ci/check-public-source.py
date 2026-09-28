#!/usr/bin/env python3
"""Check the initial public-source import for accidental local credentials."""

import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
TOKEN = re.compile(
    rb"(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|"
    rb"github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|"
    rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)"
)
CONFIG_KEY = re.compile(
    r"(?i)^\s*(?:-\s+)?(?:[A-Za-z0-9_.-]*?(?:password|passwd|secret|api[-_]?key|access[-_]?key))\s*[:=]\s*(.+)$"
)


def tracked_files() -> list[Path]:
    raw = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT)
    return [ROOT / name.decode("utf-8") for name in raw.split(b"\0") if name]


def main() -> int:
    lock = json.loads((ROOT / "docs/upstreams.lock.json").read_text(encoding="utf-8"))
    if len(lock.get("sources", [])) != 2:
        print("Expected two pinned upstream sources", file=sys.stderr)
        return 1
    bad: list[str] = []
    for path in tracked_files():
        if not path.is_file():
            continue
        rel = path.relative_to(ROOT).as_posix()
        if rel.endswith("/.env") or rel.endswith((".pem", ".p12", ".pfx", ".key")):
            bad.append(rel + ": forbidden file type")
            continue
        if path.stat().st_size > 2_000_000:
            continue
        data = path.read_bytes()
        if TOKEN.search(data):
            bad.append(rel + ": credential-shaped literal")
        if rel.startswith("services/") and path.suffix in {".yml", ".yaml", ".properties"}:
            for number, line in enumerate(data.decode("utf-8", "replace").splitlines(), 1):
                match = CONFIG_KEY.match(line)
                if not match or line.lstrip().startswith("#"):
                    continue
                value = match.group(1).strip()
                if value and not value.startswith("${") and value.lower() not in {"null", "true", "false"}:
                    bad.append(f"{rel}:{number}: literal credential configuration")
    if bad:
        print("Public-source check failed:\n" + "\n".join(bad), file=sys.stderr)
        return 1
    print("Public-source metadata and credential patterns passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
