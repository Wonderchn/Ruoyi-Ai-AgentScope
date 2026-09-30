-- Superuser-only AI identity bootstrap; keep outside Flyway locations.
-- Credentials enter from environment and must never be written to a file.
\set ON_ERROR_STOP on
\getenv migrate_password AI_MIGRATE_PASSWORD
\getenv app_password AI_APP_PASSWORD
SELECT 1 / (CASE WHEN length(:'migrate_password') >= 12 AND length(:'app_password') >= 12 THEN 1 ELSE 0 END) AS password_guard;

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='migrate_ai') AS create_migrate \gset
\if :create_migrate
CREATE ROLE migrate_ai LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'migrate_password';
\else
ALTER ROLE migrate_ai PASSWORD :'migrate_password';
\endif
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ai_app') AS create_app \gset
\if :create_app
CREATE ROLE ai_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'app_password';
\else
ALTER ROLE ai_app PASSWORD :'app_password';
\endif

-- Fail rather than silently accept a privileged pre-existing identity or wrong owner.
SELECT 1 / (CASE WHEN count(*)=2 AND bool_and(NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls)
                 THEN 1 ELSE 0 END) AS role_guard
FROM pg_roles WHERE rolname IN ('migrate_ai','ai_app');
SELECT 1 / (CASE WHEN NOT EXISTS (
  SELECT 1 FROM pg_auth_members WHERE member IN ('migrate_ai'::regrole,'ai_app'::regrole)
) THEN 1 ELSE 0 END) AS membership_guard;
CREATE SCHEMA IF NOT EXISTS ai AUTHORIZATION migrate_ai;
SELECT 1 / (CASE WHEN nspowner='migrate_ai'::regrole THEN 1 ELSE 0 END) AS owner_guard
FROM pg_namespace WHERE nspname='ai';
SELECT 1 / (CASE WHEN count(*)=1 THEN 1 ELSE 0 END) AS extension_guard
FROM pg_extension WHERE extname='vector' AND extnamespace='extensions'::regnamespace;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM migrate_ai, ai_app;
REVOKE CREATE ON SCHEMA ai FROM ai_app;
REVOKE CREATE ON SCHEMA extensions FROM migrate_ai, ai_app;
SELECT format('REVOKE CREATE ON DATABASE %I FROM migrate_ai, ai_app',current_database()) \gexec
SELECT 1 / (CASE WHEN NOT has_database_privilege('migrate_ai',current_database(),'CREATE')
                      AND NOT has_database_privilege('ai_app',current_database(),'CREATE') THEN 1 ELSE 0 END) AS database_guard;
GRANT USAGE ON SCHEMA ai TO ai_app;
GRANT USAGE ON SCHEMA extensions TO migrate_ai, ai_app;
ALTER ROLE migrate_ai SET search_path=ai,extensions;
ALTER ROLE ai_app SET search_path=ai,extensions;
ALTER DEFAULT PRIVILEGES FOR ROLE migrate_ai IN SCHEMA ai
  GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO ai_app;
ALTER DEFAULT PRIVILEGES FOR ROLE migrate_ai IN SCHEMA ai
  GRANT USAGE,SELECT ON SEQUENCES TO ai_app;
-- Default privileges affect future objects only; also cover existing migrated objects.
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA ai TO ai_app;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA ai TO ai_app;
SELECT 'ai identity bootstrap applied' AS status;
