-- ---------------------------------------------------------------------------
-- V17 — Flyway history privilege hardening (T0 allocation, owner: T1).
--
-- Why this exists (measured defect, not theory): the bootstrap grants
--   ALTER DEFAULT PRIVILEGES FOR ROLE migrate_platform IN SCHEMA platform
--     GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO platform_app;
-- and Flyway creates its history table *inside* the platform schema as
-- migrate_platform. The default privilege therefore also covers
-- platform.flyway_schema_history_platform, so the runtime account could
--   * INSERT a forged row (T8 measured: exit 0, the row landed),
--   * UPDATE an applied migration's checksum (afterwards `flyway validate`
--     fails with "Migration checksum mismatch"),
--   * DELETE history rows and force a replay of already-applied migrations.
-- The "checksum drift is refused" property the A-stage verified therefore did not
-- hold for the application identity at all.
--
-- Why a migration instead of a runbook step: a post-hoc REVOKE misses every newly
-- created schema, while the history table is created on both entry points (fresh
-- install and two-schema upgrade), so the hardening has to travel with the chain.
-- `ALTER DEFAULT PRIVILEGES` is deliberately NOT used in reverse: it would also
-- remove platform_app's write access to the business tables.
--
-- Guards (all four are required, each one exits without failing):
--   1. current_user = 'platform_app'  -> skip, do NOT lock ourselves out: some
--      deployments let the application role run migrations, and revoking its own
--      write access would break every later Flyway history insert;
--   2. the role platform_app does not exist -> nothing to revoke;
--   3. the platform history table does not exist -> nothing to revoke;
--   4. the archived legacy AI history table is handled the same way when present.
--
-- Idempotent: REVOKE of a privilege that was never granted is a no-op, so a second
-- run changes nothing. SELECT is intentionally left in place: the application may
-- keep reading the history for diagnostics; only the write verbs are revoked.
-- ---------------------------------------------------------------------------

DO $wp028_v17$
DECLARE
    v_current text := current_user;
    v_role_exists boolean;
    v_platform_history boolean;
    v_legacy_history boolean;
    v_report text := '';
BEGIN
    IF v_current = 'platform_app' THEN
        RAISE NOTICE 'WP-028/V17: current_user is platform_app; skipping the REVOKE so the migration identity keeps its own history write access';
        RETURN;
    END IF;

    SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_app') INTO v_role_exists;
    IF NOT v_role_exists THEN
        RAISE NOTICE 'WP-028/V17: role platform_app does not exist; nothing to revoke';
        RETURN;
    END IF;

    v_platform_history := to_regclass('platform.flyway_schema_history_platform') IS NOT NULL;
    v_legacy_history := to_regclass('ai.flyway_schema_history_ai_legacy') IS NOT NULL;

    IF NOT v_platform_history AND NOT v_legacy_history THEN
        RAISE NOTICE 'WP-028/V17: no Flyway history table present (fresh or partial database); nothing to revoke';
        RETURN;
    END IF;

    IF v_platform_history THEN
        EXECUTE 'REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON platform.flyway_schema_history_platform FROM platform_app';
        v_report := v_report || E'\n    platform.flyway_schema_history_platform: INSERT/UPDATE/DELETE/TRUNCATE revoked from platform_app (SELECT kept)';
    END IF;

    -- The unified chain archives the legacy AI history as ai.flyway_schema_history_ai_legacy
    -- (V8). It is not created by the platform chain in a fresh install, hence the guard.
    IF v_legacy_history THEN
        EXECUTE 'REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ai.flyway_schema_history_ai_legacy FROM platform_app';
        v_report := v_report || E'\n    ai.flyway_schema_history_ai_legacy: INSERT/UPDATE/DELETE/TRUNCATE revoked from platform_app (SELECT kept)';
    END IF;

    RAISE NOTICE 'WP-028/V17: Flyway history write privileges hardened;%', v_report;
END
$wp028_v17$;
