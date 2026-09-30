#!/usr/bin/env bash
# Rebuild both domains in a private, disposable cluster. Never target an existing DB.
# Run this file by path: docker exec -i must not consume a bash -s program's stdin.
set -euo pipefail
umask 077
ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
WORK=${WORK:-$(mktemp -d /tmp/db3-check.XXXXXX)}
PG_IMAGE=${PG_IMAGE:-pgvector/pgvector@sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b}
FLYWAY_IMAGE=${FLYWAY_IMAGE:-flyway/flyway@sha256:94a81ca7db9a9f24fd8acd7463fa4560cb8f66aae2aa64485c27eb296f5851cf}
CONTAINER=${CONTAINER:-db3-check-$$}
DB_HOST=${DB_HOST:-127.0.0.1}
DB_BIND_HOST=${DB_BIND_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-15433}
DB_NAME=db3_validation
PASS=0
ok() { PASS=$((PASS+1)); echo "[ok] $1"; }
eq() { [ "$2" = "$3" ] || { echo "[FAIL] $1 expected=[$2] actual=[$3]"; exit 1; }; ok "$1"; }
reject() {
  local label=$1; shift
  if "$@" > "$WORK/rejected.log" 2>&1; then echo "[FAIL] $label unexpectedly succeeded"; exit 1; fi
  grep -Eq 'ERROR|Exception|exception|permission denied|checksum mismatch|Invalid SQL filenames' "$WORK/rejected.log" || { cat "$WORK/rejected.log"; exit 1; }
  ok "$label"
}
for tool in docker openssl sha256sum; do command -v "$tool" >/dev/null; done
mkdir -p "$WORK"/{ai,platform,bootstrap,empty}
cp "$ROOT"/services/ai/resources/database/postgres/migrations/V*.sql "$WORK/ai/"
cp "$ROOT"/services/platform/docs/script/sql/postgres/V*.sql "$WORK/platform/"
cp "$ROOT"/services/platform/docs/script/sql/postgres/bootstrap/*.sql "$WORK/bootstrap/"
cp "$ROOT"/services/ai/resources/database/postgres/bootstrap/*.sql "$WORK/bootstrap/"
# No credential file, no password argv, no shell tracing. Export names to Docker only.
export POSTGRES_PASSWORD=$(openssl rand -hex 20) POSTGRES_DB=$DB_NAME
export PLATFORM_MIGRATE_PASSWORD=$(openssl rand -hex 20) PLATFORM_APP_PASSWORD=$(openssl rand -hex 20)
export AI_MIGRATE_PASSWORD=$(openssl rand -hex 20) AI_APP_PASSWORD=$(openssl rand -hex 20)
docker run -d --name "$CONTAINER" --memory "${PG_MEM:-768m}" --cpus "${PG_CPUS:-1}" \
  -p "$DB_BIND_HOST:$DB_PORT:5432" -e POSTGRES_PASSWORD -e POSTGRES_DB \
  -e PLATFORM_MIGRATE_PASSWORD -e PLATFORM_APP_PASSWORD -e AI_MIGRATE_PASSWORD -e AI_APP_PASSWORD \
  -v "$WORK:/check:ro" "$PG_IMAGE" >/dev/null
cleanup() {
  docker rm -f "$CONTAINER-kill" >/dev/null 2>&1 || true
  if [ "${KEEP_CONTAINER:-0}" != 1 ] || [ "${COMPLETE:-0}" != 1 ]; then docker rm -fv "$CONTAINER" >/dev/null 2>&1 || true; fi
  if [ "${KEEP_WORK:-0}" != 1 ]; then rm -rf -- "$WORK"; fi
}
trap cleanup EXIT
ready=0
for n in $(seq 1 90); do
  if docker exec "$CONTAINER" pg_isready -h 127.0.0.1 -U postgres -d "$DB_NAME" >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
eq 'TCP database ready' 1 "$ready"
psql_as() {
  local role=$1 password; shift
  case "$role" in
    postgres) password=$POSTGRES_PASSWORD;; migrate_ai) password=$AI_MIGRATE_PASSWORD;;
    ai_app) password=$AI_APP_PASSWORD;; migrate_platform) password=$PLATFORM_MIGRATE_PASSWORD;;
    platform_app) password=$PLATFORM_APP_PASSWORD;; *) return 2;;
  esac
  PGPASSWORD=$password docker exec -i -e PGPASSWORD -e PGOPTIONS "$CONTAINER" \
    psql -h 127.0.0.1 -U "$role" -d "${TARGET_DB:-$DB_NAME}" -X -q -v ON_ERROR_STOP=1 "$@"
}
q() { psql_as postgres -tAc "$1"; }
bootstrap() { psql_as postgres -1 -f "/check/bootstrap/$1"; }
flyway() {
  local domain=$1; shift
  export FLYWAY_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/${TARGET_DB:-$DB_NAME}?currentSchema=$domain,extensions"
  export FLYWAY_USER="migrate_$domain" FLYWAY_PASSWORD
  if [ "$domain" = ai ]; then FLYWAY_PASSWORD=$AI_MIGRATE_PASSWORD; else FLYWAY_PASSWORD=$PLATFORM_MIGRATE_PASSWORD; fi
  export FLYWAY_SCHEMAS=$domain FLYWAY_DEFAULT_SCHEMA=$domain FLYWAY_TABLE="flyway_schema_history_$domain"
  export FLYWAY_LOCATIONS="filesystem:/flyway/${LOCATION:-$domain}" FLYWAY_VALIDATE_MIGRATION_NAMING=true
  docker run --rm --network host ${FW_NAME:+--name "$FW_NAME"} \
    -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD -e FLYWAY_SCHEMAS -e FLYWAY_DEFAULT_SCHEMA \
    -e FLYWAY_TABLE -e FLYWAY_LOCATIONS -e FLYWAY_VALIDATE_MIGRATION_NAMING \
    -v "$WORK/ai:/flyway/ai:ro" -v "$WORK/platform:/flyway/platform:ro" \
    -v "$WORK/empty:/flyway/empty:ro" "$FLYWAY_IMAGE" "$@"
}
first_ai() { grep -q 'Successfully applied 2 migrations' "$1"; }
platform_fingerprint() {
  PGPASSWORD=$POSTGRES_PASSWORD docker exec -e PGPASSWORD "$CONTAINER" \
    pg_dump -h 127.0.0.1 -U postgres -d "$DB_NAME" -n platform \
    | sed '/^\\restrict /d; /^\\unrestrict /d' | sha256sum | cut -d' ' -f1
}
bootstrap 01-platform-casts.sql > "$WORK/bootstrap-platform-extension.log" 2>&1
bootstrap 00-platform-identity.sql > "$WORK/bootstrap-platform.log" 2>&1
flyway platform migrate > "$WORK/platform-first.log" 2>&1
grep -q 'Successfully applied 3 migrations' "$WORK/platform-first.log"
ok 'platform applied exactly three migrations'
platform_before=$(platform_fingerprint)
bootstrap 00-ai-identity.sql > "$WORK/bootstrap-ai.log" 2>&1
bootstrap 00-ai-identity.sql > "$WORK/bootstrap-ai-repeat.log" 2>&1
ok 'AI bootstrap idempotent'
AI_MIGRATE_PASSWORD= AI_APP_PASSWORD= PGPASSWORD="$POSTGRES_PASSWORD" reject 'empty AI passwords refused' \
  docker exec -i -e PGPASSWORD -e AI_MIGRATE_PASSWORD -e AI_APP_PASSWORD \
  "$CONTAINER" psql -h 127.0.0.1 -U postgres -d "$DB_NAME" -X -v ON_ERROR_STOP=1 -1 -f /check/bootstrap/00-ai-identity.sql
reject 'existing low-privilege role cannot bootstrap' psql_as migrate_ai -1 -f /check/bootstrap/00-ai-identity.sql
q 'CREATE DATABASE db3_wrong_owner' >/dev/null
TARGET_DB=db3_wrong_owner psql_as postgres -c 'CREATE SCHEMA ai AUTHORIZATION postgres' >/dev/null
TARGET_DB=db3_wrong_owner reject 'wrong existing schema owner refused' psql_as postgres -1 -f /check/bootstrap/00-ai-identity.sql
eq 'wrong owner preserved' postgres "$(TARGET_DB=db3_wrong_owner psql_as postgres -tAc "SELECT nspowner::regrole FROM pg_namespace WHERE nspname='ai'")"
flyway ai migrate > "$WORK/ai-first.log" 2>&1
first_ai "$WORK/ai-first.log" || { cat "$WORK/ai-first.log"; exit 1; }
ok 'AI applied exactly two migrations'
eq 'AI history versions' 2 "$(q "SELECT count(*) FROM ai.flyway_schema_history_ai WHERE version IN ('1','2') AND success")"
eq '32 AI business tables' 32 "$(q "SELECT count(*) FROM pg_tables WHERE schemaname='ai' AND tablename LIKE 't_%'")"
eq '86 baseline indexes' 86 "$(q "SELECT count(*) FROM pg_indexes WHERE schemaname='ai' AND tablename LIKE 't_%'")"
eq 'all AI tables owned by migration identity' 0 "$(q "SELECT count(*) FROM pg_tables WHERE schemaname='ai' AND tableowner!='migrate_ai'")"
eq 'vector dimension' 1536 "$(q "SELECT atttypmod FROM pg_attribute WHERE attrelid='ai.t_knowledge_vector'::regclass AND attname='embedding'")"
eq 'static profiles/prompts and no user seed' '1|10|0' "$(psql_as ai_app -tAc 'SELECT (SELECT count(*) FROM t_agent_profile),(SELECT count(*) FROM t_agent_prompt),(SELECT count(*) FROM t_user)')"
eq 'default schema and extension' 'ai|0.8.6' "$(psql_as ai_app -tAc "SELECT current_schema(),extversion FROM pg_extension WHERE extname='vector'")"
flyway ai migrate > "$WORK/ai-repeat.log" 2>&1
grep -q 'Schema "ai" is up to date' "$WORK/ai-repeat.log"
flyway ai validate > "$WORK/ai-validate.log" 2>&1
ok 'AI second migration and validation pass'
eq 'platform unchanged by AI migration' "$platform_before" "$(platform_fingerprint)"

# All mutation files are copies in WORK, never repository baselines.
cp "$WORK/ai/V1__ai_baseline.sql" "$WORK/V1.orig"
printf '\n-- checksum mutation\n' >> "$WORK/ai/V1__ai_baseline.sql"
reject 'checksum mutation fails validate' flyway ai validate
reject 'checksum mutation fails migrate' flyway ai migrate
mv "$WORK/V1.orig" "$WORK/ai/V1__ai_baseline.sql"
flyway ai validate > "$WORK/ai-restored.log" 2>&1
cat > "$WORK/ai/V3__broken.sql" <<'SQL'
CREATE TABLE ai.db3_partial (id int);
CREATE TABLE ai.db3_broken (id db3_missing_type);
SQL
reject 'second-statement error fails whole migration' flyway ai migrate
eq 'no partial DDL' 0 "$(q "SELECT count(*) FROM pg_tables WHERE schemaname='ai' AND tablename IN ('db3_partial','db3_broken')")"
eq 'no failed history row' 0 "$(q 'SELECT count(*) FROM ai.flyway_schema_history_ai WHERE NOT success')"
rm "$WORK/ai/V3__broken.sql"
cat > "$WORK/ai/V3__interrupted.sql" <<'SQL'
CREATE TABLE ai.db3_interrupted (id int);
SELECT pg_sleep(120);
SQL
FW_NAME="$CONTAINER-kill" flyway ai migrate > "$WORK/ai-kill.log" 2>&1 & fw_pid=$!
sleeping=0
for n in $(seq 1 60); do
  if [ "$(q "SELECT count(*) FROM pg_stat_activity WHERE usename='migrate_ai' AND state='active' AND query LIKE '%pg_sleep(120)%'")" = 1 ]; then sleeping=1; break; fi
  sleep 1
done
eq 'kill hits an executing migration' 1 "$sleeping"
docker kill "$CONTAINER-kill" >/dev/null
if wait "$fw_pid"; then echo '[FAIL] killed Flyway exited zero'; exit 1; fi
ok 'killed Flyway exits nonzero'
for n in $(seq 1 30); do
  [ "$(q "SELECT count(*) FROM pg_stat_activity WHERE usename='migrate_ai' AND state='active'")" = 0 ] && break
  sleep 1
done
eq 'kill rolls back DDL' '' "$(q "SELECT to_regclass('ai.db3_interrupted')")"
eq 'kill records no failed history' 0 "$(q 'SELECT count(*) FROM ai.flyway_schema_history_ai WHERE NOT success')"
rm "$WORK/ai/V3__interrupted.sql"
flyway ai migrate > "$WORK/ai-after-kill.log" 2>&1
cp "$WORK/ai/V1__ai_baseline.sql" "$WORK/V1.orig"
rm "$WORK/ai/V1__ai_baseline.sql"
reject 'missing V1 rejected' flyway ai validate
mv "$WORK/V1.orig" "$WORK/ai/V1__ai_baseline.sql"
printf 'SELECT 1;\n' > "$WORK/ai/late-hotfix.sql"
reject 'invalid migration filename rejected' flyway ai migrate
rm "$WORK/ai/late-hotfix.sql"
q 'CREATE DATABASE db3_zero' >/dev/null
TARGET_DB=db3_zero psql_as postgres -c 'CREATE SCHEMA ai AUTHORIZATION migrate_ai; CREATE SCHEMA extensions; CREATE EXTENSION vector SCHEMA extensions' >/dev/null
TARGET_DB=db3_zero LOCATION=empty flyway ai migrate > "$WORK/ai-zero.log" 2>&1
if first_ai "$WORK/ai-zero.log"; then echo '[FAIL] empty location passed applied guard'; exit 1; fi
eq 'zero-location object guard rejects' 0 "$(TARGET_DB=db3_zero psql_as postgres -tAc "SELECT count(*) FROM pg_tables WHERE schemaname='ai' AND tablename LIKE 't_%'")"
ok 'Flyway zero-exit cannot pass applied guard'
eq 'platform unchanged by failure drills' "$platform_before" "$(platform_fingerprint)"

# DML/sequence fixtures have no authenticating password or external business data.
psql_as migrate_ai -c 'CREATE TABLE ai.db3_canary (id bigserial PRIMARY KEY, value text)' >/dev/null
psql_as migrate_platform -c 'CREATE TABLE platform.db3_canary (id bigserial PRIMARY KEY, value text)' >/dev/null
for role in ai_app platform_app; do
  domain=${role%_app}
  eq "$role own CRUD and default sequence ACL" 'restored' "$(psql_as "$role" -tAc "INSERT INTO db3_canary(value) VALUES ('synthetic'); UPDATE db3_canary SET value='restored'; SELECT value FROM db3_canary; DELETE FROM db3_canary; INSERT INTO db3_canary(value) VALUES ('backup-canary')" | head -1)"
  eq "$role public USAGE/CREATE denied" 'f|f' "$(q "SELECT has_schema_privilege('$role','public','USAGE'),has_schema_privilege('$role','public','CREATE')")"
  eq "$role database CREATE denied" f "$(q "SELECT has_database_privilege('$role',current_database(),'CREATE')")"
  reject "$role cannot own-domain DDL" psql_as "$role" -c "CREATE TABLE $domain.forbidden(id int)"
  reject "$role cannot become migration owner" psql_as "$role" -c "SET ROLE migrate_$domain"
done
q "CREATE TABLE public.t_user (id text); CREATE TABLE public.sys_user(id text); INSERT INTO public.t_user VALUES ('decoy')" > /dev/null
for role in ai_app platform_app; do
  if [ "$role" = ai_app ]; then other=platform; table=sys_user; else other=ai; table=t_user; fi
  if [ "$table" = sys_user ]; then column=user_id; else column=id; fi
  for sql in "SELECT * FROM $other.$table" "INSERT INTO $other.$table DEFAULT VALUES" "UPDATE $other.$table SET $column=NULL" "DELETE FROM $other.$table" "CREATE TABLE $other.forbidden(id int)"; do
    reject "$role cross-domain ${sql%% *} denied" psql_as "$role" -c "$sql"
    grep -qi 'permission denied' "$WORK/rejected.log"
  done
  reject "$role public same-name decoy denied" psql_as "$role" -c "SELECT * FROM public.$table"
done
PGOPTIONS='-c search_path=ai' reject 'ai-only path cannot resolve vector' psql_as ai_app -c "SELECT '[1]'::vector"
for path in public nonexistent platform; do
  PGOPTIONS="-c search_path=$path" reject "wrong AI path $path fails operation" psql_as ai_app -c 'SELECT count(*) FROM t_user'
done
psql_as ai_app -c "INSERT INTO t_knowledge_vector(id,collection_name,embedding,metadata) VALUES ('db3-backup-vector','db3-synthetic',array_fill(1::real,ARRAY[1536])::vector,'{\"source\":\"synthetic\"}')" >/dev/null

# Restore on a fresh DB with the same roles, no passwords in role dumps or data.
PGPASSWORD=$POSTGRES_PASSWORD docker exec -e PGPASSWORD "$CONTAINER" \
  pg_dumpall -h 127.0.0.1 -U postgres --globals-only --no-role-passwords > "$WORK/globals.sql"
if grep -qi 'PASSWORD' "$WORK/globals.sql"; then echo '[FAIL] role dump contains passwords'; exit 1; fi
ok 'global role dump omits passwords'
for domain in ai platform; do
  PGPASSWORD=$POSTGRES_PASSWORD docker exec -e PGPASSWORD "$CONTAINER" \
    pg_dump -h 127.0.0.1 -U postgres -d "$DB_NAME" -n "$domain" -Fc > "$WORK/$domain.dump"
  docker cp "$WORK/$domain.dump" "$CONTAINER:/tmp/$domain.dump" >/dev/null
  docker exec "$CONTAINER" pg_restore -l "/tmp/$domain.dump" | sed "/ SCHEMA - $domain /d" > "$WORK/$domain.list"
  docker cp "$WORK/$domain.list" "$CONTAINER:/tmp/$domain.list" >/dev/null
done
q 'CREATE DATABASE db3_restored' >/dev/null
TARGET_DB=db3_restored bootstrap 01-platform-casts.sql > "$WORK/restored-extension.log" 2>&1
TARGET_DB=db3_restored bootstrap 00-platform-identity.sql > "$WORK/restored-platform-bootstrap.log" 2>&1
TARGET_DB=db3_restored bootstrap 00-ai-identity.sql > "$WORK/restored-ai-bootstrap.log" 2>&1
for domain in ai platform; do
  PGPASSWORD=$POSTGRES_PASSWORD docker exec -e PGPASSWORD "$CONTAINER" \
    pg_restore -h 127.0.0.1 -U postgres -d db3_restored --role="migrate_$domain" --no-owner \
    --exit-on-error -L "/tmp/$domain.list" "/tmp/$domain.dump" > "$WORK/$domain-restore.log" 2>&1
  eq "$domain restored owners" 0 "$(TARGET_DB=db3_restored psql_as postgres -tAc "SELECT count(*) FROM pg_tables WHERE schemaname='$domain' AND tableowner!='migrate_$domain'")"
  eq "$domain restored schema owner" "migrate_$domain" "$(TARGET_DB=db3_restored psql_as postgres -tAc "SELECT nspowner::regrole FROM pg_namespace WHERE nspname='$domain'")"
  eq "$domain restored canary and ACL" backup-canary "$(TARGET_DB=db3_restored psql_as "${domain}_app" -tAc 'SELECT value FROM db3_canary')"
  TARGET_DB=db3_restored psql_as "migrate_$domain" -c "CREATE TABLE $domain.db3_default_acl(id bigserial, value text)" >/dev/null
  TARGET_DB=db3_restored psql_as "${domain}_app" -c "INSERT INTO db3_default_acl(value) VALUES ('default-acl'); DELETE FROM db3_default_acl" >/dev/null
  ok "$domain restored default table and sequence ACL"
done
eq 'restored AI business tables/indexes/dimension' '32|86|1536' "$(TARGET_DB=db3_restored psql_as postgres -tAc "SELECT (SELECT count(*) FROM pg_tables WHERE schemaname='ai' AND tablename LIKE 't_%'),(SELECT count(*) FROM pg_indexes WHERE schemaname='ai' AND tablename LIKE 't_%'),(SELECT atttypmod FROM pg_attribute WHERE attrelid='ai.t_knowledge_vector'::regclass AND attname='embedding')")"
eq 'restored platform business tables' 32 "$(TARGET_DB=db3_restored psql_as postgres -tAc "SELECT count(*) FROM pg_tables WHERE schemaname='platform' AND tablename NOT LIKE 'db3_%' AND tablename!='flyway_schema_history_platform'")"
eq 'restored platform static seeds' '203|5|220|74|19|11|1|3' "$(TARGET_DB=db3_restored psql_as platform_app -tAc 'SELECT (SELECT count(*) FROM sys_menu),(SELECT count(*) FROM sys_role),(SELECT count(*) FROM sys_role_menu),(SELECT count(*) FROM sys_dict_data),(SELECT count(*) FROM sys_config),(SELECT count(*) FROM sys_dept),(SELECT count(*) FROM sys_tenant),(SELECT count(*) FROM sys_client)')"
TARGET_DB=db3_restored flyway ai validate > "$WORK/ai-restored-validate.log" 2>&1
TARGET_DB=db3_restored flyway platform validate > "$WORK/platform-restored-validate.log" 2>&1
ok 'both restored migration histories validate against original files'
eq 'restored AI versions and seeds' '2|1|10' "$(TARGET_DB=db3_restored psql_as ai_app -tAc "SELECT (SELECT count(*) FROM flyway_schema_history_ai WHERE version IN ('1','2') AND success),(SELECT count(*) FROM t_agent_profile),(SELECT count(*) FROM t_agent_prompt)")"
eq 'restored platform versions' 3 "$(TARGET_DB=db3_restored psql_as platform_app -tAc "SELECT count(*) FROM flyway_schema_history_platform WHERE version IN ('1','2','3') AND success")"
eq 'restored vector similarity' db3-backup-vector "$(TARGET_DB=db3_restored psql_as ai_app -tAc "SELECT id FROM t_knowledge_vector ORDER BY embedding <=> array_fill(1::real,ARRAY[1536])::vector LIMIT 1")"
TARGET_DB=db3_restored reject 'restored AI cannot read platform' psql_as ai_app -c 'SELECT * FROM platform.sys_user'
TARGET_DB=db3_restored reject 'restored platform cannot read AI' psql_as platform_app -c 'SELECT * FROM ai.t_user'
COMPLETE=1
echo "AI DOMAIN CHECK PASSED ($PASS assertions); credentials remain only in process/container environment"
if [ "${KEEP_CONTAINER:-0}" = 1 ]; then echo "retained private lab: $CONTAINER at $DB_HOST:$DB_PORT/$DB_NAME"; fi
