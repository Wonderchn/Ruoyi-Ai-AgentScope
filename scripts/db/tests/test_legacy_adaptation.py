"""Merged-table adaptation: derived columns, load order and the landing-zone contract.

The V11 driver must stay table-agnostic, so every table-specific fact lives in the generated
registry. These tests pin the three facts that unblock chat_session/chat_message:

  * the required unified columns come from an explicit source (title <- session_title,
    member_id <- the landing zone's canonical platform:<tenantId>:<userId>),
  * the message's public conversation_id is an association to the legacy session, not an
    assumption that session_id, the session key and conversation_id are equal,
  * parents are copied before children, and the landing zone refuses missing, broken,
    ambiguous or cross-tenant sources instead of inventing a value.
"""
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

SESSION_UNIFIED = {
    "id": "varchar(20) NOT NULL",
    "conversation_id": "varchar(20) NOT NULL",
    "user_id": "varchar(20) NOT NULL",
    "title": "varchar(128) NOT NULL",
    "member_id": "varchar(160) NOT NULL",
    "session_title": "varchar(255)",
    "tenant_id": "varchar(64) DEFAULT '0'",
    "create_time": "timestamp",
    "deleted": "smallint DEFAULT 0",
}
SESSION_LEGACY = [("id", "bigint NOT NULL"), ("user_id", "bigint NULL DEFAULT NULL"),
                  ("session_title", "varchar(255) NULL DEFAULT NULL"),
                  ("conversation_id", "varchar(32) NULL DEFAULT NULL"),
                  ("tenant_id", "bigint NOT NULL DEFAULT 0")]

MESSAGE_UNIFIED = {
    "id": "varchar(20) NOT NULL",
    "conversation_id": "varchar(20) NOT NULL",
    "user_id": "varchar(20) NOT NULL",
    "member_id": "varchar(160) NOT NULL",
    "role": "varchar(16) NOT NULL",
    "content": "text NOT NULL",
    "session_id": "bigint",
    "model_name": "varchar(255)",
    "total_tokens": "integer",
    "tenant_id": "varchar(64) DEFAULT '0'",
    "create_time": "timestamp",
}
MESSAGE_LEGACY = [("id", "bigint NOT NULL"), ("session_id", "bigint NULL DEFAULT NULL"),
                  ("user_id", "bigint NOT NULL"), ("content", "longtext NULL"),
                  ("role", "varchar(255) NULL DEFAULT NULL"),
                  ("total_tokens", "int NULL DEFAULT 0"),
                  ("model_name", "varchar(255) NULL DEFAULT NULL"),
                  ("tenant_id", "bigint NOT NULL DEFAULT 0")]


class AdaptationPlanTest(unittest.TestCase):
    def plan(self, pairs, legacy_columns, shapes, fk_parents=None):
        with patch.object(generator, "parse_mysql_table",
                          side_effect=lambda raw, table: (legacy_columns[table], None)), \
                patch.object(generator, "unified_keys", return_value=(["id"], [])):
            return {p["legacy"]: p for p in
                    generator.build_plan(pairs, "", shapes, "", fk_parents)}

    def chat_plan(self, fk_parents=None):
        return self.plan(
            {"chat_session": {"unified": "ai_conversation", "entity_only": False},
             "chat_message": {"unified": "ai_message", "entity_only": False}},
            {"chat_session": SESSION_LEGACY, "chat_message": MESSAGE_LEGACY},
            {"ai_conversation": SESSION_UNIFIED, "ai_message": MESSAGE_UNIFIED},
            fk_parents)

    def test_required_columns_come_from_named_sources(self):
        plan = self.chat_plan()
        session, message = plan["chat_session"], plan["chat_message"]
        self.assertEqual("copy", session["mode"])
        self.assertEqual("copy", message["mode"])
        by_column = {c["column"]: c for c in session["columns"]}
        # the title alias and the preserved original are two explicit mappings
        self.assertEqual("session_title", by_column["title"]["legacy_column"])
        self.assertEqual("session_title", by_column["session_title"]["legacy_column"])
        self.assertIn("ChatSession.java", by_column["title"]["note"])
        self.assertTrue(by_column["title"]["narrowing"])
        self.assertEqual(128, by_column["title"]["unified_length"])
        # member identity is adapted, and the derivation names both sources
        self.assertTrue(by_column["member_id"]["adapted"])
        self.assertEqual("member_id", by_column["member_id"]["legacy_column"])
        self.assertEqual(["tenant_id", "user_id"],
                         session["adaptation"][0]["sources"])

    def test_message_conversation_is_an_association_not_an_assumption(self):
        message = self.chat_plan()["chat_message"]
        by_column = {c["column"]: c for c in message["columns"]}
        conversation = by_column["conversation_id"]
        self.assertTrue(conversation["adapted"])
        self.assertTrue(conversation["narrowing"])
        self.assertEqual(20, conversation["unified_length"])
        adaptation = next(e for e in message["adaptation"]
                          if e["unified_column"] == "conversation_id")
        self.assertEqual("session_conversation", adaptation["rule"])
        self.assertEqual("chat_session", adaptation["parent_table"])
        self.assertEqual("session_id", adaptation["key_column"])
        self.assertEqual(["tenant_id", "user_id"], adaptation["match_columns"])
        self.assertEqual("conversation_id", adaptation["parent_value"])
        self.assertIn("不假设 session_id 与会话主键或 conversation_id 相等", conversation["note"])
        # the raw legacy session_id is preserved in its own appended column
        self.assertEqual("session_id", by_column["session_id"]["legacy_column"])
        self.assertFalse(by_column["session_id"]["adapted"])

    def test_parents_are_copied_before_children(self):
        plan = self.chat_plan({"ai_message": {"ai_conversation"}})
        self.assertLess(plan["chat_session"]["copy_order"],
                        plan["chat_message"]["copy_order"])

    def test_a_blocked_parent_blocks_its_child(self):
        shapes = {"ai_conversation": SESSION_UNIFIED, "ai_message": MESSAGE_UNIFIED}
        # the session cannot be copied (its required conversation_id has no legacy source), so
        # the message must not be copied either: the unified FK is immediate
        session_legacy = [c for c in SESSION_LEGACY if c[0] != "conversation_id"]
        plan = self.plan(
            {"chat_session": {"unified": "ai_conversation", "entity_only": False},
             "chat_message": {"unified": "ai_message", "entity_only": False}},
            {"chat_session": session_legacy, "chat_message": MESSAGE_LEGACY},
            shapes, {"ai_message": {"ai_conversation"}})
        self.assertEqual("blocked", plan["chat_session"]["mode"])
        self.assertEqual("blocked", plan["chat_message"]["mode"])
        self.assertIn("父表 platform.ai_conversation", plan["chat_message"]["blocked_reason"])

    def test_missing_adaptation_source_is_refused(self):
        legacy = [c for c in SESSION_LEGACY if c[0] != "tenant_id"]
        with self.assertRaises(SystemExit) as caught:
            self.plan({"chat_session": {"unified": "ai_conversation", "entity_only": False}},
                      {"chat_session": legacy},
                      {"ai_conversation": SESSION_UNIFIED})
        self.assertIn("tenant_id", str(caught.exception))

    def test_the_driver_stays_table_agnostic(self):
        plan = self.chat_plan({"ai_message": {"ai_conversation"}})
        for entry in plan.values():
            self.assertNotIn(entry["legacy"], generator.DRIVER_SQL)
            self.assertNotIn(entry["unified"], generator.DRIVER_SQL)


class AdaptationArtifactTest(unittest.TestCase):
    def adaptation_sql(self):
        with patch.object(generator, "parse_mysql_table",
                          side_effect=lambda raw, table: (
                              {"chat_session": SESSION_LEGACY,
                               "chat_message": MESSAGE_LEGACY}[table], None)), \
                patch.object(generator, "unified_keys", return_value=(["id"], [])):
            plan = generator.build_plan(
                {"chat_session": {"unified": "ai_conversation", "entity_only": False},
                 "chat_message": {"unified": "ai_message", "entity_only": False}},
                "", {"ai_conversation": SESSION_UNIFIED, "ai_message": MESSAGE_UNIFIED}, "",
                {"ai_message": {"ai_conversation"}})
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "adaptation.sql"
            generator.emit_adaptation(plan, str(output))
            return output.read_text(encoding="utf-8")

    def test_landing_zone_contract_refuses_every_unproven_source(self):
        sql = self.adaptation_sql()
        self.assertIn("BEGIN;", sql)
        self.assertIn("COMMIT;", sql)
        # identity derivation and its refusal
        self.assertIn("'platform:' || q.tenant_id::text || ':' || q.user_id::text", sql)
        self.assertIn("缺少成员身份来源（tenant_id/user_id）", sql)
        # association: key, ambiguity, missing parent, cross-tenant/member, blank parent value
        self.assertIn("q.session_id IS NULL", sql)
        self.assertIn("会话关联歧义", sql)
        self.assertIn("p.id = q.session_id", sql)
        self.assertIn("IS DISTINCT FROM q.tenant_id::text", sql)
        self.assertIn("IS DISTINCT FROM q.user_id::text", sql)
        self.assertIn("会话的 conversation_id 缺失或为空", sql)
        # the fill is a same-tenant, same-member join, not a bare id lookup
        self.assertIn("WHERE p.id = q.session_id AND p.tenant_id::text = q.tenant_id::text"
                      " AND p.user_id::text = q.user_id::text", sql)
        # required legacy sources are validated, and the derived columns are tightened
        self.assertIn("统一列 title 的必填来源 session_title 缺失（NULL 或空串）", sql)
        self.assertIn("统一列 role 的必填来源 role 缺失（NULL 或空串）", sql)
        self.assertIn("统一列 content 的必填来源 content 缺失（NULL）", sql)
        self.assertIn('ALTER TABLE legacy_mysql.chat_session ALTER COLUMN "member_id" SET NOT NULL', sql)
        self.assertIn('ALTER TABLE legacy_mysql.chat_message ALTER COLUMN "conversation_id" SET NOT NULL', sql)
        # a numeric source is only checked for NULL, never for a blank string
        self.assertNotIn("btrim(q.user_id::text)", sql)
        # no invented value anywhere: no COALESCE/default substitution for the derived columns
        self.assertNotIn("COALESCE", sql)


if __name__ == "__main__":
    unittest.main()
