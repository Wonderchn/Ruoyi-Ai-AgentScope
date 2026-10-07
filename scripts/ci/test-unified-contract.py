#!/usr/bin/env python3
"""Negative controls for the independent migration-version oracle."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("contract", Path(__file__).with_name("check-unified-contract.py"))
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


class MigrationCoverageTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.sql = Path(self.work.name)
        (self.sql / "V1__baseline.sql").write_text("-- baseline\n", encoding="utf-8")
        (self.sql / "V2__upgrade.sql").write_text("-- upgrade\n", encoding="utf-8")

    def test_reviewed_chain_passes(self):
        contract.check_versions(self.sql, [1, 2])

    def test_removed_migration_is_not_removed_from_expectations(self):
        (self.sql / "V2__upgrade.sql").unlink()
        with self.assertRaisesRegex(ValueError, "migration coverage differs"):
            contract.check_versions(self.sql, [1, 2])

    def test_extra_migration_requires_review(self):
        (self.sql / "V3__unreviewed.sql").write_text("-- new\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "migration coverage differs"):
            contract.check_versions(self.sql, [1, 2])

    def test_duplicate_version_cannot_hide_in_a_set(self):
        (self.sql / "V2__duplicate.sql").write_text("-- duplicate\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "migration coverage differs"):
            contract.check_versions(self.sql, [1, 2])

    def test_empty_expectations_cannot_pass(self):
        with self.assertRaisesRegex(ValueError, "invalid reviewed versions"):
            contract.check_versions(self.sql, [])


if __name__ == "__main__":
    unittest.main()
