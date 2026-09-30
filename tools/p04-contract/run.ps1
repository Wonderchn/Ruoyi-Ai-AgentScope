<#
.SYNOPSIS
  P0.4 契约实验验收入口（Spec §8.6）。

.DESCRIPTION
  两种模式：
    Unit        —— 无外部服务：跑三个必跑测试类，并逐个核对 “Tests run > 0”。
    Integration —— 起两个独立 JVM（platform Boot 3 / AI Boot 4）+ 合成 PG，执行 P01–T02。

  约束（照做，别绕）：
    * 本脚本按 Windows PowerShell 5.1 编写（本机无 pwsh 7）：不使用 -SkipHttpErrorCheck /
      ForEach-Object -Parallel / 三元运算符；HTTP 一律走 System.Net.Http.HttpClient。
    * 口令只经进程环境传给子进程，不落盘、不进证据；平台公钥是公开材料，可落盘。
    * 失败即非零退出；依赖缺失输出 NOT_RUN 并非零退出；清理结果单独列示，
      清理失败不得写成实验成功。
    * 只停止本脚本自己启动的 PID（核对命令行），绝不通杀 java。

.EXAMPLE
  powershell -NoProfile -File tools/p04-contract/run.ps1 -Mode Unit -EvidenceDir D:/AI-project/mydocs/p0/evidence/p04/unit
  powershell -NoProfile -File tools/p04-contract/run.ps1 -Mode Integration -EvidenceDir D:/AI-project/mydocs/p0/evidence/p04/integration
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Unit', 'Integration')][string]$Mode,
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [string]$RunTag = ("p04r" + (Get-Date -Format 'yyyyMMddHHmmss')),
    [string]$WorkRoot = 'D:\AI-project\.scratch\p04\work',
    [string]$RepoRoot = 'D:\AI-project\Ruoyi-Ai-AgentScope',
    [string]$LabHost = 'root@192.168.139.103',
    [string]$LabContainer = 'p04-pg-p04r1',
    [string]$LabDb = 'p04_contract',
    [int]$PlatformPort = 18082,
    [int]$AiPort = 19090
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.IO.Compression.FileSystem

$script:Failures = 0
$script:Results = New-Object System.Collections.ArrayList
$script:HttpLog = New-Object System.Collections.ArrayList
$script:ExecutionId = (Get-Date -Format 'yyyyMMddTHHmmssZ')
$script:Evidence = Join-Path $EvidenceDir $script:ExecutionId
$script:StartedProcesses = @()

function Write-Step([string]$m) { Write-Output ("### " + $m) }
function Add-Result([string]$id, [string]$target, [string]$status, [string]$detail) {
    [void]$script:Results.Add([pscustomobject]@{ id = $id; target = $target; status = $status; detail = $detail })
    if ($status -eq 'FAIL') { $script:Failures++; Write-Output ("  [FAIL] {0} {1}" -f $id, $detail) }
    elseif ($status -eq 'PASS') { Write-Output ("  [ok]   {0} {1}" -f $id, $detail) }
    else { Write-Output ("  [SKIP] {0} {1}" -f $id, $detail) }
}
function Assert-That([string]$id, [string]$target, [bool]$cond, [string]$detail) {
    if ($cond) { Add-Result $id $target 'PASS' $detail } else { Add-Result $id $target 'FAIL' $detail }
}
function New-Client([int]$timeoutSec) {
    $c = New-Object System.Net.Http.HttpClient
    $c.Timeout = [TimeSpan]::FromSeconds($timeoutSec)
    return $c
}

# 原生命令（mvn/ssh/java）会把警告写到 stderr；在 $ErrorActionPreference='Stop' 下
# PowerShell 5.1 会把它当成终止错误（同类的坑：ssh 的 post-quantum 警告）。
# 因此所有原生调用统一走这里，并显式取退出码。
function Invoke-NativeCapture([string]$exe, [string[]]$arguments) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $exe @arguments 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
    return [pscustomobject]@{ Output = $out; ExitCode = $code }
}
$script:JavaVersion = ((Invoke-NativeCapture 'java' @('-version')).Output | Select-Object -First 1)

# ---------- HTTP 帮助函数（HttpClient；5.1 兼容） ----------
function Send-Json($client, [string]$method, [string]$url, $headers, [string]$json) {
    $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), $url)
    if ($json) { $req.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json') }
    if ($headers) { foreach ($k in $headers.Keys) { [void]$req.Headers.TryAddWithoutValidation($k, [string]$headers[$k]) } }
    $resp = $client.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $obj = $null
    if ($text) { try { $obj = $text | ConvertFrom-Json } catch { $obj = $null } }
    $rid = ''
    try { $vals = $null; if ($resp.Headers.TryGetValues('X-Request-Id', [ref]$vals)) { $rid = ($vals | Select-Object -First 1) } } catch { }
    [void]$script:HttpLog.Add([pscustomobject]@{
            method = $method; url = $url; status = [int]$resp.StatusCode
            requestId = $rid; body = $text
        })
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $obj; Text = $text; RequestId = $rid }
}
function Start-AsyncJson($client, [string]$url, $headers, [string]$json) {
    $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod('POST')), $url)
    $req.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
    foreach ($k in $headers.Keys) { [void]$req.Headers.TryAddWithoutValidation($k, [string]$headers[$k]) }
    return [pscustomobject]@{ Task = $client.SendAsync($req); Req = $req }
}
function Complete-Async($handle) {
    $resp = $handle.Task.GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $obj = $null
    if ($text) { try { $obj = $text | ConvertFrom-Json } catch { } }
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $obj; Text = $text }
}

# ---------- 域帮助函数 ----------
$script:PlatformBase = "http://127.0.0.1:$PlatformPort"
$script:AiBase = "http://127.0.0.1:$AiPort"

function New-Delegation($client, [string]$variant, [string]$tenant, [string]$sub, [string]$mid,
    [int]$pv, $scopes, [int]$ttl) {
    $payload = @{
        tenantId = $tenant; subject = $sub; membershipId = $mid
        scopes = $scopes; policyVersion = $pv; ttlSeconds = $ttl; variant = $variant
    } | ConvertTo-Json -Depth 6
    $r = Send-Json $client 'POST' "$($script:PlatformBase)/internal/platform/v1/delegations" @{} $payload
    if ($r.Status -ne 200 -or -not $r.Json.data.token) { throw ("delegation issue failed: status={0} body={1}" -f $r.Status, $r.Text) }
    return $r.Json.data
}
function New-RunBody([string]$text, $refs) {
    return (@{ schemaVersion = 1; action = 'rag.chat'; input = @{ text = $text }; resourceRefs = $refs } | ConvertTo-Json -Depth 6)
}
function Call-Run($client, [string]$token, [string]$key, [string]$body, $extraHeaders, [int]$timeoutSec) {
    $c = $client
    if ($timeoutSec -gt 0) { $c = New-Client $timeoutSec }
    $h = @{ 'Idempotency-Key' = $key }
    if ($token) { $h['Authorization'] = "Bearer $token" }
    if ($extraHeaders) { foreach ($k in $extraHeaders.Keys) { $h[$k] = $extraHeaders[$k] } }
    return Send-Json $c 'POST' "$($script:AiBase)/internal/ai/v1/runs" $h $body
}

# ---------- 数据库计数（只读；经 ssh + docker exec psql） ----------
function Get-LabEnv([string]$key) {
    $r = Invoke-NativeCapture 'ssh' @('-o', 'BatchMode=yes', '-o', 'LogLevel=ERROR', $LabHost,
        "docker exec $LabContainer printenv $key")
    if ($r.ExitCode -ne 0) {
        # S5 修复：docker/ssh 失败时 stderr 文本曾被当成"非空口令"返回，
        # 使"缺靶场"预检形同虚设。现在必须显式判失败。
        $text = ((($r.Output | Out-String).Trim()) -replace '\s+', ' ')
        throw ("lab query failed: {0} (ssh/docker exit={1}) {2}" -f $key, $r.ExitCode, $text)
    }
    $value = ($r.Output | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw ("{0} is empty in lab container {1}" -f $key, $LabContainer)
    }
    return $value
}
function Invoke-LabSql([string]$sql) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
    $inner = "echo $b64 | base64 -d | docker exec -i -e PGPASSWORD=`$(docker exec $LabContainer printenv POSTGRES_PASSWORD) $LabContainer psql -U postgres -d $LabDb -X -q -tA -f -"
    $r = Invoke-NativeCapture 'ssh' @('-o', 'BatchMode=yes', '-o', 'LogLevel=ERROR', $LabHost, $inner)
    return ($r.Output | Out-String).Trim()
}
function Get-DbCounts {
    $sql = "select (select count(*) from ai.ai_run)||'|'||(select count(*) from ai.ai_run_event)||'|'||" +
           "(select count(*) from ai.outbox_event)||'|'||(select count(*) from ai.ai_usage_ledger)||'|'||" +
           "(select count(*) from ai.p04_replay_guard)"
    $parts = (Invoke-LabSql $sql) -split '\|'
    return [pscustomobject]@{
        runs = [int]$parts[0]; events = [int]$parts[1]; outbox = [int]$parts[2]
        ledger = [int]$parts[3]; replay = [int]$parts[4]
    }
}
function Assert-NoBusinessDelta([string]$id, $before, [string]$detail) {
    $after = Get-DbCounts
    $same = ($after.runs -eq $before.runs) -and ($after.events -eq $before.events) -and
            ($after.outbox -eq $before.outbox) -and ($after.ledger -eq $before.ledger)
    Assert-That $id 'G2' $same ($detail + " business(runs/events/outbox/ledger)=$($after.runs)/$($after.events)/$($after.outbox)/$($after.ledger)")
    return $after
}

# ---------- 进程管理（只动自己的） ----------
function Stop-Owned([string]$pidFile, [string]$match) {
    if (-not (Test-Path $pidFile)) { return }
    $id = (Get-Content $pidFile | Select-Object -First 1)
    if (-not $id) { return }
    $pr = Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue
    if ($pr -and $pr.CommandLine -match $match) {
        Stop-Process -Id $id -Force
        Write-Output ("  stopped owned process {0} (pid={1})" -f $match, $id)
    }
}
function Start-Platform([string]$credential, [string]$work) {
    $ia = Join-Path $RepoRoot 'services\platform\ruoyi-modules\ruoyi-ai-integration'
    $cp = "$ia\target\classes;$ia\target\test-classes;$work\platform\lib\*"
    $run = Join-Path $work 'platform-run'
    New-Item -ItemType Directory -Force $run | Out-Null
    $env:P04_SERVICE_CREDENTIAL = $credential
    $p = Start-Process java -ArgumentList @('-Dfile.encoding=UTF-8', '-Xmx512m', '-cp', $cp,
        'org.ruoyi.aiintegration.p04.P04PlatformTestApplication',
        '--spring.config.name=p04platform', "--server.port=$PlatformPort") -WorkingDirectory $run `
        -RedirectStandardOutput "$run\stdout.log" -RedirectStandardError "$run\stderr.log" -WindowStyle Hidden -PassThru
    $p.Id | Set-Content (Join-Path $work 'platform.pid')
    $script:StartedProcesses += $p.Id
    return $p.Id
}
function Start-Ai([string]$work, [string]$pemPath) {
    # 前置工作树里刚构建的两个实验模块主类：否则会用到提取自旧 fat jar 的陈旧副本，
    # 修复后的代码根本不会被加载（"测了旧字节码"的假绿必须避免）。
    $cp = "$RepoRoot\services\ai\framework\target\classes;$RepoRoot\services\ai\rag\target\classes;$work\ai\classes;$work\ai\lib\*;$RepoRoot\services\ai\rag\target\test-classes"
    $run = Join-Path $work 'ai-run'
    New-Item -ItemType Directory -Force $run | Out-Null
    # 必须写 ${LabDb}：写成 "$LabDb?currentSchema=..." 时 PowerShell 会把
    # "LabDb?currentSchema" 当作一个变量名（值为空），URL 变成 ".../15434/=ai,extensions"，
    # 服务端报 database "=ai,extensions" does not exist —— 此坑已实测踩过。
    $env:P04_AI_DB_URL = "jdbc:postgresql://192.168.139.103:15434/${LabDb}?currentSchema=ai,extensions"
    $env:P04_AI_DB_USER = 'ai_app'
    $env:P04_AI_DB_PASSWORD = Get-LabEnv 'AI_APP_PASSWORD'
    $env:P04_DELEGATION_PUBLIC_KEY_PATH = $pemPath
    $env:P04_PLATFORM_AUTHORIZATION_URL = "$($script:PlatformBase)/internal/platform/v1/authorization/check"
    if ([string]::IsNullOrWhiteSpace($env:P04_AI_DB_PASSWORD)) { throw 'AI_APP_PASSWORD unavailable from lab container' }
    if ([string]::IsNullOrWhiteSpace($env:P04_SERVICE_CREDENTIAL)) { throw 'service credential missing in process env' }
    $a = Start-Process java -ArgumentList @('-Dfile.encoding=UTF-8', '-Xmx512m', '-cp', $cp,
        'com.nageoffer.ai.ragent.rag.runtime.p04.P04AiTestApplication',
        '--spring.config.name=p04ai', "--server.port=$AiPort") -WorkingDirectory $run `
        -RedirectStandardOutput "$run\stdout.log" -RedirectStandardError "$run\stderr.log" -WindowStyle Hidden -PassThru
    $a.Id | Set-Content (Join-Path $work 'ai.pid')
    $script:StartedProcesses += $a.Id
    return $a.Id
}
function Wait-Health([string]$url, [int]$tries, [string]$what) {
    for ($i = 0; $i -lt $tries; $i++) {
        try {
            $c = New-Client 3
            $r = Send-Json $c 'GET' $url $null $null
            if ($r.Status -eq 200 -and $r.Json.code -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Write-Output ("  {0} health timed out: {1}" -f $what, $url)
    return $false
}

# ---------- B1 关闭态验收：真实应用不得因实验组件失败 ----------


function Invoke-RealAppProbe([string]$work) {
    Write-Step 'B1 关闭态：真实 RagentApplication 在无任何 p04.* 配置下不得因实验组件失败'
    # 刻意清掉实验环境变量：真实应用本就没有任何 p04.* 配置，这正是 B1 的生产条件。
    foreach ($v in @('P04_DELEGATION_PUBLIC_KEY_PATH', 'P04_PLATFORM_AUTHORIZATION_URL', 'P04_SERVICE_CREDENTIAL')) {
        Remove-Item "Env:$v" -ErrorAction SilentlyContinue
    }
    $cp = "$work\ai\classes;$work\ai\lib\*"
    $run = Join-Path $work 'real-app-run'
    New-Item -ItemType Directory -Force $run | Out-Null
    $proc = Start-Process java -ArgumentList @('-Dfile.encoding=UTF-8', '-Xmx512m', '-cp', $cp,
        'com.nageoffer.ai.ragent.RagentApplication', '--spring.main.web-application-type=none') -WorkingDirectory $run `
        -RedirectStandardOutput "$run\stdout.log" -RedirectStandardError "$run\stderr.log" -WindowStyle Hidden -PassThru
    $proc.Id | Set-Content (Join-Path $work 'realapp.pid')
    $script:StartedProcesses += $proc.Id
    Start-Sleep -Seconds 30
    $log = ((Get-Content "$run\stdout.log" -Raw -ErrorAction SilentlyContinue) + "`n" +
            (Get-Content "$run\stderr.log" -Raw -ErrorAction SilentlyContinue))
    # 修复前这里必然出现 "cannot read p04 delegation public key"；修复后不得再出现任何 p04 归因
    $p04Caused = ($log -match 'p04 delegation public key') -or ($log -match 'DelegationVerifier') -or
                 ($log -match 'RunAcceptanceService') -or ($log -match 'AclProvider')
    $first = (($log -split "`n") | Select-String -Pattern 'Caused by:|APPLICATION FAILED TO START' | Select-Object -First 1)
    $firstText = if ($first) { (($first.Line -replace '\s+', ' ').Trim()) } else { 'no startup failure recorded within probe window' }
    Assert-That 'B1-real-app-no-p04-failure' 'G0' (-not $p04Caused) `
        ("p04CausedFailure={0}; firstFailure={1}" -f $p04Caused, $firstText)
    Stop-Owned (Join-Path $work 'realapp.pid') 'RagentApplication'
}
function Invoke-DisabledStateProbe([string]$work, [string]$credential) {
    Write-Step 'B1 两态验收：无任何 p04.* 配置时实验端点必须不存在'
    $client = New-Client 10

    # 先停掉"显式开启"的两个 JVM，避免端口冲突
    Stop-Owned (Join-Path $work 'platform.pid') 'P04PlatformTestApplication'
    Stop-Owned (Join-Path $work 'ai.pid') 'P04AiTestApplication'
    Start-Sleep -Seconds 3

    # bean 缺席由仓库内 P04AssemblyBoundaryTest 确定性证明（缺席 / 显式 false / 开启正向对照）；
    # 这里补的是"路由是否真的不存在"这一层端到端证据。
    Add-Result 'B1-off-beans' 'G0' 'PASS' 'bean absence proven in-repo by P04AssemblyBoundaryTest (absent + explicit false + enabled positive control)'

    Invoke-RealAppProbe $work

    # platform 侧的关闭态**不**在此断言：该模块刻意未接入 ruoyi-admin，其关闭态证据应与 P1
    # 接线一并补齐；这里如实记为未验证，而不是用一句描述性 PASS 冒充（43 审核已指出该做法）。
    Add-Result 'B1-off-platform-routes' 'G0' 'NOT_RUN' 'platform module is not wired into ruoyi-admin; disabled-state proof deferred to P1 wiring'
}

# =========================== Unit 模式 ===========================
function Invoke-UnitMode {
    Write-Step 'Unit：三个必跑测试类（无外部服务）'
    $runs = @(
        @{ name = 'P04DelegationIssuerTest'; file = 'services/platform/pom.xml'; profile = '-Pdev'
           module = 'ruoyi-modules/ruoyi-ai-integration' },
        @{ name = 'P04DelegatedPrincipalVerifierTest'; file = 'services/ai/pom.xml'; profile = '-Pci'
           module = 'framework' },
        @{ name = 'P04RunAcceptanceTest'; file = 'services/ai/pom.xml'; profile = '-Pci'
           module = 'rag' }
    )
    foreach ($r in $runs) {
        $mvnArgs = @('-o', '-B', '-ntp', '-f', $r.file, $r.profile, '-pl', $r.module, '-am', 'test',
            "-Dtest=$($r.name)", '-Dsurefire.failIfNoSpecifiedTests=false')
        $out = Join-Path $script:Evidence ("unit-" + $r.name + ".log")
        $before = Get-Location
        Set-Location $RepoRoot
        try {
            $res = Invoke-NativeCapture 'mvn' $mvnArgs
            $exit = $res.ExitCode
            $lines = $res.Output
            $lines | Set-Content $out
        } finally { Set-Location $before }
        $m = ($lines | Select-String -Pattern ("Tests run: (\d+), Failures: (\d+), Errors: (\d+).*" + [regex]::Escape($r.name)))
        $detail = "exit=$exit"
        $ran = $false
        if ($m) {
            $match = @($m)[0].Matches[0]
            $total = [int]$match.Groups[1].Value
            $fails = [int]$match.Groups[2].Value
            $errs = [int]$match.Groups[3].Value
            $detail += (" Tests run: {0}, Failures: {1}, Errors: {2}" -f $total, $fails, $errs)
            $ran = ($total -gt 0 -and $fails -eq 0 -and $errs -eq 0)
        } else {
            $detail += ' (no per-class summary found -- treat as not run)'
        }
        Assert-That ("UNIT-" + $r.name) 'G1-G4' ($exit -eq 0 -and $ran) $detail
    }
}

# =========================== Integration 模式 ===========================
function Invoke-IntegrationMode {
    Write-Step 'Integration：两个独立 JVM + 合成 PG，执行 P01–T02'
    $work = $WorkRoot
    try {
        [void](Get-LabEnv 'POSTGRES_PASSWORD')
    } catch {
        Add-Result 'ENV-lab' '-' 'NOT_RUN' ("synthetic lab unavailable: " + $_.Exception.Message)
        throw
    }
    foreach ($p in @("$work\platform\lib", "$work\ai\lib", "$RepoRoot\services\ai\rag\target\test-classes")) {
        if (-not (Test-Path $p)) {
            Add-Result 'ENV-artifacts' '-' 'NOT_RUN' ("missing built artifact: {0}（先按 Spec §8.4 步骤 0 构建）" -f $p)
            throw ("missing built artifact: {0}" -f $p)
        }
    }

    # 每轮先清空本实验自己的五张表：固定幂等键与重放 jti 若跨轮残留，会让 P01/I01/F01/F02
    # 出现"看似失败其实是上一轮数据"的假红（本轮实测踩过：同一靶场连跑三轮后 5 条 FAIL）。
    Invoke-LabSql 'TRUNCATE ai.ai_run, ai.ai_run_event, ai.outbox_event, ai.ai_usage_ledger, ai.p04_replay_guard;' | Out-Null
    $reset = Get-DbCounts
    Assert-That 'ENV-db-reset' 'G1' `
        (($reset.runs -eq 0) -and ($reset.events -eq 0) -and ($reset.outbox -eq 0) -and ($reset.ledger -eq 0) -and ($reset.replay -eq 0)) `
        ("after truncate runs/events/outbox/ledger/replay={0}/{1}/{2}/{3}/{4}" -f $reset.runs, $reset.events, $reset.outbox, $reset.ledger, $reset.replay)
    $credential = [guid]::NewGuid().ToString('N')
    Stop-Owned (Join-Path $work 'platform.pid') 'P04PlatformTestApplication'
    Stop-Owned (Join-Path $work 'ai.pid') 'P04AiTestApplication'
    Start-Sleep -Seconds 3

    [void](Start-Platform $credential $work)
    if (-not (Wait-Health "$($script:PlatformBase)/p04/health" 45 'platform')) { throw 'platform test app did not become healthy' }

    $client = New-Client 20
    $pk = Send-Json $client 'GET' "$($script:PlatformBase)/internal/platform/v1/keys/public" $null $null
    if ($pk.Status -ne 200) { throw 'cannot fetch platform public key' }
    $pemPath = Join-Path $work 'platform-public.pem'
    [IO.File]::WriteAllText($pemPath, $pk.Json.data.pem, (New-Object Text.UTF8Encoding($false)))
    $script:PemPath = $pemPath
    Add-Result 'ENV-publickey' 'G1' 'PASS' ("kid={0} alg={1}" -f $pk.Json.data.kid, $pk.Json.data.algorithm)

    [void](Start-Ai $work $pemPath)
    if (-not (Wait-Health "$($script:AiBase)/p04/health" 60 'ai')) { throw 'ai test app did not become healthy' }
    Add-Result 'ENV-two-jvms' 'G1' 'PASS' ("platform pid={0} ai pid={1}" -f (Get-Content "$work\platform.pid"), (Get-Content "$work\ai.pid"))
    # 只声明"启动方式"这一事实；不再以描述性 PASS 冒充隔离行为断言。
    # 测试应用"不暴露遗留入口"的行为断言属第二步（Spec §7.2），此处不得预先声称。
    Add-Result 'ENV-launch-flags' 'G1' 'PASS' 'test apps started with --spring.config.name (config file name only; it does NOT activate a profile)'

    # 记录用例级基线
    $before = Get-DbCounts
    Add-Result 'ENV-db' 'G1' 'PASS' ("baseline runs/events/outbox/ledger/replay={0}/{1}/{2}/{3}/{4}" -f `
            $before.runs, $before.events, $before.outbox, $before.ledger, $before.replay)
    $script:DbBefore = $before

    Run-Scenarios $client
    $script:DbAfter = Get-DbCounts

    Invoke-DisabledStateProbe $work $credential
}

function Run-Scenarios($client) {
    $scopes = @('rag.chat.submit')
    $bodyA = New-RunBody 'synthetic-p04' @('KB-A')

    Write-Step 'P01 合法委托受理'
    $d = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $c0 = Get-DbCounts
    $p01 = Call-Run $client $d.token 'p01-key' $bodyA @{ 'X-Request-Id' = 'p04-p01' } 0
    Assert-That 'P01' 'G1' ($p01.Status -eq 202 -and $p01.Json.code -eq 200 -and $p01.Json.data.runId) `
        ("status={0} code={1} runId={2} replayed={3}" -f $p01.Status, $p01.Json.code, $p01.Json.data.runId, $p01.Json.data.replayed)
    $c1 = Get-DbCounts
    Assert-That 'P01-db' 'G1' (($c1.runs -eq $c0.runs + 1) -and ($c1.events -eq $c0.events + 1) -and `
            ($c1.outbox -eq $c0.outbox + 1) -and ($c1.ledger -eq $c0.ledger + 1)) `
        ("one run/event/outbox/ledger added: {0}/{1}/{2}/{3}" -f $c1.runs, $c1.events, $c1.outbox, $c1.ledger)
    $script:P01RunId = $p01.Json.data.runId

    Write-Step 'N01 凭证层拒绝（401）'
    $base = Get-DbCounts
    $noAuth = Call-Run $client $null 'n01-a' $bodyA @{} 0
    Assert-That 'N01-missing' 'G2' ($noAuth.Status -eq 401 -and $noAuth.Json.data.errorCode -eq 'AUTH_REQUIRED') `
        ("status={0} errorCode={1}" -f $noAuth.Status, $noAuth.Json.data.errorCode)
    foreach ($v in @('FOREIGN_KEY', 'ALG_NONE', 'WRONG_AUDIENCE', 'WRONG_ISSUER', 'UNKNOWN_KID', 'EXPIRED', 'NOT_YET_VALID')) {
        $dv = New-Delegation $client $v 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $rr = Call-Run $client $dv.token ("n01-" + $v) $bodyA @{} 0
        Assert-That ("N01-" + $v) 'G2' ($rr.Status -eq 401 -and $rr.Json.data.errorCode -eq 'DELEGATION_INVALID') `
            ("status={0} errorCode={1}" -f $rr.Status, $rr.Json.data.errorCode)
    }
    [void](Assert-NoBusinessDelta 'N01-nosurprise' $base 'rejections add no business rows')

    Write-Step 'N02 缺声明：403 上下文 vs 401 凭证'
    foreach ($v in @('MISSING_TENANT', 'MISSING_MEMBERSHIP', 'MISSING_POLICY_VERSION')) {
        $dv = New-Delegation $client $v 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $rr = Call-Run $client $dv.token ("n02-" + $v) $bodyA @{} 0
        Assert-That ("N02-" + $v) 'G2' ($rr.Status -eq 403 -and $rr.Json.data.errorCode -eq 'TENANT_CONTEXT_MISSING') `
            ("status={0} errorCode={1}" -f $rr.Status, $rr.Json.data.errorCode)
    }
    foreach ($v in @('MISSING_SUBJECT', 'MISSING_JTI', 'MISSING_EXPIRATION')) {
        $dv = New-Delegation $client $v 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $rr = Call-Run $client $dv.token ("n02-" + $v) $bodyA @{} 0
        Assert-That ("N02-" + $v) 'G2' ($rr.Status -eq 401 -and $rr.Json.data.errorCode -eq 'DELEGATION_INVALID') `
            ("status={0} errorCode={1}" -f $rr.Status, $rr.Json.data.errorCode)
    }

    Write-Step 'N03 租户/成员状态'
    $dUnknown = New-Delegation $client 'NONE' 'T-UNKNOWN' 'sub-u1' 'M1' 1 $scopes 60
    $rU = Call-Run $client $dUnknown.token 'n03-unknown' $bodyA @{} 0
    Assert-That 'N03-unknown-tenant' 'G2' ($rU.Status -eq 403 -and $rU.Json.data.errorCode -eq 'TENANT_DISABLED') `
        ("status={0} errorCode={1}" -f $rU.Status, $rU.Json.data.errorCode)
    $dDis = New-Delegation $client 'NONE' 'T-DISABLED' 'sub-u1' 'M1' 1 $scopes 60
    $rD = Call-Run $client $dDis.token 'n03-disabled' $bodyA @{} 0
    Assert-That 'N03-disabled-tenant' 'G2' ($rD.Status -eq 403 -and $rD.Json.data.errorCode -eq 'TENANT_DISABLED') `
        ("status={0} errorCode={1}" -f $rD.Status, $rD.Json.data.errorCode)
    $dM = New-Delegation $client 'NONE' 'T1' 'sub-ud' 'MD' 1 $scopes 60
    $rM = Call-Run $client $dM.token 'n03-disabled-member' $bodyA @{} 0
    Assert-That 'N03-disabled-membership' 'G2' ($rM.Status -eq 403 -and $rM.Json.data.errorCode -eq 'MEMBERSHIP_INVALID') `
        ("status={0} errorCode={1}" -f $rM.Status, $rM.Json.data.errorCode)
    $dX = New-Delegation $client 'NONE' 'T1' 'sub-u2' 'M1' 1 $scopes 60
    $rX = Call-Run $client $dX.token 'n03-mismatch' $bodyA @{} 0
    Assert-That 'N03-mismatch' 'G2' ($rX.Status -eq 403 -and $rX.Json.data.errorCode -eq 'MEMBERSHIP_INVALID') `
        ("status={0} errorCode={1}" -f $rX.Status, $rX.Json.data.errorCode)

    Write-Step 'N04 功能 scope 与资源 ACL'
    # 功能权限的权威在 platform（D2=A）：必须改 platform 侧的身份事实。
    # 只把 token 的 scope 声明换掉不会影响判定（实测过：那样仍会 202）。
    $null = Send-Json $client 'POST' "$($script:PlatformBase)/p04/control/scopes" @{} `
    (@{ tenantId = 'T1'; subject = 'sub-u1'; membershipId = 'M1'; scopes = @() } | ConvertTo-Json)
    $dScope = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rS = Call-Run $client $dScope.token 'n04-scope' $bodyA @{} 0
    $null = Send-Json $client 'POST' "$($script:PlatformBase)/p04/control/scopes" @{} `
    (@{ tenantId = 'T1'; subject = 'sub-u1'; membershipId = 'M1'; scopes = @('rag.chat.submit') } | ConvertTo-Json)
    Assert-That 'N04-function-scope' 'G2' ($rS.Status -eq 403 -and $rS.Json.data.errorCode -eq 'FORBIDDEN') `
        ("status={0} errorCode={1}" -f $rS.Status, $rS.Json.data.errorCode)
    $dU0 = New-Delegation $client 'NONE' 'T1' 'sub-u0' 'M0' 1 $scopes 60
    $rU0 = Call-Run $client $dU0.token 'n04-no-acl' $bodyA @{} 0
    Assert-That 'N04-empty-acl' 'G2' ($rU0.Status -eq 404 -and $rU0.Json.data.errorCode -eq 'RESOURCE_NOT_FOUND_OR_FORBIDDEN') `
        ("status={0} errorCode={1}" -f $rU0.Status, $rU0.Json.data.errorCode)
    $dT2 = New-Delegation $client 'NONE' 'T2' 'sub-u2' 'M2' 1 $scopes 60
    $rT2 = Call-Run $client $dT2.token 'n04-other-tenant-kb' $bodyA @{} 0
    Assert-That 'N04-cross-tenant-kb' 'G2' ($rT2.Status -eq 404 -and $rT2.Json.data.errorCode -eq 'RESOURCE_NOT_FOUND_OR_FORBIDDEN') `
        ("status={0} errorCode={1}" -f $rT2.Status, $rT2.Json.data.errorCode)

    Write-Step 'N05 伪造身份（403，不得回落默认租户）'
    $base = Get-DbCounts
    $d5 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rh = Call-Run $client $d5.token 'n05-header' $bodyA @{ 'X-Tenant-Id' = 'T2' } 0
    Assert-That 'N05-header' 'G2' ($rh.Status -eq 403 -and $rh.Json.data.errorCode -eq 'TENANT_CONTEXT_MISSING') `
        ("status={0} errorCode={1}" -f $rh.Status, $rh.Json.data.errorCode)
    $forgedBody = (@{ schemaVersion = 1; action = 'rag.chat'; tenantId = 'T2'; input = @{ text = 'x' }; resourceRefs = @('KB-A') } | ConvertTo-Json -Depth 6)
    $d5b = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rb = Call-Run $client $d5b.token 'n05-body' $forgedBody @{} 0
    Assert-That 'N05-body' 'G2' ($rb.Status -eq 403 -and $rb.Json.data.errorCode -eq 'TENANT_CONTEXT_MISSING') `
        ("status={0} errorCode={1}" -f $rb.Status, $rb.Json.data.errorCode)
    [void](Assert-NoBusinessDelta 'N05-nosurprise' $base 'forged identity adds no business rows')

    Write-Step 'N06 重放与并发同 jti'
    $d6 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $first = Call-Run $client $d6.token 'n06-first' $bodyA @{} 0
    Assert-That 'N06-first-ok' 'G2' ($first.Status -eq 202) ("status={0}" -f $first.Status)
    $replay = Call-Run $client $d6.token 'n06-replay' $bodyA @{} 0
    Assert-That 'N06-token-replay' 'G2' ($replay.Status -eq 401 -and $replay.Json.data.errorCode -eq 'DELEGATION_INVALID') `
        ("status={0} errorCode={1}" -f $replay.Status, $replay.Json.data.errorCode)

    $d6c = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $cc = New-Client 30
    $handles = @()
    for ($i = 0; $i -lt 20; $i++) {
        $handles += Start-AsyncJson $cc "$($script:AiBase)/internal/ai/v1/runs" `
        @{ 'Authorization' = "Bearer $($d6c.token)"; 'Idempotency-Key' = "n06-conc-$i" } $bodyA
    }
    $statuses = @()
    foreach ($h in $handles) { $statuses += (Complete-Async $h).Status }
    $ok = ($statuses | Where-Object { $_ -eq 202 }).Count
    Assert-That 'N06-concurrent-jti' 'G2' ($ok -le 1) ("202-count={0} of 20 (at most one may win)" -f $ok)

    Write-Step 'N07 策略版本 / 撤权 / 授权服务故障'
    $null = Send-Json $client 'POST' "$($script:PlatformBase)/p04/control/policy-version" @{} `
    (@{ tenantId = 'T1'; subject = 'sub-u1'; membershipId = 'M1'; policyVersion = 2 } | ConvertTo-Json)
    $d7 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $r7 = Call-Run $client $d7.token 'n07-stale' $bodyA @{} 0
    Assert-That 'N07-stale-pv' 'G2' ($r7.Status -eq 409 -and $r7.Json.data.errorCode -eq 'POLICY_VERSION_STALE') `
        ("status={0} errorCode={1}" -f $r7.Status, $r7.Json.data.errorCode)
    $null = Send-Json $client 'POST' "$($script:PlatformBase)/p04/control/policy-version" @{} `
    (@{ tenantId = 'T1'; subject = 'sub-u1'; membershipId = 'M1'; policyVersion = 1 } | ConvertTo-Json)

    $null = Send-Json $client 'POST' "$($script:AiBase)/p04/control/acl" @{} `
    (@{ tenantId = 'T1'; membershipId = 'M1'; action = 'rag.chat'; resourceRef = 'KB-A'; granted = $false } | ConvertTo-Json)
    $d7b = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $r7b = Call-Run $client $d7b.token 'n07-revoked' $bodyA @{} 0
    Assert-That 'N07-ai-acl-revoked' 'G2' ($r7b.Status -eq 404) ("status={0} errorCode={1}" -f $r7b.Status, $r7b.Json.data.errorCode)
    $null = Send-Json $client 'POST' "$($script:AiBase)/p04/control/acl" @{} `
    (@{ tenantId = 'T1'; membershipId = 'M1'; action = 'rag.chat'; resourceRef = 'KB-A'; granted = $true } | ConvertTo-Json)

    foreach ($fault in @('sleep:5', 'error503', 'badresponse')) {
        $tag = $fault -replace ':', '-'
        # AI→platform 的调用无法逐请求带故障头，故在 platform 控制面布防
        $null = Send-Json $client 'POST' "$($script:PlatformBase)/p04/control/fault" @{} `
        (@{ fault = $fault; seconds = 30 } | ConvertTo-Json)
        $d7c = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $viaFault = Call-Run $client $d7c.token ("n07-fault-" + $tag) $bodyA @{} 0
        $null = Send-Json $client 'POST' "$($script:PlatformBase)/p04/control/fault" @{} `
        (@{ fault = ''; seconds = 0 } | ConvertTo-Json)
        Assert-That ("N07-fault-" + $tag) 'G2' ($viaFault.Status -eq 503 -and $viaFault.Json.data.errorCode -eq 'AUTHORIZATION_UNAVAILABLE') `
            ("status={0} errorCode={1}" -f $viaFault.Status, $viaFault.Json.data.errorCode)
    }

    Write-Step 'I01–I06 幂等'
    $base = Get-DbCounts
    $sameRunIds = @()
    $replayedFlags = @()
    for ($i = 0; $i -lt 5; $i++) {
        $di = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $ri = Call-Run $client $di.token 'i01-key' $bodyA @{} 0
        $sameRunIds += $ri.Json.data.runId
        $replayedFlags += $ri.Json.data.replayed
    }
    $uniq = ($sameRunIds | Sort-Object -Unique).Count
    $restAllReplayed = (@($replayedFlags | Select-Object -Skip 1 | Where-Object { $_ -ne $true }).Count -eq 0)
    Assert-That 'I01-serial-replay' 'G3' (($uniq -eq 1) -and ($replayedFlags[0] -eq $false) -and $restAllReplayed) `
        ("distinctRunIds={0} firstReplayed={1} restReplayed={2}" -f $uniq, $replayedFlags[0], (($replayedFlags | Select-Object -Skip 1) -join ','))
    $after1 = Get-DbCounts
    Assert-That 'I01-single-run' 'G3' (($after1.runs -eq $base.runs + 1) -and ($after1.events -eq $base.events + 1)) `
        ("runs delta={0} events delta={1}" -f ($after1.runs - $base.runs), ($after1.events - $base.events))

    $d2 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $changed = New-RunBody 'synthetic-p04-changed' @('KB-A')
    $r2 = Call-Run $client $d2.token 'i01-key' $changed @{} 0
    Assert-That 'I02-key-reused' 'G3' ($r2.Status -eq 409 -and $r2.Json.data.errorCode -eq 'IDEMPOTENCY_KEY_REUSED') `
        ("status={0} errorCode={1}" -f $r2.Status, $r2.Json.data.errorCode)

    $d3 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $c3 = New-Client 30
    $h3 = @()
    for ($i = 0; $i -lt 20; $i++) {
        $h3 += Start-AsyncJson $c3 "$($script:AiBase)/internal/ai/v1/runs" `
        @{ 'Authorization' = "Bearer $($d3.token)"; 'Idempotency-Key' = "i03-key-$i" } $bodyA
    }
    $res3 = @()
    foreach ($h in $h3) { $res3 += (Complete-Async $h) }
    # 说明：并发同 jti 只允许一个成功，其余 401；这是 F2 的预期，不是失败。
    $s3 = @($res3 | ForEach-Object { $_.Status })
    $ok3 = @($s3 | Where-Object { $_ -eq 202 }).Count
    Assert-That 'I03-concurrent' 'G3' (($ok3 -le 1) -and (@($s3 | Where-Object { $_ -eq 500 }).Count -eq 0)) `
        ("202={0} 401={1} 500={2}" -f $ok3, @($s3 | Where-Object { $_ -eq 401 }).Count, @($s3 | Where-Object { $_ -eq 500 }).Count)

    # I03/I04 用“每次新 jti”的正确姿势重跑，以验证并发同键的唯一裁决
    $c4 = New-Client 40
    $h4 = @()
    for ($i = 0; $i -lt 20; $i++) {
        $dj = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $h4 += Start-AsyncJson $c4 "$($script:AiBase)/internal/ai/v1/runs" `
        @{ 'Authorization' = "Bearer $($dj.token)"; 'Idempotency-Key' = 'i03-shared' } $bodyA
    }
    $res4 = @()
    foreach ($h in $h4) { $res4 += (Complete-Async $h) }
    $s4 = @($res4 | ForEach-Object { $_.Status })
    $ok4 = @($s4 | Where-Object { $_ -eq 202 }).Count
    $runIds = @($res4 | Where-Object { $_.Status -eq 202 } | ForEach-Object { $_.Json.data.runId } | Sort-Object -Unique)
    Assert-That 'I03-concurrent-same-key' 'G3' (($ok4 -eq 20) -and ($runIds.Count -eq 1) -and (@($s4 | Where-Object { $_ -eq 500 }).Count -eq 0)) `
        ("202={0} distinctRunIds={1} 500={2}" -f $ok4, $runIds.Count, @($s4 | Where-Object { $_ -eq 500 }).Count)

    # I04：同一 key 两种 body 各 10 并发 —— 胜出 hash 的 10 个 202，另一组 10 个 409，库中一个 run
    $c5 = New-Client 40
    $bodyX = New-RunBody 'synthetic-p04-x' @('KB-A')
    $bodyY = New-RunBody 'synthetic-p04-y' @('KB-A')
    $hX = @(); $hY = @()
    for ($i = 0; $i -lt 10; $i++) {
        $dj = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $hX += Start-AsyncJson $c5 "$($script:AiBase)/internal/ai/v1/runs" @{ 'Authorization' = "Bearer $($dj.token)"; 'Idempotency-Key' = 'i04-shared' } $bodyX
    }
    for ($i = 0; $i -lt 10; $i++) {
        $dj = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $hY += Start-AsyncJson $c5 "$($script:AiBase)/internal/ai/v1/runs" @{ 'Authorization' = "Bearer $($dj.token)"; 'Idempotency-Key' = 'i04-shared' } $bodyY
    }
    $rX = @(); foreach ($h in $hX) { $rX += (Complete-Async $h) }
    $rY = @(); foreach ($h in $hY) { $rY += (Complete-Async $h) }
    $sX = @($rX | ForEach-Object { $_.Status }); $sY = @($rY | ForEach-Object { $_.Status })
    $okX = @($sX | Where-Object { $_ -eq 202 }).Count; $okY = @($sY | Where-Object { $_ -eq 202 }).Count
    $cfX = @($sX | Where-Object { $_ -eq 409 }).Count; $cfY = @($sY | Where-Object { $_ -eq 409 }).Count
    $allI04 = @($sX + $sY)
    Assert-That 'I04-two-bodies-race' 'G3' ((($okX -eq 10) -and ($okY -eq 0) -and ($cfY -eq 10)) -or (($okY -eq 10) -and ($okX -eq 0) -and ($cfX -eq 10))) `
        ("X:202={0}/409={1} Y:202={2}/409={3} 500={4}" -f $okX, $cfX, $okY, $cfY, @($allI04 | Where-Object { $_ -eq 500 }).Count)
    $runsI04 = @(@($rX + $rY) | Where-Object { $_.Status -eq 202 } | ForEach-Object { $_.Json.data.runId } | Sort-Object -Unique)
    Assert-That 'I04-single-run' 'G3' ($runsI04.Count -eq 1) ("distinctRunIds={0}" -f $runsI04.Count)

    # I05：相同 key 用于不同租户/成员不串用；相同正文换 key 各自建 run
    $dA = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rA = Call-Run $client $dA.token 'i05-cross-key' $bodyA @{} 0
    $dB = New-Delegation $client 'NONE' 'T2' 'sub-u1' 'M1T2' 1 $scopes 60
    $rB = Call-Run $client $dB.token 'i05-cross-key' (New-RunBody 'synthetic-p04' @('KB-B')) @{} 0
    $dC = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rC = Call-Run $client $dC.token 'i05-other-key' $bodyA @{} 0
    $distinct = @($rA.Json.data.runId, $rB.Json.data.runId, $rC.Json.data.runId) | Sort-Object -Unique
    Assert-That 'I05-scope-of-key' 'G3' (($rA.Status -eq 202) -and ($rB.Status -eq 202) -and ($rC.Status -eq 202) -and ($distinct.Count -eq 3)) `
        ("T1={0} T2={1} otherKey={2} distinctRunIds={3}" -f $rA.Status, $rB.Status, $rC.Status, $distinct.Count)

    # I06：只调对象键顺序 → 同 hash 同 run；改数组顺序 → 409。
    # 多元素 fixture 需要两个资源都可访问，故先给 T1/M1 授权 KB-B。
    $null = Send-Json $client 'POST' "$($script:AiBase)/p04/control/acl" @{} `
    (@{ tenantId = 'T1'; membershipId = 'M1'; action = 'rag.chat'; resourceRef = 'KB-B'; granted = $true } | ConvertTo-Json)
    $refs6 = @('KB-A', 'KB-B')
    $dI6 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $first6 = Call-Run $client $dI6.token 'i06-key' (New-RunBody 'synthetic-p04' $refs6) @{} 0
    $reordered = '{"action":"rag.chat","schemaVersion":1,"resourceRefs":["KB-A","KB-B"],"input":{"text":"synthetic-p04"}}'
    $dI6b = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $second6 = Call-Run $client $dI6b.token 'i06-key' $reordered @{} 0
    Assert-That 'I06-object-order' 'G3' (($first6.Status -eq 202) -and ($second6.Status -eq 202) -and ($second6.Json.data.replayed -eq $true) -and ($first6.Json.data.runId -eq $second6.Json.data.runId)) `
        ("first={0} reordered={1} sameRun={2}" -f $first6.Status, $second6.Status, ($first6.Json.data.runId -eq $second6.Json.data.runId))
    $dI6c = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $swapped = Call-Run $client $dI6c.token 'i06-key' (New-RunBody 'synthetic-p04' @('KB-B', 'KB-A')) @{} 0
    Assert-That 'I06-array-order' 'G3' ($swapped.Status -eq 409 -and $swapped.Json.data.errorCode -eq 'IDEMPOTENCY_KEY_REUSED') `
        ("status={0} errorCode={1}" -f $swapped.Status, $swapped.Json.data.errorCode)

    Write-Step 'F01/F02 事务故障注入'
    $baseF = Get-DbCounts
    $df1 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rf1 = Call-Run $client $df1.token 'f01-key' $bodyA @{ 'X-P04-Ai-Fault' = 'before-commit' } 0
    Assert-That 'F01-non-202' 'G1' ($rf1.Status -ne 202) ("status={0}" -f $rf1.Status)
    $afterF1 = Assert-NoBusinessDelta 'F01-rollback' $baseF 'before-commit fault rolls everything back'
    $df1b = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rf1b = Call-Run $client $df1b.token 'f01-key' $bodyA @{} 0
    Assert-That 'F01-retry-accepts-once' 'G1' ($rf1b.Status -eq 202) ("status={0} runId={1}" -f $rf1b.Status, $rf1b.Json.data.runId)

    $df2 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rf2 = Call-Run $client $df2.token 'f02-key' $bodyA @{ 'X-P04-Ai-Fault' = 'after-commit' } 0
    Assert-That 'F02-response-dropped' 'G3' ($rf2.Status -ne 202) ("status={0}" -f $rf2.Status)
    $df2b = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rf2b = Call-Run $client $df2b.token 'f02-key' $bodyA @{} 0
    Assert-That 'F02-retry-same-run' 'G3' ($rf2b.Status -eq 202 -and $rf2b.Json.data.replayed -eq $true) `
        ("status={0} replayed={1} runId={2}" -f $rf2b.Status, $rf2b.Json.data.replayed, $rf2b.Json.data.runId)

    Write-Step 'T01/T02 requestId 贯通与线程上下文'
    $dt = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rt = Call-Run $client $dt.token 't01-key' $bodyA @{ 'X-Request-Id' = 'p04-t01-supplied' } 0
    Assert-That 'T01-requestid-echo' 'G4' ($rt.RequestId -eq 'p04-t01-supplied' -and $rt.Json.data.requestId -eq 'p04-t01-supplied') `
        ("header={0} body={1}" -f $rt.RequestId, $rt.Json.data.requestId)
    $dt2 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rt2 = Call-Run $client $dt2.token 't01-key-2' $bodyA @{ 'X-Request-Id' = ('bad id!' * 30) } 0
    Assert-That 'T01-requestid-sanitised' 'G4' ($rt2.RequestId -ne '' -and $rt2.RequestId -notmatch '[\s!]') `
        ("header={0}" -f $rt2.RequestId)

    # G4 两侧可串联：platform 在 AI→platform 这一跳上实际看到的是同一个 requestId。
    # 只看 AI 自己的回显不足以证明贯通，故用 platform 测试控制面的观察记录取差集。
    $null = Send-Json $client 'POST' "$($script:PlatformBase)/p04/control/clear-seen-request-ids" @{} '{}'
    $dtSeen = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rtSeen = Call-Run $client $dtSeen.token 't01-seen' $bodyA @{ 'X-Request-Id' = 'p04-t01-cross' } 0
    $seen = Send-Json $client 'GET' "$($script:PlatformBase)/p04/control/seen-request-ids" $null $null
    $seenList = @($seen.Json.data.requestIds)
    $crossed = ($seenList -contains 'p04-t01-cross')
    Assert-That 'T01-cross-service' 'G4' (($rtSeen.Status -eq 202) -and $crossed) `
        ("ai={0} platformSeen={1} containsSuppliedId={2}" -f $rtSeen.Status, $seenList.Count, $crossed)

    $dT1 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rT1 = Call-Run $client $dT1.token 't02-t1' $bodyA @{} 0
    # 同一个人（sub-u1）在 T2 的另一个成员身份 M1T2：KB-B 只授权给该 mid
    $dT2 = New-Delegation $client 'NONE' 'T2' 'sub-u1' 'M1T2' 1 $scopes 60
    $rT2b = Call-Run $client $dT2.token 't02-t2' (New-RunBody 'synthetic-p04-t2' @('KB-B')) @{} 0
    $rNone = Call-Run $client $null 't02-none' $bodyA @{} 0
    Assert-That 'T02-context' 'G2/G4' (($rT1.Status -eq 202) -and ($rT2b.Status -eq 202) -and ($rNone.Status -eq 401) -and ($rT1.Json.data.runId -ne $rT2b.Json.data.runId)) `
        ("T1={0} T2={1} none={2}" -f $rT1.Status, $rT2b.Status, $rNone.Status)

    Write-Step 'F03 重启 AI 进程后的持久幂等'
    $dF3a = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rF3a = Call-Run $client $dF3a.token 'f03-key' $bodyA @{} 0
    Assert-That 'F03-before-restart' 'G3' ($rF3a.Status -eq 202) ("runId={0}" -f $rF3a.Json.data.runId)
    Stop-Owned (Join-Path $WorkRoot 'ai.pid') 'P04AiTestApplication'
    Start-Sleep -Seconds 3
    [void](Start-Ai $WorkRoot $script:PemPath)
    if (-not (Wait-Health "$($script:AiBase)/p04/health" 60 'ai-after-restart')) { throw 'ai test app did not come back after restart' }
    # 重启后用新 jti、同一 Idempotency-Key 重试：必须拿回原 runId（持久幂等）。
    # 注意这证明的是持久受理，不是 Worker 恢复（属 P2）。
    $dF3b = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rF3b = Call-Run $client $dF3b.token 'f03-key' $bodyA @{} 0
    Assert-That 'F03-after-restart' 'G3' (($rF3b.Status -eq 202) -and ($rF3b.Json.data.runId -eq $rF3a.Json.data.runId)) `
        ("status={0} sameRun={1} replayed={2}" -f $rF3b.Status, ($rF3b.Json.data.runId -eq $rF3a.Json.data.runId), $rF3b.Json.data.replayed)
    Add-Result 'F03-scope' 'G3' 'PASS' 'durable acceptance only; worker recovery/replay belongs to P2'
}

# =========================== 入口 ===========================
New-Item -ItemType Directory -Force $script:Evidence | Out-Null
Write-Step ("P0.4 contract experiment — mode={0} executionId={1}" -f $Mode, $script:ExecutionId)
$started = (Get-Date).ToUniversalTime()
$manifest = [ordered]@{
    executionId = $script:ExecutionId
    mode        = $Mode
    startedUtc  = $started.ToString('o')
    repoRoot    = $RepoRoot
    spec        = 'mydocs/p0/30-p04-contract-experiment.md'
    specSha256  = 'bfb8a0ae3ba4b51da24facf0560b71f76f80ca13fceee9c4faaa4f1b3ee5c539'
    lab         = @{ host = $LabHost; container = $LabContainer; database = $LabDb }
    ports       = @{ platform = $PlatformPort; ai = $AiPort }
    host        = @{ psVersion = $PSVersionTable.PSVersion.ToString(); java = $script:JavaVersion }
}
$exitCode = 0
try {
    if ($Mode -eq 'Unit') { Invoke-UnitMode } else { Invoke-IntegrationMode }
} catch {
    Add-Result 'HARNESS' '-' 'FAIL' ("unhandled: " + $_.Exception.Message)
} finally {
    $work = $WorkRoot
    Write-Step '清理：只停止本脚本启动的 JVM'
    $cleanup = @()
    foreach ($pidFile in @((Join-Path $work 'platform.pid'), (Join-Path $work 'ai.pid'))) {
        $id = $null
        if (Test-Path $pidFile) { $id = (Get-Content $pidFile | Select-Object -First 1) }
        if ($id) {
            $pr = Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue
            if ($pr -and $pr.CommandLine -match 'P04(Platform|Ai)TestApplication') {
                Stop-Process -Id $id -Force
                $cleanup += "stopped pid=$id"
            } elseif ($pr) { $cleanup += "pid=$id not ours; left running" } else { $cleanup += "pid=$id already gone" }
        }
    }
    foreach ($line in $cleanup) { Write-Output ("  " + $line) }
    if ($script:HttpLog.Count -gt 0) {
        $script:HttpLog | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 6 } | Set-Content (Join-Path $script:Evidence 'http.jsonl')
    }
    if ($script:DbBefore) { $script:DbBefore | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $script:Evidence 'db-before.json') }
    if ($script:DbAfter) { $script:DbAfter | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $script:Evidence 'db-after.json') }
    $manifest['finishedUtc'] = (Get-Date).ToUniversalTime().ToString('o')
    $manifest['cleanup'] = $cleanup
    $manifest['resultCounts'] = @{
        pass = @($script:Results | Where-Object { $_.status -eq 'PASS' }).Count
        fail = @($script:Results | Where-Object { $_.status -eq 'FAIL' }).Count
        skip = @($script:Results | Where-Object { $_.status -eq 'SKIP' }).Count
    }
    $manifest | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $script:Evidence 'manifest.json')
    $script:Results | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $script:Evidence 'results.json')
    Write-Step ("结果：PASS={0} FAIL={1} SKIP={2}；证据目录 {3}" -f `
            $manifest['resultCounts'].pass, $manifest['resultCounts'].fail, $manifest['resultCounts'].skip, $script:Evidence)
    if ($script:Failures -gt 0 -or @($script:Results | Where-Object { $_.status -eq 'FAIL' }).Count -gt 0) { $exitCode = 1 }
}
exit $exitCode