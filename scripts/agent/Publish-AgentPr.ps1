param(
    [Parameter(Mandatory = $true)][string] $TaskFile,
    [Parameter(Mandatory = $true)][string] $RepositoryRoot,
    [Parameter(Mandatory = $true)][string] $ResultFile,
    [switch] $Draft = $true
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath $RepositoryRoot).Path
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
if ($result.status -cne 'passed' -or $result.taskId -cne $task.taskId -or $result.branch -cne $branch) { throw 'Task result is not a passed result for this branch.' }
if ((Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $result.taskSha256) { throw 'Task approval changed after validation.' }
$specPath = if ([IO.Path]::IsPathRooted($task.specPath)) { [IO.Path]::GetFullPath($task.specPath) } else { [IO.Path]::GetFullPath((Join-Path $repo $task.specPath)) }
if ((Get-FileHash -LiteralPath $specPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $result.specSha256) { throw 'Spec changed after validation.' }
$actualBranch = (& git -C $repo branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or $actualBranch -cne $branch) { throw "Expected branch $branch; found $actualBranch" }
$head = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -cne $result.baseSha) { throw 'HEAD changed after validation.' }
$remote = (& git -C $repo remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0 -or $remote -notmatch '^https://github\.com/Wonderchn/Ruoyi-Ai-AgentScope(?:\.git)?$') { throw 'origin is not the approved repository.' }
$paths = @(& git -C $repo diff --name-only HEAD) + @(& git -C $repo ls-files --others --exclude-standard)
if ($LASTEXITCODE -ne 0 -or $paths.Count -eq 0) { throw 'No changes to publish.' }
foreach ($path in $paths) {
    $normal = ([string]$path).Replace('\', '/')
    if (-not (@($task.allowedPaths) | Where-Object { $normal -eq $_ -or $normal.StartsWith(([string]$_).TrimEnd('/') + '/') })) {
        throw "Path outside approved scope: $normal"
    }
}
$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) {
    $localGh = Join-Path $repo '.local/tools/bin/gh.exe'
    if (Test-Path -LiteralPath $localGh -PathType Leaf) { $gh = $localGh }
}
if (-not $gh) { throw 'GitHub CLI gh is required; install it and run gh auth login.' }

& git -C $repo add -A
if ($LASTEXITCODE -ne 0) { throw 'git add failed.' }
$staged = @(& git -C $repo diff --cached --name-only)
foreach ($path in $staged) {
    $normal = ([string]$path).Replace('\', '/')
    if (-not (@($task.allowedPaths) | Where-Object { $normal -eq $_ -or $normal.StartsWith(([string]$_).TrimEnd('/') + '/') })) {
        throw "Staged path outside approved scope: $normal"
    }
}
$secretFiles = @(& git -C $repo grep -IlE --cached '(sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----)' -- .)
if ($LASTEXITCODE -gt 1) { throw 'Credential pattern check failed to run.' }
if ($secretFiles.Count -gt 0) { throw ('Credential-shaped text detected in: ' + ($secretFiles -join ', ')) }
& git -C $repo commit -m ("feat: " + $task.title)
if ($LASTEXITCODE -ne 0) { throw 'Commit failed.' }
& git -C $repo push --set-upstream origin $branch
if ($LASTEXITCODE -ne 0) { throw 'Push failed; branch and commit remain local for retry.' }
$existing = [string](& $gh pr list --repo Wonderchn/Ruoyi-Ai-AgentScope --head $branch --state open --json url --jq '.[0].url')
if ($LASTEXITCODE -ne 0) { throw 'Could not check for an existing PR.' }
$existing = $existing.Trim()
if ($existing) { Write-Output $existing; exit 0 }
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
if ($LASTEXITCODE -ne 0) { throw 'PR creation failed; pushed branch remains available for retry.' }
