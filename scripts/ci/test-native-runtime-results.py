#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("runtime_results", Path(__file__).with_name("native-runtime-results.py"))
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


class RuntimeEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "results.jsonl"
        for case in sorted(results.REQUIRED):
            results.record(self.path, "true", case, 'http=200 envelope="code":401\nUnicode: 拒绝')

    def test_quotes_newlines_and_unicode_round_trip(self):
        lines = self.path.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 6)
        self.assertEqual(json.loads(lines[0])["detail"], 'http=200 envelope="code":401\nUnicode: 拒绝')
        self.assertEqual(results.validate(self.path, 6, 0, 0), (6, 0, 0))

    def test_not_run_is_explicit_and_keeps_its_reason(self):
        results.record(self.path, "null", "N4", "requires a parser")
        self.assertEqual(results.validate(self.path, 6, 0, 1), (6, 0, 1))

    def test_corrupt_json_and_false_summary_are_rejected(self):
        with self.assertRaises(ValueError):
            results.validate(self.path, 7, 0, 0)
        with self.path.open("a", encoding="utf-8") as output:
            output.write('{"case":"bad","pass":true,"detail":""code":401"}\n')
        with self.assertRaises(ValueError):
            results.validate(self.path, 7, 0, 0)

    def test_duplicate_cases_cannot_inflate_the_pass_count(self):
        results.record(self.path, "true", sorted(results.REQUIRED)[0], "duplicate")
        with self.assertRaises(ValueError):
            results.validate(self.path, 7, 0, 0)

    def test_mandatory_cases_cannot_be_replaced_with_skips(self):
        rows = [json.loads(line) for line in self.path.read_text(encoding="utf-8").splitlines()]
        rows[0] = {"case": rows[0]["case"], "pass": None, "reason": "skipped"}
        self.path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
        with self.assertRaises(ValueError):
            results.validate(self.path, 5, 0, 1)


if __name__ == "__main__":
    unittest.main()
