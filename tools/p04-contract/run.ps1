<#
.SYNOPSIS
  P0.4 契约实验验收入口（Spec §8.6）。

.DESCRIPTION
  两种模式：
    Unit        —— 无外部服务：跑三个必跑类与三个护栏类，并逐个核对 “Tests run > 0”。
    Integration —— 起两个独立 JVM（platform Boot 3 / AI Boot 4）+ 合成 PG，执行 P01–T02。

  约束（照做，别绕）：
    * 本脚本按 Windows PowerShell 5.1 编写（本机无 pwsh 7）：不使用 -SkipHttpErrorCheck /
      ForEach-Object -Parallel / 三元运算符；HTTP 一律走 System.Net.Http.HttpClient。
    * 口令只经进程环境传给子进程，不落盘、不进证据；平台公钥是公开材料，可落盘。
    * 失败即非零退出；依赖缺失输出 NOT_RUN 并非零退出；清理结果单独列示，
      清理失败不得写成实验成功。
    * 只停止本脚本自己启动的 PID（核对命令行），绝不通杀 java。
    * 本脚本记录的是**自己派生的子进程**退出码（nativeCommands）。审阅方要求的
      "顶层验收命令原生 exitCode"由包装器 `tools/p04-contract/run-acceptance.ps1`
      采集后经 -InvocationLogPath / -PreflightEntryExitCode 传入，落进
      invocation-log.json 与 manifest.acceptanceCommands，并逐条断言。

.EXAMPLE
  powershell -NoProfile -File tools/p04-contract/run.ps1 -Mode Unit -EvidenceDir D:/AI-project/mydocs/p0/evidence/p04/unit
  powershell -NoProfile -File tools/p04-contract/run.ps1 -Mode Integration -EvidenceDir D:/AI-project/mydocs/p0/evidence/p04/integration
  powershell -NoProfile -File tools/p04-contract/run-acceptance.ps1 -EvidenceDir D:/AI-project/.scratch/p04/step2b/acceptance -LabContainer p04-pg-p04step2b
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
    [int]$AiPort = 19090,
    # 外层包装器（tools/p04-contract/run-acceptance.ps1）记录的本轮各命令原生退出码文件。
    # §8.6 要求"任一子命令非零即失败"，故这里必须见到文件、见到失败命令为 0、见到预检项存在。
    [string]$InvocationLogPath = '',
    # 预检（缺靶场）那一轮 run.ps1 自身的进程退出码：本进程无法采集自己，由包装器传入。
    [int]$PreflightEntryExitCode = -1
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.IO.Compression.FileSystem
[Net.ServicePointManager]::DefaultConnectionLimit = 128

$script:Failures = 0
$script:Results = New-Object System.Collections.ArrayList
$script:HttpLog = New-Object System.Collections.ArrayList
$script:NativeCommands = New-Object System.Collections.ArrayList
$script:BarrierEvidence = New-Object System.Collections.ArrayList
$script:DbBefore = [ordered]@{}
$script:DbAfter = [ordered]@{}
$script:ExecutionId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$script:Evidence = Join-Path $EvidenceDir $script:ExecutionId
$script:StartedProcesses = @()

function Write-Step([string]$m) { Write-Output ("### " + $m) }
function Add-Result([string]$id, [string]$target, [string]$status, [string]$detail) {
    [void]$script:Results.Add([pscustomobject]@{ id = $id; target = $target; status = $status; detail = $detail })
    if ($status -eq 'FAIL') { $script:Failures++; Write-Output ("  [FAIL] {0} {1}" -f $id, $detail) }
    elseif ($status -eq 'PASS') { Write-Output ("  [ok]   {0} {1}" -f $id, $detail) }
    else { Write-Output ("  [{0}] {1} {2}" -f $status, $id, $detail) }
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
    # Keep a reviewable command record without persisting SQL payloads or credential values.
    $recordArgs = @($arguments | ForEach-Object {
        $arg = [string]$_
        if ($arg -match '\| base64 -d \| docker exec') { '[REDACTED_SQL_PIPELINE]' }
        elseif ($arg -match '(?i)(?:password|secret|token|authorization|access[_-]?key)\s*=' -or
                $arg -match '^eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.') { '[REDACTED_ARGUMENT]' }
        else { $arg }
    })
    [void]$script:NativeCommands.Add([pscustomobject]@{
            utc = (Get-Date).ToUniversalTime().ToString('o'); executable = $exe
            arguments = $recordArgs; argumentCount = $arguments.Count; exitCode = $code
        })
    return [pscustomobject]@{ Output = $out; ExitCode = $code }
}

# 包装器记录的本轮"顶层验收命令"原生退出码（§8.6 判据）。本脚本派生不出这些值：
# clean verify / 本轮 Unit / 本轮 Integration / 缺靶场预检 都是由外层逐条启动的进程。
$script:AcceptanceCommands = @()
function Read-AcceptanceInvocation {
    if (-not $InvocationLogPath) {
        Add-Result 'ACCEPTANCE-invocation-log' 'G0' 'NOT_RUN' 'no invocation log supplied; top-level exit codes unavailable'
        return
    }
    if (-not (Test-Path -LiteralPath $InvocationLogPath)) {
        Assert-That 'ACCEPTANCE-invocation-log' 'G0' $false ("invocation log missing: " + $InvocationLogPath)
        return
    }
    $raw = Get-Content -LiteralPath $InvocationLogPath -Raw -Encoding UTF8
    $doc = $null
    try { $doc = $raw | ConvertFrom-Json } catch { $doc = $null }
    if ($null -eq $doc -or $null -eq $doc.commands) {
        Assert-That 'ACCEPTANCE-invocation-log' 'G0' $false 'invocation log is not valid JSON with a commands array'
        return
    }
    $script:AcceptanceCommands = @($doc.commands)
    # 输入清单只含"在自己之前刚跑完的命令"：Unit 的快照 = 两个构建根；Integration 的快照 =
    # 两个构建根 + Unit。缺靶场预检是**最后**一条，只出现在包装器最终的
    # invocation-commands.json 里，因此 Integration 不能要求"预检已在自己的输入中"，
    # 改用包装器的自检结果（见下方 ACCEPTANCE-wrapper-selfcheck）来核对它。
    $required = if ($Mode -eq 'Unit') { 2 } else { 4 }
    Assert-That 'ACCEPTANCE-invocation-log' 'G0' ($script:AcceptanceCommands.Count -ge $required) `
        ("commands={0} required>={1} log={2} wrapper={3}" -f $script:AcceptanceCommands.Count, $required,
            $InvocationLogPath, $doc.wrapper)
    # 两条构建根必须在场（§8.6 的前两条），否则"全零"可能只是因为清单里什么都没记。
    $builds = @($script:AcceptanceCommands | Where-Object { $_.kind -like 'mvn clean verify*' })
    Assert-That 'ACCEPTANCE-build-roots-present' 'G0' ($builds.Count -eq 2) `
        ("mvnCleanVerifyRecords={0} labels={1}" -f $builds.Count,
            (@($builds | ForEach-Object { $_.label }) -join ','))
    # 自己的前序记录（Integration 轮里 Unit 的那条）必须已经是 0；非零说明 Unit 真的失败过。
    $selfLabel = if ($Mode -eq 'Unit') { 'unit-entry' } else { 'integration-entry' }
    $selfEntry = @($script:AcceptanceCommands | Where-Object { $_.label -eq $selfLabel })
    if ($selfEntry.Count -gt 0) {
        Assert-That ('ACCEPTANCE-' + $selfLabel + '-prior-record') 'G0' ([int]$selfEntry[0].exitCode -eq 0) `
            ("priorRecordedExit={0}" -f $selfEntry[0].exitCode)
    }
    # 非零只允许出现在显式标记 expectsNonZero 的命令上。
    $bad = @($script:AcceptanceCommands | Where-Object { [int]$_.exitCode -ne 0 -and $_.expectsNonZero -ne $true })
    Assert-That 'ACCEPTANCE-all-exit-zero' 'G1-G4' ($bad.Count -eq 0) `
        ("unexpectedNonzero={0} of {1} [{2}]" -f $bad.Count, $script:AcceptanceCommands.Count,
            (@($bad | ForEach-Object { $_.label + '=' + $_.exitCode }) -join ','))
    # 包装器自己的自检：读的是含预检那条的最终清单，是最完整的一份。
    # Unit 轮跑在预检之前，这份 summary 还不存在——那是正常时序，不记 FAIL。
    if ($Mode -eq 'Unit') {
        Add-Result 'ACCEPTANCE-wrapper-selfcheck' 'G1-G4' 'NOT_RUN' `
            'Unit runs before the preflight; the wrapper summary is produced at the end of the chain and is asserted by the Integration run'
    } elseif ($InvocationLogPath) {
        $selfCheck = Join-Path (Split-Path $InvocationLogPath -Parent) 'acceptance-summary.json'
        if (Test-Path -LiteralPath $selfCheck) {
            $sc = $null
            try { $sc = Get-Content -LiteralPath $selfCheck -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $sc = $null }
            if ($null -ne $sc) {
                Assert-That 'ACCEPTANCE-wrapper-selfcheck' 'G1-G4' `
                    ([int]$sc.commandCount -eq 5 -and [int]$sc.nonzeroUnexpected -eq 0 -and
                     [int]$sc.preflightExitCode -ne 0) `
                    ("wrapperCommandCount={0} nonzeroUnexpected={1} preflightExitCode={2} preflightWrapperExit={3}" -f
                        $sc.commandCount, $sc.nonzeroUnexpected, $sc.preflightExitCode, $sc.wrapperExitCode)
            } else {
                Add-Result 'ACCEPTANCE-wrapper-selfcheck' 'G1-G4' 'NOT_RUN' 'wrapper summary unreadable'
            }
        } else {
            Add-Result 'ACCEPTANCE-wrapper-selfcheck' 'G1-G4' 'NOT_RUN' ("wrapper summary absent: " + $selfCheck)
        }
    }
    $pf = @($script:AcceptanceCommands | Where-Object { $_.expectsNonZero -eq $true })
    if ($pf.Count -gt 0) {
        # 只核"清单里恰好一条被标记为应当非零，且它确实非零"。包装器自己那一轮的退出码要等它
        # 结束才存在（Integration 跑在预检之后、包装器结束之前），故此处不做重复绑定；
        # 包装器侧的等价核对由 acceptance-summary.json + ACCEPTANCE-wrapper-selfcheck 负责。
        $pfRecorded = [int]$pf[0].exitCode
        Assert-That 'ACCEPTANCE-preflight-nonzero' 'G1-G4' ($pf.Count -eq 1 -and $pfRecorded -ne 0) `
            ("preflightLabel={0} recordedExit={1} wrapperSuppliedExit={2} expectsNonZeroEntries={3}" -f
                $pf[0].label, $pfRecorded, $PreflightEntryExitCode, $pf.Count)
    } else {
        # Unit 轮输入清单里本来就没有预检（预检在 Unit 之后跑），故不记 NOT_RUN，
        # 否则 Unit 入口会被自己的时序判成非零，进而级联把 Integration 的前序记录判红。
        # 该断言在 Integration 轮以 expectsNonZero 记录独立核对。
        Assert-That 'ACCEPTANCE-preflight-nonzero' 'G1-G4' ($Mode -eq 'Unit') `
            ("preflight not yet run in this mode (mode={0}); asserted by the Integration run" -f $Mode)
    }
    foreach ($c in $script:AcceptanceCommands) {
        Assert-That ("ACCEPTANCE-" + $c.label) 'G0' ([int]$c.exitCode -eq 0 -or $c.expectsNonZero -eq $true) `
            ("exit={0} kind={1}" -f $c.exitCode, $c.kind)
    }
}
$javaProbe = Invoke-NativeCapture 'java' @('-version')
if ($javaProbe.ExitCode -ne 0) { throw 'java -version preflight failed' }
$script:JavaVersion = @($javaProbe.Output | ForEach-Object {
    if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message }
    else { [string]$_ }
} | Where-Object { $_ -match '^(?:openjdk|java) version ' } | Select-Object -First 1)[0]
if (-not $script:JavaVersion) { throw 'java -version output unavailable' }

function Write-SourceSnapshot {
    $git = Invoke-NativeCapture 'git' @('-C', $RepoRoot, '-c', 'core.excludesFile=NUL',
        'ls-files', '-m', '-o', '--exclude-standard')
    if ($git.ExitCode -ne 0) { throw 'cannot enumerate the working-tree source snapshot' }
    $originFile = 'D:\AI-project\.scratch\p04\fix2\source-origins.json'
    $old = @{}
    if (Test-Path -LiteralPath $originFile) {
        foreach ($item in ((Get-Content -Raw -LiteralPath $originFile) | ConvertFrom-Json).files) {
            $old[$item.path] = $item.sha256
        }
    }
    $snapshotRoot = Join-Path $script:Evidence 'source-snapshot'
    $rows = @()
    foreach ($rel in @($git.Output | ForEach-Object { [string]$_ } | Where-Object { $_ -and $_ -notmatch '^warning:' })) {
        $source = [IO.Path]::GetFullPath((Join-Path $RepoRoot ($rel -replace '/', '\')))
        if (-not $source.StartsWith($RepoRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
                -not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "unsafe source path: $rel" }
        $dest = Join-Path $snapshotRoot ($rel -replace '/', '\')
        [void](New-Item -ItemType Directory -Path (Split-Path $dest) -Force)
        Copy-Item -LiteralPath $source -Destination $dest
        $hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLower()
        if ($hash -ne (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLower()) {
            throw "source snapshot copy mismatch: $rel"
        }
        $rows += [pscustomobject]@{ path = $rel; sha256 = $hash;
            previousFix2Sha256 = $old[$rel]; previousFix2Path = $old.ContainsKey($rel) }
    }
    $rows | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $script:Evidence 'source-hashes.json') -Encoding UTF8
    $diff = Invoke-NativeCapture 'git' @('-C', $RepoRoot, 'diff', '--stat')
    if ($diff.ExitCode -ne 0) { throw 'git diff --stat failed' }
    $diff.Output | Set-Content (Join-Path $script:Evidence 'git-diff-stat.txt') -Encoding UTF8
    return @($rows).Count
}
function Protect-LogText([string]$content) {
    $content = $content -replace '\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\b', '[REDACTED]'
    $content = $content -replace '(?i)\bBearer\s+[A-Za-z0-9._~+/-]{20,}', 'Bearer [REDACTED]'
    return $content
}
function Copy-SanitizedLog([string]$from, [string]$to) {
    if (-not (Test-Path -LiteralPath $from -PathType Leaf)) { return }
    [void](New-Item -ItemType Directory -Force -Path (Split-Path $to))
    [IO.File]::WriteAllText($to, (Protect-LogText ([IO.File]::ReadAllText($from))),
        (New-Object Text.UTF8Encoding($false)))
}
function Archive-Surefire {
    $root = Join-Path $script:Evidence 'surefire'
    $count = 0
    foreach ($side in @('platform', 'ai')) {
        $base = Join-Path $RepoRoot "services\$side"
        foreach ($file in @(Get-ChildItem -LiteralPath $base -Recurse -File -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match '[\\/]target[\\/]surefire-reports[\\/]' })) {
            $rel = $file.FullName.Substring($base.Length).TrimStart('\')
            $to = Join-Path $root (Join-Path $side $rel)
            Copy-SanitizedLog $file.FullName $to
            $count++
        }
    }
    return $count
}
function Archive-JvmLogs([string]$phase, [string]$work, [string[]]$sides = @('platform', 'ai')) {
    foreach ($side in $sides) {
        foreach ($stream in @('stdout', 'stderr')) {
            $from = Join-Path (Join-Path $work "$side-run") "$stream.log"
            $to = Join-Path $script:Evidence "jvm-logs\$phase\$side-$stream.log"
            Copy-SanitizedLog $from $to
        }
    }
}

# ---------- HTTP 帮助函数（HttpClient；5.1 兼容） ----------
function Protect-EvidenceValue($value) {
    if ($null -eq $value -or $value -is [string] -or $value -is [ValueType]) { return $value }
    if ($value -is [Collections.IEnumerable] -and $value -isnot [Collections.IDictionary] -and $value -isnot [pscustomobject]) {
        $items = @(); foreach ($item in $value) { $items += ,(Protect-EvidenceValue $item) }; return ,$items
    }
    $copy = [ordered]@{}
    foreach ($property in $value.PSObject.Properties) {
        if ($property.Name -match '^(token|authorization|password|.*credential|.*secret|api[-_]?key|access[-_]?key)$') {
            $copy[$property.Name] = '[REDACTED]'
        } else { $copy[$property.Name] = Protect-EvidenceValue $property.Value }
    }
    return [pscustomobject]$copy
}
function Protect-EvidenceBody([string]$body) {
    if (-not $body) { return '' }
    try { return (Protect-EvidenceValue ($body | ConvertFrom-Json)) | ConvertTo-Json -Depth 64 -Compress }
    catch { return '[NON_JSON_BODY]' }
}
function Send-Json($client, [string]$method, [string]$url, $headers, [string]$json) {
    $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), $url)
    if ($json -and $method -notin @('GET', 'HEAD')) { $req.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json') }
    if ($headers) { foreach ($k in $headers.Keys) { [void]$req.Headers.TryAddWithoutValidation($k, [string]$headers[$k]) } }
    $resp = $client.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $obj = $null
    if ($text) { try { $obj = $text | ConvertFrom-Json } catch { $obj = $null } }
    $rid = ''
    try { $vals = $null; if ($resp.Headers.TryGetValues('X-Request-Id', [ref]$vals)) { $rid = ($vals | Select-Object -First 1) } } catch { }
    [void]$script:HttpLog.Add([pscustomobject]@{
            method = $method; url = $url; status = [int]$resp.StatusCode
            requestId = $rid; body = (Protect-EvidenceBody $text)
        })
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $obj; Text = $text; RequestId = $rid }
}
function Start-AsyncJson($client, [string]$url, $headers, [string]$json) {
    $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod('POST')), $url)
    $req.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
    foreach ($k in $headers.Keys) { [void]$req.Headers.TryAddWithoutValidation($k, [string]$headers[$k]) }
    return [pscustomobject]@{ Task = $client.SendAsync($req); Req = $req; Url = $url }
}
function Complete-Async($handle) {
    $resp = $handle.Task.GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $obj = $null
    if ($text) { try { $obj = $text | ConvertFrom-Json } catch { } }
    $rid = ''
    try { $vals = $null; if ($resp.Headers.TryGetValues('X-Request-Id', [ref]$vals)) { $rid = ($vals | Select-Object -First 1) } } catch { }
    [void]$script:HttpLog.Add([pscustomobject]@{
            method = 'POST'; url = $handle.Url; status = [int]$resp.StatusCode
            requestId = $rid; body = (Protect-EvidenceBody $text); async = $true
        })
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $obj; Text = $text; RequestId = $rid }
}

function Arm-DecisionBarrier($client, [string]$position, [string]$key, [int]$expected) {
    $r = Send-Json $client 'POST' "$($script:AiBase)/p04/control/decision-barrier/arm" @{} `
        (@{ position = $position; key = $key; expected = $expected } | ConvertTo-Json)
    if ($r.Status -ne 200 -or $r.Json.data.arrived -ne 0) { throw "cannot arm $position decision barrier" }
    [void]$script:BarrierEvidence.Add([pscustomobject]@{ phase = 'arm'; position = $position; key = $key;
            expected = $expected; arrived = $r.Json.data.arrived; released = $r.Json.data.released })
}
function Release-DecisionBarrier($client, [string]$position, [string]$key, [int]$expected) {
    $ready = $false
    for ($i = 0; $i -lt 200; $i++) {
        $s = Send-Json $client 'GET' "$($script:AiBase)/p04/control/decision-barrier/status" $null $null
        if ($s.Status -ne 200) { throw 'decision barrier status unavailable' }
        if ($s.Json.data.position -ne $position -or $s.Json.data.key -ne $key) {
            throw 'decision barrier identity changed during race'
        }
        if ($s.Json.data.arrived -eq $expected -and $s.Json.data.released -eq $false) { $ready = $true; break }
        if ($s.Json.data.arrived -gt $expected) { throw 'too many barrier participants' }
        Start-Sleep -Milliseconds 200
    }
    [void]$script:BarrierEvidence.Add([pscustomobject]@{ phase = 'ready'; position = $position; key = $key;
            expected = $expected; arrived = $s.Json.data.arrived; released = $s.Json.data.released })
    if (-not $ready) { throw "decision barrier not reached by all $expected participants" }
    $release = Send-Json $client 'POST' "$($script:AiBase)/p04/control/decision-barrier/release" @{} '{}'
    if ($release.Status -ne 200 -or $release.Json.data.arrived -ne $expected -or
            $release.Json.data.released -ne $true) { throw 'decision barrier release failed' }
    [void]$script:BarrierEvidence.Add([pscustomobject]@{ phase = 'release'; position = $position; key = $key;
            expected = $expected; arrived = $release.Json.data.arrived; released = $release.Json.data.released })
    Assert-That "BARRIER-$position-$key" 'G3' $true ("position={0} participants={1}/{2} released=true" -f
            $position, $release.Json.data.arrived, $expected)
}
function Invoke-DroppedResponse($client, [string]$token, [string]$key, [string]$body) {
    # A one-request loopback proxy receives the real HTTP request, forwards it to AI,
    # observes the committed 202, then closes the client socket without HTTP bytes.
    $proxyPort = $AiPort + 1
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $proxyPort)
    $proxyClient = New-Client 20
    $socket = $null
    try {
        $listener.Start()
        $accept = $listener.AcceptTcpClientAsync()
        $attempt = Start-AsyncJson $proxyClient "http://127.0.0.1:$proxyPort/internal/ai/v1/runs" `
            @{ 'Authorization' = "Bearer $token"; 'Idempotency-Key' = $key } $body
        $socket = $accept.GetAwaiter().GetResult()
        $stream = $socket.GetStream()
        $headerBytes = New-Object System.Collections.Generic.List[byte]
        while ($headerBytes.Count -lt 16384) {
            $next = $stream.ReadByte()
            if ($next -lt 0) { throw 'proxy client closed before headers' }
            $headerBytes.Add([byte]$next)
            $n = $headerBytes.Count
            if ($n -ge 4 -and $headerBytes[$n-4] -eq 13 -and $headerBytes[$n-3] -eq 10 -and
                    $headerBytes[$n-2] -eq 13 -and $headerBytes[$n-1] -eq 10) { break }
        }
        if ($headerBytes.Count -ge 16384) { throw 'proxy request headers too large' }
        $headerText = [Text.Encoding]::ASCII.GetString($headerBytes.ToArray())
        if ($headerText -notmatch '^POST /internal/ai/v1/runs HTTP/1\.[01]') { throw 'proxy received unexpected request' }
        $lengthMatch = [regex]::Match($headerText, '(?im)^Content-Length:\s*(\d+)\s*$')
        $authMatch = [regex]::Match($headerText, '(?im)^Authorization:\s*Bearer\s+([^\r\n]+)')
        $keyMatch = [regex]::Match($headerText, '(?im)^Idempotency-Key:\s*([^\r\n]+)')
        if (-not $lengthMatch.Success -or -not $authMatch.Success -or -not $keyMatch.Success) {
            throw 'proxy request missing required headers'
        }
        $length = [int]$lengthMatch.Groups[1].Value
        if ($length -lt 0 -or $length -gt 8192) { throw 'proxy request body length out of range' }
        $received = New-Object byte[] $length
        $offset = 0
        while ($offset -lt $length) {
            $read = $stream.Read($received, $offset, $length - $offset)
            if ($read -le 0) { throw 'proxy client closed before request body' }
            $offset += $read
        }
        $receivedBody = [Text.Encoding]::UTF8.GetString($received)
        if ($authMatch.Groups[1].Value.Trim() -ne $token -or $keyMatch.Groups[1].Value.Trim() -ne $key -or
                $receivedBody -ne $body) { throw 'proxy request differs from dispatched request' }
        $upstream = Call-Run $client $token $key $receivedBody @{} 0
        if ($upstream.Status -ne 202) { throw "proxy upstream did not commit: status=$($upstream.Status)" }
        $socket.Close()
        $socket = $null
        $lost = $false
        try { $unexpected = Complete-Async $attempt }
        catch { $lost = $true }
        [void]$script:HttpLog.Add([pscustomobject]@{
                method = 'POST'; url = "http://127.0.0.1:$proxyPort/internal/ai/v1/runs";
                status = 'TRANSPORT_LOST'; requestId = ''; body = '';
                proxyUpstreamStatus = $upstream.Status; clientTransportLost = $lost
            })
        return [pscustomobject]@{ Upstream = $upstream; TransportLost = $lost }
    } finally {
        if ($socket) { $socket.Close() }
        $listener.Stop()
        $proxyClient.Dispose()
    }
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
    $inner = "echo $b64 | base64 -d | docker exec -i -e PGPASSWORD=`$(docker exec $LabContainer printenv POSTGRES_PASSWORD) $LabContainer psql -U postgres -d $LabDb -X -q -tA -v ON_ERROR_STOP=1 -f -"
    $r = Invoke-NativeCapture 'ssh' @('-o', 'BatchMode=yes', '-o', 'LogLevel=ERROR', $LabHost, $inner)
    if ($r.ExitCode -ne 0) { throw ("synthetic SQL failed (ssh/docker exit={0})" -f $r.ExitCode) }
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
function Get-DbSnapshot {
    # Each table is a full, ordered set with the frozen business keys and linkage columns.
    $sql = @'
SELECT json_build_object(
  'runs', COALESCE((SELECT json_agg(t ORDER BY t.id) FROM
    (SELECT id, tenant_id, membership_id, subject, action, idempotency_key,
            request_hash, status, policy_version, acl_version FROM ai.ai_run) t), '[]'::json),
  'events', COALESCE((SELECT json_agg(t ORDER BY t.id) FROM
    (SELECT id, run_id, seq, type FROM ai.ai_run_event) t), '[]'::json),
  'outbox', COALESCE((SELECT json_agg(t ORDER BY t.id) FROM
    (SELECT id, run_id, event_id, topic FROM ai.outbox_event) t), '[]'::json),
  'ledger', COALESCE((SELECT json_agg(t ORDER BY t.id) FROM
    (SELECT id, run_id, tenant_id, units, state FROM ai.ai_usage_ledger) t), '[]'::json)
)::text;
'@
    return (Invoke-LabSql $sql | ConvertFrom-Json)
}
function Begin-Case([string]$id) {
    $script:DbBefore[$id] = Get-DbSnapshot
}
function End-Case([string]$id, [int]$minNewRuns, [int]$maxNewRuns) {
    $before = $script:DbBefore[$id]
    if ($null -eq $before) { throw "missing per-case before snapshot: $id" }
    $after = Get-DbSnapshot
    $script:DbAfter[$id] = $after
    $oldRunIds = @($before.runs | ForEach-Object { $_.id })
    $oldEventIds = @($before.events | ForEach-Object { $_.id })
    $oldOutboxIds = @($before.outbox | ForEach-Object { $_.id })
    $oldLedgerIds = @($before.ledger | ForEach-Object { $_.id })
    $newRuns = @($after.runs | Where-Object { $oldRunIds -notcontains $_.id })
    $newEvents = @($after.events | Where-Object { $oldEventIds -notcontains $_.id })
    $newOutbox = @($after.outbox | Where-Object { $oldOutboxIds -notcontains $_.id })
    $newLedger = @($after.ledger | Where-Object { $oldLedgerIds -notcontains $_.id })
    $unchanged = $true
    foreach ($table in @('runs', 'events', 'outbox', 'ledger')) {
        foreach ($prior in @($before.$table)) {
            $current = @($after.$table | Where-Object { $_.id -eq $prior.id })
            if ($current.Count -ne 1 -or ($current[0] | ConvertTo-Json -Compress -Depth 8) -ne
                    ($prior | ConvertTo-Json -Compress -Depth 8)) { $unchanged = $false }
        }
    }
    $linked = $true
    foreach ($run in $newRuns) {
        $ev = @($newEvents | Where-Object { $_.run_id -eq $run.id })
        $ob = @($newOutbox | Where-Object { $_.run_id -eq $run.id })
        $le = @($newLedger | Where-Object { $_.run_id -eq $run.id })
        if ($ev.Count -ne 1 -or $ob.Count -ne 1 -or $le.Count -ne 1) { $linked = $false; continue }
        if ($ev[0].seq -ne 1 -or $ev[0].type -ne 'run.accepted' -or $ob[0].event_id -ne $ev[0].id -or
                $le[0].tenant_id -ne $run.tenant_id -or $le[0].units -ne 1 -or $le[0].state -ne 'RESERVED' -or
                $run.request_hash -notmatch '^[a-f0-9]{64}$' -or $run.status -ne 'QUEUED' -or
                $run.policy_version -lt 1 -or $run.acl_version -lt 1) { $linked = $false }
    }
    $countsMatch = $newRuns.Count -eq $newEvents.Count -and $newRuns.Count -eq $newOutbox.Count -and
            $newRuns.Count -eq $newLedger.Count
    $ok = $newRuns.Count -ge $minNewRuns -and $newRuns.Count -le $maxNewRuns -and
            $countsMatch -and $linked -and $unchanged
    Assert-That "DB-$id" 'G1-G4' $ok ("new runs/events/outbox/ledger={0}/{1}/{2}/{3}; unchangedPrior={4}; linked={5}; runIds={6}" -f
            $newRuns.Count, $newEvents.Count, $newOutbox.Count, $newLedger.Count, $unchanged, $linked,
            (@($newRuns | ForEach-Object { $_.id }) -join ','))
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
    if ($script:StartedProcesses -notcontains [int]$id) { return }
    $pr = Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction Stop
    if ($pr -and $pr.CommandLine -match $match) {
        Stop-Process -Id $id -Force
        $process = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($process) { [void]$process.WaitForExit(5000) }
        if (Get-Process -Id $id -ErrorAction SilentlyContinue) { throw "owned process failed to stop: $id" }
        Write-Output ("  stopped owned process {0} (pid={1})" -f $match, $id)
    }
}
function Initialize-AiRuntime {
    $jar = Join-Path $RepoRoot 'services\ai\bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'fresh bootstrap jar missing; run both clean verify roots first' }
    $script:AiRuntime = Join-Path $script:Evidence 'ai-runtime'
    [void](New-Item -ItemType Directory -Path $script:AiRuntime)
    $archive = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($entry in $archive.Entries) {
            $relative = $null
            if ($entry.FullName.StartsWith('BOOT-INF/classes/') -and -not $entry.FullName.EndsWith('/')) {
                $relative = 'classes/' + $entry.FullName.Substring(17)
            } elseif ($entry.FullName -match '^BOOT-INF/lib/[^/]+\.jar$') {
                $relative = 'lib/' + $entry.Name
            }
            if ($relative) {
                $destination = [IO.Path]::GetFullPath((Join-Path $script:AiRuntime $relative))
                if (-not $destination.StartsWith($script:AiRuntime + '\', [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'unsafe archive path'
                }
                [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination)
            }
        }
    } finally { $archive.Dispose() }
    $origins = @()
    foreach ($module in @('framework', 'rag', 'system')) {
        $built = Join-Path $RepoRoot "services\ai\$module\target\$module-0.0.1-SNAPSHOT.jar"
        $packed = Join-Path $script:AiRuntime "lib\$module-0.0.1-SNAPSHOT.jar"
        if (-not (Test-Path -LiteralPath $built) -or -not (Test-Path -LiteralPath $packed)) { throw "missing $module artifact" }
        $hash = (Get-FileHash -LiteralPath $built -Algorithm SHA256).Hash
        if ($hash -ne (Get-FileHash -LiteralPath $packed -Algorithm SHA256).Hash) { throw "stale $module in bootstrap jar" }
        $newerSources = @(Get-ChildItem (Join-Path $RepoRoot "services\ai\$module\src") -Recurse -File |
            Where-Object { $_.Extension -eq '.java' -and $_.LastWriteTimeUtc -gt (Get-Item $built).LastWriteTimeUtc })
        if ($newerSources.Count -gt 0) { throw "sources newer than $module artifact; clean verify required" }
        $origins += [pscustomobject]@{ module = $module; sha256 = $hash; artifact = $packed }
    }
    $origins | ConvertTo-Json | Set-Content (Join-Path $script:Evidence 'ai-artifact-origins.json') -Encoding UTF8
    Assert-That 'ENV-fresh-ai-artifacts' 'G1' ($origins.Count -eq 3) 'bootstrap contains exactly the freshly built framework/rag/system jars; no old work/lib is used'
}
function Initialize-PlatformRuntime {
    $jar = Join-Path $RepoRoot 'services\platform\ruoyi-admin\target\ruoyi-admin.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'fresh ruoyi-admin jar missing; run platform clean verify first' }
    $script:PlatformRuntime = Join-Path $script:Evidence 'platform-runtime'
    $libDir = Join-Path $script:PlatformRuntime 'lib'
    [void](New-Item -ItemType Directory -Path $libDir -Force)
    # The full admin jar carries unrelated MyBatis/Redis autoconfiguration. Resolve the
    # integration module's own test classpath afresh from the offline Maven repository.
    $deps = Invoke-NativeCapture 'mvn' @('-o', '-B', '-ntp', '-f',
        (Join-Path $RepoRoot 'services\platform\pom.xml'), '-pl', 'ruoyi-modules/ruoyi-ai-integration',
        'dependency:copy-dependencies', '-DincludeScope=test', "-DoutputDirectory=$libDir")
    $deps.Output | Set-Content (Join-Path $script:Evidence 'platform-runtime-deps.log') -Encoding UTF8
    if ($deps.ExitCode -ne 0) { throw "platform test dependencies unavailable (mvn exit=$($deps.ExitCode))" }
    $iaJars = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'services\platform\ruoyi-modules\ruoyi-ai-integration\target') `
            -Filter 'ruoyi-ai-integration-*.jar' | Where-Object { $_.Name -notmatch '(sources|javadoc)' })
    if ($iaJars.Count -ne 1) { throw 'fresh ruoyi-ai-integration jar missing or ambiguous' }
    $iaJar = $iaJars[0].FullName
    $manifest['platformArtifacts'] = @{
        adminJarSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLower()
        integrationJarSha256 = (Get-FileHash -LiteralPath $iaJar -Algorithm SHA256).Hash.ToLower()
        dependencyJarCount = @(Get-ChildItem -LiteralPath $libDir -Filter '*.jar').Count
    }
}
function Get-AiClassPath([bool]$includeTests) {
    if (-not $script:AiRuntime) { throw 'AI runtime not initialized' }
    $cp = "$($script:AiRuntime)\classes;$($script:AiRuntime)\lib\*"
    if ($includeTests) { $cp += ";$RepoRoot\services\ai\rag\target\test-classes" }
    return $cp
}
function Assert-Assembly([string]$id, [string]$file, [bool]$enabled, [string[]]$expected) {
    for ($i = 0; $i -lt 10 -and -not (Test-Path -LiteralPath $file); $i++) { Start-Sleep -Seconds 1 }
    if (-not (Test-Path -LiteralPath $file)) { Assert-That $id 'G0' $false 'no successful application-context evidence'; return }
    $facts = Get-Content -LiteralPath $file -Raw -Encoding UTF8 | ConvertFrom-Json
    $ok = $facts.contextStarted -eq $true
    if ($enabled) {
        foreach ($name in $expected) { $ok = $ok -and (@($facts.p04Beans) -contains $name) }
    } else {
        $ok = $ok -and (@($facts.p04Beans).Count -eq 0)
        $ok = $ok -and (@($facts.routes | Where-Object { $_ -match '^/(internal/ai/v1/runs|internal/platform/v1/(authorization|delegations|keys)|p04/)' }).Count -eq 0)
    }
    Assert-That $id 'G0' $ok ("contextStarted={0}; experimentalBeans={1}; evidence={2}" -f $facts.contextStarted, (@($facts.p04Beans) -join ','), $file)
}
function Assert-LegacyIsolation($client, [string]$side, [string]$assemblyFile, [string]$baseUrl,
    [string[]]$paths) {
    $facts = Get-Content -LiteralPath $assemblyFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $legacy = @($facts.legacyControllers)
    $mq = @($facts.mqBeans)
    $storage = @($facts.storageBeans)
    Assert-That "ISOLATION-$side-beans" 'G1' `
        ($facts.contextStarted -eq $true -and $legacy.Count -eq 0 -and $mq.Count -eq 0 -and $storage.Count -eq 0) `
        ("legacyControllers={0} mqBeans={1} storageBeans={2} assembly={3}" -f
            $legacy.Count, $mq.Count, $storage.Count, $assemblyFile)
    foreach ($path in $paths) {
        $r = Send-Json $client 'POST' "$baseUrl$path" @{} '{}'
        Assert-That "ISOLATION-$side-$path" 'G1' ($r.Status -eq 404) ("POST {0} status={1}" -f $path, $r.Status)
    }
}
function Start-Platform([string]$credential, [string]$work, [string]$state = 'enabled') {
    $ia = Join-Path $RepoRoot 'services\platform\ruoyi-modules\ruoyi-ai-integration'
    $libs = @(Get-ChildItem -LiteralPath (Join-Path $script:PlatformRuntime 'lib') -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '^ruoyi-ai-integration-' } | ForEach-Object { $_.FullName }) -join ';'
    $cp = "$ia\target\classes;$ia\target\test-classes;$libs"
    $run = Join-Path $work 'platform-run'
    New-Item -ItemType Directory -Force $run | Out-Null
    $env:P04_SERVICE_CREDENTIAL = $credential
    $script:PlatformAssembly = Join-Path $script:Evidence ("platform-assembly-$state.json")
    $env:CONTRACT_ASSEMBLY_EVIDENCE = $script:PlatformAssembly
    $arguments = @('-Dfile.encoding=UTF-8', '-Xmx512m')
    if ($state -eq 'system-false') { $arguments += '-Dp04.enabled=false' }
    $arguments += @('-cp', $cp,
        'org.ruoyi.aiintegration.p04.P04PlatformTestApplication',
        '--spring.config.name=p04platform', "--server.port=$PlatformPort")
    if ($state -eq 'cli-false') { $arguments += '--p04.enabled=false' }
    $p = Start-Process java -ArgumentList $arguments -WorkingDirectory $run `
        -RedirectStandardOutput "$run\stdout.log" -RedirectStandardError "$run\stderr.log" -WindowStyle Hidden -PassThru
    $p.Id | Set-Content (Join-Path $work 'platform.pid')
    $script:StartedProcesses += $p.Id
    return $p.Id
}
function Start-Ai([string]$work, [string]$pemPath, [string]$state = 'enabled') {
    # 前置工作树里刚构建的两个实验模块主类：否则会用到提取自旧 fat jar 的陈旧副本，
    # 修复后的代码根本不会被加载（"测了旧字节码"的假绿必须避免）。
    $cp = Get-AiClassPath $true
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
    $script:AiAssembly = Join-Path $script:Evidence ("ai-assembly-$state.json")
    $env:CONTRACT_ASSEMBLY_EVIDENCE = $script:AiAssembly
    $arguments = @('-Dfile.encoding=UTF-8', '-Xmx512m')
    if ($state -eq 'system-false') { $arguments += '-Dp04.enabled=false' }
    $arguments += @('-cp', $cp,
        'com.nageoffer.ai.ragent.rag.runtime.p04.P04AiTestApplication',
        '--spring.config.name=p04ai', "--server.port=$AiPort")
    if ($state -eq 'cli-false') { $arguments += '--p04.enabled=false' }
    $a = Start-Process java -ArgumentList $arguments -WorkingDirectory $run `
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
    foreach ($v in @('P04_ENABLED', 'P04_DELEGATION_PUBLIC_KEY_PATH', 'P04_PLATFORM_AUTHORIZATION_URL', 'P04_SERVICE_CREDENTIAL')) {
        Remove-Item "Env:$v" -ErrorAction SilentlyContinue
    }
    $aiDbUrl = $env:P04_AI_DB_URL
    if (-not $aiDbUrl) { throw 'synthetic AI database URL missing' }
    Get-ChildItem Env:P04_* | ForEach-Object { Remove-Item ("Env:" + $_.Name) }
    if ($LabContainer -notmatch '^p04-pg-([a-z0-9-]+)$') { throw 'synthetic PG container name must identify a lab tag' }
    $labTag = $Matches[1]
    # 只复制无受管注解的两个探针类；绝不把整份test-classes放入产品全根扫描。
    $probeRoot = Join-Path $script:Evidence 'real-probe-classes'
    $package = 'com\nageoffer\ai\ragent\rag\runtime\p04'
    [void](New-Item -ItemType Directory -Force (Join-Path $probeRoot $package))
    foreach ($name in @('P04RealApplicationProbe', 'P04ApplicationEvidence')) {
        Copy-Item -LiteralPath "$RepoRoot\services\ai\rag\target\test-classes\$package\$name.class" -Destination (Join-Path $probeRoot $package)
    }
    $cp = "$probeRoot;$(Get-AiClassPath $false)"
    $run = Join-Path $work 'real-app-run'
    New-Item -ItemType Directory -Force $run | Out-Null
    $facts = Join-Path $script:Evidence 'real-app-assembly.json'
    $env:CONTRACT_ASSEMBLY_EVIDENCE = $facts
    # 填入纯合成占位值并把模型端点指向不可用回环地址，不使用用户已有服务凭证。
    $config = [IO.File]::ReadAllText((Join-Path $RepoRoot 'services\ai\bootstrap\src\main\resources\application.yaml'))
    foreach ($match in [regex]::Matches($config, '\$\{([A-Z][A-Z0-9_]+)\}')) {
        [Environment]::SetEnvironmentVariable($match.Groups[1].Value, [guid]::NewGuid().ToString('N'), 'Process')
    }
    foreach ($key in @('RUSTFS_ACCESS_KEY', 'RUSTFS_SECRET_KEY')) {
        $response = Invoke-NativeCapture 'ssh' @('-o', 'BatchMode=yes', '-o', 'LogLevel=ERROR', $LabHost, "docker exec p04-s3-$labTag printenv $key")
        if ($response.ExitCode -ne 0) { Add-Result 'ENV-real-storage' '-' 'NOT_RUN' 'synthetic object storage credentials unavailable'; throw 'synthetic object storage credentials unavailable' }
        $value = ($response.Output | Out-String).Trim()
        if (-not $value) { throw 'synthetic object storage credential is empty' }
        $property = if ($key -eq 'RUSTFS_ACCESS_KEY') { 'RAG_STORAGE_S3_ACCESS_KEY' } else { 'RAG_STORAGE_S3_SECRET_KEY' }
        [Environment]::SetEnvironmentVariable($property, $value, 'Process')
    }
    $redis = Invoke-NativeCapture 'ssh' @('-o', 'BatchMode=yes', '-o', 'LogLevel=ERROR', $LabHost, "docker exec p04-redis-$labTag printenv P04_REDIS_PASSWORD")
    if ($redis.ExitCode -ne 0) { Add-Result 'ENV-real-redis' '-' 'NOT_RUN' 'synthetic Redis credential unavailable'; throw 'synthetic Redis credential unavailable' }
    $redisPassword = ($redis.Output | Out-String).Trim()
    if (-not $redisPassword) { throw 'synthetic Redis credential is empty' }
    $env:SPRING_DATA_REDIS_PASSWORD = $redisPassword
    $env:PROJECT_SERVICES_AI_BOOTSTRAP_SRC_MAIN_RESOURCES_APPLICATION_YAML_PASSWORD_33 = $redisPassword
    # 仅合成PG；不允许产品application.yaml回落到业务库。
    $env:AI_DB_URL = $aiDbUrl
    $env:AI_DB_USER = 'ai_app'
    $env:AI_DB_PASSWORD = Get-LabEnv 'AI_APP_PASSWORD'
    $realPort = $AiPort + 1
    $arguments = @('-Dfile.encoding=UTF-8', '-Xmx512m', '-cp', $cp,
        'com.nageoffer.ai.ragent.rag.runtime.p04.P04RealApplicationProbe',
        "--server.port=$realPort", '--server.servlet.context-path=/',
        '--spring.data.redis.host=192.168.139.103', '--spring.data.redis.port=16389',
        '--rag.storage.s3.endpoint=http://192.168.139.103:19000',
        '--rocketmq.name-server=192.168.139.103:19876', "--unique-name=$labTag",
        '--agent.mcp.servers[0].url=http://127.0.0.1:1', '--agent.trace.enabled=false')
    foreach ($provider in [regex]::Matches($config, '(?m)^    ([a-zA-Z][a-zA-Z0-9_-]*):\r?\n      url:')) {
        $arguments += ("--ai.providers.{0}.url=http://127.0.0.1:1" -f $provider.Groups[1].Value)
    }
    $proc = Start-Process java -ArgumentList $arguments -WorkingDirectory $run `
        -RedirectStandardOutput "$run\stdout.log" -RedirectStandardError "$run\stderr.log" -WindowStyle Hidden -PassThru
    $proc.Id | Set-Content (Join-Path $work 'realapp.pid')
    $script:StartedProcesses += $proc.Id
    try {
        $ready = Wait-AssemblyFile $facts $proc.Id
        Assert-That 'B1-real-app-no-p04-failure' 'G0' $ready 'real RagentApplication context must actually start; unrelated failures are failures too'
        if ($ready) {
            $realFacts = Get-Content -LiteralPath $facts -Raw -Encoding UTF8 | ConvertFrom-Json
            Assert-That 'B1-real-app-no-switch' 'G0' ($null -eq $realFacts.p04Enabled) 'default product context has no p04.enabled property'
            Assert-Assembly 'B1-real-app-beans' $facts $false @()
            $client = New-Client 10
            foreach ($path in @('/internal/ai/v1/runs', '/p04/health', '/p04/control/reset')) {
                $method = if ($path -eq '/p04/health') { 'GET' } else { 'POST' }
                $response = Send-Json $client $method "http://127.0.0.1:$realPort$path" @{} '{}'
                Assert-That ("B1-real-app-route-$path") 'G0' ($response.Status -eq 404) ("path=$path status=$($response.Status)")
            }
        }
    } finally {
        Stop-Owned (Join-Path $work 'realapp.pid') 'P04RealApplicationProbe'
        Copy-SanitizedLog (Join-Path $run 'stdout.log') (Join-Path $script:Evidence 'real-app-run\stdout.log')
        Copy-SanitizedLog (Join-Path $run 'stderr.log') (Join-Path $script:Evidence 'real-app-run\stderr.log')
    }
}
function Wait-AssemblyFile([string]$file, [int]$processId) {
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-Path -LiteralPath $file) { return $true }
        if (-not (Get-Process -Id $processId -ErrorAction SilentlyContinue)) { return $false }
        Start-Sleep -Seconds 1
    }
    return $false
}
function Invoke-DisabledStateProbe([string]$work, [string]$credential) {
    Write-Step 'B1 两态验收：无任何 p04.* 配置时实验端点必须不存在'
    $client = New-Client 10

    # 先停掉"显式开启"的两个 JVM，避免端口冲突
    Stop-Owned (Join-Path $work 'platform.pid') 'P04PlatformTestApplication'
    Stop-Owned (Join-Path $work 'ai.pid') 'P04AiTestApplication'
    Archive-JvmLogs 'enabled-after-restart' $work
    Start-Sleep -Seconds 3

    foreach ($state in @('cli-false', 'system-false')) {
        $pidPlatform = Start-Platform $credential $work $state
        $pidAi = Start-Ai $work $script:PemPath $state
        try {
            $platformReady = Wait-AssemblyFile $script:PlatformAssembly $pidPlatform
            $aiReady = Wait-AssemblyFile $script:AiAssembly $pidAi
            Assert-That ("B1-$state-contexts") 'G0' ($platformReady -and $aiReady) 'both disabled JVM contexts must start'
            if (-not $platformReady -or -not $aiReady) { throw 'disabled application failed to create context' }
            $beanId = if ($state -eq 'cli-false') { 'B1-off-beans' } else { 'B1-system-false-beans' }
            Assert-Assembly $beanId $script:AiAssembly $false @()
            Assert-Assembly ("B1-platform-$state-beans") $script:PlatformAssembly $false @()
            foreach ($path in @('/internal/ai/v1/runs', '/p04/health', '/p04/control/reset')) {
                $method = if ($path -eq '/p04/health') { 'GET' } else { 'POST' }
                $r = Send-Json $client $method "$($script:AiBase)$path" @{} '{}'
                Assert-That ("B1-ai-$state-$path") 'G0' ($r.Status -eq 404) ("status=$($r.Status)")
            }
            $platformOff = $true
            foreach ($path in @('/internal/platform/v1/authorization/check', '/internal/platform/v1/delegations', '/internal/platform/v1/keys/public', '/p04/health', '/p04/control/reset')) {
                $method = if ($path -match '(health|keys/public)$') { 'GET' } else { 'POST' }
                $r = Send-Json $client $method "$($script:PlatformBase)$path" @{} '{}'
                $platformOff = $platformOff -and ($r.Status -eq 404)
            }
            $routeId = if ($state -eq 'cli-false') { 'B1-off-platform-routes' } else { 'B1-system-false-platform-routes' }
            Assert-That $routeId 'G0' $platformOff 'all five platform experiment routes must be absent (integration module; not admin wiring)'
        } finally {
            Stop-Owned (Join-Path $work 'platform.pid') 'P04PlatformTestApplication'
            Stop-Owned (Join-Path $work 'ai.pid') 'P04AiTestApplication'
            Archive-JvmLogs $state $work
        }
    }
    Invoke-RealAppProbe $work
}

# =========================== Unit 模式 ===========================
function Invoke-UnitMode {
    Write-Step 'Unit：三个必跑测试类与三个护栏类（无外部服务）'
    $runs = @(
        @{ name = 'P04DelegationIssuerTest'; file = 'services/platform/pom.xml'; profile = '-Pdev'
           module = 'ruoyi-modules/ruoyi-ai-integration' },
        @{ name = 'P04DelegatedPrincipalVerifierTest'; file = 'services/ai/pom.xml'; profile = '-Pci'
           module = 'framework' },
        @{ name = 'P04RunAcceptanceTest'; file = 'services/ai/pom.xml'; profile = '-Pci'
           module = 'rag' },
        @{ name = 'P04AssemblyBoundaryTest'; file = 'services/ai/pom.xml'; profile = '-Pci'
           module = 'rag' },
        @{ name = 'P04AuthorizationTest'; file = 'services/platform/pom.xml'; profile = '-Pdev'
           module = 'ruoyi-modules/ruoyi-ai-integration' },
        @{ name = 'SaTokenConfigTest'; file = 'services/ai/pom.xml'; profile = '-Pci'
           module = 'system' }
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
            $lines | Set-Content -LiteralPath $out -Encoding UTF8
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
function Invoke-BothBuildRoots {
    # Spec §8.6 的两个构建根：在 Integration 轮内执行一次，
    # 使"命令 → 原生 exitCode → 日志"落在同一份证据里（否则这两个顶层的退出码只能靠外部口头转述）。
    $roots = @(
        @{ id = 'BUILD-platform-clean-verify'; file = 'services/platform/pom.xml'; profile = '-Pdev'
           log = 'platform-clean-verify.log' },
        @{ id = 'BUILD-ai-clean-verify'; file = 'services/ai/pom.xml'; profile = '-Pci'
           log = 'ai-clean-verify.log' }
    )
    foreach ($r in $roots) {
        Write-Step ("构建根：" + $r.id)
        $args = @('-o', '-B', '-ntp', '-f', $r.file, $r.profile, 'clean', 'verify')
        $before = Get-Location
        Set-Location $RepoRoot
        try { $res = Invoke-NativeCapture 'mvn' $args } finally { Set-Location $before }
        $res.Output | Set-Content -LiteralPath (Join-Path $script:Evidence $r.log) -Encoding UTF8
        $summary = @($res.Output | Select-String -Pattern '^\[INFO\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$')
        $totals = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
        foreach ($line in $summary) {
            $mm = [regex]::Match($line.Line, 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)')
            if ($mm.Success) {
                $totals.tests += [int]$mm.Groups[1].Value; $totals.failures += [int]$mm.Groups[2].Value
                $totals.errors += [int]$mm.Groups[3].Value; $totals.skipped += [int]$mm.Groups[4].Value
            }
        }
        $buildSuccess = @($res.Output | Select-String -Pattern '^\[INFO\] BUILD SUCCESS$').Count -gt 0
        Assert-That $r.id 'G1-G4' ($res.ExitCode -eq 0 -and $buildSuccess -and $totals.tests -gt 0) `
            ("exit={0} buildSuccess={1} modules={2} tests={3} failures={4} errors={5} skipped={6} log={7}" -f `
                $res.ExitCode, $buildSuccess, $summary.Count, $totals.tests, $totals.failures, $totals.errors,
                $totals.skipped, $r.log)
    }
}

function Invoke-IntegrationMode {
    Write-Step 'Integration：两个独立 JVM + 合成 PG，执行 P01–T02'
    Invoke-BothBuildRoots
    $work = $WorkRoot
    try { [void](Get-CimInstance Win32_Process -Filter "ProcessId=$PID" -ErrorAction Stop) }
    catch { Add-Result 'ENV-process-ownership' '-' 'NOT_RUN' 'CIM process metadata access required for safe owned-PID cleanup'; throw }
    Initialize-AiRuntime
    Initialize-PlatformRuntime
    try {
        [void](Get-LabEnv 'POSTGRES_PASSWORD')
    } catch {
        Add-Result 'ENV-lab' '-' 'NOT_RUN' ("synthetic lab unavailable: " + $_.Exception.Message)
        throw
    }
    $pgFacts = Invoke-LabSql @'
SELECT json_build_object(
  'serverVersion', current_setting('server_version'),
  'database', current_database(),
  'transactionIsolation', current_setting('default_transaction_isolation'),
  'platformMigrations', (SELECT count(*) FROM platform.flyway_schema_history_platform WHERE success),
  'aiMigrations', (SELECT count(*) FROM ai.flyway_schema_history_ai WHERE success),
  'schemas', (SELECT json_agg(schema_name ORDER BY schema_name) FROM information_schema.schemata
              WHERE schema_name IN ('platform','ai','extensions'))
)::text;
'@
    $manifest['pg'] = $pgFacts | ConvertFrom-Json
    foreach ($p in @((Join-Path $script:PlatformRuntime 'lib'), "$RepoRoot\services\ai\rag\target\test-classes")) {
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
    Assert-Assembly 'B1-enabled-ai-beans' $script:AiAssembly $true @('delegationVerifier', 'platformAuthorizationClient', 'p04SecurityConfig', 'p04AiExceptionHandler', 'requestHasher', 'runAcceptanceService', 'jdbcRunStore', 'runAcceptanceController')
    Assert-Assembly 'B1-enabled-platform-beans' $script:PlatformAssembly $true @('delegationIssuer', 'internalAuthorizationController', 'p04PlatformConfig', 'p04ExceptionHandler')
    Assert-LegacyIsolation $client 'ai' $script:AiAssembly $script:AiBase `
        @('/auth/login', '/rag/v3/chat', '/agent/v1/chat', '/knowledge-base')
    Assert-LegacyIsolation $client 'platform' $script:PlatformAssembly $script:PlatformBase `
        @('/auth/login', '/system/user', '/resource/oss/config')
    Add-Result 'ENV-two-jvms' 'G1' 'PASS' ("platform pid={0} ai pid={1}" -f (Get-Content "$work\platform.pid"), (Get-Content "$work\ai.pid"))
    # 只声明"启动方式"这一事实；不再以描述性 PASS 冒充隔离行为断言。
    # 测试应用"不暴露遗留入口"的行为断言属第二步（Spec §7.2），此处不得预先声称。
    Add-Result 'ENV-launch-flags' 'G1' 'PASS' 'test apps started with --spring.config.name (config file name only; it does NOT activate a profile)'

    # 记录用例级基线
    $before = Get-DbCounts
    Add-Result 'ENV-db' 'G1' 'PASS' ("baseline runs/events/outbox/ledger/replay={0}/{1}/{2}/{3}/{4}" -f `
            $before.runs, $before.events, $before.outbox, $before.ledger, $before.replay)
    $manifest['baselineCounts'] = $before

    Run-Scenarios $client
    $manifest['finalCounts'] = Get-DbCounts

    Invoke-DisabledStateProbe $work $credential
}

function Run-Scenarios($client) {
    $scopes = @('rag.chat.submit')
    $bodyA = New-RunBody 'synthetic-p04' @('KB-A')

    Write-Step 'P01 合法委托受理'
    Begin-Case 'P01'
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

    End-Case 'P01' 1 1
    Write-Step 'B2 严格契约与审核反例（每次新jti，四表无增量）'
    Begin-Case 'B2'
    $strictBody = '{"schemaVersion":1,"action":"rag.chat","input":{"text":"x"},"resourceRefs":["KB-A"]}'
    $cases = @(
        @{ id = 'version2'; body = $strictBody.Replace('"schemaVersion":1', '"schemaVersion":2'); status = 400 },
        @{ id = 'version-float'; body = $strictBody.Replace('"schemaVersion":1', '"schemaVersion":1.0'); status = 400 },
        @{ id = 'version-overflow'; body = $strictBody.Replace('"schemaVersion":1', '"schemaVersion":4294967297'); status = 400 },
        @{ id = 'version-bigint'; body = $strictBody.Replace('"schemaVersion":1', '"schemaVersion":18446744073709551617'); status = 400 },
        @{ id = 'unknown-action'; body = $strictBody.Replace('rag.chat', 'admin.delete'); status = 400 },
        @{ id = 'text-number'; body = $strictBody.Replace('"text":"x"', '"text":123'); status = 400 },
        @{ id = 'text-null'; body = $strictBody.Replace('"text":"x"', '"text":null'); status = 400 },
        @{ id = 'empty-refs'; body = $strictBody.Replace('["KB-A"]', '[]'); status = 404 },
        @{ id = 'numeric-ref'; body = $strictBody.Replace('["KB-A"]', '[1]'); status = 400 },
        @{ id = 'duplicate-input'; body = $strictBody.Replace('"text":"x"', '"text":"x","text":"y"'); status = 400 },
        @{ id = 'unknown-input'; body = $strictBody.Replace('"text":"x"', '"text":"x","extra":1'); status = 400 },
        @{ id = 'trailing-object'; body = $strictBody + ' {"tenantId":"T2"}'; status = 400 },
        @{ id = 'trailing-garbage'; body = $strictBody + ' INVALID'; status = 400 },
        @{ id = 'root-array'; body = '[' + $strictBody + ']'; status = 400 },
        @{ id = 'identity-last'; body = '{"extra":1,"tenantId":"T2",' + $strictBody.Substring(1); status = 403 },
        @{ id = 'identity-first'; body = '{"tenantId":"T2","extra":1,' + $strictBody.Substring(1); status = 403 }
    )
    foreach ($case in $cases) {
        $delegation = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $beforeCase = Get-DbCounts
        $response = Call-Run $client $delegation.token ("b2-$($case.id)") $case.body @{} 0
        $expectedError = if ($case.status -eq 404) { 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' } elseif ($case.status -eq 403) { 'TENANT_CONTEXT_MISSING' } else { 'BAD_REQUEST' }
        Assert-That ("B2-$($case.id)") 'G2' ($response.Status -eq $case.status -and $response.Json.code -eq $case.status -and $response.Json.data.errorCode -eq $expectedError) ("status=$($response.Status) code=$($response.Json.code) error=$($response.Json.data.errorCode)")
        [void](Assert-NoBusinessDelta ("B2-$($case.id)-db") $beforeCase 'strict request rejection')
    }
    foreach ($action in @('unknown', 'RAG.CHAT', 'rag.chat ', $null)) {
        $request = @{ tenantId = 'T1'; subject = 'sub-u1'; membershipId = 'M1'; policyVersion = 1; action = $action; resourceRef = 'KB-A' } | ConvertTo-Json
        $response = Send-Json $client 'POST' "$($script:PlatformBase)/internal/platform/v1/authorization/check" @{ 'X-P04-Service-Credential' = $env:P04_SERVICE_CREDENTIAL } $request
        Assert-That ("B2-platform-action-$action") 'G2' ($response.Status -eq 403 -and $response.Json.code -eq 403 -and $response.Json.data.errorCode -eq 'FORBIDDEN') ("status=$($response.Status) code=$($response.Json.code)")
    }

    Write-Step 'N01 凭证层拒绝（401）'
    End-Case 'B2' 0 0
    Begin-Case 'N01'
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
    End-Case 'N01' 0 0
    Begin-Case 'N02'
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
    End-Case 'N02' 0 0
    Begin-Case 'N03'
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
    End-Case 'N03' 0 0
    Begin-Case 'N04'
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
    End-Case 'N04' 0 0
    Begin-Case 'N05'
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
    End-Case 'N05' 0 0
    Begin-Case 'N06'
    $d6 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $first = Call-Run $client $d6.token 'n06-first' $bodyA @{} 0
    Assert-That 'N06-first-ok' 'G2' ($first.Status -eq 202) ("status={0}" -f $first.Status)
    $replay = Call-Run $client $d6.token 'n06-replay' $bodyA @{} 0
    Assert-That 'N06-token-replay' 'G2' ($replay.Status -eq 401 -and $replay.Json.data.errorCode -eq 'DELEGATION_INVALID') `
        ("status={0} errorCode={1}" -f $replay.Status, $replay.Json.data.errorCode)

    $d6c = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $cc = New-Client 90
    Arm-DecisionBarrier $client 'jti' $d6c.jti 20
    $handles = @()
    for ($i = 0; $i -lt 20; $i++) {
        $handles += Start-AsyncJson $cc "$($script:AiBase)/internal/ai/v1/runs" `
        @{ 'Authorization' = "Bearer $($d6c.token)"; 'Idempotency-Key' = "n06-conc-$i" } $bodyA
    }
    Release-DecisionBarrier $client 'jti' $d6c.jti 20
    $statuses = @()
    foreach ($h in $handles) { $statuses += (Complete-Async $h).Status }
    $ok = ($statuses | Where-Object { $_ -eq 202 }).Count
    Assert-That 'N06-concurrent-jti' 'G2' ($ok -le 1) ("202-count={0} of 20 (at most one may win)" -f $ok)

    Write-Step 'N07 策略版本 / 撤权 / 授权服务故障'
    End-Case 'N06' 1 2
    Begin-Case 'N07'
    $null = Send-Json $client 'POST' "$($script:PlatformBase)/p04/control/policy-version" @{} `
    (@{ tenantId = 'T1'; subject = 'sub-u1'; membershipId = 'M1'; policyVersion = 2 } | ConvertTo-Json)
    $d7 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $r7 = Call-Run $client $d7.token 'n07-stale' $bodyA @{} 0
    Assert-That 'N07-stale-pv' 'G2' ($r7.Status -eq 409 -and $r7.Json.data.errorCode -eq 'POLICY_VERSION_STALE') `
        ("status={0} errorCode={1}" -f $r7.Status, $r7.Json.data.errorCode)
    $null = Send-Json $client 'POST' "$($script:PlatformBase)/p04/control/policy-version" @{} `
    (@{ tenantId = 'T1'; subject = 'sub-u1'; membershipId = 'M1'; policyVersion = 1 } | ConvertTo-Json)

    $aclBefore = Send-Json $client 'GET' "$($script:AiBase)/p04/control/acl-version?tenantId=T1" $null $null
    $aclRevoke = Send-Json $client 'POST' "$($script:AiBase)/p04/control/acl" @{} `
    (@{ tenantId = 'T1'; membershipId = 'M1'; action = 'rag.chat'; resourceRef = 'KB-A'; granted = $false } | ConvertTo-Json)
    Assert-That 'N07-acl-version-revoked' 'G2' ($aclBefore.Status -eq 200 -and
            $aclRevoke.Json.data.aclVersionBefore -eq $aclBefore.Json.data.aclVersion -and
            $aclRevoke.Json.data.aclVersionAfter -eq $aclBefore.Json.data.aclVersion + 1) `
        ("aclVersion {0}->{1}; platform policyVersion remains 1" -f
            $aclBefore.Json.data.aclVersion, $aclRevoke.Json.data.aclVersionAfter)
    $d7b = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $r7b = Call-Run $client $d7b.token 'n07-revoked' $bodyA @{} 0
    Assert-That 'N07-ai-acl-revoked' 'G2' ($r7b.Status -eq 404) ("status={0} errorCode={1}" -f $r7b.Status, $r7b.Json.data.errorCode)
    $aclRestore = Send-Json $client 'POST' "$($script:AiBase)/p04/control/acl" @{} `
    (@{ tenantId = 'T1'; membershipId = 'M1'; action = 'rag.chat'; resourceRef = 'KB-A'; granted = $true } | ConvertTo-Json)
    Assert-That 'N07-acl-version-restored' 'G2' ($aclRestore.Json.data.aclVersionAfter -eq
            $aclRevoke.Json.data.aclVersionAfter + 1) `
        ("aclVersion {0}->{1}" -f $aclRevoke.Json.data.aclVersionAfter,
            $aclRestore.Json.data.aclVersionAfter)

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
    End-Case 'N07' 0 0
    Begin-Case 'I01'
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

    End-Case 'I01' 1 1
    Begin-Case 'I02'
    $d2 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $changed = New-RunBody 'synthetic-p04-changed' @('KB-A')
    $r2 = Call-Run $client $d2.token 'i01-key' $changed @{} 0
    Assert-That 'I02-key-reused' 'G3' ($r2.Status -eq 409 -and $r2.Json.data.errorCode -eq 'IDEMPOTENCY_KEY_REUSED') `
        ("status={0} errorCode={1}" -f $r2.Status, $r2.Json.data.errorCode)

    End-Case 'I02' 0 0
    Begin-Case 'I03'
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
    $c4 = New-Client 90
    $tokens4 = @()
    for ($i = 0; $i -lt 20; $i++) { $tokens4 += New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60 }
    Assert-That 'I03-distinct-jti' 'G3' (@($tokens4 | ForEach-Object { $_.jti } | Sort-Object -Unique).Count -eq 20) '20 tokens were signed before dispatch and carry distinct jti'
    Arm-DecisionBarrier $client 'idempotency' 'i03-shared' 20
    $h4 = @()
    for ($i = 0; $i -lt 20; $i++) {
        $h4 += Start-AsyncJson $c4 "$($script:AiBase)/internal/ai/v1/runs" `
        @{ 'Authorization' = "Bearer $($tokens4[$i].token)"; 'Idempotency-Key' = 'i03-shared' } $bodyA
    }
    Release-DecisionBarrier $client 'idempotency' 'i03-shared' 20
    $res4 = @()
    foreach ($h in $h4) { $res4 += (Complete-Async $h) }
    $s4 = @($res4 | ForEach-Object { $_.Status })
    $ok4 = @($s4 | Where-Object { $_ -eq 202 }).Count
    $runIds = @($res4 | Where-Object { $_.Status -eq 202 } | ForEach-Object { $_.Json.data.runId } | Sort-Object -Unique)
    Assert-That 'I03-concurrent-same-key' 'G3' (($ok4 -eq 20) -and ($runIds.Count -eq 1) -and (@($s4 | Where-Object { $_ -eq 500 }).Count -eq 0)) `
        ("202={0} distinctRunIds={1} 500={2}" -f $ok4, $runIds.Count, @($s4 | Where-Object { $_ -eq 500 }).Count)

    End-Case 'I03' 1 2
    Begin-Case 'I04'
    # I04：同一 key 两种 body 各 10 并发 —— 胜出 hash 的 10 个 202，另一组 10 个 409，库中一个 run
    $c5 = New-Client 90
    $bodyX = New-RunBody 'synthetic-p04-x' @('KB-A')
    $bodyY = New-RunBody 'synthetic-p04-y' @('KB-A')
    $tokens5 = @()
    for ($i = 0; $i -lt 20; $i++) { $tokens5 += New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60 }
    Assert-That 'I04-distinct-jti' 'G3' (@($tokens5 | ForEach-Object { $_.jti } | Sort-Object -Unique).Count -eq 20) '20 tokens were signed before dispatch and carry distinct jti'
    Arm-DecisionBarrier $client 'idempotency' 'i04-shared' 20
    $hX = @(); $hY = @()
    for ($i = 0; $i -lt 10; $i++) {
        $hX += Start-AsyncJson $c5 "$($script:AiBase)/internal/ai/v1/runs" @{ 'Authorization' = "Bearer $($tokens5[$i].token)"; 'Idempotency-Key' = 'i04-shared' } $bodyX
    }
    for ($i = 0; $i -lt 10; $i++) {
        $hY += Start-AsyncJson $c5 "$($script:AiBase)/internal/ai/v1/runs" @{ 'Authorization' = "Bearer $($tokens5[$i+10].token)"; 'Idempotency-Key' = 'i04-shared' } $bodyY
    }
    Release-DecisionBarrier $client 'idempotency' 'i04-shared' 20
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

    End-Case 'I04' 1 1
    Begin-Case 'I05'
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

    End-Case 'I05' 3 3
    Begin-Case 'I06'
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

    End-Case 'I06' 1 1
    Write-Step 'F01/F02 事务故障注入'
    Begin-Case 'F01'
    $baseF = Get-DbCounts
    $df1 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rf1 = Call-Run $client $df1.token 'f01-key' $bodyA @{ 'X-P04-Fault' = 'before-commit' } 0
    Assert-That 'F01-non-202' 'G1' ($rf1.Status -ne 202) ("status={0}" -f $rf1.Status)
    $afterF1 = Assert-NoBusinessDelta 'F01-rollback' $baseF 'before-commit fault rolls everything back'
    $df1b = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rf1b = Call-Run $client $df1b.token 'f01-key' $bodyA @{} 0
    Assert-That 'F01-retry-accepts-once' 'G1' ($rf1b.Status -eq 202) ("status={0} runId={1}" -f $rf1b.Status, $rf1b.Json.data.runId)

    foreach ($stage in @('run', 'event', 'reserve', 'outbox')) {
        $stageBefore = Get-DbCounts
        $stageKey = "f01-$stage"
        $stageToken = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $stageFailed = Call-Run $client $stageToken.token $stageKey $bodyA `
            @{ 'X-P04-Fault' = "before-commit:$stage" } 0
        Assert-That "F01-$stage-non-202" 'G1' ($stageFailed.Status -ne 202) `
            ("stage={0} status={1}" -f $stage, $stageFailed.Status)
        [void](Assert-NoBusinessDelta "F01-$stage-rollback" $stageBefore "failure after $stage write rolls back")
        $retryToken = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
        $stageRetry = Call-Run $client $retryToken.token $stageKey $bodyA @{} 0
        Assert-That "F01-$stage-retry" 'G1' ($stageRetry.Status -eq 202 -and
                $stageRetry.Json.data.replayed -eq $false) `
            ("stage={0} status={1} runId={2}" -f $stage, $stageRetry.Status, $stageRetry.Json.data.runId)
    }

    End-Case 'F01' 5 5
    Begin-Case 'F02'
    $df2 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $drop = Invoke-DroppedResponse $client $df2.token 'f02-key' $bodyA
    $rf2 = [pscustomobject]@{ Status = $(if ($drop.TransportLost) { 'TRANSPORT_LOST' } else { 202 }) }
    Assert-That 'F02-response-dropped' 'G3' ($rf2.Status -ne 202) ("status={0}" -f $rf2.Status)
    Assert-That 'F02-proxy-committed-then-lost' 'G3' ($drop.Upstream.Status -eq 202 -and $drop.TransportLost) `
        ("upstreamStatus={0} clientTransportLost={1}" -f $drop.Upstream.Status, $drop.TransportLost)
    $df2b = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rf2b = Call-Run $client $df2b.token 'f02-key' $bodyA @{} 0
    Assert-That 'F02-retry-same-run' 'G3' ($rf2b.Status -eq 202 -and $rf2b.Json.data.replayed -eq $true) `
        ("status={0} replayed={1} runId={2}" -f $rf2b.Status, $rf2b.Json.data.replayed, $rf2b.Json.data.runId)
    Assert-That 'F02-proxy-runid' 'G3' ($rf2b.Json.data.runId -eq $drop.Upstream.Json.data.runId) `
        'new-jti retry returned the committed upstream runId'

    End-Case 'F02' 1 1
    Write-Step 'T01/T02 requestId 贯通与线程上下文'
    Begin-Case 'T01'
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

    End-Case 'T01' 3 3
    Begin-Case 'T02'
    $dT1 = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rT1 = Call-Run $client $dT1.token 't02-t1' $bodyA @{} 0
    # 同一个人（sub-u1）在 T2 的另一个成员身份 M1T2：KB-B 只授权给该 mid
    $dT2 = New-Delegation $client 'NONE' 'T2' 'sub-u1' 'M1T2' 1 $scopes 60
    $rT2b = Call-Run $client $dT2.token 't02-t2' (New-RunBody 'synthetic-p04-t2' @('KB-B')) @{} 0
    $rNone = Call-Run $client $null 't02-none' $bodyA @{} 0
    Assert-That 'T02-context' 'G2/G4' (($rT1.Status -eq 202) -and ($rT2b.Status -eq 202) -and ($rNone.Status -eq 401) -and ($rT1.Json.data.runId -ne $rT2b.Json.data.runId)) `
        ("T1={0} T2={1} none={2}" -f $rT1.Status, $rT2b.Status, $rNone.Status)

    End-Case 'T02' 2 2
    Write-Step 'F03 重启 AI 进程后的持久幂等'
    Begin-Case 'F03'
    $dF3a = New-Delegation $client 'NONE' 'T1' 'sub-u1' 'M1' 1 $scopes 60
    $rF3a = Call-Run $client $dF3a.token 'f03-key' $bodyA @{} 0
    Assert-That 'F03-before-restart' 'G3' ($rF3a.Status -eq 202) ("runId={0}" -f $rF3a.Json.data.runId)
    Stop-Owned (Join-Path $WorkRoot 'ai.pid') 'P04AiTestApplication'
    Archive-JvmLogs 'enabled-before-restart' $WorkRoot @('ai')
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
    End-Case 'F03' 1 1
}

# =========================== 入口 ===========================
New-Item -ItemType Directory -Force $script:Evidence | Out-Null
Write-Step ("P0.4 contract experiment — mode={0} executionId={1}" -f $Mode, $script:ExecutionId)
$started = (Get-Date).ToUniversalTime()
$headResult = Invoke-NativeCapture 'git' @('-C', $RepoRoot, 'rev-parse', 'HEAD')
$branchResult = Invoke-NativeCapture 'git' @('-C', $RepoRoot, 'branch', '--show-current')
$mavenResult = Invoke-NativeCapture 'mvn' @('-version')
if ($headResult.ExitCode -ne 0 -or $branchResult.ExitCode -ne 0 -or $mavenResult.ExitCode -ne 0) {
    throw 'git/Maven preflight failed'
}
$currentSpec = 'D:\AI-project\mydocs\p0\30-p04-contract-experiment.md'
$fixSpec = 'D:\AI-project\mydocs\p0\44-p04-blocker-fix-spec.md'
$requiredCases = @('P01','N01','N02','N03','N04','N05','N06','N07',
    'I01','I02','I03','I04','I05','I06','F01','F02','F03','T01','T02')
$manifest = [ordered]@{
    executionId = $script:ExecutionId
    mode        = $Mode
    startedUtc  = $started.ToString('o')
    repoRoot    = $RepoRoot
    checkoutCommit = ($headResult.Output | Select-Object -First 1)
    branch = ($branchResult.Output | Select-Object -First 1)
    spec = $currentSpec
    specCurrentSha256 = (Get-FileHash -LiteralPath $currentSpec -Algorithm SHA256).Hash.ToLower()
    fixSpec = $fixSpec
    fixSpecCurrentSha256 = (Get-FileHash -LiteralPath $fixSpec -Algorithm SHA256).Hash.ToLower()
    approvedSnapshotSha256HistoricalOnly = 'bfb8a0ae3ba4b51da24facf0560b71f76f80ca13fceee9c4faaa4f1b3ee5c539'
    requiredCases = $requiredCases
    offline = $true
    lab         = @{ host = $LabHost; container = $LabContainer; database = $LabDb }
    ports       = @{ platform = $PlatformPort; ai = $AiPort }
    host        = @{ psVersion = $PSVersionTable.PSVersion.ToString(); java = $script:JavaVersion;
                     maven = (($mavenResult.Output | Select-Object -First 1) | Out-String).Trim() }
    invocationLogPath = $InvocationLogPath
    preflightEntryExitCode = $PreflightEntryExitCode
}
$exitCode = 0
try {
    $manifest['sourceFileCount'] = Write-SourceSnapshot
    $manifest['surefireXmlCount'] = Archive-Surefire
    # 出处说明：这些 XML 是在本轮 Integration 跑业务场景**之前**归档的，来自外部 clean verify
    # 与 Unit 轮次，不是 Integration 运行时产物（Integration 不起 surefire）。
    # 报告引用 skip 数时应以本轮 BUILD-*-clean-verify 的模块汇总为准。
    $manifest['surefireXmlProvenance'] =
        'archived from services/*/target/surefire-reports at the start of this run (clean verify + Unit runs); NOT produced by the Integration scenario run'
    Read-AcceptanceInvocation
    if ($Mode -eq 'Unit') { Invoke-UnitMode } else { Invoke-IntegrationMode }
} catch {
    Add-Result 'HARNESS' '-' 'FAIL' ("unhandled: " + $_.Exception.Message)
} finally {
    $work = $WorkRoot
    Write-Step '清理：只停止本脚本启动的 JVM'
    $cleanup = @()
    foreach ($pidFile in @((Join-Path $work 'platform.pid'), (Join-Path $work 'ai.pid'), (Join-Path $work 'realapp.pid'))) {
        $id = $null
        if (Test-Path $pidFile) { $id = (Get-Content $pidFile | Select-Object -First 1) }
        if ($id -and $script:StartedProcesses -contains [int]$id) {
            try { Stop-Owned $pidFile 'P04(Platform|Ai)TestApplication|P04RealApplicationProbe'; $cleanup += "owned pid=$id stopped/already gone" }
            catch { Add-Result 'CLEANUP-owned-process' '-' 'FAIL' $_.Exception.Message; $cleanup += "cleanup failed pid=$id" }
        }
    }
    foreach ($line in $cleanup) { Write-Output ("  " + $line) }
    # 注意：这里的最后一次 JVM 是两态验收末尾那一轮（Integration 时为 system-false），
    # 不是"启用态最终日志"。启用态日志在 enabled-before-restart / enabled-after-restart，
    # 真实产品应用日志在 real-app-run/。目录名按实际内容命名，避免出处误读。
    Archive-JvmLogs 'last-disabled-state' $work
    Copy-SanitizedLog (Join-Path $work 'real-app-run\stdout.log') (Join-Path $script:Evidence 'real-app-run\stdout.log')
    Copy-SanitizedLog (Join-Path $work 'real-app-run\stderr.log') (Join-Path $script:Evidence 'real-app-run\stderr.log')
    if ($script:HttpLog.Count -gt 0) {
        $script:HttpLog | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 6 } | Set-Content -LiteralPath (Join-Path $script:Evidence 'http.jsonl') -Encoding UTF8
    }
    if ($script:DbBefore.Count -gt 0) { $script:DbBefore | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $script:Evidence 'db-before.json') -Encoding UTF8 }
    if ($script:DbAfter.Count -gt 0) { $script:DbAfter | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $script:Evidence 'db-after.json') -Encoding UTF8 }
    if ($script:BarrierEvidence.Count -gt 0) {
        $script:BarrierEvidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'barriers.json') -Encoding UTF8
    }
    $script:NativeCommands | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $script:Evidence 'native-commands.json') -Encoding UTF8
    $manifest['finishedUtc'] = (Get-Date).ToUniversalTime().ToString('o')
    $manifest['cleanup'] = $cleanup
    $manifest['nativeCommandCount'] = $script:NativeCommands.Count
    $manifest['nativeCommandsFile'] = 'native-commands.json'
    $manifest['nativeCommands'] = @($script:NativeCommands)
    $manifest['barrierCount'] = @($script:BarrierEvidence | Where-Object { $_.phase -eq 'release' }).Count
    $manifest['completedCaseSnapshots'] = @($script:DbAfter.Keys | ForEach-Object { [string]$_ })
    if ($Mode -eq 'Integration') {
        $missingCases = @($requiredCases | Where-Object { -not $script:DbAfter.Contains($_) })
        foreach ($caseId in $missingCases) { Add-Result "CASE-$caseId" '-' 'NOT_RUN' 'no completed four-table before/after snapshot' }
        Assert-That 'CASES-complete' 'G1-G4' ($missingCases.Count -eq 0) `
            ("required={0} completed={1} missing={2}" -f $requiredCases.Count,
                $script:DbAfter.Count, ($missingCases -join ','))
    }
    $manifest['resultCounts'] = @{
        pass = @($script:Results | Where-Object { $_.status -eq 'PASS' }).Count
        fail = @($script:Results | Where-Object { $_.status -eq 'FAIL' }).Count
        skip = @($script:Results | Where-Object { $_.status -in @('SKIP', 'NOT_RUN') }).Count
    }
    # 退出码口径（不降低）：Failures>0、或出现 FAIL、或出现 NOT_RUN/SKIP 都算失败——
    # 只允许一条例外，且理由必须是"该断言只能在包装器结束之后才能核对"：
    # acceptance-summary.json 由包装器在全部命令跑完后才写盘，任何被包装的轮次都看不到它；
    # 该自检由包装器收尾时自己完成（acceptance-summary.json 的 nonzeroUnexpected + wrapperExitCode）。
    # 其余 NOT_RUN（含缺靶场预检、缺失必需 case）在任何模式下仍然算失败，不放宽。
    $allowedNotRun = @('ACCEPTANCE-wrapper-selfcheck')
    $strictBad = @($script:Results | Where-Object {
            $_.status -eq 'FAIL' -or $_.status -eq 'SKIP' -or
            ($_.status -eq 'NOT_RUN' -and $allowedNotRun -notcontains $_.id) })
    $manifest['notRunAllowedInThisMode'] = @($allowedNotRun)
    if ($script:Failures -gt 0 -or $strictBad.Count -gt 0) { $exitCode = 1 }
    $manifest['strictBlockingResults'] = @($strictBad | ForEach-Object { $_.id + '=' + $_.status })
    # 本轮自身的入口退出码：§8.6 点名的四条顶层命令里，本条由本进程给出，其余三条来自包装器文件。
    $manifest['acceptanceCommands'] = @($script:AcceptanceCommands)
    $manifest['acceptanceCommandCount'] = @($script:AcceptanceCommands).Count
    $manifest['harnessEntryExitCode'] = $exitCode
    $selfEntry = [ordered]@{
        label = ("integration-or-unit-entry-" + $Mode); kind = 'run.ps1 入口（本进程）'
        command = ("powershell -NoProfile -File tools/p04-contract/run.ps1 -Mode {0} -EvidenceDir {1}" -f $Mode, $EvidenceDir)
        exitCode = $exitCode; expectsNonZero = $false; source = 'self'
    }
    $invocation = [ordered]@{
        mode = $Mode; executionId = $script:ExecutionId
        evidenceDir = $script:Evidence
        invocationLogPath = $InvocationLogPath
        preflightEntryExitCode = $PreflightEntryExitCode
        harnessEntryExitCode = $exitCode
        selfEntry = $selfEntry
        commands = @($script:AcceptanceCommands) + @([pscustomobject]$selfEntry)
    }
    $invocation | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'invocation-log.json') -Encoding UTF8
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'manifest.json') -Encoding UTF8
    $script:Results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'results.json') -Encoding UTF8
    Write-Step ("结果：PASS={0} FAIL={1} SKIP={2}；入口 exitCode={3}；证据目录 {4}" -f `
            $manifest['resultCounts'].pass, $manifest['resultCounts'].fail, $manifest['resultCounts'].skip,
            $exitCode, $script:Evidence)
}
exit $exitCode
