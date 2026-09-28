param(
    [Parameter(Mandatory = $true)][string] $RepositoryRoot,
    [Parameter(Mandatory = $true)][string] $PromptFile,
    [Parameter(Mandatory = $true)][string] $SchemaFile,
    [Parameter(Mandatory = $true)][string] $FinalMessageFile
)

$ErrorActionPreference = 'Stop'
$promptText = [System.IO.File]::ReadAllText($PromptFile)
$promptText | & codex exec --sandbox workspace-write --json -C $RepositoryRoot --output-schema $SchemaFile --output-last-message $FinalMessageFile -
exit $LASTEXITCODE
