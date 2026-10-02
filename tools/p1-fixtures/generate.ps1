[CmdletBinding()]
param(
    [ValidateSet('Validate','Generate')][string]$Mode='Validate',
    [Parameter(Mandatory=$true)][string]$OutputDir
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(-not [IO.Path]::IsPathRooted($OutputDir)){throw 'OutputDir must be absolute'}
$output=[IO.Path]::GetFullPath($OutputDir).TrimEnd('\','/')
if($output -eq $repo -or $output.StartsWith($repo+'\',[StringComparison]::OrdinalIgnoreCase)){
    throw 'OutputDir must be outside the checkout'
}
$specPath=Join-Path $PSScriptRoot 'p1-fixture-spec.json'
$spec=Get-Content -LiteralPath $specPath -Raw -Encoding UTF8 | ConvertFrom-Json
function Require([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message}}
Require ($spec.manifestVersion -eq 'P1-FIXTURE-v1' -and $spec.specVersion -eq 'P1-FULL-v1') 'Unknown fixture contract'
Require ($spec.baseline -eq 'c2fdc73c67ca9156098ce4a1b1f2853486fc5aa1') 'Fixture baseline drift'
$actual=[ordered]@{tenants=@($spec.tenants).Count;departments=@($spec.tenants | ForEach-Object {$_.departments}).Count;
    platformUsers=@($spec.tenants | ForEach-Object {$_.users}).Count;aiResources=@($spec.resources).Count;
    conversations=@($spec.conversations).Count;memories=@($spec.memories).Count;stateKeys=@($spec.stateKeys).Count;
    runs=@($spec.runs).Count;objects=@($spec.objects).Count;vectors=@($spec.vectors).Count;legacyTokens=@($spec.legacyTokens).Count}
foreach($name in $actual.Keys){Require ($actual[$name] -gt 0 -and $actual[$name] -eq $spec.counts.$name) ('Fixture count mismatch: '+$name)}
Require (@($spec.tenants.tenantId | Select-Object -Unique).Count -eq 2) 'Exactly two distinct tenants required'
foreach($tenant in $spec.tenants){foreach($member in $tenant.users){
    Require ($member.membershipId -eq ('platform:'+ $tenant.tenantId+':'+$member.userId)) 'Noncanonical member'
}}
$pair=@($spec.sameUsernameDifferentIdentity)
Require ($pair.Count -eq 2 -and $pair[0].username -eq $pair[1].username -and $pair[0].tenantId -ne $pair[1].tenantId `
    -and $pair[0].userId -ne $pair[1].userId -and $pair[0].membershipId -ne $pair[1].membershipId) 'Same-name identity control missing'
Require (@($spec.dataScopes.scopeCode | Sort-Object -Unique) -join ',' -eq '1,2,3,4,5,6') 'Data scope matrix incomplete'
Require (@($spec.unknownOwnership).Count -gt 0) 'Unknown ownership negative fixture missing'
foreach($resource in $spec.resources){
    Require ($resource.tenantId -in @($spec.tenants.tenantId) -and $resource.ownerMemberId.StartsWith('platform:'+$resource.tenantId+':')) 'Resource ownership invalid'
}
$inputs=@($spec.hashes.inputs)
Require ($spec.hashes.algorithm -eq 'SHA256' -and $inputs.Count -eq 3) 'Input hash contract invalid'
$hashes=@(foreach($relative in $inputs){
    $path=[IO.Path]::GetFullPath((Join-Path $repo $relative))
    Require ($path.StartsWith($repo+'\',[StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $path -PathType Leaf)) 'Missing or invalid input path'
    @{path=$relative;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower()}
})
[void](New-Item -ItemType Directory -Path $output -Force)
if($Mode -eq 'Generate'){
    foreach($relative in $inputs){
        $target=Join-Path $output $relative
        [void](New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force)
        Copy-Item -LiteralPath (Join-Path $repo $relative) -Destination $target
    }
}
$manifest=[ordered]@{mode=$Mode;verifiedUtc=[DateTime]::UtcNow.ToString('o');syntheticOnly=$true;
    databaseConnected=$false;runtimeIsolationPass=$false;status='PASS';counts=$actual;inputs=$hashes}
[IO.File]::WriteAllText((Join-Path $output 'manifest.json'),($manifest | ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
Write-Output ('PASS synthetic fixture '+$Mode+'; no database connection; evidence='+$output)
