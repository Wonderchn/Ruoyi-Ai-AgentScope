[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('P1.2b','P1.3a','P1.2c','P1.3b','P1.3c','P1.3d','P1.4')][string]$Unit,
    [Parameter(Mandatory=$true)][ValidateSet('Unit','Integration')][string]$Mode,
    [ValidateSet('pg')][string]$Backends='pg',
    [Parameter(Mandatory=$true)][string]$EvidenceDir,
    [string]$RepoRoot='D:\AI-project\Ruoyi-Ai-AgentScope',
    [string]$WorkRoot='D:\AI-project\.scratch\p1-isolation',
    [string]$RunTag=('p1c'+(Get-Date -Format 'yyyyMMddHHmmss')),
    [string]$RemoteHost='', [string]$SshKeyPath='',
    [int]$PlatformPort=18082, [int]$AiPort=19090,
    [switch]$SkipBuild,
    [switch]$BrowserVerification
)
# Reuse the audited owner-label, account, secret-redaction and process-cleanup helpers.
. (Join-Path $RepoRoot 'tools\p1-boundary\run.ps1') -Mode $Mode -EvidenceDir $EvidenceDir `
    -RepoRoot $RepoRoot -WorkRoot $WorkRoot -RunTag $RunTag -RemoteHost $RemoteHost `
    -SshKeyPath $SshKeyPath -PlatformPort $PlatformPort -AiPort $AiPort -LibraryOnly
$script:EvidenceRoot=[IO.Path]::GetFullPath($EvidenceDir)
$script:WorkRoot=[IO.Path]::GetFullPath($WorkRoot)
if (-not [IO.Path]::IsPathRooted($EvidenceDir) -or (Test-UnderRootLoose $script:EvidenceRoot $RepoRoot) `
    -or (Test-UnderRootLoose $script:WorkRoot $RepoRoot)) { throw 'EvidenceDir/WorkRoot must be absolute and outside checkout' }
$script:Evidence=Join-Path $script:EvidenceRoot $script:ExecutionId
$script:RunWork=Join-Path $script:WorkRoot $script:ExecutionId
[void](New-Item -ItemType Directory -Force $script:Evidence,$script:RunWork)
$script:Ai2Port=0
$script:LiveRuntimes=@{}
$script:CreateAttempted=$false
$planned=if($Mode -eq 'Integration'){@('C06-login-proxy-200','C06-authenticated-cross-tenant','C07-policy-stale-http',
    'C08-acl-stale-http','C10-two-ai-shared-active','C10-unreleased-expired-active','C10-pending-denies-acquire',
    'C10-coordinator-timeout','C10-release-drain-bump','C10-node-unreachable','C09-live-state-memory','C11-production-extended')}else{@()}
function Wait-ProductionReady($Process,[int]$Port,[string]$LogPath){
    for($attempt=0;$attempt -lt 360;$attempt++){
        if($Process.HasExited){return $false}
        $log=Read-SharedText $LogPath
        if($log -match 'APPLICATION FAILED TO START'){return $false}
        if($log -match 'Started .+ in [\d.]+ seconds' -and (Test-TcpEndpoint '127.0.0.1' $Port 300)){return $true}
        Start-Sleep -Milliseconds 500
    }
    return $false
}
function Sql([string]$Text,[string]$Name='case-sql.log') {
    Invoke-SyntheticSql $Text 'ragent_p1b' 'postgres' 'pgSuperuser' $Name
}
function Start-Production([string]$Side,[string]$State,[int]$Port) {
    $extra=@('--p04.enabled=false','--ai.integration.enabled=true','--server.servlet.context-path=/')
    if ($Side -eq 'platform') {
        $extra+=@(('--ai.integration.delegation.private-key-path='+ (Join-Path $script:RunWork 'private.pem')),
            ('--ai.integration.ai-base-url=http://127.0.0.1:'+$AiPort),
            '--ai.integration.forward-timeout-millis=60000',
            '--ai.integration.service-credential=${P1C_SERVICE}',
            '--ai.integration.authorization.service-credential=${P1C_SERVICE}')
    } else {
        $extra+=@('--ai.integration.security.enabled=true',
            ('--ai.integration.security.public-key-path='+(Join-Path $script:RunWork 'public.pem')),
            '--ai.integration.security.service-credential=${P1C_SERVICE}',
            ('--ai.integration.platform-base-url=http://127.0.0.1:'+$PlatformPort),
            '--ai.integration.platform-service-credential=${P1C_SERVICE}',
            '--ai.integration.high-risk.enabled=true','--rag.vector.type=pg',
            '--rag.storage.s3.access-key=${P1B_S3_ACCESS_KEY}','--rag.storage.s3.secret-key=${P1B_S3_SECRET_KEY}',
            ('--ai.providers.ollama.url='+$env:P1C_EMBEDDING_URL),
            '--ai.embedding.default-model=qwen-emb-local','--ai.embedding.candidates[0].enabled=true',
            '--ai.embedding.candidates[0].id=qwen-emb-local','--ai.embedding.candidates[0].provider=ollama',
            '--ai.embedding.candidates[0].model=synthetic','--ai.embedding.candidates[0].dimension=1536',
            '--ai.embedding.candidates[0].priority=1','--ai.embedding.candidates[1].enabled=false',
            '--ai.embedding.candidates[2].enabled=false')
    }
    Start-ProductJar $Side $State $Port $extra
}
function New-Delegation([string]$Tenant,[string]$Subject,[string]$Action,[int]$Pv) {
    $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $claims=@{iss='platform';aud=@('ai');sub=$Subject;tid=$Tenant;mid=('platform:'+$Tenant+':'+$Subject);
        pv=$Pv;scope=@($Action);jti=[guid]::NewGuid().ToString();iat=$now;nbf=$now;exp=($now+60)} | ConvertTo-Json -Compress
    $path=Join-Path $script:RunWork 'claims.json'
    [IO.File]::WriteAllText($path,$claims,(New-Object Text.UTF8Encoding($false)))
    $r=Invoke-NativeCapture (Join-Path $script:JdkHome 'bin\java.exe') @('-cp',$script:RunWork,'P1Keys','sign',$script:RunWork,$path) 'sign-token.log'
    if ($r.ExitCode -ne 0) { throw 'test delegation signing failed' }
    return (@($r.Output | Where-Object { $_ -match '^eyJ' }) | Select-Object -Last 1)
}
function Invoke-Live([string]$Side,[string]$Class,[string[]]$Arguments=@()) {
    if(-not $script:LiveRuntimes.ContainsKey($Side)){
        $runtime=Join-Path $script:RunWork ('live-'+$Side)
        [void](New-Item -ItemType Directory -Force (Join-Path $runtime 'lib'),(Join-Path $runtime 'classes'))
        $jar=if($Side -eq 'ai'){Join-Path $RepoRoot 'services\ai\bootstrap\target\bootstrap-0.0.1-SNAPSHOT.jar'}else{Join-Path $RepoRoot 'services\platform\ruoyi-admin\target\ruoyi-admin.jar'}
        $zip=[IO.Compression.ZipFile]::OpenRead($jar)
        try {
            foreach($entry in $zip.Entries){
                if($entry.FullName.EndsWith('/')){continue}
                $relative=if($entry.FullName.StartsWith('BOOT-INF/lib/')){'lib/'+$entry.FullName.Substring(13)}elseif($entry.FullName.StartsWith('BOOT-INF/classes/')){'classes/'+$entry.FullName.Substring(17)}else{continue}
                $target=[IO.Path]::GetFullPath((Join-Path $runtime $relative))
                if(-not (Test-UnderRootLoose $target $runtime)){throw 'Unsafe product archive path'}
                [void](New-Item -ItemType Directory -Force (Split-Path $target -Parent))
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
            }
        } finally {$zip.Dispose()}
        $script:LiveRuntimes[$Side]=$runtime
    }
    $runtime=$script:LiveRuntimes[$Side]
    $cp=(Join-Path $runtime 'classes')+';'+(Join-Path $runtime 'lib\*')
    $source=Join-Path $RepoRoot ('tools\p1-isolation\'+$Class+'.java')
    $r=Invoke-NativeCapture (Join-Path $script:JdkHome 'bin\javac.exe') @('-proc:none','-cp',$cp,'-d',(Join-Path $runtime 'classes'),$source) ('live-compile-'+$Class+'.log')
    if($r.ExitCode -ne 0){throw ('live harness compile failed: '+$Class)}
    return Invoke-NativeCapture (Join-Path $script:JdkHome 'bin\java.exe') (@('-cp',$cp,$Class)+$Arguments) ('live-'+$Class+($Arguments -join '-')+'.log')
}
function Invoke-Cases {
    $client=New-Object System.Net.Http.HttpClient
    $client.Timeout=[TimeSpan]::FromSeconds(20)
    $platform='http://127.0.0.1:'+$PlatformPort
    $ai='http://127.0.0.1:'+$AiPort
    $ai2='http://127.0.0.1:'+$script:Ai2Port
    $service=@{'X-P04-Service-Credential'=$script:Secrets['cService']}
    $tokens=@{}
    foreach ($tenant in @('p1t1','p1t2')) {
        $body=@{tenantId=$tenant;clientId='p1b-client';grantType='password';username='p1b-admin';password=$script:Secrets['fixtureUser']} | ConvertTo-Json -Compress
        $r=Send-Json $client 'POST' ($platform+'/auth/login') @{} $body
        Assert-That ('LOGIN-'+$tenant) 'C06' ($r.Status -eq 200 -and $r.Json.code -eq 200 -and $r.Json.data.access_token) ('status='+$r.Status+' code='+$r.Json.code)
        $tokens[$tenant]=[string]$r.Json.data.access_token
    }
    $headers=@{Authorization='Bearer '+$tokens['p1t1'];clientid='p1b-client'}
    $r=Send-Json $client 'GET' ($platform+'/api/ai/v1/knowledge-bases/kb-t1-private-a') $headers ''
    Assert-That 'C06-login-proxy-200' 'C06' ($r.Status -eq 200 -and $r.Json.code -eq 200 -and $r.Json.data.kbId -eq 'kb-t1-private-a') ('status='+$r.Status+' code='+$r.Json.code)
    $before=Get-RowHashSnapshot
    $cross=Send-Json $client 'GET' ($platform+'/api/ai/v1/knowledge-bases/kb-t2-same-selector') $headers ''
    $missing=Send-Json $client 'GET' ($platform+'/api/ai/v1/knowledge-bases/missing') $headers ''
    $after=Get-RowHashSnapshot
    # replay/permit audit records are expected; business tables must remain byte-identical.
    $changed=@($before.PSObject.Properties | Where-Object { $_.Name -notmatch '(replay|permit|barrier)' -and
        ($_.Value | ConvertTo-Json -Compress) -cne ($after.($_.Name) | ConvertTo-Json -Compress) })
    Assert-That 'C06-authenticated-cross-tenant' 'C06' ($cross.Status -eq 404 -and $missing.Status -eq 404 `
        -and $cross.Json.data.errorCode -ceq $missing.Json.data.errorCode -and $changed.Count -eq 0) ('cross='+$cross.Status+' missing='+$missing.Status+' changed='+$changed.Count)
    [IO.File]::WriteAllText((Join-Path $script:Evidence 'denial-before.json'),($before | ConvertTo-Json -Depth 20))
    [IO.File]::WriteAllText((Join-Path $script:Evidence 'denial-after.json'),($after | ConvertTo-Json -Depth 20))
    $old=New-Delegation 'p1t1' '900000000000000001' 'kb.read' 1
    $policy=Send-Json $client 'PUT' ($platform+'/system/role/dataScope') $headers '{"roleId":900000000000000021,"dataScope":"1","deptIds":[]}'
    Assert-That 'C07-automatic-policy-mutation' 'C07' ($policy.Status -eq 200 -and $policy.Json.code -eq 200) ('status='+$policy.Status+' code='+$policy.Json.code)
    $oldHeaders=@{Authorization='Bearer '+$old;'X-P04-Service-Credential'=$script:Secrets['cService']}
    $r=Send-Json $client 'GET' ($ai+'/internal/ai/v1/knowledge-bases/kb-t1-private-a') $oldHeaders ''
    Assert-That 'C07-policy-stale-http' 'C07' ($r.Status -eq 409 -and $r.Json.code -eq 409) ('status='+$r.Status+' code='+$r.Json.code)
    $env:AI_DB_URL='jdbc:postgresql://127.0.0.1:'+$script:PgPort+'/ragent_p1b?currentSchema=ai,extensions'
    $env:P1C_PLATFORM_DB_URL='jdbc:postgresql://127.0.0.1:'+$script:PgPort+'/ragent_p1b?currentSchema=platform,extensions'
    $env:P1C_PLATFORM_URL=$platform
    $env:P1C_AI_URL=$ai
    $env:P1C_S3_URL='http://127.0.0.1:'+$script:S3Port
    $env:P1C_PRIVATE_KEY=Join-Path $script:RunWork 'private.pem'
    $env:P1C_BROWSER_TOKEN=$tokens['p1t1']
    if($BrowserVerification){
        [IO.File]::WriteAllText((Join-Path $script:RunWork 'browser-ready.json'),(@{platform=$platform;ai=$ai;username='p1b-admin';password=$script:Secrets['fixtureUser'];tenantId='p1t1';clientId='p1b-client'} | ConvertTo-Json))
        $deadline=[DateTime]::UtcNow.AddMinutes(15)
        while(-not (Test-Path (Join-Path $script:RunWork 'browser-pass.json')) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 500}
        Assert-That 'C12-browser-tenant-login-cache' 'C12' (Test-Path (Join-Path $script:RunWork 'browser-pass.json')) 'Browser evidence attestation supplied by active UI verification'
        if(Test-Path (Join-Path $script:RunWork 'browser-pass.json')){Copy-Item -LiteralPath (Join-Path $script:RunWork 'browser-pass.json') -Destination (Join-Path $script:Evidence 'browser-pass.json')}
    }
    $extended=Invoke-Live 'ai' 'P1ProductionAcceptance'
    Assert-That 'C11-production-extended' 'C11' ($extended.ExitCode -eq 0 -and ($extended.Output -join '') -match 'PASS C11-production-extended') ('exit='+$extended.ExitCode+' actual HTTP/source/PG/delivery/data-scope checks')
    if($extended.ExitCode -ne 0){throw 'Extended production acceptance failed'}
    $version=Sql "SELECT version FROM platform.sys_ai_policy_revision WHERE tenant_id='p1t1';" 'current-policy.log'
    $env:P1C_PV=$version.Trim()
    $live=Invoke-Live 'ai' 'P1LiveAcceptance'
    Assert-That 'C09-live-state-memory' 'C09' ($live.ExitCode -eq 0 -and ($live.Output -join '') -match 'PASS C09-live-state-memory') ('exit='+$live.ExitCode+' actual mapper/service in live harness log')
    $env:P1C_RACE_JWT=New-Delegation 'p1t1' '900000000000000001' 'kb.write' ([int]$env:P1C_PV)
    $race=Invoke-Live 'ai' 'P1LiveAcceptance' @('acl-race')
    Assert-That 'C08-acl-stale-http' 'C08' ($race.ExitCode -eq 0 -and ($race.Output -join '') -match 'HTTP 409 code=409') ('exit='+$race.ExitCode+' actual HTTP blocked acquire and concurrent epoch commit')
    Remove-Item Env:P1C_RACE_JWT -ErrorAction SilentlyContinue
    $held=Invoke-Live 'ai' 'P1LiveAcceptance' @('hold')
    Assert-That 'C10-held-permit' 'C10' ($held.ExitCode -eq 0 -and ($held.Output -join '') -match 'PASS C10-held-permit') ('exit='+$held.ExitCode+' actual AI/platform permit registration')
    $body=@{action='CLOSE';tenantId='p1t1';barrierId='c10'} | ConvertTo-Json -Compress
    $one=Send-Json $client 'POST' ($ai+'/internal/ai/v1/authorization/barriers') $service $body
    $two=Send-Json $client 'POST' ($ai2+'/internal/ai/v1/authorization/barriers') $service (@{action='STATUS';tenantId='p1t1';barrierId='c10'} | ConvertTo-Json -Compress)
    Assert-That 'C10-two-ai-shared-active' 'C10' ($one.Status -eq 200 -and $two.Status -eq 200 -and $one.Json.activePermits -eq 1 -and $two.Json.activePermits -eq 1 -and $two.Json.status -eq 'PENDING') ('node1='+$one.Text+' node2='+$two.Text)
    Assert-That 'C10-unreleased-expired-active' 'C10' ($two.Json.activePermits -eq 1) 'Expired ACTIVE permit remains in drain set; no lease-based success.'
    $write=Send-Json $client 'POST' ($platform+'/api/ai/v1/knowledge-bases') $headers (@{name='blocked';embeddingModel='synthetic';collectionName='blocked'} | ConvertTo-Json -Compress)
    Assert-That 'C10-pending-denies-acquire' 'C10' ($write.Status -eq 503) ('status='+$write.Status)
    $blocked=Invoke-Live 'platform' 'P1PlatformLiveAcceptance' @('blocked')
    Assert-That 'C10-coordinator-timeout' 'C10' ($blocked.ExitCode -eq 0 -and ($blocked.Output -join '') -match 'PASS C10-coordinator-timeout') ('exit='+$blocked.ExitCode+' actual coordinator timeout; pv unchanged and PENDING')
    $released=Invoke-Live 'ai' 'P1LiveAcceptance' @('release')
    Assert-That 'C10-held-release' 'C10' ($released.ExitCode -eq 0 -and ($released.Output -join '') -match 'PASS C10-held-release') ('exit='+$released.ExitCode+' actual AI/platform release')
    $drained=Invoke-Live 'platform' 'P1PlatformLiveAcceptance' @('drained')
    $two=Send-Json $client 'POST' ($ai2+'/internal/ai/v1/authorization/barriers') $service (@{action='STATUS';tenantId='p1t1';barrierId='c10'} | ConvertTo-Json -Compress)
    Assert-That 'C10-release-drain-bump' 'C10' ($drained.ExitCode -eq 0 -and ($drained.Output -join '') -match 'PASS C10-release-drain-bump' -and $two.Status -eq 200 -and $two.Json.activePermits -eq 0 -and $two.Json.status -eq 'OPEN') ('exit='+$drained.ExitCode+' node2='+$two.Text)
    Stop-OwnedProcess (Join-Path $script:RunWork 'ai-production2.pid') 'java' 'fault node2'
    $env:P1C_STOPPED_AI_URL=$ai2
    $fault=Invoke-Live 'platform' 'P1PlatformLiveAcceptance'
    Assert-That 'C10-node-unreachable' 'C10' ($fault.ExitCode -eq 0 -and ($fault.Output -join '') -match 'PASS C10-node-unreachable') ('exit='+$fault.ExitCode+' actual coordinator/JDBC/HTTP node fault; pv unchanged and PENDING persisted')
    $client.Dispose()
}
try {
    [void](Initialize-Toolchain)
    $env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir='+$script:RunWork
    $taskJavaHome=Join-Path $script:RunWork 'java-home'
    [void](New-Item -ItemType Directory -Force (Join-Path $taskJavaHome '.msp'))
    $env:MAVEN_OPTS='-Dfile.encoding=UTF-8 -Duser.home='+$taskJavaHome+' -Dmaven.repo.local=D:/develop/maven_repository'
    $script:Secrets['cService']=New-RandomSecret
    $env:P1C_SERVICE=$script:Secrets['cService']
    if ($Mode -eq 'Unit') {
        $classes=@{ 'P1.2b'='P1CurrentAuthorizationTest,P1PolicyRevisionTest,P1IdentityAssemblyTest';
            'P1.3a'='P1ResourceSqlIsolationTest,P1PersistentAclTest'; 'P1.2c'='P1PrincipalSecurityTest,P1GatewayIdentityTest';
            'P1.3b'='P1RetrieverDirectIsolationTest,P1MilvusFilterTest,P1EsFilterTest,P1PreModelAuthorizationTest';
            'P1.3c'='P1ObjectAndCacheIsolationTest,P1AsyncContextIsolationTest';
            'P1.3d'='P1StateAndMemoryIsolationTest,P1ConversationEventIsolationTest';
            'P1.4'='P1RevocationRaceTest,P1IsolationAcceptanceTest,P1PolicyMutationCoverageTest' }
        foreach($side in @('platform','ai')) {
            $profile=if($side -eq 'platform'){'-Pdev'}else{'-Pci'}
            $r=Invoke-NativeCapture $script:MavenExe @('-o','-B','-ntp','-f',('services/'+$side+'/pom.xml'),$profile,'test',('-Dtest='+$classes[$Unit]),'-Dsurefire.failIfNoSpecifiedTests=false') ('unit-'+$side+'.log') $RepoRoot
            Assert-That ('UNIT-'+$side) 'UNIT' ($r.ExitCode -eq 0) ('exit='+$r.ExitCode)
        }
        foreach($name in $classes[$Unit].Split(',')) {
            $files=@(@('ai','platform') | ForEach-Object {
                Get-ChildItem (Join-Path $RepoRoot ('services/'+$_)) -Recurse -File -Filter ('TEST-*.'+$name+'.xml')
            } | Where-Object { $_.Directory.Name -eq 'surefire-reports' -and $_.LastWriteTimeUtc -ge $script:RunStartedUtc })
            $valid=$files.Count -eq 1
            if($valid){ [xml]$suite=Get-Content $files[0].FullName; $valid=([int]$suite.testsuite.tests -gt 0 -and [int]$suite.testsuite.failures -eq 0 -and [int]$suite.testsuite.errors -eq 0 -and [int]$suite.testsuite.skipped -eq 0) }
            Assert-That ('XML-'+$name) 'UNIT' $valid ('freshXml='+$files.Count)
        }
    } else {
        if($Unit -ne 'P1.4'){ throw 'Earlier-stage Integration migrations are not implemented; use Unit mode or final P1.4 Integration. No stage PASS is inferred.' }
        Invoke-ContainerRuntimePreflight
        if($script:EnvGateBlocked){ foreach($id in $planned){Add-Result $id 'C' 'NOT_RUN' $script:GateReasonDetail} }
        else {
            if(-not $SkipBuild){
                foreach($side in @('platform','ai')) {
                    $profile=if($side -eq 'platform'){'-Pdev'}else{'-Pci'}
                    $r=Invoke-NativeCapture $script:MavenExe @('-o','-B','-ntp','-f',('services/'+$side+'/pom.xml'),$profile,'verify') ('build-'+$side+'.log') $RepoRoot
                    Assert-That ('BUILD-'+$side) 'BUILD' ($r.ExitCode -eq 0) ('exit='+$r.ExitCode)
                    if($r.ExitCode -ne 0){ throw 'Product build failed' }
                }
            }
            Invoke-ProviderKeyIsolation
            Initialize-SyntheticSecrets
            $script:PgPort=Get-FreePortInRange 15432 15472
            $script:RedisPort=Get-FreePortInRange 16379 16419
            $script:S3Port=Get-FreePortInRange 19000 19040
            $script:Ai2Port=Get-FreePortInRange 19200 19280
            if(0 -in @($script:PgPort,$script:RedisPort,$script:S3Port,$script:Ai2Port)){throw 'No free synthetic ports'}
            Initialize-SyntheticIdentity
            $collision=Invoke-RuntimeCapture @('ps','--all','--filter',('label=p1.boundary.owner='+$RunTag),'--format','{{.Names}}') 'owner-tag-collision.log'
            if($collision.ExitCode -ne 0 -or @($collision.Output | Where-Object {$_.Trim()}).Count -gt 0){throw 'Owner tag is unavailable; existing resources will not be reused or cleaned'}
            $script:CreateAttempted=$true
            Start-SyntheticEnvironment
            if(@((Get-ResultRow 'ENV-compose-up') | Where-Object {$_.status -eq 'FAIL'}).Count -gt 0){throw 'Owned compose startup failed'}
            if(-not (Start-RemotePortForward)){throw 'Owned port-forward failed'}
            Initialize-SyntheticDatabase
            $tenants=[IO.File]::ReadAllText((Join-Path $RepoRoot 'tools/p1-isolation/tenants.sql'))
            $resources=[IO.File]::ReadAllText((Join-Path $RepoRoot 'tools/p1-isolation/resources.sql'))
            [IO.File]::WriteAllText((Join-Path $script:Evidence 'tenants.sql'),$tenants)
            [IO.File]::WriteAllText((Join-Path $script:Evidence 'resources.sql'),$resources)
            [void](Sql ($tenants+$resources) 'C-fixture-install.log')
            $schema=Sql ([IO.File]::ReadAllText((Join-Path $RepoRoot 'tools/p1-isolation/schema-acceptance.sql'))) 'C-live-schema.log'
            $ledger=Get-Content (Join-Path $RepoRoot 'services/ai/rag/src/test/resources/p1/table-attribution.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            foreach($table in $ledger.tables){
                $lines=@($schema -split "\r?\n")
                $valid=$lines -contains ('PK|'+$table.name+'|'+($table.primaryKey -join ','))
                if($table.tenantColumn -eq 'not_null'){$valid=$valid -and ($lines -contains ('NN|'+$table.name+'|tenant_id'))}
                if($table.memberColumn){$valid=$valid -and ($lines -contains ('NN|'+$table.name+'|member_id'))}
                if($table.ownerColumn){$valid=$valid -and ($lines -contains ('NN|'+$table.name+'|'+$table.ownerColumn))}
                foreach($key in $table.uniqueTenantScoped){$suffix='|'+($key -join ',');$valid=$valid -and @($lines | Where-Object {$_.StartsWith('UQ|'+$table.name+'|') -and $_.EndsWith($suffix)}).Count -gt 0}
                foreach($fk in $table.foreignKeys){if($fk -match '-> (\w+) \(([^)]+)\)'){$suffix='|'+$Matches[1]+'|'+($Matches[2] -replace ' ','');$valid=$valid -and @($lines | Where-Object {$_.StartsWith('FK|'+$table.name+'|') -and $_.EndsWith($suffix)}).Count -gt 0}}
                Assert-That ('DDL-'+$table.name) 'C02' $valid 'live schema matches shared attribution ledger'
            }
            $counts=Sql "SELECT 'kb='||count(*) FROM ai.t_knowledge_base UNION ALL SELECT 'doc='||count(*) FROM ai.t_knowledge_document UNION ALL SELECT 'vector='||count(*) FROM ai.t_knowledge_vector UNION ALL SELECT 'conversation='||count(*) FROM ai.t_conversation UNION ALL SELECT 'run='||count(*) FROM ai.ai_run;" 'C-fixture-counts.log'
            Assert-That 'C02-fixture-counts' 'C02' (@(($counts -split "\r?\n") | Where-Object {$_ -in @('kb=8','doc=3','vector=4','conversation=3','run=2')}).Count -eq 5) 'exact synthetic fixtures installed'
            [void](Initialize-ExternalizedPlaceholders)
            $java=@'
import java.nio.file.*; import java.security.*; import java.security.spec.*; import java.util.*;
public class P1Keys {
  static String b(byte[] v){return Base64.getUrlEncoder().withoutPadding().encodeToString(v);}
  static void pem(Path p,String type,byte[] v)throws Exception{Files.writeString(p,"-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(v)+"\n-----END "+type+"-----\n");}
  public static void main(String[] a)throws Exception{
    Path dir=Path.of(a[1]);
    if(a[0].equals("generate")){var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);var k=g.generateKeyPair();pem(dir.resolve("private.pem"),"PRIVATE KEY",k.getPrivate().getEncoded());pem(dir.resolve("public.pem"),"PUBLIC KEY",k.getPublic().getEncoded());return;}
    String p=Files.readString(dir.resolve("private.pem")).replaceAll("-----[^-]+-----","").replaceAll("\\s","");
    var key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(p)));
    String s=b("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"platform-prod-k1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+b(Files.readAllBytes(Path.of(a[2])));
    var sig=Signature.getInstance("SHA256withRSA");sig.initSign(key);sig.update(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));System.out.println(s+"."+b(sig.sign()));
  }
}
'@
            [IO.File]::WriteAllText((Join-Path $script:RunWork 'P1Keys.java'),$java)
            $r=Invoke-NativeCapture (Join-Path $script:JdkHome 'bin\javac.exe') @('-d',$script:RunWork,(Join-Path $script:RunWork 'P1Keys.java')) 'keys-compile.log'
            if($r.ExitCode -ne 0){throw 'RSA helper compilation failed'}
            [void](Invoke-NativeCapture (Join-Path $script:JdkHome 'bin\java.exe') @('-cp',$script:RunWork,'P1Keys','generate',$script:RunWork) 'keys-generate.log')
            $script:EmbeddingPort=Get-FreePortInRange 19400 19480
            $env:P1C_EMBEDDING_URL='http://127.0.0.1:'+$script:EmbeddingPort
            $r=Invoke-NativeCapture (Join-Path $script:JdkHome 'bin\javac.exe') @('-d',$script:RunWork,(Join-Path $RepoRoot 'tools/p1-isolation/P1EmbeddingFixture.java')) 'embedding-compile.log'
            if($r.ExitCode -ne 0){throw 'Embedding fixture compilation failed'}
            $embedding=Start-OwnedProcess (Join-Path $script:JdkHome 'bin\java.exe') @('-cp',$script:RunWork,'P1EmbeddingFixture',([string]$script:EmbeddingPort)) $script:RunWork (Join-Path $script:RunWork 'embedding.pid')
            for($attempt=0;$attempt -lt 40 -and -not (Test-TcpEndpoint '127.0.0.1' $script:EmbeddingPort 200);$attempt++){Start-Sleep -Milliseconds 100}
            foreach($node in @(@('platform','production',$PlatformPort),@('ai','production1',$AiPort),@('ai','production2',$script:Ai2Port))){
                $process=Start-Production $node[0] $node[1] $node[2]
                $ready=Wait-ProductionReady $process $node[2] (Join-Path $script:RunWork ($node[0]+'-run-'+$node[1]+'\stdout.log'))
                Assert-That ('BOOT-'+$node[1]) 'BOOT' ($ready -and -not $process.HasExited) ('ready='+$ready)
                if(-not $ready -or $process.HasExited){throw 'Production jar failed to start'}
            }
            Invoke-Cases
        }
    }
} catch { Add-Result 'HARNESS' '-' 'FAIL' ($_.Exception.Message+' '+$_.InvocationInfo.PositionMessage) }
finally {
    foreach($file in @(Get-ChildItem $script:RunWork -Filter '*.pid' -File)){
        $kind=if($file.Name -like 'ssh*'){'ssh'}else{'java'}
        Stop-OwnedProcess $file.FullName $kind ('cleanup '+$file.Name)
    }
    foreach($dir in @(Get-ChildItem $script:RunWork -Directory | Where-Object {$_.Name -like '*-run-*'})){
        foreach($stream in @('stdout','stderr')){Copy-SanitizedLog (Join-Path $dir.FullName ($stream+'.log')) (Join-Path $script:Evidence ('jvm-logs\'+$dir.Name+'\'+$stream+'.log'))}
    }
    $removedContainers=$false
    if($script:CreateAttempted -and $script:ComposePath){
        $down=Invoke-OwnedCompose @('down','--volumes','--remove-orphans','--timeout','20') 'compose-down.log'
        $left=Invoke-RuntimeCapture @('ps','--all','--filter',('label=p1.boundary.owner='+$RunTag),'--format','{{.Names}}') 'compose-leftover.log'
        Assert-That 'CLEANUP-owned-containers' 'CLEANUP' ($down.ExitCode -eq 0 -and @($left.Output | Where-Object {$_.Trim()}).Count -eq 0) ('exit='+$down.ExitCode)
        $removedContainers=$down.ExitCode -eq 0 -and $left.ExitCode -eq 0 -and @($left.Output | Where-Object {$_.Trim()}).Count -eq 0
    }
    if($removedContainers -and $script:RemoteMode -and $script:RemoteComposeDir -match '^/opt/p1-acceptance/[a-z0-9-]+$'){
        [void](Invoke-RemoteShell ('rm -rf -- '+(Format-ShellArg $script:RemoteComposeDir)) 'cleanup-remote-compose.log')
    }
    foreach($id in @($script:OwnedProcessObjects.Keys)){try{$script:OwnedProcessObjects[$id].Dispose()}catch{}}
    foreach($id in @($script:ProcessWriters.Keys)){foreach($d in @($script:ProcessWriters[$id])){try{[void]$d.ps.EndInvoke($d.handle);$d.ps.Dispose();$d.rs.Dispose()}catch{}}}
    foreach($id in $planned){if(-not (Get-ResultRow $id)){Add-Result $id 'C' 'NOT_RUN' 'Previous prerequisite failed; no PASS inferred.'}}
    [IO.File]::WriteAllText((Join-Path $script:Evidence 'results.json'),($script:Results | ConvertTo-Json -Depth 20))
    [IO.File]::WriteAllText((Join-Path $script:Evidence 'http.json'),($script:HttpLog | ConvertTo-Json -Depth 20))
    [IO.File]::WriteAllText((Join-Path $script:Evidence 'native-commands.json'),($script:NativeCommands | ConvertTo-Json -Depth 20))
    $sourceFiles=@(& git -C $RepoRoot status --porcelain --untracked-files=all | ForEach-Object {
        $relative=$_.Substring(3)
        $file=Join-Path $RepoRoot $relative
        if(Test-Path -LiteralPath $file -PathType Leaf){@{path=$relative;sha256=(Get-Sha256 $file)}}
    })
    $productJars=@('services/ai/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar','services/platform/ruoyi-admin/target/ruoyi-admin.jar') | ForEach-Object {
        $jarPath=Join-Path $RepoRoot $_
        if(Test-Path -LiteralPath $jarPath -PathType Leaf){@{path=$_;sha256=(Get-Sha256 $jarPath);lastWriteUtc=(Get-Item -LiteralPath $jarPath).LastWriteTimeUtc.ToString('o')}}
    }
    $manifest=@{runner='p1-isolation';unit=$Unit;mode=$Mode;runTag=$RunTag;startedUtc=$script:RunStartedUtc.ToString('o');startedAsiaShanghai=(Get-ShanghaiStamp $script:RunStartedUtc);
        head=(& git -C $RepoRoot rev-parse HEAD);sourceDiff=(& git -C $RepoRoot diff --stat);specs=@($script:SpecPaths | ForEach-Object {@{path=$_;sha256=(Get-Sha256 $_)}});
        syntheticOnly=$true;fullP1Pass=$false;skipBuild=[bool]$SkipBuild;productJars=@($productJars);sourceFiles=$sourceFiles;results=@($script:Results);cleanup=@($script:Cleanup)}
    [IO.File]::WriteAllText((Join-Path $script:Evidence 'manifest.json'),($manifest | ConvertTo-Json -Depth 20))
    Remove-Item Env:P1C_SERVICE -ErrorAction SilentlyContinue
    Remove-OwnedPath $script:RunWork 'owned synthetic work including temporary RSA private key'
}
if($script:Failures -gt 0){exit 1}
if(@($script:Results | Where-Object {$_.status -eq 'NOT_RUN'}).Count -gt 0){exit 3}
exit 0
