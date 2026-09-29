param(
    [Parameter(Mandatory = $true)][string] $TaskFile,
    [Parameter(Mandatory = $true)][string] $RepositoryRoot,
    [Parameter(Mandatory = $true)][string] $ResultFile,
    [switch] $Draft = $true
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath $RepositoryRoot).Path
Import-Module (Join-Path $repo 'scripts/agent/TaskPatch.psm1') -Force
$taskPath = (Resolve-Path -LiteralPath $TaskFile).Path
$resultPath = (Resolve-Path -LiteralPath $ResultFile).Path
if (-not (Test-Json -LiteralPath $taskPath -SchemaFile (Join-Path $repo '.agent/task.schema.json'))) {
    throw 'Task file does not match .agent/task.schema.json.'
}
if (-not (Test-Json -LiteralPath $resultPath -SchemaFile (Join-Path $repo '.agent/run-result.schema.json'))) {
    throw 'Result file does not match .agent/run-result.schema.json.'
}
$task = Get-Content -LiteralPath $taskPath -Raw | ConvertFrom-Json
$result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
$branch = 'agent/' + $task.taskId
if ($result.status -cne 'passed' -or $result.taskId -cne $task.taskId -or $result.branch -cne $branch) {
    throw 'Task result is not a passed result for this branch.'
}
if ([string]$result.patchSha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Passed result lacks a task patch digest.' }
if ((Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $result.taskSha256) {
    throw 'Task approval changed after validation.'
}
$specPath = if ([IO.Path]::IsPathRooted($task.specPath)) { [IO.Path]::GetFullPath($task.specPath) } else { [IO.Path]::GetFullPath((Join-Path $repo $task.specPath)) }
if ((Get-FileHash -LiteralPath $specPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $result.specSha256) {
    throw 'Spec changed after validation.'
}
if (@(Compare-Object -ReferenceObject @($task.validation) -DifferenceObject @($result.validationPassed) -CaseSensitive).Count -gt 0) {
    throw 'The task validation list does not match the passed checks.'
}
$actualBranch = (& git -C $repo branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or $actualBranch -cne $branch) { throw "Expected branch $branch; found $actualBranch" }
$head = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect HEAD.' }
$remote = (& git -C $repo remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0 -or $remote -notmatch '^https://github\.com/Wonderchn/Ruoyi-Ai-AgentScope(?:\.git)?$') {
    throw 'origin is not the approved repository.'
}

function Assert-ApprovedPaths([string[]] $Paths) {
    foreach ($path in $Paths) {
        $normal = ([string]$path).Replace('\', '/')
        $approved = $false
        foreach ($scopeValue in $task.allowedPaths) {
            $scope = [string]$scopeValue
            if ($scope -match '(^/|\.\.|\\|:)' -or $scope -match '(^|/)\.git(/|$)') { throw "Invalid allowed path: $scope" }
            if ($normal -ceq $scope -or $normal.StartsWith($scope.TrimEnd('/') + '/', [StringComparison]::Ordinal)) {
                $approved = $true
                break
            }
        }
        if (-not $approved) { throw "Path outside approved scope: $normal" }
    }
}

function Assert-ResultPaths([string[]] $Paths) {
    $expected = @($result.changedPaths | Sort-Object -Unique -CaseSensitive)
    $actual = @($Paths | Sort-Object -Unique -CaseSensitive)
    if ($expected.Count -eq 0 -or $actual.Count -eq 0 -or @(Compare-Object -ReferenceObject $expected -DifferenceObject $actual -CaseSensitive).Count -gt 0) {
        throw 'Changed paths do not match the validated task result.'
    }
    Assert-ApprovedPaths $actual
}

$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) {
    $localGh = Join-Path $repo '.local/tools/bin/gh.exe'
    if (Test-Path -LiteralPath $localGh -PathType Leaf) { $gh = $localGh }
}
if (-not $gh) { throw 'GitHub CLI gh is required; install it and run gh auth login.' }

$resultSha256 = (Get-FileHash -LiteralPath $resultPath -Algorithm SHA256).Hash.ToLowerInvariant()
$patchPath = Join-Path (Split-Path -Parent $resultPath) 'publish.patch'
if ($head -ceq $result.baseSha) {
    $tracked = @(& git -C $repo diff --name-only HEAD)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect tracked task changes.' }
    $untracked = @(& git -C $repo ls-files --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect untracked task changes.' }
    Assert-ResultPaths @($tracked + $untracked | Where-Object { $_ })
    & git -C $repo add -A
    if ($LASTEXITCODE -ne 0) { throw 'git add failed.' }
    $unstaged = @(& git -C $repo diff --name-only)
    if ($LASTEXITCODE -ne 0 -or $unstaged.Count -gt 0) { throw 'Task files changed while staging the patch.' }
    $staged = @(& git -C $repo diff --cached --name-only $head)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the staged task patch.' }
    Assert-ResultPaths $staged
    $patchSha256 = Get-TaskPatchSha256 -RepositoryRoot $repo -BaseSha $head -OutputFile $patchPath -Cached
    if ($patchSha256 -cne $result.patchSha256) { throw 'Task patch changed after validation.' }
    $secretFiles = @(& git -C $repo grep -IlE --cached '(sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----)' -- .)
    if ($LASTEXITCODE -gt 1) { throw 'Credential pattern check failed to run.' }
    if ($secretFiles.Count -gt 0) { throw ('Credential-shaped text detected in: ' + ($secretFiles -join ', ')) }
    $trailers = "Agent-Task-Id: $($task.taskId)`nAgent-Result-SHA256: $resultSha256`nAgent-Patch-SHA256: $patchSha256"
    & git -C $repo commit -m ("feat: " + $task.title) -m $trailers
    if ($LASTEXITCODE -ne 0) { throw 'Commit failed.' }
    $head = (& git -C $repo rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the new commit.' }
    $status = @(& git -C $repo status --porcelain)
    if ($LASTEXITCODE -ne 0 -or $status.Count -gt 0) { throw 'Task branch has new changes after its commit.' }
} else {
    $parent = (& git -C $repo rev-parse 'HEAD^').Trim()
    if ($LASTEXITCODE -ne 0 -or $parent -cne $result.baseSha) { throw 'HEAD is not the single validated task commit.' }
    $status = @(& git -C $repo status --porcelain)
    if ($LASTEXITCODE -ne 0 -or $status.Count -gt 0) { throw 'Task branch has new changes after its commit.' }
    $message = (& git -C $repo log -1 --format=%B) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the task commit.' }
    foreach ($trailer in @("Agent-Task-Id: $($task.taskId)", "Agent-Result-SHA256: $resultSha256", "Agent-Patch-SHA256: $($result.patchSha256)")) {
        if (-not (@($message -split '\r?\n') -ccontains $trailer)) { throw 'Task commit does not match the validated result.' }
    }
    $committedPaths = @(& git -C $repo diff --name-only $result.baseSha HEAD)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect committed task paths.' }
    Assert-ResultPaths $committedPaths
    $patchSha256 = Get-TaskPatchSha256 -RepositoryRoot $repo -BaseSha $result.baseSha -OutputFile $patchPath
    if ($patchSha256 -cne $result.patchSha256) { throw 'Committed task patch differs from validation.' }
}

$remoteLine = [string](& git -C $repo ls-remote --heads origin ('refs/heads/' + $branch))
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the remote task branch.' }
$remoteSha = if ($remoteLine) { ($remoteLine -split '\s+')[0] } else { '' }
if ($remoteSha -and $remoteSha -cne $head) { throw 'Remote task branch has a different commit; refusing to overwrite it.' }
if (-not $remoteSha) {
    & git -C $repo push --set-upstream origin $branch
    if ($LASTEXITCODE -ne 0) { throw 'Push failed; the validated task commit remains local for retry.' }
}

$prJson = & $gh pr list --repo Wonderchn/Ruoyi-Ai-AgentScope --head $branch --state all --json url,state,isDraft,headRefOid
if ($LASTEXITCODE -ne 0) { throw 'Could not check for an existing PR.' }
$existingPrs = @($prJson | ConvertFrom-Json)
if ($existingPrs.Count -gt 1) { throw 'Multiple PRs exist for the task branch; review them manually.' }
if ($existingPrs.Count -eq 1) {
    $existing = $existingPrs[0]
    if ($existing.state -cne 'OPEN') { throw 'The task already has a closed or merged PR; refusing to create another.' }
    if ($existing.headRefOid -cne $head) { throw 'The existing PR head differs from the validated commit.' }
    Write-Output $existing.url
    exit 0
}

$bodyPath = Join-Path (Split-Path -Parent $resultPath) 'pr-body.md'
$body = @(
    '## Change',
    "Task ID: $($task.taskId)",
    "Goal: $($task.goal)",
    '',
    '## Validation',
    ($result.validationPassed | ForEach-Object { "- $_" }),
    '',
    'This PR was produced by a locally approved headless Codex task. Review its code and CI checks before merging.'
) -join "`n"
[IO.File]::WriteAllText($bodyPath, $body, [Text.UTF8Encoding]::new($false))
$arguments = @('pr', 'create', '--repo', 'Wonderchn/Ruoyi-Ai-AgentScope', '--base', 'main', '--head', $branch, '--title', [string]$task.title, '--body-file', $bodyPath)
if ($Draft) { $arguments += '--draft' }
& $gh @arguments
if ($LASTEXITCODE -ne 0) { throw 'PR creation failed; rerun this script to reuse the validated task commit.' }
