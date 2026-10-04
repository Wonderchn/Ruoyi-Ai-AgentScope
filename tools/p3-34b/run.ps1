[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Container,
    [string]$RemoteHost='root@192.168.139.103',
    [string]$RunTag=('p34b'+(Get-Date -Format 'yyyyMMddHHmmss')),
    [string]$EvidenceDir='D:\AI-project\mydocs\p3\evidence\U01\p2p3-cont20261003',
    [string]$RepoRoot='D:\AI-project\Ruoyi-Ai-AgentScope'
)
$ErrorActionPreference='Stop'
if($Container -notmatch '^p2core-pg-([a-z0-9]+)$' -or $RunTag -notmatch '^[a-z][a-z0-9]{1,30}$') {throw 'owned container/tag required'}
$owner=$Matches[1]
# Re-match separately: RunTag checks must never replace the container owner capture.
$owner=$Container.Substring('p2core-pg-'.Length)
$dir=Join-Path $EvidenceDir $RunTag
$private=Join-Path 'D:\AI-project\.scratch\p3-34b' $RunTag
[void](New-Item -ItemType Directory -Force $dir,$private)
$remote="/opt/p2core-acceptance/$owner/34b-$RunTag"
$probeSchema="probe_$RunTag"; $sandboxSchema="sandbox_$RunTag"; $sandboxUser="sandbox_$RunTag"
$bytes=New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$sandboxPassword=([BitConverter]::ToString($bytes)).Replace('-','').ToLower()
function Invoke-Owned([string]$script,[string]$log) {
    $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($script -replace "`r`n","`n")+"`n"))
    $raw=& ssh -o BatchMode=yes -o LogLevel=ERROR $RemoteHost "printf %s $encoded | base64 -d | bash" 2>&1
    $code=$LASTEXITCODE
    $safe=($raw|Out-String).Replace($sandboxPassword,'<redacted>')
    [IO.File]::WriteAllText((Join-Path $dir $log),$safe,(New-Object Text.UTF8Encoding($false)))
    if($code -ne 0) {throw "owned command failed ($code), see $log"}
    return $safe
}
$check=@'
set -eu
test "$(docker inspect -f '{{index .Config.Labels "p2core.owner"}}' __CONTAINER__)" = '__OWNER__'
test -f /opt/p2core-acceptance/__OWNER__/env.sh
java -version 2>&1
javac -version
mkdir -p __REMOTE__; chmod 700 __REMOTE__
'@
$null=Invoke-Owned ($check.Replace('__CONTAINER__',$Container).Replace('__OWNER__',$owner).Replace('__REMOTE__',$remote)) 'preflight.log'
$jar=Join-Path $RepoRoot 'services/ai/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar'
$source=Join-Path $RepoRoot 'tools/p3-34b/Probe34b.java'
& scp -q $source "${RemoteHost}:$remote/Probe34b.java"
if($LASTEXITCODE -ne 0){throw 'probe upload failed'}
& scp -q $jar "${RemoteHost}:$remote/ai.jar"
if($LASTEXITCODE -ne 0){throw 'jar upload failed'}
$sql=@"
CREATE SCHEMA $probeSchema;
CREATE SCHEMA $sandboxSchema;
CREATE ROLE $sandboxUser LOGIN PASSWORD '$sandboxPassword';
CREATE TABLE $probeSchema.ai_run (LIKE ai.ai_run INCLUDING ALL);
CREATE TABLE $probeSchema.ai_run_step (LIKE ai.ai_run_step INCLUDING ALL);
CREATE TABLE $probeSchema.ai_run_event (LIKE ai.ai_run_event INCLUDING ALL);
CREATE TABLE $probeSchema.outbox_event (LIKE ai.outbox_event INCLUDING ALL);
CREATE TABLE $probeSchema.probe_control(stage text PRIMARY KEY);
CREATE TABLE $probeSchema.probe_tool(operation_key text PRIMARY KEY,args_hash text NOT NULL,state text NOT NULL,result text,fence bigint);
CREATE TABLE $probeSchema.probe_state(state_key text PRIMARY KEY,payload text,tenant_id text,member_id text,run_id text,engine_version text,checkpoint_version int);
CREATE TABLE $probeSchema.probe_claim(run_id text PRIMARY KEY,worker text NOT NULL);
CREATE TABLE $sandboxSchema.requests(id bigserial PRIMARY KEY,operation_key text NOT NULL,args_hash text NOT NULL,at timestamptz DEFAULT now());
CREATE TABLE $sandboxSchema.tickets(operation_key text PRIMARY KEY,args_hash text NOT NULL,external_id text NOT NULL);
GRANT USAGE ON SCHEMA $probeSchema TO p2app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA $probeSchema TO p2app;
GRANT CONNECT ON DATABASE ragent_p2core TO $sandboxUser;
GRANT USAGE ON SCHEMA $sandboxSchema TO $sandboxUser;
GRANT SELECT,INSERT ON ALL TABLES IN SCHEMA $sandboxSchema TO $sandboxUser;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA $sandboxSchema TO $sandboxUser;
"@
$sqlEncoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
$null=Invoke-Owned "set -eu; printf %s $sqlEncoded | base64 -d | docker exec -i $Container psql -X -v ON_ERROR_STOP=1 -U postgres -d ragent_p2core -f -" 'schema.log'
$env=@'
set -eu
umask 077
cd __REMOTE__
source /opt/p2core-acceptance/__OWNER__/env.sh
port=$(docker port __CONTAINER__ 5432/tcp | sed 's/.*://')
export PROBE_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/ragent_p2core?currentSchema=__PROBE__,extensions"
export SANDBOX_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/ragent_p2core?currentSchema=__SANDBOX__"
export SANDBOX_DB_USER='__USER__'
export SANDBOX_DB_PASSWORD='__PASSWORD__'
unzip -q ai.jar 'BOOT-INF/lib/*' 'BOOT-INF/classes/*' -d unpacked
classpath=".:unpacked/BOOT-INF/classes:unpacked/BOOT-INF/lib/*"
javac -encoding UTF-8 -cp "$classpath" Probe34b.java
java -cp "$classpath" Probe34b
docker exec __CONTAINER__ psql -U postgres -d ragent_p2core -X -tA -v ON_ERROR_STOP=1 -c "SELECT operation_key,args_hash,external_id,(SELECT count(*) FROM __SANDBOX__.requests r WHERE r.operation_key=t.operation_key) AS request_count FROM __SANDBOX__.tickets t ORDER BY operation_key"
docker exec __CONTAINER__ psql -U postgres -d ragent_p2core -X -tA -v ON_ERROR_STOP=1 -c "SELECT run_id,status,attempt,fence FROM __PROBE__.ai_run ORDER BY run_id"
'@
$env=$env.Replace('__REMOTE__',$remote).Replace('__OWNER__',$owner).Replace('__CONTAINER__',$Container).Replace('__PROBE__',$probeSchema).Replace('__SANDBOX__',$sandboxSchema).Replace('__USER__',$sandboxUser).Replace('__PASSWORD__',$sandboxPassword)
[IO.File]::WriteAllText((Join-Path $private 'controlled-run.sh'),$env,(New-Object Text.UTF8Encoding($false)))
$passed=$false
try {
    $output=Invoke-Owned $env 'probe.log'
    $passed=$output.Contains('PASS 34B-production-substrate') -and -not $output.Contains('FAIL ')
} finally {
    $manifest=@{container=$Container;owner=$owner;runTag=$RunTag;probeSchema=$probeSchema;sandboxSchema=$sandboxSchema;remote=$remote;pass=$passed;
        sourceSha256=(Get-FileHash -Algorithm SHA256 $source).Hash;jarSha256=(Get-FileHash -Algorithm SHA256 $jar).Hash;
        head=(& git -C $RepoRoot rev-parse HEAD);workingTree='uncommitted corrections, source hash authoritative';
        limitation='Production P2 lease/fence probe. Binding rows are probe state, not formal AgentScope StateStore; 34A actual save/get evidence is separate. Formal P3 gate not signed by this report.'}
    [IO.File]::WriteAllText((Join-Path $dir 'manifest.json'),($manifest|ConvertTo-Json -Depth 6),(New-Object Text.UTF8Encoding($false)))
}
if(-not $passed){exit 1}
Write-Host "34B substrate probe passed; evidence: $dir"
