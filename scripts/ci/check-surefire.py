#!/usr/bin/env python3
"""Fail a Maven job that accidentally selected no tests."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-surefire.py SERVICE_DIR", file=sys.stderr)
        return 2
    service = Path(sys.argv[1])
    reports = list(service.glob("**/target/surefire-reports/TEST-*.xml"))
    tests = failures = errors = 0
    for path in reports:
        suite = ET.parse(path).getroot()
        tests += int(suite.get("tests", "0"))
        failures += int(suite.get("failures", "0"))
        errors += int(suite.get("errors", "0"))
    print(f"surefire reports={len(reports)} tests={tests} failures={failures} errors={errors}")
    return 0 if reports and tests > 0 and failures == 0 and errors == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
