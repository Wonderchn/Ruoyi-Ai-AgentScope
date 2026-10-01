[CmdletBinding()]
param([string]$TestRoot = (Join-Path $env:TEMP ('p04-acceptance-test-' + [guid]::NewGuid().ToString('N'))))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'acceptance-evidence.ps1')
[void](New-Item -ItemType Directory -Force $TestRoot)
$fixture = Join-Path $TestRoot 'missing-lab-fixture'
[void](New-Item -ItemType Directory -Force $fixture)
$tag = 'p04test'; $container = 'p04-pg-p04test-missing'
$cases = @('P01','N01','N02','N03','N04','N05','N06','N07','I01','I02','I03','I04','I05','I06','F01','F02','F03','T01','T02')
$failure = 'lab query failed: POSTGRES_PASSWORD (ssh/docker exit=1) No such container: ' + $container
$manifest = @{ mode = 'Integration'; runTag = $tag; lab = @{ container = $container }; harnessEntryExitCode = 1
    ownedProcessIds = @(); completedCaseSnapshots = @(); requiredCases = $cases
    missingLabEvidence = @{ reason = 'MISSING_LAB_CONTAINER'; container = $container; confirmedMissing = $true
        probeExitCode = 0; failedCommandIndex = 2; probeCommandIndex = 3; failureDetail = $failure } }
$native = @(
    @{ executable = 'mvn'; exitCode = 0; arguments = @('platform', 'clean', 'verify') },
    @{ executable = 'mvn'; exitCode = 0; arguments = @('ai', 'clean', 'verify') },
    @{ executable = 'ssh'; exitCode = 1; arguments = @('fixture-host', "docker exec $container printenv POSTGRES_PASSWORD") },
    @{ executable = 'ssh'; exitCode = 0; arguments = @('fixture-host', "docker container ls -a --format '{{.Names}}'") })
$results = @(
    @{ id = 'BUILD-platform-clean-verify'; status = 'PASS' },
    @{ id = 'BUILD-ai-clean-verify'; status = 'PASS' },
    @{ id = 'ENV-fresh-ai-artifacts'; status = 'PASS' },
    @{ id = 'ENV-lab'; status = 'NOT_RUN'; detail = 'synthetic lab unavailable: ' + $failure },
    @{ id = 'HARNESS'; status = 'FAIL'; detail = 'unhandled: ' + $failure },
    @{ id = 'CASES-complete'; status = 'FAIL' },
    @{ id = 'ACCEPTANCE-invocation-log'; status = 'NOT_RUN' })
foreach ($case in $cases) { $results += @{ id = 'CASE-' + $case; status = 'NOT_RUN' } }
function Write-Fixture {
    $manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $fixture 'manifest.json') -Encoding UTF8
    $native | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $fixture 'native-commands.json') -Encoding UTF8
    $results | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $fixture 'results.json') -Encoding UTF8
}
$script:Checks = New-Object System.Collections.ArrayList
function Check([string]$Name, [bool]$Condition) {
    [void]$script:Checks.Add([pscustomobject]@{ name = $Name; passed = $Condition })
    if (-not $Condition) { throw "Acceptance regression failed: $Name" }
    Write-Output "PASS $Name"
}
function Validate([int]$Code = 1) {
    return Test-P04MissingLabEvidence -EvidencePath $fixture -ExpectedContainer $container -ExpectedRunTag $tag -EntryExitCode $Code
}
Write-Fixture
Check 'confirmed missing lab accepted' (Validate).valid
Check 'unrelated exit 7 rejected' (-not (Validate 7).valid)
$record = [pscustomobject]@{ label = 'preflight-missing-lab'; expectsNonZero = $true; exitCode = 1
    mode = 'Integration'; runTag = $tag; labContainer = $container; expectedFailure = 'MISSING_LAB_CONTAINER'
    preflightValidation = (Validate) }
Check 'bound preflight record accepted' (Test-P04AcceptanceRecord $record $tag 1)
Check 'supplied exit mismatch rejected' (-not (Test-P04AcceptanceRecord $record $tag 7))
Check 'wrong run tag rejected' (-not (Test-P04AcceptanceRecord $record 'p04other' 1))
Check 'nonzero marker on build rejected' (-not (Test-P04AcceptanceRecord ([pscustomobject]@{label='build-platform-clean-verify';expectsNonZero=$true;exitCode=7}) $tag 1))
Check 'ordinary build failure rejected' (-not (Test-P04AcceptanceRecord ([pscustomobject]@{label='build-ai-clean-verify';expectsNonZero=$false;exitCode=1}) $tag 1))
$manifest.missingLabEvidence.confirmedMissing = $false; Write-Fixture
Check 'unconfirmed reason rejected' (-not (Validate).valid)
$manifest.missingLabEvidence.confirmedMissing = $true
$native[0].exitCode = 7; Write-Fixture
Check 'failed preflight build rejected' (-not (Validate).valid)
$native[0].exitCode = 0
$results[0].status = 'FAIL'; Write-Fixture
Check 'failed prerequisite assertion rejected' (-not (Validate).valid)
$results[0].status = 'PASS'
$native[3].exitCode = 255; Write-Fixture
Check 'SSH inventory failure rejected' (-not (Validate).valid)
$native[3].exitCode = 0
$manifest.ownedProcessIds = @(123); Write-Fixture
Check 'started application rejected' (-not (Validate).valid)
$manifest.ownedProcessIds = @()
$manifest.lab.container = 'unrelated-container'; Write-Fixture
Check 'wrong container rejected' (-not (Validate).valid)
$manifest.lab.container = $container
$results += @{id='UNRELATED';status='FAIL'}; Write-Fixture
Check 'unrelated assertion rejected' (-not (Validate).valid)
$results = @($results | Where-Object {$_.id -ne 'UNRELATED'}); Write-Fixture
Check 'changed evidence fingerprint rejected' (-not (Test-P04AcceptanceRecord $record $tag 1))
$record.preflightValidation = Validate
Check 'restored evidence accepted' (Test-P04AcceptanceRecord $record $tag 1)

# Verify the real invocation reader rejects marker relocation and duplicate build labels.
$tokens=$null; $parseErrors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'run.ps1'),[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'run.ps1 parse failed'}
foreach($name in @('Add-Result','Assert-That','Read-AcceptanceInvocation')) {
    $fn=$ast.Find({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
    . ([scriptblock]::Create($fn.Extent.Text))
}
$validCommands=@(
    [pscustomobject]@{label='build-platform-clean-verify';kind='mvn clean verify (platform)';exitCode=0;expectsNonZero=$false},
    [pscustomobject]@{label='build-ai-clean-verify';kind='mvn clean verify (ai)';exitCode=0;expectsNonZero=$false},
    [pscustomobject]@{label='unit-entry';kind='run.ps1 -Mode Unit';exitCode=0;expectsNonZero=$false},$record)
$Mode='Integration'; $RunTag=$tag; $PreflightEntryExitCode=1
$InvocationLogPath=Join-Path $TestRoot 'invocation-fixture.json'
foreach($scenario in @('valid','wrong-marker','duplicate-root')) {
    $clone=@{commands=$validCommands}|ConvertTo-Json -Depth 10|ConvertFrom-Json
    $commands=@($clone.commands)
    if($scenario -eq 'wrong-marker'){$commands[0].exitCode=7;$commands[0].expectsNonZero=$true}
    if($scenario -eq 'duplicate-root'){$commands[1].label='build-platform-clean-verify'}
    @{runTag=$tag;commandCount=4;commands=$commands}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $InvocationLogPath -Encoding UTF8
    $script:Failures=0;$script:Results=New-Object System.Collections.ArrayList
    try{Read-AcceptanceInvocation}catch{}
    Check ('real invocation reader: '+$scenario) (($script:Failures -gt 0) -eq ($scenario -ne 'valid'))
}
$caseGuard=$ast.Find({param($n) $n -is [System.Management.Automation.Language.IfStatementAst] -and $n.Extent.Text.Contains('$missingCases =')},$true)
$strictAssignment=$ast.Find({param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$strictBad'},$true)
$exitGuard=$ast.Find({param($n) $n -is [System.Management.Automation.Language.IfStatementAst] -and $n.Extent.Text.Contains('$strictBad.Count -gt 0')},$true)
$script:Failures=0;$script:Results=New-Object System.Collections.ArrayList;$script:DbAfter=[ordered]@{}
$requiredCases=$cases; foreach($case in $cases){if($case -ne 'N07'){$script:DbAfter[$case]=@{}}}
. ([scriptblock]::Create($caseGuard.Extent.Text))
$allowedNotRun=@('ACCEPTANCE-wrapper-selfcheck');$exitCode=0
. ([scriptblock]::Create($strictAssignment.Extent.Text));. ([scriptblock]::Create($exitGuard.Extent.Text))
Check 'missing required N07 exits nonzero' ($exitCode -eq 1)

# Exercise the real wrapper process with harmless child commands, preserving native exit codes.
$fakeRepo = Join-Path $TestRoot 'fake-repo'; $fakeBin = Join-Path $TestRoot 'fake-bin'
[void](New-Item -ItemType Directory -Force (Join-Path $fakeRepo 'tools\p04-contract'), $fakeBin)
@'
@echo off
echo [INFO] BUILD SUCCESS
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $fakeBin 'mvn.cmd') -Encoding ASCII
@'
param([string]$Mode,[string]$EvidenceDir,[string]$RunTag,[string]$WorkRoot,[string]$RepoRoot,[string]$LabHost,[string]$LabContainer,[string]$LabDb,[int]$PlatformPort,[int]$AiPort,[string]$InvocationLogPath,[int]$PreflightEntryExitCode)
if($Mode -eq 'Unit' -and $env:P04_ACCEPTANCE_SCENARIO -eq 'unit-failure'){exit 7}
if($LabContainer -like '*-missing'){
 if($env:P04_ACCEPTANCE_SCENARIO -eq 'unrelated-preflight-failure'){Write-Output '[INFO] BUILD FAILURE';exit 7}
 if($env:P04_ACCEPTANCE_SCENARIO -eq 'unexplained-exit-one'){exit 1}
 $dest=Join-Path $EvidenceDir 'fixture-execution';[void](New-Item -ItemType Directory -Force $dest)
 Copy-Item -LiteralPath (Join-Path $env:P04_ACCEPTANCE_FIXTURE 'manifest.json'),(Join-Path $env:P04_ACCEPTANCE_FIXTURE 'results.json'),(Join-Path $env:P04_ACCEPTANCE_FIXTURE 'native-commands.json') -Destination $dest
 exit 1
}
if($Mode -eq 'Integration' -and $env:P04_ACCEPTANCE_SCENARIO -eq 'case-failure'){exit 1}
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fakeRepo 'tools\p04-contract\run.ps1') -Encoding UTF8
$previousPath=$env:PATH; $previousFixture=$env:P04_ACCEPTANCE_FIXTURE; $previousScenario=$env:P04_ACCEPTANCE_SCENARIO
try {
    $env:PATH = $fakeBin + ';' + $previousPath; $env:P04_ACCEPTANCE_FIXTURE = $fixture
    foreach($scenario in @('control','unrelated-preflight-failure','unexplained-exit-one','unit-failure','case-failure')) {
        $env:P04_ACCEPTANCE_SCENARIO = $scenario
        $ev=Join-Path $TestRoot ('wrapper-'+$scenario)
        $log=Join-Path $TestRoot ($scenario+'.log')
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-acceptance.ps1') `
            -EvidenceDir $ev -RepoRoot $fakeRepo -WorkRoot (Join-Path $fakeRepo 'work') `
            -LabContainer 'p04-pg-p04test' -MissingLabContainer $container -RunTag $tag > $log 2>&1
        $code=$LASTEXITCODE
        $expected=if($scenario -eq 'control'){0}else{1}
        Check ('wrapper native exit: '+$scenario) ($code -eq $expected)
    }
} finally {
    $env:PATH=$previousPath; $env:P04_ACCEPTANCE_FIXTURE=$previousFixture; $env:P04_ACCEPTANCE_SCENARIO=$previousScenario
}
$script:Checks | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $TestRoot 'results.json') -Encoding UTF8
Write-Output ("Acceptance regression: {0} passed; evidence={1}" -f $script:Checks.Count,$TestRoot)
