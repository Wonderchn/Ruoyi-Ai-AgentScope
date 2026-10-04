# Formal product Agent HTTP/PG + independent durable ticket provider. Explicit synthetic model only.
Write-Step 'P3 formal AgentScope / read tools / sandbox confirmation / reconciliation'
$script:SandboxCredential=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
$sandboxFile=Join-Path $RepoRoot 'tools/p3-acceptance/sandbox_ticket.py'
& scp -q $sandboxFile "${RemoteHost}:$($script:RemoteRoot)/sandbox_ticket.py"
if($LASTEXITCODE -ne 0){throw 'owned sandbox source upload failed'}
$sandboxEnv="export P3_SANDBOX_DB=$($script:RemoteRoot)/sandbox.sqlite`nexport P3_SANDBOX_CREDENTIAL=$($script:SandboxCredential)`nexport P3_SANDBOX_NAMESPACE=$($script:Tag)`nexport P3_SANDBOX_FAULTS=true`n"
$sandboxEncoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sandboxEnv))
$script:SandboxName="p3-ticket-$($script:Tag)"
$launchCommand=@"
set -e
lab=ragent-ai-lab-20261003-mineru-1
test "`$(docker inspect -f '{{ index .Config.Labels "io.ragent.owner" }}' "`$lab")" = ragent-ai-lab-20261003
test "`$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "`$lab")" = ragent-ai-lab-20261003
image=`$(docker inspect -f '{{.Image}}' "`$lab")
name=$($script:SandboxName)
if docker inspect "`$name" >/dev/null 2>&1; then
 test "`$(docker inspect -f '{{ index .Config.Labels "io.ragent.owner" }}' "`$name")" = '$($script:Tag)'
 test "`$(docker inspect -f '{{ index .Config.Labels "io.ragent.role" }}' "`$name")" = sandbox-ticket
 docker stop "`$name" >/dev/null
 docker rm "`$name" >/dev/null
fi
test -z "`$(ss -ltnp | grep ':19520 ')" || exit 9
cd $($script:RemoteRoot)
umask 077
printf %s '$sandboxEncoded' | base64 -d > sandbox.env
source sandbox.env
mkdir -p sandbox-source sandbox-data
cp sandbox_ticket.py sandbox-source/sandbox_ticket.py
docker run --pull=never --detach --name "`$name" --label io.ragent.owner='$($script:Tag)' --label io.ragent.role=sandbox-ticket --network=host --memory=128m --cpus=0.5 --read-only --tmpfs /tmp:rw,noexec,size=16m -e P3_SANDBOX_DB=/data/sandbox.sqlite -e P3_SANDBOX_CREDENTIAL -e P3_SANDBOX_NAMESPACE -e P3_SANDBOX_FAULTS -v $($script:RemoteRoot)/sandbox-source:/source:ro -v $($script:RemoteRoot)/sandbox-data:/data --entrypoint /usr/local/bin/python3 "`$image" /source/sandbox_ticket.py >/dev/null
echo PID=`$(docker inspect -f '{{.State.Pid}}' "`$name")
echo IMAGE=`$image
"@
$launch=Remote $launchCommand 'p3-sandbox-start'
if($launch.ExitCode -ne 0){throw 'sandbox port occupied or launch rejected'}
$sandboxPid=0;if($launch.Output -match 'PID=(\d+)'){$sandboxPid=[int]$Matches[1]}
Save-Evidence 'p3-sandbox-owner.json' (@{runTag=$script:Tag;root=$script:RemoteRoot;pid=$sandboxPid;container=$script:SandboxName;sourceHash=(Get-FileHash $sandboxFile).Hash;image=$launch.Output;target='127.0.0.1:19520';db="$($script:RemoteRoot)/sandbox-data/sandbox.sqlite"}|ConvertTo-Json)
$health=Remote "docker exec $($script:SandboxName) python3 -c 'import os,json,urllib.request; r=urllib.request.Request(`"http://127.0.0.1:19520/health`",headers={`"Authorization`":`"Bearer `"+os.environ[`"P3_SANDBOX_CREDENTIAL`"],`"X-Sandbox-Namespace`":os.environ[`"P3_SANDBOX_NAMESPACE`"]}); v=json.load(urllib.request.urlopen(r,timeout=3)); assert v[`"state`"]==`"READY`" and v[`"namespace`"]==os.environ[`"P3_SANDBOX_NAMESPACE`"]; print(`"OWNED_SANDBOX_READY`")'" 'p3-sandbox-health'
$identity=Remote "test -r /proc/$sandboxPid/cmdline && tr '\0' '\n' < /proc/$sandboxPid/cmdline | grep -Fx '/source/sandbox_ticket.py' >/dev/null && test `"`$(docker inspect -f '{{.State.Pid}}' $($script:SandboxName))`" = '$sandboxPid'" 'p3-sandbox-pid-witness'
Add-Case 'ENV-p3-sandbox' ($health.ExitCode -eq 0 -and $health.Output -match 'OWNED_SANDBOX_READY' -and $identity.ExitCode -eq 0) 'existing owned image/Python3/HTTP readiness/namespace and exact owned container PID verified before product calls'
if($health.ExitCode -ne 0 -or $identity.ExitCode -ne 0){throw 'owned sandbox readiness required'}
function SandboxControl([string]$Body,[string]$Name) {
 return Http 'POST' 'http://127.0.0.1:19520/control' @{'Authorization'="Bearer $($script:SandboxCredential)";'X-Sandbox-Namespace'=$script:Tag;'Content-Type'='application/json'} $Body 10 $Name
}
$agentConfig=@($aiReal|Where-Object{$_ -notmatch '^--p2.chat.egress\.'})+@('--p2.chat.egress.enabled=true','--p2.chat.egress.allowed-providers=synthetic','--p3.enabled=true','--p3.synthetic-model=true','--p3.sandbox.enabled=true','--p3.approval.initiator-enabled=true',"--p3.sandbox.namespace=$($script:Tag)","--p3.sandbox.credential=$($script:SandboxCredential)")
Restart-ProductAi $agentConfig
function AgentSubmit([string]$Name,[string]$Mode='read',[string]$Retry='',[string]$Inherit='',[string]$Budget='{"maxTokens":4000,"maxSteps":6,"maxToolCalls":6}') {
 $input=@{text='What is P2-MARKER-XYZZY?';mode=$Mode}
 if($Mode -eq 'sandbox'){$input.ticket=@{title='Owned synthetic ticket';details='P3 formal test only'}}
 if($Inherit){$input.inheritActionId=$Inherit}
 $request=@{schemaVersion=1;action='agent.run';agentVersion='core-v1';input=$input;resourceRefs=@(@{type='knowledge_base';id=$kb1});budget=($Budget|ConvertFrom-Json)}
 if($Retry){$request.retryOf=$Retry}
 $reply=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs" @{'Authorization'="Bearer $t1";'Content-Type'='application/json';'Idempotency-Key'="$Name-$CaseAttempt-$($script:Tag)"} ($request|ConvertTo-Json -Compress -Depth 10) 30 $Name
 return [pscustomobject]@{Status=$reply.Status;Run=(RunId $reply.Body);Body=$reply.Body}
}
function Actions([string]$Run,[string]$Name,[string]$Token=$t1) {return Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$Run/actions" @{'Authorization'="Bearer $Token"} '' 20 $Name}
function TicketAction([string]$Run,[string]$Name) {return ((Actions $Run $Name).Body|ConvertFrom-Json).data|Where-Object{$_.tool -eq 'sandbox_ticket'}|Select-Object -First 1}
function ApproveBody($Action,[string]$Decision='ALLOW') {return @{actionId=$Action.actionId;argsHash=$Action.argsHash;toolVersion=$Action.toolVersion;target=$Action.target;approvalVersion=$Action.approvalVersion;decision=$Decision}|ConvertTo-Json -Compress}
function Approve([string]$Run,$Action,[string]$Name,[string]$Decision='ALLOW') {return Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$Run/approvals" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} (ApproveBody $Action $Decision) 30 $Name}
function ParallelApprove([string]$Run,$Action,[string]$Name) {
 $body=ApproveBody $Action
 $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($body))
 $command=@"
set -e
cd /tmp/p2http-$($script:Tag)
umask 077
printf %s '$encoded' | base64 -d > $Name-body.json
for i in `$(seq 1 20); do
 (curl -sS -m 30 -o $Name-`$i.json -w '%{http_code}\n' -X POST 'http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$Run/approvals' -H 'clientid: p2c-client' -H 'Authorization: Bearer $t1' -H 'Content-Type: application/json' --data-binary @$Name-body.json > $Name-`$i.status) &
done
wait
cat $Name-*.status
"@
 $r=Remote $command $Name
 $codes=@(($r.Output -split "`n")|ForEach-Object{$_.Trim()}|Where-Object{$_ -match '^\d{3}$'})
 $ok=$r.ExitCode -eq 0 -and $codes.Count -eq 20 -and @($codes|Where-Object{$_ -ne '200'}).Count -eq 0
 Add-Case 'P3-A13-20-confirmations' $ok '20 actual HTTP decisions, same bound proposal, every reply200; durable approval/write count verified separately'
 return [pscustomobject]@{Status=$(if($ok){200}else{0});Body=$r.Output}
}
function QueryAction([string]$Run,$Action,[string]$Name) {return Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$Run/reconciliations/$($Action.actionId)/query" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} '{}' 30 $Name}
function SandboxFacts([string]$Name) {
 $facts=Remote "docker exec $($script:SandboxName) python3 -c 'import sqlite3,json; c=sqlite3.connect(`"/data/sandbox.sqlite`"); print(json.dumps(c.execute(`"select operation_key,requests,writes from tickets order by operation_key`").fetchall()))'" $Name
 if($facts.ExitCode -ne 0){throw 'external fact query failed; no write-count assertion allowed'}
 [void]($facts.Output|ConvertFrom-Json)
 return $facts
}
$seed=UploadPdf $t1 $kb1 'p3-evidence.pdf' 'p3-seed-upload' "p3-seed-$CaseAttempt-$($script:Tag)"
$ingest=Ingest $seed.Doc $seed.Upload ('p3-seed-ingest-'+$CaseAttempt);$ingested=Wait-RunStatus $t1 $ingest.Run 'SUCCEEDED' 180
Add-Case 'P3-source-control' ([bool]$ingested) 'real LocalMinerU + synthetic embedding source, formal APIs'
if(-not $ingested){throw 'P3 source control is required'}
if($AgentReadFaultsOnly) {
 . (Join-Path $RepoRoot 'tools/p3-acceptance/cases-read-faults.ps1')
 return
}
if($AgentSecurityOnly) {
 . (Join-Path $RepoRoot 'tools/p3-acceptance/cases-adversarial.ps1')
 . (Join-Path $RepoRoot 'tools/p3-acceptance/cases-security.ps1')
 return
}
if(-not $AgentRecoveryOnly) {
$read=AgentSubmit 'p3-read';$readDone=Wait-RunStatus $t1 $read.Run 'SUCCEEDED' 120
$readFacts=Sql "SELECT engine_version,steps_used,tools_used,tokens_used,(SELECT count(*) FROM ai_agent_checkpoint WHERE tenant_id='p2t1' AND run_id='$($read.Run)'),(SELECT terminal_result->>'answer' FROM ai_run WHERE tenant_id='p2t1' AND run_id='$($read.Run)') FROM ai_agent_run WHERE tenant_id='p2t1' AND run_id='$($read.Run)';" 'p3-read-facts'
Add-Case 'P3-read-real-engine' ($read.Status -eq 202 -and [bool]$readDone -and $readFacts.Output -match '(?s)2\.0\.2\|2\|1\|20\|[1-9][0-9]*\|.*P2-MARKER-XYZZY') 'actual AgentScope2.0.2, formal fenced state SPI, trusted PG read tool, explicit synthetic model'
if(-not $readDone){throw 'formal Agent read control required before write tests'}
$cross=Actions $read.Run 'p3-cross-tenant' $t2
Add-Case 'P3-private-run' ($cross.Status -eq 404) 'same-named other tenant cannot see actions'
$initialFacts=SandboxFacts 'p3-initial-facts'
$write=AgentSubmit 'p3-write' 'sandbox';$waiting=Wait-RunStatus $t1 $write.Run 'WAITING_APPROVAL' 120
$action=TicketAction $write.Run 'p3-proposed'
$before=SandboxFacts 'p3-before-approval'
Add-Case 'P3-waiting-zero-write' ([bool]$waiting -and $action.state -eq 'PROPOSED' -and $before.Output.Trim() -eq $initialFacts.Output.Trim()) 'persisted exact proposal, no external POST before confirmation'
if(-not $action){throw 'formal proposed ticket required'}
$wrong=($action|ConvertTo-Json -Depth 6|ConvertFrom-Json);$wrong.argsHash='0'*64
$changed=Approve $write.Run $wrong 'p3-approval-changed'
Add-Case 'P3-approval-argument-binding' ($changed.Status -eq 409) 'changed argument digest rejected without queueing'
$approved=ParallelApprove $write.Run $action 'p3-approval-concurrent'
$writeDone=Wait-RunStatus $t1 $write.Run 'SUCCEEDED' 120
$writeFacts=SandboxFacts 'p3-after-approved-write'
$single=Sql "SELECT state,external_id IS NOT NULL,(SELECT count(*) FROM ai_action_approval WHERE tenant_id='p2t1' AND action_id='$($action.actionId)'),(SELECT count(*) FROM ai_run_event WHERE tenant_id='p2t1' AND run_id='$($write.Run)' AND event_type='run.terminal') FROM ai_tool_call WHERE tenant_id='p2t1' AND action_id='$($action.actionId)';" 'p3-write-facts'
Add-Case 'P3-approved-single-write' ($approved.Status -eq 200 -and [bool]$writeDone -and $single.Output.Trim() -eq 'SUCCEEDED|t|1|1' -and $writeFacts.Output -match ('\[\"'+[regex]::Escape((DbScalar "SELECT operation_key FROM ai_tool_call WHERE tenant_id='p2t1' AND action_id='$($action.actionId)';"))+ '\", 1, 1\]')) 'one approval, one actual external request/write, one terminal'
# Unknown response, nonfinal empty query, verified FOUND, then explicit resume retains key.
[void](SandboxControl '{"responseLost":1,"queryAbsent":1}' 'p3-arm-loss')
$unknown=AgentSubmit 'p3-unknown' 'sandbox';[void](Wait-RunStatus $t1 $unknown.Run 'WAITING_APPROVAL' 120)
$unknownAction=TicketAction $unknown.Run 'p3-unknown-proposed';[void](Approve $unknown.Run $unknownAction 'p3-unknown-approve')
$needs=Wait-RunStatus $t1 $unknown.Run 'NEEDS_RECONCILIATION' 120
$absent=QueryAction $unknown.Run $unknownAction 'p3-query-absent'
$found=QueryAction $unknown.Run $unknownAction 'p3-query-found'
$snapshot=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($unknown.Run)" @{'Authorization'="Bearer $t1"} '' 20 'p3-needs-snapshot'
$resume=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($unknown.Run)/resume" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} (@{expectedVersion=(($snapshot.Body|ConvertFrom-Json).data.version)}|ConvertTo-Json -Compress) 20 'p3-needs-resume'
$resumed=Wait-RunStatus $t1 $unknown.Run 'SUCCEEDED' 120
$unknownFacts=SandboxFacts 'p3-unknown-external-facts'
$reconciled=Sql "SELECT state,(SELECT string_agg(finality,',' ORDER BY seq) FROM ai_action_reconciliation WHERE tenant_id='p2t1' AND action_id='$($unknownAction.actionId)'),(SELECT count(*) FROM ai_run_event WHERE tenant_id='p2t1' AND run_id='$($unknown.Run)' AND event_type='run.terminal') FROM ai_tool_call WHERE tenant_id='p2t1' AND action_id='$($unknownAction.actionId)';" 'p3-unknown-ledger'
Add-Case 'P3-unknown-reconcile-resume' ([bool]$needs -and $absent.Body -match 'UNKNOWN' -and $found.Body -match 'FOUND' -and $resume.Status -eq 200 -and [bool]$resumed -and $reconciled.Output.Trim() -eq 'SUCCEEDED|UNKNOWN,FOUND|1') 'lost response remains unknown; absence does not rePOST; verified receipt resumes original key'
$inherit=AgentSubmit 'p3-inherit' 'sandbox' $write.Run $action.actionId
$inherited=Wait-RunStatus $t1 $inherit.Run 'SUCCEEDED' 120
$inheritFacts=Sql "SELECT count(*),(SELECT count(*) FROM ai_tool_call WHERE tenant_id='p2t1' AND run_id='$($inherit.Run)' AND tool_name='sandbox_ticket'),(SELECT count(*) FROM ai_run_event WHERE tenant_id='p2t1' AND run_id='$($inherit.Run)' AND event_type='tool.inherited') FROM ai_action_inheritance WHERE tenant_id='p2t1' AND new_run_id='$($inherit.Run)';" 'p3-inheritance-facts'
Add-Case 'P3-cross-run-inheritance' ($inherit.Status -eq 202 -and [bool]$inherited -and $inheritFacts.Output.Trim() -eq '1|0|1') 'new formal run explicitly inherits verified original result, no new ticket proposal/write'
$limited=AgentSubmit 'p3-budget' 'read' '' '' '{"maxTokens":4000,"maxSteps":1,"maxToolCalls":1}'
$budgetFailed=Wait-RunStatus $t1 $limited.Run 'FAILED' 120
$budgetFacts=Sql "SELECT r.error_code,a.steps_used,a.tools_used FROM ai_run r JOIN ai_agent_run a USING(tenant_id,run_id) WHERE r.tenant_id='p2t1' AND r.run_id='$($limited.Run)';" 'p3-budget-facts'
Add-Case 'P3-step-budget' ([bool]$budgetFailed -and $budgetFacts.Output.Trim() -eq 'BUDGET_EXCEEDED|1|1') 'cumulative formal model-step limit is enforced before another call'
}
. (Join-Path $RepoRoot 'tools/p3-acceptance/cases-adversarial.ps1')
. (Join-Path $RepoRoot 'tools/p3-acceptance/cases-recovery.ps1')
Save-Evidence 'p3-agent-results.json' ($script:Results|ConvertTo-Json -Depth 6)
Save-Evidence 'p3-final-external-facts.json' (SandboxFacts 'p3-final-external-facts').Output
