#!/usr/bin/env python3
"""Validate migration coverage against a reviewed, repository-owned contract."""
import argparse
import json
from pathlib import Path
import re
import shlex


def check_versions(directory, expected):
    if not expected or expected != sorted(set(expected)) or any(type(v) is not int or v < 1 for v in expected):
        raise ValueError(f"invalid reviewed versions for {directory}")
    actual = []
    for path in directory.glob("V*.sql"):
        match = re.fullmatch(r"V([1-9][0-9]*)__.+\.sql", path.name)
        if not match:
            raise ValueError(f"invalid migration filename: {path.name}")
        actual.append(int(match.group(1)))
    if sorted(actual) != expected:
        raise ValueError(f"migration coverage differs for {directory.name}: expected {expected}, actual {sorted(actual)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--shell", action="store_true")
    args = parser.parse_args()
    contract = json.loads((args.repo / "scripts/ci/unified-schema-contract.json").read_text(encoding="utf-8"))
    platform = contract["platformVersions"]
    ai = contract["aiVersions"]
    check_versions(args.repo / "services/platform/docs/script/sql/postgres", platform)
    check_versions(args.repo / "services/ai/resources/database/postgres/migrations", ai)
    tables = contract["expectedAiTables"]
    if not tables or tables != sorted(set(tables)) or any(not re.fullmatch(r"(?:ai_[a-z0-9_]+|outbox_event)", t) for t in tables):
        raise ValueError("invalid or empty reviewed table coverage")
    if contract["legacyTarget"] != 6:
        raise ValueError("legacy entry point must remain V6")
    if args.shell:
        values = {
            "PLATFORM_VERSIONS": ",".join(map(str, platform)),
            "AI_VERSIONS": ",".join(map(str, ai)),
            "PLATFORM_TOP": str(platform[-1]),
            "AI_TOP": str(ai[-1]),
            "LEGACY_TARGET": "6",
            "EXPECTED": " ".join(tables),
        }
        for key, value in values.items():
            print(f"{key}={shlex.quote(value)}")
    else:
        print(f"Unified contract verified: {len(platform)} platform migrations, {len(ai)} AI migrations, {len(tables)} AI tables")


if __name__ == "__main__":
    main()
