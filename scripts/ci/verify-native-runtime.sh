#!/usr/bin/env bash
# verify-native-runtime.sh - run the P2/P3 native failure matrix against the real
# product Boot JARs on a throwaway, owner-tagged PostgreSQL+Redis pair.
#
# Why this exists: the original atomic-admission / worker-fence / SSE-replay evidence
# was produced by dedicated acceptance runs. Without a repeatable script those chains
# are only exercised when someone reruns the private harness. This script re-runs the
# first required batch of that matrix on one Linux host (CI runner or lab VM):
#   N1  atomic admission: same-key same-body replay, same-key different-body 409,
#       unauthenticated 401 - with SQL assertions on run/reservation/outbox counts
#   N2  worker takeover: the executing AI node is killed mid-run, the lease expires,
#       another node claims attempt+1 and completes - exactly one terminal, no event
#       sequence gaps, one reservation
#   N3  SSE continuity across instances: frames 1..k replayed+live from the first
#       gateway instance, then afterSeq=k from a second instance whose AI node took
#       over; the seq set must equal ai_run_event exactly and end with the terminal
#   N4  two-tenant private PDF / citation / revocation chain - REQUIRES a MinerU
#       instance (RUN_INGEST_CHAIN=1 + MINERU_BASE_URL/MINERU_TOKEN); without it the
#       group is explicitly NOT_RUN, never counted as a pass
#   N5  agent sandbox ticket write + operationKey replay - REQUIRES
#       RUN_AGENT_SANDBOX=1; without it explicitly NOT_RUN
#
# Model boundaries: embedding and chat are explicit synthetic providers with egress
# allowed only for "synthetic" (no billed calls); run/Worker/outbox/SSE persistence is
# the real product chain from the given Boot JARs. Everything is owner-tagged
# (io.ragent.owner=$OWNER); a name or port collision refuses to run instead of
# reusing or deleting anything. Teardown only kills JVMs whose cmdline still points
# into this run's WORK and only removes containers carrying this run's owner label.
set -u

die() { echo "FATAL: $*" >&2; exit 2; }
for tool in docker openssl curl unzip javac java ss; do command -v "$tool" >/dev/null || die "missing required tool: $tool"; done

OWNER=${OWNER:?set OWNER to a unique run tag}
WORK=${WORK:-/tmp/native-runtime-$OWNER}
case "$WORK" in *"$OWNER"*) ;; *) die "WORK ($WORK) must contain OWNER ($OWNER) so owned JVMs are identifiable";; esac
REPO_ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
EVIDENCE=${EVIDENCE:-$REPO_ROOT/target/native-runtime-evidence/$OWNER}
AI_JAR=${AI_JAR:?path to the AI boot jar}
PLATFORM_JAR=${PLATFORM_JAR:?path to the platform boot jar}
[ -f "$AI_JAR" ] || die "AI_JAR not found: $AI_JAR"
[ -f "$PLATFORM_JAR" ] || die "PLATFORM_JAR not found: $PLATFORM_JAR"

PG_IMAGE=${PG_IMAGE:-pgvector/pgvector@sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b}
REDIS_IMAGE=${REDIS_IMAGE:-redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499}
DB_NAME=${DB_NAME:-nrt_$OWNER}
DB_PORT=${DB_PORT:?set DB_PORT}
REDIS_PORT=${REDIS_PORT:?set REDIS_PORT}
PLATFORM_PORT=${PLATFORM_PORT:?set PLATFORM_PORT}
PLATFORM2_PORT=${PLATFORM2_PORT:?set PLATFORM2_PORT}
AI_PORT=${AI_PORT:?set AI_PORT}
AI2_PORT=${AI2_PORT:?set AI2_PORT}
PG_CONTAINER=nrt-pg-$OWNER
REDIS_CONTAINER=nrt-redis-$OWNER
for name in "$PG_CONTAINER" "$REDIS_CONTAINER"; do
  docker inspect "$name" >/dev/null 2>&1 && die "container $name already exists (owner-tag collision); refusing reuse"
done
for port in "$DB_PORT" "$REDIS_PORT" "$PLATFORM_PORT" "$PLATFORM2_PORT" "$AI_PORT" "$AI2_PORT"; do
  ss -ltn 2>/dev/null | grep -q ":$port " && die "port $port already held; refusing"
done

PG_SUPER=$(openssl rand -hex 16); REDIS_PASS=$(openssl rand -hex 16)
SERVICE_CREDENTIAL=$(openssl rand -hex 24)
FIXTURE_PASSWORD="NrtSynth-$(openssl rand -hex 10)"
mkdir -p "$WORK"/{objects,keys,bcrypt} "$EVIDENCE"
umask 077
PASS=0; FAIL=0
PIDS=""
note() { echo "[native] $*"; }
ok() { echo "  [ok]   $1"; PASS=$((PASS+1)); printf '{"case":"%s","pass":true,"detail":"%s"}\n' "$1" "$2" >> "$EVIDENCE/results.jsonl"; }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); printf '{"case":"%s","pass":false,"detail":"%s"}\n' "$1" "$2" >> "$EVIDENCE/results.jsonl"; }
not_run() { echo "  [NOT_RUN] $1 ($2)"; printf '{"case":"%s","pass":null,"reason":"%s"}\n' "$1" "$2" >> "$EVIDENCE/results.jsonl"; }
jsonstr() { grep -oE "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]+\"" "$1" 2>/dev/null | head -1 | sed -E "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"([^\"]+)\".*/\1/"; }
cleanup() {
  for pid in $PIDS; do
    if [ -r "/proc/$pid/cmdline" ] && tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q "$OWNER"; then
      kill -9 "$pid" 2>/dev/null || true
    fi
  done
  for c in "$PG_CONTAINER" "$REDIS_CONTAINER"; do
    if [ "$(docker inspect --format '{{index .Config.Labels "io.ragent.owner"}}' "$c" 2>/dev/null)" = "$OWNER" ] && [ "${NATIVE_KEEP_PG:-0}" != 1 ]; then
      docker rm -f "$c" >/dev/null 2>&1 || true
    fi
  done
  [ "${KEEP_WORK:-0}" = 1 ] || rm -rf "$WORK"
}
trap cleanup EXIT
psql() { docker exec -i -e PGPASSWORD="$PG_SUPER" "$PG_CONTAINER" psql -h 127.0.0.1 -U postgres -d "$DB_NAME" -X -q -v ON_ERROR_STOP=1 "$@"; }
sqlq() { docker exec -e PGPASSWORD="$PG_SUPER" "$PG_CONTAINER" psql -h 127.0.0.1 -U postgres -d "$DB_NAME" -X -q -tAc "$1"; }
sqlq_as() { docker exec -e PGPASSWORD="$3" "$PG_CONTAINER" psql -h 127.0.0.1 -U "$2" -d "$DB_NAME" -X -q -tAc "$1" 2>&1 || true; }
http() { local method=$1 url=$2 log=$3; shift 3
  curl -sS -o "$EVIDENCE/$log.body" -w '%{http_code}' -X "$method" "$@" "$url" > "$EVIDENCE/$log.code" 2>"$EVIDENCE/$log.err"; }
codeof() { cat "$EVIDENCE/$1.code" 2>/dev/null; }

note "### 1. owned PostgreSQL + Redis (digest-pinned, owner-labeled)"
docker run -d --name "$PG_CONTAINER" --label "io.ragent.owner=$OWNER" --cpus "${PG_CPUS:-1}" --memory "${PG_MEM:-768m}" \
  -p 127.0.0.1:$DB_PORT:5432 -e POSTGRES_PASSWORD="$PG_SUPER" -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER=postgres "$PG_IMAGE" >/dev/null || die 'pg failed to start'
docker run -d --name "$REDIS_CONTAINER" --label "io.ragent.owner=$OWNER" --memory 128m \
  -p 127.0.0.1:$REDIS_PORT:6379 "$REDIS_IMAGE" redis-server --requirepass "$REDIS_PASS" >/dev/null || die 'redis failed to start'
READY=0; STREAK=0
for i in $(seq 1 90); do
  if docker exec "$PG_CONTAINER" pg_isready -U postgres -d "$DB_NAME" >/dev/null 2>&1; then
    STREAK=$((STREAK+1))
    # initdb briefly serves a temporary instance and restarts; require a stable window
    [ "$STREAK" -ge 3 ] && { READY=1; break; }
  else
    STREAK=0
  fi
  sleep 1
done
[ "$READY" = 1 ] || { docker logs "$PG_CONTAINER" | tail -20; die 'pg never became ready'; }
ok 'ENV-owned-containers' "pg=$PG_CONTAINER redis=$REDIS_CONTAINER owner=$OWNER"

note "### 2. runtime roles, byte-identical migrations, cross-schema isolation"
docker exec "$PG_CONTAINER" psql -h 127.0.0.1 -U postgres -d "$DB_NAME" -X -q -c "CREATE ROLE nrtplatform LOGIN PASSWORD '$PG_SUPER'; CREATE ROLE nrtapp LOGIN PASSWORD '$PG_SUPER'" || die 'roles'
psql -c "CREATE SCHEMA IF NOT EXISTS platform; CREATE SCHEMA IF NOT EXISTS ai; CREATE SCHEMA IF NOT EXISTS extensions; CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensions" >/dev/null || die 'schemas'
APPLIED=0; : > "$EVIDENCE/migrations-applied.txt"
apply_dir() { local dir=$1 schema=$2
  for f in $(ls "$dir"/V*.sql | sort -t V -k2 -n); do
    { echo "CREATE SCHEMA IF NOT EXISTS $schema; CREATE SCHEMA IF NOT EXISTS extensions;"
      echo "SET search_path TO $schema,extensions;"; cat "$f"; } | docker exec -i "$PG_CONTAINER" psql -h 127.0.0.1 -U postgres -d "$DB_NAME" -X -q -v ON_ERROR_STOP=1 2>"$EVIDENCE/mig-err.txt" || { cat "$EVIDENCE/mig-err.txt"; die "migration failed: $schema/$(basename "$f")"; }
    echo "$schema/$(basename "$f")=$(sha256sum "$f" | cut -d' ' -f1)" >> "$EVIDENCE/migrations-applied.txt"
    APPLIED=$((APPLIED+1))
  done; }
apply_dir "$REPO_ROOT/services/platform/docs/script/sql/postgres" platform
apply_dir "$REPO_ROOT/services/ai/resources/database/postgres/migrations" ai
[ "$APPLIED" -eq 18 ] || die "expected 18 migrations (platform 6 + AI 12), applied $APPLIED"
ok 'ENV-migrations' "$APPLIED migrations applied byte-identical (platform 6 + AI 12)"
psql -c "GRANT USAGE ON SCHEMA platform TO nrtplatform; GRANT USAGE ON SCHEMA ai TO nrtapp; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA platform TO nrtplatform; GRANT USAGE,SELECT,UPDATE ON ALL SEQUENCES IN SCHEMA platform TO nrtplatform; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA ai TO nrtapp; GRANT USAGE,SELECT,UPDATE ON ALL SEQUENCES IN SCHEMA ai TO nrtapp; ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA platform GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO nrtplatform; ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA ai GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO nrtapp" >/dev/null || die 'grants'
X1=$(sqlq_as "SELECT count(*) FROM platform.sys_user" nrtapp "$PG_SUPER")
X2=$(sqlq_as "SELECT count(*) FROM ai.ai_run" nrtplatform "$PG_SUPER")
if echo "$X1" | grep -qi 'permission denied' && echo "$X2" | grep -qi 'permission denied'; then ok 'ENV-runtime-isolation' 'nrtapp cannot read platform; nrtplatform cannot read ai'; else fail 'ENV-runtime-isolation' "x1=$X1 x2=$X2"; fi

note "### 3. fixture tenants (BCrypt hash from the platform's own hutool)"
cd "$WORK/bcrypt"
unzip -o -q "$PLATFORM_JAR" 'BOOT-INF/lib/hutool*' -d . || die 'hutool extraction failed'
# BCrypt lives in hutool-crypto; put every hutool module on the classpath
HUTOOL=$(ls "$WORK"/bcrypt/BOOT-INF/lib/hutool*.jar | tr '\n' ':' | sed 's/:$//')
[ -n "$HUTOOL" ] || die 'hutool jars not found in platform jar'
cat > HashGen.java <<'EOF'
import cn.hutool.crypto.digest.BCrypt;
public class HashGen { public static void main(String[] a) { System.out.println(BCrypt.hashpw(a[0])); } }
EOF
javac -cp "$HUTOOL" HashGen.java || die 'bcrypt hashgen compile failed'
FIXTURE_HASH=$(java -cp "$HUTOOL:." HashGen "$FIXTURE_PASSWORD")
[ -n "$FIXTURE_HASH" ] || die 'bcrypt hash failed'
cat > "$WORK/fixtures.sql" <<SQL
INSERT INTO platform.sys_tenant_package (package_id, package_name, menu_ids, remark, menu_check_strictly, status, del_flag, create_dept, create_by, create_time, update_by, update_time)
SELECT 910000000000100901, 'nrt-package', COALESCE(string_agg(menu_id::text, ','), '0'), 'nrt synthetic package', true, '0', '0', 103, 1, now(), 1, now()
FROM platform.sys_menu WHERE perms LIKE 'ai:%';
INSERT INTO platform.sys_tenant (id, tenant_id, contact_user_name, contact_phone, company_name, package_id, account_count, status, del_flag)
VALUES (910000000000100011, 'nrtt1', 'nrt-admin-t1', '13900001011', 'nrt synthetic tenant 1', 910000000000100901, -1, '0', '0'),
       (910000000000100012, 'nrtt2', 'nrt-admin-t2', '13900001012', 'nrt synthetic tenant 2', 910000000000100901, -1, '0', '0');
INSERT INTO platform.sys_user (user_id, tenant_id, user_name, nick_name, password, status, del_flag)
VALUES (910000000000100001, 'nrtt1', 'nrtadmin', 'nrtadmin-t1', '$FIXTURE_HASH', '0', '0'),
       (910000000000100002, 'nrtt2', 'nrtadmin', 'nrtadmin-t2', '$FIXTURE_HASH', '0', '0');
INSERT INTO platform.sys_client (id, client_id, client_key, client_secret, grant_type, device_type, active_timeout, timeout, status, del_flag)
VALUES (910000000000100101, 'nrt-client', 'nrt-client-key', '', 'password', 'pc', 1800, 604800, '0', '0');
INSERT INTO platform.sys_role (role_id, tenant_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag)
VALUES (910000000000100021, 'nrtt1', 'nrt-role-t1', 'nrt_t1', 1, '1', true, true, '0', '0'),
       (910000000000100022, 'nrtt2', 'nrt-role-t2', 'nrt_t2', 1, '1', true, true, '0', '0');
INSERT INTO platform.sys_user_role (user_id, role_id) VALUES (910000000000100001, 910000000000100021), (910000000000100002, 910000000000100022);
INSERT INTO platform.sys_role_menu (role_id, menu_id) SELECT 910000000000100021, menu_id FROM platform.sys_menu WHERE perms LIKE 'ai:%';
INSERT INTO platform.sys_role_menu (role_id, menu_id) SELECT 910000000000100022, menu_id FROM platform.sys_menu WHERE perms LIKE 'ai:%';
INSERT INTO platform.sys_ai_policy_revision (tenant_id, version) VALUES ('nrtt1', 1), ('nrtt2', 1);
INSERT INTO ai.ai_acl_epoch (tenant_id, version) VALUES ('nrtt1', 1), ('nrtt2', 1);
SQL
psql < "$WORK/fixtures.sql" >/dev/null || die 'fixtures failed'
ok 'ENV-fixtures' 'two synthetic tenants seeded (package/users/client/roles/ai-menu/policy/acl)'

note "### 4. env placeholders, delegation keys, service config"
{
  echo "export AI_DB_PASSWORD='$PG_SUPER'"
  echo "export AI_DB_USER='nrtapp'"
  echo "export AI_DB_USERNAME='nrtapp'"
  echo "export PLATFORM_DB_PASSWORD='$PG_SUPER'"
  echo "export PLATFORM_DB_USERNAME='nrtplatform'"
  echo "export REDIS_PASSWORD='$REDIS_PASS'"
} > "$WORK/env.sh"
grep -rhoE '\$\{(PROJECT_SERVICES_[A-Z0-9_]+)\}' "$REPO_ROOT/services/platform" "$REPO_ROOT/services/ai" --include='*.yml' --include='*.yaml' 2>/dev/null \
  | sed -E 's/.*PROJECT_SERVICES_([A-Z0-9_]+).*/PROJECT_SERVICES_\1/' | sort -u > "$WORK/placeholders.txt"
PH_COUNT=0
while IFS= read -r name; do
  [ -n "$name" ] || continue
  echo "export $name='nrtsynth$(openssl rand -hex 8)'" >> "$WORK/env.sh"
  PH_COUNT=$((PH_COUNT+1))
done < "$WORK/placeholders.txt"
openssl genrsa -out "$WORK/keys/private-pkcs1.pem" 2048 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$WORK/keys/private-pkcs1.pem" -out "$WORK/keys/private.pem" 2>/dev/null
openssl rsa -in "$WORK/keys/private.pem" -pubout -out "$WORK/keys/public.pem" 2>/dev/null
rm -f "$WORK/keys/private-pkcs1.pem"
chmod 700 "$WORK/keys" "$WORK/env.sh"
ok 'ENV-config' "env.sh ($PH_COUNT placeholders) + delegation keypair generated (0600/0700)"

MINERU_FLAGS=""
if [ "${RUN_INGEST_CHAIN:-0}" = 1 ]; then
  [ -n "${MINERU_BASE_URL:-}" ] && [ -n "${MINERU_TOKEN:-}" ] || die 'RUN_INGEST_CHAIN=1 requires MINERU_BASE_URL and MINERU_TOKEN'
  MINERU_FLAGS="--mineru.local.enabled=true --mineru.local.base-url=$MINERU_BASE_URL --mineru.local.token=\${MINERU_TOKEN} --mineru.local.tier=flash --mineru.local.poll-interval-ms=500"
fi

start_ai() { local port=$1 worker=$2
  local args=(
    --p2.enabled=true "--p2.worker.enabled=$worker" --p2.outbox.relay-enabled=true
    --p2.object-store.type=fs "--p2.object-store.root=$WORK/objects"
    "--AI_DB_URL=jdbc:postgresql://127.0.0.1:$DB_PORT/$DB_NAME?client_encoding=UTF8&currentSchema=ai,extensions"
    --spring.data.redis.host=127.0.0.1 "--spring.data.redis.port=$REDIS_PORT"
    "--spring.data.redis.password=$REDIS_PASS"
    --ai.integration.enabled=true --ai.integration.security.enabled=true --ai.integration.high-risk.enabled=true
    "--ai.integration.security.public-key-path=$WORK/keys/public.pem"
    "--ai.integration.security.service-credential=$SERVICE_CREDENTIAL"
    "--ai.integration.platform-base-url=http://127.0.0.1:$PLATFORM_PORT"
    "--ai.integration.platform-service-credential=$SERVICE_CREDENTIAL"
    --p2.test-control.enabled=true --p2.events.poll-interval-ms=200 --p2.worker.poll-interval-ms=200
    --p2.worker.heartbeat-seconds=2 --p2.worker.lease-seconds=6
    --p2.embedding.mode=synthetic --p2.chat.mode=synthetic
    --p2.chat.egress.enabled=true --p2.chat.egress.allowed-providers=synthetic
    --p2.upload.max-bytes=52428800 --p2.budget.default-tenant-units=100000
    --p2.executor.mode=synthetic --p2.executor.synthetic-step-delay-ms=1500
    "--server.port=$port")
  ( cd "$WORK" && source ./env.sh
    # shellcheck disable=SC2086
    nohup java -Dfile.encoding=UTF-8 -Xmx1024m -jar "$AI_JAR" "${args[@]}" $MINERU_FLAGS > "$EVIDENCE/ai-$port.log" 2>&1 < /dev/null & echo $! > "$WORK/ai-$port.pid" ) }
start_platform() { local port=$1 aiport=$2
  local args=(
    --spring.profiles.active=dev
    "--spring.datasource.dynamic.datasource.master.url=jdbc:postgresql://127.0.0.1:$DB_PORT/$DB_NAME?currentSchema=platform,extensions"
    --PLATFORM_DB_USERNAME=nrtplatform --REDIS_HOST=127.0.0.1 "--REDIS_PORT=$REDIS_PORT"
    --ai.integration.enabled=true "--ai.integration.ai-base-url=http://127.0.0.1:$aiport/api/ragent"
    --ai.integration.forward-timeout-millis=10000
    "--ai.integration.service-credential=$SERVICE_CREDENTIAL"
    "--ai.integration.authorization.service-credential=$SERVICE_CREDENTIAL"
    "--ai.integration.delegation.private-key-path=$WORK/keys/private.pem"
    --ai.integration.sse.connect-timeout-millis=5000 --ai.integration.sse.idle-timeout-millis=120000
    --ai.integration.sse.max-duration-millis=1800000 --ai.integration.upload-max-bytes=52559872
    "--server.port=$port")
  ( cd "$WORK" && source ./env.sh
    nohup java -Dfile.encoding=UTF-8 -Xmx1024m -jar "$PLATFORM_JAR" "${args[@]}" > "$EVIDENCE/platform-$port.log" 2>&1 < /dev/null & echo $! > "$WORK/platform-$port.pid" ) }

wait_ready() { local port=$1 pidfile=$2 seconds=${3:-180}
  for i in $(seq 1 "$seconds"); do
    local code; code=$(curl -s -o /dev/null -w '%{http_code}' -m 2 "http://127.0.0.1:$port/" 2>/dev/null || true)
    if [[ "$code" =~ ^[2-5] ]]; then
      local pid; pid=$(cat "$pidfile")
      if [ -r "/proc/$pid/cmdline" ] && tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q "$OWNER"; then
        echo "$pid"; return 0
      fi
    fi
    sleep 1
  done
  return 1; }

note "### 5. start platform1->AI1(worker on), platform2->AI2(worker off)"
start_ai "$AI_PORT" true
start_platform "$PLATFORM_PORT" "$AI_PORT"
start_platform "$PLATFORM2_PORT" "$AI2_PORT"
PID_AI1=$(wait_ready "$AI_PORT" "$WORK/ai-$AI_PORT.pid") || { tail -30 "$EVIDENCE/ai-$AI_PORT.log"; die 'AI1 not ready'; }
PIDS="$PIDS $PID_AI1"
PID_P1=$(wait_ready "$PLATFORM_PORT" "$WORK/platform-$PLATFORM_PORT.pid") || { tail -30 "$EVIDENCE/platform-$PLATFORM_PORT.log"; die 'platform1 not ready'; }
PIDS="$PIDS $PID_P1"
PID_P2=$(wait_ready "$PLATFORM2_PORT" "$WORK/platform-$PLATFORM2_PORT.pid") || { tail -30 "$EVIDENCE/platform-$PLATFORM2_PORT.log"; die 'platform2 not ready'; }
PIDS="$PIDS $PID_P2"
ok 'ENV-services' "AI1 pid=$PID_AI1 platform1 pid=$PID_P1 platform2 pid=$PID_P2"

note "### 6. real platform login for both synthetic tenants"
token() { local port=$1 tenant=$2
  curl -sS -m 20 -X POST "http://127.0.0.1:$port/auth/login" -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"$tenant\",\"username\":\"nrtadmin\",\"password\":\"$FIXTURE_PASSWORD\",\"clientId\":\"nrt-client\",\"grantType\":\"password\"}" \
    > "$EVIDENCE/login-$tenant-$port.body" 2>/dev/null
  local t; t=$(jsonstr "$EVIDENCE/login-$tenant-$port.body" accessToken)
  [ -n "$t" ] || t=$(jsonstr "$EVIDENCE/login-$tenant-$port.body" access_token)
  echo "$t"; }
T1=$(token "$PLATFORM_PORT" nrtt1)
T1B=$(token "$PLATFORM2_PORT" nrtt1)
[ -n "$T1" ] && [ -n "$T1B" ] || { tail -5 "$EVIDENCE/login-nrtt1-$PLATFORM_PORT.body"; die 'platform login failed'; }
ok 'ENV-login' 'both synthetic tenants logged in through the real platform login flow (both gateway instances)'

AUTH1=(-H "Authorization: Bearer $T1" -H 'clientid: nrt-client')
submit() { local key=$1 body=$2 log=$3
  http POST "http://127.0.0.1:$PLATFORM_PORT/api/ai/v1/runs" "$log" -H 'Content-Type: application/json' -H "Idempotency-Key: $key" "${AUTH1[@]}" -d "$body"
  codeof "$log"; }

note "### 7. N1 atomic admission"
http POST "http://127.0.0.1:$PLATFORM_PORT/api/ai/v1/knowledge-bases" n1-kb -H 'Content-Type: application/json' "${AUTH1[@]}" -d '{"name":"nrt-kb-t1"}'
KB1=$(jsonstr "$EVIDENCE/n1-kb.body" kbId)
[ -n "$KB1" ] || KB1=$(jsonstr "$EVIDENCE/n1-kb.body" id)
[ -n "$KB1" ] || { cat "$EVIDENCE/n1-kb.body"; die 'KB creation failed'; }
ok 'ENV-kb' "knowledge base created through the gateway (kbId=$KB1)"
KEY1="nrt-n1-$OWNER"
RUN_BODY="{\"schemaVersion\":1,\"action\":\"rag.chat\",\"input\":{\"text\":\"nrt admission control case\"},\"resourceRefs\":[{\"type\":\"knowledge_base\",\"id\":\"$KB1\"}],\"budget\":{\"maxTokens\":2000}}"
S1=$(submit "$KEY1" "$RUN_BODY" n1-first)
RUN1=$(jsonstr "$EVIDENCE/n1-first.body" runId)
[ -n "$RUN1" ] || { cat "$EVIDENCE/n1-first.body"; die 'first submit did not return runId'; }
S2=$(submit "$KEY1" "$RUN_BODY" n1-replay)
RUN2=$(jsonstr "$EVIDENCE/n1-replay.body" runId)
REPLAYED=$(grep -oE '"replayed"[[:space:]]*:[[:space:]]*(true|false)' "$EVIDENCE/n1-replay.body" | head -1 | grep -o 'true\|false')
if [ "$S1" = 202 ] && [ "$S2" = 202 ] && [ "$RUN2" = "$RUN1" ] && [ "$REPLAYED" = true ]; then
  ok 'N1-same-key-same-body-replay' "202->202 same runId=$RUN1 replayed=$REPLAYED"
else fail 'N1-same-key-same-body-replay' "s1=$S1 s2=$S2 run1=$RUN1 run2=$RUN2 replayed=$REPLAYED"; fi
DB1=$(sqlq "SELECT (SELECT count(*) FROM ai.ai_run WHERE idempotency_key='$KEY1')||'|'||(SELECT count(*) FROM ai.ai_budget_reservation b JOIN ai.ai_run r ON r.tenant_id=b.tenant_id AND r.run_id=b.run_id WHERE r.idempotency_key='$KEY1')||'|'||(SELECT count(*) FROM ai.outbox_event o JOIN ai.ai_run r ON r.tenant_id=o.tenant_id AND r.run_id=o.run_id WHERE r.idempotency_key='$KEY1' AND o.seq=1)")
if [ "$DB1" = "1|1|1" ]; then ok 'N1-db-single-acceptance' "run|reservation|outbox-seq1 = $DB1"; else fail 'N1-db-single-acceptance' "got $DB1"; fi
CONFLICT_BODY="{\"schemaVersion\":1,\"action\":\"rag.chat\",\"input\":{\"text\":\"different body same key\"},\"resourceRefs\":[{\"type\":\"knowledge_base\",\"id\":\"$KB1\"}],\"budget\":{\"maxTokens\":2000}}"
S3=$(submit "$KEY1" "$CONFLICT_BODY" n1-conflict)
RUN3=$(sqlq "SELECT count(*) FROM ai.ai_run WHERE idempotency_key='$KEY1'")
if [ "$S3" = 409 ] && [ "$RUN3" = 1 ]; then ok 'N1-same-key-different-body-409' "status=$S3 runs=$RUN3"; else fail 'N1-same-key-different-body-409' "status=$S3 runs=$RUN3"; fi
S4=$(curl -sS -o "$EVIDENCE/n1-noauth.body" -w '%{http_code}' -X POST "http://127.0.0.1:$PLATFORM_PORT/api/ai/v1/runs" -H 'Content-Type: application/json' -H "Idempotency-Key: nrt-noauth-$OWNER" -d "$RUN_BODY")
NOAUTH=$(grep -oE '"code":[[:space:]]*401' "$EVIDENCE/n1-noauth.body" | head -1)
NOAUTH_RUNS=$(sqlq "SELECT count(*) FROM ai.ai_run WHERE idempotency_key='nrt-noauth-$OWNER'")
if [ "$NOAUTH_RUNS" = 0 ] && { [ "$S4" = 401 ] || [ -n "$NOAUTH" ]; }; then ok 'N1-unauthenticated-401' "http=$S4 envelope=$NOAUTH runs=$NOAUTH_RUNS"; else fail 'N1-unauthenticated-401' "http=$S4 envelope=$NOAUTH runs=$NOAUTH_RUNS"; fi

note "### 8. N2+N3: pause worker mid-step, capture SSE, kill node A, takeover on node B, replay"
KEY2="nrt-n2-$OWNER"
SLOW_BODY="{\"schemaVersion\":1,\"action\":\"rag.chat\",\"input\":{\"text\":\"nrt takeover and replay case\"},\"resourceRefs\":[{\"type\":\"knowledge_base\",\"id\":\"$KB1\"}],\"budget\":{\"maxTokens\":2000}}"
# Widen the takeover window deterministically: pause the worker inside its first step
# commit on AI1 only (the hook lives in that JVM; AI2 boots without it).
curl -sS -m 10 -X POST "http://127.0.0.1:$AI_PORT/internal/ai/v1/test/fault" -H 'Content-Type: application/json' \
  -d '{"hook":"worker.beforeStepCommit","times":1,"pauseMillis":45000}' > "$EVIDENCE/arm-fault.body" 2>/dev/null
S5=$(submit "$KEY2" "$SLOW_BODY" n2-submit)
RUN2ID=$(jsonstr "$EVIDENCE/n2-submit.body" runId)
[ -n "$RUN2ID" ] || { cat "$EVIDENCE/n2-submit.body"; die 'N2 submit failed'; }
SSE1="$EVIDENCE/sse-nodeA.txt"
rm -f "$SSE1"
# stream in the background and kill AI1 the moment frames appear - the armed pause
# holds the worker inside its first step commit, so the run is mid-execution then
curl -sS -N -m 120 "http://127.0.0.1:$PLATFORM_PORT/api/ai/v1/runs/$RUN2ID/events?afterSeq=0" -H 'clientid: nrt-client' -H "Authorization: Bearer $T1" -o "$SSE1" 2>/dev/null &
SSE_PID=$!
SEQ_A=''
for i in $(seq 1 40); do
  sleep 1
  SEQ_A=$(grep -oE '^id: [0-9]+' "$SSE1" 2>/dev/null | awk '{print $2}' | sort -n | tail -1)
  [ -n "$SEQ_A" ] && [ "$SEQ_A" -ge 2 ] && break
done
if [ -z "$SEQ_A" ] || [ "$SEQ_A" -lt 2 ]; then kill $SSE_PID 2>/dev/null || true; cat "$SSE1" 2>/dev/null; die 'no SSE frames captured from node A'; fi
note "  node A frames up to seq=$SEQ_A; killing AI1 (pid $PID_AI1) mid-run"
kill -9 "$PID_AI1"
kill $SSE_PID 2>/dev/null || true
note "  restarting AI2 with its worker enabled (port $AI2_PORT was worker-off)"
start_ai "$AI2_PORT" true
PID_AI2=$(wait_ready "$AI2_PORT" "$WORK/ai-$AI2_PORT.pid") || { tail -30 "$EVIDENCE/ai-$AI2_PORT.log"; die 'AI2 not ready'; }
PIDS="$PIDS $PID_AI2"
note "  AI2 ready (pid $PID_AI2); waiting for lease expiry + takeover + terminal"
STATUS=''
for i in $(seq 1 120); do
  http GET "http://127.0.0.1:$PLATFORM2_PORT/api/ai/v1/runs/$RUN2ID" n2-poll -H "Authorization: Bearer $T1B" -H 'clientid: nrt-client'
  STATUS=$(jsonstr "$EVIDENCE/n2-poll.body" status)
  case "$STATUS" in SUCCEEDED|FAILED) break;; esac
  sleep 2
done
[ "$STATUS" = SUCCEEDED ] || { cat "$EVIDENCE/n2-poll.body"; die "run did not succeed after takeover (status=$STATUS)"; }
ATTEMPT=$(sqlq "SELECT attempt FROM ai.ai_run WHERE run_id='$RUN2ID'")
TERMINALS=$(sqlq "SELECT count(*) FROM ai.ai_run_event WHERE run_id='$RUN2ID' AND event_type='run.terminal'")
GAPS=$(sqlq "SELECT count(*) FROM (SELECT seq, lag(seq) OVER (ORDER BY seq) prev FROM ai.ai_run_event WHERE run_id='$RUN2ID') t WHERE prev IS NOT NULL AND seq != prev+1")
RESV=$(sqlq "SELECT count(*) FROM ai.ai_budget_reservation WHERE run_id='$RUN2ID'")
if [ "$TERMINALS" = 1 ] && [ "$GAPS" = 0 ] && [ "$RESV" = 1 ] && [ "$ATTEMPT" -ge 2 ]; then
  ok 'N2-worker-takeover' "attempt=$ATTEMPT terminals=1 event-gaps=0 reservations=1"
else fail 'N2-worker-takeover' "attempt=$ATTEMPT terminals=$TERMINALS gaps=$GAPS resv=$RESV"; fi
SSE2="$EVIDENCE/sse-nodeB.txt"
timeout 60 curl -sS -N -m 55 "http://127.0.0.1:$PLATFORM2_PORT/api/ai/v1/runs/$RUN2ID/events?afterSeq=$SEQ_A" -H 'clientid: nrt-client' -H "Authorization: Bearer $T1B" -o "$SSE2" || true
SEQ_B=$(grep -oE '^id: [0-9]+' "$SSE2" | awk '{print $2}' | sort -n | head -1)
LAST_B=$(grep -oE '^id: [0-9]+' "$SSE2" | awk '{print $2}' | sort -n | tail -1)
DUPES=$(grep -oE '^id: [0-9]+' "$SSE2" | awk '{print $2}' | sort -n | uniq -d | wc -l)
PGSEQS=$(sqlq "SELECT count(*) FROM ai.ai_run_event WHERE run_id='$RUN2ID' AND seq>$SEQ_A")
BSEQS=$(grep -oE '^id: [0-9]+' "$SSE2" | awk '{print $2}' | sort -n | wc -l)
BTERMINAL=$(grep -q 'event: run.terminal' "$SSE2" && echo yes || echo no)
MAXSEQ=$(sqlq "SELECT max(seq) FROM ai.ai_run_event WHERE run_id='$RUN2ID'")
if [ "$SEQ_B" = "$((SEQ_A+1))" ] && [ "$DUPES" = 0 ] && [ "$BSEQS" = "$PGSEQS" ] && [ "$BTERMINAL" = yes ] && [ "$LAST_B" = "$MAXSEQ" ]; then
  ok 'N3-sse-cross-instance-replay' "afterSeq=$SEQ_A -> frames $SEQ_B..$LAST_B (db=$PGSEQS, dupes=$DUPES, terminal=$BTERMINAL)"
else fail 'N3-sse-cross-instance-replay' "seq_b=$SEQ_B last=$LAST_B db=$PGSEQS frames=$BSEQS dupes=$DUPES terminal=$BTERMINAL max=$MAXSEQ"; fi

note "### 9. N4/N5 gated groups"
if [ "${RUN_INGEST_CHAIN:-0}" = 1 ]; then
  not_run 'N4-private-pdf-chain' 'gated group: chain execution not part of this batch run; run with a real local MinerU to enable'
else
  not_run 'N4-private-pdf-chain' 'requires RUN_INGEST_CHAIN=1 + MINERU_BASE_URL/MINERU_TOKEN (local parser instance)'
fi
if [ "${RUN_AGENT_SANDBOX:-0}" = 1 ]; then
  not_run 'N5-agent-sandbox' 'gated group: operationKey choreography not part of this batch run'
else
  not_run 'N5-agent-sandbox' 'requires RUN_AGENT_SANDBOX=1 (independent sandbox service)'
fi

note "### 10. teardown (owned JVMs verified by cmdline, containers by owner label)"
NOTRUN=$(grep -c '"pass":null' "$EVIDENCE/results.jsonl" || true)
note "summary: PASS=$PASS FAIL=$FAIL NOT_RUN=$NOTRUN"
if [ "$FAIL" -gt 0 ]; then echo "NATIVE RUNTIME CHECK FAILED ($FAIL failed case(s))"; exit 1; fi
echo "NATIVE RUNTIME CHECK PASSED (mandatory groups; gated groups recorded as NOT_RUN with reasons)"
