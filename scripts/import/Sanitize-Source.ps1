param(
    [Parameter(Mandatory = $true)]
    [string] $RepositoryRoot
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$services = Join-Path $repo 'services'
if (-not (Test-Path -LiteralPath (Join-Path $services 'platform')) -or
    -not (Test-Path -LiteralPath (Join-Path $services 'ai'))) {
    throw 'Expected services/platform and services/ai under RepositoryRoot.'
}

$sensitiveKey = '(?i)(?:password|passwd|secret|api[-_]?key|access[-_]?key|access[-_]?key[-_]?id|access[-_]?key[-_]?secret|token)$'
$mapping = [System.Collections.Generic.List[string]]::new()
$files = Get-ChildItem -LiteralPath $services -Recurse -File |
    Where-Object {
        $_.Name.Trim() -match '^(?:application.*\.(?:ya?ml|properties)|initializer\.properties|regression\.properties|.*\.compose\.ya?ml|docker-compose.*\.ya?ml)$'
    }

foreach ($file in $files) {
    $relative = [System.IO.Path]::GetRelativePath($repo, $file.FullName).Replace('\', '/')
    $lines = [System.IO.File]::ReadAllLines($file.FullName)
    $changed = $false
    for ($index = 0; $index -lt $lines.Length; $index++) {
        $line = $lines[$index]
        if ($line -match '^\s*(?:#|//)') { continue }
        if ($line -notmatch '^(\s*(?:-\s+)?)([A-Za-z0-9_.-]+)(\s*[:=]\s*)(.*)$') { continue }
        $indent = $Matches[1]
        $key = $Matches[2]
        $separator = $Matches[3]
        $value = $Matches[4].Trim()
        if ($key -notmatch $sensitiveKey) { continue }
        if ($value -eq '' -or $value -eq 'null' -or $value -eq 'true' -or $value -eq 'false') { continue }
        $name = 'PROJECT_' + (($relative + '_' + $key + '_' + ($index + 1)).ToUpperInvariant() -replace '[^A-Z0-9]', '_')
        # A source value must never be copied into the output or the audit report.
        $lines[$index] = $indent + $key + $separator + '${' + $name + '}'
        $mapping.Add('| `' + $relative + ':' + ($index + 1) + '` | `' + $name + '` |')
        $changed = $true
    }
    if ($changed) {
        [System.IO.File]::WriteAllLines($file.FullName, $lines, [System.Text.UTF8Encoding]::new($false))
    }
}

$mainFile = Join-Path $services 'platform/ruoyi-modules/ruoyi-chat/src/test/java/org/ruoyi/service/rerank/impl/AliBaiLianRerankTestMain.java'
$testFile = Join-Path $services 'platform/ruoyi-modules/ruoyi-chat/src/test/java/org/ruoyi/service/embed/impl/QianwenAdminApiKeyTest.java'
$keyLiteral = '"sk-[A-Za-z0-9_-]{16,}"'
$mainText = [System.IO.File]::ReadAllText($mainFile)
$mainText = [regex]::Replace($mainText, $keyLiteral, 'System.getenv("ALIYUN_DASHSCOPE_API_KEY")')
[System.IO.File]::WriteAllText($mainFile, $mainText, [System.Text.UTF8Encoding]::new($false))
$testText = [System.IO.File]::ReadAllText($testFile)
$testText = [regex]::Replace($testText, $keyLiteral, '"test-api-key"')
[System.IO.File]::WriteAllText($testFile, $testText, [System.Text.UTF8Encoding]::new($false))

$frontendEnv = Join-Path $services 'ai/frontend/.env'
if (Test-Path -LiteralPath $frontendEnv -PathType Leaf) {
    $sampleLines = [System.IO.File]::ReadAllLines($frontendEnv) | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=') { $Matches[1] + '=' } else { $_ }
    }
    [System.IO.File]::WriteAllLines((Join-Path $services 'ai/frontend/.env.example'), $sampleLines, [System.Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $frontendEnv
}

$auditPath = Join-Path $repo 'docs/configuration.md'
$auditDir = Split-Path -Parent $auditPath
New-Item -ItemType Directory -Path $auditDir -Force | Out-Null
$content = @(
    '# 运行配置',
    '',
    '公开仓库只保留环境变量引用，不保存真实密码或 API Key。部署时通过运行环境提供相应变量。',
    '',
    '下表由 `scripts/import/Sanitize-Source.ps1` 根据固定上游快照生成；修改上游版本后重新核对。',
    '',
    '| 来源位置 | 环境变量 |',
    '|---|---|'
) + $mapping
[System.IO.File]::WriteAllLines($auditPath, $content, [System.Text.UTF8Encoding]::new($false))
Write-Output "Sanitized $($mapping.Count) configuration entries; values were not printed."
