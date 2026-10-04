[CmdletBinding()]
param([string]$RepoRoot='D:\AI-project\Ruoyi-Ai-AgentScope',
 [string]$ProductJar='D:\AI-project\.scratch\p2-final-isolated\services\ai\bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar',
 [string]$EvidenceDir='D:\AI-project\mydocs\p2\evidence\usage-native-20261004',
 [string]$ControlledPasswordFile='D:\AI-project\.scratch\migration-native-20261004\controlled-password.txt',
 [string]$RemoteHost='root@192.168.139.103')
$ErrorActionPreference='Stop'
$tag='p3cont1004r';$container='p2core-pg-'+$tag;$database='ragent_migration_p2p3_1004'
$work=Join-Path 'D:\AI-project\.scratch' ('usage-native-'+[guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Force $work,$EvidenceDir)
$controlledPassword=[IO.File]::ReadAllText($ControlledPasswordFile)
$command="set -eu`ntest `"`$(docker inspect -f '{{index .Config.Labels `"p2core.owner`"}}' $container)`" = $tag`ntest `"`$(docker exec $container psql -U postgres -d postgres -X -q -tA -c `"select shobj_description(oid,'pg_database') from pg_database where datname='$database';`")`" = 'owned:$tag:migration-probe'`necho 'OWNED_USAGE_DATABASE=verified'"
$encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($command))
& ssh -o BatchMode=yes $RemoteHost "printf %s $encoded | base64 -d | bash" *> (Join-Path $EvidenceDir 'owned-database.log')
if($LASTEXITCODE -ne 0){throw 'owned database witness failed'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($ProductJar)
try {
 foreach($entry in $zip.Entries){
  if($entry.FullName -notmatch '^BOOT-INF/(lib/[^/]+\.jar|classes/.+\.class)$'){continue}
  $target=Join-Path $work $entry.FullName
  [void](New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($target)))
  [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$false)
 }
} finally {$zip.Dispose()}
$jars=@(Get-ChildItem -LiteralPath (Join-Path $work 'BOOT-INF/lib') -Filter '*.jar'|ForEach-Object{$_.FullName})
$cp=(Join-Path $work 'BOOT-INF/classes')+';'+(Join-Path $work 'BOOT-INF/lib/*')
@($ProductJar,(Join-Path $RepoRoot 'tools/p2-acceptance/UsageProbe.java'))+$jars|ForEach-Object{Get-FileHash -LiteralPath $_}|Select-Object Path,Hash|ConvertTo-Json -Depth 3|Set-Content -LiteralPath (Join-Path $EvidenceDir 'runtime-hashes.json') -Encoding utf8
& 'D:\develop\java\jdk-17.0.18.8-hotspot\bin\javac.exe' -encoding UTF-8 -cp $cp -d $work (Join-Path $RepoRoot 'tools/p2-acceptance/UsageProbe.java') *> (Join-Path $EvidenceDir 'javac.log')
if($LASTEXITCODE -ne 0){throw 'native usage compile failed'}
$port=38496
if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw 'native usage tunnel collision'}
$tunnel=Start-Process ssh.exe -ArgumentList @('-N','-o','BatchMode=yes','-o','ExitOnForwardFailure=yes','-L',"127.0.0.1:$port`:127.0.0.1:16492",$RemoteHost) -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $work 'ssh-error.log')
try {
 $ready=$false
 for($i=0;$i -lt 40;$i++){
  $tunnel.Refresh();if($tunnel.HasExited){throw 'owned usage SSH exited before readiness'}
  if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){$ready=$true;break}
  Start-Sleep -Milliseconds 250
 }
 if(-not $ready){throw 'owned usage tunnel did not become ready'}
 $env:P2_USAGE_URL="jdbc:postgresql://127.0.0.1:$port/$database`?currentSchema=ai,extensions"
 $env:P2_USAGE_PASSWORD=$controlledPassword
 $ErrorActionPreference='Continue'
 & 'D:\develop\java\jdk-17.0.18.8-hotspot\bin\java.exe' -cp ($work+';'+$cp) UsageProbe *> (Join-Path $EvidenceDir 'native-usage.log')
 $code=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 [IO.File]::WriteAllText((Join-Path $EvidenceDir 'exit-code.txt'),[string]$code)
 Get-Content -LiteralPath (Join-Path $EvidenceDir 'native-usage.log') -Tail 18
 if($code -ne 0){throw 'native usage failed; preserve fixtures and all evidence'}
} finally {
 $env:P2_USAGE_PASSWORD=$null
 $owned=Get-CimInstance Win32_Process -Filter "ProcessId=$($tunnel.Id)"
 if($owned -and $owned.Name -eq 'ssh.exe' -and $owned.CommandLine -like '*38496*16492*'){
  $owned|Select-Object ProcessId,Name,CommandLine|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $EvidenceDir 'owned-tunnel-stop.json') -Encoding utf8
  Stop-Process -Id $tunnel.Id
 }
}
