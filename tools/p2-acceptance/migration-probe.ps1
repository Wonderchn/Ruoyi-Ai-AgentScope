[CmdletBinding()]
param([string]$RepoRoot='D:\AI-project\Ruoyi-Ai-AgentScope',
 [string]$EvidenceDir='D:\AI-project\mydocs\p2\evidence\migration-native-20261004',
 [string]$SecretFile='D:\AI-project\.scratch\p2-acceptance\p3cont1004r\secrets.json',
 [string]$RemoteHost='root@192.168.139.103',[switch]$OwnedResume)
$ErrorActionPreference='Stop'
$tag='p3cont1004r';$container='p2core-pg-'+$tag;$database='ragent_migration_p2p3_1004'
$work='D:\AI-project\.scratch\migration-native-20261004'
[void](New-Item -ItemType Directory -Force $work,$EvidenceDir)
$taskSecret=Get-Content -LiteralPath $SecretFile -Raw|ConvertFrom-Json
if($OwnedResume){$taskPass=[IO.File]::ReadAllText((Join-Path $work 'controlled-password.txt'))}
else{$taskPass=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N');[IO.File]::WriteAllText((Join-Path $work 'controlled-password.txt'),$taskPass)}
function RemoteMigration([string]$Command,[string]$Name) {
 $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Command))
 $output=& ssh -o BatchMode=yes $RemoteHost "printf %s $encoded | base64 -d | bash" 2>&1
 $code=$LASTEXITCODE
 $safe=($output|Out-String).Replace($taskPass,'<redacted>').Replace([string]$taskSecret.pg,'<redacted>')
 [IO.File]::WriteAllText((Join-Path $EvidenceDir ($Name+'.log')),$safe)
 if($code -ne 0){throw "owned migration operation failed: $Name"}
}
if($OwnedResume) {
 RemoteMigration "set -eu`ntest `"`$(docker inspect -f '{{index .Config.Labels `"p2core.owner`"}}' $container)`" = $tag`ntest `"`$(docker exec $container psql -U postgres -d postgres -X -q -tA -c `"select shobj_description(oid,'pg_database') from pg_database where datname='$database';`")`" = 'owned:$tag:migration-probe'" 'owned-resume-database'
} else {
RemoteMigration "set -eu`ntest `"`$(docker inspect -f '{{index .Config.Labels `"p2core.owner`"}}' $container)`" = $tag`ntest `"`$(docker exec $container psql -U postgres -d postgres -X -q -tA -c `"select count(*) from pg_database where datname='$database';`" )`" = 0`ndocker exec $container createdb -U postgres $database`ndocker exec $container psql -U postgres -d $database -X -q -v ON_ERROR_STOP=1 -c `"COMMENT ON DATABASE $database IS 'owned:$tag:migration-probe';`"" 'owned-new-database'
$bootstrap=@"
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA ai; CREATE SCHEMA platform; CREATE SCHEMA extensions;
CREATE EXTENSION vector SCHEMA extensions;
CREATE ROLE p2p3_ai_mig LOGIN PASSWORD '$taskPass';
CREATE ROLE p2p3_platform_mig LOGIN PASSWORD '$taskPass';
CREATE ROLE p2p3_ai_app LOGIN PASSWORD '$taskPass';
CREATE ROLE p2p3_platform_app LOGIN PASSWORD '$taskPass';
GRANT USAGE,CREATE ON SCHEMA ai TO p2p3_ai_mig;
GRANT USAGE,CREATE ON SCHEMA platform TO p2p3_platform_mig;
GRANT USAGE ON SCHEMA extensions TO p2p3_ai_mig,p2p3_platform_mig,p2p3_ai_app;
GRANT USAGE ON SCHEMA ai TO p2p3_ai_app;
GRANT USAGE ON SCHEMA platform TO p2p3_platform_app;
ALTER DEFAULT PRIVILEGES FOR ROLE p2p3_ai_mig IN SCHEMA ai GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO p2p3_ai_app;
ALTER DEFAULT PRIVILEGES FOR ROLE p2p3_platform_mig IN SCHEMA platform GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO p2p3_platform_app;
"@
$sqlEncoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($bootstrap))
RemoteMigration "set -eu`nprintf %s '$sqlEncoded' | base64 -d | docker exec -i $container psql -U postgres -d $database -X -q -v ON_ERROR_STOP=1 -f -" 'controlled-role-bootstrap'
}
$port=38494
if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw 'migration tunnel port occupied'}
$tunnel=Start-Process ssh.exe -ArgumentList @('-N','-o','BatchMode=yes','-o','ExitOnForwardFailure=yes','-L',"127.0.0.1:$port`:127.0.0.1:16492",$RemoteHost) -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $work 'ssh-error.log')
try {
 $env:P2_MIGRATION_URL="jdbc:postgresql://127.0.0.1:$port/$database"
 $env:P2_MIGRATION_PASSWORD=$taskPass;$env:P2_MIGRATION_REPO=$RepoRoot;$env:P2_MIGRATION_WORK=$work
 $m2='D:\develop\maven_repository'
 $jars=@("$m2\org\flywaydb\flyway-core\9.16.3\flyway-core-9.16.3.jar","$m2\org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar","$m2\org\slf4j\slf4j-api\1.7.36\slf4j-api-1.7.36.jar")
 foreach($artifact in @('jackson-core','jackson-databind','jackson-annotations')){$jars+="$m2\com\fasterxml\jackson\core\$artifact\2.15.3\$artifact-2.15.3.jar"}
 $jars+="$m2\com\fasterxml\jackson\dataformat\jackson-dataformat-toml\2.15.3\jackson-dataformat-toml-2.15.3.jar"
 $jars|ForEach-Object{if(-not(Test-Path -LiteralPath $_)){throw "missing offline dependency: $_"}}
 $jars|ForEach-Object{Get-FileHash -LiteralPath $_}|Select-Object Path,Hash|ConvertTo-Json |Set-Content -LiteralPath (Join-Path $EvidenceDir 'runtime-hashes.json') -Encoding utf8
 $cp=$jars -join ';'
 & 'D:\develop\java\jdk-17.0.18.8-hotspot\bin\javac.exe' -encoding UTF-8 -cp $cp -d $work (Join-Path $RepoRoot 'tools/p2-acceptance/MigrationProbe.java') *> (Join-Path $EvidenceDir 'javac.log')
 if($LASTEXITCODE -ne 0){throw 'native migration probe compile failed'}
 $ErrorActionPreference='Continue'
 & 'D:\develop\java\jdk-17.0.18.8-hotspot\bin\java.exe' -cp ($work+';'+$cp) MigrationProbe *> (Join-Path $EvidenceDir 'native-flyway.log')
 $code=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 Get-Content -LiteralPath (Join-Path $EvidenceDir 'native-flyway.log') -Tail 22
 if($code -ne 0){throw 'native migration probe failed; preserve database and evidence'}
} finally {
 $env:P2_MIGRATION_PASSWORD=$null
 $owned=Get-CimInstance Win32_Process -Filter "ProcessId=$($tunnel.Id)"
 if($owned -and $owned.Name -eq 'ssh.exe' -and $owned.CommandLine -like '*38494*16492*'){Stop-Process -Id $tunnel.Id}
}
