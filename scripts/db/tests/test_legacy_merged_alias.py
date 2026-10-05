"""Merged-column aliases must remain explicit and refuse unsafe data conversions."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

DB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DB))
spec = importlib.util.spec_from_file_location("legacy_copy", DB / "generate-legacy-ai-copy.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class MergedAliasTest(unittest.TestCase):
    def test_width_parser_reads_parameters_before_constraints(self):
        for definition, width in (("varchar(64) NOT NULL", 64), ("CHAR(20) DEFAULT 'x'", 20),
                                  (" character varying (160) NOT NULL", 160), ("text", None),
                                  ("bigint NOT NULL", None)):
            with self.subTest(definition=definition):
                self.assertEqual(width, generator.char_width(definition))

    def plan(self, columns, shapes=None):
        shapes = shapes or {"id": "varchar(20) NOT NULL", "name": "varchar(64) NOT NULL",
                            "agent_name": "varchar(200)"}
        with patch.object(generator, "parse_mysql_table", return_value=(columns, [])), \
                patch.object(generator, "unified_keys", return_value=(["id"], [])):
            return generator.build_plan({"agent_info": {"unified": "ai_agent_profile", "entity_only": False}},
                                        "", {"ai_agent_profile": shapes}, "")[0]

    def test_name_alias_and_preserved_source_are_distinct_explicit_mappings(self):
        plan = self.plan([("id", "bigint NOT NULL"), ("agent_name", "varchar(200) NOT NULL")])
        self.assertEqual("copy", plan["mode"])
        name = next(c for c in plan["columns"] if c["column"] == "name")
        self.assertEqual("agent_name", name["legacy_column"])
        self.assertTrue(name["narrowing"])
        self.assertEqual(64, name["unified_length"])
        self.assertIn("Agent.java", name["note"])
        self.assertEqual(2, sum(c["legacy_column"] == "agent_name" for c in plan["columns"]))
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "V11.sql"
            with patch.object(generator, "ENUM_MAPS", []):
                generator.emit_v11([plan], str(output))
            sql = output.read_text(encoding="utf-8")
        self.assertIn("'agent_name', 'name', 'identity'", sql)
        self.assertIn("'agent_name', 'agent_name', 'identity'", sql)

    def test_missing_alias_source_stays_blocked(self):
        plan = self.plan([("id", "bigint NOT NULL")])
        self.assertEqual("blocked", plan["mode"])
        self.assertIn("name", plan["unmapped_required"])

    def test_other_required_fields_are_not_invented_by_the_alias(self):
        plan = self.plan([("id", "bigint NOT NULL"), ("agent_name", "varchar(200) NOT NULL")],
                         {"id": "varchar(20) NOT NULL", "name": "varchar(64) NOT NULL", "owner_member_id": "varchar(160) NOT NULL"})
        self.assertEqual("blocked", plan["mode"])
        self.assertIn("owner_member_id", plan["unmapped_required"])


if __name__ == "__main__":
    unittest.main()
