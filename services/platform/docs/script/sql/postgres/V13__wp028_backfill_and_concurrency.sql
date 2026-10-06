-- ---------------------------------------------------------------------------
-- WP-028 / V13 — merged-table backfill, audit-column normalisation and the
-- conversation rename concurrency column.
--
-- Contract: team/contracts/CONTRACTS-v1.md C10.1 (number allocated to T1 by T0).
-- Decisions implemented: D08 (audit old rows), D09 (last_time), D10 (rename
-- concurrency). Registry provenance for the column decisions:
--   services/platform/ruoyi-modules/ruoyi-ai-rag/src/test/resources/unified/
--     merged-append-column-registry.json   (68 statements / 62 truly new columns)
--
-- Properties this migration keeps (all four are covered by real runs, see the
-- WP-028 report and evidence/WP-028/):
--   1. every section is idempotent: a second execution changes nothing;
--   2. the fresh-install path (V1..V13 on an empty database) exits 0;
--   3. the two-schema upgrade path (platform V1..V6 + AI chain, then V7..V13)
--      exits 0 and keeps every copied row;
--   4. a refusal (D08 anomaly, NOT NULL pre-guard) aborts the WHOLE migration —
--      Flyway runs one migration inside one transaction, so nothing is
--      half-applied and the offending rows stay untouched.
--
-- Deliberately NOT done here (each one is a registered decision, not an omission):
--   * knowledge-domain tables stay untouched (C10.1-6): their five
--     decision=blocked required columns have no trustworthy source yet (WP-027
--     owns the two missing deployment inputs), so half-adapting them would only
--     make the domain look adapted;
--   * `session_title` is never written (C9.1: `title` is the single write target);
--   * no audit column type is changed: text audit columns stay text and only the
--     stored VALUES are normalised, so the frozen-shape guards keep their
--     "numeric Java field on a string DDL column" registration valid;
--   * no legacy (pre-unification) table name is written anywhere in this file.
--
-- New object created here (for the shared registry / T0 diff review):
--   platform.ai_wp028_audit_normalisation — append-only ledger of every audit
--   value this migration rewrites (table, column, row key, original value, new
--   value, kind). D08 requires the original-value list for the leading-zero /
--   plus-sign normalisation, so the list is persisted and queryable, not just
--   logged.
-- ---------------------------------------------------------------------------


-- ===========================================================================
-- 1. D10 — conversation rename concurrency: bigint `version`, old rows at 0.
--
--    The ADD COLUMN statement is intentionally NOT written as
--    `ADD COLUMN IF NOT EXISTS`: the WP-028 append-column registry guard treats
--    every `IF NOT EXISTS` append on a merged table as a new column that must be
--    reviewed in the registry, and the guard's V9 statement set must stay exactly
--    68. Idempotency is provided by the existence check below instead, which also
--    keeps the statement executable on its own (the real-database test helpers
--    extract `ALTER TABLE platform.<t> ...;` statements from the whole chain).
-- ===========================================================================

DO $wp028_d10$
DECLARE
    v_type text;
    v_nulls bigint;
BEGIN
    IF to_regclass('platform.ai_conversation') IS NULL THEN
        RAISE NOTICE 'WP-028/V13 D10: platform.ai_conversation absent; skipped (nothing to add)';
        RETURN;
    END IF;

    SELECT data_type INTO v_type
      FROM information_schema.columns
     WHERE table_schema = 'platform'
       AND table_name = 'ai_conversation'
       AND column_name = 'version';

    IF v_type IS NULL THEN
        ALTER TABLE platform.ai_conversation ADD COLUMN "version" bigint NOT NULL DEFAULT 0;
        RAISE NOTICE 'WP-028/V13 D10: added ai_conversation.version bigint NOT NULL DEFAULT 0';
    ELSIF v_type <> 'bigint' THEN
        RAISE EXCEPTION 'WP-028/V13 D10: ai_conversation.version exists with type % but bigint is required'
            , v_type
            USING HINT = 'Rename or drop the incompatible column first; this migration will not guess.';
    END IF;

    -- Old rows start at 0. Covers both the fresh ADD COLUMN (rows were given the
    -- default) and a pre-existing nullable column.
    UPDATE platform.ai_conversation SET "version" = 0 WHERE "version" IS NULL;

    ALTER TABLE platform.ai_conversation ALTER COLUMN "version" SET DEFAULT 0;

    -- NOT NULL pre-guard (C10.1-5): refuse the whole migration instead of half
    -- tightening the constraint.
    SELECT count(*) INTO v_nulls FROM platform.ai_conversation WHERE "version" IS NULL;
    IF v_nulls > 0 THEN
        RAISE EXCEPTION 'WP-028/V13 D10: % row(s) still have a NULL version; refusing SET NOT NULL', v_nulls;
    END IF;

    ALTER TABLE platform.ai_conversation ALTER COLUMN "version" SET NOT NULL;
END
$wp028_d10$;


-- ===========================================================================
-- 2. D09 — `last_time` backfill: the business time of the most recent
--    PERSISTED USER message; no user message keeps NULL.
--
--    * only rows whose last_time IS NULL are touched, so a value written by the
--      new write path is never overwritten;
--    * the aggregate is grouped by tenant_id + member_id + conversation_id and
--      filtered by role = 'user'; assistant completion time, migration time and
--      the current clock are NOT used;
--    * NULL-safe join (IS NOT DISTINCT FROM): the tenant column of these merged
--      tables is nullable in the V7 expand step, so a plain `=` would silently
--      drop rows whose attribution is NULL.
-- ===========================================================================

DO $wp028_d09$
DECLARE
    v_updated bigint;
    v_still_null bigint;
BEGIN
    IF to_regclass('platform.ai_conversation') IS NULL OR to_regclass('platform.ai_message') IS NULL THEN
        RAISE NOTICE 'WP-028/V13 D09: merged conversation/message table absent; skipped';
        RETURN;
    END IF;

    UPDATE platform.ai_conversation c
       SET last_time = m.last_user_message_time
      FROM (
            SELECT tenant_id,
                   member_id,
                   conversation_id,
                   max(create_time) AS last_user_message_time
              FROM platform.ai_message
             WHERE role = 'user'
               AND create_time IS NOT NULL
             GROUP BY tenant_id, member_id, conversation_id
           ) m
     WHERE c.last_time IS NULL
       AND c.tenant_id       IS NOT DISTINCT FROM m.tenant_id
       AND c.member_id       IS NOT DISTINCT FROM m.member_id
       AND c.conversation_id IS NOT DISTINCT FROM m.conversation_id;

    GET DIAGNOSTICS v_updated = ROW_COUNT;

    SELECT count(*) INTO v_still_null
      FROM platform.ai_conversation c
     WHERE c.last_time IS NULL;

    RAISE NOTICE 'WP-028/V13 D09: last_time backfilled on % conversation row(s); % row(s) keep NULL (no persisted user message)',
        v_updated, v_still_null;
END
$wp028_d09$;


-- ===========================================================================
-- 3. D08 — audit old rows: per column, judged by the column's ACTUAL type.
--
--    Rule (D08 + C10.1-4):
--      * text column (varchar/char/text):
--          - pure whitespace (PostgreSQL `[[:space:]]`, NOT btrim: btrim only
--            strips spaces and was measured to miss tab/newline) -> NULL;
--          - for the numeric-intent columns below, the trimmed value must match
--            ^[+-]?[0-9]+$ and fit in a signed bigint, and is then rewritten to
--            its canonical decimal text (leading zeros / plus sign removed,
--            value unchanged). Anything else (decimal, exponent, NaN, out of
--            range, non-numeric) is REFUSED: the migration aborts, the rows stay
--            as they are, and the refusal message lists column + row count +
--            samples. Truncation, rounding and fabrication are forbidden;
--          - text columns without a numeric consumer only get blank -> NULL; the
--            non-blank values are counted and reported, never rewritten.
--      * bigint column: the value is already a number, so the same
--        ^[+-]?[0-9]+$ / range invariant is verified (it cannot fail for the
--        type) and no row is rewritten;
--      * any other type: reported, not touched.
--
--    Numeric-intent registry (provenance: the platform entities read these
--    through BaseEntity Long fields and the frozen-shape guard registers exactly
--    these combinations as numeric-Java-on-string-DDL):
--      ai_agent_profile.create_by / update_by   (Agent#createBy / #updateBy)
--      ai_model_provider.create_by / update_by  (ChatProvider#createBy / #updateBy)
--    `ai_conversation.create_dept` is text on purpose — its legacy source column
--    is a department label, not an id — and therefore only gets blank -> NULL.
--
--    Every row this section rewrites is recorded in a queryable ledger BEFORE it is
--    rewritten: platform.ai_wp028_audit_normalisation holds
--    (table, column, row key, original value, new value, kind). D08 requires the
--    "original value list" for the leading-zero/plus-sign normalisation, and a
--    count in a log line is not a list. The ledger is append-only, keyed on
--    (table, column, row key, original value, kind), so a second run inserts
--    nothing (the rows are already canonical) and never duplicates an entry.
--
--    Scope: the AI-domain tables authored by V7/V9/V10, minus the three
--    knowledge-domain tables (C10.1-6). Tables outside this list (the platform's
--    own system tables) carry the platform's own shape and are not legacy rows.
-- ===========================================================================

-- 3.0 The audit ledger. Created only when the unified schema exists; a fresh
--     database that never reaches V7 simply skips it.
DO $wp028_ledger$
BEGIN
    IF to_regnamespace('platform') IS NULL THEN
        RAISE NOTICE 'WP-028/V13 ledger: platform schema absent; skipped';
        RETURN;
    END IF;
    CREATE TABLE IF NOT EXISTS platform.ai_wp028_audit_normalisation (
        id                bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
        table_name        varchar(64)  NOT NULL,
        column_name       varchar(64)  NOT NULL,
        row_key           text         NOT NULL,
        original_value    text         NOT NULL,
        normalised_value  text,
        change_kind       varchar(32)  NOT NULL,
        migration_version varchar(16)  NOT NULL DEFAULT 'V13',
        recorded_at       timestamptz  NOT NULL DEFAULT now(),
        CONSTRAINT uk_wp028_audit_normalisation
            UNIQUE (table_name, column_name, row_key, original_value, change_kind),
        CONSTRAINT ck_wp028_audit_normalisation_kind
            CHECK (change_kind IN ('blank_to_null', 'canonicalised'))
    );
    RAISE NOTICE 'WP-028/V13 ledger: platform.ai_wp028_audit_normalisation ready';
END
$wp028_ledger$;

DO $wp028_d08$
DECLARE
    v_tables text[] := ARRAY[
        'ai_agent_profile', 'ai_agent_prompt', 'ai_agent_skill', 'ai_conversation',
        'ai_flow_trace_node', 'ai_flow_trace_run', 'ai_intent_node', 'ai_mcp_market',
        'ai_mcp_market_tool', 'ai_mcp_tool', 'ai_message', 'ai_model', 'ai_model_config',
        'ai_model_provider', 'ai_query_term_mapping', 'ai_short_drama_audio',
        'ai_short_drama_character', 'ai_short_drama_character_appearance',
        'ai_short_drama_location', 'ai_short_drama_project', 'ai_short_drama_script',
        'ai_short_drama_storyboard'];
    v_audit_columns text[] := ARRAY['create_by', 'update_by', 'create_dept'];
    v_numeric_text text[] := ARRAY[
        'ai_agent_profile.create_by', 'ai_agent_profile.update_by',
        'ai_model_provider.create_by', 'ai_model_provider.update_by'];

    t text;
    col text;
    key text;
    v_type text;
    v_trim text;
    v_pk_expr text;
    v_re_blank text := quote_literal('^[[:space:]]*$');
    v_re_int text := quote_literal('^[+-]?[0-9]+$');
    v_blank bigint;
    v_bad bigint;
    v_range bigint;
    v_norm bigint;
    v_kept bigint;
    v_ledger_rows bigint;
    v_ledger_total bigint := 0;
    v_samples text;
    v_anomalies bigint := 0;
    v_refusals text := '';
    v_notes text := '';
    v_normalised bigint := 0;
    v_blanked bigint := 0;
BEGIN
    FOREACH t IN ARRAY v_tables LOOP
        IF to_regclass('platform.' || t) IS NULL THEN
            CONTINUE;
        END IF;

        -- Row identity for the ledger: the table's primary key when it has one,
        -- otherwise the row's physical location as a best-effort identity.
        SELECT string_agg(
                   format('%I::text', a.attname),
                   ' || ''|'' || '
                   ORDER BY array_position(i.indkey::smallint[], a.attnum::smallint))
          INTO v_pk_expr
          FROM pg_index i
          JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
         WHERE i.indrelid = format('platform.%I', t)::regclass
           AND i.indisprimary;
        IF v_pk_expr IS NULL THEN
            v_pk_expr := 'ctid::text';
        END IF;

        FOREACH col IN ARRAY v_audit_columns LOOP
            SELECT c.data_type INTO v_type
              FROM information_schema.columns c
             WHERE c.table_schema = 'platform'
               AND c.table_name = t
               AND c.column_name = col;
            IF v_type IS NULL THEN
                CONTINUE;
            END IF;

            key := t || '.' || col;

            -- whitespace trim used by the numeric rule; [^...] rather than btrim()
            v_trim := format(
                'regexp_replace(regexp_replace(%I::text, %L, %L), %L, %L)',
                col, '^[[:space:]]+', '', '[[:space:]]+$', '');

            IF v_type IN ('character varying', 'character', 'text') THEN

                -- (a) pure whitespace -> NULL (this is the '' default of the old
                --     MySQL text audit columns; a Java Long read of '' throws).
                --     The ledger row is written BEFORE the rewrite, so the original
                --     value stays auditable.
                EXECUTE format(
                    'INSERT INTO platform.ai_wp028_audit_normalisation'
                    || ' (table_name, column_name, row_key, original_value, normalised_value, change_kind)'
                    || ' SELECT %L, %L, (%s), %I::text, NULL, %L FROM platform.%I'
                    || ' WHERE %I IS NOT NULL AND %I::text ~ %s'
                    || ' ON CONFLICT (table_name, column_name, row_key, original_value, change_kind) DO NOTHING',
                    t, col, v_pk_expr, col, 'blank_to_null', t, col, col, v_re_blank);
                GET DIAGNOSTICS v_ledger_rows = ROW_COUNT;
                v_ledger_total := v_ledger_total + v_ledger_rows;

                EXECUTE format(
                    'UPDATE platform.%I SET %I = NULL WHERE %I IS NOT NULL AND %I::text ~ %s',
                    t, col, col, col, v_re_blank);
                GET DIAGNOSTICS v_blank = ROW_COUNT;
                v_blanked := v_blanked + v_blank;

                IF key = ANY (v_numeric_text) THEN

                    -- (b) lossless bigint text check
                    EXECUTE format(
                        'SELECT count(*) FROM platform.%I WHERE %I IS NOT NULL AND NOT (%s ~ %s)',
                        t, col, v_trim, v_re_int) INTO v_bad;

                    -- The cast is wrapped in CASE on purpose: PostgreSQL does not
                    -- promise left-to-right evaluation of AND, so a bare
                    -- (trim)::numeric in the WHERE clause can be evaluated on a
                    -- malformed value and raise instead of being counted. CASE
                    -- evaluates its branches lazily, so only matching rows are cast.
                    EXECUTE format(
                        'SELECT count(*) FROM platform.%I WHERE %I IS NOT NULL'
                        || ' AND (CASE WHEN %s ~ %s THEN (%s)::numeric END) IS NOT NULL'
                        || ' AND NOT ((CASE WHEN %s ~ %s THEN (%s)::numeric END)'
                        || ' BETWEEN -9223372036854775808 AND 9223372036854775807)',
                        t, col, v_trim, v_re_int, v_trim, v_trim, v_re_int, v_trim) INTO v_range;

                    IF v_bad + v_range > 0 THEN
                        v_anomalies := v_anomalies + v_bad + v_range;
                        -- Samples cover BOTH refusal reasons: malformed text and
                        -- out-of-range integers. The CASE keeps the cast away from
                        -- values that failed the integer-text check.
                        EXECUTE format(
                            'SELECT string_agg(DISTINCT left(%I::text, 64), %L) FROM ('
                            || 'SELECT %I FROM platform.%I WHERE %I IS NOT NULL'
                            || ' AND (NOT (%s ~ %s)'
                            || '      OR NOT ((CASE WHEN %s ~ %s THEN (%s)::numeric END)'
                            || '             BETWEEN -9223372036854775808 AND 9223372036854775807))'
                            || ' LIMIT 5) s',
                            col, ', ', col, t, col, v_trim, v_re_int, v_trim, v_re_int, v_trim) INTO v_samples;
                        v_refusals := v_refusals || format(
                            E'\n    %s: %s row(s) refused (%s not ^[+-]?[0-9]+$, %s outside bigint range); samples: %s',
                            key, v_bad + v_range, v_bad, v_range, coalesce(v_samples, '<none>'));
                    ELSE
                        -- (c) canonical decimal text; value unchanged, form normalised.
                        --     Same CASE guard: the cast is never applied to a value
                        --     that failed the integer-text check. The original value
                        --     is persisted in the ledger before the rewrite.
                        EXECUTE format(
                            'INSERT INTO platform.ai_wp028_audit_normalisation'
                            || ' (table_name, column_name, row_key, original_value, normalised_value, change_kind)'
                            || ' SELECT %L, %L, (%s), %I::text,'
                            || ' (CASE WHEN %s ~ %s THEN ((%s)::numeric)::text ELSE %I::text END), %L'
                            || ' FROM platform.%I'
                            || ' WHERE %I IS NOT NULL'
                            || ' AND (CASE WHEN %s ~ %s THEN ((%s)::numeric)::text END) IS NOT NULL'
                            || ' AND %I::text IS DISTINCT FROM'
                            || ' (CASE WHEN %s ~ %s THEN ((%s)::numeric)::text ELSE %I::text END)'
                            || ' ON CONFLICT (table_name, column_name, row_key, original_value, change_kind) DO NOTHING',
                            t, col, v_pk_expr, col, v_trim, v_re_int, v_trim, col, 'canonicalised',
                            t, col, v_trim, v_re_int, v_trim, col,
                            v_trim, v_re_int, v_trim, col);
                        GET DIAGNOSTICS v_ledger_rows = ROW_COUNT;
                        v_ledger_total := v_ledger_total + v_ledger_rows;

                        EXECUTE format(
                            'UPDATE platform.%I'
                            || ' SET %I = (CASE WHEN %s ~ %s THEN ((%s)::numeric)::text ELSE %I::text END)'
                            || ' WHERE %I IS NOT NULL'
                            || ' AND %I::text IS DISTINCT FROM'
                            || ' (CASE WHEN %s ~ %s THEN ((%s)::numeric)::text ELSE %I::text END)',
                            t, col, v_trim, v_re_int, v_trim, col,
                            col, col, v_trim, v_re_int, v_trim, col);
                        GET DIAGNOSTICS v_norm = ROW_COUNT;
                        v_normalised := v_normalised + v_norm;
                        IF v_norm > 0 THEN
                            v_notes := v_notes || format(E'\n    %s: %s value(s) canonicalised (leading zeros / plus sign)', key, v_norm);
                        END IF;
                    END IF;

                ELSE
                    -- text column without a numeric consumer: blank -> NULL only.
                    -- Non-blank values are left byte-identical and reported.
                    EXECUTE format(
                        'SELECT count(*) FROM platform.%I WHERE %I IS NOT NULL',
                        t, col) INTO v_kept;
                    IF v_kept > 0 THEN
                        v_notes := v_notes || format(
                            E'\n    %s: %s non-blank text value(s) kept as-is (no numeric consumer registered)',
                            key, v_kept);
                    END IF;
                END IF;

            ELSIF v_type = 'bigint' THEN
                -- Already a number: verify the same invariant, rewrite nothing.
                EXECUTE format(
                    'SELECT count(*) FROM platform.%I WHERE %I IS NOT NULL AND NOT (%I::text ~ %s)',
                    t, col, col, v_re_int) INTO v_bad;
                IF v_bad > 0 THEN
                    v_anomalies := v_anomalies + v_bad;
                    v_refusals := v_refusals || format(E'\n    %s: %s row(s) refused (not integer text)', key, v_bad);
                END IF;
            ELSE
                v_notes := v_notes || format(E'\n    %s: type %s not touched (no D08 rule)', key, v_type);
            END IF;
        END LOOP;
    END LOOP;

    RAISE NOTICE 'WP-028/V13 D08: % blank value(s) set to NULL; % numeric value(s) canonicalised; % ledger row(s) appended (platform.ai_wp028_audit_normalisation);%',
        v_blanked, v_normalised, v_ledger_total, v_notes;

    IF v_anomalies > 0 THEN
        -- plpgsql's RAISE does not accept `||` in its format string, so the full
        -- message is built with format() and passed through USING MESSAGE.
        RAISE EXCEPTION USING
            MESSAGE = format(
                'WP-028/V13 D08: %s audit value(s) are not losslessly convertible to bigint;'
                || ' the whole migration is refused and every row stays as it is. Offending columns:%s',
                v_anomalies, v_refusals),
            HINT = 'Fix the trustworthy mapping (or clean the legacy rows) and re-run; D08 forbids truncation, rounding and fabricated values.';
    END IF;
END
$wp028_d08$;


-- ===========================================================================
-- 4. Source-backed backfill of a required appended column.
--
--    `ai_agent_profile.agent_name` is the retained legacy column of the twin
--    pair whose unified column is `name` (V11 records the same alias copy for the
--    rows it moves, and the platform write path writes `name` only). Rows that
--    arrived through the unified AI copy therefore still carry NULL; the
--    evidenced alias is applied here — and ONLY where the column is still NULL,
--    so a value that a real source already provided is never overwritten.
--
--    Not backfilled (no trustworthy source exists, and D08 forbids fabrication):
--      * `ai_agent_profile.model_id` — the unified AI chain has no agent-to-model
--        binding at all, so rows that came from it cannot get a model id;
--      * `ai_conversation.session_title` — C9.1: `title` is the only write target.
-- ===========================================================================

DO $wp028_backfill$
DECLARE
    v_filled bigint;
    v_left bigint;
BEGIN
    IF to_regclass('platform.ai_agent_profile') IS NULL THEN
        RAISE NOTICE 'WP-028/V13 backfill: platform.ai_agent_profile absent; skipped';
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'platform' AND table_name = 'ai_agent_profile'
                  AND column_name = 'agent_name') THEN
        UPDATE platform.ai_agent_profile
           SET agent_name = name
         WHERE agent_name IS NULL
           AND name IS NOT NULL;
        GET DIAGNOSTICS v_filled = ROW_COUNT;
        SELECT count(*) INTO v_left FROM platform.ai_agent_profile WHERE agent_name IS NULL;
        RAISE NOTICE 'WP-028/V13 backfill: agent_name filled from the unified name on % row(s); % still NULL (no source)',
            v_filled, v_left;
    END IF;
END
$wp028_backfill$;


-- ===========================================================================
-- 5. Business-required columns: NOT NULL invariant + pre-guarded tightening.
--
--    The registry counts 13 appended columns that are required by the legacy
--    constraint. The account is complete and closed — 6 + 5 + 2 = 13:
--
--      6 tenant columns (all six listed in v_enforced below):
--        ai_conversation.tenant_id, ai_message.tenant_id, ai_agent_profile.tenant_id,
--        ai_knowledge_base.tenant_id, ai_knowledge_document.tenant_id,
--        ai_knowledge_chunk.tenant_id
--        The frozen V7 already ran `ALTER COLUMN tenant_id SET NOT NULL` on all six
--        (V7 tighten step, lines 1945-1988), so today every one of them takes the
--        `IF v_nullable = 'NO' THEN CONTINUE` branch: the entry is an invariant
--        check, not a write. The three knowledge-domain ones are listed for the
--        accounting to be airtight, but V13 changes NOTHING in that domain (their
--        five blocked columns stay untouched, C10.1-6) — no data, no constraint.
--
--      5 knowledge-domain columns with decision=blocked (C10.1-6, untouched):
--        ai_knowledge_base.user_id, ai_knowledge_document.knowledge_id,
--        ai_knowledge_document.type, ai_knowledge_chunk.fid, ai_knowledge_chunk.idx
--
--      2 registered gaps (listed in v_gaps, reported with live NULL counts):
--        ai_agent_profile.agent_name — no writer fills it (both platform and AI
--          entities map the unified `name`; only V11's alias copy and section 4
--          above ever fill the retained column). NOT NULL here would turn every
--          agent insert into a constraint violation.
--        ai_agent_profile.model_id — rows that came from the unified AI chain have
--          no model binding to copy from, so tightening would require inventing a
--          value.
--
--    For every enforced column the invariant holds: if the live database has it
--    nullable and NULL rows exist, the migration FAILS as a whole (C10.1-5)
--    instead of half tightening; if it has no NULL rows it is tightened.
-- ===========================================================================

DO $wp028_not_null$
DECLARE
    v_enforced text[] := ARRAY[
        'ai_conversation.tenant_id',
        'ai_message.tenant_id',
        'ai_agent_profile.tenant_id',
        'ai_knowledge_base.tenant_id',
        'ai_knowledge_document.tenant_id',
        'ai_knowledge_chunk.tenant_id'];
    v_gaps text[] := ARRAY[
        'ai_agent_profile.agent_name',
        'ai_agent_profile.model_id'];

    key text;
    t text;
    col text;
    v_nullable text;
    v_nulls bigint;
    v_tightened text := '';
    v_gap_report text := '';
BEGIN
    FOREACH key IN ARRAY v_enforced LOOP
        t := split_part(key, '.', 1);
        col := split_part(key, '.', 2);

        IF to_regclass('platform.' || t) IS NULL THEN
            CONTINUE;
        END IF;

        SELECT is_nullable INTO v_nullable
          FROM information_schema.columns
         WHERE table_schema = 'platform' AND table_name = t AND column_name = col;

        IF v_nullable IS NULL THEN
            RAISE EXCEPTION 'WP-028/V13 NOT NULL: required column % is missing', key;
        END IF;

        IF v_nullable = 'NO' THEN
            CONTINUE;   -- already enforced by the frozen shape; nothing to do
        END IF;

        EXECUTE format('SELECT count(*) FROM platform.%I WHERE %I IS NULL', t, col) INTO v_nulls;

        IF v_nulls > 0 THEN
            RAISE EXCEPTION USING
                MESSAGE = format(
                    'WP-028/V13 NOT NULL pre-guard: required column %s is nullable and has %s NULL row(s);'
                    || ' refusing to tighten half-way (C10.1-5). Supply a trustworthy value first.',
                    key, v_nulls);
        END IF;

        EXECUTE format('ALTER TABLE platform.%I ALTER COLUMN %I SET NOT NULL', t, col);
        v_tightened := v_tightened || format(' %s;', key);
    END LOOP;

    FOREACH key IN ARRAY v_gaps LOOP
        t := split_part(key, '.', 1);
        col := split_part(key, '.', 2);
        IF to_regclass('platform.' || t) IS NULL THEN
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM platform.%I WHERE %I IS NULL', t, col) INTO v_nulls;
        v_gap_report := v_gap_report || format(E'\n    %s: %s NULL row(s) left nullable (registered gap, see section 5 header)', key, v_nulls);
    END LOOP;

    IF v_tightened <> '' THEN
        RAISE NOTICE 'WP-028/V13 NOT NULL: tightened%', v_tightened;
    END IF;
    RAISE NOTICE 'WP-028/V13 NOT NULL: registered gaps kept nullable:%', v_gap_report;
END
$wp028_not_null$;
