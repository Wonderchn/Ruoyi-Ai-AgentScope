# Dot-sourced after the formal base controls. No provider secrets or business data.
Write-Step 'P3 adversarial controls: decisions / IDs / recovery / budgets'
function ExternalKeyFact([string]$ActionId,[string]$Name) {
 $key=DbScalar "SELECT operation_key FROM ai_tool_call WHERE tenant_id='p2t1' AND action_id='$ActionId';"
 if($key -notmatch '^op-[a-f0-9]{32}$'){throw 'invalid fixed operation key from owned ledger'}
 $r=Remote "docker exec $($script:SandboxName) python3 -c 'import sqlite3; c=sqlite3.connect(`"/data/sandbox.sqlite`"); row=c.execute(`"select requests,writes from tickets where operation_key=?`",(`"$key`",)).fetchone(); print(`"ABSENT`" if row is None else str(row[0])+`"|`"+str(row[1]))'" $Name
 if($r.ExitCode -ne 0){throw 'external fact proof unavailable'}
 return $r.Output.Trim()
}
function SignalOwned([int]$Port,[string]$Signal,[string]$Name) {
 $record=@($script:Pids|Where-Object{$_.port -eq $Port})|Select-Object -Last 1
 if(-not $record -or $Signal -notin @('STOP','CONT')){throw 'missing exact owned signal target'}
 $r=Remote "test -r /proc/$($record.pid)/cmdline && tr '\0' '\n' < /proc/$($record.pid)/cmdline | grep -Fx '$($script:RemoteRoot)/ai.jar' >/dev/null && tr '\0' '\n' < /proc/$($record.pid)/cmdline | grep -Fx -- '--server.port=$Port' >/dev/null && kill -$Signal $($record.pid); echo SIGNAL_EXIT=`$?" $Name
 if($r.Output -notmatch 'SIGNAL_EXIT=0'){throw 'owned signal rejected'}
}
function PauseHook([string]$Hook) {
 return AiHttp 'POST' '/internal/ai/v1/test/fault' 'p2t1' (FixtureUserId 'p2t1') 'run.get' @{'Content-Type'='application/json'} (@{hook=$Hook;times=1;pauseMillis=30000}|ConvertTo-Json -Compress) 1 20 ('p3-pause-'+$Hook)
}
function WaitHook([string]$Hook,[string]$Name) {
 for($n=0;$n -lt 30;$n++){
  $hits=AiHttp 'GET' '/internal/ai/v1/test/fault' 'p2t1' (FixtureUserId 'p2t1') 'run.get' @{} '' 1 20 $Name
  if(($hits.Body|ConvertFrom-Json).hits.$Hook -ge 1){return $true}
  Start-Sleep -Milliseconds 200
 }
 return $false
}
if($AgentRecoveryOnly -or $AgentSecurityOnly){return}
$baseline=SandboxFacts 'p3-adv-baseline'
foreach($field in @('toolVersion','target','approvalVersion')) {
 $altered=($action|ConvertTo-Json -Depth 6|ConvertFrom-Json)
 if($field -eq 'toolVersion'){$altered.toolVersion='untrusted-v2'}
 elseif($field -eq 'target'){$altered.target='http://127.0.0.1:1/other'}
 else{$altered.approvalVersion=[int]$altered.approvalVersion+1}
 $denied=Approve $write.Run $altered ('p3-bind-'+$field)
 Add-Case ('P3-A15-'+$field) ($denied.Status -eq 409) 'owned same-run parameter/version change rejects the old confirmation'
}
$opposite=Approve $write.Run $action 'p3-opposite' 'DENY'
$same=Approve $write.Run $action 'p3-repeat-allow'
Add-Case 'P3-A14-opposite-decision' ($opposite.Status -eq 409 -and $same.Status -eq 200 -and (ExternalKeyFact $action.actionId 'p3-original-repeat-fact') -eq '1|1') 'opposite confirmation conflicts; same decision is idempotent, no external POST'
$otherRun=Approve $read.Run $action 'p3-existing-other-run-id'
$random=($action|ConvertTo-Json -Depth 6|ConvertFrom-Json);$random.actionId='act-'+[guid]::NewGuid().ToString('N')
$unknownId=Approve $read.Run $random 'p3-unknown-id'
Add-Case 'P3-A34-action-id-private' ($otherRun.Status -eq 404 -and $unknownId.Status -eq 404) 'existing other-run action and random action ID both return404'
$spoof=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($unknown.Run)/reconciliations/$($unknownAction.actionId)/query" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} '{"state":"SUCCEEDED","externalId":"fabricated"}' 20 'p3-spoof-proof'
Add-Case 'P3-A22-proof-not-client-input' ($spoof.Status -eq 400 -and (ExternalKeyFact $unknownAction.actionId 'p3-spoof-fact') -eq '1|1') 'manual success evidence is rejected; fixed provider QUERY is the only evidence source'
# A new requested business action needs a new proposal/key and confirmation.
$again=AgentSubmit 'p3-another-ticket' 'sandbox';[void](Wait-RunStatus $t1 $again.Run 'WAITING_APPROVAL' 120)
$another=TicketAction $again.Run 'p3-another-proposal';[void](Approve $again.Run $another 'p3-another-allow')
$againDone=Wait-RunStatus $t1 $again.Run 'SUCCEEDED' 120
$newKey=DbScalar "SELECT operation_key FROM ai_tool_call WHERE tenant_id='p2t1' AND action_id='$($another.actionId)';"
$oldKey=DbScalar "SELECT operation_key FROM ai_tool_call WHERE tenant_id='p2t1' AND action_id='$($action.actionId)';"
Add-Case 'P3-A27-new-request' ([bool]$againDone -and $newKey -ne $oldKey -and (ExternalKeyFact $another.actionId 'p3-new-ticket-fact') -eq '1|1') 'explicit new action has new approval/key, one additional external write'
$deny=AgentSubmit 'p3-deny-ticket' 'sandbox';[void](Wait-RunStatus $t1 $deny.Run 'WAITING_APPROVAL' 120)
$deniedAction=TicketAction $deny.Run 'p3-deny-proposal';[void](Approve $deny.Run $deniedAction 'p3-deny' 'DENY')
$deniedDone=Wait-RunStatus $t1 $deny.Run 'SUCCEEDED' 120
Add-Case 'P3-A14-deny' ([bool]$deniedDone -and (ExternalKeyFact $deniedAction.actionId 'p3-denied-absent') -eq 'ABSENT') 'explicit rejection resolves the proposal without an external POST'
$cancel=AgentSubmit 'p3-cancel-waiting' 'sandbox';[void](Wait-RunStatus $t1 $cancel.Run 'WAITING_APPROVAL' 120)
$cancelAction=TicketAction $cancel.Run 'p3-cancel-proposal'
$cancelled=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($cancel.Run)/cancel" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} '{}' 20 'p3-cancel-wait'
$cancelDone=Wait-RunStatus $t1 $cancel.Run 'CANCELLED' 60
$lateApproval=Approve $cancel.Run $cancelAction 'p3-approval-after-cancel'
Add-Case 'P3-A14-cancel-before-allow' ($cancelled.Status -eq 200 -and [bool]$cancelDone -and $lateApproval.Status -eq 409 -and (ExternalKeyFact $cancelAction.actionId 'p3-cancel-absent') -eq 'ABSENT') 'cancel wins before allow; no external write and no later queue'
# A frozen proposal expiry is injected only into this owned synthetic action.
$expired=AgentSubmit 'p3-expired-confirmation' 'sandbox';[void](Wait-RunStatus $t1 $expired.Run 'WAITING_APPROVAL' 120)
$expiredAction=TicketAction $expired.Run 'p3-expired-proposal'
[void](Sql "UPDATE ai_tool_call SET created_at=now()-interval '16 minutes' WHERE tenant_id='p2t1' AND action_id='$($expiredAction.actionId)' AND state='PROPOSED';" 'p3-inject-expired-proposal')
$expiryReject=Approve $expired.Run $expiredAction 'p3-expired-reject'
Add-Case 'P3-A16-expired-confirmation' ($expiryReject.Status -eq 409 -and (ExternalKeyFact $expiredAction.actionId 'p3-expiry-absent') -eq 'ABSENT') 'synthetic past deadline rejects confirmation before any write'
[void](Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($expired.Run)/cancel" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} '{}' 20 'p3-expiry-clean-cancel')
# Actual SDK whole-turn state save, stopped old worker, second JVM takeover, old worker revived.
[void](ClearFaults 'p2t1');[void](PauseHook 'agent.afterEngineSave')
$restore=AgentSubmit 'p3-state-takeover'
$saveHit=WaitHook 'agent.afterEngineSave' 'p3-engine-save-hit'
if(-not $saveHit){throw 'actual engine save window not observed'}
SignalOwned $AiPort 'STOP' 'p3-old-worker-sigstop'
try {
 Start-Jar 'ai' $Ai2Port $agentConfig|Out-Null
 if(-not(Wait-Ready $Ai2Port "$($script:RemoteRoot)/ai-$Ai2Port.log")){throw 'owned second Agent JVM unavailable'}
 $script:AiNodes=@($AiPort,$Ai2Port)
 $secondDone=$false
 for($i=0;$i -lt 60;$i++){
  $status=DbScalar "SELECT status FROM ai_run WHERE tenant_id='p2t1' AND run_id='$($restore.Run)';"
  if($status -eq 'SUCCEEDED'){$secondDone=$true;break}
  Start-Sleep -Milliseconds 500
 }
} finally { SignalOwned $AiPort 'CONT' 'p3-old-worker-sigcont' }
$restored=Wait-RunStatus $t1 $restore.Run 'SUCCEEDED' 60
$restoreProof=Sql "SELECT r.status,r.attempt,r.fence,(SELECT count(*) FROM ai_run_event WHERE tenant_id=r.tenant_id AND run_id=r.run_id AND event_type='run.terminal'),(SELECT count(*) FROM ai_model_call WHERE tenant_id=r.tenant_id AND run_id=r.run_id),(SELECT count(*) FROM ai_run_event WHERE tenant_id=r.tenant_id AND run_id=r.run_id AND event_type='agent.state_loaded'),(SELECT min(fence) FROM ai_agent_checkpoint WHERE tenant_id=r.tenant_id AND run_id=r.run_id) FROM ai_run r WHERE r.tenant_id='p2t1' AND r.run_id='$($restore.Run)';" 'p3-formal-takeover-proof'
Add-Case 'P3-A03-A06-real-state-takeover' ([bool]$restored -and $secondDone -and $restoreProof.Output -match 'SUCCEEDED\|[2-9][0-9]*\|[2-9][0-9]*\|1\|3\|[1-9][0-9]*\|[2-9][0-9]*') 'actual state SPI loaded by new JVM; app model/tool checkpoints reused with exactly three provider calls, one terminal, saved new fence'
Stop-OwnedJar $Ai2Port|Out-Null;$script:AiNodes=@($AiPort)
[void](ClearFaults 'p2t1')
$toolLimit=AgentSubmit 'p3-tool-limit' 'sandbox' '' '' '{"maxTokens":4000,"maxSteps":6,"maxToolCalls":1}'
$toolLimitDone=Wait-RunStatus $t1 $toolLimit.Run 'FAILED' 120
$toolLimitProof=Sql "SELECT r.error_code,a.tools_used,(SELECT count(*) FROM ai_tool_call WHERE tenant_id=r.tenant_id AND run_id=r.run_id AND tool_name='sandbox_ticket') FROM ai_run r JOIN ai_agent_run a USING(tenant_id,run_id) WHERE r.tenant_id='p2t1' AND r.run_id='$($toolLimit.Run)';" 'p3-tool-budget'
Add-Case 'P3-A08-tool-budget' ([bool]$toolLimitDone -and $toolLimitProof.Output.Trim() -eq 'BUDGET_EXCEEDED|1|0') 'tool call budget prevents the next proposal/write'
$tokens=AgentSubmit 'p3-token-limit' 'read' '' '' '{"maxTokens":10,"maxSteps":6,"maxToolCalls":6}'
$tokensDone=Wait-RunStatus $t1 $tokens.Run 'FAILED' 120
$tokensProof=Sql "SELECT r.error_code,a.tokens_used<=a.max_tokens FROM ai_run r JOIN ai_agent_run a USING(tenant_id,run_id) WHERE r.tenant_id='p2t1' AND r.run_id='$($tokens.Run)';" 'p3-token-budget'
Add-Case 'P3-A08-token-budget' ([bool]$tokensDone -and $tokensProof.Output.Trim() -eq 'BUDGET_EXCEEDED|t') 'cumulative tokens cannot exceed frozen run budget; received provider usage retained'
Save-Evidence 'p3-adversarial-results.json' ($script:Results|ConvertTo-Json -Depth 6)
