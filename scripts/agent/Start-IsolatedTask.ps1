param(
    [Parameter(Mandatory = $true)][string] $TaskFile,
    [Parameter(Mandatory = $true)][string] $RepositoryRoot,
    [ValidateRange(1, 180)][int] $TimeoutMinutes = 30
)

$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$taskPath = (Resolve-Path -LiteralPath $TaskFile).Path
$taskHash = (Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash.ToLowerInvariant()
$gitRoot = (& git -C $source rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($gitRoot) -cne [IO.Path]::GetFullPath($source)) {
    throw 'RepositoryRoot must be the Git checkout root.'
}
if (-not (Test-Json -LiteralPath $taskPath -SchemaFile (Join-Path $source '.agent/task.schema.json'))) {
    throw 'Task file does not match .agent/task.schema.json.'
}
$task = Get-Content -LiteralPath $taskPath -Raw | ConvertFrom-Json
if ([string]$task.taskId -cnotmatch '^[a-z0-9][a-z0-9-]{1,62}$') { throw 'Invalid taskId.' }
if ($task.approval.status -cne 'approved' -or [string]::IsNullOrWhiteSpace($task.approval.approvedBy) -or [string]::IsNullOrWhiteSpace($task.approval.approvedAt)) {
    throw 'Task does not have a recorded approval.'
}
$branch = (& git -C $source branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or $branch -cne 'main') { throw 'Start isolated tasks from the clean main checkout.' }
$head = (& git -C $source rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -cne $task.baseSha) { throw 'Main HEAD does not match the approved baseSha.' }
$status = @(& git -C $source status --porcelain)
if ($LASTEXITCODE -ne 0 -or $status.Count -gt 0) { throw 'Main checkout must be clean before creating a task worktree.' }
& git -C $source show-ref --verify --quiet ('refs/heads/agent/' + $task.taskId)
if ($LASTEXITCODE -eq 0) { throw 'Task branch already exists; inspect and recover it instead of creating another.' }

$specSource = if ([IO.Path]::IsPathRooted($task.specPath)) {
    [IO.Path]::GetFullPath($task.specPath)
} else {
    [IO.Path]::GetFullPath((Join-Path $source $task.specPath))
}
if (-not (Test-Path -LiteralPath $specSource -PathType Leaf)) { throw 'Task Spec does not exist.' }
if ((Get-FileHash -LiteralPath $specSource -Algorithm SHA256).Hash.ToLowerInvariant() -cne $task.specSha256) {
    throw 'Task Spec changed since approval.'
}
if (-not [IO.Path]::IsPathRooted($task.specPath) -and
    -not $specSource.StartsWith($source + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Relative task Spec resolves outside the source repository; use an absolute Spec path.'
}

$parent = Join-Path (Split-Path -Parent $source) ((Split-Path -Leaf $source) + '.agent-worktrees')
if (Test-Path -LiteralPath $parent) {
    if (-not (Test-Path -LiteralPath $parent -PathType Container) -or
        (((Get-Item -LiteralPath $parent).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw 'Task worktree parent is not a normal directory.'
    }
} else {
    New-Item -ItemType Directory -Path $parent -ErrorAction Stop | Out-Null
}
$worktree = Join-Path $parent $task.taskId
if (Test-Path -LiteralPath $worktree) { throw "Task worktree already exists: $worktree" }
& git -C $source worktree add --detach $worktree $task.baseSha
if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated task worktree.' }

$taskCopy = Join-Path $worktree ('.agent/tasks/' + $task.taskId + '.json')
New-Item -ItemType Directory -Path (Split-Path -Parent $taskCopy) -Force | Out-Null
Copy-Item -LiteralPath $taskPath -Destination $taskCopy -ErrorAction Stop
if ((Get-FileHash -LiteralPath $taskCopy -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHash) {
    throw 'Task approval file changed while creating the isolated worktree.'
}
if (-not [IO.Path]::IsPathRooted($task.specPath)) {
    $specCopy = [IO.Path]::GetFullPath((Join-Path $worktree $task.specPath))
    New-Item -ItemType Directory -Path (Split-Path -Parent $specCopy) -Force | Out-Null
    Copy-Item -LiteralPath $specSource -Destination $specCopy -ErrorAction Stop
}
Write-Output "Task worktree: $worktree"
Push-Location -LiteralPath $worktree
try {
    & pwsh -NoProfile -File (Join-Path $worktree 'scripts/agent/Invoke-AgentTask.ps1') `
        -TaskFile $taskCopy -RepositoryRoot $worktree -TimeoutMinutes $TimeoutMinutes
    $taskExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $taskExitCode
