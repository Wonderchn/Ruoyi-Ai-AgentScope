#!/usr/bin/env bash
# verify-ai-migrations.sh - rebuild BOTH domains from the repository's own PostgreSQL
# files on a throwaway database and fail unless the current frozen migration set holds:
# platform exactly V1..V6, AI exactly V1..V12, applied by real Native Flyway with
# separate migrate/app roles per domain.
#
# Why this exists: the db job only rebuilds the platform domain, and the old
# scripts/db/verify-ai-domain.sh still asserts the early "AI 2 / platform 3" set.
# The shipped SQL now carries twelve AI migrations (V5 run/state refs, V8
# run-execution+ingestion, V9 upload intents, V12 agent action ledgers, ...) and six
# platform migrations, and nothing in CI proved that combined chain actually applies.
#
# The expected migration list is frozen below as an exact version sequence - a missing
# file must fail even though Flyway happily reports "up to date" for whatever remains.
# Business invariants follow the same rules the platform check learned the hard way:
#   * a failed migration is judged by exit code plus "nothing landed" (PG records no
#     failed history row),
#   * bootstrap objects (vector extension, casts) are superuser-only and never
#     migrations,
#   * the app identities must not reach public or the other domain - the ACL, not
#     application code, is what stops a wrong currentSchema from serving another
#     domain's data.
#
# Credentials are generated per run and passed only through process environment (never
# argv or credential files). The container is owned (name collision => refuse, never
# reuse) and always torn down, including on early failure paths.
set -u

PG_IMAGE=${PG_IMAGE:-pgvector/pgvector@sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b}
FLYWAY_IMAGE=${FLYWAY_IMAGE:-flyway/flyway@sha256:94a81ca7db9a9f24fd8acd7463fa4560cb8f66aae2aa64485c27eb296f5851cf}
DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-ci_combined}
WORK=${WORK:-/tmp/dbci-ai-verify}
PG_CONTAINER=${PG_CONTAINER:-dbai-pg-$$}
OWNER=${OWNER:-dbai-$$}
[[ "$PG_CONTAINER" =~ ^[a-z0-9-]+$ && "$OWNER" =~ ^[a-z0-9-]+$ ]] || { echo 'invalid owned container identity'; exit 2; }
REPO_ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
PLATFORM_SQL=$REPO_ROOT/services/platform/docs/script/sql/postgres
AI_SQL=$REPO_ROOT/services/ai/resources/database/postgres/migrations
AI_BOOTSTRAP=$REPO_ROOT/services/ai/resources/database/postgres/bootstrap

FAILS=0
ok()  { echo "  [ok]   $1"; }
bad() { echo "  [FAIL] $1"; FAILS=$((FAILS + 1)); }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1 = $3"; else bad "$1 expected=[$2] actual=[$3]"; fi; }

for tool in docker openssl; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool"; exit 2; }
done
[ -f "$AI_SQL/V12__agent_action_ledgers.sql" ] || { echo "missing $AI_SQL/V12__agent_action_ledgers.sql"; exit 2; }
[ -f "$PLATFORM_SQL/V6__agent_action_permissions.sql" ] || { echo "missing $PLATFORM_SQL/V6__agent_action_permissions.sql"; exit 2; }

umask 077
rm -rf "$WORK"; mkdir -p "$WORK"/sql/ai "$WORK"/sql/platform "$WORK"/bootstrap
source "$(dirname "${BASH_SOURCE[0]}")/stage-legacy-platform.sh"
stage_legacy_platform "$PLATFORM_SQL" "$WORK/sql/platform" || exit 2
cp "$AI_SQL"/V*.sql "$WORK/sql/ai/"
cp "$PLATFORM_SQL"/bootstrap/*.sql "$WORK/bootstrap/"
cp "$AI_BOOTSTRAP"/*.sql "$WORK/bootstrap/"

PG_SUPER=$(openssl rand -hex 16); PLATFORM_MIGRATE_PW=$(openssl rand -hex 16); PLATFORM_APP_PW=$(openssl rand -hex 16)
AI_MIGRATE_PW=$(openssl rand -hex 16); AI_APP_PW=$(openssl rand -hex 16)
export POSTGRES_PASSWORD=$PG_SUPER POSTGRES_DB=$DB_NAME POSTGRES_USER=postgres
export PLATFORM_MIGRATE_PASSWORD=$PLATFORM_MIGRATE_PW PLATFORM_APP_PASSWORD=$PLATFORM_APP_PW
export AI_MIGRATE_PASSWORD=$AI_MIGRATE_PW AI_APP_PASSWORD=$AI_APP_PW

PSQL_VOLS=(-v "$WORK/bootstrap:/bootstrap:ro" -v "$WORK/sql:/flyway/sql:ro")
q() { PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
        psql "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=postgres" -X -q -tAc "$1" 2>&1; }
as_user() { local password; case "$1" in
        migrate_ai) password=$AI_MIGRATE_PW;; ai_app) password=$AI_APP_PW;;
        migrate_platform) password=$PLATFORM_MIGRATE_PW;; platform_app) password=$PLATFORM_APP_PW;;
        *) return 2;; esac
        PGPASSWORD=$password docker run --rm -i --net host -e PGPASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
        psql "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=$1" -X -q -v ON_ERROR_STOP=1 "${@:2}"; }
bootstrap() { PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD \
        -e PLATFORM_MIGRATE_PASSWORD -e PLATFORM_APP_PASSWORD -e AI_MIGRATE_PASSWORD -e AI_APP_PASSWORD \
        "${PSQL_VOLS[@]}" $PG_IMAGE psql "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=postgres" \
        -X -q -v ON_ERROR_STOP=1 -f "/bootstrap/$1" 2>&1; }
flyway() { local domain=$1; shift
        local user password table
        if [ "$domain" = ai ]; then user=migrate_ai; password=$AI_MIGRATE_PW; else user=migrate_platform; password=$PLATFORM_MIGRATE_PW; fi
        docker run --rm --net host \
          -e FLYWAY_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME?currentSchema=$domain,extensions" \
          -e FLYWAY_USER="$user" -e FLYWAY_PASSWORD="$password" \
          -e FLYWAY_SCHEMAS="$domain" -e FLYWAY_DEFAULT_SCHEMA="$domain" \
          -e FLYWAY_TABLE="flyway_schema_history_$domain" -e FLYWAY_LOCATIONS="filesystem:/flyway/sql/$domain" \
          -e FLYWAY_VALIDATE_MIGRATION_NAMING=true \
          -v "$WORK/sql:/flyway/sql:ro" $FLYWAY_IMAGE "$@"; }
platform_fingerprint() { PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
        pg_dump "host=$DB_HOST port=$DB_PORT dbname=$DB_NAME user=postgres" -n platform \
        | sed '/^\\restrict /d; /^\\unrestrict /d' | sha256sum | cut -d' ' -f1; }

echo "### 1. throwaway PostgreSQL (image pinned by digest, owner-labeled)"
if docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then echo 'owned container name collision; refusing reuse'; exit 2; fi
docker run -d --name "$PG_CONTAINER" --label "p1.boundary.owner=$OWNER" --cpus "${PG_CPUS:-1}" --memory "${PG_MEM:-768m}" \
  -p 127.0.0.1:$DB_PORT:5432 -e POSTGRES_PASSWORD -e POSTGRES_DB -e POSTGRES_USER $PG_IMAGE >/dev/null \
  || { bad 'container failed to start'; exit 1; }
cleanup() {
  if [ "$(docker inspect --format '{{index .Config.Labels "p1.boundary.owner"}}' "$PG_CONTAINER" 2>/dev/null)" = "$OWNER" ]; then
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  fi
  [ "${KEEP_WORK:-0}" = 1 ] || rm -rf "$WORK"
}
trap cleanup EXIT
READY=0
for i in $(seq 1 90); do
  docker run --rm --net host $PG_IMAGE pg_isready -h $DB_HOST -p $DB_PORT -U postgres -d $DB_NAME >/dev/null 2>&1 && { READY=$i; break; }
  sleep 1
done
[ "$READY" -gt 0 ] && ok "ready after ${READY}s" || { bad 'database never became ready'; docker logs "$PG_CONTAINER" | tail -20; exit 1; }

echo "### 2. version lock (pgvector >= 0.8.0 is a hard floor)"
VER=$(q "select default_version from pg_available_extensions where name='vector'")
MINOR=$(echo "$VER" | cut -d. -f2)
echo "  server: $(q 'select version()' | cut -d, -f1) ; pgvector: $VER"
[ "${MINOR:-0}" -ge 8 ] && ok 'pgvector >= 0.8.0' || bad "pgvector too old: $VER"

echo "### 3. bootstrap (superuser-only objects are never migrations)"
bootstrap 01-platform-casts.sql > "$WORK/out01.txt"; assert_eq 'platform casts exit' 0 $?
bootstrap 00-platform-identity.sql > "$WORK/out00.txt"; assert_eq 'platform identity exit' 0 $?
bootstrap 00-ai-identity.sql > "$WORK/outai.txt"; assert_eq 'ai identity exit' 0 $?
bootstrap 00-ai-identity.sql > "$WORK/outai2.txt"; assert_eq 'ai identity re-run idempotent' 0 $?
assert_eq 'four identities present' 4 "$(q "select count(*) from pg_roles where rolname in ('migrate_platform','platform_app','migrate_ai','ai_app')")"

echo "### 4. platform applies exactly V1..V6"
flyway platform migrate > "$WORK/fly-platform.txt" 2>&1; RC=$?
if [ $RC -eq 0 ] && grep -Eq 'Successfully applied 6 migrations to schema "platform", now at version v6([[:space:]]|$)' "$WORK/fly-platform.txt"; then
  ok 'platform migrate exit 0 and applied count = 6'
else bad "platform migrate rc=$RC"; tail -8 "$WORK/fly-platform.txt"; fi
assert_eq 'platform version history frozen list' '1,2,3,4,5,6' \
  "$(q "select string_agg(version, ',' order by installed_rank) from platform.flyway_schema_history_platform where version is not null")"
assert_eq 'platform failed rows' 0 "$(q "select count(*) from platform.flyway_schema_history_platform where not success")"
PLATFORM_BEFORE=$(platform_fingerprint)

echo "### 5. AI applies exactly V1..V12 (real Native Flyway)"
flyway ai migrate > "$WORK/fly-ai.txt" 2>&1; RC=$?
if [ $RC -eq 0 ] && grep -Eq 'Successfully applied 12 migrations to schema "ai", now at version v12([[:space:]]|$)' "$WORK/fly-ai.txt"; then
  ok 'AI migrate exit 0 and applied count = 12'
else bad "AI migrate rc=$RC"; tail -8 "$WORK/fly-ai.txt"; fi
assert_eq 'AI version history frozen list' '1,2,3,4,5,6,7,8,9,10,11,12' \
  "$(q "select string_agg(version, ',' order by installed_rank) from ai.flyway_schema_history_ai where version is not null")"
assert_eq 'AI failed rows' 0 "$(q "select count(*) from ai.flyway_schema_history_ai where not success")"

echo "### 6. structure anchors across the chain (frozen, spot-checked by introducing version)"
assert_eq 'V5 ai_run table' 1 "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_name='ai_run'")"
assert_eq 'V8 outbox_event table' 1 "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_name='outbox_event'")"
assert_eq 'V9 ai_upload_intent table' 1 "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_name='ai_upload_intent'")"
assert_eq 'V12 ai_tool_call table' 1 "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_name='ai_tool_call'")"
assert_eq 'V12 ai_action_approval table' 1 "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_name='ai_action_approval'")"
assert_eq 'V12 ai_agent_checkpoint table' 1 "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_name='ai_agent_checkpoint'")"
assert_eq 'vector dimension 1536' 1536 "$(q "select atttypmod from pg_attribute where attrelid='ai.t_knowledge_vector'::regclass and attname='embedding'")"
assert_eq 'every AI business table owned by migrate_ai' 0 "$(q "select count(*) from pg_tables where schemaname='ai' and tableowner!='migrate_ai'")"
AI_TABLES=$(q "select count(*) from information_schema.tables where table_schema='ai' and table_type='BASE TABLE' and table_name != 'flyway_schema_history_ai'")
echo "  AI base tables observed: $AI_TABLES"
assert_eq 'AI base tables (frozen from the V1..V12 DDL)' "${AI_TABLES_EXPECTED:-60}" "$AI_TABLES"

echo "### 7. repeat migrate + validate"
flyway ai migrate > "$WORK/fly-ai2.txt" 2>&1; assert_eq 'AI second migrate exit' 0 $?
grep -q 'Schema "ai" is up to date' "$WORK/fly-ai2.txt" && ok 'AI second pass reports up to date' || bad 'AI second pass not up to date'
assert_eq 'AI validate exit' 0 "$(flyway ai validate > "$WORK/fly-aiv.txt" 2>&1; echo $?)"
assert_eq 'platform unchanged by AI migration' "$PLATFORM_BEFORE" "$(platform_fingerprint)"

echo "### 8. app roles: DML works, DDL and cross-domain are refused"
as_user migrate_ai -c "CREATE TABLE ai.dbci_canary (id bigserial PRIMARY KEY, value text)" >/dev/null 2>&1
as_user migrate_ai -c "GRANT INSERT,SELECT,UPDATE,DELETE ON ai.dbci_canary TO ai_app" >/dev/null 2>&1
assert_eq 'ai_app own DML' 'restored' "$(as_user ai_app -tAc "INSERT INTO ai.dbci_canary(value) VALUES ('synthetic'); UPDATE ai.dbci_canary SET value='restored'; SELECT value FROM ai.dbci_canary; DELETE FROM ai.dbci_canary" )"
as_user ai_app -c "CREATE TABLE ai.dbci_forbidden (id int)" > "$WORK/neg-ai-ddl.txt" 2>&1
if [ $? -ne 0 ] && grep -qi 'permission denied' "$WORK/neg-ai-ddl.txt"; then ok 'ai_app own-domain DDL refused'; else bad 'ai_app ran DDL in its own domain'; fi
as_user ai_app -c "SELECT count(*) FROM platform.sys_user" > "$WORK/neg-ai-xd.txt" 2>&1
grep -qi 'permission denied' "$WORK/neg-ai-xd.txt" && ok 'ai_app cross-domain read refused' || bad 'ai_app read platform domain'
as_user ai_app -c "INSERT INTO platform.sys_menu(menu_id,menu_name) VALUES (1,'x')" > "$WORK/neg-ai-xd2.txt" 2>&1
grep -qi 'permission denied' "$WORK/neg-ai-xd2.txt" && ok 'ai_app cross-domain write refused' || bad 'ai_app wrote platform domain'
q "CREATE TABLE public.t_user (id text); INSERT INTO public.t_user VALUES ('decoy')" >/dev/null 2>&1
as_user ai_app -c "SELECT * FROM public.t_user" > "$WORK/neg-ai-pub.txt" 2>&1
grep -qi 'permission denied' "$WORK/neg-ai-pub.txt" && ok 'ai_app public same-name decoy refused' || bad 'ai_app read public decoy'
as_user platform_app -c "SELECT count(*) FROM ai.t_user" > "$WORK/neg-plat-xd.txt" 2>&1
grep -qi 'permission denied' "$WORK/neg-plat-xd.txt" && ok 'platform_app cross-domain read refused' || bad 'platform_app read ai domain'
as_user ai_app -c "SET ROLE migrate_ai" > "$WORK/neg-ai-role.txt" 2>&1
if [ $? -ne 0 ]; then ok 'ai_app cannot become migration owner'; else bad 'ai_app escalated to migrate_ai'; fi
as_user migrate_platform -c "CREATE TABLE platform.dbci_canary (id bigserial PRIMARY KEY, value text)" >/dev/null 2>&1
as_user migrate_platform -c "GRANT INSERT,SELECT,UPDATE,DELETE ON platform.dbci_canary TO platform_app" >/dev/null 2>&1
assert_eq 'platform_app own DML' 'restored' "$(as_user platform_app -tAc "INSERT INTO platform.dbci_canary(value) VALUES ('synthetic'); UPDATE platform.dbci_canary SET value='restored'; SELECT value FROM platform.dbci_canary; DELETE FROM platform.dbci_canary")"

echo "### 9. checksum drift and missing migrations must fail validate"
cp "$WORK/sql/ai/V12__agent_action_ledgers.sql" "$WORK/V12.orig"
printf '\n-- drift probe\n' >> "$WORK/sql/ai/V12__agent_action_ledgers.sql"
flyway ai validate > "$WORK/fly-drift.txt" 2>&1
if [ $? -ne 0 ] && grep -qi 'checksum mismatch' "$WORK/fly-drift.txt"; then ok 'editing an applied AI migration is caught (V12)'; else bad 'AI checksum drift not detected'; fi
mv "$WORK/V12.orig" "$WORK/sql/ai/V12__agent_action_ledgers.sql"
assert_eq 'validate after restore' 0 "$(flyway ai validate > "$WORK/fly-aiv2.txt" 2>&1; echo $?)"
cp "$WORK/sql/platform/V6__agent_action_permissions.sql" "$WORK/P6.orig"
printf '\n-- drift probe\n' >> "$WORK/sql/platform/V6__agent_action_permissions.sql"
flyway platform validate > "$WORK/fly-drift-p.txt" 2>&1
if [ $? -ne 0 ] && grep -qi 'checksum mismatch' "$WORK/fly-drift-p.txt"; then ok 'editing an applied platform migration is caught (V6)'; else bad 'platform checksum drift not detected'; fi
mv "$WORK/P6.orig" "$WORK/sql/platform/V6__agent_action_permissions.sql"
# a removed late migration: the frozen history list must catch the hole, not "up to date"
cp "$WORK/sql/ai/V11__provider_spend_envelope.sql" "$WORK/V11.orig"
rm "$WORK/sql/ai/V11__provider_spend_envelope.sql"
flyway ai validate > "$WORK/fly-missing.txt" 2>&1
if [ $? -ne 0 ]; then ok 'missing applied migration (V11) fails validate'; else bad 'validate accepted a missing applied migration'; fi
mv "$WORK/V11.orig" "$WORK/sql/ai/V11__provider_spend_envelope.sql"

echo "### 10. broken migration: non-zero exit and nothing half-applied"
cat > "$WORK/sql/ai/V99__ci_broken.sql" <<'SQL'
CREATE TABLE ai.t_partial_probe (id int);
CREATE TABLE ai.t_bad_probe (id totally_missing_type_dbci);
SQL
flyway ai migrate > "$WORK/fly-broken.txt" 2>&1; RC=$?
[ $RC -ne 0 ] && ok "broken AI migration exits non-zero (rc=$RC)" || bad 'broken AI migration returned 0'
assert_eq 'no failed row recorded' 0 "$(q "select count(*) from ai.flyway_schema_history_ai where not success")"
assert_eq 'no partial objects' 0 "$(q "select count(*) from information_schema.tables where table_schema='ai' and table_name in ('t_partial_probe','t_bad_probe')")"
rm -f "$WORK/sql/ai/V99__ci_broken.sql"
assert_eq 'removing the bad file is enough (no repair)' 0 "$(flyway ai migrate > "$WORK/fly-broken2.txt" 2>&1; echo $?)"

echo "### 11. the silent success cases must not pass"
q "CREATE DATABASE ci_ai_wrong" >/dev/null 2>&1
PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
  psql "host=$DB_HOST port=$DB_PORT dbname=ci_ai_wrong user=postgres" -X -q -c "CREATE SCHEMA ai AUTHORIZATION postgres" >/dev/null 2>&1
OUT=$(docker run --rm --net host -e FLYWAY_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/ci_ai_wrong?currentSchema=ai,extensions" \
  -e FLYWAY_USER=migrate_ai -e FLYWAY_PASSWORD="$AI_MIGRATE_PW" -e FLYWAY_SCHEMAS=ai -e FLYWAY_DEFAULT_SCHEMA=ai \
  -e FLYWAY_TABLE=flyway_schema_history_ai -e FLYWAY_LOCATIONS="filesystem:/flyway/sql/ai" -e FLYWAY_VALIDATE_MIGRATION_NAMING=true \
  -v "$WORK/sql:/flyway/sql:ro" $FLYWAY_IMAGE migrate 2>&1); RC=$?
if [ $RC -ne 0 ] || echo "$OUT" | grep -qiE 'permission denied|ERROR'; then ok 'wrong schema owner refused at migrate time'; else bad 'migrate ran against a foreign-owned schema'; fi
# zero-migration location: Flyway exits 0 claiming up to date - the applied-count guard rejects it
q "CREATE DATABASE ci_ai_scratch" >/dev/null 2>&1
PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
  psql "host=$DB_HOST port=$DB_PORT dbname=ci_ai_scratch user=postgres" -X -q \
  -c "CREATE SCHEMA ai AUTHORIZATION migrate_ai; CREATE SCHEMA extensions" >/dev/null 2>&1
mkdir -p "$WORK/empty"
OUT=$(docker run --rm --net host -e FLYWAY_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/ci_ai_scratch?currentSchema=ai,extensions" \
  -e FLYWAY_USER=migrate_ai -e FLYWAY_PASSWORD="$AI_MIGRATE_PW" -e FLYWAY_SCHEMAS=ai -e FLYWAY_DEFAULT_SCHEMA=ai \
  -e FLYWAY_TABLE=flyway_schema_history_ai -e FLYWAY_LOCATIONS="filesystem:/flyway/empty" -e FLYWAY_VALIDATE_MIGRATION_NAMING=true \
  -v "$WORK/empty:/flyway/empty:ro" $FLYWAY_IMAGE migrate 2>&1); RC=$?
assert_eq 'Flyway exits 0 with zero migration files (documented hazard)' 0 "$RC"
echo "$OUT" | grep -q 'Successfully applied 12 migrations' && bad 'zero-location run passed the applied guard' || ok 'applied guard rejects a zero-migration run'
assert_eq 'no versions recorded in the scratch run' 0 "$(PGPASSWORD=$PG_SUPER docker run --rm -i --net host -e PGPASSWORD "${PSQL_VOLS[@]}" $PG_IMAGE \
  psql "host=$DB_HOST port=$DB_PORT dbname=ci_ai_scratch user=postgres" -X -q -tAc \
  "select count(*) from ai.flyway_schema_history_ai where version is not null" | tr -d '[:space:]')"
printf 'SELECT 1;\n' > "$WORK/sql/ai/late-hotfix.sql"
flyway ai migrate > "$WORK/fly-misnamed.txt" 2>&1
if [ $? -ne 0 ] && grep -qi 'Invalid SQL filenames' "$WORK/fly-misnamed.txt"; then ok 'misnamed migration file fails the job'; else bad 'misnamed file was not rejected'; fi
rm -f "$WORK/sql/ai/late-hotfix.sql"

echo "### 12. summary"
[[ "${KEEP_WORK:-0}" = 1 ]] && echo "  work dir kept for inspection: $WORK"
echo
if [ "$FAILS" -eq 0 ]; then echo "COMBINED MIGRATION CHECK PASSED (platform 6 / AI 12)"; exit 0; fi
echo "COMBINED MIGRATION CHECK FAILED ($FAILS assertion(s))"; exit 1
