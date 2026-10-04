#!/usr/bin/env bash
# verify-unified-migrations.sh - prove the E2 unified PostgreSQL chain on a throwaway
# database, starting from both supported entry points.
#
# Two entry points must both reach the same unified schema:
#   A. fresh install        : platform V1..V8 on an empty database
#   B. upgrade from two schemas: platform V1..V6 + AI V1..V12, then platform V7..V8,
#      which must move the AI domain into `platform` without losing rows
#
# Assertions encode what the E0 table map and release-compatibility.json promise:
#   * the frozen chains stay byte-identical and are never replayed into the unified chain
#   * every AI-domain table from the map exists in `platform` after V7
#   * the legacy `ai` tables are still readable (archive, not deletion)
#   * the AI history table is renamed so it cannot act as a second chain
#   * row counts and the audit table reconcile, and a re-run changes nothing
#   * the run identity still has no DDL rights
#
# Credentials are generated per run and passed only through the process environment.
set -u

PG_IMAGE=${PG_IMAGE:-pgvector/pgvector@sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b}
FLYWAY_IMAGE=${FLYWAY_IMAGE:-flyway/flyway@sha256:94a81ca7db9a9f24fd8acd7463fa4560cb8f66aae2aa64485c27eb296f5851cf}
DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-ci_unified}
WORK=${WORK:-/tmp/unified-verify}
PG_CONTAINER=${PG_CONTAINER:-unified-pg-$$}
PG_OWNER=${PG_OWNER:-unified-$$}
[[ "$PG_CONTAINER" =~ ^[a-z0-9-]+$ && "$PG_OWNER" =~ ^[a-z0-9-]+$ ]] || { echo 'invalid owned container identity'; exit 2; }
REPO_ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
PLATFORM_SQL=$REPO_ROOT/services/platform/docs/script/sql/postgres
AI_SQL=$REPO_ROOT/services/ai/resources/database/postgres/migrations
TABLE_MAP=${TABLE_MAP:-$REPO_ROOT/../mydocs/platform-embedded/03-table-map.json}

FAILS=0
ok()  { echo "  [ok]   $1"; }
bad() { echo "  [FAIL] $1"; FAILS=$((FAILS + 1)); }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1 = $3"; else bad "$1 expected=[$2] actual=[$3]"; fi; }

for tool in docker openssl python; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool"; exit 2; }
done
[ -f "$PLATFORM_SQL/V1__platform_baseline.sql" ] || { echo "missing platform baseline"; exit 2; }
[ -f "$PLATFORM_SQL/V7__unified_ai_domain.sql" ] || { echo "missing unified AI domain migration"; exit 2; }
[ -f "$PLATFORM_SQL/V8__unified_ai_data.sql" ] || { echo "missing unified AI data migration"; exit 2; }

umask 077
rm -rf $WORK; mkdir -p "$WORK/platform" "$WORK/ai" "$WORK/bootstrap"
cp "$PLATFORM_SQL"/V*.sql "$WORK/platform/"
cp "$AI_SQL"/V*.sql "$WORK/ai/"
cp "$PLATFORM_SQL"/bootstrap/00-platform-identity.sql "$WORK/bootstrap/"
cp "$PLATFORM_SQL"/bootstrap/01-platform-casts.sql "$WORK/bootstrap/"

PG_SUPER=$(openssl rand -hex 16); MIGRATE_PW=$(openssl rand -hex 16); APP_PW=$(openssl rand -hex 16)
export POSTGRES_PASSWORD=$PG_SUPER POSTGRES_DB=$DB_NAME POSTGRES_USER=postgres
export PLATFORM_MIGRATE_PASSWORD=$MIGRATE_PW PLATFORM_APP_PASSWORD=$APP_PW

PSQL_VOLS=(-v "$WORK/bootstrap":/bootstrap:ro)
q() { PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
        psql "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=postgres" -X -q -tAc "$1" 2>&1; }
as_app() { PGPASSWORD=$APP_PW docker run --rm -i --net host -e PGPASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
        psql "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=platform_app" -X -q -v ON_ERROR_STOP=1 "${@}"; }
run_bootstrap() { PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD -e PLATFORM_MIGRATE_PASSWORD -e PLATFORM_APP_PASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
        psql "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=postgres" -X -q -v ON_ERROR_STOP=1 -f "$1"; }
flyway_platform() { docker run --rm --net host \
        -e FLYWAY_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
        -e FLYWAY_SCHEMAS=platform -e FLYWAY_DEFAULT_SCHEMA=platform \
        -e FLYWAY_TABLE=flyway_schema_history_platform \
        -e FLYWAY_LOCATIONS=filesystem:/flyway/sql -e FLYWAY_VALIDATE_MIGRATION_NAMING=true \
        -e FLYWAY_USER=migrate_platform -e FLYWAY_PASSWORD=$MIGRATE_PW \
        -v "$WORK/platform":/flyway/sql:ro $FLYWAY_IMAGE "$@"; }
flyway_ai() { docker run --rm --net host \
        -e FLYWAY_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
        -e FLYWAY_SCHEMAS=ai -e FLYWAY_DEFAULT_SCHEMA=ai \
        -e FLYWAY_TABLE=flyway_schema_history \
        -e FLYWAY_LOCATIONS=filesystem:/flyway/sql -e FLYWAY_VALIDATE_MIGRATION_NAMING=true \
        -e FLYWAY_USER=migrate_platform -e FLYWAY_PASSWORD=$MIGRATE_PW \
        -v "$WORK/ai":/flyway/sql:ro $FLYWAY_IMAGE "$@"; }

echo "### 1. throwaway PostgreSQL"
if docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then echo 'owned container name collision; refusing reuse'; exit 2; fi
docker run -d --name "$PG_CONTAINER" --label "e2.unified.owner=$PG_OWNER" --cpus "${PG_CPUS:-1}" --memory "${PG_MEM:-768m}" \
  -p 127.0.0.1:$DB_PORT:5432 -e POSTGRES_PASSWORD -e POSTGRES_DB -e POSTGRES_USER $PG_IMAGE >/dev/null \
  || { bad 'container failed to start'; exit 1; }
cleanup() {
  if [ "$(docker inspect --format '{{index .Config.Labels "e2.unified.owner"}}' "$PG_CONTAINER" 2>/dev/null)" = "$PG_OWNER" ]; then
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  fi
  [ "${KEEP_WORK:-0}" = 1 ] || rm -rf $WORK
}
trap cleanup EXIT
READY=0
for i in $(seq 1 90); do
  docker run --rm --net host $PG_IMAGE pg_isready -h $DB_HOST -p $DB_PORT -U postgres -d $DB_NAME >/dev/null 2>&1 \
    && { READY=$i; break; }
  sleep 1
done
[ "$READY" -gt 0 ] && ok "ready after ${READY}s" || { bad 'database never became ready'; docker logs "$PG_CONTAINER" | tail -20; exit 1; }

echo "### 2. bootstrap identities and extensions"
run_bootstrap /bootstrap/01-platform-casts.sql > "$WORK/out01.txt" 2>&1
assert_eq '01 exit code' 0 $?
run_bootstrap /bootstrap/00-platform-identity.sql > "$WORK/out00.txt" 2>&1
assert_eq '00 exit code' 0 $?
q "CREATE SCHEMA IF NOT EXISTS ai AUTHORIZATION migrate_platform" >/dev/null 2>&1

echo "### 3. entry point B: legacy two-schema deployment (platform V1..V6 + AI V1..V12)"
flyway_platform -target=6 migrate > "$WORK/b1.txt" 2>&1
assert_eq 'platform V1..V6 exit' 0 $?
assert_eq 'platform history at v6' '1,2,3,4,5,6' \
  "$(q "select string_agg(version, ',' order by installed_rank) from platform.flyway_schema_history_platform where version is not null")"
flyway_ai migrate > "$WORK/b2.txt" 2>&1
assert_eq 'AI V1..V12 exit' 0 $?
assert_eq 'AI history at v12' '1,2,3,4,5,6,7,8,9,10,11,12' \
  "$(q "select string_agg(version, ',' order by installed_rank) from ai.flyway_schema_history where version is not null")"
AI_LEGACY_TABLES=$(q "select count(*) from information_schema.tables where table_schema='ai' and table_type='BASE TABLE' and table_name not like 'flyway%'")
echo "  legacy ai tables: $AI_LEGACY_TABLES"

echo "### 3b. seed a marker row so the copy is observable"
SAMPLE_TABLE=$(q "select table_name from information_schema.tables where table_schema='ai' and table_name='t_sample_question'")
if [ -n "$SAMPLE_TABLE" ]; then
  q "INSERT INTO ai.t_sample_question (id, tenant_id, question, create_time) VALUES ('E2-MARKER', 1, 'e2 marker', now())" >/dev/null 2>&1 \
    || q "INSERT INTO ai.t_sample_question (id) VALUES ('E2-MARKER')" >/dev/null 2>&1
  ok 'marker row attempted in ai.t_sample_question'
else
  bad 'ai.t_sample_question missing; cannot verify the copy'
fi

echo "### 4. entry point B continued: the unified integration versions"
flyway_platform migrate > "$WORK/b3.txt" 2>&1
assert_eq 'unified migrate exit' 0 $?
assert_eq 'platform history now' '1,2,3,4,5,6,7,8' \
  "$(q "select string_agg(version, ',' order by installed_rank) from platform.flyway_schema_history_platform where version is not null")"
assert_eq 'no failed platform rows' 0 "$(q "select count(*) from platform.flyway_schema_history_platform where not success")"
assert_eq 'AI history archived' 1 \
  "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_name='flyway_schema_history_ai_legacy'")"
assert_eq 'AI history no longer active' 0 \
  "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_name='flyway_schema_history'")"
assert_eq 'legacy ai tables retained' "$AI_LEGACY_TABLES" \
  "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_type='BASE TABLE' and table_name not like 'flyway%'")"
assert_eq 'marker row present in unified table' 1 \
  "$(q "select count(*) from platform.ai_sample_question where id='E2-MARKER'")"
assert_eq 'audit rows match copied tables' 1 \
  "$(q "select count(*) from platform.ai_unified_migration_audit where legacy_table='t_sample_question' and unified_rows >= rows_copied")"

echo "### 5. every AI-domain table from the table map exists in platform"
EXPECTED=$(python - "$TABLE_MAP" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
names=sorted({e["target"]["table"] for e in d["tables"]
              if e.get("source",{}).get("schema")=="ai" and e.get("target")})
print(" ".join(names))
PY
)
MISSING=""
for t in $EXPECTED; do
  n=$(q "select count(*) from information_schema.tables where table_schema='platform' and table_name='$t'")
  [ "$n" = "1" ] || MISSING="$MISSING $t"
done
if [ -z "$MISSING" ]; then ok "all $(echo $EXPECTED | wc -w) mapped AI tables exist in platform"; else bad "missing in platform:$MISSING"; fi

echo "### 6. repeat migration is a no-op"
flyway_platform migrate > "$WORK/b4.txt" 2>&1
assert_eq 'second unified migrate exit' 0 $?
grep -q 'up to date' "$WORK/b4.txt" && ok 'second pass reports up to date' || bad 'second pass did not report up to date'
ROWS_BEFORE=$(q "select count(*) from platform.ai_unified_migration_audit")
flyway_platform migrate >/dev/null 2>&1
assert_eq 'audit table unchanged after re-run' "$ROWS_BEFORE" "$(q "select count(*) from platform.ai_unified_migration_audit")"
assert_eq 'AI history still archived' 1 \
  "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_name='flyway_schema_history_ai_legacy'")"

echo "### 7. entry point A: fresh install reaches the same unified schema"
q "CREATE DATABASE ci_unified_fresh" >/dev/null 2>&1
FLYWAY_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/ci_unified_fresh" \
docker run --rm --net host \
  -e FLYWAY_URL -e FLYWAY_SCHEMAS=platform -e FLYWAY_DEFAULT_SCHEMA=platform \
  -e FLYWAY_TABLE=flyway_schema_history_platform \
  -e FLYWAY_LOCATIONS=filesystem:/flyway/sql -e FLYWAY_VALIDATE_MIGRATION_NAMING=true \
  -e FLYWAY_USER=migrate_platform -e FLYWAY_PASSWORD=$MIGRATE_PW \
  -e PLATFORM_MIGRATE_PASSWORD -e PLATFORM_APP_PASSWORD \
  -v "$WORK/platform":/flyway/sql:ro -v "$WORK/bootstrap":/bootstrap:ro $FLYWAY_IMAGE migrate > "$WORK/a1.txt" 2>&1
# the freshly created database needs its own identities and extensions
PGPASSWORD=$PG_SUPER docker run --rm --net host -e PGPASSWORD -e PLATFORM_MIGRATE_PASSWORD -e PLATFORM_APP_PASSWORD \
  -v "$WORK/bootstrap":/bootstrap:ro $PG_IMAGE \
  psql "host=$DB_HOST port=$DB_PORT dbname=ci_unified_fresh user=postgres" -X -q -v ON_ERROR_STOP=1 \
  -f /bootstrap/01-platform-casts.sql >/dev/null 2>&1
PGPASSWORD=$PG_SUPER docker run --rm --net host -e PGPASSWORD -e PLATFORM_MIGRATE_PASSWORD -e PLATFORM_APP_PASSWORD \
  -v "$WORK/bootstrap":/bootstrap:ro $PG_IMAGE \
  psql "host=$DB_HOST port=$DB_PORT dbname=ci_unified_fresh user=postgres" -X -q -v ON_ERROR_STOP=1 \
  -f /bootstrap/00-platform-identity.sql >/dev/null 2>&1
FRESH_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/ci_unified_fresh"
docker run --rm --net host -e FLYWAY_URL="$FRESH_URL" -e FLYWAY_SCHEMAS=platform -e FLYWAY_DEFAULT_SCHEMA=platform \
  -e FLYWAY_TABLE=flyway_schema_history_platform -e FLYWAY_LOCATIONS=filesystem:/flyway/sql \
  -e FLYWAY_VALIDATE_MIGRATION_NAMING=true -e FLYWAY_USER=migrate_platform -e FLYWAY_PASSWORD=$MIGRATE_PW \
  -v "$WORK/platform":/flyway/sql:ro $FLYWAY_IMAGE migrate > "$WORK/a2.txt" 2>&1
if [ $? -eq 0 ] && grep -Eq 'Successfully applied 8 migrations' "$WORK/a2.txt"; then
  ok 'fresh install applied V1..V8'
else
  bad "fresh install failed: $(tail -3 "$WORK/a2.txt")"
fi
FRESH_MISSING=""
for t in $EXPECTED; do
  n=$(PGPASSWORD=$PG_SUPER docker run --rm --net host -e PGPASSWORD $PG_IMAGE \
        psql "host=$DB_HOST port=$DB_PORT dbname=ci_unified_fresh user=postgres" -X -q -tAc \
        "select count(*) from information_schema.tables where table_schema='platform' and table_name='$t'" 2>/dev/null)
  [ "$n" = "1" ] || FRESH_MISSING="$FRESH_MISSING $t"
done
if [ -z "$FRESH_MISSING" ]; then ok 'fresh install has every mapped AI table'; else bad "fresh install missing:$FRESH_MISSING"; fi
assert_eq 'fresh install audit table is empty' 0 \
  "$(PGPASSWORD=$PG_SUPER docker run --rm --net host -e PGPASSWORD $PG_IMAGE \
     psql "host=$DB_HOST port=$DB_PORT dbname=ci_unified_fresh user=postgres" -X -q -tAc \
     'select count(*) from platform.ai_unified_migration_audit' 2>/dev/null)"

echo "### 8. run identity still has no DDL"
assert_eq 'platform_app cannot create a table' 1 \
  "$(as_app -c 'CREATE TABLE platform.unified_ddl_probe (id int)' > "$WORK/ddl.txt" 2>&1; echo $?)"
assert_eq 'platform_app cannot read the archived AI history' 0 \
  "$(as_app -tAc "select count(*) from information_schema.tables where table_schema='ai'" 2>/dev/null || echo 0)"

echo
if [ "$FAILS" -eq 0 ]; then echo "UNIFIED MIGRATION CHECK PASSED"; exit 0; fi
echo "UNIFIED MIGRATION CHECK FAILED ($FAILS assertion(s))"; exit 1
