function Get-TaskPatchSha256 {
    param(
        [Parameter(Mandatory = $true)][string] $RepositoryRoot,
        [Parameter(Mandatory = $true)][string] $BaseSha,
        [Parameter(Mandatory = $true)][string] $OutputFile,
        [switch] $Cached
    )

    $diffArgs = @('-C', $RepositoryRoot, 'diff', '--binary', '--full-index', '--no-renames', '--no-ext-diff', '--no-textconv', '--no-color')
    if ($Cached) { $diffArgs += '--cached' }
    $diffArgs += $BaseSha
    if (-not $Cached) { $diffArgs += 'HEAD' }
    $diffArgs += ('--output=' + $OutputFile)
    & git @diffArgs
    if ($LASTEXITCODE -ne 0) { throw 'Could not generate the task patch.' }
    return (Get-FileHash -LiteralPath $OutputFile -Algorithm SHA256).Hash.ToLowerInvariant()
}

Export-ModuleMember -Function Get-TaskPatchSha256
