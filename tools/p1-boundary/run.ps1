<#
.SYNOPSIS
  P1.2a「SaaS 旧身份 / 旧路由 / 无主体触发关闭」单元边界验收 runner（Spec 01 §6、00 §9）。

.DESCRIPTION
  两种模式，语义完全不同，别混：

    Unit        —— 只跑 Spec 01 §6 列明的 5 条原生 mvn 命令（覆盖 6 个必跑类），逐类核对
                   services/<domain>/<module>/target/surefire-reports/TEST-<fqcn>.xml：
                   tests>0 且 failures=errors=skips=0。XML 缺失 / 陈旧（本轮未重写）/
                   tests=0 / 有 skip，一律 FAIL —— reactor 的 BUILD SUCCESS 不能证明指定类跑过。

    Integration —— 规格要求的「两个真实产品 jar + runner 自有合成 PG17/pgvector、Redis、
                   S3 兼容 mock」端到端验收（B01–B13）。预检拿不到可用容器运行时/合成库时，
                   被闸门挡住的检查写 NOT_RUN + 确切探测证据，**exit 0**（缺环境 ≠ PASS ≠ 假 FAIL）。
                   happy path 已按契约实现但本机不可执行，见 README「当前 NOT_RUN 与原因」。

  约束（照做，别绕）：
    * 按 Windows PowerShell 5.1 编写（本机无 pwsh 7）：不用 -SkipHttpErrorCheck / 三元运算符 /
      ForEach-Object -Parallel；HTTP 一律走 System.Net.Http.HttpClient。
    * 口令/密钥只经**子进程环境**传给子进程；不落盘、不进证据；写盘前统一脱敏。
    * 只停止本脚本自己启动的 PID；停止前同时核对 PID 与命令行；绝不通杀 java。
    * 删除前解析绝对路径并确认位于 $WorkRoot 或 $EvidenceDir 之下，否则拒绝（留证，不删）。
    * 绝不连接、也绝不冒充本机/远端已有业务库或缓存；检测到就拒绝并留证（refusals.json）。
    * 退出码：Unit —— 任一检查 FAIL 即 exit 1；Integration —— 环境缺失时被闸门挡住的检查全部
      NOT_RUN 则 exit 0，任何 FAIL 仍 exit 1；-EvidenceDir 相对路径 / 落在仓库内属用法拒绝，exit 2。

.EXAMPLE
  powershell -NoProfile -File tools/p1-boundary/run.ps1 -Mode Unit -EvidenceDir D:/AI-project/mydocs/p1/evidence/full/p12a-unit
  powershell -NoProfile -File tools/p1-boundary/run.ps1 -Mode Integration -EvidenceDir D:/AI-project/mydocs/p1/evidence/full/p12a
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Unit', 'Integration')][string]$Mode,
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [string]$RunTag = ('p1b' + (Get-Date -Format 'yyyyMMddHHmmss')),
    [string]$RepoRoot = 'D:\AI-project\Ruoyi-Ai-AgentScope',
    [string]$WorkRoot = 'D:\AI-project\.scratch\p1-boundary\work',
    # 远端容器宿主（可选）。本机没有容器运行时时，验收环境可以是既有测试 VM：
    # 容器命令经 ssh 在远端执行，合成 PG/Redis/S3 建在远端，本机只跑 jar 与断言。
    # 语义边界：-RemoteHost 只把"执行容器命令的位置"搬到远端，**不**降低任何检查强度，
    # 也**不**允许复用远端既有容器——归属仍由本脚本自己的 run tag label 判定。
    [string]$RemoteHost = '',
    [string]$SshKeyPath = '',
    [int]$SshConnectTimeoutSeconds = 15,
    # 端口默认值 = 绑定期做一次空闲端口扫描（区间内第一个可绑定端口）；0 = 扫描失败，交给 ENV-ports 判 FAIL。
    [int]$PlatformPort = $( $chosen = 0; foreach ($c in 18082..18160) { try { $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $c); $l.Start(); $l.Stop(); $chosen = $c; break } catch { } }; $chosen ),
    [int]$AiPort = $( $chosen = 0; foreach ($c in 19090..19168) { try { $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $c); $l.Start(); $l.Stop(); $chosen = $c; break } catch { } }; $chosen ),
    # 评审辅助（不属于验收契约）：预检判定环境缺失时，仍执行两条 clean verify 构建根。
    # 只会让 BUILD-* 从 NOT_RUN 变成真实结果，永远不会把 NOT_RUN 变成 PASS。
    [switch]$ForceBuildRoots
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.IO.Compression.FileSystem

# =========================== 脚本状态 ===========================
$script:JdkHome = 'D:\develop\java\jdk-17.0.18.8-hotspot'
$script:MavenExe = 'D:\develop\apache-maven-3.9.1\bin\mvn.cmd'
$script:SpecPaths = @(
    'D:\AI-project\mydocs\p1\01-p1-first-unit-spec.md',
    'D:\AI-project\mydocs\p1\00-p1-plan.md'
)
$script:Failures = 0
$script:Results = New-Object System.Collections.ArrayList
$script:Refusals = New-Object System.Collections.ArrayList
$script:Probes = New-Object System.Collections.ArrayList
$script:NativeCommands = New-Object System.Collections.ArrayList
$script:Surefire = New-Object System.Collections.ArrayList
$script:Cleanup = New-Object System.Collections.ArrayList
$script:HttpLog = New-Object System.Collections.ArrayList
$script:GatedNotRun = New-Object System.Collections.ArrayList
$script:StartedProcesses = @()
$script:OwnedContainers = New-Object System.Collections.ArrayList
$script:ExecutionId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$script:RunStartedUtc = (Get-Date).ToUniversalTime()
$script:EnvGateBlocked = $false
$script:GateReason = 'NOT_EVALUATED'
$script:GateReasonDetail = ''
$script:GateReasonShort = ''
$script:Runtime = $null
$script:Secrets = [ordered]@{}
$script:ProviderKeyOverrides = @()
$script:DbBefore = $null
$script:DbAfter = $null
$script:DbDelta = $null
$script:Facts = $null
$script:PlatformJarEntries = @()
$script:PlatformJarConfig = ''
$script:PlatformBase = ''
$script:AiBase = ''
$script:IllegalBootResults = @()
$script:ComposeProject = ''
$script:ComposePath = ''
$script:PgContainer = ''
$script:RedisContainer = ''
$script:S3Container = ''
$script:PgPort = 0
$script:RedisPort = 0
$script:S3Port = 0
$script:RemoteMode = $false
$script:SshExe = ''
$script:RemoteComposeDir = ''

# -RemoteHost 的**最早**生效点。不能等到容器运行时预检才设置：Integration 的端口预检
# （Invoke-IntegrationPortCheck）在预检之前运行，而端口是否空闲必须在容器宿主上判定。
# 若此处不生效，远端模式下端口预检会退回探测本机，从而给出与真实宿主无关的结论。
if ($RemoteHost) {
    $script:RemoteMode = $true
    $sshCmd = Get-Command 'ssh' -ErrorAction SilentlyContinue
    if ($null -ne $sshCmd) { $script:SshExe = $sshCmd.Source }
}

# Spec 01 §5/§6 的精确路径与 FQCN。expectedFqcn 是规格路径推导出的身份：
# 若 XML 的 testsuite name 与之不符（同名类跑到别的包），按 FAIL 处理并写清差异。
$script:RequiredClasses = @(
    [pscustomobject]@{
        name = 'P1LegacyAssemblyBoundaryTest'; expectedFqcn = 'org.ruoyi.config.P1LegacyAssemblyBoundaryTest'
        domain = 'platform'; module = 'ruoyi-admin'; spec = 'Spec 01 §5 admin 装配护栏'
        source = 'services/platform/ruoyi-admin/src/test/java/org/ruoyi/config/P1LegacyAssemblyBoundaryTest.java'
    },
    [pscustomobject]@{
        name = 'SaasCapabilityBoundaryTest'; expectedFqcn = 'com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundaryTest'
        domain = 'ai'; module = 'framework'; spec = 'Spec 01 §5 关闭判定/受控异常'
        source = 'services/ai/framework/src/test/java/com/nageoffer/ai/ragent/framework/integration/SaasCapabilityBoundaryTest.java'
    },
    [pscustomobject]@{
        name = 'P04PlatformAuthorizationClientTest'; expectedFqcn = 'com.nageoffer.ai.ragent.framework.security.P04PlatformAuthorizationClientTest'
        domain = 'ai'; module = 'framework'; spec = 'P0.4 已合并安全回归（22 项真实 client 边界）'
        source = 'services/ai/framework/src/test/java/com/nageoffer/ai/ragent/framework/security/P04PlatformAuthorizationClientTest.java'
    },
    [pscustomobject]@{
        name = 'SaasEntryBoundaryTest'; expectedFqcn = 'com.nageoffer.ai.ragent.user.config.SaasEntryBoundaryTest'
        domain = 'ai'; module = 'system'; spec = 'Spec 01 §5 真 handler MVC 关闭'
        source = 'services/ai/system/src/test/java/com/nageoffer/ai/ragent/user/config/SaasEntryBoundaryTest.java'
    },
    [pscustomobject]@{
        name = 'SaTokenConfigTest'; expectedFqcn = 'com.nageoffer.ai.ragent.user.config.SaTokenConfigTest'
        domain = 'ai'; module = 'system'; spec = '既有 SaToken 配置回归'
        source = 'services/ai/system/src/test/java/com/nageoffer/ai/ragent/user/config/SaTokenConfigTest.java'
    },
    [pscustomobject]@{
        name = 'P1TriggerBoundaryTest'; expectedFqcn = 'com.nageoffer.ai.ragent.boundary.P1TriggerBoundaryTest'
        domain = 'ai'; module = 'rag'; spec = 'Spec 01 §5 listener/checker/job/initializer 直接调用'
        source = 'services/ai/rag/src/test/java/com/nageoffer/ai/ragent/boundary/P1TriggerBoundaryTest.java'
    },
    [pscustomobject]@{
        name = 'P04AssemblyBoundaryTest'; expectedFqcn = 'com.nageoffer.ai.ragent.boundary.P04AssemblyBoundaryTest'
        domain = 'ai'; module = 'rag'; spec = 'P0.4 已合并装配护栏'
        source = 'services/ai/rag/src/test/java/com/nageoffer/ai/ragent/boundary/P04AssemblyBoundaryTest.java'
    },
    [pscustomobject]@{
        name = 'P1McpStartupBoundaryTest'; expectedFqcn = 'com.nageoffer.ai.ragent.agent.tool.P1McpStartupBoundaryTest'
        domain = 'ai'; module = 'agent'; spec = 'Spec 01 §5 关闭时 MCP 零请求、Bean 可构造/销毁'
        source = 'services/ai/agent/src/test/java/com/nageoffer/ai/ragent/agent/tool/P1McpStartupBoundaryTest.java'
    }
)

# Spec 01 §6 的 5 条原生 mvn 命令（覆盖上面 6 个必跑类 + 2 个回归类）。逐条原生调用、逐条采集 exit。
$script:UnitInvocations = @(
    [pscustomobject]@{ id = 'platform-ruoyi-admin'; pom = 'services/platform/pom.xml'; profile = '-Pdev'
        module = 'ruoyi-admin'; tests = @('P1LegacyAssemblyBoundaryTest') },
    [pscustomobject]@{ id = 'ai-framework'; pom = 'services/ai/pom.xml'; profile = '-Pci'
        module = 'framework'; tests = @('SaasCapabilityBoundaryTest', 'P04PlatformAuthorizationClientTest') },
    [pscustomobject]@{ id = 'ai-system'; pom = 'services/ai/pom.xml'; profile = '-Pci'
        module = 'system'; tests = @('SaasEntryBoundaryTest', 'SaTokenConfigTest') },
    [pscustomobject]@{ id = 'ai-rag'; pom = 'services/ai/pom.xml'; profile = '-Pci'
        module = 'rag'; tests = @('P1TriggerBoundaryTest', 'P04AssemblyBoundaryTest') },
    [pscustomobject]@{ id = 'ai-agent'; pom = 'services/ai/pom.xml'; profile = '-Pci'
        module = 'agent'; tests = @('P1McpStartupBoundaryTest') }
)

# Integration 的「环境闸门打开后才可能真实执行」检查清单：单一事实来源。
# 预检判定环境缺失时逐条写 NOT_RUN（id 记进 $script:GatedNotRun）；收尾用
# Assert-IntegrationInventory 核对「计划清单 == 实际产出」，防止静默漏项或把 NOT_RUN 伪装成 PASS。
$script:GatedInventoryPlan = @(
    [pscustomobject]@{ id = 'BUILD-platform-clean-verify'; target = 'G0'
        detail = 'mvn -o -B -ntp -f services/platform/pom.xml -Pdev clean verify（本轮两 jar 来源）' },
    [pscustomobject]@{ id = 'BUILD-ai-clean-verify'; target = 'G0'
        detail = 'mvn -o -B -ntp -f services/ai/pom.xml -Pci clean verify（本轮两 jar 来源）' },
    [pscustomobject]@{ id = 'ENV-p04-container-isolation'; target = 'G0'
        detail = '证明本轮不复用 P0.4 容器 / 远端 LabHost（按 owner label 过滤本机容器）' },
    [pscustomobject]@{ id = 'ENV-compose-up'; target = 'G0'
        detail = 'runner 自有 compose：PG17+pgvector / Redis / S3 mock，唯一 owner label' },
    [pscustomobject]@{ id = 'ENV-db-accounts'; target = 'G0'
        detail = '独立 migrate 与 app 账号（platform_app / ai_app）+ 双 schema + 迁移原字节' },
    [pscustomobject]@{ id = 'ENV-fixtures'; target = 'G0'
        detail = '两个合成 tenant 的 fixture（仅合成记录，随机口令）' },
    [pscustomobject]@{ id = 'PROBE-availability'; target = 'G0'
        detail = 'Spec 01 §5 的 P1ProductBoundaryProbe 存在（Bean/Mapping/注册数事实来源）' },
    [pscustomobject]@{ id = 'PROBE-ai-facts'; target = 'G0'
        detail = '实际 RagentApplication 进程写出 probe facts（mappings/beans/注册数/调用计数）' },
    [pscustomobject]@{ id = 'BOOT-platform-jar'; target = 'G0'
        detail = '真实 ruoyi-admin.jar 启动 + ApplicationReady / 端口 / 进程存活' },
    [pscustomobject]@{ id = 'BOOT-ai-jar'; target = 'G0'
        detail = '真实 bootstrap jar 默认配置启动 + 显式 p04=false 启动' },
    [pscustomobject]@{ id = 'LISTENER-registration-counts'; target = 'G1'
        detail = '三个旧 listener 与两个旧 TransactionChecker 注册数为 0' },
    [pscustomobject]@{ id = 'CHECKER-registration-counts'; target = 'G1'
        detail = 'checker 直接调用不查库（Mapper 调用数 0、Delegate 注册数 0）' },
    [pscustomobject]@{ id = 'DB-snapshot-before'; target = 'G1'
        detail = 'B01–B13 之前的全表行数 + 行哈希快照' },
    [pscustomobject]@{ id = 'DB-snapshot-after'; target = 'G1'
        detail = 'B01–B13 之后的全表行数 + 行哈希快照' },
    [pscustomobject]@{ id = 'DB-no-business-delta'; target = 'G1'
        detail = '前后快照逐表行数 + 行哈希一致（防 update/delete 漏检）' },
    [pscustomobject]@{ id = 'CASES-complete'; target = 'G1-G4'
        detail = 'B01–B13 全部真实执行（不完整不得判完成）' },
    [pscustomobject]@{ id = 'CLEANUP-owned-containers'; target = 'G0'
        detail = '按 owner label 销毁本轮自有容器/卷，且不动任何非本轮资源' },
    [pscustomobject]@{ id = 'CLEANUP-synthetic-secrets'; target = 'G0'
        detail = '本轮随机口令/密钥从进程环境清除且未写入证据' }
)
foreach ($caseId in @('B01', 'B02', 'B03', 'B04', 'B05', 'B06', 'B07', 'B08', 'B09', 'B10', 'B11', 'B12', 'B13')) {
    $script:GatedInventoryPlan += [pscustomobject]@{ id = $caseId; target = 'B'
        detail = ('Spec 01 §7 ' + $caseId + ' 真实两 jar / 合成环境验收') }
}
# 环境无关、任何 Integration 轮都必须真实执行且（环境缺失时）仍须 PASS 的检查 id。
$script:AlwaysRunIds = @(
    'ENV-jdk17', 'ENV-maven', 'ENV-evidence-path', 'ENV-workroot-safety', 'ENV-ports',
    'ENV-substitute-scan', 'ENV-provider-key-isolation', 'CLEANUP-owned-processes'
)
# 恒真但由预检判 PASS/NOT_RUN 的检查 id（环境缺失时必须是 NOT_RUN，不得是 PASS）。
$script:PreflightGatedIds = @('ENV-container-runtime')

# =========================== 通用帮助函数 ===========================
function Write-Step([string]$m) { Write-Output ("### " + $m) }

function Add-Result([string]$id, [string]$target, [string]$status, [string]$detail) {
    # 状态集：PASS / FAIL / NOT_RUN / REFUSED。
    #   NOT_RUN：只在「环境缺失/被闸门挡住」时出现，必须带确切原因；Unit 模式下不算通过。
    #   REFUSED：只用于「检测到但拒绝使用」（例如本机已有业务库/缓存）：不算 PASS、不算 FAIL、不阻塞。
    [void]$script:Results.Add([pscustomobject]@{ id = $id; target = $target; status = $status; detail = $detail })
    if ($status -eq 'FAIL') { $script:Failures++; Write-Output ("  [FAIL]    {0} {1}" -f $id, $detail) }
    elseif ($status -eq 'PASS') { Write-Output ("  [ok]      {0} {1}" -f $id, $detail) }
    elseif ($status -eq 'REFUSED') { Write-Output ("  [refused] {0} {1}" -f $id, $detail) }
    else { Write-Output ("  [{0}] {1} {2}" -f $status, $id, $detail) }
}
function Assert-That([string]$id, [string]$target, [bool]$cond, [string]$detail) {
    if ($cond) { Add-Result $id $target 'PASS' $detail } else { Add-Result $id $target 'FAIL' $detail }
}
function Add-Refusal([string]$id, [string]$reason, [string]$action) {
    [void]$script:Refusals.Add([pscustomobject]@{ id = $id; reason = $reason; action = $action })
    Add-Result $id 'G0' 'REFUSED' ($reason + ' :: ' + $action)
}
function Add-Probe([string]$id, [string]$probe, [string]$result, [string]$detail) {
    [void]$script:Probes.Add([pscustomobject]@{ id = $id; probe = $probe; result = $result; detail = $detail })
    Write-Output ("  <probe>   {0} :: {1} -> {2} {3}" -f $id, $probe, $result, $detail)
}
function Test-UnderRootLoose([string]$Path, [string]$Root) {
    # Path 严格位于 Root 之下（不等于 Root 自身）。
    if (-not $Path -or -not $Root) { return $false }
    $p = [IO.Path]::GetFullPath($Path)
    $r = [IO.Path]::GetFullPath($Root)
    if (-not $r.EndsWith('\')) { $r = $r + '\' }
    return $p.StartsWith($r, [StringComparison]::OrdinalIgnoreCase)
}
function Test-UnderOwnedRoots([string]$Path) {
    $p = [IO.Path]::GetFullPath($Path)
    return ((Test-UnderRootLoose $p $script:WorkRoot) -or (Test-UnderRootLoose $p $script:Evidence))
}
function Remove-OwnedPath([string]$Path, [string]$Reason) {
    # 删除前必须解析绝对路径并确认在 $WorkRoot 或 $EvidenceDir 之下；否则拒绝并留证。
    $full = [IO.Path]::GetFullPath($Path)
    if (-not (Test-UnderOwnedRoots $full)) {
        [void]$script:Cleanup.Add([pscustomobject]@{ id = 'CLEANUP-refused-path'; target = $full
                action = 'delete'; result = 'REFUSED'; detail = 'outside WorkRoot/EvidenceDir' })
        Add-Result 'CLEANUP-refused-path' 'G0' 'REFUSED' ("refused to delete {0} ({1}): outside WorkRoot/EvidenceDir" -f $full, $Reason)
        return $false
    }
    if (-not (Test-Path -LiteralPath $full)) {
        [void]$script:Cleanup.Add([pscustomobject]@{ id = 'CLEANUP-owned-path'; target = $full
                action = 'delete'; result = 'ABSENT'; detail = $Reason })
        return $true
    }
    Remove-Item -LiteralPath $full -Recurse -Force
    $gone = -not (Test-Path -LiteralPath $full)
    [void]$script:Cleanup.Add([pscustomobject]@{ id = 'CLEANUP-owned-path'; target = $full
            action = 'delete'; result = $(if ($gone) { 'REMOVED' } else { 'FAILED' }); detail = $Reason })
    if (-not $gone) { Add-Result 'CLEANUP-owned-path' 'G0' 'FAIL' ("owned path still present after delete: " + $full) }
    return $gone
}
function New-RandomSecret([int]$Bytes = 24) {
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    return ([Convert]::ToBase64String($buffer)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
function Protect-LogText([string]$Content) {
    if (-not $Content) { return '' }
    $Content = $Content -replace '\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\b', '[REDACTED]'
    $Content = $Content -replace '(?i)\bBearer\s+[A-Za-z0-9._~+/-]{20,}', 'Bearer [REDACTED]'
    foreach ($name in @($script:Secrets.Keys)) {   # 本轮随机口令：即使被误打印也不落盘
        $value = [string]$script:Secrets[$name]
        if ($value.Length -ge 12) { $Content = $Content.Replace($value, '[REDACTED]') }
    }
    return $Content
}
function Protect-NativeArgument([string]$Argument) {
    $a = [string]$Argument
    if ($a -match '(?i)(?:password|passwd|secret|token|authorization|access[_-]?key|api[_-]?key)\s*=') { return '[REDACTED_ARGUMENT]' }
    if ($a -match '^eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.') { return '[REDACTED_ARGUMENT]' }
    foreach ($name in @($script:Secrets.Keys)) {
        $value = [string]$script:Secrets[$name]
        if ($value.Length -ge 12 -and $a.Contains($value)) { return '[REDACTED_ARGUMENT]' }
    }
    return $a
}
function New-CommandLine([string]$Exe, [string[]]$Arguments) {
    $parts = @($Exe)
    foreach ($a in $Arguments) {
        $s = [string]$a
        if ($s -match '[\s''"]') { $parts += ("'" + ($s -replace "'", "''") + "'") } else { $parts += $s }
    }
    return ($parts -join ' ')
}
function Invoke-NativeCapture([string]$Exe, [string[]]$Arguments, [string]$LogName = '', [string]$WorkDir = '') {
    # 原生调用统一走这里：显式取退出码，记录可审阅的命令行（已脱敏）与耗时。
    $started = (Get-Date).ToUniversalTime()
    $missing = $false
    if (-not (Test-Path -LiteralPath $Exe)) {
        if (-not (Get-Command $Exe -ErrorAction SilentlyContinue)) { $missing = $true }
    }
    $lines = @()
    $code = 127
    if ($missing) {
        $lines = @("command not found: $Exe")
    } else {
        $before = Get-Location
        if ($WorkDir) { Set-Location -LiteralPath $WorkDir }
        $prev = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $out = & $Exe @Arguments 2>&1
            $code = $LASTEXITCODE
            if ($null -eq $code) { $code = 0 }
        } catch {
            $lines = @("native invocation threw: " + $_.Exception.Message)
            $code = 127
        } finally {
            $ErrorActionPreference = $prev
            if ($WorkDir) { Set-Location -LiteralPath $before }
        }
        $lines += @($out | ForEach-Object {
                if ($_ -is [System.Management.Automation.ErrorRecord]) { [string]$_.Exception.Message } else { [string]$_ }
            })
    }
    $finished = (Get-Date).ToUniversalTime()
    $text = ($lines -join "`r`n")
    if ($LogName) {
        $path = Join-Path $script:Evidence $LogName
        $parent = Split-Path $path -Parent
        if ($parent) { [void](New-Item -ItemType Directory -Force -Path $parent) }
        [IO.File]::WriteAllText($path, (Protect-LogText $text), (New-Object Text.UTF8Encoding($false)))
    }
    $recordArgs = @($Arguments | ForEach-Object { Protect-NativeArgument ([string]$_) })
    $record = [pscustomobject]@{
        index = $script:NativeCommands.Count
        startedUtc = $started.ToString('o'); finishedUtc = $finished.ToString('o')
        durationMs = [int]($finished - $started).TotalMilliseconds
        executable = $Exe; arguments = $recordArgs; argumentCount = @($Arguments).Count
        commandLine = (New-CommandLine $Exe $recordArgs)
        executableMissing = $missing
        exitCode = [int]$code
        logFile = $LogName
    }
    [void]$script:NativeCommands.Add($record)
    return [pscustomobject]@{ Output = $lines; Text = $text; ExitCode = [int]$code; Record = $record }
}
function Test-TcpEndpoint([string]$TargetHost, [int]$Port, [int]$TimeoutMs = 700) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($TargetHost, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        $client.EndConnect($iar)
        return $true
    } catch { return $false } finally { $client.Close() }
}
function Test-PortFree([int]$Port) {
    # 端口是否空闲必须在**容器宿主**上判定，而不是本机。
    # 远端模式下合成 PG/Redis/S3 发布在 VM 的端口上：只看本机，
    # 会让一个在 VM 上已被占用的端口通过预检，然后 compose up 才失败——
    # 而那种失败信息会被误读成"环境不可用"。
    if ($script:RemoteMode) { return Test-RemotePortFree $Port }
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start(); $listener.Stop()
        return $true
    } catch { return $false }
}
function Test-RemotePortFree([int]$Port) {
    # 在容器宿主上探测：/dev/tcp 不需要额外工具，也不依赖 ss/netstat 的输出格式。
    #
    # 这里**不**走 Invoke-RemoteRuntime：那条路径把每个参数按 POSIX 单引号引用，
    # 对 `docker ps --format ...` 这类"参数即参数"的命令是对的，但对本探测这种复合
    # shell 命令会把整条命令变成一个被引用的**单词**，远端 shell 于是去找一个叫
    # "if (echo > /dev/tcp/...)" 的文件（实测报 No such file or directory，
    # 而退出码非 0 又被本函数判为"端口不空闲"，于是把空闲端口误报成占用）。
    # 探测语句由本函数自己构造、不含外部输入，直接作为单个 ssh 参数传递即可。
    $probe = 'if (echo > /dev/tcp/127.0.0.1/' + $Port + ') >/dev/null 2>&1; then echo BUSY; else echo FREE; fi'
    $sshArgs = @('-o', 'BatchMode=yes', '-o', 'LogLevel=ERROR',
        '-o', ('ConnectTimeout=' + $SshConnectTimeoutSeconds))
    if ($SshKeyPath) { $sshArgs += @('-i', $SshKeyPath) }
    $sshArgs += @($RemoteHost, $probe)
    $r = Invoke-NativeCapture $script:SshExe $sshArgs 'preflight-remote-port.log' $RepoRoot
    $text = ((@($r.Output) -join ' ') -replace '\s+', ' ').Trim()
    # 探测本身失败（ssh 不通等）时**不**当作空闲：宁可让预检判失败，也不要盲起容器。
    if ($r.ExitCode -ne 0) { return $false }
    return ($text -match 'FREE')
}
function Get-FreePortInRange([int]$From, [int]$To) {
    foreach ($candidate in $From..$To) { if (Test-PortFree $candidate) { return $candidate } }
    return 0
}
function Get-ShanghaiStamp([datetime]$Utc) {
    $tz = $null
    foreach ($tzId in @('China Standard Time', 'Asia/Shanghai')) {
        try { $tz = [System.TimeZoneInfo]::FindSystemTimeZoneById($tzId); break } catch { }
    }
    if ($null -eq $tz) { return $null }
    $local = [System.TimeZoneInfo]::ConvertTimeFromUtc($Utc, $tz)
    return [pscustomobject]@{ timeZoneId = $tz.Id; iso = $local.ToString('yyyy-MM-ddTHH:mm:sszzz') }
}
function Get-Sha256([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLower()
}
function Get-ResultRow([string]$Id) {
    # 注意：单元素 @() 会被管道解包成标量对象（PS 5.1 上标量 .Count 为 $null），
    # 因此用逗号包一层，保证调用方永远拿到真正的数组。
    return , @($script:Results | Where-Object { $_.id -ceq $Id })
}

# ---------- 进程所有权（只动自己的） ----------
function Start-OwnedProcess([string]$Exe, [string[]]$Arguments, [string]$WorkDir, [string]$PidFile) {
    [void](New-Item -ItemType Directory -Force -Path $WorkDir)
    $stdout = Join-Path $WorkDir 'stdout.log'
    $stderr = Join-Path $WorkDir 'stderr.log'
    Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath $Exe -ArgumentList $Arguments -WorkingDirectory $WorkDir `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $PidFile -Value ([string]$p.Id) -Encoding UTF8
    $script:StartedProcesses += $p.Id
    return $p
}
function Stop-OwnedProcess([string]$PidFile, [string]$CommandMatch, [string]$Label) {
    # 只停止本脚本启动过、且 PID 归属 + 命令行双重对得上的进程。
    if (-not (Test-Path -LiteralPath $PidFile)) {
        [void]$script:Cleanup.Add([pscustomobject]@{ id = 'CLEANUP-owned-process'; target = $Label
                action = 'stop'; result = 'NO_PID_FILE'; detail = $PidFile })
        return
    }
    $raw = (Get-Content -LiteralPath $PidFile -Encoding UTF8 | Select-Object -First 1)
    $processId = 0
    if (-not [int]::TryParse([string]$raw, [ref]$processId)) {
        Add-Result 'CLEANUP-owned-process' 'G0' 'FAIL' ("unreadable pid file {0}: '{1}'" -f $PidFile, [string]$raw)
        return
    }
    if ($script:StartedProcesses -notcontains $processId) {
        Add-Result 'CLEANUP-owned-process' 'G0' 'FAIL' ("pid {0} in {1} was not started by this run; refusing to stop" -f $processId, $PidFile)
        return
    }
    $proc = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $processId) -ErrorAction SilentlyContinue
    if ($null -eq $proc) {
        [void]$script:Cleanup.Add([pscustomobject]@{ id = 'CLEANUP-owned-process'; target = $Label
                action = 'stop'; result = 'ALREADY_GONE'; detail = ("pid=" + $processId) })
        return
    }
    if (-not ($proc.CommandLine -match $CommandMatch)) {
        Add-Result 'CLEANUP-owned-process' 'G0' 'FAIL' ("refusing to stop pid {0}: command line does not match '{1}'" -f $processId, $CommandMatch)
        return
    }
    Stop-Process -Id $processId -Force
    for ($i = 0; $i -lt 20; $i++) {
        if (-not (Get-Process -Id $processId -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Milliseconds 250
    }
    $alive = $null -ne (Get-Process -Id $processId -ErrorAction SilentlyContinue)
    [void]$script:Cleanup.Add([pscustomobject]@{ id = 'CLEANUP-owned-process'; target = $Label
            action = 'stop'; result = $(if ($alive) { 'FAILED' } else { 'STOPPED' }); detail = ("pid=" + $processId) })
    if ($alive) { Add-Result 'CLEANUP-owned-process' 'G0' 'FAIL' ("owned process failed to stop: " + $processId) }
}
function Wait-ProcessExit($Process, [int]$TimeoutSec) {
    for ($i = 0; $i -lt ($TimeoutSec * 4); $i++) {
        if ($Process.HasExited) { break }
        Start-Sleep -Milliseconds 250
        try { $Process.Refresh() } catch { }
    }
    try { $Process.Refresh() } catch { }
    return $Process.HasExited
}
function Copy-SanitizedLog([string]$From, [string]$To) {
    if (-not (Test-Path -LiteralPath $From -PathType Leaf)) { return }
    $parent = Split-Path $To -Parent
    if ($parent) { [void](New-Item -ItemType Directory -Force -Path $parent) }
    [IO.File]::WriteAllText($To, (Protect-LogText ([IO.File]::ReadAllText($From))), (New-Object Text.UTF8Encoding($false)))
}

# ---------- 工具链 ----------
function Initialize-Toolchain {
    Write-Step '工具链预检：JDK17 + Maven（Spec 01 §6）'
    $env:JAVA_HOME = $script:JdkHome
    $javaExe = Join-Path $script:JdkHome 'bin\java.exe'
    $env:PATH = (Join-Path $script:JdkHome 'bin') + ';' + $env:PATH
    $jdkOk = Test-Path -LiteralPath $javaExe
    $version = $null
    if ($jdkOk) {
        $r = Invoke-NativeCapture $javaExe @('-version') 'env-java-version.log'
        $version = (@($r.Output | Where-Object { $_ -match '^(?:openjdk|java) version ' } | Select-Object -First 1) -join '')
    }
    $is17 = ($version -match '\b17\.')
    Assert-That 'ENV-jdk17' 'G0' ($jdkOk -and $is17) `
        ("JAVA_HOME={0} exists={1} java='{2}'" -f $script:JdkHome, $jdkOk, $version)

    $mavenExe = $script:MavenExe
    if (-not (Test-Path -LiteralPath $mavenExe)) {
        $cmd = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
        if ($null -eq $cmd) { $cmd = Get-Command 'mvn' -ErrorAction SilentlyContinue }
        if ($null -ne $cmd) { $mavenExe = $cmd.Source }
    }
    $maven = Invoke-NativeCapture $mavenExe @('-version') 'env-maven-version.log'
    $mavenOk = ($maven.ExitCode -eq 0 -and $maven.Text -match 'Apache Maven')
    Assert-That 'ENV-maven' 'G0' $mavenOk ("exe={0} exit={1}" -f $mavenExe, $maven.ExitCode)
    $script:MavenExe = $mavenExe
    return [pscustomobject]@{ java = $version; javaHome = $script:JdkHome; mavenExe = $mavenExe
        maven = ((@($maven.Output | Where-Object { $_ -match '^Apache Maven' } | Select-Object -First 1)) -join '') }
}

# =========================== Unit 模式 ===========================
function Get-RequiredClass([string]$Name) {
    $found = @($script:RequiredClasses | Where-Object { $_.name -ceq $Name })
    if ($found.Count -ne 1) { throw ("unknown required class: " + $Name) }
    return $found[0]
}
function Read-SurefireSuite([string]$XmlPath) {
    $xml = [xml](Get-Content -LiteralPath $XmlPath -Raw -Encoding UTF8)
    $suite = $xml.testsuite
    if ($null -eq $suite) { throw ("no <testsuite> root: " + $XmlPath) }
    return [pscustomobject]@{
        name = [string]$suite.name
        tests = [int]$suite.tests; failures = [int]$suite.failures
        errors = [int]$suite.errors; skipped = [int]$suite.skipped
        timeSeconds = [string]$suite.time; caseCount = @($suite.testcase).Count
    }
}
function Get-UnitClassReport($Class, $Invocation, [datetime]$StartedUtc) {
    $moduleDir = Join-Path (Join-Path $RepoRoot ("services\" + $Class.domain)) ($Class.module -replace '/', '\')
    $reportsDir = Join-Path $moduleDir 'target\surefire-reports'
    $expectedXml = Join-Path $reportsDir ("TEST-" + $Class.expectedFqcn + ".xml")
    $xmlPath = $null
    $identityNote = ''
    if (Test-Path -LiteralPath $expectedXml) {
        $xmlPath = $expectedXml
    } else {
        # 同名类跑到别的包：显式分辨并 FAIL（规格给的是精确 FQCN）。
        $candidates = @(Get-ChildItem -LiteralPath $reportsDir -File -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
            Where-Object { $_.BaseName -match ('\.' + [regex]::Escape($Class.name) + '$') })
        if ($candidates.Count -eq 1) {
            $xmlPath = $candidates[0].FullName
            $identityNote = ("ran as {0} instead of the spec FQCN {1}" -f $candidates[0].BaseName.Substring(5), $Class.expectedFqcn)
        } elseif ($candidates.Count -gt 1) {
            $identityNote = ("ambiguous surefire XML for {0}: {1}" -f $Class.name, (@($candidates | ForEach-Object { $_.Name }) -join ','))
        }
    }
    $report = [ordered]@{
        class = $Class.name; expectedFqcn = $Class.expectedFqcn; spec = $Class.spec
        module = ("services/{0}/{1}" -f $Class.domain, $Class.module)
        pom = $Invocation.pom; profile = $Invocation.profile
        command = (New-CommandLine $script:MavenExe @('-o', '-B', '-ntp', '-f', $Invocation.pom, $Invocation.profile,
                '-pl', $Invocation.module, '-am', 'test', ('-Dtest=' + ($Invocation.tests -join ',')), '-Dsurefire.failIfNoSpecifiedTests=false'))
        invocationStartedUtc = $StartedUtc.ToString('o')
        sourcePath = $Class.source; sourceSha256 = (Get-Sha256 (Join-Path $RepoRoot ($Class.source -replace '/', '\')))
        xmlPath = $null; xmlSha256 = $null; tests = 0; failures = 0; errors = 0; skips = 0
        suiteName = $null; caseCount = 0; refreshedByThisRun = $false
        archivedXml = $null; status = 'FAIL'; detail = ''
    }
    if ($null -eq $xmlPath) {
        $report['detail'] = ("surefire XML missing: services/{0}/{1}/target/surefire-reports/TEST-{2}.xml ({3})" -f `
                $Class.domain, $Class.module, $Class.expectedFqcn, $(if ($identityNote) { $identityNote } else { 'class did not run' }))
        [void]$script:Surefire.Add([pscustomobject]$report)
        Add-Result ('UNIT-' + $Class.name) 'G1-G4' 'FAIL' ($report['detail'] + ' -- a reactor BUILD SUCCESS does not prove the named class ran')
        return
    }
    $report['xmlPath'] = ($xmlPath.Substring($RepoRoot.Length).TrimStart('\') -replace '\\', '/')
    $report['xmlSha256'] = Get-Sha256 $xmlPath
    $writeTime = (Get-Item -LiteralPath $xmlPath).LastWriteTimeUtc
    $report['refreshedByThisRun'] = ($writeTime -ge $StartedUtc)
    try {
        $suite = Read-SurefireSuite $xmlPath
    } catch {
        $report['detail'] = ("unreadable surefire XML: {0} ({1})" -f $xmlPath, $_.Exception.Message)
        [void]$script:Surefire.Add([pscustomobject]$report)
        Add-Result ('UNIT-' + $Class.name) 'G1-G4' 'FAIL' $report['detail']
        return
    }
    $report['suiteName'] = $suite.name; $report['tests'] = $suite.tests; $report['failures'] = $suite.failures
    $report['errors'] = $suite.errors; $report['skips'] = $suite.skipped; $report['caseCount'] = $suite.caseCount
    $archive = Join-Path $script:Evidence (Join-Path 'surefire' ($Class.domain + '\' + $Class.module + '\TEST-' + $suite.name + '.xml'))
    [void](New-Item -ItemType Directory -Force -Path (Split-Path $archive -Parent))
    Copy-Item -LiteralPath $xmlPath -Destination $archive -Force
    $report['archivedXml'] = ($archive.Substring($script:Evidence.Length).TrimStart('\') -replace '\\', '/')

    $reasons = @()
    if ($suite.name -cne $Class.expectedFqcn) { $reasons += ("testsuite name '{0}' != spec FQCN '{1}'" -f $suite.name, $Class.expectedFqcn) }
    if (-not $report['refreshedByThisRun']) { $reasons += ("stale XML (written {0:o}, before this invocation)" -f $writeTime) }
    if ($suite.tests -le 0) { $reasons += 'tests=0' }
    if ($suite.failures -ne 0) { $reasons += ("failures=" + $suite.failures) }
    if ($suite.errors -ne 0) { $reasons += ("errors=" + $suite.errors) }
    if ($suite.skipped -ne 0) { $reasons += ("skips=" + $suite.skipped) }
    $report['status'] = if ($reasons.Count -eq 0) { 'PASS' } else { 'FAIL' }
    $report['detail'] = ("tests={0} failures={1} errors={2} skips={3} cases={4} xml={5} refreshed={6}{7}" -f `
            $suite.tests, $suite.failures, $suite.errors, $suite.skipped, $suite.caseCount, $report['xmlPath'],
            $report['refreshedByThisRun'], $(if ($reasons.Count -gt 0) { ' :: ' + ($reasons -join '; ') } else { '' }))
    [void]$script:Surefire.Add([pscustomobject]$report)
    Add-Result ('UNIT-' + $Class.name) 'G1-G4' $report['status'] $report['detail']
}
function Invoke-UnitMode {
    Write-Step ("Unit 模式：{0} 条原生 mvn 命令 / {1} 个规格必跑类（无外部服务）" -f `
            $script:UnitInvocations.Count, $script:RequiredClasses.Count)
    foreach ($inv in $script:UnitInvocations) {
        $mvnArgs = @('-o', '-B', '-ntp', '-f', $inv.pom, $inv.profile, '-pl', $inv.module, '-am', 'test',
            ('-Dtest=' + ($inv.tests -join ',')), '-Dsurefire.failIfNoSpecifiedTests=false')
        Write-Step ("mvn {0} :: {1}" -f $inv.id, ($inv.tests -join ','))
        $started = (Get-Date).ToUniversalTime()
        $logName = ('unit-' + $inv.id + '.log')
        $run = Invoke-NativeCapture $script:MavenExe $mvnArgs $logName $RepoRoot
        $buildSuccess = @($run.Output | Where-Object { $_ -match '^\[INFO\] BUILD SUCCESS$' }).Count -gt 0
        $totals = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
        foreach ($line in @($run.Output | Where-Object { $_ -match '^\[INFO\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$' })) {
            $m = [regex]::Match($line, 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)')
            if ($m.Success) {
                $totals.tests += [int]$m.Groups[1].Value; $totals.failures += [int]$m.Groups[2].Value
                $totals.errors += [int]$m.Groups[3].Value; $totals.skipped += [int]$m.Groups[4].Value
            }
        }
        # 失败原因要能一眼定位：取第一条 [ERROR]（编译/spotless/依赖缺失都会出现在这里）。
        $firstError = ((@($run.Output | Where-Object { $_ -match '^\[ERROR\]' } | Select-Object -First 1)) -join '')
        if ($firstError.Length -gt 300) { $firstError = $firstError.Substring(0, 300) + '...' }
        $errorNote = if ($run.ExitCode -ne 0 -and $firstError) { (" firstError='" + $firstError + "'") } else { '' }
        Assert-That ('UNIT-MVN-' + $inv.id) 'G1-G4' ($run.ExitCode -eq 0) `
            ("exit={0} buildSuccess={1} log={2} reactorTotals(tests={3},failures={4},errors={5},skipped={6}){7}" -f `
                $run.ExitCode, $buildSuccess, $logName, $totals.tests, $totals.failures, $totals.errors, $totals.skipped, $errorNote)
        foreach ($name in $inv.tests) { Get-UnitClassReport (Get-RequiredClass $name) $inv $started }
    }
}

# =========================== Integration：预检（环境无关部分） ===========================
function Invoke-IntegrationPortCheck {
    Write-Step 'Integration 端口预检：两 jar 端口必须空闲'
    $platformFree = Test-PortFree $PlatformPort
    $aiFree = Test-PortFree $AiPort
    Assert-That 'ENV-ports' 'G0' ($PlatformPort -gt 0 -and $AiPort -gt 0 -and $platformFree -and $aiFree) `
        ("platform={0} free={1}; ai={2} free={3} (defaults come from a bind-time free-port scan)" -f `
            $PlatformPort, $platformFree, $AiPort, $aiFree)
}
function Format-ShellArg([string]$Value) {
    # POSIX 单引号引用：' -> '\'' 是唯一在单引号内可用的转义形式。
    # 远端命令是**参数向量**（ssh 逐个拼接、由远端 shell 解析），
    # 不做这一步会把含空格/引号的 SQL 或口令拆成多个参数，静默改变语义。
    $q = [string][char]39
    return $q + ($Value -replace $q, ($q + '\' + $q + $q)) + $q
}
function Invoke-RemoteShell([string]$Command, [string]$LogName = '') {
    # 执行一条**复合** shell 命令（不是"参数即参数"的容器命令）。
    # 与 Invoke-RemoteRuntime 的区别：这里由调用方负责引用，因为命令本身是 shell 语法；
    # 若按参数逐个引用，整条命令会变成一个被引用的单词，远端只会去找同名文件。
    $sshArgs = @('-o', 'BatchMode=yes', '-o', 'LogLevel=ERROR',
        '-o', ('ConnectTimeout=' + $SshConnectTimeoutSeconds))
    if ($SshKeyPath) { $sshArgs += @('-i', $SshKeyPath) }
    $sshArgs += @($RemoteHost, $Command)
    return Invoke-NativeCapture $script:SshExe $sshArgs $LogName $RepoRoot
}
function Invoke-RemoteRuntime([string[]]$Arguments, [string]$LogName = '') {
    # 参数向量的语义与本地模式一致：**不含**可执行文件名本身。
    # 本地模式是 `& docker <args>`，远端就必须是 `ssh host docker <args>`；
    # 漏掉这里的 'docker' 会让远端执行 `bash <args>`，例如
    # `compose --project-name ... up` 变成 bash 去找一个叫 compose 的脚本，
    # 报 "compose: command not found"（exit 127）——一个接线缺陷被读成环境问题。
    $remote = (@('docker') + $Arguments | ForEach-Object { Format-ShellArg $_ }) -join ' '
    $sshArgs = @('-o', 'BatchMode=yes', '-o', 'LogLevel=ERROR',
        '-o', ('ConnectTimeout=' + $SshConnectTimeoutSeconds))
    if ($SshKeyPath) { $sshArgs += @('-i', $SshKeyPath) }
    $sshArgs += @($RemoteHost, $remote)
    return Invoke-NativeCapture $script:SshExe $sshArgs $LogName $RepoRoot
}
function Invoke-RuntimeCapture([string[]]$Arguments, [string]$LogName = '') {
    # 全部容器命令的唯一出口：本机直调，远端经 ssh。下游调用点因此**不需要**知道
    # 运行时在哪台机器上——这是"环境位置可变、检查强度不变"的实现方式。
    if ($script:RemoteMode) { return Invoke-RemoteRuntime $Arguments $LogName }
    return Invoke-NativeCapture $script:Runtime.path $Arguments $LogName $RepoRoot
}
function Test-RemoteRuntimeUsable {
    # -RemoteHost 指定时先走这里。返回 $true 表示远端 docker 可用且已切到远端模式。
    #
    # 为什么需要它：本机没有容器运行时**不等于**没有验收环境。此前只探测本机，
    # 于是把"这台 Windows 上没有 docker"误判成"无法验收"，并据此把整段 Integration 记 NOT_RUN——
    # 那是关于沙箱的结论，不是关于基础设施的结论。探测口径必须覆盖用户指定的验收宿主。
    if (-not $RemoteHost) { return $false }
    $ssh = Get-Command 'ssh' -ErrorAction SilentlyContinue
    if ($null -eq $ssh) {
        Add-Probe 'ENV-runtime-remote-ssh' 'ssh -V' 'absent' 'ssh client not found on PATH'
        return $false
    }
    $script:SshExe = $ssh.Source
    $prevMode = $script:RemoteMode
    $script:RemoteMode = $true   # 让 Invoke-RuntimeCapture 走远端分支
    try {
        $ver = Invoke-RuntimeCapture @('version', '--format', '{{.Server.Version}}') 'preflight-remote-docker-version.log'
        $verText = ((@($ver.Output | Where-Object { $_.Trim() }) -join ' ') -replace '\s+', ' ').Trim()
        Add-Probe 'ENV-runtime-remote-docker' ('ssh ' + $RemoteHost + ' docker version') `
            $(if ($ver.ExitCode -eq 0) { 'usable' } else { 'unusable' }) `
            ("host={0} exit={1} serverVersion='{2}'" -f $RemoteHost, $ver.ExitCode, $verText)
        if ($ver.ExitCode -ne 0) { $script:RemoteMode = $prevMode; return $false }

        $compose = Invoke-RuntimeCapture @('compose', 'version', '--short') 'preflight-remote-compose-version.log'
        $composeText = ((@($compose.Output | Where-Object { $_.Trim() }) -join ' ') -replace '\s+', ' ').Trim()
        Add-Probe 'ENV-runtime-remote-compose' ('ssh ' + $RemoteHost + ' docker compose version') `
            $(if ($compose.ExitCode -eq 0) { 'usable' } else { 'unusable' }) `
            ("exit={0} version='{1}'" -f $compose.ExitCode, $composeText)
        if ($compose.ExitCode -ne 0) { $script:RemoteMode = $prevMode; return $false }

        # 记录既有容器只用于**声明不去碰它们**，不参与任何复用判定。
        $existing = Invoke-RuntimeCapture @('ps', '--format', '{{.Names}}') 'preflight-remote-existing-containers.log'
        $existingNames = @($existing.Output | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
        Add-Probe 'ENV-runtime-remote-existing' ('ssh ' + $RemoteHost + ' docker ps') `
            'noted-not-reused' ("count={0} names='{1}'" -f $existingNames.Count, ($existingNames -join ','))

        $script:Runtime = [pscustomobject]@{
            name   = 'docker@' + $RemoteHost
            path   = 'ssh ' + $RemoteHost + ' docker'
            engine = $true
        }
        $script:RemoteMode = $true
        Add-Result 'ENV-container-runtime' 'G0' 'PASS' `
            ("runtime={0}; container commands execute on the remote acceptance host over ssh; serverVersion={1} composeVersion={2}; existingContainersNotReused={3}" -f `
                $script:Runtime.name, $verText, $composeText, $existingNames.Count)
        return $true
    } catch {
        Add-Probe 'ENV-runtime-remote-docker' ('ssh ' + $RemoteHost) 'unusable' ('probe threw: ' + $_.Exception.Message)
        $script:RemoteMode = $prevMode
        return $false
    }
}
function Invoke-ContainerRuntimePreflight {
    Write-Step 'Integration 预检：可用容器运行时（合成 PG/Redis/S3 的唯一前提）'
    if (Test-RemoteRuntimeUsable) { return }
    $found = @()
    foreach ($cli in @('docker', 'podman', 'docker-compose', 'nerdctl')) {
        $cmd = Get-Command $cli -ErrorAction SilentlyContinue
        if ($null -eq $cmd) {
            Add-Probe ('ENV-runtime-' + $cli) ($cli + ' version') 'absent' 'executable not found on PATH'
            continue
        }
        $r = Invoke-NativeCapture $cmd.Source @('version') ('preflight-' + $cli + '-version.log')
        $first = (@($r.Output | Where-Object { $_.Trim() } | Select-Object -First 1) -join '')
        Add-Probe ('ENV-runtime-' + $cli) ($cli + ' version') $(if ($r.ExitCode -eq 0) { 'usable' } else { 'unusable' }) `
            ("path={0} exit={1} firstLine='{2}'" -f $cmd.Source, $r.ExitCode, $first)
        if ($r.ExitCode -eq 0) { $found += [pscustomobject]@{ name = $cli; path = $cmd.Source; engine = ($cli -ne 'docker-compose') } }
    }
    $desktop = 'C:\Program Files\Docker'
    Add-Probe 'ENV-runtime-docker-desktop' ('Test-Path ' + $desktop) `
        $(if (Test-Path -LiteralPath $desktop) { 'present' } else { 'absent' }) $desktop
    $pipe = '\\.\pipe\docker_engine'
    Add-Probe 'ENV-runtime-docker-pipe' ('Test-Path ' + $pipe) `
        $(if (Test-Path -LiteralPath $pipe) { 'present' } else { 'absent' }) $pipe
    $wsl = Get-Command 'wsl.exe' -ErrorAction SilentlyContinue
    if ($null -eq $wsl) {
        Add-Probe 'ENV-runtime-wsl' 'wsl.exe -l -v' 'absent' 'wsl.exe not found'
    } else {
        # wsl.exe 用 UTF-16LE 写 stdout；不改控制台编码会得到乱码证据。
        $prevEncoding = [Console]::OutputEncoding
        try {
            [Console]::OutputEncoding = [Text.Encoding]::Unicode
            $list = Invoke-NativeCapture $wsl.Source @('-l', '-v') 'preflight-wsl-list.log'
            $listText = (((@($list.Output) -join ' ') -replace "`0", '') -replace '\s+', ' ').Trim()
            Add-Probe 'ENV-runtime-wsl' 'wsl.exe -l -v' $(if ($list.ExitCode -eq 0) { 'distros-listed' } else { 'unusable' }) `
                ("exit={0} text='{1}'" -f $list.ExitCode, $listText)
            if ($list.ExitCode -eq 0) {
                $probe = Invoke-NativeCapture $wsl.Source @('-e', 'sh', '-c', 'command -v docker || command -v podman || echo NO_CONTAINER_RUNTIME') 'preflight-wsl-container-runtime.log'
                $text = (((@($probe.Output) -join ' ') -replace "`0", '') -replace '\s+', ' ').Trim()
                $usable = ($probe.ExitCode -eq 0 -and $text -notmatch 'NO_CONTAINER_RUNTIME')
                Add-Probe 'ENV-runtime-wsl-engine' 'wsl.exe -e sh -c "command -v docker || command -v podman"' `
                    $(if ($usable) { 'usable' } else { 'absent' }) ("exit={0} text='{1}'" -f $probe.ExitCode, $text)
                if ($usable) { $found += [pscustomobject]@{ name = 'wsl-container-engine'; path = ($wsl.Source + ' -e ' + $text); engine = $true } }
            }
        } finally { [Console]::OutputEncoding = $prevEncoding }
    }
    $usableRuntime = @($found | Where-Object { $_.engine })
    if ($usableRuntime.Count -eq 0) {
        $script:EnvGateBlocked = $true
        $script:GateReason = 'NO_CONTAINER_RUNTIME'
        $detail = 'no usable container runtime: docker/podman/docker-compose/nerdctl absent or unusable on PATH, ' +
            'Docker Desktop directory and \\.\pipe\docker_engine absent, and no container engine reachable through WSL'
        if ($RemoteHost) {
            $detail = $detail + '; additionally -RemoteHost ' + $RemoteHost + ' did not yield a usable docker'
        }
        $script:GateReasonDetail = $detail
        $script:GateReasonShort = 'no usable container runtime (docker/podman/docker-compose/nerdctl/Docker Desktop/docker_engine pipe/WSL engine all absent or unusable)'
        # 该行是主记录：保留完整探测结论，并显式写出原因代号（收尾一致性会核对这个代号）。
        Add-Result 'ENV-container-runtime' 'G0' 'NOT_RUN' ("blocked: {0} -- {1}" -f $script:GateReason, $script:GateReasonDetail)
    } else {
        $script:Runtime = $usableRuntime[0]
        Add-Result 'ENV-container-runtime' 'G0' 'PASS' `
            ("runtime={0} path={1}; synthetic PG/Redis/S3 will be created by this run only" -f $script:Runtime.name, $script:Runtime.path)
    }
}
function Invoke-SubstituteRefusalScan {
    Write-Step 'Integration 预检：拒绝复用本机/远端已有业务库与缓存'
    $targets = @(
        [pscustomobject]@{ id = 'pg-default'; port = 5432; what = 'host PostgreSQL on the product default port' },
        [pscustomobject]@{ id = 'redis-default'; port = 6379; what = 'host Redis on the product default port' },
        [pscustomobject]@{ id = 's3-default'; port = 9000; what = 'host S3-compatible endpoint on the product default port' },
        [pscustomobject]@{ id = 'p04-lab-pg'; port = 15434; what = 'P0.4 synthetic lab PostgreSQL port (remote LabHost port-forward)' },
        [pscustomobject]@{ id = 'mysql-default'; port = 3306; what = 'host MySQL' },
        [pscustomobject]@{ id = 'milvus-default'; port = 19530; what = 'host Milvus' }
    )
    $listeners = @()
    foreach ($t in $targets) {
        $open = Test-TcpEndpoint '127.0.0.1' $t.port 700
        Add-Probe ('ENV-substitute-' + $t.id) ('TcpClient connect 127.0.0.1:' + $t.port) `
            $(if ($open) { 'listener' } else { 'absent' }) $t.what
        if ($open) { $listeners += $t }
    }
    foreach ($t in $listeners) {
        Add-Refusal ('REFUSE-substitute-' + $t.id) `
            ("non-runner-owned listener present at 127.0.0.1:{0} ({1})" -f $t.port, $t.what) `
            'never connected and never used as the synthetic environment; this run uses only its own compose endpoints'
    }
    if ($listeners.Count -eq 0) {
        Add-Result 'ENV-substitute-scan' 'G0' 'PASS' 'no listener on any candidate substitute endpoint (5432/6379/9000/15434/3306/19530); nothing to refuse'
    } else {
        Add-Result 'ENV-substitute-scan' 'G0' 'PASS' `
            ("probed 6 candidate endpoints; {0} listener(s) detected and explicitly refused (see refusals.json): {1}" -f `
                $listeners.Count, (@($listeners | ForEach-Object { $_.id + ':' + $_.port }) -join ','))
    }
}
function Invoke-ProviderKeyIsolation {
    Write-Step 'Integration 预检：真实 provider key 不外泄给子进程'
    $candidateVars = @('DASHSCOPE_API_KEY', 'DEEPSEEK_API_KEY', 'OPENAI_API_KEY', 'AIHUBMIX_API_KEY',
        'SILICONFLOW_API_KEY', 'ZHIPU_API_KEY', 'MOONSHOT_API_KEY', 'MINERU_API_KEY', 'OSS_SECRET_KEY',
        'LANGFUSE_SECRET_KEY')
    $present = @($candidateVars | Where-Object { -not [string]::IsNullOrWhiteSpace([string](Get-Item -Path ('env:' + $_) -ErrorAction SilentlyContinue).Value) })
    $script:ProviderKeyOverrides = $candidateVars
    Assert-That 'ENV-provider-key-isolation' 'G0' $true `
        ("parent env real provider keys visible={0} [{1}]; synthetic children get runner-generated random values for all {2} names" -f `
            $present.Count, ($present -join ','), $candidateVars.Count)
}

# =========================== Integration：环境闸门 ===========================
function Add-GatedResult([string]$id, [string]$target, [string]$detail) {
    if ($script:GatedNotRun -notcontains $id) { [void]$script:GatedNotRun.Add($id) }
    Add-Result $id $target 'NOT_RUN' ("{0} :: NOT RUN -- blocked by {1}: {2}" -f $detail, $script:GateReason, $script:GateReasonShort)
}
function Publish-GatedNotRun([string[]]$ExceptIds = @()) {
    # 环境缺失：逐条写 NOT_RUN（带确切原因）并登记 id；绝不写 PASS，也不写假 FAIL。
    foreach ($entry in $script:GatedInventoryPlan) {
        if ($ExceptIds -contains $entry.id) { continue }
        Add-GatedResult $entry.id $entry.target $entry.detail
    }
}
function Publish-BlockedInventory {
    Write-Step ("Integration 环境闸门关闭：" + $script:GateReason)
    Write-Output ("  reason: " + $script:GateReasonDetail)
    Publish-GatedNotRun
    Write-Output '  BUILD-* / ENV-compose-* / ENV-db-* / ENV-fixtures / PROBE-* / BOOT-* / B01-B13 / DB-* / LISTENER / CHECKER / CASES-complete / CLEANUP-owned-containers 全部 NOT_RUN。'
    Write-Output '  本轮未启动任何 jar、容器或数据库连接。Absence of an environment is NOT_RUN, never PASS and never a fake FAIL -> exit 0.'
}
function Complete-IntegrationInventory([string]$Reason) {
    # 闸门打开但 happy path 中途中止：未产出的计划项补 NOT_RUN，由 INVENTORY-integrity 判 FAIL（fail closed）。
    foreach ($entry in $script:GatedInventoryPlan) {
        if ((Get-ResultRow $entry.id).Count -eq 0) {
            Add-Result $entry.id $entry.target 'NOT_RUN' ("{0} :: NOT RUN -- happy path did not reach this check ({1})" -f $entry.detail, $Reason)
        }
    }
}
function Assert-IntegrationInventory {
    $problems = @()
    foreach ($entry in $script:GatedInventoryPlan) {
        $rows = Get-ResultRow $entry.id
        if ($rows.Count -ne 1) { $problems += ("planned check {0} produced {1} result row(s)" -f $entry.id, $rows.Count) }
    }
    foreach ($id in $script:PreflightGatedIds) {
        $rows = Get-ResultRow $id
        if ($rows.Count -ne 1) { $problems += ("preflight check {0} produced {1} result row(s)" -f $id, $rows.Count) }
        elseif ($script:EnvGateBlocked -and $rows[0].status -ne 'NOT_RUN') { $problems += ("{0} is {1} while the gate is blocked" -f $id, $rows[0].status) }
        elseif (-not $script:EnvGateBlocked -and $rows[0].status -ne 'PASS') { $problems += ("{0} is {1} while the gate is open" -f $id, $rows[0].status) }
    }
    foreach ($id in $script:AlwaysRunIds) {
        $rows = Get-ResultRow $id
        if ($rows.Count -ne 1) { $problems += ("always-run check {0} produced {1} result row(s)" -f $id, $rows.Count) }
        elseif ($rows[0].status -ne 'PASS') { $problems += ("always-run check {0} is {1}" -f $id, $rows[0].status) }
    }
    if ($script:EnvGateBlocked) {
        $planned = @($script:GatedInventoryPlan | ForEach-Object { $_.id })
        $notNotRun = @($script:Results | Where-Object { $planned -contains $_.id -and $_.status -ne 'NOT_RUN' })
        if ($notNotRun.Count -gt 0) { $problems += ('gate blocked but planned check(s) are not NOT_RUN: ' + (@($notNotRun | ForEach-Object { $_.id + '=' + $_.status }) -join ',')) }
        $badReason = @($script:Results | Where-Object { $_.status -eq 'NOT_RUN' -and $_.detail -notmatch 'NO_CONTAINER_RUNTIME' })
        if ($badReason.Count -gt 0) { $problems += ('NOT_RUN rows must name the blocking reason: ' + (@($badReason | ForEach-Object { $_.id }) -join ',')) }
    } else {
        $planned = @($script:GatedInventoryPlan | ForEach-Object { $_.id })
        $notPass = @($script:Results | Where-Object { $planned -contains $_.id -and $_.status -ne 'PASS' })
        if ($notPass.Count -gt 0) { $problems += ('gate open but planned check(s) are not PASS: ' + (@($notPass | ForEach-Object { $_.id + '=' + $_.status }) -join ',')) }
    }
    $detail = if ($problems.Count -eq 0) {
        ("planned={0} results={1} gateBlocked={2} reason={3}" -f $script:GatedInventoryPlan.Count, $script:Results.Count, $script:EnvGateBlocked, $script:GateReason)
    } else { ($problems -join ' | ') }
    Assert-That 'INVENTORY-integrity' 'G0' ($problems.Count -eq 0) $detail
}

# =========================== Integration：合成环境（全部在闸门之后） ===========================
function Initialize-SyntheticSecrets {
    foreach ($name in @('pgSuperuser', 'platformMigrate', 'platformApp', 'aiMigrate', 'aiApp', 'redis', 's3Access', 's3Secret',
            'platformJwt', 'aiServiceCredential', 'aiDelegationSigning', 'fixtureUser')) {
        $script:Secrets[$name] = New-RandomSecret 24
    }
    # 真实 provider key 一律覆盖为随机值：即使发生意外外呼，也只会用合成 key 失败。
    foreach ($var in $script:ProviderKeyOverrides) { Set-Item -Path ('env:' + $var) -Value (New-RandomSecret 18) }
}
function Write-ComposeFile {
    $composeDir = Join-Path $script:RunWork 'compose'
    [void](New-Item -ItemType Directory -Force -Path $composeDir)
    $composePath = Join-Path $composeDir 'docker-compose.yml'
    # 只写 ${...} 占位符：口令值经进程环境注入 compose，落盘文件里没有任何秘密。
    $yaml = @'
# runner-owned synthetic environment for P1.2a integration acceptance.
# Secrets are injected from the runner process environment; this file is safe to archive.
name: __PROJECT__
services:
  pg:
    image: pgvector/pgvector:0.8.6-pg17
    container_name: __PG_CONTAINER__
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${P1B_PG_SUPERUSER_PASSWORD}
      POSTGRES_DB: ragent_p1b
    ports:
      - "127.0.0.1:__PG_PORT__:5432"
    labels:
      p1.boundary.owner: __RUNTAG__
      p1.boundary.role: pg
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d ragent_p1b"]
      interval: 3s
      timeout: 3s
      retries: 40
  redis:
    image: redis:7.4-alpine
    container_name: __REDIS_CONTAINER__
    command: ["redis-server", "--requirepass", "${P1B_REDIS_PASSWORD}", "--save", ""]
    # 健康检查在**容器内**执行，所以口令必须是容器自己的环境变量。
    # 原先只把它插值进 command，容器里并没有这个变量，于是
    # `redis-cli -a $P1B_REDIS_PASSWORD ping` 展开成 `-a ""`，
    # redis-cli 把空口令当成"没有口令"从而拒绝认证，容器永远 unhealthy——
    # 而 redis-server 本身是好的，看起来像"Redis 起不来"，实际只是自检方式错。
    environment:
      P1B_REDIS_PASSWORD: ${P1B_REDIS_PASSWORD}
    ports:
      - "127.0.0.1:__REDIS_PORT__:6379"
    labels:
      p1.boundary.owner: __RUNTAG__
      p1.boundary.role: redis
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$$P1B_REDIS_PASSWORD\" ping | grep PONG"]
      interval: 3s
      timeout: 3s
      retries: 40
  s3:
    image: rustfs/rustfs:1.0.0-alpha.72
    container_name: __S3_CONTAINER__
    environment:
      RUSTFS_ACCESS_KEY: ${P1B_S3_ACCESS_KEY}
      RUSTFS_SECRET_KEY: ${P1B_S3_SECRET_KEY}
      RUSTFS_ADDRESS: "0.0.0.0:9000"
      RUSTFS_CONSOLE_ENABLE: "false"
    ports:
      - "127.0.0.1:__S3_PORT__:9000"
    labels:
      p1.boundary.owner: __RUNTAG__
      p1.boundary.role: s3
    volumes:
      - s3data:/data
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://127.0.0.1:9000/health >/dev/null 2>&1 || exit 1"]
      interval: 3s
      timeout: 3s
      retries: 40
volumes:
  pgdata:
    labels:
      p1.boundary.owner: __RUNTAG__
  s3data:
    labels:
      p1.boundary.owner: __RUNTAG__
'@
    $yaml = $yaml.Replace('__PROJECT__', $script:ComposeProject)
    $yaml = $yaml.Replace('__RUNTAG__', $RunTag)
    $yaml = $yaml.Replace('__PG_CONTAINER__', $script:PgContainer)
    $yaml = $yaml.Replace('__REDIS_CONTAINER__', $script:RedisContainer)
    $yaml = $yaml.Replace('__S3_CONTAINER__', $script:S3Container)
    $yaml = $yaml.Replace('__PG_PORT__', [string]$script:PgPort)
    $yaml = $yaml.Replace('__REDIS_PORT__', [string]$script:RedisPort)
    $yaml = $yaml.Replace('__S3_PORT__', [string]$script:S3Port)
    [IO.File]::WriteAllText($composePath, $yaml, (New-Object Text.UTF8Encoding($false)))
    $archive = Join-Path $script:Evidence 'compose\docker-compose.yml'
    [void](New-Item -ItemType Directory -Force -Path (Split-Path $archive -Parent))
    Copy-Item -LiteralPath $composePath -Destination $archive -Force
    return $composePath
}
function Invoke-OwnedCompose([string[]]$Arguments, [string]$LogName) {
    # 空 --file 会变成 `docker compose --file "" ps`：docker 报错，而错误文本会被读成
    # "环境不可用"，把一次接线缺陷伪装成环境问题。这里先自证，让缺陷在源头显形。
    if (-not $script:ComposePath) {
        throw ('Invoke-OwnedCompose called before the compose file exists (project={0}); ' -f $script:ComposeProject) +
            'the run must prepare its own synthetic environment identity first'
    }
    $full = @('compose', '--project-name', $script:ComposeProject, '--file', $script:ComposePath) + $Arguments
    return Invoke-RuntimeCapture $full $LogName
}
function Test-P04ContainerIsolation {
    Write-Step 'G0：本轮不得复用 P0.4 容器（只按 owner label 识别自有资源）'
    $ps = Invoke-OwnedCompose @('ps', '--all', '--format', 'json') 'compose-ps.log'
    $names = @()
    foreach ($line in @($ps.Output)) {
        $t = ([string]$line).Trim()
        if (-not $t -or $t -eq 'null') { continue }
        try { $obj = $t | ConvertFrom-Json } catch { continue }
        if ($obj.name) { $names += [string]$obj.name }
    }
    $p04 = @($names | Where-Object { $_ -match '^p04-' })
    Assert-That 'ENV-p04-container-isolation' 'G0' ($p04.Count -eq 0) `
        ("composeProject={0} existingContainers=[{1}] p04Containers={2} (p04 lab never reused; runner only touches its own project)" -f `
            $script:ComposeProject, ($names -join ','), $p04.Count)
    return $true
}
function Initialize-SyntheticIdentity {
    # 把"本轮自有资源的身份 + compose 文件"与"把它们起来"分开。
    #
    # 为什么必须分开：Test-P04ContainerIsolation 要先问一句"这个 compose project 里现在有没有
    # p04-* 容器"，而 `docker compose --file <path> ps` 需要一个**真实存在**的 file——
    # 原先 ComposePath 是在 Start-SyntheticEnvironment 里才赋值的，于是隔离检查在
    # --file 为空串的情况下发出，docker 直接报错。检查的顺序是对的（先确认不复用，再创建），
    # 错的是"文件还没准备好"。
    $script:ComposeProject = (('p1b-' + $RunTag.ToLower()) -replace '[^a-z0-9-]', '-')
    $script:PgContainer = ('p1b-pg-' + $RunTag.ToLower())
    $script:RedisContainer = ('p1b-redis-' + $RunTag.ToLower())
    $script:S3Container = ('p1b-s3-' + $RunTag.ToLower())
    # 端口必须在写文件**之前**确定：compose 文件把端口固化成字面量，
    # 若先写文件再分配端口，文件里会留下 "127.0.0.1:0:5432"，
    # 而 docker 对 0 端口的行为不是报错而是"随机映射"——合成服务会起在一个
    # 谁也猜不到的端口上，后续所有连接与隧道全部失效。
    $script:ComposePath = Write-ComposeFile
}
function Publish-ComposeFileToHost {
    # 远端模式下 docker compose 在远端执行，--file 必须是**远端**可读的路径。
    # 直接把 Windows 路径交给远端会得到
    # "stat /root/D:\...\docker-compose.yml: no such file or directory"——
    # 又一次把接线缺陷伪装成环境问题。这里把文件投递到本轮自有目录，
    # 并把 ComposePath 换成远端路径。文件内容只有 ${...} 变量名，不含口令，可安全归档。
    if (-not $script:RemoteMode) { return }
    $remoteDir = '/opt/p1-acceptance/' + ($RunTag.ToLower() -replace '[^a-z0-9-]', '-')
    $script:RemoteComposeDir = $remoteDir
    $mk = Invoke-RemoteShell ('mkdir -p ' + (Format-ShellArg $remoteDir) + ' && chmod 700 ' + (Format-ShellArg $remoteDir)) 'compose-remote-mkdir.log'
    if ($mk.ExitCode -ne 0) { throw ('cannot create remote compose dir ' + $remoteDir) }
    $content = [IO.File]::ReadAllText($script:ComposePath)
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($content))
    $remoteFile = $remoteDir + '/docker-compose.yml'
    $cmd = 'printf %s ' + (Format-ShellArg $b64) + ' | base64 -d > ' + (Format-ShellArg $remoteFile)
    $put = Invoke-RemoteShell $cmd 'compose-remote-publish.log'
    if ($put.ExitCode -ne 0) { throw ('cannot publish compose file to ' + $remoteFile) }
    $script:ComposePath = $remoteFile

    # 变量插值发生在**执行 compose 的那台机器**上。合成口令只存在于本进程环境里，
    # 远端 shell 看不到它们，于是 compose 把 ${P1B_...} 当未定义变量：
    # 它不报错，只警告 "variable is not set. Defaulting to a blank string"，
    # 然后拿空口令把容器**起成功**——一个"全绿但从未真正设过口令"的环境。
    # 因此把变量写成 compose 同目录的 .env（docker compose 自动读取），umask 077，
    # 并在清理时随本轮自有目录一起删除。.env 只落在远端本轮自有目录，不进证据、不进仓库。
    $envLines = @(
        ('P1B_PG_SUPERUSER_PASSWORD=' + $script:Secrets['pgSuperuser']),
        ('P1B_REDIS_PASSWORD=' + $script:Secrets['redis']),
        ('P1B_S3_ACCESS_KEY=' + $script:Secrets['s3Access']),
        ('P1B_S3_SECRET_KEY=' + $script:Secrets['s3Secret'])
    ) -join "`n"
    $envB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($envLines + "`n"))
    $remoteEnv = $remoteDir + '/.env'
    $envCmd = 'umask 077; printf %s ' + (Format-ShellArg $envB64) + ' | base64 -d > ' + (Format-ShellArg $remoteEnv)
    $envPut = Invoke-RemoteShell $envCmd 'compose-remote-env.log'
    if ($envPut.ExitCode -ne 0) { throw ('cannot publish compose env file to ' + $remoteEnv) }
}
function Start-SyntheticEnvironment {
    Write-Step 'G0：起 runner 自有合成 PG17+pgvector / Redis / S3 mock（唯一 owner label）'
    if (-not $script:ComposePath) { throw 'compose file was not prepared; Initialize-SyntheticIdentity must run first' }
    foreach ($port in @($script:PgPort, $script:RedisPort, $script:S3Port)) {
        if ($port -le 0) { Add-Result 'ENV-compose-up' 'G0' 'FAIL' 'synthetic port was not allocated'; return }
        if (-not (Test-PortFree $port)) { Add-Result 'ENV-compose-up' 'G0' 'FAIL' ("synthetic port already in use: " + $port); return }
    }
    Publish-ComposeFileToHost
    $env:P1B_PG_SUPERUSER_PASSWORD = $script:Secrets['pgSuperuser']
    $env:P1B_REDIS_PASSWORD = $script:Secrets['redis']
    $env:P1B_S3_ACCESS_KEY = $script:Secrets['s3Access']
    $env:P1B_S3_SECRET_KEY = $script:Secrets['s3Secret']
    $up = Invoke-OwnedCompose @('up', '--detach', '--wait') 'compose-up.log'
    if ($up.ExitCode -ne 0) { Add-Result 'ENV-compose-up' 'G0' 'FAIL' ("compose up exit={0}; see compose-up.log" -f $up.ExitCode); return }
    foreach ($container in @($script:PgContainer, $script:RedisContainer, $script:S3Container)) { [void]$script:OwnedContainers.Add($container) }
    $label = Invoke-RuntimeCapture @('ps', '--all', '--filter', ('label=p1.boundary.owner=' + $RunTag),
        '--format', '{{.Names}}|{{.Label "p1.boundary.role"}}|{{.Status}}') 'compose-owned-label-inventory.log'
    $ownedRows = @($label.Output | Where-Object { $_ -match '\|' })
    Assert-That 'ENV-compose-up' 'G0' ($label.ExitCode -eq 0 -and $ownedRows.Count -eq 3) `
        ("up exit=0; containersWithOwnLabel={0} [{1}]" -f $ownedRows.Count, ($ownedRows -join '; '))
}
function Invoke-SyntheticSql([string]$Sql, [string]$Database, [string]$User, [string]$PasswordKey, [string]$LogName) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    # 原文经 base64 传递：SQL 里有中文与单引号，直接进参数向量会被远端 shell 重新解析。
    # 口令**不**进参数向量，而是在远端容器内按容器自身环境自证（见下），
    # 因此它既不出现在本机命令行、也不出现在 ssh 远端命令行、更不落盘或进证据。
    $inner = 'test -n "$PGPASSWORD" || { echo NO_PGPASSWORD_IN_CONTAINER; exit 96; }; '
    $inner += 'printf %s ' + (Format-ShellArg $b64) + ' | base64 -d | '
    $inner += 'psql -U ' + (Format-ShellArg $User) + ' -d ' + (Format-ShellArg $Database) + ' -X -q -tA -v ON_ERROR_STOP=1 -f -'
    # -e PGPASSWORD 不带值：把值从**本进程环境**映射进容器，命令行只看得到变量名。
    $env:PGPASSWORD = $script:Secrets[$PasswordKey]
    try {
        $r = Invoke-RuntimeCapture @('exec', '-i', '-e', 'PGPASSWORD', $script:PgContainer, 'sh', '-c', $inner) $LogName
    } finally {
        Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
    }
    if ($r.ExitCode -ne 0) { throw ("synthetic SQL failed (exit={0}), see {1}" -f $r.ExitCode, $LogName) }
    return (($r.Output -join "`r`n").Trim())
}
function Initialize-SyntheticDatabase {
    Write-Step 'G0：独立 migrate / app 账号 + 双 schema + 迁移原字节'
    $roleSql = @'
DO $$
DECLARE r text; p text;
BEGIN
  FOR r, p IN SELECT * FROM (VALUES ('platform_migrate','__P1__'),('platform_app','__P2__'),('ai_migrate','__P3__'),('ai_app','__P4__')) AS t(r,p) LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', r, p);
    END IF;
  END LOOP;
END $$;
CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS ai;
CREATE SCHEMA IF NOT EXISTS extensions;
GRANT USAGE ON SCHEMA platform, ai, extensions TO platform_app, ai_app;
'@
    $roleSql = $roleSql.Replace('__P1__', $script:Secrets['platformMigrate']).Replace('__P2__', $script:Secrets['platformApp'])
    $roleSql = $roleSql.Replace('__P3__', $script:Secrets['aiMigrate']).Replace('__P4__', $script:Secrets['aiApp'])
    [void](Invoke-SyntheticSql $roleSql 'ragent_p1b' 'postgres' 'pgSuperuser' 'db-roles.log')
    # 迁移用 migrate 账号，逐字执行仓库内迁移原字节（不重写、不改序）。
    $migrateDirs = @(
        [pscustomobject]@{ dir = 'services/platform/ruoyi-admin/src/main/resources/db/migration'; schema = 'platform'; user = 'platform_migrate'; key = 'platformMigrate' },
        [pscustomobject]@{ dir = 'services/ai/bootstrap/src/main/resources/db/migration'; schema = 'ai'; user = 'ai_migrate'; key = 'aiMigrate' }
    )
    $applied = @()
    foreach ($spec in $migrateDirs) {
        $full = Join-Path $RepoRoot ($spec.dir -replace '/', '\')
        if (-not (Test-Path -LiteralPath $full)) { continue }
        foreach ($file in @(Get-ChildItem -LiteralPath $full -File -Filter '*.sql' | Sort-Object Name)) {
            $sql = [IO.File]::ReadAllText($file.FullName)
            [void](Invoke-SyntheticSql ("SET search_path TO " + $spec.schema + ",extensions;`r`n" + $sql) 'ragent_p1b' `
                    $spec.user $spec.key ('migration-' + $spec.schema + '-' + $file.Name + '.log'))
            $applied += [pscustomobject]@{ schema = $spec.schema; file = $file.Name
                sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLower() }
        }
    }
    Assert-That 'ENV-db-accounts' 'G0' ($applied.Count -gt 0) `
        ("migrate/app accounts + platform/ai/extensions schemas ready; migrations applied byte-identical from repo: [{0}]" -f `
            (@($applied | ForEach-Object { $_.schema + '/' + $_.file }) -join ','))
    $fixtureSql = @'
-- synthetic fixtures only: two tenants, one same-named user per tenant; password hash injected by the runner.
INSERT INTO platform.sys_user (tenant_id, user_name, nick_name, password, status, del_flag)
VALUES ('p1t1', 'p1b-admin', 'p1b-admin-t1', '__HASH__', '0', '0'),
       ('p1t2', 'p1b-admin', 'p1b-admin-t2', '__HASH__', '0', '0');
'@
    $fixtureSql = $fixtureSql.Replace('__HASH__', $script:Secrets['fixtureUser'])
    try { [void](Invoke-SyntheticSql $fixtureSql 'ragent_p1b' 'platform_migrate' 'platformMigrate' 'db-fixtures.log') } catch {
        Add-Result 'ENV-fixtures' 'G0' 'FAIL' ("synthetic fixture seed failed: " + $_.Exception.Message); return
    }
    Assert-That 'ENV-fixtures' 'G0' $true 'two synthetic tenants with same-named user seeded; no real customer data touched'
}
function Get-RowHashSnapshot {
    # 全表行数 + 行哈希：不只数新增，update/delete 同样查得出来。
    $sql = @'
SELECT jsonb_object_agg(k, v)::text FROM (
  SELECT c.table_schema || '.' || c.table_name AS k,
         jsonb_build_object(
           'count', (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', c.table_schema, c.table_name), false, true, '')))[1]::text::bigint,
           'rowHash', (xpath('/row/h/text()', query_to_xml(format('SELECT COALESCE(md5(string_agg(md5(t::text), %L ORDER BY md5(t::text))), %L) AS h FROM %I.%I t', '', 'EMPTY_TABLE', c.table_schema, c.table_name), false, true, '')))[1]::text
         ) AS v
  FROM information_schema.tables c
  WHERE c.table_type = 'BASE TABLE'
    AND c.table_schema IN ('platform','ai','extensions','public')
    AND c.table_name NOT LIKE 'flyway%'
) s;
'@
    return (Invoke-SyntheticSql $sql 'ragent_p1b' 'ai_migrate' 'aiMigrate' 'db-snapshot.log' | ConvertFrom-Json)
}
function Compare-RowHashSnapshots($Before, $After) {
    $beforeKeys = @($Before.PSObject.Properties.Name)
    $afterKeys = @($After.PSObject.Properties.Name)
    $missing = @($beforeKeys | Where-Object { $afterKeys -notcontains $_ })
    $added = @($afterKeys | Where-Object { $beforeKeys -notcontains $_ })
    $changed = @()
    foreach ($key in $beforeKeys) {
        if ($afterKeys -notcontains $key) { continue }
        $b = $Before.$key; $a = $After.$key
        if ([string]$b.count -ne [string]$a.count -or [string]$b.rowHash -ne [string]$a.rowHash) { $changed += $key }
    }
    return [pscustomobject]@{ missing = $missing; added = $added; changed = $changed; tables = $beforeKeys.Count
        unchanged = ($missing.Count -eq 0 -and $added.Count -eq 0 -and $changed.Count -eq 0) }
}
function Test-ProbeAvailability {
    Write-Step 'G0：probe 事实来源（Bean/Mapping/注册数/调用计数）'
    $specRelative = 'services/ai/rag/src/test/java/com/nageoffer/ai/ragent/boundary/P1ProductBoundaryProbe.java'
    $path = Join-Path $RepoRoot ($specRelative -replace '/', '\')
    $specPathMatch = Test-Path -LiteralPath $path
    if (-not $specPathMatch) {
        # 规格给了一个精确路径，但 probe 是可替换的测试源集工具：允许落在别的模块，
        # 但必须显式记录位置偏差（评审看 detail 即可），不得悄悄放过。
        $found = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'services') -Recurse -File -Filter 'P1ProductBoundaryProbe.java' -ErrorAction SilentlyContinue)
        if ($found.Count -eq 1) { $path = $found[0].FullName }
        elseif ($found.Count -gt 1) {
            Add-Result 'PROBE-availability' 'G0' 'FAIL' `
                ("ambiguous P1ProductBoundaryProbe.java: {0}" -f (@($found | ForEach-Object { $_.FullName.Substring($RepoRoot.Length) }) -join ','))
            return $false
        }
    }
    if (-not (Test-Path -LiteralPath $path)) {
        Add-Result 'PROBE-availability' 'G0' 'FAIL' `
            ("P1ProductBoundaryProbe.java not found (spec path {0}); B09-B13 与 handler/注册数事实无法采集" -f $specRelative)
        return $false
    }
    $relative = $path.Substring($RepoRoot.Length).TrimStart('\') -replace '\\', '/'
    Add-Result 'PROBE-availability' 'G0' 'PASS' `
        ("probe source present: {0} sha256={1} specPathMatch={2}" -f $relative, (Get-Sha256 $path), $specPathMatch)
    return $true
}
function Get-FactsMissingField($Facts) {
    # facts 契约（README §5.4）：缺字段必须点名，不能读成 null 后当 0 判定。
    $missing = @()
    foreach ($fieldPath in @('contextStarted', 'routes', 'legacyMappings', 'mqConsumers', 'transactionCheckers',
            'checkerMapperInvocations', 'createdBuckets', 'createdIndexes', 'publicReadGrants', 'mcpConnects', 'beanLifecycleOk',
            'directTrigger.listener', 'directTrigger.checker', 'directTrigger.userContextLeaks',
            'schedule.observedSeconds', 'schedule.dbScans', 'schedule.claims', 'schedule.statusUpdates',
            'schedule.redisLocks', 'schedule.submittedTasks',
            'dispatch.forwardClosed', 'dispatch.asyncClosed', 'dispatch.errorRecursion',
            'counters.handlerExecutions', 'counters.userMapperInvocations', 'counters.authServiceInvocations',
            'counters.saTokenLogins', 'counters.mapperQueries', 'counters.mqSends', 'counters.objectWrites', 'counters.modelCalls')) {
        $node = $Facts
        $ok = $true
        foreach ($segment in $fieldPath.Split('.')) {
            if ($null -eq $node) { $ok = $false; break }
            $prop = $node.PSObject.Properties[$segment]
            if ($null -eq $prop) { $ok = $false; break }
            $node = $prop.Value
        }
        if (-not $ok) { $missing += $fieldPath }
    }
    return $missing
}
function Start-RemotePortForward {
    # 把远端的合成端口通过 ssh -L 转发到本机回环。
    #
    # 为什么用转发而不是把 JAR 直接指向远端 IP：
    #  - 产品配置里所有端点都是 127.0.0.1（见 Start-ProductJar）。改成远端 IP 就要在
    #    每个 URL/主机名参数上分别特判，配置面越改越大；
    #  - 更关键的是"绝不连到已有业务库"这条约束：转发后本机回环上出现的端口一定是
    #    本轮的 ssh 隧道，语义与本地模式完全一致；而直接指向远端 IP 会让
    #    Invoke-SubstituteRefusalScan 失去意义——它扫的是回环。隧道让拒绝扫描继续有效。
    #
    # 隧道只让端点"在本机看起来一样"，不降低任何检查强度：合成 PG/Redis/S3
    # 仍是本轮自己在远端创建的、带 owner label 的容器。
    if (-not $script:RemoteMode) { return $true }
    $forward = @()
    foreach ($p in @($script:PgPort, $script:RedisPort, $script:S3Port)) {
        $forward += @('-L', ('127.0.0.1:' + $p + ':127.0.0.1:' + $p))
    }
    $sshArgs = @('-o', 'BatchMode=yes', '-o', 'LogLevel=ERROR', '-o', 'ExitOnForwardFailure=yes',
        '-o', ('ConnectTimeout=' + $SshConnectTimeoutSeconds), '-N') + $forward
    if ($SshKeyPath) { $sshArgs += @('-i', $SshKeyPath) }
    $sshArgs += @($RemoteHost)
    $stdout = Join-Path $script:RunWork 'ssh-port-forward.out'
    $stderr = Join-Path $script:RunWork 'ssh-port-forward.err'
    $p = Start-Process -FilePath $script:SshExe -ArgumentList $sshArgs -PassThru -NoNewWindow `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $pidFile = Join-Path $script:RunWork 'ssh-port-forward.pid'
    Set-Content -LiteralPath $pidFile -Value ([string]$p.Id) -Encoding UTF8
    [void]$script:StartedProcesses.Add($p.Id)
    # 等隧道真正可用：进程活着不等于端口已建立。
    foreach ($port in @($script:PgPort, $script:RedisPort, $script:S3Port)) {
        $ok = $false
        for ($i = 0; $i -lt 40; $i++) {
            if (Test-TcpEndpoint '127.0.0.1' $port 300) { $ok = $true; break }
            Start-Sleep -Milliseconds 250
        }
        if (-not $ok) {
            Add-Result 'ENV-port-forward' 'G0' 'FAIL' `
                ("ssh tunnel did not open 127.0.0.1:{0} (see ssh-port-forward.err)" -f $port)
            return $false
        }
    }
    Add-Result 'ENV-port-forward' 'G0' 'PASS' `
        ("ssh -L tunnels for PG/Redis/S3 ({0},{1},{2}); endpoints stay 127.0.0.1 so the substitute-refusal scan keeps its meaning" -f `
            $script:PgPort, $script:RedisPort, $script:S3Port)
    return $true
}
function Start-ProductJar([string]$Side, [string]$State, [int]$Port) {
    $jar = if ($Side -eq 'platform') {
        Join-Path $RepoRoot 'services\platform\ruoyi-admin\target\ruoyi-admin.jar'
    } else {
        Join-Path $RepoRoot 'services\ai\bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar'
    }
    if (-not (Test-Path -LiteralPath $jar)) { throw ("fresh product jar missing for {0}: {1} (run the build roots first)" -f $Side, $jar) }
    $run = Join-Path $script:RunWork ($Side + '-run-' + $State)
    $arguments = @('-Dfile.encoding=UTF-8', '-Xmx1024m', '-jar', $jar, ('--server.port=' + $Port))
    if ($Side -eq 'platform') {
        $arguments += @(
            ('--spring.datasource.dynamic.datasource.master.url=jdbc:postgresql://127.0.0.1:' + $script:PgPort + '/ragent_p1b?currentSchema=platform,extensions'),
            '--PLATFORM_DB_USERNAME=platform_app', '--REDIS_HOST=127.0.0.1', ('--REDIS_PORT=' + $script:RedisPort))
    } else {
        $arguments += @(
            ('--AI_DB_URL=jdbc:postgresql://127.0.0.1:' + $script:PgPort + '/ragent_p1b?client_encoding=UTF8&currentSchema=ai,extensions'),
            ('--spring.data.redis.host=127.0.0.1'), ('--spring.data.redis.port=' + $script:RedisPort),
            ('--rag.storage.s3.endpoint=http://127.0.0.1:' + $script:S3Port))
    }
    if ($State -eq 'cli-false') { $arguments += '--p04.enabled=false' }
    if ($State -eq 'illegal-p04') { $arguments += '--p04.enabled=true' }
    if ($State -eq 'illegal-integration') { $arguments += '--ai.integration.enabled=true' }
    if ($State -eq 'illegal-customer-api') { $arguments += '--ai.integration.customer-api.enabled=true' }
    if ($State -eq 'illegal-legacy-listeners') { $arguments += '--ai.integration.legacy-listeners-enabled=true' }
    $pidFile = Join-Path $script:RunWork ($Side + '-' + $State + '.pid')
    return Start-OwnedProcess (Join-Path $script:JdkHome 'bin\java.exe') $arguments $run $pidFile
}
function Wait-ApplicationReady([int]$Port, [string]$LogPath, [int]$TimeoutSec) {
    for ($i = 0; $i -lt ($TimeoutSec * 2); $i++) {
        if (Test-Path -LiteralPath $LogPath) {
            $text = [IO.File]::ReadAllText($LogPath)
            if ($text -match 'Started .+ in [\d.]+ seconds' -or $text -match 'ApplicationReadyEvent') { return $true }
            if ($text -match 'APPLICATION FAILED TO START') { return $false }
        }
        if (Test-TcpEndpoint '127.0.0.1' $Port 300) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}
function Send-Json($Client, [string]$Method, [string]$Url, $Headers, [string]$Json) {
    $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($Method)), $Url)
    if ($Json -and $Method -notin @('GET', 'HEAD')) {
        $req.Content = New-Object System.Net.Http.StringContent($Json, [Text.Encoding]::UTF8, 'application/json')
    }
    if ($Headers) { foreach ($k in $Headers.Keys) { [void]$req.Headers.TryAddWithoutValidation($k, [string]$Headers[$k]) } }
    $resp = $Client.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $obj = $null
    if ($text) { try { $obj = $text | ConvertFrom-Json } catch { $obj = $null } }
    $rid = ''
    try { $vals = $null; if ($resp.Headers.TryGetValues('X-Request-Id', [ref]$vals)) { $rid = ($vals | Select-Object -First 1) } } catch { }
    [void]$script:HttpLog.Add([pscustomobject]@{ method = $Method; url = $Url; status = [int]$resp.StatusCode
            requestId = $rid; body = (Protect-LogText $text) })
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $obj; Text = $text; RequestId = $rid }
}
function Assert-ClosedEnvelope($Response, [string]$Id, [string]$What) {
    $ok = ($Response.Status -eq 404 -and $null -ne $Response.Json -and
        [int]$Response.Json.code -eq 404 -and
        $null -ne $Response.Json.data -and [string]$Response.Json.data.errorCode -ceq 'RESOURCE_NOT_FOUND_OR_FORBIDDEN')
    Assert-That $Id 'B' $ok ("{0}: status={1} body.code={2} errorCode={3}" -f $What, $Response.Status,
        $(if ($Response.Json) { $Response.Json.code } else { 'null' }),
        $(if ($Response.Json -and $Response.Json.data) { $Response.Json.data.errorCode } else { 'null' }))
}
function Invoke-FactsDrivenCases {
    # B09-B13：直接触发/注册数/装配事实全部来自由 P1ProductBoundaryProbe 写出的 facts 文件。
    $factsPath = Join-Path $script:Evidence 'probe\ai-runtime-facts.json'
    if (-not (Test-Path -LiteralPath $factsPath)) {
        Add-Result 'PROBE-ai-facts' 'G0' 'FAIL' ("probe facts missing: {0}; registration/direct-trigger facts unavailable" -f $factsPath)
        foreach ($id in @('B09', 'B10', 'B11', 'B12', 'LISTENER-registration-counts', 'CHECKER-registration-counts')) {
            Add-Result $id 'B' 'NOT_RUN' 'blocked: no probe facts file (PROBE-ai-facts failed)'
        }
        return
    }
    $facts = Get-Content -LiteralPath $factsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $script:Facts = $facts
    $missingFields = @(Get-FactsMissingField $facts)
    if ($missingFields.Count -gt 0) {
        # probe 输出契约不符：点名缺哪些字段后一律 FAIL，剩余 B09-B13 记 NOT_RUN（不猜、不补默认值）。
        Add-Result 'PROBE-ai-facts' 'G0' 'FAIL' `
            ("probe facts do not match the documented contract; missing fields: {0}" -f ($missingFields -join ','))
        foreach ($id in @('B09', 'B10', 'B11', 'B12', 'LISTENER-registration-counts', 'CHECKER-registration-counts')) {
            Add-Result $id 'B' 'NOT_RUN' ("blocked: probe facts missing fields ({0})" -f ($missingFields -join ','))
        }
        return
    }
    Assert-That 'PROBE-ai-facts' 'G0' ($facts.contextStarted -eq $true) `
        ("contextStarted={0} routes={1} legacyMappings={2}" -f $facts.contextStarted, @($facts.routes).Count, @($facts.legacyMappings).Count)
    Assert-That 'LISTENER-registration-counts' 'G1' (@($facts.mqConsumers).Count -eq 0) `
        ("registered legacy MQ consumers={0} [{1}]" -f @($facts.mqConsumers).Count, (@($facts.mqConsumers) -join ','))
    Assert-That 'CHECKER-registration-counts' 'G1' (@($facts.transactionCheckers).Count -eq 0 -and [int]$facts.checkerMapperInvocations -eq 0) `
        ("registered transaction checkers={0} checkerMapperInvocations={1}" -f @($facts.transactionCheckers).Count, $facts.checkerMapperInvocations)
    Assert-That 'B09' 'B' ((@($facts.directTrigger.listener).Count -ge 3) -and
        (@($facts.directTrigger.listener | Where-Object { $_.closed -ne $true }).Count -eq 0) -and
        ([int]$facts.directTrigger.userContextLeaks -eq 0) -and ([int]$facts.counters.mqSends -eq 0) -and
        ([int]$facts.counters.objectWrites -eq 0) -and ([int]$facts.counters.modelCalls -eq 0)) `
        ("listenerDirect={0} allClosed={1} userContextLeaks={2} mq/object/model={3}/{4}/{5}" -f `
            @($facts.directTrigger.listener).Count, (@($facts.directTrigger.listener | Where-Object { $_.closed -eq $true }).Count),
            $facts.directTrigger.userContextLeaks, $facts.counters.mqSends, $facts.counters.objectWrites, $facts.counters.modelCalls)
    Assert-That 'B10' 'B' ((@($facts.directTrigger.checker).Count -ge 2) -and
        (@($facts.directTrigger.checker | Where-Object { $_.closed -ne $true }).Count -eq 0) -and
        ([int]$facts.counters.mapperQueries -eq 0)) `
        ("checkerDirect={0} allClosed={1} mapperQueries={2}" -f @($facts.directTrigger.checker).Count,
            (@($facts.directTrigger.checker | Where-Object { $_.closed -eq $true }).Count), $facts.counters.mapperQueries)
    Assert-That 'B11' 'B' (([int]$facts.schedule.dbScans -eq 0) -and ([int]$facts.schedule.claims -eq 0) -and
        ([int]$facts.schedule.statusUpdates -eq 0) -and ([int]$facts.schedule.redisLocks -eq 0) -and
        ([int]$facts.schedule.submittedTasks -eq 0) -and ([double]$facts.schedule.observedSeconds -gt 1)) `
        ("observationWindow={0}s scans/claims/updates/locks/submits={1}/{2}/{3}/{4}/{5}" -f `
            $facts.schedule.observedSeconds, $facts.schedule.dbScans, $facts.schedule.claims,
            $facts.schedule.statusUpdates, $facts.schedule.redisLocks, $facts.schedule.submittedTasks)
    Assert-That 'B12' 'B' ((@($facts.createdBuckets).Count -eq 0) -and (@($facts.createdIndexes).Count -eq 0) -and
        ([int]$facts.publicReadGrants -eq 0) -and ([int]$facts.mcpConnects -eq 0) -and ($facts.beanLifecycleOk -eq $true)) `
        ("buckets=[{0}] indexes=[{1}] publicRead={2} mcpConnects={3} beanLifecycleOk={4}" -f `
            (@($facts.createdBuckets) -join ','), (@($facts.createdIndexes) -join ','),
            $facts.publicReadGrants, $facts.mcpConnects, $facts.beanLifecycleOk)
}
function Invoke-HttpCases {
    Write-Step 'B01–B08 / B13：真实产品 jar 的 HTTP 与装配断言'
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    try {
        $script:DbBefore = Get-RowHashSnapshot
        Add-Result 'DB-snapshot-before' 'G1' 'PASS' ("tables={0} (count+rowHash per table)" -f @($script:DbBefore.PSObject.Properties.Name).Count)

        # B01：平台正常登录控制组（两租户同名用户）+ 保留业务审批装配。
        foreach ($tenant in @('p1t1', 'p1t2')) {
            $body = @{ tenantId = $tenant; username = 'p1b-admin'; password = $script:Secrets['fixtureUser'] } | ConvertTo-Json -Compress
            $r = Send-Json $client 'POST' ($script:PlatformBase + '/auth/login') @{} $body
            Assert-That ('B01-login-' + $tenant) 'B' ($r.Status -eq 200 -and $null -ne $r.Json -and [int]$r.Json.code -eq 200) `
                ("POST /auth/login tenant={0} status={1}" -f $tenant, $r.Status)
        }
        $legacyLibs = @($script:PlatformJarEntries | Where-Object { $_ -match 'BOOT-INF/lib/(ruoyi-chat|ruoyi-ai-integration)' })
        $workflowKept = @($script:PlatformJarEntries | Where-Object { $_ -match 'BOOT-INF/lib/ruoyi-workflow' }).Count -gt 0
        Assert-That 'B01-preserved-business' 'B' ($legacyLibs.Count -eq 0 -and $workflowKept) `
            ("legacyAiLibs=[{0}] ruoyi-workflowKept={1}" -f ($legacyLibs -join ','), $workflowKept)

        # facts 可用性：缺 facts 时"事实类"断言一律 NOT_RUN，绝不读 null 当 0 判 PASS。
        $factsPath = Join-Path $script:Evidence 'probe\ai-runtime-facts.json'
        if (Test-Path -LiteralPath $factsPath) {
            try { $script:Facts = Get-Content -LiteralPath $factsPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $script:Facts = $null }
        }
        $factsOk = ($null -ne $script:Facts -and @(Get-FactsMissingField $script:Facts).Count -eq 0)

        # B02：默认无 p04 属性 + 显式 p04=false 的启动事实（BOOT 阶段保证），未注册 health 路径必须 404。
        foreach ($probe in @(@{ m = 'GET'; p = '/actuator/health' }, @{ m = 'GET'; p = '/p04/health' }, @{ m = 'POST'; p = '/p04/control/reset' })) {
            $r = Send-Json $client $probe.m ($script:AiBase + $probe.p) @{} '{}'
            Assert-That ('B02-unregistered-' + $probe.p) 'B' ($r.Status -eq 404) ("{0} {1} status={2}" -f $probe.m, $probe.p, $r.Status)
        }
        if ($factsOk) {
            $experimental = @($script:Facts.routes | Where-Object { $_ -match '^/(p04/|internal/ai/v1/|api/ai/v1/)' })
            Assert-That 'B02-no-experimental-routes' 'B' ($experimental.Count -eq 0) ("experimentalRoutes=[{0}]" -f ($experimental -join ','))
        } else {
            Add-Result 'B02-no-experimental-routes' 'B' 'NOT_RUN' 'blocked: probe facts unavailable or incomplete (route inventory unknown)'
        }

        # B03：旧登录/用户管理在 handler 之前关闭（无 token / 旧 token / admin token / platform token 同口径）。
        $credentials = @(
            [pscustomobject]@{ label = 'none'; headers = @{} },
            [pscustomobject]@{ label = 'old-ai-token'; headers = @{ Authorization = 'Bearer legacy-ai-token' } },
            [pscustomobject]@{ label = 'admin-token'; headers = @{ Authorization = 'Bearer synthetic-admin-token' } },
            [pscustomobject]@{ label = 'platform-token'; headers = @{ Authorization = 'Bearer synthetic-platform-token' } }
        )
        foreach ($credential in $credentials) {
            foreach ($probe in @([pscustomobject]@{ m = 'POST'; p = '/auth/login' }, [pscustomobject]@{ m = 'POST'; p = '/auth/logout' },
                    [pscustomobject]@{ m = 'POST'; p = '/users' }, [pscustomobject]@{ m = 'GET'; p = '/user/me' },
                    [pscustomobject]@{ m = 'PUT'; p = '/user/password' })) {
                $r = Send-Json $client $probe.m ($script:AiBase + $probe.p) $credential.headers '{"userId":"forged","tenantId":"forged"}'
                Assert-ClosedEnvelope $r ('B03-' + $credential.label + '-' + $probe.m + $probe.p) ($credential.label + ' ' + $probe.m + ' ' + $probe.p)
            }
        }
        if ($factsOk) {
            Assert-That 'B03-zero-handler-executions' 'B' (([int]$script:Facts.counters.handlerExecutions -eq 0) -and
                ([int]$script:Facts.counters.userMapperInvocations -eq 0) -and ([int]$script:Facts.counters.authServiceInvocations -eq 0) -and
                ([int]$script:Facts.counters.saTokenLogins -eq 0)) `
                ("handler/userMapper/authService/saTokenLogins={0}/{1}/{2}/{3}" -f $script:Facts.counters.handlerExecutions,
                    $script:Facts.counters.userMapperInvocations, $script:Facts.counters.authServiceInvocations, $script:Facts.counters.saTokenLogins)
        } else {
            Add-Result 'B03-zero-handler-executions' 'B' 'NOT_RUN' 'blocked: probe facts unavailable or incomplete (handler/Mapper counters unknown)'
        }

        # B04：02 文档里所有旧 controller 映射方法 + 未知路径。
        $legacyPaths = @()
        if ($factsOk) { $legacyPaths = @($script:Facts.legacyMappings | ForEach-Object { $_.path }) }
        if ($legacyPaths.Count -eq 0) {
            $legacyPaths = @('/chat/chat', '/knowledge/doc', '/rag/query', '/agent/chat', '/memory/list', '/trace/dashboard')
            Add-Result 'B04-mapping-inventory' 'B' 'NOT_RUN' `
                ("blocked: probe facts unavailable; probed the fallback path list only ({0}), NOT the full 02 mapping inventory" -f ($legacyPaths -join ','))
        } else {
            Add-Result 'B04-mapping-inventory' 'B' 'PASS' ("probing {0} spec-mapped legacy paths from probe facts" -f $legacyPaths.Count)
        }
        $badLegacy = @()
        foreach ($path in $legacyPaths) {
            $r = Send-Json $client 'POST' ($script:AiBase + $path) @{} '{}'
            if ($r.Status -ne 404) { $badLegacy += ($path + '=' + $r.Status) }
        }
        Assert-That 'B04-legacy-mappings' 'B' ($badLegacy.Count -eq 0) ("probed={0} nonClosed=[{1}]" -f $legacyPaths.Count, ($badLegacy -join ','))
        $unknown = Send-Json $client 'GET' ($script:AiBase + '/p1b-unknown-resource-6f2c') $null $null
        Assert-That 'B04-unknown-path' 'B' ($unknown.Status -eq 404) ("unknown status={0} (must equal existing-other-tenant externals)" -f $unknown.Status)

        # B05：伪造 header/body 不得被解析成可信 principal，payload 不进安全日志。
        $marker = 'p1b-forged-marker-7a1d'
        $forgedHeaders = @{ 'X-Tenant' = 'p1t1'; 'X-User' = 'forged-admin'
            'X-P04-Service-Credential' = 'forged-credential'; Authorization = 'Bearer forged.eyJhbGciOiJub25lIn0.forged' }
        $r = Send-Json $client 'POST' ($script:AiBase + '/knowledge/doc') $forgedHeaders `
            (@{ userId = 'forged'; tenantId = 'p1t1'; marker = $marker } | ConvertTo-Json -Compress)
        Assert-ClosedEnvelope $r 'B05-forged-headers' 'forged headers on a closed route'
        $aiLogText = ''
        foreach ($log in @(Get-ChildItem -LiteralPath (Join-Path $script:Evidence 'jvm-logs') -Recurse -File -ErrorAction SilentlyContinue)) {
            $aiLogText += (Protect-LogText ([IO.File]::ReadAllText($log.FullName)))
        }
        Assert-That 'B05-no-payload-in-audit' 'B' ($aiLogText -notmatch [regex]::Escape($marker)) `
            ("forged marker present in sanitized logs={0} (must be false)" -f ($aiLogText -match [regex]::Escape($marker)))

        # B06：尾斜线 / encoded / 不同 method / FORWARD-ASYNC-ERROR dispatcher / 合法 OPTIONS。
        foreach ($path in @('/auth/login/', '/auth%2flogin', '/auth/login%20', '/%2e%2e/auth/login')) {
            $r = Send-Json $client 'GET' ($script:AiBase + $path) @{} ''
            Assert-That ('B06-normalisation-' + $path) 'B' ($r.Status -eq 400 -or $r.Status -eq 404) ("GET {0} status={1}" -f $path, $r.Status)
        }
        $options = Send-Json $client 'OPTIONS' ($script:AiBase + '/auth/login') `
            @{ Origin = 'http://127.0.0.1:5173'; 'Access-Control-Request-Method' = 'POST' } ''
        Assert-That 'B06-options' 'B' ($options.Status -eq 204 -or $options.Status -eq 404) `
            ("OPTIONS status={0} (204 allowed, but no handler execution and no business success)" -f $options.Status)
        if ($factsOk) {
            Assert-That 'B06-dispatcher-policy' 'B' (($script:Facts.dispatch.forwardClosed -eq $true) -and
                ($script:Facts.dispatch.asyncClosed -eq $true) -and ([int]$script:Facts.dispatch.errorRecursion -eq 0) -and
                ([int]$script:Facts.counters.handlerExecutions -eq 0)) `
                ("forwardClosed={0} asyncClosed={1} errorRecursion={2} handlerExecutions={3}" -f $script:Facts.dispatch.forwardClosed,
                    $script:Facts.dispatch.asyncClosed, $script:Facts.dispatch.errorRecursion, $script:Facts.counters.handlerExecutions)
        } else {
            Add-Result 'B06-dispatcher-policy' 'B' 'NOT_RUN' 'blocked: probe facts unavailable or incomplete (FORWARD/ASYNC/ERROR dispatch facts unknown)'
        }

        # B07：尚未开放/未注册的能力一律 404。
        foreach ($probe in @([pscustomobject]@{ m = 'POST'; p = '/internal/ai/v1/runs' }, [pscustomobject]@{ m = 'POST'; p = '/internal/ai/v1/runs/x' },
                [pscustomobject]@{ m = 'POST'; p = '/api/ai/v1/runs' }, [pscustomobject]@{ m = 'GET'; p = '/p04/health' })) {
            $r = Send-Json $client $probe.m ($script:AiBase + $probe.p) @{} '{}'
            Assert-That ('B07-' + $probe.p) 'B' ($r.Status -eq 404) ("{0} {1} status={2}" -f $probe.m, $probe.p, $r.Status)
        }

        # B08：非法开关必须让产品启动非零退出（真实启动在下面完成）。
        foreach ($state in @('illegal-p04', 'illegal-integration', 'illegal-customer-api', 'illegal-legacy-listeners')) {
            $entry = @($script:IllegalBootResults | Where-Object { $_.state -ceq $state })
            $ok = ($entry.Count -eq 1 -and $entry[0].exited -and [int]$entry[0].exitCode -ne 0)
            Assert-That ('B08-' + $state) 'B' $ok `
                ("state={0} exited={1} exitCode={2} (non-zero required; redacted configuration refusal in log)" -f `
                    $state, $(if ($entry.Count -eq 1) { $entry[0].exited } else { 'notBooted' }), $(if ($entry.Count -eq 1) { $entry[0].exitCode } else { 'n/a' }))
        }

        Invoke-FactsDrivenCases

        $script:DbAfter = Get-RowHashSnapshot
        Add-Result 'DB-snapshot-after' 'G1' 'PASS' ("tables={0}" -f @($script:DbAfter.PSObject.Properties.Name).Count)
        $delta = Compare-RowHashSnapshots $script:DbBefore $script:DbAfter
        $script:DbDelta = $delta
        Assert-That 'DB-no-business-delta' 'G1' $delta.unchanged `
            ("tables={0} missing=[{1}] added=[{2}] changed=[{3}]" -f $delta.tables, ($delta.missing -join ','), ($delta.added -join ','), ($delta.changed -join ','))

        # B13：platform 运行 jar 内容 + 残留 /workflow/run 安全排除项。
        $testClasses = @($script:PlatformJarEntries | Where-Object { $_ -match 'BOOT-INF/classes/.*Test.*\.class$' })
        $workflowRunExclusion = ($script:PlatformJarConfig -match '/workflow/run')
        Assert-That 'B13-jar-contents' 'B' ($legacyLibs.Count -eq 0 -and $testClasses.Count -eq 0 -and $workflowKept) `
            ("legacyAiLibs={0} testClasses={1} ruoyi-workflowKept={2}" -f $legacyLibs.Count, $testClasses.Count, $workflowKept)
        Assert-That 'B13-workflow-run-exclusion-removed' 'B' (-not $workflowRunExclusion) `
            ("packaged application.yml still excludes /workflow/run={0}" -f $workflowRunExclusion)

        $caseRows = @($script:Results | Where-Object { $_.id -match '^B\d\d$' })
        Assert-That 'CASES-complete' 'G1-G4' ($caseRows.Count -eq 13 -and @($caseRows | Where-Object { $_.status -ne 'PASS' }).Count -eq 0) `
            ("cases={0} nonPass=[{1}]" -f $caseRows.Count, (@($caseRows | Where-Object { $_.status -ne 'PASS' } | ForEach-Object { $_.id }) -join ','))
    } finally { $client.Dispose() }
}
function Invoke-BootAndCases {
    Write-Step 'Integration happy path：两真实 jar + runner 自有合成资源 + B01–B13'
    Initialize-SyntheticSecrets
    # 合成端口先定下来（同样走空闲扫描：绝不复用开发机 5432/6379/9000），
    # 再确定本轮自有资源身份并写 compose 文件——顺序不能反，见 Initialize-SyntheticIdentity。
    $script:PgPort = Get-FreePortInRange 15432 15472
    $script:RedisPort = Get-FreePortInRange 16379 16419
    $script:S3Port = Get-FreePortInRange 19000 19040
    if (0 -in @($script:PgPort, $script:RedisPort, $script:S3Port)) {
        Add-Result 'ENV-compose-up' 'G0' 'FAIL' 'no free synthetic port available for PG/Redis/S3'
        return
    }
    Initialize-SyntheticIdentity
    [void](Test-P04ContainerIsolation)
    Start-SyntheticEnvironment
    $upRows = Get-ResultRow 'ENV-compose-up'
    if (@($upRows | Where-Object { $_.status -eq 'FAIL' }).Count -gt 0) { return }
    # 合成服务在远端时，先把它们的端口转发到本机回环，再让两个 jar 按原有 127.0.0.1 配置连接。
    if (-not (Start-RemotePortForward)) { return }
    Invoke-SyntheticDatabase
    if (-not (Test-ProbeAvailability)) { return }

    # 平台 jar 内容解析（B13 事实来源）。
    $platformJar = Join-Path $RepoRoot 'services\platform\ruoyi-admin\target\ruoyi-admin.jar'
    $archive = [IO.Compression.ZipFile]::OpenRead($platformJar)
    try {
        $script:PlatformJarEntries = @($archive.Entries | ForEach-Object { $_.FullName })
        $configEntry = @($archive.Entries | Where-Object { $_.FullName -ceq 'BOOT-INF/classes/application.yml' })
        if ($configEntry.Count -eq 1) {
            $reader = New-Object IO.StreamReader($configEntry[0].Open())
            try { $script:PlatformJarConfig = $reader.ReadToEnd() } finally { $reader.Dispose() }
        }
    } finally { $archive.Dispose() }

    $script:PlatformBase = ('http://127.0.0.1:' + $PlatformPort)
    $script:AiBase = ('http://127.0.0.1:' + $AiPort + '/api/ragent')
    $platformProc = Start-ProductJar 'platform' 'default' $PlatformPort
    $aiProc = Start-ProductJar 'ai' 'default' $AiPort
    $platformReady = Wait-ApplicationReady $PlatformPort (Join-Path $script:RunWork 'platform-run-default\stdout.log') 240
    $aiReady = Wait-ApplicationReady $AiPort (Join-Path $script:RunWork 'ai-run-default\stdout.log') 240
    Assert-That 'BOOT-platform-jar' 'G0' ($platformReady -and -not $platformProc.HasExited) `
        ("ready={0} alive={1} port={2}" -f $platformReady, (-not $platformProc.HasExited), $PlatformPort)
    Assert-That 'BOOT-ai-jar' 'G0' ($aiReady -and -not $aiProc.HasExited) `
        ("default boot ready={0} alive={1} port={2}" -f $aiReady, (-not $aiProc.HasExited), $AiPort)
    Stop-OwnedProcess (Join-Path $script:RunWork 'platform-default.pid') 'java' 'platform default boot'

    # 显式 p04=false 的第二态启动（B02 的另一半）。
    $aiFalsePort = $AiPort + 1
    $aiFalse = Start-ProductJar 'ai' 'cli-false' $aiFalsePort
    $falseReady = Wait-ApplicationReady $aiFalsePort (Join-Path $script:RunWork 'ai-run-cli-false\stdout.log') 240
    Assert-That 'BOOT-ai-jar' 'G0' ($falseReady -and -not $aiFalse.HasExited) `
        ("explicit p04.enabled=false boot ready={0} alive={1}" -f $falseReady, (-not $aiFalse.HasExited))
    Stop-OwnedProcess (Join-Path $script:RunWork 'ai-cli-false.pid') 'java' 'ai p04=false boot'

    # B08：四个非法开关各起一次，必须非零退出。
    $script:IllegalBootResults = @()
    $statePort = $AiPort + 10
    foreach ($state in @('illegal-p04', 'illegal-integration', 'illegal-customer-api', 'illegal-legacy-listeners')) {
        $p = Start-ProductJar 'ai' $state $statePort
        $exited = Wait-ProcessExit $p 180
        $code = if ($exited) { $p.ExitCode } else { $null }
        $script:IllegalBootResults += [pscustomobject]@{ state = $state; exited = $exited; exitCode = $code
            log = ('jvm-logs\ai-' + $state + '\stdout.log') }
        if (-not $exited) { Stop-OwnedProcess (Join-Path $script:RunWork ('ai-' + $state + '.pid')) 'java' ('illegal switch ' + $state) }
        $statePort++
    }
    Invoke-HttpCases
}

# =========================== 入口 ===========================
function Stop-Usage([string]$Message) {
    Write-Output ("### USAGE REFUSED: " + $Message)
    exit 2
}
if (-not [IO.Path]::IsPathRooted($EvidenceDir)) {
    Stop-Usage ("-EvidenceDir must be an ABSOLUTE path, got '{0}'. Example: D:/AI-project/mydocs/p1/evidence/full/p12a-unit" -f $EvidenceDir)
}
$script:EvidenceRoot = [IO.Path]::GetFullPath($EvidenceDir)
if (Test-UnderRootLoose $script:EvidenceRoot $RepoRoot) {
    Stop-Usage ("-EvidenceDir resolves inside the repository ({0}); this runner must not modify the repository. Use D:/AI-project/mydocs/p1/evidence/... (outside the checkout)." -f $script:EvidenceRoot)
}
$script:WorkRoot = [IO.Path]::GetFullPath($WorkRoot)
if (Test-UnderRootLoose $script:WorkRoot $RepoRoot) {
    Stop-Usage ("-WorkRoot resolves inside the repository ({0}); scratch/work files would modify the repository." -f $script:WorkRoot)
}
try {
    [void](New-Item -ItemType Directory -Force -Path $script:WorkRoot)
    [void](New-Item -ItemType Directory -Force -Path $script:EvidenceRoot)
} catch { Stop-Usage ("cannot create WorkRoot/EvidenceDir: " + $_.Exception.Message) }
$script:Evidence = Join-Path $script:EvidenceRoot $script:ExecutionId
[void](New-Item -ItemType Directory -Force -Path $script:Evidence)
$script:RunWork = Join-Path $script:WorkRoot $script:ExecutionId
[void](New-Item -ItemType Directory -Force -Path $script:RunWork)

Write-Step ("P1.2a boundary runner — mode={0} runTag={1} executionId={2}" -f $Mode, $RunTag, $script:ExecutionId)
$toolchain = Initialize-Toolchain
Add-Result 'ENV-evidence-path' 'G0' 'PASS' ("absolute={0} outsideRepo=true evidence={1}" -f $script:EvidenceRoot, $script:Evidence)
Add-Result 'ENV-workroot-safety' 'G0' 'PASS' ("workRoot={0} outsideRepo=true runWork={1}" -f $script:WorkRoot, $script:RunWork)

$headResult = Invoke-NativeCapture 'git' @('-C', $RepoRoot, 'rev-parse', 'HEAD') 'git-head.log'
$branchResult = Invoke-NativeCapture 'git' @('-C', $RepoRoot, 'branch', '--show-current') 'git-branch.log'
$statusResult = Invoke-NativeCapture 'git' @('-C', $RepoRoot, 'status', '--porcelain') 'git-status.log'
if ($headResult.ExitCode -ne 0 -or $branchResult.ExitCode -ne 0) { throw 'git identity preflight failed' }
$script:HeadSha = ((@($headResult.Output | Where-Object { $_.Trim() }) | Select-Object -First 1) -join '')
$script:Branch = ((@($branchResult.Output | Where-Object { $_.Trim() }) | Select-Object -First 1) -join '')
$script:DirtyPaths = @($statusResult.Output | Where-Object { $_.Trim() -and ([string]$_).Length -gt 3 } | ForEach-Object { ([string]$_).Substring(3) })

$manifest = [ordered]@{
    runner = 'tools/p1-boundary/run.ps1'
    spec = 'D:\AI-project\mydocs\p1\01-p1-first-unit-spec.md (SS6 commands/params, SS7 B01-B14, SS8 evidence)'
    plan = 'D:\AI-project\mydocs\p1\00-p1-plan.md (SS9 commands/environment/evidence)'
    executionId = $script:ExecutionId
    mode = $Mode
    runTag = $RunTag
    repoRoot = $RepoRoot
    branch = $script:Branch
    headSha = $script:HeadSha
    dirtyPathCount = $script:DirtyPaths.Count
    dirtyPaths = $script:DirtyPaths
    startedUtc = $script:RunStartedUtc.ToString('o')
    startedAsiaShanghai = (Get-ShanghaiStamp $script:RunStartedUtc)
    evidenceDir = $script:Evidence
    workRoot = $script:WorkRoot
    runWork = $script:RunWork
    host = @{ psVersion = $PSVersionTable.PSVersion.ToString(); java = $toolchain.java
        javaHome = $toolchain.javaHome; mavenExe = $toolchain.mavenExe; maven = $toolchain.maven }
    ports = @{ platform = $PlatformPort; ai = $AiPort }
    requiredClasses = @($script:RequiredClasses | ForEach-Object { $_.expectedFqcn })
    unitCommands = @($script:UnitInvocations | ForEach-Object {
            (New-CommandLine 'mvn' @('-o', '-B', '-ntp', '-f', $_.pom, $_.profile, '-pl', $_.module, '-am', 'test',
                    ('-Dtest=' + ($_.tests -join ',')), '-Dsurefire.failIfNoSpecifiedTests=false')) })
    specFileSha256 = @($script:SpecPaths | ForEach-Object { [pscustomobject]@{ path = $_; sha256 = (Get-Sha256 $_) } })
    forceBuildRoots = [bool]$ForceBuildRoots
    offline = $true
}

$exitCode = 0
try {
    if ($Mode -eq 'Unit') {
        Invoke-UnitMode
    } else {
        Invoke-IntegrationPortCheck
        Invoke-ContainerRuntimePreflight
        Invoke-SubstituteRefusalScan
        Invoke-ProviderKeyIsolation
        if ($script:EnvGateBlocked -and -not $ForceBuildRoots) {
            Publish-BlockedInventory
        } else {
            if ($script:EnvGateBlocked) {
                Write-Step '评审辅助 -ForceBuildRoots：环境已判定缺失，但仍执行两条 clean verify（BUILD-* 记录真实结果）'
                Publish-GatedNotRun -ExceptIds @('BUILD-platform-clean-verify', 'BUILD-ai-clean-verify')
            }
            foreach ($root in @(
                    [pscustomobject]@{ id = 'BUILD-platform-clean-verify'; pom = 'services/platform/pom.xml'; profile = '-Pdev'; log = 'build-platform-clean-verify.log' },
                    [pscustomobject]@{ id = 'BUILD-ai-clean-verify'; pom = 'services/ai/pom.xml'; profile = '-Pci'; log = 'build-ai-clean-verify.log' })) {
                $r = Invoke-NativeCapture $script:MavenExe @('-o', '-B', '-ntp', '-f', $root.pom, $root.profile, 'clean', 'verify') $root.log $RepoRoot
                $ok = ($r.ExitCode -eq 0 -and @($r.Output | Where-Object { $_ -match '^\[INFO\] BUILD SUCCESS$' }).Count -gt 0)
                Assert-That $root.id 'G0' $ok ("exit={0} log={1}" -f $r.ExitCode, $root.log)
            }
            if (-not $script:EnvGateBlocked) { Invoke-BootAndCases }
            Complete-IntegrationInventory 'environment gate open'
        }
    }
} catch {
    Add-Result 'HARNESS' '-' 'FAIL' ("unhandled: " + $_.Exception.Message)
} finally {
    Write-Step '清理：只停止本脚本自己启动的 PID / 只销毁带本轮 owner label 的容器'
    # 1) 自有 JVM：pid 文件 + PID 归属 + 命令行三重核对。
    $pidFiles = @(Get-ChildItem -LiteralPath $script:RunWork -Filter '*.pid' -File -ErrorAction SilentlyContinue)
    foreach ($pidFile in $pidFiles) {
        # ssh 端口转发同样是本脚本启动、并按 PID 归属核对后才停止的自有进程；
        # 命令行的判别模式不同（ssh 而非 java），因此按文件名分流。
        if ($pidFile.Name -eq 'ssh-port-forward.pid') {
            Stop-OwnedProcess $pidFile.FullName 'ssh' 'owned ssh port-forward'
        } else {
            Stop-OwnedProcess $pidFile.FullName 'java' ('owned JVM ' + $pidFile.Name)
        }
    }
    if ($pidFiles.Count -eq 0) {
        Add-Result 'CLEANUP-owned-processes' 'G0' 'PASS' 'no JVM was started by this run; nothing to stop'
    } else {
        $owned = @($script:Cleanup | Where-Object { $_.id -eq 'CLEANUP-owned-process' })
        $stopped = @($owned | Where-Object { $_.result -eq 'STOPPED' -or $_.result -eq 'ALREADY_GONE' })
        Assert-That 'CLEANUP-owned-processes' 'G0' ($owned.Count -eq $pidFiles.Count -and $stopped.Count -eq $owned.Count) `
            ("pidFiles={0} accounted={1} stoppedOrGone={2} startedProcessIds=[{3}]" -f `
                $pidFiles.Count, $owned.Count, $stopped.Count, ($script:StartedProcesses -join ','))
    }
    # 2) jar 日志先按脱敏归档，再删自有临时目录（顺序不能反）。
    foreach ($side in @('platform-default', 'ai-default', 'ai-cli-false', 'ai-illegal-p04', 'ai-illegal-integration',
            'ai-illegal-customer-api', 'ai-illegal-legacy-listeners')) {
        foreach ($stream in @('stdout', 'stderr')) {
            Copy-SanitizedLog (Join-Path (Join-Path $script:RunWork ($side + '-run')) ($stream + '.log')) `
                (Join-Path $script:Evidence ('jvm-logs\' + $side + '\' + $stream + '.log'))
        }
    }
    # 2b) 远端自有 compose 目录：只删本轮自己建的路径，路径由 run tag 派生且已核对前缀。
    if ($script:RemoteMode -and $script:RemoteComposeDir) {
        if ($script:RemoteComposeDir -match '^/opt/p1-acceptance/[a-z0-9-]+$') {
            $rm = Invoke-RemoteShell ('rm -rf ' + (Format-ShellArg $script:RemoteComposeDir)) 'compose-remote-cleanup.log'
            [void]$script:Cleanup.Add([pscustomobject]@{ id = 'CLEANUP-remote-compose-dir'; target = $script:RemoteComposeDir
                    action = 'rm -rf'; result = $(if ($rm.ExitCode -eq 0) { 'REMOVED' } else { 'FAILED' })
                    detail = ("exit=" + $rm.ExitCode + " (only this run's own tag-derived directory)") })
        } else {
            Add-Result 'CLEANUP-remote-compose-dir' 'G0' 'FAIL' `
                ("refusing to remove unexpected remote path: " + $script:RemoteComposeDir)
        }
    }
    # 3) 自有容器：只按本轮 project/label 销毁。Unit 模式不起容器，不产生容器清理结论（只记 cleanup.json）。
    if ($Mode -ne 'Integration') {
        [void]$script:Cleanup.Add([pscustomobject]@{ id = 'CLEANUP-owned-containers'; target = 'none'
                action = 'noop'; result = 'NOT_APPLICABLE'; detail = 'Unit mode starts no container and no database' })
    } elseif ($script:OwnedContainers.Count -gt 0 -and $null -ne $script:Runtime) {
        $down = Invoke-OwnedCompose @('down', '--volumes', '--remove-orphans', '--timeout', '20') 'compose-down.log'
        $left = Invoke-RuntimeCapture @('ps', '--all', '--filter', ('label=p1.boundary.owner=' + $RunTag), '--format', '{{.Names}}') 'compose-leftover.log'
        $remaining = @($left.Output | Where-Object { $_.Trim() })
        Assert-That 'CLEANUP-owned-containers' 'G0' ($down.ExitCode -eq 0 -and $remaining.Count -eq 0) `
            ("down exit={0} remainingOwnedContainers={1} [{2}]" -f $down.ExitCode, $remaining.Count, ($remaining -join ','))
    } elseif ($script:GatedNotRun -contains 'CLEANUP-owned-containers') {
        Write-Output '  CLEANUP-owned-containers 已在闸门关闭时记为 NOT_RUN（本轮未创建容器）。'
    } else {
        Add-GatedResult 'CLEANUP-owned-containers' 'G0' 'destroy runner-owned synthetic containers/volumes by owner label'
    }
    # 4) 随机口令：从进程环境清除；证据里只留 key 名。
    foreach ($name in @('PGPASSWORD', 'P1B_PG_SUPERUSER_PASSWORD', 'P1B_REDIS_PASSWORD', 'P1B_S3_ACCESS_KEY', 'P1B_S3_SECRET_KEY',
            'AI_DB_PASSWORD', 'PLATFORM_DB_PASSWORD', 'REDIS_PASSWORD')) {
        Remove-Item -LiteralPath ('env:' + $name) -Force -ErrorAction SilentlyContinue
        [void]$script:Cleanup.Add([pscustomobject]@{ id = 'CLEANUP-synthetic-secrets'; target = $name
                action = 'env-remove'; result = 'CLEARED'; detail = 'value never written to evidence' })
    }
    if ($Mode -ne 'Integration') {
        Write-Output '  Unit 模式未生成任何合成口令；仍清掉 PGPASSWORD/REDIS_PASSWORD 等环境变量（见 cleanup.json）。'
    } elseif ($script:GatedNotRun -contains 'CLEANUP-synthetic-secrets') {
        Write-Output '  CLEANUP-synthetic-secrets 已在闸门关闭时记为 NOT_RUN（本轮未生成合成口令）。'
    } elseif ($script:EnvGateBlocked) {
        Add-GatedResult 'CLEANUP-synthetic-secrets' 'G0' 'clear runner-generated synthetic secrets from the process environment'
    } else {
        Add-Result 'CLEANUP-synthetic-secrets' 'G0' 'PASS' 'runner-generated synthetic secrets removed from the process environment; evidence holds key names only'
    }
    # 5) 自有临时工作目录（解析绝对路径 + 归属校验后才删）。
    if (Test-Path -LiteralPath $script:RunWork) { [void](Remove-OwnedPath $script:RunWork 'per-run scratch under WorkRoot') }

    # ---------- 证据落盘 ----------
    if ($Mode -eq 'Unit') {
        $script:Surefire | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $script:Evidence 'surefire-summary.json') -Encoding UTF8
    } else {
        $script:Probes | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'preflight.json') -Encoding UTF8
        # 即使一条 refusal 都没有，也要显式写出 []（"没有需要拒绝的替身资源"本身是结论，不能靠缺文件表达）。
        $refusalJson = if ($script:Refusals.Count -eq 0) { '[]' } else { ($script:Refusals | ConvertTo-Json -Depth 6) }
        Set-Content -LiteralPath (Join-Path $script:Evidence 'refusals.json') -Value $refusalJson -Encoding UTF8
        $gated = [ordered]@{
            blocked = $script:EnvGateBlocked; reason = $script:GateReason; reasonDetail = $script:GateReasonDetail
            plannedGatedChecks = @($script:GatedInventoryPlan | ForEach-Object { $_.id })
            notRunIds = @($script:GatedNotRun); alwaysRunIds = $script:AlwaysRunIds
            refusals = @($script:Refusals | ForEach-Object { $_.id })
            note = 'Integration happy path is implemented but gated; absence of an environment is NOT_RUN (exit 0), never PASS and never a fake FAIL.'
        }
        $gated | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'gated-inventory.json') -Encoding UTF8
        if ($script:HttpLog.Count -gt 0) {
            $script:HttpLog | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 6 } | Set-Content -LiteralPath (Join-Path $script:Evidence 'http.jsonl') -Encoding UTF8
        }
        if ($null -ne $script:DbBefore) { $script:DbBefore | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $script:Evidence 'db-before.json') -Encoding UTF8 }
        if ($null -ne $script:DbAfter) { $script:DbAfter | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $script:Evidence 'db-after.json') -Encoding UTF8 }
        if ($null -ne $script:DbDelta) { $script:DbDelta | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'db-delta.json') -Encoding UTF8 }
        if ($null -ne $script:Facts) {
            [pscustomobject]@{ mqConsumers = @($script:Facts.mqConsumers); transactionCheckers = @($script:Facts.transactionCheckers)
                mcpConnects = $script:Facts.mcpConnects; createdBuckets = @($script:Facts.createdBuckets)
                createdIndexes = @($script:Facts.createdIndexes); handlerExecutions = $script:Facts.counters.handlerExecutions
            } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'listener-registration.json') -Encoding UTF8
        }
    }

    $finished = (Get-Date).ToUniversalTime()
    $manifest['finishedUtc'] = $finished.ToString('o')
    $manifest['finishedAsiaShanghai'] = (Get-ShanghaiStamp $finished)
    $manifest['durationSeconds'] = [int]($finished - $script:RunStartedUtc).TotalSeconds
    $manifest['nativeCommandCount'] = $script:NativeCommands.Count
    $manifest['nativeCommandsFile'] = 'native-commands.json'
    $manifest['ownedProcessIds'] = @($script:StartedProcesses)
    $manifest['ownedContainerNames'] = @($script:OwnedContainers)
    $manifest['cleanup'] = @($script:Cleanup)
    $manifest['cleanupFile'] = 'cleanup.json'
    $manifest['secretsPolicy'] = 'passwords/keys are generated per run, passed only through child process environment, redacted in every written file; evidence records key names only'
    $manifest['secretKeyNames'] = @($script:Secrets.Keys)
    if ($Mode -eq 'Integration') {
        $manifest['environmentGate'] = @{ blocked = $script:EnvGateBlocked; reason = $script:GateReason
            reasonDetail = $script:GateReasonDetail; probes = @($script:Probes) }
        $manifest['refusals'] = @($script:Refusals)
        $manifest['notRunCheckIds'] = @($script:GatedNotRun)
        $manifest['notRunPolicy'] = 'absence of a container runtime / synthetic environment => NOT_RUN with the exact probe evidence; exit 0; never PASS'
        $manifest['substituteRefusalPolicy'] = 'never connect to or impersonate an existing local/remote business database, cache or object store (probed 127.0.0.1:5432/6379/9000/15434/3306/19530)'
        $manifest['integrationInventoryPlan'] = @($script:GatedInventoryPlan | ForEach-Object { $_.id })
    } else {
        $manifest['surefireSummaryFile'] = 'surefire-summary.json'
        $manifest['unitInvocationCount'] = $script:UnitInvocations.Count
    }

    if ($Mode -eq 'Integration') { Assert-IntegrationInventory }

    $manifest['resultCounts'] = @{
        pass = @($script:Results | Where-Object { $_.status -eq 'PASS' }).Count
        fail = @($script:Results | Where-Object { $_.status -eq 'FAIL' }).Count
        notRun = @($script:Results | Where-Object { $_.status -eq 'NOT_RUN' }).Count
        refused = @($script:Results | Where-Object { $_.status -eq 'REFUSED' }).Count
    }
    $allowedNotRun = @()
    if ($Mode -eq 'Integration' -and $script:EnvGateBlocked) {
        # 闸门关闭时，被闸门挡住的计划项与预检本身（ENV-container-runtime）都允许是 NOT_RUN：
        # 它们都带确切阻塞原因，且上面的 INVENTORY-integrity 已核对「没有一条被写成 PASS」。
        $allowedNotRun = @($script:GatedNotRun) + @($script:PreflightGatedIds)
    }
    $blocking = @($script:Results | Where-Object {
            $_.status -eq 'FAIL' -or $_.status -eq 'SKIP' -or
            ($_.status -eq 'NOT_RUN' -and $allowedNotRun -notcontains $_.id) })
    $manifest['notRunAllowedInThisMode'] = @($allowedNotRun)
    $manifest['blockingResults'] = @($blocking | ForEach-Object { $_.id + '=' + $_.status })
    $manifest['refusedResults'] = @($script:Results | Where-Object { $_.status -eq 'REFUSED' } | ForEach-Object { $_.id })
    if ($script:Failures -gt 0 -or $blocking.Count -gt 0) { $exitCode = 1 }
    if ($Mode -eq 'Integration' -and $script:EnvGateBlocked -and $exitCode -eq 0) {
        $manifest['closingStatement'] = ('Integration acceptance NOT RUN: {0}. No jar, container or database connection was started; no gated check is reported PASS.' -f $script:GateReason)
    }
    $manifest['harnessEntryExitCode'] = $exitCode

    $script:NativeCommands | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'native-commands.json') -Encoding UTF8
    $script:Cleanup | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'cleanup.json') -Encoding UTF8
    $script:Results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:Evidence 'results.json') -Encoding UTF8
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $script:Evidence 'manifest.json') -Encoding UTF8

    Write-Step ("结果：PASS={0} FAIL={1} NOT_RUN={2} REFUSED={3}；入口 exitCode={4}" -f `
            $manifest['resultCounts'].pass, $manifest['resultCounts'].fail, $manifest['resultCounts'].notRun,
            $manifest['resultCounts'].refused, $exitCode)
    Write-Output ("### 证据目录：" + $script:Evidence)
    if ($Mode -eq 'Unit') {
        foreach ($row in $script:Surefire) {
            Write-Output ("    {0,-40} {1,-5} tests={2} failures={3} errors={4} skips={5} refreshed={6}" -f `
                    $row.class, $row.status, $row.tests, $row.failures, $row.errors, $row.skips, $row.refreshedByThisRun)
        }
    }
}
exit $exitCode
