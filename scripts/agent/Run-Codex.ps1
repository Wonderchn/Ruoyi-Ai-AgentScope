param(
    [Parameter(Mandatory = $true)][string] $RepositoryRoot,
    [Parameter(Mandatory = $true)][string] $PromptFile,
    [Parameter(Mandatory = $true)][string] $SchemaFile,
    [Parameter(Mandatory = $true)][string] $FinalMessageFile,
    [Parameter(Mandatory = $true)][string] $WritableDirsFile
)

$ErrorActionPreference = 'Stop'
$promptText = [System.IO.File]::ReadAllText($PromptFile)
$writableDirs = @([System.IO.File]::ReadAllText($WritableDirsFile) | ConvertFrom-Json)
$codexArgs = @('exec', '--sandbox', 'workspace-write', '--json', '-C', $RepositoryRoot)
foreach ($dir in $writableDirs) {
    $codexArgs += @('--add-dir', [string]$dir)
}
$codexArgs += @('--output-schema', $SchemaFile, '--output-last-message', $FinalMessageFile, '-')
$promptText | & codex @codexArgs
exit $LASTEXITCODE
