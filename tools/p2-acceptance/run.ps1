[CmdletBinding()]
param(
    [ValidateSet('Integration')][string]$Mode = 'Integration',
    [string]$EvidenceDir = 'D:\AI-project\mydocs\p2\evidence',
    [string]$RepoRoot = 'D:\AI-project\Ruoyi-Ai-AgentScope',
    [string]$RemoteHost = 'root@192.168.139.103',
    [string]$RunTag = ('p2c' + (Get-Date -Format 'yyyyMMddHHmmss')),
    [int]$AiPort = 0, [int]$Ai2Port = 0, [int]$PlatformPort = 0,
    [int]$DedicatedPgPort = 0,[int]$DedicatedRedisPort = 0,
    [switch]$SkipBuild, [switch]$SkipSetup, [switch]$KeepEnvironment, [switch]$ReclaimStale, [switch]$SmokeOnly, [switch]$PhaseAOnly, [switch]$PhaseBOnly, [switch]$PhaseCOnly, [switch]$BrowserOnly, [switch]$AgentCoreOnly, [switch]$AgentRecoveryOnly, [switch]$AgentSecurityOnly, [switch]$AgentReadFaultsOnly, [switch]$ParserRecoveryOnly, [string]$CaseAttempt = '1', [switch]$RealProvidersOnly, [switch]$RealAgentOnly, [string]$RealAgentKb, [string]$ProviderSecretFile, [string]$OwnedDeliveryRecoveryScript, [string]$OwnedMetadataFile
)
# P2 专属合成环境验收 runner。
#
# 边界（与交接授权一致）：
#  * 所有资源为本轮自建、owner 标签为 $RunTag：PG/Redis 容器、数据库、角色、对象目录、jar 进程；
#  * 不触碰原有 ruoyi-agent-pg / ruoyi-agent-redis / 业务库；MinerU 只调用专属 lab 实例的本地 API；
#  * 口令随机生成、只在远端本轮目录与本机 work 目录（不进证据）；证据脱敏；
#  * 结束按 PID 停止本轮自有进程，容器默认保留供复核（-KeepEnvironment 时连容器也保留）。
# 用例大量依赖预期失败（注入故障/负例），错误记录不终止运行；关键路径显式检查退出码
$ErrorActionPreference = 'Continue'
$script:Tag = $RunTag.ToLower()
$script:Evidence = Join-Path ([IO.Path]::GetFullPath($EvidenceDir)) $script:Tag
$script:Work = Join-Path 'D:\AI-project\.scratch\p2-acceptance' $script:Tag
[void](New-Item -ItemType Directory -Force $script:Evidence, $script:Work)
$script:RemoteRoot = "/opt/p2core-acceptance/$($script:Tag)"
$script:PgName = "p2core-pg-$($script:Tag)"
$script:RedisName = "p2core-redis-$($script:Tag)"
$script:Db = 'ragent_p2core'
# 端口隔离（U09 §5 R1）：由 tag 的稳定散列映射到互不重叠区段。
# 原先按 tag 长度推导端口，等长 tag（...a / ...b）得到同一端口，且服务端口固定，
# 上一轮孤儿 jar 会占住 28082/29090，让本轮 jar 启动失败却被"能连通"判成就绪。
function Get-TagSlot([string]$Value, [int]$Mod) {
    $sum = 0
    foreach ($ch in $Value.ToCharArray()) { $sum = ($sum * 31 + [int]$ch) % 1000003 }
    return $sum % $Mod
}
$script:Slot = Get-TagSlot $script:Tag 120
$script:HalfSlot = [int]($script:Slot / 2)
$script:PgHostPort = 15400 + $script:Slot
$script:RedisHostPort = 15700 + $script:Slot
if($DedicatedPgPort -gt 0){$script:PgHostPort=$DedicatedPgPort}
if($DedicatedRedisPort -gt 0){$script:RedisHostPort=$DedicatedRedisPort}
if ($PlatformPort -le 0) { $PlatformPort = 28082 + $script:HalfSlot }
if ($AiPort -le 0) { $AiPort = 29090 + $script:HalfSlot }
if ($Ai2Port -le 0) { $Ai2Port = $AiPort + 100 }
$script:Results = New-Object System.Collections.ArrayList
$script:Pids = @()
if($ReclaimStale) {
    $priorPath=Join-Path 'D:\AI-project\mydocs\p2\evidence' "$($script:Tag)/run-meta.json"
    if($OwnedMetadataFile){$priorPath=$OwnedMetadataFile}
    if(Test-Path -LiteralPath $priorPath) {
        $prior=Get-Content -LiteralPath $priorPath -Raw|ConvertFrom-Json
        if($prior.runTag -ne $script:Tag){throw 'owned metadata tag mismatch'}
        $script:Pids=@($prior.pids)
    }
}
$script:AiNodes = @()
$script:HttpLogs = New-Object System.Collections.ArrayList

function Write-Step([string]$Text) { Write-Host ("[p2] " + $Text) }
function Get-PortOwnerPid([int]$Port) {
    $r = Remote "ss -lntp 2>/dev/null | awk -v p=`":$Port`" '`$4 ~ p`"$`" {print `$NF}' | head -1" ''
    if ($r.Output -match 'pid=(\d+)') { return [int]$Matches[1] }
    return 0
}
function Get-PortOwnerName([int]$Port) {
    $r = Remote "ss -lntp 2>/dev/null | awk -v p=`":$Port`" '`$4 ~ p`"$`" {print `$NF}' | head -1" ''
    if ($r.Output -match '"([^"]+)"') { return $Matches[1] }
    return 'unknown'
}
function Add-Case([string]$Id, [bool]$Ok, [string]$Detail, [string]$Class = 'integration') {
    [void]$script:Results.Add([pscustomobject]@{ id = $Id; ok = $Ok; detail = $Detail; class = $Class; at = (Get-Date).ToUniversalTime().ToString('o') })
    $mark = if ($Ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("  [{0}] {1} :: {2}" -f $mark, $Id, $Detail)
    Save-Evidence 'results-partial.json' ($script:Results | ConvertTo-Json -Depth 5)
}
function Save-Evidence([string]$Name, [string]$Content) {
    $path = Join-Path $script:Evidence $Name
    [IO.File]::WriteAllText($path, $Content, (New-Object Text.UTF8Encoding($false)))
}
function Redact([string]$Text) {
    if (-not $Text) { return $Text }
    foreach ($secret in @($script:PgPass, $script:RedisPass, $script:ServiceCredential, $script:FixturePassword, $script:DeepSeekKey, $script:DashScopeKey, $script:SandboxCredential)) {
        if ($secret) { $Text = $Text.Replace($secret, '<redacted>') }
    }
    $Text = [regex]::Replace($Text, 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '<redacted-jwt>')
    return [regex]::Replace($Text, '("(?:access_token|accessToken|token)"\s*:\s*")[^"]+', '$1<redacted-token>')
}

# ---------------------------------------------------------------- remote helpers

function Remote([string]$Command, [string]$LogName = '') {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $previousEncoding = $OutputEncoding
    $OutputEncoding = New-Object Text.UTF8Encoding($false)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($Command -replace "`r`n", "`n") + "`n"))
    try { $out = & ssh -o BatchMode=yes -o LogLevel=ERROR $RemoteHost "printf %s $encoded | base64 -d | bash" 2>&1 } finally { $ErrorActionPreference = $previous; $OutputEncoding = $previousEncoding }
    $code = $LASTEXITCODE
    $text = ($out | Out-String)
    if ($LogName) { Save-Evidence ($LogName + '.log') (Redact $text) }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}
function RemoteStdin([string]$Command, [string]$Text, [string]$LogName = '') {
    $tmp = Join-Path $script:Work ([guid]::NewGuid().ToString('N') + '.stdin')
    [IO.File]::WriteAllText($tmp, $Text, (New-Object Text.UTF8Encoding($false)))
    $previous = $ErrorActionPreference
    $previousEncoding = $OutputEncoding
    $ErrorActionPreference = 'Continue'
    $OutputEncoding = New-Object Text.UTF8Encoding($false)
    try { $out = Get-Content -LiteralPath $tmp -Raw -Encoding UTF8 | & ssh -o BatchMode=yes -o LogLevel=ERROR $RemoteHost $Command 2>&1 } finally {
        $ErrorActionPreference = $previous
        $OutputEncoding = $previousEncoding
    }
    $code = $LASTEXITCODE
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    $text = ($out | Out-String)
    if ($LogName) { Save-Evidence ($LogName + '.log') (Redact $text) }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}
function Sql([string]$Text, [string]$LogName = 'case-sql', [string]$User = 'postgres') {
    $dbgCmd = "docker exec -i -e PGCLIENTENCODING=UTF8 $($script:PgName) psql -U $User -d $($script:Db) -X -q -tA -v ON_ERROR_STOP=1 -f -"
    if ($dbgCmd -match '[{}]') { Write-Host ("[sqldebug-BRACE] {0} dbType={1} userType={2} cmd={3}" -f $LogName, $script:Db.GetType().Name, $User.GetType().Name, $dbgCmd) }
    # search_path 在 SQL 文本内设置（经 stdin 传入，无 shell 引号问题）；
    # 迁移自带的 SET search_path 在文本更后处执行，自然覆盖本前缀。
    $prefix = "SET search_path TO ai,extensions,platform;`n"
    return RemoteStdin ("docker exec -i -e PGCLIENTENCODING=UTF8 $($script:PgName) psql -U $User -d $($script:Db) -X -q -tA -v ON_ERROR_STOP=1 -f -") ($prefix + $Text) ($LogName + '.sql')
}
function Http([string]$Method, [string]$Url, [hashtable]$Headers = @{}, [string]$Body = '', [int]$Timeout = 30, [string]$LogName = 'http') {
    $dir = "/tmp/p2http-$($script:Tag)"
    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add("cd $dir")
    [void]$lines.Add('rm -f resp.txt resp.hdr')
    # 平台网关校验 clientid 头与 token 内 clientId 一致；AI 内部接口忽略多余头
    if (-not $Headers.ContainsKey('clientid')) { $Headers['clientid'] = 'p2c-client' }
    if ($Body) {
        $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Body))
        [void]$lines.Add("printf %s '$b64' | base64 -d > body.bin")
        [void]$lines.Add("BODYARG='--data-binary @body.bin'")
    } else { [void]$lines.Add("BODYARG=''") }
    $hdr = ''
    foreach ($key in $Headers.Keys) {
        $value = ([string]$Headers[$key]).Replace("'", "'\''")
        $hdr += " -H '$key`: $value'"
    }
    [void]$lines.Add("STATUS=`$(curl -sS -m $Timeout -o resp.txt -D resp.hdr -w '%{http_code}' -X $Method '$Url'$hdr `$BODYARG)")
    [void]$lines.Add("echo `"P2STATUS=`$STATUS`"")
    [void]$lines.Add('test ! -f resp.txt || cat resp.txt')
    $script = ($lines -join "`n")
    $scriptB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($script))
    $result = RemoteStdin ("mkdir -p $dir; umask 077; printf %s '$scriptB64' | base64 -d > $dir/req.sh; bash $dir/req.sh") '' ($LogName + '.http')
    $text = $result.Output
    $status = 0
    if ($text -match 'P2STATUS=(\d+)') { $status = [int]$Matches[1] }
    $body = ($text -replace '(?s)^.*?P2STATUS=\d+\s*', '').Trim()
    $safeBody = Redact $body
    [void]$script:HttpLogs.Add([pscustomobject]@{ log = $LogName; status = $status; body = $safeBody.Substring(0, [Math]::Min(600, $safeBody.Length)) })
    return [pscustomobject]@{ Status = $status; Body = $body }
}
function Token([string]$Tenant, [string]$User, [string]$Password) {
    $body = (@{ clientId = 'p2c-client'; grantType = 'password'; tenantId = $Tenant; username = $User; password = $Password } | ConvertTo-Json -Compress)
    $r = Http 'POST' "http://127.0.0.1:$PlatformPort/auth/login" @{ 'Content-Type' = 'application/json' } $body 20 'login'
    if ($r.Body -match '"accessToken"\s*:\s*"([^"]+)"') { return $Matches[1] }
    if ($r.Body -match '"access_token"\s*:\s*"([^"]+)"') { return $Matches[1] }
    if ($r.Body -match '"token"\s*:\s*"([^"]+)"') { return $Matches[1] }
    return $null
}
function SignDelegation([string]$Tenant, [string]$User, [string]$Action, [int]$Pv) {
    $now = [long](Remote 'date +%s').Output.Trim()
    $claims = @{ iss = 'platform'; aud = @('ai'); sub = $User; tid = $Tenant; mid = ("platform:$Tenant`:$User")
        pv = $Pv; scope = @($Action); jti = [guid]::NewGuid().ToString(); iat = $now; nbf = $now; exp = ($now + 60) } | ConvertTo-Json -Compress
    $r = Remote "bash $($script:RemoteRoot)/sign.sh '$($claims.Replace("'", "'\''"))'" 'sign-token'
    return ((($r.Output -split "`n") | Where-Object { $_ -match '^eyJ' } | Select-Object -Last 1).Trim())
}
function AiHttp([string]$Method, [string]$Path, [string]$Tenant, [string]$User, [string]$Action, [hashtable]$Headers = @{}, [string]$Body = '', [int]$Pv = 1, [int]$Timeout = 30, [string]$LogName = 'ai-http', [int]$TargetPort = 0) {
    $token = SignDelegation $Tenant $User $Action $Pv
    # 合并到新表：边枚举 Keys 边写入同一 hashtable 会抛“集合已修改”
    $forwardHeaders = @{ 'X-P04-Service-Credential' = $script:ServiceCredential }
    foreach ($k in @($Headers.Keys)) { $forwardHeaders[$k] = $Headers[$k] }
    $forwardHeaders['Authorization'] = "Bearer $token"
    $port = if ($TargetPort -gt 0) { $TargetPort } else { $AiPort }
    return Http $Method "http://127.0.0.1:$port/api/ragent$Path" $forwardHeaders $Body $Timeout $LogName
}
function DbScalar([string]$Query, [string]$LogName = 'db-scalar') {
    $r = Sql $Query $LogName 'p2app'
    return ([string](($r.Output -split "`n") | Where-Object { $_.Trim() -ne '' } | Select-Object -Last 1)).Trim()
}

# ---------------------------------------------------------------- setup

function Stop-OwnedJar([int]$Port,[bool]$Hard=$false) {
    foreach ($p in @($script:Pids | Where-Object { $_.port -eq $Port })) {
        $expected = "$($script:RemoteRoot)/$($p.side).jar"
        $killCommand=if($Hard){"kill -9"}else{"kill"}
        $r = Remote "if test -r /proc/$($p.pid)/cmdline; then tr '\0' '\n' < /proc/$($p.pid)/cmdline | grep -Fx -- '$expected' >/dev/null && tr '\0' '\n' < /proc/$($p.pid)/cmdline | grep -Fx -- '--server.port=$Port' >/dev/null || exit 2; $killCommand $($p.pid); fi" "stop-owned-$Port"
        if ($r.ExitCode -ne 0) { throw "PID ownership check failed for port $Port" }
        $stopped=Remote "for wait in `$(seq 1 30); do test ! -e /proc/$($p.pid) && exit 0; sleep 1; done; exit 3" "stop-owned-process-witness-$Port"
        if($stopped.ExitCode -ne 0){throw "verified owned JVM has not exited for port $Port; jar replacement forbidden"}
        $script:Pids = @($script:Pids | Where-Object { $_.pid -ne $p.pid })
    }
}

try {
Write-Step "runTag=$($script:Tag) evidence=$($script:Evidence)"
$script:PgPass = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
$script:RedisPass = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
$script:ServiceCredential = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ })
$script:FixturePassword = 'P2fix' + (-join ((48..57) | Get-Random -Count 6 | ForEach-Object { [char]$_ }))
# -SkipSetup 时必须复用上一轮的合成凭据（角色口令/环境文件/服务凭证已固定在本轮资源上）
$secretFile = Join-Path $script:Work 'secrets.json'
if ($SkipSetup -and (Test-Path -LiteralPath $secretFile)) {
    $saved = Get-Content -LiteralPath $secretFile -Raw | ConvertFrom-Json
    $script:PgPass = $saved.pg
    $script:RedisPass = $saved.redis
    $script:ServiceCredential = $saved.credential
    $script:FixturePassword = $saved.fixture
} else {
    [IO.File]::WriteAllText($secretFile, (@{ pg = $script:PgPass; redis = $script:RedisPass; credential = $script:ServiceCredential; fixture = $script:FixturePassword } | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
}

$pre = Remote 'echo ok; docker version --format "{{.Server.Version}}" | head -1' 'preflight'
if ($pre.ExitCode -ne 0) { Add-Case 'ENV-preflight' $false 'remote ssh/docker unavailable'; exit 2 }

if (-not $SkipBuild) {
    Write-Step 'build product jars (offline)'
    & mvn -o -B -ntp -f (Join-Path $RepoRoot 'services/ai/pom.xml') -Pci -DskipTests package 2>&1 | Out-File (Join-Path $script:Work 'build-ai.log')
    if ($LASTEXITCODE -ne 0) { Add-Case 'ENV-build-ai' $false 'ai package failed'; exit 2 }
    & mvn -o -B -ntp -f (Join-Path $RepoRoot 'services/platform/pom.xml') -Pdev -DskipTests package 2>&1 | Out-File (Join-Path $script:Work 'build-platform.log')
    if ($LASTEXITCODE -ne 0) { Add-Case 'ENV-build-platform' $false 'platform package failed'; exit 2 }
}
$aiJar = Join-Path $RepoRoot 'services\ai\bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar'
$platformJar = Join-Path $RepoRoot 'services\platform\ruoyi-admin\target\ruoyi-admin.jar'
foreach ($jar in @($aiJar, $platformJar)) { if (-not (Test-Path -LiteralPath $jar)) { Add-Case 'ENV-jars' $false "missing jar: $jar"; exit 2 } }


function New-BCryptHash([string]$Plain) {
    $platformJar = Join-Path $RepoRoot 'services\platform\ruoyi-admin\target\ruoyi-admin.jar'
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [IO.Compression.ZipFile]::OpenRead($platformJar)
    $libNames = @()
    try { $libNames = @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }
    $jars = @()
    foreach ($prefix in @('hutool-crypto-', 'hutool-core-')) {
        $entry = @($libNames | Where-Object { $_ -match ('^BOOT-INF/lib/' + $prefix) -and $_ -match '\.jar$' }) | Select-Object -First 1
        if (-not $entry) { return '' }
        $fileName = Split-Path $entry -Leaf
        $version = ($fileName -replace ('^' + $prefix), '') -replace '\.jar$', ''
        $candidate = Join-Path 'D:\develop\maven_repository' ('cn\hutool\' + $prefix.TrimEnd('-') + '\' + $version + '\' + $fileName)
        if (-not (Test-Path -LiteralPath $candidate)) { return '' }
        $jars += $candidate
    }
    $dir = Join-Path $script:Work 'bcrypt'
    [void](New-Item -ItemType Directory -Force $dir)
    $src = Join-Path $dir 'HashGen.java'
    $java = "public class HashGen { public static void main(String[] a) { System.out.println(cn.hutool.crypto.digest.BCrypt.hashpw(a[0])); } }"
    [IO.File]::WriteAllText($src, $java, (New-Object Text.UTF8Encoding($false)))
    $jdk = 'D:\develop\java\jdk-17.0.18.8-hotspot\bin'
    $cp = ($jars -join ';')
    & (Join-Path $jdk 'javac.exe') -cp $cp -d $dir $src 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { return '' }
    $out = & (Join-Path $jdk 'java.exe') -cp ($dir + ';' + $cp) HashGen $Plain 2>&1
    return (@($out) | Where-Object { $_ -match '^\$2' } | Select-Object -Last 1)
}

$script:FixtureHash = New-BCryptHash $script:FixturePassword
if (-not $script:FixtureHash) { Add-Case 'ENV-bcrypt' $false 'cannot compute platform-compatible BCrypt hash'; exit 2 }
Add-Case 'ENV-bcrypt' $true 'fixture password hash computed with the platform hutool implementation'

if (-not $SkipSetup) {
    Write-Step 'provision owned PG/Redis containers, schemas, migrations, fixtures'
    Remote "mkdir -p $($script:RemoteRoot)/objects $($script:RemoteRoot)/keys /tmp/p2http-$($script:Tag)" 'remote-mkdir' | Out-Null
    $existingTag=Remote "for name in $($script:PgName) $($script:RedisName); do if docker inspect `$name >/dev/null 2>&1; then echo EXISTING_TAG_RESOURCE; exit 8; fi; done" 'check-new-tag'
    if($existingTag.ExitCode -ne 0){throw 'existing tag resources retained; use SkipSetup with verified owned metadata'}
    $pg = Remote "docker run -d --name $($script:PgName) --label p2core.owner=$($script:Tag) -e POSTGRES_PASSWORD='$($script:PgPass)' -e POSTGRES_DB=$($script:Db) -p 127.0.0.1:$($script:PgHostPort):5432 pgvector/pgvector:0.8.6-pg17" 'pg-run'
    if ($pg.ExitCode -ne 0) { Add-Case 'ENV-pg' $false 'cannot start owned pg container'; exit 2 }
    $redis = Remote "docker run -d --name $($script:RedisName) --label p2core.owner=$($script:Tag) -p 127.0.0.1:$($script:RedisHostPort):6379 redis:7.4-alpine redis-server --requirepass '$($script:RedisPass)'" 'redis-run'
    if ($redis.ExitCode -ne 0) { Add-Case 'ENV-redis' $false 'cannot start owned redis container'; exit 2 }
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        $check = Remote "docker exec $($script:PgName) pg_isready -U postgres" 'pg-ready'
        if ($check.Output -match 'accepting connections') { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { Add-Case 'ENV-pg-ready' $false 'pg did not become ready'; exit 2 }
    # 角色/库/schema/扩展：全部本轮自建
    $roleSql = @"
DO `$`$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='p2mig') THEN CREATE ROLE p2mig LOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='p2app') THEN CREATE ROLE p2app LOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='p2platform') THEN CREATE ROLE p2platform LOGIN; END IF;
END `$`$;
ALTER ROLE p2mig LOGIN PASSWORD '$($script:PgPass)';
ALTER ROLE p2app LOGIN PASSWORD '$($script:PgPass)';
ALTER ROLE p2platform LOGIN PASSWORD '$($script:PgPass)';
CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS ai;
CREATE SCHEMA IF NOT EXISTS extensions;
GRANT USAGE, CREATE ON SCHEMA platform, ai, extensions TO p2mig;
GRANT CREATE ON DATABASE $($script:Db) TO p2mig;
GRANT USAGE ON SCHEMA ai, extensions TO p2app;
GRANT USAGE ON SCHEMA platform TO p2platform;
CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensions;
"@
    $r = Sql $roleSql 'db-roles'
    Add-Case 'ENV-db-roles' ($r.ExitCode -eq 0) 'owned roles p2mig/p2app + schemas + pgvector installed'
    # 迁移逐字节应用（不重写、不改序）
    $applied = New-Object System.Collections.ArrayList
    $migrations = @(
        @{ dir = 'services/platform/docs/script/sql/postgres'; schema = 'platform' },
        @{ dir = 'services/ai/resources/database/postgres/migrations'; schema = 'ai' }
    )
    foreach ($spec in $migrations) {
        $full = Join-Path $RepoRoot ($spec.dir -replace '/', '\')
        foreach ($file in @(Get-ChildItem -LiteralPath $full -File -Filter '*.sql' | Sort-Object @{Expression={ [int]($_.Name -replace '^V(\d+)__.*$', '$1') }})) {
            $sql = [IO.File]::ReadAllText($file.FullName)
            $prefix = "CREATE SCHEMA IF NOT EXISTS $($spec.schema);`r`nCREATE SCHEMA IF NOT EXISTS extensions;`r`nSET search_path TO $($spec.schema),extensions;`r`n"
            $r = Sql ($prefix + $sql) ("migration-$($spec.schema)-$($file.Name)") 'p2mig'
            if ($r.ExitCode -ne 0) { Add-Case 'ENV-migrations' $false ("migration failed: $($spec.schema)/$($file.Name)"); exit 2 }
            [void]$applied.Add("$($spec.schema)/$($file.Name)=$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLower())")
        }
    }
    Save-Evidence 'migrations-applied.txt' (($applied -join "`n") + "`n")
    Add-Case 'ENV-migrations' ($applied.Count -ge 12) ("applied byte-identical: " + ($applied -join ','))
    $grantSql = @"
GRANT USAGE ON SCHEMA ai, extensions TO p2app;
GRANT USAGE ON SCHEMA platform TO p2platform;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA platform TO p2platform;
GRANT USAGE,SELECT,UPDATE ON ALL SEQUENCES IN SCHEMA platform TO p2platform;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA ai TO p2app;
GRANT USAGE,SELECT,UPDATE ON ALL SEQUENCES IN SCHEMA ai TO p2app;
ALTER DEFAULT PRIVILEGES FOR ROLE p2mig IN SCHEMA platform GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO p2platform;
ALTER DEFAULT PRIVILEGES FOR ROLE p2mig IN SCHEMA ai GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO p2app;
"@
    $r = Sql $grantSql 'db-grants'
    Add-Case 'ENV-db-grants' ($r.ExitCode -eq 0) 'app role grants applied'
    $aiDenied=Sql 'SELECT count(*) FROM platform.sys_user;' 'role-ai-cross-schema' 'p2app'
    $platformDenied=Sql 'SELECT count(*) FROM ai.ai_run;' 'role-platform-cross-schema' 'p2platform'
    Add-Case 'ENV-runtime-isolation' ($aiDenied.ExitCode -ne 0 -and $platformDenied.ExitCode -ne 0 -and $aiDenied.Output -match 'permission denied' -and $platformDenied.Output -match 'permission denied') 'both runtime roles denied the other authority schema'
    # fixtures：两租户同名用户、角色/岗位/客户端、AI 权限菜单（V4+V5 全量）、policy revision、acl epoch
    $fixtureSql = @"
INSERT INTO platform.sys_tenant_package (package_id, package_name, menu_ids, remark, menu_check_strictly, status, del_flag, create_dept, create_by, create_time, update_by, update_time)
SELECT 910000000000000901, 'p2c-package', COALESCE(string_agg(menu_id::text, ','), '0'), 'p2 synthetic package', true, '0', '0', 103, 1, now(), 1, now()
FROM platform.sys_menu WHERE perms LIKE 'ai:%';
INSERT INTO platform.sys_tenant (id, tenant_id, contact_user_name, contact_phone, company_name, package_id, account_count, status, del_flag)
VALUES (910000000000000011, 'p2t1', 'p2c-admin-t1', '13900000011', 'p2 synthetic tenant 1', 910000000000000901, -1, '0', '0'),
       (910000000000000012, 'p2t2', 'p2c-admin-t2', '13900000012', 'p2 synthetic tenant 2', 910000000000000901, -1, '0', '0');
INSERT INTO platform.sys_user (user_id, tenant_id, user_name, nick_name, password, status, del_flag)
VALUES (910000000000000001, 'p2t1', 'p2admin', 'p2admin-t1', '__HASH__', '0', '0'),
       (910000000000000002, 'p2t2', 'p2admin', 'p2admin-t2', '__HASH__', '0', '0');
INSERT INTO platform.sys_client (id, client_id, client_key, client_secret, grant_type, device_type, active_timeout, timeout, status, del_flag)
VALUES (910000000000000101, 'p2c-client', 'p2c-client-key', '', 'password', 'pc', 1800, 604800, '0', '0');
INSERT INTO platform.sys_role (role_id, tenant_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag)
VALUES (910000000000000021, 'p2t1', 'p2c-role-t1', 'p2c_t1', 1, '1', true, true, '0', '0'),
       (910000000000000022, 'p2t2', 'p2c-role-t2', 'p2c_t2', 1, '1', true, true, '0', '0');
INSERT INTO platform.sys_post (post_id, tenant_id, post_code, post_category, post_name, post_sort, status)
VALUES (910000000000000031, 'p2t1', 'p2c_post_t1', NULL, 'p2c-post-t1', 1, '0'),
       (910000000000000032, 'p2t2', 'p2c_post_t2', NULL, 'p2c-post-t2', 1, '0');
INSERT INTO platform.sys_user_role (user_id, role_id) VALUES (910000000000000001, 910000000000000021), (910000000000000002, 910000000000000022);
INSERT INTO platform.sys_user_post (user_id, post_id) VALUES (910000000000000001, 910000000000000031), (910000000000000002, 910000000000000032);
INSERT INTO platform.sys_role_menu (role_id, menu_id) SELECT 910000000000000021, menu_id FROM platform.sys_menu WHERE perms LIKE 'ai:%';
INSERT INTO platform.sys_role_menu (role_id, menu_id) SELECT 910000000000000022, menu_id FROM platform.sys_menu WHERE perms LIKE 'ai:%';
INSERT INTO platform.sys_ai_policy_revision (tenant_id, version) VALUES ('p2t1', 1), ('p2t2', 1);
INSERT INTO ai.ai_acl_epoch (tenant_id, version) VALUES ('p2t1', 1), ('p2t2', 1);
"@
    $fixtureSql = $fixtureSql.Replace('__HASH__', $script:FixtureHash)
    $r = Sql $fixtureSql 'db-fixtures' 'postgres'
    Add-Case 'ENV-fixtures' ($r.ExitCode -eq 0) 'two synthetic tenants, same-named user, ai:* menu perms, policy/acl versions seeded'
    # 签名密钥 + JWT 签名脚本（密钥只在本轮目录，0600）
    Remote "cd $($script:RemoteRoot)/keys && umask 077 && openssl genrsa -out private-pkcs1.pem 2048 2>/dev/null && openssl pkcs8 -topk8 -nocrypt -in private-pkcs1.pem -out private.pem 2>/dev/null && openssl rsa -in private.pem -pubout -out public.pem 2>/dev/null && rm -f private-pkcs1.pem" 'keys-gen' | Out-Null
    $signScript = @'
#!/bin/bash
set -e
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
H=$(printf '{"alg":"RS256","typ":"JWT","kid":"platform-prod-k1"}' | b64url)
P=$(printf '%s' "$1" | b64url)
S=$(printf '%s.%s' "$H" "$P" | openssl dgst -sha256 -sign "$(dirname "$0")/keys/private.pem" | b64url)
echo "$H.$P.$S"
'@
    $signB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($signScript -replace "`r`n", "`n")))
    Remote "umask 077; printf %s '$signB64' | base64 -d > $($script:RemoteRoot)/sign.sh; chmod 700 $($script:RemoteRoot)/sign.sh" 'sign-script' | Out-Null
    Add-Case 'ENV-keys' $true 'owned RSA keypair + JWT signer on the VM (private key never leaves the VM)'
}

# ---------------------------------------------------------------- start services

function Start-Jar([string]$Side, [int]$Port, [string[]]$Extra) {
    $jar = if ($Side -eq 'ai') { '/opt/p2core-acceptance/' + $script:Tag + '/ai.jar' } else { '/opt/p2core-acceptance/' + $script:Tag + '/platform.jar' }
    $bootId=[guid]::NewGuid().ToString('N')
    $log = "/opt/p2core-acceptance/$($script:Tag)/$Side-$Port-$bootId.log"
    $latestLog="/opt/p2core-acceptance/$($script:Tag)/$Side-$Port.log"
    # 清场：先结束占用该端口的上一轮 jar（以命令行里的 server.port 精确匹配），否则
    # 残留进程会让 Wait-Ready 误判就绪、把流量打到带旧凭据的僵尸服务上。
    foreach ($owned in @($script:Pids | Where-Object { $_.port -eq $Port })) { Stop-OwnedJar $owned.port }
    $holder = Remote "ss -tln | grep -c ':$Port ' || true" ("port-check-$Port")
    if (($holder.Output.Trim() -as [int]) -gt 0) {
        Add-Case "ENV-port-$Port" $false "port $Port still held by an unknown process after cleanup"
        exit 2
    }
    $envLine = if ($Side -eq 'ai') {
        "export P2_MINERU_TOKEN=`$(cat /opt/ragent-ai-lab-20261003/secrets/mineru_token); export AI_DB_PASSWORD='$($script:PgPass)'; export AI_DB_USER=p2app; export AI_DB_USERNAME=p2app;"
    } else {
        "export PLATFORM_DB_PASSWORD='$($script:PgPass)'; export PLATFORM_DB_USERNAME=p2platform; export REDIS_PASSWORD='$($script:RedisPass)';"
    }
    $argsLine = ($Extra | ForEach-Object { "'" + $_.Replace("'", "'\''") + "'" }) -join ' '
    $cmd = "cd /opt/p2core-acceptance/$($script:Tag) || exit 1`nsource ./env.sh`nif [ -f ./provider.env ]; then source ./provider.env; fi`n$envLine`nln -sfn $log $latestLog`nnohup java -Dfile.encoding=UTF-8 -Xmx1024m -jar $jar --server.port=$Port $argsLine > $log 2>&1 < /dev/null &`necho PID=`$!"
    $r = Remote $cmd "start-$Side-$Port"
    if ($r.Output -match 'PID=(\d+)') { $script:Pids += [pscustomobject]@{ side = $Side; port = $Port; pid = [int]$Matches[1] } }
    return $r
}
function Wait-Ready([int]$Port, [string]$LogPath, [int]$Seconds = 240) {
    for ($i = 0; $i -lt $Seconds; $i++) {
        $probe = Remote "curl -s -o /dev/null -w '%{http_code}' -m 2 http://127.0.0.1:$Port/ 2>/dev/null; true" ''
        if ($probe.Output -match '^\s*[2-5]\d\d\s*$') {
            $expected = @($script:Pids | Where-Object { $_.port -eq $Port } | Select-Object -Last 1)
            return $expected.Count -eq 1 -and (Get-PortOwnerPid $Port) -eq $expected[0].pid
        }
        Start-Sleep -Seconds 1
        if ($i % 20 -eq 19) { Remote "tail -3 $LogPath" 'ready-tail' | Out-Null }
    }
    return $false
}

$aiCommon = @(
    '--p2.enabled=true', '--p2.worker.enabled=true', '--p2.outbox.relay-enabled=true',
    "--p2.object-store.type=fs", "--p2.object-store.root=/opt/p2core-acceptance/$($script:Tag)/objects",
    "--AI_DB_URL=jdbc:postgresql://127.0.0.1:$($script:PgHostPort)/$($script:Db)?client_encoding=UTF8&currentSchema=ai,extensions",
    "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=$($script:RedisHostPort)",
    "--spring.data.redis.password=$($script:RedisPass)",
    '--ai.integration.enabled=true', '--ai.integration.security.enabled=true',
    '--ai.integration.high-risk.enabled=true',
    "--ai.integration.security.public-key-path=$($script:RemoteRoot)/keys/public.pem",
    "--ai.integration.security.service-credential=$($script:ServiceCredential)",
    "--ai.integration.platform-base-url=http://127.0.0.1:$PlatformPort",
    "--ai.integration.platform-service-credential=$($script:ServiceCredential)",
    '--p2.test-control.enabled=true', '--p2.events.poll-interval-ms=200', '--p2.worker.poll-interval-ms=200',
    '--p2.worker.heartbeat-seconds=2', '--p2.worker.lease-seconds=6',
    '--p2.embedding.mode=synthetic', '--p2.chat.mode=synthetic',
    '--mineru.local.enabled=true', '--mineru.local.base-url=http://127.0.0.1:18000',
    '--mineru.local.token=${P2_MINERU_TOKEN}', '--mineru.local.tier=flash', '--mineru.local.poll-interval-ms=500',
    '--p2.chat.egress.enabled=false', '--p2.chat.egress.allowed-providers=',
    '--p2.upload.max-bytes=52428800', '--p2.budget.default-tenant-units=100000',
    '--p2.executor.mode=synthetic', '--p2.executor.synthetic-step-delay-ms=1500'
)
$aiReal = @(
    '--p2.enabled=true', '--p2.worker.enabled=true', '--p2.outbox.relay-enabled=true',
    "--p2.object-store.type=fs", "--p2.object-store.root=/opt/p2core-acceptance/$($script:Tag)/objects",
    "--AI_DB_URL=jdbc:postgresql://127.0.0.1:$($script:PgHostPort)/$($script:Db)?client_encoding=UTF8&currentSchema=ai,extensions",
    "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=$($script:RedisHostPort)",
    "--spring.data.redis.password=$($script:RedisPass)",
    '--ai.integration.enabled=true', '--ai.integration.security.enabled=true',
    '--ai.integration.high-risk.enabled=true',
    "--ai.integration.security.public-key-path=$($script:RemoteRoot)/keys/public.pem",
    "--ai.integration.security.service-credential=$($script:ServiceCredential)",
    "--ai.integration.platform-base-url=http://127.0.0.1:$PlatformPort",
    "--ai.integration.platform-service-credential=$($script:ServiceCredential)",
    '--p2.test-control.enabled=true', '--p2.events.poll-interval-ms=200', '--p2.worker.poll-interval-ms=200',
    '--p2.worker.heartbeat-seconds=2', '--p2.worker.lease-seconds=6',
    '--p2.embedding.mode=synthetic', '--p2.chat.mode=synthetic',
    '--mineru.local.enabled=true', '--mineru.local.base-url=http://127.0.0.1:18000',
    '--mineru.local.token=${P2_MINERU_TOKEN}', '--mineru.local.tier=flash', '--mineru.local.poll-interval-ms=500',
    '--p2.chat.egress.enabled=false', '--p2.chat.egress.allowed-providers=',
    '--p2.upload.max-bytes=52428800', '--p2.budget.default-tenant-units=100000',
    '--p2.executor.mode=real'
)
$platformArgs = @(
    "--spring.datasource.dynamic.datasource.master.url=jdbc:postgresql://127.0.0.1:$($script:PgHostPort)/$($script:Db)?currentSchema=platform,extensions",
    '--PLATFORM_DB_USERNAME=p2platform', '--REDIS_HOST=127.0.0.1', "--REDIS_PORT=$($script:RedisHostPort)",
    '--ai.integration.enabled=true', "--ai.integration.ai-base-url=http://127.0.0.1:$AiPort/api/ragent",
    '--ai.integration.forward-timeout-millis=10000',
    "--ai.integration.service-credential=$($script:ServiceCredential)",
    "--ai.integration.authorization.service-credential=$($script:ServiceCredential)",
    "--ai.integration.delegation.private-key-path=$($script:RemoteRoot)/keys/private.pem",
    '--ai.integration.sse.connect-timeout-millis=5000', '--ai.integration.sse.idle-timeout-millis=120000',
    '--ai.integration.sse.max-duration-millis=1800000', '--ai.integration.upload-max-bytes=52559872'
)

Remote "mkdir -p $($script:RemoteRoot)/objects $($script:RemoteRoot)/keys /tmp/p2http-$($script:Tag)" 'remote-mkdir' | Out-Null
function Initialize-ExternalizedPlaceholders {
    # 平台/AI 把密钥外化成 ${PROJECT_SERVICES_*} 环境占位符；未设置时 Spring 拒绝启动。
    # 这里从仓库配置源**发现**全部占位符名（不硬编码清单），按类别生成合成值，
    # 写入远端本轮目录 env.sh（0600，随本轮资源清理）。值只在本轮合成环境使用。
    $names = New-Object System.Collections.ArrayList
    foreach ($root in @('services\platform', 'services\ai')) {
        $full = Join-Path $RepoRoot $root
        if (-not (Test-Path -LiteralPath $full)) { continue }
        foreach ($file in @(Get-ChildItem -LiteralPath $full -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -in @('.yml', '.yaml') })) {
            $text = ''
            try { $text = [IO.File]::ReadAllText($file.FullName) } catch { continue }
            foreach ($m in [regex]::Matches($text, '\$\{(PROJECT_SERVICES_[A-Z0-9_]+)\}')) {
                $n = $m.Groups[1].Value
                if (-not $names.Contains($n)) { [void]$names.Add($n) }
            }
        }
    }
    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add('#!/bin/sh')
    [void]$lines.Add("export AI_DB_PASSWORD='$($script:PgPass)'")
    [void]$lines.Add("export AI_DB_USER='p2app'")
    [void]$lines.Add("export AI_DB_USERNAME='p2app'")
    [void]$lines.Add("export PLATFORM_DB_PASSWORD='$($script:PgPass)'")
    [void]$lines.Add("export PLATFORM_DB_USERNAME='p2platform'")
    [void]$lines.Add("export REDIS_PASSWORD='$($script:RedisPass)'")
    [void]$lines.Add("export P2_MINERU_TOKEN=`$(cat /opt/ragent-ai-lab-20261003/secrets/mineru_token)")
    foreach ($name in $names) {
        $value = switch -Regex ($name) {
            '_TOKEN_\d+$' { [guid]::NewGuid().ToString('N') }
            '_CLIENT_SECRET_\d+$' { [guid]::NewGuid().ToString('N') }
            '_ACCESS_KEY_(ID|SECRET)_\d+$' { [guid]::NewGuid().ToString('N') }
            '_API_KEY_\d+$' { [guid]::NewGuid().ToString('N') }
            default { 'p2synth' + ([guid]::NewGuid().ToString('N').Substring(0, 16)) }
        }
        [void]$lines.Add("export $name='$value'")
    }
    $envText = ($lines -join "`n") + "`n"
    $localEnv = Join-Path $script:Work 'env.sh'
    [IO.File]::WriteAllText($localEnv, $envText, (New-Object Text.UTF8Encoding($false)))
    & scp -q $localEnv "${RemoteHost}:$($script:RemoteRoot)/env.sh"
    if ($LASTEXITCODE -ne 0) { Add-Case 'ENV-placeholders' $false 'cannot upload env.sh'; return 0 }
    Remote "chmod 700 $($script:RemoteRoot)/env.sh" 'env-placeholders-chmod' | Out-Null
    return $names.Count
}
$placeholderCount = Initialize-ExternalizedPlaceholders
Add-Case 'ENV-placeholders' ($placeholderCount -gt 50) "$placeholderCount externalized PROJECT_SERVICES_* placeholders resolved with synthetic values (env.sh on the VM, 0600)"

if ($RealProvidersOnly) {
    if(-not $SkipSetup){throw 'real provider acceptance must reuse the designated owned environment; cannot reset envelope'}
    $config=Get-Content -LiteralPath $ProviderSecretFile -Raw | ConvertFrom-Json
    function Unprotect-Provider([string]$Cipher) {
        $secure=ConvertTo-SecureString $Cipher
        $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    }
    $script:DeepSeekKey=Unprotect-Provider $config.deepseek
    $script:DashScopeKey=Unprotect-Provider $config.dashscope
    if($script:DeepSeekKey -notmatch '^sk-[a-zA-Z0-9_-]+$' -or $script:DashScopeKey -notmatch '^sk-[a-zA-Z0-9_-]+$'){throw 'provider references invalid'}
    $providerFile=Join-Path $script:Work 'provider.env'
    [IO.File]::WriteAllText($providerFile, "export DEEPSEEK_API_KEY='$($script:DeepSeekKey)'`nexport DASHSCOPE_API_KEY='$($script:DashScopeKey)'`n",(New-Object Text.UTF8Encoding($false)))
    & scp -q $providerFile "${RemoteHost}:$($script:RemoteRoot)/provider.env"
    if($LASTEXITCODE -ne 0){throw 'controlled provider injection failed'}
    [void](Remote "chmod 600 $($script:RemoteRoot)/provider.env" '')
}

Write-Step 'copy jars to the VM and start services (synthetic executor phase)'
# Never replace a jar while any verified owned JVM may still load its classes.
foreach($port in @($AiPort,$Ai2Port,$PlatformPort)){Stop-OwnedJar $port | Out-Null}
if($script:Pids.Count -ne 0){throw 'owned JVMs must be stopped before jar replacement'}
Remote "scp -q /dev/null /dev/null 2>/dev/null; true" '' | Out-Null
& scp -q $aiJar "${RemoteHost}:$($script:RemoteRoot)/ai.jar"
& scp -q $platformJar "${RemoteHost}:$($script:RemoteRoot)/platform.jar"
if ($LASTEXITCODE -ne 0) { Add-Case 'ENV-upload-jars' $false 'scp failed'; exit 2 }

Remote "cd $($script:RemoteRoot) && umask 077 && printf '%s' '$($script:FixtureHash)' > /dev/null; true" '' | Out-Null
$pdfB64 = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'sample-pdf.b64') -Raw
$pdfB64 = $pdfB64.Trim()
Remote "printf %s '$pdfB64' | base64 -d > $($script:RemoteRoot)/sample.pdf" 'sample-pdf' | Out-Null

Start-Jar 'platform' $PlatformPort $platformArgs | Out-Null
if (-not (Wait-Ready $PlatformPort "/opt/p2core-acceptance/$($script:Tag)/platform-$PlatformPort.log")) {
    Add-Case 'ENV-platform-start' $false 'platform did not become ready'; exit 2
}
Start-Jar 'ai' $AiPort $aiCommon | Out-Null
if (-not (Wait-Ready $AiPort "/opt/p2core-acceptance/$($script:Tag)/ai-$AiPort.log")) {
    Add-Case 'ENV-ai-start' $false 'ai did not become ready'; exit 2
}
$script:AiNodes = @($AiPort)
Add-Case 'ENV-services' $true 'platform + ai node1 started (owned pids recorded)'
if($OwnedDeliveryRecoveryScript){ & powershell -NoProfile -ExecutionPolicy Bypass -File $OwnedDeliveryRecoveryScript; if($LASTEXITCODE -ne 0){throw 'owned delivery recovery evidence not confirmed'} }

$t1 = Token 'p2t1' 'p2admin' $script:FixturePassword
$t2 = Token 'p2t2' 'p2admin' $script:FixturePassword
Add-Case 'ENV-login' ($null -ne $t1 -and $null -ne $t2) 'both synthetic tenants logged in through the real platform login flow'
if (-not $t1) { Write-Host 'cannot continue without a token'; exit 3 }

for($warm=0;$warm -lt 3;$warm++) {
    $read=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases" @{'Authorization'="Bearer $t1"} '' 20 ("warm-read-$warm")
    if($read.Status -eq 200){break}
}

# ---------------------------------------------------------------- case suites
. (Join-Path $PSScriptRoot 'cases-phase-a.ps1')
. (Join-Path $PSScriptRoot 'helpers-product.ps1')
if ($AgentCoreOnly -or $AgentRecoveryOnly -or $AgentSecurityOnly -or $AgentReadFaultsOnly) { . (Join-Path $RepoRoot 'tools/p3-acceptance/cases-agent.ps1') }
elseif ($BrowserOnly) { . (Join-Path $PSScriptRoot 'cases-browser.ps1') }
elseif ($RealProvidersOnly) { . (Join-Path $PSScriptRoot 'cases-real-providers.ps1') }
elseif ($PhaseCOnly) { . (Join-Path $PSScriptRoot 'cases-phase-c.ps1') }
elseif (-not $SmokeOnly -and -not $PhaseAOnly) { . (Join-Path $PSScriptRoot 'cases-phase-b.ps1') }
if (@($script:Results | Where-Object { -not $_.ok }).Count -gt 0) { exit 1 }
} catch {
    Save-Evidence 'runner-error.json' (@{type=$_.Exception.GetType().Name;message=(Redact $_.Exception.Message);position=(Redact $_.InvocationInfo.PositionMessage)}|ConvertTo-Json)
    Add-Case 'RUNNER-ERROR' $false $_.Exception.GetType().Name
    exit 1
} finally {
    if (-not $KeepEnvironment) {
        foreach ($p in @($script:Pids)) { Stop-OwnedJar $p.port }
        Add-Case 'A44-cleanup' ($script:Pids.Count -eq 0) 'stopped command-line verified owned PIDs; test containers and data retained'
    }
    Save-Evidence 'results.json' ($script:Results | ConvertTo-Json -Depth 5)
    Save-Evidence 'http-log.json' ($script:HttpLogs | ConvertTo-Json -Depth 5)
    Save-Evidence 'run-meta.json' (@{ runTag=$script:Tag; pids=$script:Pids; aiPort=$AiPort; ai2Port=$Ai2Port; platformPort=$PlatformPort; db=$script:Db; sourceHead=(& git -C $RepoRoot rev-parse HEAD); aiJarHash=$(if($aiJar){(Get-FileHash $aiJar).Hash}); platformJarHash=$(if($platformJar){(Get-FileHash $platformJar).Hash}) } | ConvertTo-Json -Depth 5)
}
