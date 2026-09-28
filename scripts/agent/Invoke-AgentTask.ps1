param(
    [Parameter(Mandatory = $true)][string] $TaskFile,
    [Parameter(Mandatory = $true)][string] $RepositoryRoot,
    [ValidateRange(1, 180)][int] $TimeoutMinutes = 30
)

$ErrorActionPreference = 'Stop'

function Invoke-Git([string[]] $GitArgs) {
    $output = & git -C $repo @GitArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArgs[0]) failed: $output" }
    return $output
}

function Get-ChangedPaths {
    $tracked = @(& git -C $repo diff --name-only HEAD)
    $untracked = @(& git -C $repo ls-files --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect untracked paths.' }
    return @($tracked + $untracked | Where-Object { $_ } | Sort-Object -Unique)
}

function Test-AllowedPaths([string[]] $Paths) {
    foreach ($path in $Paths) {
        $normalized = $path.Replace('\', '/')
        $allowed = $false
        foreach ($prefix in $task.allowedPaths) {
            $scope = [string]$prefix
            if ($scope -match '(^/|\.\.|\\|:)') { throw "Invalid allowed path: $scope" }
            if ($normalized -eq $scope -or $normalized.StartsWith($scope.TrimEnd('/') + '/')) {
                $allowed = $true
                break
            }
        }
        if (-not $allowed) { throw "Agent changed a path outside the approved scope: $normalized" }
    }
}

function Get-TaskWritableDirs {
    $dirs = [System.Collections.Generic.List[string]]::new()
    foreach ($allowedPath in $task.allowedPaths) {
        $scope = [string]$allowedPath
        if ($scope -match '(^/|\.\.|\\|:)' -or $scope -match '(^|/)\.git(/|$)') {
            throw "Invalid allowed path: $scope"
        }
        $candidate = [IO.Path]::GetFullPath((Join-Path $repo $scope))
        while (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
            $parent = Split-Path -Parent $candidate
            if ($parent -eq $candidate -or [string]::IsNullOrWhiteSpace($parent)) {
                throw "No writable parent for allowed path: $scope"
            }
            $candidate = $parent
        }
        $resolved = (Resolve-Path -LiteralPath $candidate).Path
        if ($resolved -ne $repo -and -not $resolved.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Allowed path resolves outside repository: $scope"
        }
        $walk = $resolved
        while ($walk -ne $repo) {
            if (((Get-Item -LiteralPath $walk).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Allowed path traverses a reparse point: $scope"
            }
            $walk = Split-Path -Parent $walk
        }
        if ($resolved -ne $repo -and -not $dirs.Contains($resolved)) { $dirs.Add($resolved) }
    }
    return @($dirs)
}

$repo = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$taskPath = (Resolve-Path -LiteralPath $TaskFile).Path
if (-not (Test-Json -LiteralPath $taskPath -SchemaFile (Join-Path $repo '.agent/task.schema.json'))) {
    throw 'Task file does not match .agent/task.schema.json.'
}
$task = Get-Content -LiteralPath $taskPath -Raw | ConvertFrom-Json
if ([string]$task.taskId -cnotmatch '^[a-z0-9][a-z0-9-]{1,62}$') { throw 'Invalid taskId.' }
if ($task.approval.status -cne 'approved' -or [string]::IsNullOrWhiteSpace($task.approval.approvedBy) -or [string]::IsNullOrWhiteSpace($task.approval.approvedAt)) {
    throw 'Task does not have a recorded approval.'
}
if (@($task.allowedPaths).Count -eq 0 -or @($task.validation).Count -eq 0) { throw 'Task has no allowedPaths or validation.' }
$taskHash = (Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash.ToLowerInvariant()
$specPath = if ([IO.Path]::IsPathRooted($task.specPath)) { [IO.Path]::GetFullPath($task.specPath) } else { [IO.Path]::GetFullPath((Join-Path $repo $task.specPath)) }
if (-not (Test-Path -LiteralPath $specPath -PathType Leaf)) { throw 'Task Spec does not exist.' }
$specHash = (Get-FileHash -LiteralPath $specPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($specHash -ne [string]$task.specSha256) { throw 'Task Spec changed since approval.' }
$head = [string](Invoke-Git -GitArgs @('rev-parse', 'HEAD'))
if ($head -ne [string]$task.baseSha) { throw "Checkout HEAD $head does not match approved baseSha." }
if (@(Invoke-Git -GitArgs @('status', '--porcelain')).Count -gt 0) { throw 'Checkout must be clean before the task starts.' }
$writableDirs = @(Get-TaskWritableDirs)
$branch = 'agent/' + $task.taskId
$branchExists = & git -C $repo show-ref --verify --quiet ('refs/heads/' + $branch)
if ($LASTEXITCODE -eq 0) { throw "Branch $branch exists; use a fresh checkout or explicit recovery." }
Invoke-Git -GitArgs @('switch', '-c', $branch) | Out-Null

$runDir = Join-Path $repo ('.agent/runs/' + $task.taskId)
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$promptFile = Join-Path $runDir 'prompt.md'
$resultFile = Join-Path $runDir 'result.json'
$finalFile = Join-Path $runDir 'agent-final.json'
$writableDirsFile = Join-Path $runDir 'writable-dirs.json'
$schemaFile = Join-Path $repo '.agent/result.schema.json'
[IO.File]::WriteAllText($writableDirsFile, (ConvertTo-Json -InputObject $writableDirs -Compress), [Text.UTF8Encoding]::new($false))
$promptTemplate = [IO.File]::ReadAllText((Join-Path $repo '.agent/prompts/implement.md'))
$prompt = $promptTemplate + "`n`nTask:`n" + ($task | ConvertTo-Json -Depth 12) + "`n`nApproved Spec:`n" + [IO.File]::ReadAllText($specPath)
[IO.File]::WriteAllText($promptFile, $prompt, [Text.UTF8Encoding]::new($false))

$startInfo = [Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = (Get-Command pwsh).Source
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
foreach ($arg in @('-NoProfile', '-File', (Join-Path $repo 'scripts/agent/Run-Codex.ps1'), '-RepositoryRoot', $repo, '-PromptFile', $promptFile, '-SchemaFile', $schemaFile, '-FinalMessageFile', $finalFile, '-WritableDirsFile', $writableDirsFile)) {
    $startInfo.ArgumentList.Add([string]$arg)
}
$process = [Diagnostics.Process]::Start($startInfo)
$stdout = $process.StandardOutput.ReadToEndAsync()
$stderr = $process.StandardError.ReadToEndAsync()
$finished = $process.WaitForExit($TimeoutMinutes * 60000)
if (-not $finished) { $process.Kill($true); $process.WaitForExit() }
[IO.File]::WriteAllText((Join-Path $runDir 'codex.jsonl'), $stdout.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $runDir 'codex.stderr.log'), $stderr.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
$changed = @(Get-ChangedPaths)
$status = 'failed'
$validation = [System.Collections.Generic.List[string]]::new()
$reason = ''
try {
    if (-not $finished) { throw 'Codex timed out.' }
    if ($process.ExitCode -ne 0) { throw "Codex exited with code $($process.ExitCode)." }
    if ($changed.Count -eq 0) { throw 'Codex made no changes.' }
    Test-AllowedPaths $changed
    if ((Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskHash) { throw 'Task approval file changed during execution.' }
    if ((Get-FileHash -LiteralPath $specPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $specHash) { throw 'Approved Spec changed during execution.' }
    foreach ($check in $task.validation) {
        switch ([string]$check) {
            'platform' {
                & mvn -B -ntp -Pdev -f (Join-Path $repo 'services/platform/pom.xml') clean verify
                if ($LASTEXITCODE -ne 0) { throw 'Platform verification failed.' }
            }
            'ai' {
                & mvn -B -ntp -Pci -f (Join-Path $repo 'services/ai/pom.xml') clean verify
                if ($LASTEXITCODE -ne 0) { throw 'AI verification failed.' }
            }
            'frontend' {
                Push-Location (Join-Path $repo 'services/ai/frontend')
                try {
                    & npm ci
                    if ($LASTEXITCODE -ne 0) { throw 'Frontend npm ci failed.' }
                    & npm run lint
                    if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed.' }
                    & npm run build
                    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
                } finally { Pop-Location }
            }
            default { throw "Unsupported validation selector: $check" }
        }
        $validation.Add([string]$check)
    }
    Test-AllowedPaths @(Get-ChangedPaths)
    $status = 'passed'
} catch {
    $reason = $_.Exception.Message
}
$result = [ordered]@{
    taskId = $task.taskId
    branch = $branch
    baseSha = $head
    taskSha256 = $taskHash
    specSha256 = $specHash
    status = $status
    reason = $reason
    changedPaths = @(Get-ChangedPaths)
    validationPassed = @($validation)
    codexExitCode = if ($finished) { $process.ExitCode } else { $null }
    finishedAt = (Get-Date).ToUniversalTime().ToString('o')
}
[IO.File]::WriteAllText($resultFile, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output "Task $($task.taskId): $status; report=$resultFile"
if ($status -ne 'passed') { throw $reason }
