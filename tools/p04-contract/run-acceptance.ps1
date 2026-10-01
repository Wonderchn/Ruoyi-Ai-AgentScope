<#
.SYNOPSIS
  P0.4 验收外层包装器：逐条执行 §8.6 的顶层验收命令，采集**原生 exitCode**。

.DESCRIPTION
  为什么需要它：run.ps1 只能记录自己派生的子进程退出码（nativeCommands）。
  审阅方要求的是"§8.6 点名的每一条顶层命令 → 原生 exitCode → 日志"绑定，
  这必须由启动这些命令的那一层来采集。本脚本就是那一层。

  执行顺序（与自己记录的顺序一致）：
    1. mvn -o -B -ntp -f services/platform/pom.xml -Pdev clean verify
    2. mvn -o -B -ntp -f services/ai/pom.xml       -Pci  clean verify
    3. run.ps1 -Mode Unit        （--InvocationLogPath 传入 1/2 的退出码）
    4. run.ps1 -Mode Integration --LabContainer <不存在的容器>（确认缺靶场，期望非零）
    5. run.ps1 -Mode Integration （--InvocationLogPath 传入前四条命令及已验证预检证据）

  非零例外必须绑定到缺容器的结构化证据；无关构建、环境或断言失败不能充当预检成功。

  约束：只操作本 run 自己的证据目录与 workRoot；发布由维护者指令另行控制。
  本脚本保持 UTF-8 with BOM（PS 5.1 解析中文必需）。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/p04-contract/run-acceptance.ps1 `
      -EvidenceDir D:/AI-project/.scratch/p04/step2b/acceptance -LabContainer p04-pg-p04step2b
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [string]$RepoRoot  = 'D:\AI-project\Ruoyi-Ai-AgentScope',
    [string]$WorkRoot  = 'D:\AI-project\.scratch\p04\step2b\work',
    [string]$LabHost   = 'root@192.168.139.103',
    [string]$LabContainer = 'p04-pg-p04step2b',
    [string]$LabDb     = 'p04_contract',
    [string]$MissingLabContainer = 'p04-pg-p04step2b-missing',
    [string]$RunTag    = 'p04step2b',
    [int]$PlatformPort = 18082,
    [int]$AiPort = 19090,
    # true 时跳过两个 clean verify（仅在已单独验证过、只想补录其余退出码时使用）
    [switch]$SkipBuildRoots
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'acceptance-evidence.ps1')
if ($RunTag -notmatch '^p04[a-z0-9]+$' -or $LabContainer -cne "p04-pg-$RunTag" -or
        $MissingLabContainer -cne "p04-pg-$RunTag-missing") { throw 'container names must belong to the supplied P0.4 run tag' }
$script:RunPs1 = Join-Path $RepoRoot 'tools\p04-contract\run.ps1'
if (-not (Test-Path -LiteralPath $script:RunPs1)) { throw "run.ps1 not found: $($script:RunPs1)" }

$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$invRoot = Join-Path $EvidenceDir $runId
[void](New-Item -ItemType Directory -Force $invRoot)
$logDir = Join-Path $invRoot 'command-logs'
[void](New-Item -ItemType Directory -Force $logDir)
$invocationLog = Join-Path $invRoot 'invocation-commands.json'

function Invoke-AcceptanceCommand {
    param(
        [string]$Label, [string]$Kind, [string]$Exe, [string[]]$Arguments,
        [string]$LogName, [switch]$ExpectsNonZero
    )
    $utc = (Get-Date).ToUniversalTime().ToString('o')
    $log = Join-Path $logDir $LogName
    Write-Host ("### [{0}] {1} {2}" -f $Label, $Exe, ($Arguments -join ' '))
    $capture = Join-Path $env:TEMP ("p04-acc-" + [guid]::NewGuid().ToString('N') + ".log")
    $display = ("{0} {1}" -f $Exe, (@($Arguments | ForEach-Object {
        if ([string]$_ -match '(?i)password|secret|token') { '[REDACTED_ARGUMENT]' } else { $_ } }) -join ' '))
    try {
        $prev = Get-Location
        Set-Location $RepoRoot
        try {
            $previousPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                $global:LASTEXITCODE = $null
                & $Exe @Arguments > $capture 2>&1
                $code = $global:LASTEXITCODE
            } finally { $ErrorActionPreference = $previousPreference }
        } finally { Set-Location $prev }
        if (Test-Path -LiteralPath $capture) { Get-Content -LiteralPath $capture | Set-Content -LiteralPath $log -Encoding UTF8 }
    } finally {
        if (Test-Path -LiteralPath $capture) { Remove-Item -LiteralPath $capture -Force -ErrorAction SilentlyContinue }
    }
    if ($null -eq $code) { $code = -1 }
    $record = [pscustomobject]@{
        label = $Label; kind = $Kind; command = $display; log = $LogName
        utc = $utc; exitCode = [int]$code; expectsNonZero = [bool]$ExpectsNonZero
    }
    $script:Commands.Add($record) | Out-Null
    Write-Host ("    exitCode={0}" -f $code)
    return $record
}

$script:Commands = New-Object System.Collections.ArrayList

# ---------- 1/2：两个构建根 ----------
if (-not $SkipBuildRoots) {
    [void](Invoke-AcceptanceCommand -Label 'build-platform-clean-verify' -Kind 'mvn clean verify (platform)' -Exe 'mvn' -Arguments @('-o', '-B', '-ntp', '-f', 'services/platform/pom.xml', '-Pdev', 'clean', 'verify') -LogName 'platform-clean-verify.log')
    [void](Invoke-AcceptanceCommand -Label 'build-ai-clean-verify' -Kind 'mvn clean verify (ai)' -Exe 'mvn' -Arguments @('-o', '-B', '-ntp', '-f', 'services/ai/pom.xml', '-Pci', 'clean', 'verify') -LogName 'ai-clean-verify.log')
} else {
    Write-Host '### SkipBuildRoots: 未执行两个构建根（本轮 invocation 不覆盖它们）'
}

# 到目前为止的退出码：先落盘一次，供 Unit 使用（Unit 只应看到构建根）
function Write-InvocationLog {
    param([int]$PreflightExitCode = -1)
    [pscustomobject]@{
        wrapper = 'tools/p04-contract/run-acceptance.ps1'
        runTag = $RunTag; invocationRoot = $invRoot
        writtenUtc = (Get-Date).ToUniversalTime().ToString('o')
        commandCount = $script:Commands.Count
        preflightEntryExitCode = $PreflightExitCode
        commands = @($script:Commands)
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $invocationLog -Encoding UTF8
}

# 快照必须在**该命令执行完之后**再拷贝：否则某条命令的输入清单里永远看不到它自己。
Write-InvocationLog
$unitLogSnapshot = Join-Path $invRoot 'invocation-commands.unit.json'
Copy-Item -LiteralPath $invocationLog -Destination $unitLogSnapshot -Force

# ---------- 3：Unit ----------
$unitEvidence = Join-Path $EvidenceDir 'unit'
[void](Invoke-AcceptanceCommand -Label 'unit-entry' -Kind 'run.ps1 -Mode Unit' -Exe 'powershell' `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script:RunPs1,
        '-Mode', 'Unit', '-EvidenceDir', $unitEvidence, '-RunTag', $RunTag, '-WorkRoot', $WorkRoot,
        '-RepoRoot', $RepoRoot, '-LabHost', $LabHost, '-LabContainer', $LabContainer, '-LabDb', $LabDb,
        '-PlatformPort', "$PlatformPort", '-AiPort', "$AiPort",
        '-InvocationLogPath', $unitLogSnapshot) -LogName 'unit-entry.log')
Write-InvocationLog

# ---------- 4：缺靶场预检（期望非零；先跑，好让 Integration 的快照里能看到它的非零退出码） ----------
$preflightEvidence = Join-Path $EvidenceDir 'preflight-missing-lab'
$preflightDirectories = @(Get-ChildItem -LiteralPath $preflightEvidence -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
# 预检不传 -InvocationLogPath（它不需要看别人的记录）；它的"应当非零"由 -ExpectsNonZero 记录，
# 并由 Integration 从自己的输入清单里独立核对，不依赖事后自检。
$preflight = Invoke-AcceptanceCommand -Label 'preflight-missing-lab' -Kind 'run.ps1 -Mode Integration（不存在的靶场容器）' `
    -Exe 'powershell' -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script:RunPs1,
        '-Mode', 'Integration', '-EvidenceDir', $preflightEvidence, '-RunTag', $RunTag, '-WorkRoot', $WorkRoot,
        '-RepoRoot', $RepoRoot, '-LabHost', $LabHost, '-LabContainer', $MissingLabContainer, '-LabDb', $LabDb,
        '-PlatformPort', "$PlatformPort", '-AiPort', "$AiPort") -LogName 'preflight-missing-lab.log' -ExpectsNonZero
Write-InvocationLog -PreflightExitCode ([int]$preflight.exitCode)
# Integration 的输入清单在此刻拷贝：此时它已含两条构建根、Unit，以及带 expectsNonZero 的预检。
$newPreflight = @(Get-ChildItem -LiteralPath $preflightEvidence -Directory -ErrorAction SilentlyContinue |
    Where-Object { $preflightDirectories -notcontains $_.FullName })
$preflightPath = if ($newPreflight.Count -eq 1) { $newPreflight[0].FullName } else { '' }
$preflightValidation = Test-P04MissingLabEvidence -EvidencePath $preflightPath `
    -ExpectedContainer $MissingLabContainer -ExpectedRunTag $RunTag -EntryExitCode ([int]$preflight.exitCode)
$preflight | Add-Member -NotePropertyMembers @{ mode = 'Integration'; runTag = $RunTag
    labContainer = $MissingLabContainer; expectedFailure = 'MISSING_LAB_CONTAINER'
    preflightValidation = $preflightValidation }
Write-Host ("### 缺靶场原因校验：valid={0} error={1}" -f $preflightValidation.valid, $preflightValidation.error)
Write-InvocationLog -PreflightExitCode ([int]$preflight.exitCode)
$integrationLogSnapshot = Join-Path $invRoot 'invocation-commands.integration.json'
Copy-Item -LiteralPath $invocationLog -Destination $integrationLogSnapshot -Force
# ---------- 5：Integration（最后跑：它的快照已含预检那条 expectsNonZero 记录） ----------
$integrationEvidence = Join-Path $EvidenceDir 'integration'
[void](Invoke-AcceptanceCommand -Label 'integration-entry' -Kind 'run.ps1 -Mode Integration' -Exe 'powershell' `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script:RunPs1,
        '-Mode', 'Integration', '-EvidenceDir', $integrationEvidence, '-RunTag', $RunTag, '-WorkRoot', $WorkRoot,
        '-RepoRoot', $RepoRoot, '-LabHost', $LabHost, '-LabContainer', $LabContainer, '-LabDb', $LabDb,
        '-PlatformPort', "$PlatformPort", '-AiPort', "$AiPort",
        '-InvocationLogPath', $integrationLogSnapshot,
        '-PreflightEntryExitCode', "$([int]$preflight.exitCode)") -LogName 'integration-entry.log')
Write-InvocationLog -PreflightExitCode ([int]$preflight.exitCode)

# ---------- 汇总 ----------
Write-Host ''
Write-Host '### 顶层验收命令原生退出码'
$script:Commands | ForEach-Object {
    Write-Host ("  {0,-28} exit={1,-4} expectsNonZero={2}  log={3}" -f $_.label, $_.exitCode, $_.expectsNonZero, $_.log)
}
$bad = @($script:Commands | Where-Object {
    -not (Test-P04AcceptanceRecord -Record $_ -ExpectedRunTag $RunTag -PreflightEntryExitCode ([int]$preflight.exitCode)) })
$pfOk = Test-P04AcceptanceRecord -Record $preflight -ExpectedRunTag $RunTag -PreflightEntryExitCode ([int]$preflight.exitCode)
Write-Host ("### 非零且非预期：{0}；缺靶场预检非零：{1}" -f $bad.Count, $pfOk)
$wrapperExit = 0
if ($script:Commands.Count -ne 5 -or $bad.Count -gt 0 -or -not $pfOk) { $wrapperExit = 1 }
[pscustomobject]@{
    wrapper = 'tools/p04-contract/run-acceptance.ps1'; runId = $runId
    invocationRoot = $invRoot; invocationLog = $invocationLog
    commandCount = $script:Commands.Count
    nonzeroUnexpected = $bad.Count
    preflightExitCode = [int]$preflight.exitCode
    preflightEvidenceValid = [bool]$pfOk
    wrapperExitCode = $wrapperExit
    commands = @($script:Commands)
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $invRoot 'acceptance-summary.json') -Encoding UTF8

exit $wrapperExit
