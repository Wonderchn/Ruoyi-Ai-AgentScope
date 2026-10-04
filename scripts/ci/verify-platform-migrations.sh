#!/usr/bin/env bash
# verify-platform-migrations.sh - rebuild the platform domain from the repository's own
# PostgreSQL files on a throwaway database, and fail unless every DB2 invariant holds.
#
# Why this exists: DB2 was verified once, by hand, against a lab VM. Without this check the
# SQL files stay outside CI, so any later edit to a migration is unverified. The
# assertions encode findings that cost a full round each to discover:
#  * PG rolls a failed migration back whole and records NO failed row, so a broken migration
#    is judged by exit code plus "nothing landed", never by reading history for failures.
#  * Flyway exits 0 and claims success when it finds zero migration files, and it prints
#  "up to date" in that case too, so the first round must match
#    "Successfully applied 4 migrations" at target v4, then the two P2/P3 permission
#    migrations at v6. History must be counted by version, not by count(*) (Flyway also
#    writes a version=NULL row when it creates the schema).
#  * bootstrap objects (CREATE EXTENSION vector, the implicit varchar->timestamptz cast)
#    need superuser/type owner, so they are not migrations and must be applied separately.
#  * the app identity must not be able to reach public: that ACL, not application code, is
#    what stops a wrong currentSchema from silently serving another domain.
#
# Credentials are generated per run and passed only through process environment (never
# argv or credential files). Everything runs over TCP with password auth, so the container's local
# "trust" entries cannot mask an auth bug.
set -u

PG_IMAGE=${PG_IMAGE:-pgvector/pgvector@sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b}
FLYWAY_IMAGE=${FLYWAY_IMAGE:-flyway/flyway@sha256:94a81ca7db9a9f24fd8acd7463fa4560cb8f66aae2aa64485c27eb296f5851cf}
DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-ci_platform}
WORK=${WORK:-/tmp/dbci-verify}
PG_CONTAINER=${PG_CONTAINER:-dbci-pg-$$}
PG_OWNER=${PG_OWNER:-dbci-$$}
[[ "$PG_CONTAINER" =~ ^[a-z0-9-]+$ && "$PG_OWNER" =~ ^[a-z0-9-]+$ ]] || { echo 'invalid owned container identity'; exit 2; }
REPO_ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
SQL_SRC=$REPO_ROOT/services/platform/docs/script/sql/postgres

FAILS=0
ok()  { echo "  [ok]   $1"; }
bad() { echo "  [FAIL] $1"; FAILS=$((FAILS + 1)); }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1 = $3"; else bad "$1 expected=[$2] actual=[$3]"; fi; }

for tool in docker openssl; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool"; exit 2; }
done
[ -f "$SQL_SRC/V1__platform_baseline.sql" ] || { echo "missing $SQL_SRC/V1__platform_baseline.sql"; exit 2; }

umask 077
rm -rf $WORK; mkdir -p $WORK/sql $WORK/bootstrap
cp "$SQL_SRC"/V*.sql $WORK/sql/                                  # only V*.sql may live in the Flyway location
cp "$SQL_SRC"/bootstrap/00-platform-identity.sql $WORK/bootstrap/
cp "$SQL_SRC"/bootstrap/01-platform-casts.sql $WORK/bootstrap/

PG_SUPER=$(openssl rand -hex 16); MIGRATE_PW=$(openssl rand -hex 16); APP_PW=$(openssl rand -hex 16)
export POSTGRES_PASSWORD=$PG_SUPER POSTGRES_DB=$DB_NAME POSTGRES_USER=postgres
export PLATFORM_MIGRATE_PASSWORD=$MIGRATE_PW PLATFORM_APP_PASSWORD=$APP_PW
export FLYWAY_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME"
export FLYWAY_SCHEMAS=platform FLYWAY_DEFAULT_SCHEMA=platform
export FLYWAY_TABLE=flyway_schema_history_platform FLYWAY_LOCATIONS=filesystem:/flyway/sql
export FLYWAY_VALIDATE_MIGRATION_NAMING=true FLYWAY_USER=migrate_platform FLYWAY_PASSWORD=$MIGRATE_PW
FLYWAY_ENV=(-e FLYWAY_URL -e FLYWAY_SCHEMAS -e FLYWAY_DEFAULT_SCHEMA -e FLYWAY_TABLE
            -e FLYWAY_LOCATIONS -e FLYWAY_VALIDATE_MIGRATION_NAMING -e FLYWAY_USER -e FLYWAY_PASSWORD)

PSQL_VOLS=(-v $WORK/bootstrap:/bootstrap:ro -v $WORK/sql:/flyway/sql:ro)
q() { PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
        psql "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=postgres" -X -q -tAc "$1" 2>&1; }
as_user() { local password; case "$1" in migrate) password=$MIGRATE_PW;; app) password=$APP_PW;; *) return 2;; esac
        PGPASSWORD=$password docker run --rm -i --net host -e PGPASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
        psql "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=$2" -X -q -v ON_ERROR_STOP=1 "${@:3}"; }
run_bootstrap() { PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD -e PLATFORM_MIGRATE_PASSWORD -e PLATFORM_APP_PASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
        psql "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=postgres" -X -q -v ON_ERROR_STOP=1 -f "$1"; }
flyway() { docker run --rm --net host "${FLYWAY_ENV[@]}" -v $WORK/sql:/flyway/sql:ro $FLYWAY_IMAGE "$@"; }
guard_first_migrate() { grep -Eq 'Successfully applied 4 migrations to schema "platform", now at version v4([[:space:]]|$)' "$1"; }
guard_core_migrate()  { grep -Eq 'Successfully applied 2 migrations to schema "platform", now at version v6([[:space:]]|$)' "$1"; }
guard_round_two()     { grep -Eq 'Schema "platform" is up to date' "$1"; }
history_versions()    { q "select count(*) from platform.flyway_schema_history_platform where version in ('1','2','3','4')"; }

echo "### 1. throwaway PostgreSQL (image pinned by digest)"
# Limits are conservative and overridable: `--cpus 2` is refused outright on a 1-vCPU host,
# which looks like "container failed to start" rather than a resource error.
if docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then echo 'owned container name collision; refusing reuse'; exit 2; fi
docker run -d --name "$PG_CONTAINER" --label "p1.boundary.owner=$PG_OWNER" --cpus "${PG_CPUS:-1}" --memory "${PG_MEM:-768m}" -p 127.0.0.1:$DB_PORT:5432 \
  -e POSTGRES_PASSWORD -e POSTGRES_DB -e POSTGRES_USER $PG_IMAGE >/dev/null || { bad 'container failed to start'; exit 1; }
cleanup() {
  if [ "$(docker inspect --format '{{index .Config.Labels "p1.boundary.owner"}}' "$PG_CONTAINER" 2>/dev/null)" = "$PG_OWNER" ]; then
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  fi
  [ "${KEEP_WORK:-0}" = 1 ] || rm -rf $WORK
}
trap cleanup EXIT
READY=0
for i in $(seq 1 90); do
  docker run --rm --net host $PG_IMAGE \
    pg_isready -h $DB_HOST -p $DB_PORT -U postgres -d $DB_NAME >/dev/null 2>&1 && { READY=$i; break; }
  sleep 1
done
[ "$READY" -gt 0 ] && ok "ready after ${READY}s" || { bad 'database never became ready'; docker logs "$PG_CONTAINER" | tail -20; exit 1; }
# Always tear the throwaway container down, including on the early `exit 1` paths above and
# any future one - a CI runner that leaks a bound port fails the next job for the wrong reason.

echo "### 2. version lock (pgvector >= 0.8.0 is a hard floor, not a preference)"
VER=$(q "select default_version from pg_available_extensions where name='vector'")
MINOR=$(echo "$VER" | cut -d. -f2)
echo "  server: $(q 'select version()' | cut -d, -f1) ; pgvector: $VER"
[ "${MINOR:-0}" -ge 8 ] && ok 'pgvector >= 0.8.0' || bad "pgvector too old: $VER"

echo "### 3. bootstrap 01: extension + implicit cast (superuser only, so never a migration)"
run_bootstrap /bootstrap/01-platform-casts.sql > $WORK/out01.txt 2>&1
assert_eq '01 exit code' 0 $?
assert_eq 'vector installed' 1 "$(q "select count(*) from pg_extension where extname='vector'")"
assert_eq 'implicit cast installed' 1 "$(q "select count(*) from pg_cast c join pg_type s on s.oid=c.castsource join pg_type t on t.oid=c.casttarget where s.typname='varchar' and t.typname='timestamptz'")"

echo "### 4. bootstrap 00: identities, domain schema, public hardening"
run_bootstrap /bootstrap/00-platform-identity.sql > $WORK/out00.txt 2>&1
assert_eq '00 exit code' 0 $?
assert_eq 'identities present' 2 "$(q "select count(*) from pg_roles where rolname in ('migrate_platform','platform_app')")"
assert_eq 'domain schema owner' migrate_platform "$(q "select nspowner::regrole::text from pg_namespace where nspname='platform'")"
run_bootstrap /bootstrap/00-platform-identity.sql > $WORK/out00b.txt 2>&1
assert_eq '00 re-run stays idempotent' 0 $?
echo "-- negatives (after the roles exist: before that a connection failure would look like a refusal)"
as_user migrate migrate_platform -f /bootstrap/01-platform-casts.sql > $WORK/neg01.txt 2>&1
RC=$?
if [ $RC -ne 0 ] && grep -q 'ERROR' $WORK/neg01.txt; then ok "bootstrap refused to migrate_platform (rc=$RC)"; else bad 'non-superuser ran bootstrap'; fi
as_user migrate migrate_platform -c "CREATE ROLE rogue_login LOGIN" > $WORK/negrole.txt 2>&1
grep -q 'permission denied to create role' $WORK/negrole.txt && ok 'migrate identity cannot create roles' || bad 'role creation not refused'
echo "-- empty credential env must abort, not create a passwordless role"
PGPASSWORD=$PG_SUPER PLATFORM_MIGRATE_PASSWORD= PLATFORM_APP_PASSWORD= docker run --rm -i --net host -e PGPASSWORD -e PLATFORM_MIGRATE_PASSWORD -e PLATFORM_APP_PASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
  psql "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=postgres" -X -q -v ON_ERROR_STOP=1 \
  -f /bootstrap/00-platform-identity.sql > $WORK/guard.txt 2>&1
if [ $? -ne 0 ] && grep -qi 'division by zero' $WORK/guard.txt; then ok 'credential guard fired'; else bad 'credential guard did not fire'; fi
assert_eq 'platform_app USAGE on public'  f "$(q "select has_schema_privilege('platform_app','public','USAGE')")"
assert_eq 'platform_app CREATE on public' f "$(q "select has_schema_privilege('platform_app','public','CREATE')")"
assert_eq 'migrate_platform CREATE on database' f "$(q "select has_database_privilege('migrate_platform','$DB_NAME','CREATE')")"
assert_eq 'app role search_path' 'platform, extensions' "$(q "select split_part(setconfig[1],'=',2) from pg_db_role_setting s join pg_roles r on r.oid=s.setrole where r.rolname='platform_app'")"

echo "### 5. Flyway applies the immutable v1..v4 baseline and seeds"
flyway -target=4 migrate > $WORK/fly1.txt 2>&1; RC=$?
if [ $RC -eq 0 ] && guard_first_migrate $WORK/fly1.txt; then ok 'migrate exit 0 and applied count = 4'
else bad "migrate rc=$RC guard=$(guard_first_migrate $WORK/fly1.txt && echo yes || echo no)"; tail -8 $WORK/fly1.txt; fi
assert_eq 'history rows for v1..v4' 4 "$(history_versions)"
assert_eq 'baseline version history' '1,2,3,4' "$(q "select string_agg(version, ',' order by installed_rank) from platform.flyway_schema_history_platform where version is not null")"
assert_eq 'failed rows (PG records none)' 0 "$(q "select count(*) from platform.flyway_schema_history_platform where not success")"
assert_eq 'domain base tables' 35 "$(q "select count(*) from information_schema.tables where table_schema='platform' and table_type='BASE TABLE' and table_name != 'flyway_schema_history_platform'")"
assert_eq 'tables with primary key' 35 "$(q "select count(distinct table_name) from information_schema.table_constraints where table_schema='platform' and constraint_type='PRIMARY KEY' and table_name != 'flyway_schema_history_platform'")"
assert_eq 'sys_user columns' 23 "$(q "select count(*) from information_schema.columns where table_schema='platform' and table_name='sys_user'")"
assert_eq 'user_balance definition' 'numeric|20|2|YES|0.00' "$(q "select data_type||'|'||numeric_precision||'|'||numeric_scale||'|'||is_nullable||'|'||column_default from information_schema.columns where table_schema='platform' and table_name='sys_user' and column_name='user_balance'")"
assert_eq 'deliberately excluded objects' 0 "$(q "select count(*) from information_schema.tables where table_schema='platform' and table_name in ('gen_table','gen_table_column','test_demo','test_tree','test_leave')")"
assert_eq 'seed counts via the app identity' 'menu=217 role=5 role_menu=220 dict_data=74 config=19 dept=11 tenant=1 client=3' \
  "$(as_user app platform_app -tAc "select 'menu='||(select count(*) from sys_menu)||' role='||(select count(*) from sys_role)||' role_menu='||(select count(*) from sys_role_menu)||' dict_data='||(select count(*) from sys_dict_data)||' config='||(select count(*) from sys_config)||' dept='||(select count(*) from sys_dept)||' tenant='||(select count(*) from sys_tenant)||' client='||(select count(*) from sys_client)")"

echo "### 5b. P2/P3 append permission declarations without default grants"
flyway migrate > $WORK/flycore.txt 2>&1; RC=$?
if [ $RC -eq 0 ] && guard_core_migrate $WORK/flycore.txt; then ok 'core migrate exit 0 and applied count = 2 (v5..v6)'
else bad "core migrate rc=$RC guard=$(guard_core_migrate $WORK/flycore.txt && echo yes || echo no)"; tail -8 $WORK/flycore.txt; fi
assert_eq 'complete version history' '1,2,3,4,5,6' "$(q "select string_agg(version, ',' order by installed_rank) from platform.flyway_schema_history_platform where version is not null")"
assert_eq 'complete failed rows' 0 "$(q "select count(*) from platform.flyway_schema_history_platform where not success")"
assert_eq 'complete domain base tables' 35 "$(q "select count(*) from information_schema.tables where table_schema='platform' and table_type='BASE TABLE' and table_name != 'flyway_schema_history_platform'")"
assert_eq 'complete tables with primary key' 35 "$(q "select count(distinct table_name) from information_schema.table_constraints where table_schema='platform' and constraint_type='PRIMARY KEY' and table_name != 'flyway_schema_history_platform'")"
assert_eq 'complete sys_user columns' 23 "$(q "select count(*) from information_schema.columns where table_schema='platform' and table_name='sys_user'")"
assert_eq 'complete user_balance definition' 'numeric|20|2|YES|0.00' "$(q "select data_type||'|'||numeric_precision||'|'||numeric_scale||'|'||is_nullable||'|'||column_default from information_schema.columns where table_schema='platform' and table_name='sys_user' and column_name='user_balance'")"
assert_eq 'complete deliberately excluded objects' 0 "$(q "select count(*) from information_schema.tables where table_schema='platform' and table_name in ('gen_table','gen_table_column','test_demo','test_tree','test_leave')")"
assert_eq 'complete seed counts via the app identity' 'menu=227 role=5 role_menu=220 dict_data=74 config=19 dept=11 tenant=1 client=3' \
  "$(as_user app platform_app -tAc "select 'menu='||(select count(*) from sys_menu)||' role='||(select count(*) from sys_role)||' role_menu='||(select count(*) from sys_role_menu)||' dict_data='||(select count(*) from sys_dict_data)||' config='||(select count(*) from sys_config)||' dept='||(select count(*) from sys_dept)||' tenant='||(select count(*) from sys_tenant)||' client='||(select count(*) from sys_client)")"
assert_eq 'P2/P3 canonical permissions' '7114:ai:run:submit,7115:ai:run:cancel,7116:ai:run:resume,7117:ai:run:stream,7118:ai:document:upload,7119:ai:document:ingest,7120:ai:agent:execute,7121:ai:run:approve,7122:ai:run:reconcile,7123:ai:tool:sandbox:write' \
  "$(as_user app platform_app -tAc "select string_agg(menu_id::text||':'||perms, ',' order by menu_id) from sys_menu where menu_id between 7114 and 7123")"
assert_eq 'P2/P3 hidden permission buttons' 10 "$(as_user app platform_app -tAc "select count(*) from sys_menu where menu_id between 7114 and 7123 and parent_id=7100 and menu_type='F' and visible='1' and status='0'")"
assert_eq 'no default P2/P3 role grants' 0 "$(as_user app platform_app -tAc "select count(*) from sys_role_menu where menu_id between 7114 and 7123")"

echo "### 6. no drift on a second pass; applied baseline is immutable"
flyway migrate > $WORK/fly2.txt 2>&1; assert_eq 'second migrate exit' 0 $?
guard_round_two $WORK/fly2.txt && ok 'second pass reports up to date' || bad 'second pass did not report up to date'
assert_eq 'validate exit' 0 "$(flyway validate > $WORK/flyv.txt 2>&1; echo $?)"
# Probe the highest-numbered migration that actually exists: hardcoding V3 produced two
# misleading failures in the "migration file removed" mutation, where V3 is absent anyway.
DRIFT_FILE=$(ls $WORK/sql/V*.sql 2>/dev/null | sort | tail -1)
if [ -n "$DRIFT_FILE" ]; then
  cp "$DRIFT_FILE" "$DRIFT_FILE.orig"
  printf '
-- drift probe
' >> "$DRIFT_FILE"
  flyway validate > $WORK/flyd.txt 2>&1
  if [ $? -ne 0 ] && grep -qi 'checksum mismatch' $WORK/flyd.txt; then ok "editing an applied migration is caught ($(basename "$DRIFT_FILE"))"; else bad 'checksum drift not detected'; fi
  mv "$DRIFT_FILE.orig" "$DRIFT_FILE"
  assert_eq 'validate after restore' 0 "$(flyway validate > $WORK/flyv2.txt 2>&1; echo $?)"
else
  bad 'no migration files present to probe (the baseline itself is missing)'
fi

echo "### 7. broken migration: non-zero exit and nothing half-applied"
cat > $WORK/sql/V99__ci_broken.sql <<'SQL'
CREATE TABLE platform.t_partial_probe (id int);
CREATE TABLE platform.t_bad_probe (id totally_missing_type_ci);
SQL
flyway migrate > $WORK/fly3.txt 2>&1; RC=$?
[ $RC -ne 0 ] && ok "broken migration exits non-zero (rc=$RC)" || bad 'broken migration returned 0'
assert_eq 'no failed row recorded' 0 "$(q "select count(*) from platform.flyway_schema_history_platform where not success")"
assert_eq 'no partial objects' 0 "$(q "select count(*) from information_schema.tables where table_schema='platform' and table_name in ('t_partial_probe','t_bad_probe')")"
rm -f $WORK/sql/V99__ci_broken.sql
assert_eq 'removing the bad file is enough (no repair)' 0 "$(flyway migrate > $WORK/fly3b.txt 2>&1; echo $?)"

echo "### 8. the silent success cases must not pass"
q "CREATE DATABASE ci_scratch" >/dev/null 2>&1
FLYWAY_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/ci_scratch" FLYWAY_USER=postgres FLYWAY_PASSWORD=$PG_SUPER FLYWAY_LOCATIONS=filesystem:/flyway/nowhere \
docker run --rm --net host "${FLYWAY_ENV[@]}" \
  -v $WORK/sql:/flyway/sql:ro $FLYWAY_IMAGE migrate > $WORK/fly4.txt 2>&1; RC=$?
assert_eq 'Flyway exits 0 when it finds no migration files' 0 "$RC"          # the hazard, documented
guard_first_migrate $WORK/fly4.txt && bad 'guard missed the zero-migration case' || ok 'guard rejects a zero-migration run'
assert_eq 'no versions recorded in that run' 0 "$(PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD $PG_IMAGE \
  psql "host=$DB_HOST port=$DB_PORT dbname=ci_scratch user=postgres" -X -q -tAc "select count(*) from platform.flyway_schema_history_platform where version in ('1','2','3','4')" | tr -d '[:space:]')"
# Self-contained content: copying V3 here made this case fail for the wrong reason in the
# "migration file removed" mutation.
printf 'SELECT 1;
' > $WORK/sql/late-hotfix.sql
flyway migrate > $WORK/fly6.txt 2>&1
if [ $? -ne 0 ] && grep -qi 'Invalid SQL filenames' $WORK/fly6.txt; then ok 'misnamed migration file fails the job'; else bad 'misnamed file was not rejected'; fi
rm -f $WORK/sql/late-hotfix.sql

echo "### 9. domain isolation holds at the database layer"
q "CREATE SCHEMA IF NOT EXISTS ai_probe AUTHORIZATION migrate_platform" >/dev/null 2>&1
q "CREATE TABLE IF NOT EXISTS ai_probe.t_probe (id int)" >/dev/null 2>&1
as_user app platform_app -tAc "select count(*) from ai_probe.t_probe" > $WORK/xd.txt 2>&1
grep -qi 'permission denied' $WORK/xd.txt && ok 'app identity cannot read another domain' || { bad 'cross-domain read allowed'; cat $WORK/xd.txt; }
assert_eq 'other-domain tables visible to the app role' 0 "$(as_user app platform_app -tAc "select count(*) from information_schema.tables where table_schema='ai_probe'")"
assert_eq 'app role cannot run DDL in its own domain' 1 "$(as_user app platform_app -c "CREATE TABLE platform.app_write_probe (id int)" > $WORK/aw.txt 2>&1; echo $?)"

echo "### 10. summary"
[[ "${KEEP_WORK:-0}" = 1 ]] && echo "  work dir kept for inspection: $WORK"

echo
if [ "$FAILS" -eq 0 ]; then echo "PLATFORM MIGRATION CHECK PASSED"; exit 0; fi
echo "PLATFORM MIGRATION CHECK FAILED ($FAILS assertion(s))"; exit 1
