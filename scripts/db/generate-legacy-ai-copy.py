"""Generate the legacy MySQL AI-domain row-data migration (V11) and its staging contract.

Background (E2 spec §5.1b): the platform's AI domain (chat / agent / knowledge / mcp /
aiflow / trace / short-drama) lived in MySQL. V9 authored the PostgreSQL DDL for those
tables and V10 authored the one table that had no DDL anywhere, but **no migration moves
their rows**. V8 only copies the `ai` schema, which is the other deployment.

This generator emits two artifacts from one source of truth:

  1. `V11__legacy_ai_domain_data.sql` -- the Flyway migration. It creates a machine
     readable mapping registry (table map, column map, value/enum map, audit table) and a
     generic driver function, then copies every table the registry can prove. It is a no-op
     on a fresh install (no staging schema) and on a deployment that never ran the MySQL
     platform AI domain.
  2. `legacy-mysql/10-legacy-ai-domain-staging.sql` -- the landing-zone DDL. The operator
     loads the MySQL dump into a `legacy_mysql` schema (pgloader / CSV COPY) whose tables
     keep the *original* MySQL column names and MySQL-ish types, so the copy's casts and
     the enum translation stay explicit instead of being hidden in the loader.

Refusal is the default. A table is only copied when every NOT NULL column of the unified
table that has no default is covered by a same-named legacy column; anything else is
recorded as `blocked` in the audit table with the exact list of columns that need a
platform-side decision. That decision belongs to the platform mapper (E2 spec §5.1c, E5),
not to a migration that would have to invent values.

Usage:
  python generate-legacy-ai-copy.py <tableMapJson> <mysqlSql> <pgMigrationDir> \
      <outV11> <outStaging>
"""
import json
import os
import re
import sys

from mysql_ddl import (WARNINGS, map_type, parse_mysql_table, quote, split_defs,
                       strip_line_comments, warn)
from sql_lint import assert_no_orphan_comment_lines
from table_shape import parse_created_table_definitions

LEGACY_SOURCE = "mysql/ruoyi-ai.sql"
STAGING_SCHEMA = "legacy_mysql"
ENTITY_ONLY_SCHEMA = "java-entity"
# The enum sources of truth are named relative to the repository root (override with
# REPO_ROOT when the generator is run from a copy of the tree).
REPO_ROOT = os.environ.get("REPO_ROOT") or os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

# Evidence-backed merged-column aliases. A source may feed both its preserved legacy
# field and the unified field; the driver already supports explicit source/target names.
# Do not put deployment-dependent collection names, object URLs or member ids here.
COLUMN_ALIASES = {
    ("agent_info", "name"): (
        "agent_name",
        "Agent 名称：ruoyi-chat/domain/entity/agent/Agent.java (@TableName agent_info, agentName); "
        "旧 DDL agent_name → V7 ai_agent_profile.name；原 agent_name 列同时保留"),
    ("chat_session", "title"): (
        "session_title",
        "会话标题：ruoyi-chat/domain/entity/chat/ChatSession.java 的 sessionTitle 与旧 DDL "
        "session_title（COMMENT '会话标题'）描述同一字段；V7 ai_conversation.title 是统一读路径"
        "（TenantConversationReadRepository 读 title）展示的标题。原 session_title 列同时保留"),
}

# Landing-zone adaptation columns (E5). A merged table's unified NOT NULL column that no legacy
# column can fill is NOT invented by the migration: the landing zone materialises it in a
# generated, reviewable step (legacy-mysql/20-legacy-ai-domain-adaptation.sql) and V11 then
# copies it like any other column. Each entry names the legacy columns the value comes from and
# the rule that computes it, so the derivation is auditable instead of hidden in a loader. The
# V11 driver itself stays table-agnostic (all table specifics live in the registry).
ADAPTATION_COLUMNS = [
    {
        "legacy_table": "chat_session",
        "unified_column": "member_id",
        "staging_column": "member_id",
        "staging_type": "varchar(160)",
        "rule": "member_platform",
        "sources": ["tenant_id", "user_id"],
        "note": ("成员身份：canonical platform:<tenantId>:<userId>"
                 "（framework ExecutionPrincipal.membershipId / AiResourceWriteService 的 "
                 "\"platform:\" + tenantId + \":\" 前缀校验）；tenant_id/user_id 任一缺失即拒绝，"
                 "不造默认值；数值租户按文本参与，0 → 'platform:0:<userId>'，不并入 '000000'"),
    },
    {
        "legacy_table": "chat_message",
        "unified_column": "member_id",
        "staging_column": "member_id",
        "staging_type": "varchar(160)",
        "rule": "member_platform",
        "sources": ["tenant_id", "user_id"],
        "note": ("成员身份：canonical platform:<tenantId>:<userId>，与所属会话的 member_id "
                 "必须一致（V7 的 fk_message_conversation 按 tenant_id+conversation_id+member_id "
                 "引用 ai_conversation）；tenant_id/user_id 任一缺失即拒绝"),
    },
    {
        "legacy_table": "chat_message",
        "unified_column": "conversation_id",
        "staging_column": "conversation_id",
        "staging_type": "varchar(32)",
        "rule": "session_conversation",
        "parent_table": "chat_session",
        "parent_key": "id",
        "key_column": "session_id",
        "match_columns": ["tenant_id", "user_id"],
        "parent_value": "conversation_id",
        "sources": ["session_id", "tenant_id", "user_id"],
        "note": ("公开会话标识：按同租户、同成员的 legacy chat_session 行（session_id → id）取 "
                 "conversation_id；不假设 session_id 与会话主键或 conversation_id 相等。"
                 "断关联/跨租户/成员不一致/父值缺失/父键歧义均拒绝，不造默认值"),
    },
]

# Required character columns whose blank value is as broken as NULL: identity/association keys,
# the role discriminator and the display title. `content` is deliberately absent -- an empty
# message body is representable, while an empty identity or association would make the row
# unreachable for the platform's own read paths.
NON_BLANK_REQUIRED = ("conversation_id", "member_id", "user_id", "role", "title")

# Value-level translations. Each entry names the two Java enums that define both sides of
# the mapping; the generator refuses to emit the map if either enum no longer declares the
# codes it translates, so the mapping cannot silently drift away from the code.
ENUM_MAPS = [
    {
        "domain": "knowledge_document_status",
        "legacy_table": "knowledge_attach",
        "legacy_column": "status",
        "unified_column": "status",
        "legacy_enum": ("services/platform/ruoyi-modules/ruoyi-chat/src/main/java/"
                        "org/ruoyi/enums/KnowledgeAttachStatus.java"),
        "unified_enum": ("services/ai/rag/src/main/java/com/nageoffer/ai/ragent/"
                         "knowledge/enums/DocumentStatus.java"),
        "values": [
            ("0", "pending", "KnowledgeAttachStatus.WAITING(0) 待解析 → DocumentStatus.PENDING"),
            ("1", "running", "KnowledgeAttachStatus.PARSING(1) 解析中 → DocumentStatus.RUNNING"),
            ("2", "success", "KnowledgeAttachStatus.COMPLETED(2) 已解析 → DocumentStatus.SUCCESS"),
            ("3", "failed", "KnowledgeAttachStatus.FAILED(3) 解析失败 → DocumentStatus.FAILED"),
        ],
        "legacy_markers": ["WAITING(0", "PARSING(1", "COMPLETED(2", "FAILED(3"],
        "unified_markers": ['PENDING("pending")', 'RUNNING("running")', 'FAILED("failed")',
                            'SUCCESS("success")'],
    },
]

# The hand-written half of V11: the driver. Kept as a literal because Flyway migrations are
# plain SQL (no \i include) and because the function must not change when the registry data
# is regenerated. Everything the driver needs to know about a table comes from the registry
# tables, so unblocking a table in E5 is a data change, not a code change.
DRIVER_SQL = r"""
-- ---------------------------------------------------------------------------
-- Driver. Everything table-specific comes from the registry above, so a table can be
-- unblocked (E5) by adding registry rows instead of editing this migration.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION platform.ai_legacy_domain_expr(
    p_transform text, p_legacy_column text, p_arg text, p_alias text)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $expr$
    SELECT CASE p_transform
        WHEN 'identity'      THEN format('%I.%I', p_alias, p_legacy_column)
        WHEN 'cast_text'     THEN format('%I.%I::text', p_alias, p_legacy_column)
        WHEN 'enum'          THEN format(
            '(SELECT e.target_value FROM platform.ai_legacy_domain_enum_map e'
            ' WHERE e.domain = %L AND e.source_value = %I.%I::text)',
            p_arg, p_alias, p_legacy_column)
        WHEN 'coalesce_text' THEN format('COALESCE(%I.%I::text, %L)', p_alias, p_legacy_column, '')
        WHEN 'constant'      THEN format('%L', p_arg)
        ELSE NULL
    END
$expr$;
COMMENT ON FUNCTION platform.ai_legacy_domain_expr(text, text, text, text) IS
    'E2 旧 MySQL AI 域行迁移：把注册表里的 transform 展开成 SQL 表达式（别名限定）';

CREATE OR REPLACE FUNCTION platform.ai_legacy_domain_audit(
    p_source text, p_legacy text, p_target text, p_mode text,
    p_read bigint, p_inserted bigint, p_skipped bigint, p_defaulted bigint,
    p_before bigint, p_present bigint, p_reason text)
RETURNS void
LANGUAGE sql
AS $audit$
    INSERT INTO platform.ai_legacy_domain_migration_audit
        (legacy_source, legacy_table, unified_table, mode, rows_read, rows_inserted,
         rows_skipped_existing, rows_defaulted, unified_rows_before, unified_rows,
         blocked_reason, recorded_at)
    VALUES (p_source, p_legacy, p_target, p_mode, p_read, p_inserted, p_skipped,
            p_defaulted, p_before, p_present, p_reason, CURRENT_TIMESTAMP)
    ON CONFLICT (legacy_source, legacy_table) DO UPDATE
       SET unified_table = EXCLUDED.unified_table,
           mode = EXCLUDED.mode,
           rows_read = EXCLUDED.rows_read,
           rows_inserted = EXCLUDED.rows_inserted,
           rows_skipped_existing = EXCLUDED.rows_skipped_existing,
           rows_defaulted = EXCLUDED.rows_defaulted,
           unified_rows_before = EXCLUDED.unified_rows_before,
           unified_rows = EXCLUDED.unified_rows,
           blocked_reason = EXCLUDED.blocked_reason,
           recorded_at = CURRENT_TIMESTAMP
$audit$;
COMMENT ON FUNCTION platform.ai_legacy_domain_audit(text, text, text, text, bigint, bigint,
    bigint, bigint, bigint, bigint, text) IS
    'E2 旧 MySQL AI 域行迁移：逐表对账写入（幂等 upsert）';

CREATE OR REPLACE FUNCTION platform.ai_legacy_domain_copy_table(
    p_source text, p_legacy text, p_staging text DEFAULT 'legacy_mysql')
RETURNS text
LANGUAGE plpgsql
AS $copy$
DECLARE
    v_target     text;
    v_mode       text;
    v_reason     text;
    v_keys       text[];
    v_cols       text;
    v_exprs      text;
    v_key_pred   text;
    v_null_pred  text;
    v_read       bigint := 0;
    v_inserted   bigint := 0;
    v_skipped    bigint := 0;
    v_existing   bigint := 0;
    v_defaulted  bigint := 0;
    v_before     bigint := 0;
    v_present    bigint := 0;
    v_missing    text;
    v_unmapped   text;
    v_wide       bigint;
    v_samples    text;
    v_enum       text;
    v_seq        text;
    r            record;
BEGIN
    SELECT m.unified_table, m.mode, m.blocked_reason, m.key_columns
      INTO v_target, v_mode, v_reason, v_keys
      FROM platform.ai_legacy_domain_migration_table_map m
     WHERE m.legacy_source = p_source AND m.legacy_table = p_legacy;
    IF v_target IS NULL THEN
        RAISE EXCEPTION 'no legacy-domain mapping registered for %.%', p_source, p_legacy;
    END IF;

    -- A deployment that never ran the MySQL platform AI domain has no staging schema.
    -- That is the normal fresh-install case: record it and move on.
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables t
                    WHERE t.table_schema = p_staging AND t.table_name = p_legacy) THEN
        PERFORM platform.ai_legacy_domain_audit(p_source, p_legacy, v_target, 'absent',
            0, 0, 0, 0, NULL, NULL,
            format('staging table %s.%s not present', p_staging, p_legacy));
        RETURN 'absent';
    END IF;

    -- Guard 1: every legacy column the registry names must exist in the staging table.
    -- A column-count/name mismatch is refused, never resolved by position.
    SELECT string_agg(m.legacy_column, ', ' ORDER BY m.legacy_column) INTO v_missing
      FROM platform.ai_legacy_domain_migration_map m
     WHERE m.legacy_source = p_source AND m.legacy_table = p_legacy
       AND m.legacy_column IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns c
                        WHERE c.table_schema = p_staging AND c.table_name = p_legacy
                          AND c.column_name = m.legacy_column);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'staging table %.% is missing mapped column(s): % (refusing to guess a mapping)',
            p_staging, p_legacy, v_missing;
    END IF;

    -- Guard 2: every unified column the registry names must exist in the target.
    SELECT string_agg(m.unified_column, ', ' ORDER BY m.unified_column) INTO v_missing
      FROM platform.ai_legacy_domain_migration_map m
     WHERE m.legacy_source = p_source AND m.legacy_table = p_legacy
       AND m.unified_column IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns c
                        WHERE c.table_schema = 'platform' AND c.table_name = v_target
                          AND c.column_name = m.unified_column);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'unified table platform.% is missing mapped column(s): %',
            v_target, v_missing;
    END IF;

    -- Guard 3 (value level): every distinct legacy value of an enum-mapped column must be
    -- in the value map. Runs even when the table is blocked, so the mapping is validated
    -- against the real data now instead of when the copy is unblocked later.
    FOR r IN SELECT m.legacy_column, m.transform_arg
               FROM platform.ai_legacy_domain_migration_map m
              WHERE m.legacy_source = p_source AND m.legacy_table = p_legacy
                AND m.transform = 'enum'
              ORDER BY m.legacy_column
    LOOP
        EXECUTE format(
            'SELECT string_agg(DISTINCT COALESCE(src.%I::text, %L), %L) FROM %I.%I src'
            ' WHERE src.%I IS NULL OR NOT EXISTS (SELECT 1 FROM platform.ai_legacy_domain_enum_map e'
            '  WHERE e.domain = %L AND e.source_value = src.%I::text)',
            r.legacy_column, '<null>', ', ', p_staging, p_legacy, r.legacy_column,
            r.transform_arg, r.legacy_column)
          INTO v_enum;
        IF v_enum IS NOT NULL THEN
            RAISE EXCEPTION 'legacy value(s) [%] in %.%.% have no mapping in enum domain "%"; add them to platform.ai_legacy_domain_enum_map before copying',
                v_enum, p_staging, p_legacy, r.legacy_column, r.transform_arg;
        END IF;
    END LOOP;

    -- Guard 4: a NOT NULL unified column without a default and without a mapped source
    -- cannot be filled. That is a platform-side adaptation (E2 §5.1c / E5), so the table is
    -- recorded as blocked instead of being filled with an invented value.
    SELECT string_agg(c.column_name, ', ' ORDER BY c.column_name) INTO v_unmapped
      FROM information_schema.columns c
     WHERE c.table_schema = 'platform' AND c.table_name = v_target
       AND c.is_nullable = 'NO' AND c.column_default IS NULL
       AND NOT EXISTS (SELECT 1 FROM platform.ai_legacy_domain_migration_map m
                        WHERE m.legacy_source = p_source AND m.legacy_table = p_legacy
                          AND m.unified_column = c.column_name);
    IF v_unmapped IS NOT NULL THEN
        PERFORM platform.ai_legacy_domain_audit(p_source, p_legacy, v_target, 'blocked',
            0, 0, 0, 0, NULL, NULL,
            format('unified NOT NULL column(s) without a mapped legacy source: %s', v_unmapped));
        RETURN 'blocked';
    END IF;

    IF v_mode <> 'copy' THEN
        PERFORM platform.ai_legacy_domain_audit(p_source, p_legacy, v_target, 'blocked',
            0, 0, 0, 0, NULL, NULL, COALESCE(v_reason, 'declared blocked'));
        RETURN 'blocked';
    END IF;

    -- Guard 5: narrowing columns. PostgreSQL would raise "value too long" on the first
    -- offending row; report the count and samples up front so the operator sees the exact
    -- problem instead of a half-applied copy.
    FOR r IN SELECT m.legacy_column, m.unified_column, m.unified_length
               FROM platform.ai_legacy_domain_migration_map m
              WHERE m.legacy_source = p_source AND m.legacy_table = p_legacy
                AND m.narrowing AND m.unified_length IS NOT NULL
              ORDER BY m.unified_column
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I src WHERE src.%I IS NOT NULL AND length(src.%I::text) > %s',
                       p_staging, p_legacy, r.legacy_column, r.legacy_column, r.unified_length)
          INTO v_wide;
        IF v_wide > 0 THEN
            EXECUTE format('SELECT string_agg(DISTINCT left(src.%I::text, 60), %L) FROM %I.%I src WHERE src.%I IS NOT NULL AND length(src.%I::text) > %s',
                           r.legacy_column, ' | ', p_staging, p_legacy, r.legacy_column,
                           r.legacy_column, r.unified_length)
              INTO v_samples;
            RAISE EXCEPTION '% row(s) in %.%.% exceed the unified width % of platform.% (%): %',
                v_wide, p_staging, p_legacy, r.legacy_column, r.unified_length, v_target,
                r.unified_column, v_samples;
        END IF;
    END LOOP;

    SELECT string_agg(format('%I', m.unified_column), ', ' ORDER BY m.unified_column),
           string_agg(platform.ai_legacy_domain_expr(m.transform, m.legacy_column,
                                                     m.transform_arg, 'src'),
                      ', ' ORDER BY m.unified_column)
      INTO v_cols, v_exprs
      FROM platform.ai_legacy_domain_migration_map m
     WHERE m.legacy_source = p_source AND m.legacy_table = p_legacy
       AND m.legacy_column IS NOT NULL;
    IF v_exprs IS NULL OR v_cols IS NULL THEN
        RAISE EXCEPTION 'no column mapping registered for %.%', p_source, p_legacy;
    END IF;

    -- Key predicate: the copy is re-runnable and never overwrites a row that is already
    -- there (the AI-side rows from V8 may legitimately share ids).
    SELECT string_agg(format('t.%I IS NOT DISTINCT FROM %s', m.unified_column,
                             platform.ai_legacy_domain_expr(m.transform, m.legacy_column,
                                                            m.transform_arg, 'src')),
                      ' AND ' ORDER BY m.unified_column)
      INTO v_key_pred
      FROM platform.ai_legacy_domain_migration_map m
     WHERE m.legacy_source = p_source AND m.legacy_table = p_legacy
       AND m.unified_column = ANY (v_keys);
    IF v_key_pred IS NULL THEN
        RAISE EXCEPTION 'registered key column(s) % are not mapped for %.%',
            array_to_string(v_keys, ','), p_source, p_legacy;
    END IF;

    EXECUTE format('SELECT count(*) FROM %I.%I', p_staging, p_legacy) INTO v_read;
    EXECUTE format('SELECT count(*) FROM platform.%I', v_target) INTO v_before;
    EXECUTE format('SELECT count(*) FROM %I.%I src WHERE EXISTS (SELECT 1 FROM platform.%I t WHERE %s)',
                   p_staging, p_legacy, v_target, v_key_pred) INTO v_existing;

    -- rows that take the target's own representation of "no value" (documented in the
    -- registry note); counted so the substitution is visible in the audit, not silent
    SELECT string_agg(format('src.%I IS NULL', m.legacy_column), ' OR ')
      INTO v_null_pred
      FROM platform.ai_legacy_domain_migration_map m
     WHERE m.legacy_source = p_source AND m.legacy_table = p_legacy
       AND m.transform = 'coalesce_text';
    IF v_null_pred IS NOT NULL THEN
        EXECUTE format('SELECT count(*) FROM %I.%I src WHERE %s', p_staging, p_legacy, v_null_pred)
          INTO v_defaulted;
    END IF;

    EXECUTE format(
        'INSERT INTO platform.%I (%s) SELECT %s FROM %I.%I src'
        ' WHERE NOT EXISTS (SELECT 1 FROM platform.%I t WHERE %s)'
        ' ON CONFLICT DO NOTHING',
        v_target, v_cols, v_exprs, p_staging, p_legacy, v_target, v_key_pred);
    GET DIAGNOSTICS v_inserted = ROW_COUNT;
    v_skipped := v_existing;
    -- ON CONFLICT DO NOTHING also swallows a collision on any *other* unique constraint, and
    -- a duplicate key inside the staging data. Either way a row silently disappears, so the
    -- accounting is checked rather than assumed.
    IF v_inserted <> v_read - v_existing THEN
        RAISE EXCEPTION 'copy of %.% inserted % row(s) but % were expected (% read, % already present by key); row(s) were dropped by another constraint or the staging data has duplicate keys',
            p_staging, p_legacy, v_inserted, v_read - v_existing, v_read, v_existing;
    END IF;
    EXECUTE format('SELECT count(*) FROM platform.%I', v_target) INTO v_present;

    -- Explicit ids were inserted into an identity column; move the sequence past them so a
    -- later application insert cannot collide with a migrated row.
    IF array_length(v_keys, 1) = 1 THEN
        v_seq := pg_get_serial_sequence('platform.' || v_target, v_keys[1]);
        IF v_seq IS NOT NULL THEN
            EXECUTE format('SELECT setval(%L, GREATEST((SELECT COALESCE(max(%I), 0) FROM platform.%I), 1), true)',
                           v_seq, v_keys[1], v_target);
        END IF;
    END IF;

    PERFORM platform.ai_legacy_domain_audit(p_source, p_legacy, v_target, 'copied',
        v_read, v_inserted, v_skipped, v_defaulted, v_before, v_present,
        CASE WHEN v_skipped > 0
             THEN format('%s row(s) already present by key and left untouched', v_skipped)
             ELSE NULL END);
    RETURN 'copied';
END
$copy$;
COMMENT ON FUNCTION platform.ai_legacy_domain_copy_table(text, text, text) IS
    'E2 旧 MySQL AI 域行迁移驱动器：按注册表复制；列不一致即拒绝，绝不隐式映射';
"""

FOOTER_SQL = r"""
-- ---------------------------------------------------------------------------
-- Reconciliation. A copy that cannot account for every source row fails the migration;
-- a blocked table is a recorded, reviewable state (not a silent loss).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    bad         record;
    v_copied    bigint;
    v_blocked   bigint;
    v_absent    bigint;
    v_unaudited text;
    v_list      text;
BEGIN
    SELECT string_agg(tm.legacy_table, ', ' ORDER BY tm.legacy_table) INTO v_unaudited
      FROM platform.ai_legacy_domain_migration_table_map tm
     WHERE NOT EXISTS (SELECT 1 FROM platform.ai_legacy_domain_migration_audit a
                        WHERE a.legacy_source = tm.legacy_source
                          AND a.legacy_table = tm.legacy_table);
    IF v_unaudited IS NOT NULL THEN
        RAISE EXCEPTION 'legacy-domain table(s) without an audit row: %', v_unaudited;
    END IF;

    FOR bad IN
        SELECT a.legacy_table, a.rows_read, a.rows_inserted, a.rows_skipped_existing,
               a.unified_rows_before, a.unified_rows
          FROM platform.ai_legacy_domain_migration_audit a
         WHERE a.mode = 'copied'
           AND (a.rows_read <> a.rows_inserted + a.rows_skipped_existing
                OR a.unified_rows <> a.unified_rows_before + a.rows_inserted)
    LOOP
        RAISE EXCEPTION 'row accounting failed for % (read %, inserted %, skipped %, before %, after %)',
            bad.legacy_table, bad.rows_read, bad.rows_inserted, bad.rows_skipped_existing,
            bad.unified_rows_before, bad.unified_rows;
    END LOOP;

    SELECT count(*) FILTER (WHERE mode = 'copied'),
           count(*) FILTER (WHERE mode = 'blocked'),
           count(*) FILTER (WHERE mode = 'absent'),
           string_agg(legacy_table, ', ' ORDER BY legacy_table) FILTER (WHERE mode = 'blocked')
      INTO v_copied, v_blocked, v_absent, v_list
      FROM platform.ai_legacy_domain_migration_audit a
     WHERE a.legacy_source = '<LEGACY_SOURCE>';

    RAISE NOTICE 'legacy MySQL AI domain: % copied, % blocked, % absent',
        v_copied, v_blocked, v_absent;
    IF v_blocked > 0 THEN
        RAISE WARNING 'legacy MySQL AI-domain rows not copied (platform-side adaptation pending, see platform.ai_legacy_domain_migration_audit.blocked_reason): %',
            v_list;
    END IF;
END $$;
"""

V11_HEADER = """-- ---------------------------------------------------------------------------
-- E2 unified AI domain: move the legacy MySQL AI-domain rows into `platform`.
--
-- Generated by scripts/db/generate-legacy-ai-copy.py from the E0 table map
-- (mydocs/platform-embedded/03-table-map.json), the legacy MySQL install script
-- (<LEGACY_SOURCE>), the frozen AI migrations and the unified DDL (V7..V10).
-- Do not hand-edit the generated parts: change the generator and regenerate.
--
-- Why this exists: V8 copies the legacy `ai` schema and V9/V10 authored the PostgreSQL DDL
-- for the MySQL-only AI domain, but nothing moved *those* rows (E2 spec §5.1b).
--
-- Contract:
--   * The legacy rows must first be loaded into the staging schema `legacy_mysql` with their
--     original MySQL column names (see docs/script/sql/legacy-mysql/10-legacy-ai-domain-staging.sql
--     and the README next to it). No staging schema -> every table is recorded as `absent`
--     and the migration is a no-op, which is the normal fresh-install case.
--   * A table is copied only when every NOT NULL unified column without a default is covered
--     by a same-named legacy column. Otherwise it is recorded as `blocked` with the exact
--     columns that need a platform-side adaptation (E2 §5.1c, E5). Nothing is invented and
--     nothing is mapped by position.
--   * Copies are idempotent: a row whose key is already present is left untouched and counted
--     in rows_skipped_existing.
--   * Narrowing columns are pre-checked; over-long values abort the migration with the row
--     count and samples instead of being truncated.
--   * Every table gets an audit row in platform.ai_legacy_domain_migration_audit and the
--     footer fails the migration if the row accounting does not add up.
-- ---------------------------------------------------------------------------

"""

STAGING_HEADER = """-- ---------------------------------------------------------------------------
-- E2 legacy MySQL AI domain: staging (landing zone) for the row-data migration V11.
--
-- Generated by scripts/db/generate-legacy-ai-copy.py. This file is NOT a Flyway migration:
-- the operator runs it once, before the platform migration chain reaches V11.
--
-- Usage:
--   1. create the staging schema and tables:  psql -f 10-legacy-ai-domain-staging.sql
--   2. load the MySQL rows with their original column names (pgloader or CSV + COPY):
--        pgloader mysql://user@host/ruoyi_ai postgresql:///unified?schema=<STAGING_SCHEMA>
--      or, per table:
--        mysql -e "SELECT * FROM chat_model" --batch --raw > chat_model.tsv
--        psql -c "\\copy <STAGING_SCHEMA>.chat_model FROM 'chat_model.tsv'"
--   3. run the platform migration chain (Flyway applies V11 and moves the rows)
--   4. verify, then drop the landing zone:  DROP SCHEMA <STAGING_SCHEMA> CASCADE;
--
-- Column names are the MySQL names on purpose: V11's mapping registry names them
-- explicitly, and a mismatch is refused rather than resolved by position. Types are the
-- MySQL types mapped to PostgreSQL *without* the tenant normalisation V9 applies, so the
-- casts the copy performs stay visible in the migration.
-- ---------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS <STAGING_SCHEMA>;

"""

ADAPTATION_HEADER = """-- ---------------------------------------------------------------------------
-- E2 legacy MySQL AI domain: landing-zone adaptation and validation (E5).
--
-- Generated by scripts/db/generate-legacy-ai-copy.py. This file is NOT a Flyway migration:
-- the landing-zone owner (DBA) runs it once, after the MySQL rows are loaded and *before*
-- the platform migration chain reaches V11. It materialises the derived columns the merged
-- tables need -- the values no legacy column carries -- and refuses to hand V11 incomplete
-- data.
--
-- Why here and not inside V11: the V11 driver stays table-agnostic (every table-specific
-- fact lives in its registry) and the migration identity only holds USAGE+SELECT on the
-- landing zone. The derivation is therefore computed by the landing-zone owner from the
-- sources named below, and V11 then copies the column like any other one -- including the
-- source-width pre-check when the derived value is wider than the unified column.
--
-- Contract:
--   * every derived value names its sources and its rule; nothing is invented and no default
--     is substituted for a missing source. Missing identity, broken / ambiguous /
--     cross-tenant association and blank required values abort with the row count and
--     samples instead of copying a repaired-looking row;
--   * load order: the parent rows (chat_session) are loaded before this file runs, because a
--     message's public conversation id is resolved from them;
--   * the whole file runs in one transaction: a refusal leaves the landing zone untouched;
--   * it is re-runnable: derived columns are recomputed and re-tightened.
-- ---------------------------------------------------------------------------

"""

ADAPTATION_EXISTS = """DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables t
                    WHERE t.table_schema = '<STAGING_SCHEMA>' AND t.table_name = '{table}') THEN
        RAISE EXCEPTION '落地区表 {schema}.{table} 不存在：请先执行 '
            '<STAGING_SCHEMA>/10-legacy-ai-domain-staging.sql 并装载 MySQL 行数据';
    END IF;
END $$;
"""

ADAPTATION_GUARD = """DO $$
DECLARE
    v_bad     bigint;
    v_samples text;
BEGIN
    SELECT count(*) INTO v_bad FROM {schema}.{table} q WHERE {predicate};
    IF v_bad > 0 THEN
        SELECT string_agg({sample}, ' | ') INTO v_samples
          FROM (SELECT * FROM {schema}.{table} q WHERE {predicate} ORDER BY q.id LIMIT 5) s;
        RAISE EXCEPTION '{problem}（% 行）；样本：%', v_bad, v_samples;
    END IF;
END $$;
"""

REGISTRY_DDL = """
-- ---------------------------------------------------------------------------
-- 1. Registry: what to copy, how each column maps, and how legacy values translate.
--    All three tables hold generated data; the driver below reads them.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS platform.ai_legacy_domain_migration_table_map (
    legacy_source   varchar(64)  NOT NULL,
    legacy_table    varchar(128) NOT NULL,
    unified_table   varchar(128) NOT NULL,
    mode            varchar(16)  NOT NULL,
    key_columns     text[]       NOT NULL,
    copy_order      integer      NOT NULL DEFAULT 100,
    blocked_reason  text,
    recorded_at     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ai_legacy_domain_table_map PRIMARY KEY (legacy_source, legacy_table),
    CONSTRAINT ck_ai_legacy_domain_table_mode CHECK (mode IN ('copy', 'blocked'))
);
COMMENT ON TABLE platform.ai_legacy_domain_migration_table_map IS
    'E2 旧 MySQL AI 域行迁移：逐表模式（copy=可复制 / blocked=需平台侧适配，原因见 blocked_reason）';
COMMENT ON COLUMN platform.ai_legacy_domain_migration_table_map.key_columns IS
    '幂等判定的键（统一表主键）：复制时按 IS NOT DISTINCT FROM 反连接，已存在的行不改写';
COMMENT ON COLUMN platform.ai_legacy_domain_migration_table_map.copy_order IS
    '复制顺序：父表必须先于子表（V7 的复合外键立即生效）；由生成器按冻结外键图计算';

CREATE TABLE IF NOT EXISTS platform.ai_legacy_domain_migration_map (
    legacy_source    varchar(64)  NOT NULL,
    legacy_table     varchar(128) NOT NULL,
    unified_table    varchar(128) NOT NULL,
    legacy_column    varchar(128),
    unified_column   varchar(128) NOT NULL,
    transform        varchar(24)  NOT NULL,
    transform_arg    text,
    legacy_type      varchar(64),
    unified_type     varchar(64),
    unified_length   integer,
    narrowing        boolean      NOT NULL DEFAULT false,
    note             text,
    CONSTRAINT pk_ai_legacy_domain_column_map PRIMARY KEY (legacy_source, legacy_table, unified_column),
    CONSTRAINT ck_ai_legacy_domain_transform
        CHECK (transform IN ('identity', 'cast_text', 'enum', 'coalesce_text', 'constant'))
);
COMMENT ON TABLE platform.ai_legacy_domain_migration_map IS
    'E2 旧 MySQL AI 域行迁移：显式列映射（绝不按位置/隐式映射；transform=enum 时 transform_arg 指向枚举域）';
COMMENT ON COLUMN platform.ai_legacy_domain_migration_map.narrowing IS
    '旧列宽于统一列：复制前做超长预检，超长即报错拒绝，不截断';

CREATE TABLE IF NOT EXISTS platform.ai_legacy_domain_enum_map (
    domain         varchar(64) NOT NULL,
    source_value   varchar(64) NOT NULL,
    target_value   varchar(64) NOT NULL,
    note           text,
    CONSTRAINT pk_ai_legacy_domain_enum_map PRIMARY KEY (domain, source_value)
);
COMMENT ON TABLE platform.ai_legacy_domain_enum_map IS
    'E2 旧 MySQL AI 域行迁移：枚举编码映射（例如 knowledge_attach.status smallint → DocumentStatus 字符串码）';

CREATE TABLE IF NOT EXISTS platform.ai_legacy_domain_migration_audit (
    legacy_source         varchar(64)  NOT NULL,
    legacy_table          varchar(128) NOT NULL,
    unified_table         varchar(128) NOT NULL,
    mode                  varchar(16)  NOT NULL,
    rows_read             bigint       NOT NULL DEFAULT 0,
    rows_inserted         bigint       NOT NULL DEFAULT 0,
    rows_skipped_existing bigint       NOT NULL DEFAULT 0,
    rows_defaulted        bigint       NOT NULL DEFAULT 0,
    unified_rows_before   bigint,
    unified_rows          bigint,
    blocked_reason        text,
    recorded_at           timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ai_legacy_domain_audit PRIMARY KEY (legacy_source, legacy_table),
    CONSTRAINT ck_ai_legacy_domain_audit_mode CHECK (mode IN ('copied', 'blocked', 'absent'))
);
COMMENT ON TABLE platform.ai_legacy_domain_migration_audit IS
    'E2 旧 MySQL AI 域行迁移：逐表行数对账（read = inserted + skipped；unified_rows = before + inserted）';
COMMENT ON COLUMN platform.ai_legacy_domain_migration_audit.rows_defaulted IS
    '旧值为 NULL、按注册表取目标侧空表示的行数（coalesce_text），用于让替代可见而非静默';
"""


# ---------------------------------------------------------------------------
# shape helpers
# ---------------------------------------------------------------------------

def platform_baseline_tables(pg_dir):
    """Tables created by the frozen platform baseline (V1..V6).

    The legacy MySQL script also contains the platform's own tables (sys_*, flow_*, sj_*).
    Those rows are NOT part of this migration: their PostgreSQL tables already exist in the
    baseline and are owned by the platform's own data path. Refusing to touch them here is
    what keeps this migration scoped to the AI domain.

    V1 creates its tables *unqualified* (the connection sets currentSchema=platform), so both
    forms have to be recognised -- a qualified-only scan would silently let the whole
    platform baseline through.
    """
    out = set()
    for name in sorted(os.listdir(pg_dir)):
        if not re.match(r"V[1-6]__.*\.sql$", name):
            continue
        sql = open(os.path.join(pg_dir, name), encoding="utf-8").read()
        out |= {t.lower() for t in re.findall(
            r"CREATE TABLE (?:IF NOT EXISTS )?(?:platform\.)?([A-Za-z_]\w*)", sql, re.I)}
    return out


def unified_shapes(pg_dir):
    """{table: {column: definition}} for V7..V10, including ALTER-added and ALTER-modified columns.

    `ALTER COLUMN x SET NOT NULL` matters: V7 adds member_id and only later makes it NOT
    NULL, and the copy has to know that the column is required.
    """
    shapes = {}
    for name in sorted(os.listdir(pg_dir)):
        if not re.match(r"V(7|8|9|10)__.*\.sql$", name):
            continue
        path = os.path.join(pg_dir, name)
        sql = open(path, encoding="utf-8").read()
        for table, cols in parse_created_table_definitions(path).items():
            shapes.setdefault(table, {}).update(cols)
        for m in re.finditer(r"ALTER TABLE (?:IF EXISTS )?(?:ONLY )?platform\.([a-z_]\w*)\s+([^;]*)",
                             sql, re.I):
            cols = shapes.setdefault(m.group(1), {})
            for am in re.finditer(r"ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:\"(\w+)\"|(\w+))\s+([^;]+)",
                                  m.group(2), re.I):
                col = am.group(1) or am.group(2)
                cols[col] = " ".join(am.group(3).split()).rstrip(";").strip()
            for nm in re.finditer(r"ALTER\s+COLUMN\s+(?:\"(\w+)\"|(\w+))\s+SET\s+NOT\s+NULL",
                                  m.group(2), re.I):
                col = nm.group(1) or nm.group(2)
                if col in cols and not re.search(r"\bNOT NULL\b", cols[col], re.I):
                    cols[col] = cols[col] + " NOT NULL"
            for nm in re.finditer(r"ALTER\s+COLUMN\s+(?:\"(\w+)\"|(\w+))\s+DROP\s+NOT\s+NULL",
                                  m.group(2), re.I):
                col = nm.group(1) or nm.group(2)
                if col in cols:
                    cols[col] = re.sub(r"\s+NOT\s+NULL", "", cols[col], flags=re.I)
    return shapes


def unified_keys(pg_dir, table):
    """(primary key columns, identity columns) for one unified table."""
    pk, ident = [], []
    for name in sorted(os.listdir(pg_dir)):
        if not re.match(r"V(7|8|9|10)__.*\.sql$", name):
            continue
        sql = open(os.path.join(pg_dir, name), encoding="utf-8").read()
        for m in re.finditer(r"CREATE TABLE IF NOT EXISTS platform\.%s\s*\(" % re.escape(table),
                             sql, re.I):
            depth, start, body = 0, m.end() - 1, ""
            for i in range(start, len(sql)):
                if sql[i] == "(":
                    depth += 1
                elif sql[i] == ")":
                    depth -= 1
                    if depth == 0:
                        body = sql[start + 1:i]
                        break
            for part in split_defs(body):
                pm = re.match(r"PRIMARY KEY\s*\(([^)]*)\)", part, re.I)
                if pm:
                    pk = [c.strip().strip('"') for c in pm.group(1).split(",")]
                # V7 quotes its column names, V9/V10 do not; both forms carry inline
                # PRIMARY KEY / GENERATED ... AS IDENTITY.
                cm = (re.match(r'"(?P<q>\w+)"\s+(?P<d>.+)', part, re.S)
                      or re.match(r"(?P<q>\w+)\s+(?P<d>.+)", part, re.S))
                if cm and not re.match(r"^(CONSTRAINT|PRIMARY|UNIQUE|FOREIGN|CHECK)$",
                                       cm.group("q"), re.I):
                    if re.search(r"IDENTITY", cm.group("d"), re.I):
                        ident.append(cm.group("q"))
                    if re.search(r"\bPRIMARY KEY\b", cm.group("d"), re.I):
                        pk.append(cm.group("q"))
        for m in re.finditer(r"ALTER TABLE (?:IF EXISTS )?(?:ONLY )?platform\.%s\s+([^;]*)"
                             % re.escape(table), sql, re.I):
            for pm in re.finditer(r"ADD\s+(?:CONSTRAINT\s+\w+\s+)?PRIMARY KEY\s*\(([^)]*)\)",
                                  m.group(1), re.I):
                pk = [c.strip().strip('"') for c in pm.group(1).split(",")]
    return pk, ident


TYPE_WIDTH = re.compile(r"^\s*(char|varchar|character\s+varying)\s*\(\s*(\d+)\s*\)", re.I)
INTEGER_TYPES = ("bigint", "integer", "smallint")


def base_type(definition):
    """The declared type of a column definition, lower-cased and whitespace-normalised."""
    m = re.match(r"\s*([A-Za-z][A-Za-z ]*?)\s*(\(|$|NOT|DEFAULT|GENERATED)", definition, re.I)
    return " ".join(m.group(1).lower().split()) if m else ""


def char_width(definition):
    # base_type intentionally strips type parameters; widths require the full DDL.
    m = TYPE_WIDTH.match(definition)
    return int(m.group(2)) if m else None


def is_character(definition):
    return base_type(definition) in ("char", "varchar", "character varying", "text")


def is_integer(definition):
    return base_type(definition) in INTEGER_TYPES


def is_required(definition):
    """NOT NULL without a default: the copy must supply a value for this column."""
    return (re.search(r"\bNOT NULL\b", definition, re.I) is not None
            and re.search(r"\bDEFAULT\b", definition, re.I) is None)


def transform_for(legacy_def, unified_def):
    """(transform, narrowing, conflict) for one same-named column pair."""
    if is_character(unified_def):
        if not is_character(legacy_def):
            return "cast_text", False, None
        src_w, dst_w = char_width(legacy_def), char_width(unified_def)
        narrowing = src_w is not None and dst_w is not None and src_w > dst_w
        return "identity", narrowing, None
    if is_integer(unified_def) and is_character(legacy_def):
        # A text column cannot be copied into a numeric column without a cast that may fail
        # on real data; that is a data decision, not a migration detail.
        return None, False, "legacy text column feeding a numeric unified column"
    return "identity", False, None


# ---------------------------------------------------------------------------
# generation
# ---------------------------------------------------------------------------

def legacy_pairs(table_map_path, baseline, unified_tables):
    """The legacy MySQL AI-domain tables: MySQL-sourced targets created by V7..V10.

    The MySQL script also carries the platform's own tables (sys_*, flow_*) whose PostgreSQL
    tables V1..V6 already created; those are excluded structurally, by asking whether the
    frozen baseline creates the target rather than by a hand-maintained name list. Targets
    that no migration creates at all (the snail-job `sj_*` tables are deployment-scoped and
    absent from the unified schema) are reported instead of being copied into nothing.
    """
    doc = json.load(open(table_map_path, encoding="utf-8"))
    pairs, excluded, not_created = {}, [], []
    for entry in doc["tables"]:
        src = entry.get("source") or {}
        tgt = entry.get("target") or {}
        schema = src.get("schema", "")
        if schema != LEGACY_SOURCE and schema != ENTITY_ONLY_SCHEMA:
            continue
        if entry.get("status") == "excluded" or not tgt.get("table"):
            continue
        name = tgt["table"].lower()
        if name in baseline:
            excluded.append((src.get("table"), tgt["table"]))
            continue
        if name not in unified_tables:
            not_created.append((src.get("table"), tgt["table"]))
            continue
        pairs[src["table"]] = {"legacy": src["table"], "unified": tgt["table"],
                               "entity_only": schema == ENTITY_ONLY_SCHEMA}
    return pairs, excluded, not_created


def unified_fk_parents(pg_dir):
    """{child_table: {parent_table}} for the FKs the frozen V7..V10 declare.

    The unified FKs are immediate (not deferrable), so a child copied before its parent fails
    on the constraint even when every row is individually correct. The generator derives the
    copy order and the blocked-parent rule from this graph instead of a hand-kept list.
    """
    parents = {}
    for name in sorted(os.listdir(pg_dir)):
        if not re.match(r"V(7|8|9|10)__.*\.sql$", name):
            continue
        sql = open(os.path.join(pg_dir, name), encoding="utf-8").read()
        for m in re.finditer(r"ALTER TABLE (?:IF EXISTS )?(?:ONLY )?platform\.(\w+)\s+"
                             r"ADD\s+CONSTRAINT\s+\w+\s+FOREIGN KEY\s*\([^)]*\)\s*"
                             r"REFERENCES\s+platform\.(\w+)", sql, re.I):
            parents.setdefault(m.group(1).lower(), set()).add(m.group(2).lower())
    return parents


def assign_copy_order(plan, fk_parents):
    """Parents before children: a table's copy order is one past its deepest parent."""
    by_unified = {p["unified"]: p for p in plan}
    order = {}

    def depth(table, seen):
        if table in order:
            return order[table]
        if table in seen:
            raise SystemExit("foreign-key cycle among unified tables: %s"
                             % ", ".join(sorted(seen | {table})))
        seen = seen | {table}
        parents = [depth(p, seen) for p in sorted(fk_parents.get(table, ())) if p in by_unified]
        order[table] = max(parents) + 1 if parents else 0
        return order[table]

    for p in plan:
        p["copy_order"] = depth(p["unified"], frozenset())


def build_plan(pairs, mysql_raw, shapes, pg_dir, fk_parents=None):
    """Decide, per table, the mode and the explicit column map."""
    plan = []
    for legacy in sorted(pairs):
        info = pairs[legacy]
        unified = info["unified"]
        target = shapes.get(unified)
        if not target:
            raise SystemExit("unified table platform.%s is not created by V7..V10" % unified)

        if info["entity_only"]:
            # No DDL anywhere for this table (V10 authored the target from the Java entity,
            # whose @TableName is the legacy table name), so the legacy column names are the
            # target's own names. The driver still refuses at runtime if the real staging
            # table disagrees.
            legacy_cols = {c: d for c, d in target.items()}
            entity_note = ("旧表在任何脚本中都没有 DDL（V10 依据 Java 实体 org.ruoyi.system.domain."
                           "ChatConfig 补写）：列名沿用目标列名，实际形状以 MySQL 目录为准，"
                           "不一致时迁移拒绝复制")
        else:
            cols, _ = parse_mysql_table(mysql_raw, legacy)
            if cols is None:
                warn("legacy table not found in the MySQL script: %s" % legacy)
                continue
            legacy_cols = dict(cols)
            entity_note = None

        pk, ident = unified_keys(pg_dir, unified)
        adaptation = {e["unified_column"]: e
                      for e in ADAPTATION_COLUMNS if e["legacy_table"] == legacy}
        for e in adaptation.values():
            for source in e["sources"]:
                if source not in legacy_cols:
                    raise SystemExit("adaptation for %s.%s needs legacy column %s, "
                                     "which %s does not declare"
                                     % (legacy, e["unified_column"], source, legacy))
        mapped, unmapped_required, conflicts, narrowing = [], [], [], []
        for col, udef in sorted(target.items()):
            alias = COLUMN_ALIASES.get((legacy, col))
            legacy_col = alias[0] if alias else col
            ldef = legacy_cols.get(legacy_col)
            ad = adaptation.get(col)
            if ad is not None:
                # The landing zone materialises this column from the declared sources; the
                # copy then treats it as an ordinary column and re-checks it at the source
                # width (a derived value wider than the unified column is still refused).
                legacy_col = ad["staging_column"]
                ldef = ad["staging_type"] + " NOT NULL"
            if ldef is None:
                if is_required(udef):
                    unmapped_required.append(col)
                continue
            transform, is_narrowing, conflict = transform_for(ldef, udef)
            if conflict:
                conflicts.append((col, base_type(ldef), base_type(udef), conflict))
                continue
            note = ad["note"] if ad is not None else (alias[1] if alias else entity_note)
            if is_narrowing:
                width_note = "宽度收窄（%s → %s）：超长值在复制时以明确报错拒绝，不静默截断" % (
                    base_type(ldef), base_type(udef))
                note = (note + "；" if note else "") + width_note
            mapped.append({"column": col, "legacy_column": legacy_col,
                           "transform": transform, "narrowing": is_narrowing,
                           "legacy_type": base_type(ldef), "unified_type": base_type(udef),
                           "unified_length": char_width(udef), "note": note,
                           "adapted": ad is not None, "required": is_required(udef),
                           "character": is_character(udef), "legacy_character": is_character(ldef)})
            if is_narrowing:
                narrowing.append(col)

        for em in ENUM_MAPS:
            if em["legacy_table"] != legacy:
                continue
            for row in mapped:
                if row["column"] == em["unified_column"]:
                    row["transform"] = "enum"
                    row["transform_arg"] = em["domain"]
                    row["note"] = ("枚举编码映射（domain=%s）：smallint 编码 → 字符串状态码，"
                                   "取值映射见 platform.ai_legacy_domain_enum_map"
                                   % em["domain"])

        reasons = []
        if unmapped_required:
            reasons.append("统一表 NOT NULL 且无默认值、旧表无同名列：%s"
                           % ", ".join(unmapped_required))
        for col, lt, ut, why in conflicts:
            reasons.append("列 %s 类型方向不可表示（旧=%s → 统一=%s）：%s" % (col, lt, ut, why))
        plan.append({"legacy": legacy, "unified": unified,
                     "mode": "copy" if not reasons else "blocked",
                     "blocked_reason": "；".join(reasons) if reasons else None,
                     "columns": mapped, "key_columns": pk, "identity": ident,
                     "unmapped_required": unmapped_required, "narrowing": narrowing,
                     "adaptation": sorted(adaptation.values(),
                                          key=lambda e: e["unified_column"]),
                     "legacy_column_count": len(legacy_cols) + len(adaptation),
                     "unified_column_count": len(target),
                     "entity_only": info["entity_only"]})

    fk_parents = fk_parents or {}
    assign_copy_order(plan, fk_parents)

    # A blocked parent blocks its children: the unified FKs are immediate, so copying a child
    # whose parent rows were not copied would leave rows that reference nothing. Derived from
    # the frozen FK declarations, not from a hand-kept list of table names.
    blocked = {p["unified"]: p for p in plan if p["mode"] == "blocked"}
    for p in plan:
        if p["mode"] != "copy":
            continue
        parent = sorted(fk_parents.get(p["unified"], set()) & set(blocked))
        if parent:
            p["mode"] = "blocked"
            p["blocked_reason"] = ("父表 platform.%s 处于 blocked（见其 blocked_reason）："
                                   "V7 声明了立即生效的复合外键，先复制子表会产生悬空引用"
                                   % parent[0])
    return plan


def sql_literal(value):
    return "NULL" if value is None else quote(value)


def emit_v11(plan, out_path):
    tables = sorted(plan, key=lambda p: p["legacy"])
    body = [V11_HEADER.replace("<LEGACY_SOURCE>", LEGACY_SOURCE), REGISTRY_DDL]

    body.append("\n-- ---------------------------------------------------------------------------\n"
                "-- 2. Value (enum) mapping, bound to the Java enums that define both sides.\n"
                "-- ---------------------------------------------------------------------------\n")
    for em in ENUM_MAPS:
        body.append("-- domain %s: %s.%s -> %s.%s\n--   legacy enum: %s\n--   unified enum: %s\n"
                    % (em["domain"], em["legacy_table"], em["legacy_column"],
                       em["unified_table_name"], em["unified_column"],
                       em["legacy_enum"], em["unified_enum"]))
        values = ",\n".join(
            "    (%s, %s, %s, %s)" % (sql_literal(em["domain"]), sql_literal(src),
                                     sql_literal(dst), sql_literal(note))
            for src, dst, note in em["values"])
        body.append("INSERT INTO platform.ai_legacy_domain_enum_map\n"
                    "    (domain, source_value, target_value, note)\nVALUES\n%s\n"
                    "ON CONFLICT (domain, source_value) DO UPDATE\n"
                    "   SET target_value = EXCLUDED.target_value, note = EXCLUDED.note;\n" % values)

    body.append("\n-- ---------------------------------------------------------------------------\n"
                "-- 3. Registry contents (generated). Replaced wholesale for this legacy source so\n"
                "--    the generator, not the database, decides what the mapping is.\n"
                "-- ---------------------------------------------------------------------------\n")
    body.append("DELETE FROM platform.ai_legacy_domain_migration_map WHERE legacy_source = %s;\n"
                "DELETE FROM platform.ai_legacy_domain_migration_table_map WHERE legacy_source = %s;\n"
                % (sql_literal(LEGACY_SOURCE), sql_literal(LEGACY_SOURCE)))

    rows = []
    for p in tables:
        keys = ("ARRAY[%s]::text[]" % ", ".join(sql_literal(k) for k in p["key_columns"])
                if p["key_columns"] else "ARRAY[]::text[]")
        rows.append("    (%s, %s, %s, %s, %s, %d, %s)" % (
            sql_literal(LEGACY_SOURCE), sql_literal(p["legacy"]), sql_literal(p["unified"]),
            sql_literal(p["mode"]), keys, p["copy_order"], sql_literal(p["blocked_reason"])))
    body.append("INSERT INTO platform.ai_legacy_domain_migration_table_map\n"
                "    (legacy_source, legacy_table, unified_table, mode, key_columns, copy_order,\n"
                "     blocked_reason)\n"
                "VALUES\n%s;\n" % ",\n".join(rows))

    rows = []
    for p in tables:
        for c in p["columns"]:
            rows.append("    (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)" % (
                sql_literal(LEGACY_SOURCE), sql_literal(p["legacy"]), sql_literal(p["unified"]),
                sql_literal(c.get("legacy_column", c["column"])), sql_literal(c["column"]), sql_literal(c["transform"]),
                sql_literal(c.get("transform_arg")), sql_literal(c["legacy_type"]),
                sql_literal(c["unified_type"]),
                c["unified_length"] if c["unified_length"] is not None else "NULL",
                "true" if c["narrowing"] else "false", sql_literal(c["note"])))
    body.append("INSERT INTO platform.ai_legacy_domain_migration_map\n"
                "    (legacy_source, legacy_table, unified_table, legacy_column, unified_column,\n"
                "     transform, transform_arg, legacy_type, unified_type, unified_length,\n"
                "     narrowing, note)\nVALUES\n%s;\n" % ",\n".join(rows))

    body.append(DRIVER_SQL)
    body.append("""
-- ---------------------------------------------------------------------------
-- 5. Run. Copy-mode tables are copied; blocked/absent tables are validated and recorded.
--    copy_order puts parents before children: the unified FKs are immediate, so a child
--    copied first would fail even though every row is individually correct.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t        record;
    v_result text;
    v_notes  text[] := ARRAY[]::text[];
BEGIN
    FOR t IN SELECT legacy_table
               FROM platform.ai_legacy_domain_migration_table_map
              WHERE legacy_source = %s
              ORDER BY copy_order, legacy_table
    LOOP
        v_result := platform.ai_legacy_domain_copy_table(%s, t.legacy_table);
        IF v_result <> 'copied' THEN
            v_notes := v_notes || (t.legacy_table || '=' || v_result);
        END IF;
    END LOOP;
    IF array_length(v_notes, 1) > 0 THEN
        RAISE NOTICE 'legacy MySQL AI domain: not copied: %%', array_to_string(v_notes, ', ');
    END IF;
END $$;
""" % (sql_literal(LEGACY_SOURCE), sql_literal(LEGACY_SOURCE)))
    body.append(FOOTER_SQL.replace("<LEGACY_SOURCE>", LEGACY_SOURCE))

    sql = "".join(body)
    assert_no_orphan_comment_lines(sql, out_path)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as output:
        output.write(sql)


def emit_staging(mysql_raw, plan, out_path):
    """Landing-zone DDL: original MySQL table and column names, MySQL-ish types.

    Deliberately NOT the unified shape: keeping the legacy shape is what forces the copy to
    state its casts and its enum translation explicitly. tenant_id stays as MySQL declared
    it (usually bigint) instead of being normalised, so the E2-D1 decision ("numeric legacy
    tenants are carried as literal text and are not merged into the platform default
    tenant") happens in V11 where it is visible.
    """
    body = [STAGING_HEADER.replace("<STAGING_SCHEMA>", STAGING_SCHEMA)]
    for p in sorted(plan, key=lambda x: x["legacy"]):
        legacy = p["legacy"]
        cols, comment = parse_mysql_table(mysql_raw, legacy)
        if cols is None:
            body.append("-- ===== %s: no DDL in the shipped MySQL script (entity-only table, see V10);\n"
                        "--       create it from the live MySQL catalog, keeping the column names\n"
                        "--       V11 maps (%s).\n"
                        % (legacy, ", ".join(c["column"] for c in p["columns"])))
            continue
        lines = []
        for name, definition in cols:
            type_match = re.match(r"([a-z]+(?:\s*\([^)]*\))?)", definition, re.I)
            pg_type = map_type(type_match.group(1)) if type_match else "text"
            rest = definition[len(type_match.group(1)):] if type_match else definition
            not_null = " NOT NULL" if re.search(r"\bNOT\s+NULL\b", rest, re.I) else ""
            default = ""
            dm = re.search(r"\bDEFAULT\s+('(?:[^']|'')*'|[^\s,]+)", rest, re.I)
            if dm:
                default = " DEFAULT %s" % dm.group(1)
            lines.append('    "%s" %s%s%s' % (name, pg_type, not_null, default))
        for e in p["adaptation"]:
            lines.append('    -- 落地区适配列（E5，不是 MySQL 原始列）：由 20-legacy-ai-domain-adaptation.sql\n'
                         '    -- 按显式来源计算并收紧 NOT NULL；V11 只把它当普通列复制\n'
                         '    "%s" %s DEFAULT NULL' % (e["staging_column"], e["staging_type"]))
        body.append("-- ===== %s%s =====\nCREATE TABLE IF NOT EXISTS %s.%s (\n%s\n);\n"
                    % (legacy, ("  -- %s" % comment) if comment else "", STAGING_SCHEMA,
                       legacy, ",\n".join(lines)))
    sql = "".join(body)
    assert_no_orphan_comment_lines(sql, out_path)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as output:
        output.write(sql)


def emit_adaptation(plan, out_path):
    """Landing-zone adaptation: derive the merged tables' platform-side columns.

    Refusal is the default here too: a missing source, a broken / ambiguous / cross-tenant
    association or a blank required value aborts the whole file (one transaction), so V11
    never receives a landing zone that looks complete but carries an invented value.
    """
    by_legacy = {p["legacy"]: p for p in plan}
    adapted = {}
    for e in ADAPTATION_COLUMNS:
        adapted.setdefault(e["legacy_table"], []).append(e)

    def guard(table, problem, predicate, sample):
        return ADAPTATION_GUARD.format(schema=STAGING_SCHEMA, table=table,
                                       predicate=predicate, sample=sample, problem=problem)

    body = [ADAPTATION_HEADER.replace("<STAGING_SCHEMA>", STAGING_SCHEMA)]
    body.append("BEGIN;\n")
    for table in sorted(adapted):
        entry = by_legacy[table]
        body.append("\n-- =======================================================================\n"
                    "-- %s -> platform.%s\n"
                    "-- =======================================================================\n"
                    % (table, entry["unified"]))
        body.append(ADAPTATION_EXISTS.replace("<STAGING_SCHEMA>", STAGING_SCHEMA)
                    .format(schema=STAGING_SCHEMA, table=table))
        for e in adapted[table]:
            column = e["staging_column"]
            body.append('ALTER TABLE %s.%s ADD COLUMN IF NOT EXISTS "%s" %s;\n'
                        % (STAGING_SCHEMA, table, column, e["staging_type"]))
            body.append("-- 派生 %s：%s\n" % (e["unified_column"], e["note"]))
            if e["rule"] == "member_platform":
                tenant, user = e["sources"]
                body.append(guard(
                    table,
                    "%s：缺少成员身份来源（%s/%s），拒绝派生 %s，不造默认值"
                    % (table, tenant, user, column),
                    "q.%s IS NULL OR q.%s IS NULL" % (tenant, user),
                    "format('id=%s {c1}=%s {c2}=%s', s.id, s.{c1}, s.{c2})"
                    .format(c1=tenant, c2=user)))
                body.append('UPDATE %s.%s q SET "%s" = \'platform:\' || q.%s::text'
                            ' || \':\' || q.%s::text;\n'
                            % (STAGING_SCHEMA, table, column, tenant, user))
            elif e["rule"] == "session_conversation":
                parent, parent_key, value = e["parent_table"], e["parent_key"], e["parent_value"]
                child_key = e["key_column"]
                tenant, user = e["match_columns"]
                key_sample = "format('id=%s {k}=%s', s.id, s.{k})".format(k=child_key)
                body.append(guard(
                    table,
                    "%s：消息缺少会话关联键（%s），拒绝派生 %s" % (table, child_key, column),
                    "q.%s IS NULL" % child_key, key_sample))
                body.append(guard(
                    table,
                    "%s：落地区 %s 中同一 %s 有多行，会话关联歧义，拒绝派生 %s"
                    % (table, parent, parent_key, column),
                    "q.%s IN (SELECT p.%s FROM %s.%s p GROUP BY p.%s HAVING count(*) > 1)"
                    % (child_key, parent_key, STAGING_SCHEMA, parent, parent_key),
                    key_sample))
                body.append(guard(
                    table,
                    "%s：消息引用的会话在落地区 %s 中不存在（断关联），拒绝派生 %s"
                    % (table, parent, column),
                    "NOT EXISTS (SELECT 1 FROM %s.%s p WHERE p.%s = q.%s)"
                    % (STAGING_SCHEMA, parent, parent_key, child_key),
                    key_sample))
                for match in (tenant, user):
                    body.append(guard(
                        table,
                        "%s：消息与其会话的 %s 不一致（跨租户/跨成员关联），拒绝派生 %s"
                        % (table, match, column),
                        "EXISTS (SELECT 1 FROM %s.%s p WHERE p.%s = q.%s"
                        " AND p.%s::text IS DISTINCT FROM q.%s::text)"
                        % (STAGING_SCHEMA, parent, parent_key, child_key, match, match),
                        "format('id=%s {k}=%s {c}=%s', s.id, s.{k}, s.{c})"
                        .format(c=match, k=child_key)))
                body.append(guard(
                    table,
                    "%s：会话的 %s 缺失或为空，无法作为消息的公开会话标识" % (table, value),
                    "EXISTS (SELECT 1 FROM %s.%s p WHERE p.%s = q.%s"
                    " AND (p.%s IS NULL OR btrim(p.%s) = ''))"
                    % (STAGING_SCHEMA, parent, parent_key, child_key, value, value),
                    key_sample))
                body.append('UPDATE %s.%s q SET "%s" = p.%s\n'
                            '  FROM %s.%s p\n'
                            ' WHERE p.%s = q.%s AND p.%s::text = q.%s::text'
                            ' AND p.%s::text = q.%s::text;\n'
                            % (STAGING_SCHEMA, table, column, value, STAGING_SCHEMA, parent,
                               parent_key, child_key, tenant, tenant, user, user))
            else:
                raise SystemExit("unknown adaptation rule: %s" % e["rule"])

        # Required unified columns fed by the table's own legacy columns: a NULL (or, for the
        # identity/association/role/title fields, a blank) source would fail at V11's INSERT
        # or be repaired silently; refuse it here where the operator can see the rows.
        for c in entry["columns"]:
            if not c["required"] or c["adapted"]:
                continue
            col = c["legacy_column"]
            blank = c["legacy_character"] and c["column"] in NON_BLANK_REQUIRED
            body.append(guard(
                table,
                "%s：统一列 %s 的必填来源 %s 缺失（%s），拒绝复制，不造默认值"
                % (table, c["column"], col, "NULL 或空串" if blank else "NULL"),
                ("q.%s IS NULL OR btrim(q.%s::text) = ''" % (col, col)) if blank
                else ("q.%s IS NULL" % col),
                "format('id=%s', s.id)" if col == "id"
                else "format('id=%s {c}=%s', s.id, s.{c})".format(c=col)))

        for e in adapted[table]:
            body.append('ALTER TABLE %s.%s ALTER COLUMN "%s" SET NOT NULL;\n'
                        % (STAGING_SCHEMA, table, e["staging_column"]))
    body.append("COMMIT;\n")

    sql = "".join(body)
    assert_no_orphan_comment_lines(sql, out_path)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as output:
        output.write(sql)


def main():
    if len(sys.argv) < 7:
        print("usage: python generate-legacy-ai-copy.py <tableMapJson> <mysqlSql> "
              "<pgMigrationDir> <outV11> <outStaging> <outAdaptation>", file=sys.stderr)
        return 2
    map_path, mysql_path, pg_dir, out_v11, out_staging, out_adaptation = sys.argv[1:7]

    for em in ENUM_MAPS:
        for path, markers in ((em["legacy_enum"], em["legacy_markers"]),
                              (em["unified_enum"], em["unified_markers"])):
            full = path if os.path.isabs(path) else os.path.join(REPO_ROOT, path)
            text = open(full, encoding="utf-8").read()
            for marker in markers:
                if marker not in text:
                    print("enum drift: %s no longer declares %r" % (path, marker), file=sys.stderr)
                    return 1

    mysql_raw = strip_line_comments(open(mysql_path, encoding="utf-8", errors="replace").read())
    baseline = platform_baseline_tables(pg_dir)
    shapes = unified_shapes(pg_dir)
    pairs, excluded, not_created = legacy_pairs(map_path, baseline, set(shapes))
    if not pairs:
        print("no legacy MySQL AI-domain tables found in the table map", file=sys.stderr)
        return 1
    plan = build_plan(pairs, mysql_raw, shapes, pg_dir, unified_fk_parents(pg_dir))

    # bind each enum map entry to its unified table for the emitted comment
    by_legacy = {p["legacy"]: p["unified"] for p in plan}
    for em in ENUM_MAPS:
        em["unified_table_name"] = by_legacy.get(em["legacy_table"], "?")

    emit_v11(plan, out_v11)
    emit_staging(mysql_raw, plan, out_staging)
    emit_adaptation(plan, out_adaptation)

    copy_mode = [p for p in plan if p["mode"] == "copy"]
    blocked = [p for p in plan if p["mode"] != "copy"]
    print("legacy AI-domain tables: %d (copy %d, blocked %d)"
          % (len(plan), len(copy_mode), len(blocked)))
    print("excluded, frozen platform baseline already owns the target: %d (%s)"
          % (len(excluded), ", ".join(sorted({t for _, t in excluded}))))
    print("excluded, no unified table exists (out of E2 §5.1b scope): %d (%s)"
          % (len(not_created), ", ".join(sorted({t for _, t in not_created}))))
    for p in plan:
        print("  %-38s -> %-24s %-8s cols %d/%d order=%d%s"
              % (p["legacy"], p["unified"], p["mode"], len(p["columns"]),
                 p["unified_column_count"], p["copy_order"],
                 (" narrowing=" + ",".join(p["narrowing"])) if p["narrowing"] else ""))
        for e in p["adaptation"]:
            print("      adapted: %s <- %s (%s)"
                  % (e["unified_column"], "+".join(e["sources"]), e["rule"]))
        if p["blocked_reason"]:
            print("      blocked: %s" % p["blocked_reason"])
    if WARNINGS:
        print("warnings (%d):" % len(WARNINGS))
        for w in WARNINGS:
            print("  -", w)
    return 0


if __name__ == "__main__":
    sys.exit(main())
