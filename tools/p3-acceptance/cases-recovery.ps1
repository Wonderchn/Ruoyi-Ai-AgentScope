# Formal recovery cases; operates only inside the runner's verified owned namespace.
Write-Step 'P3 recovery controls: successful-write failed-summary / unknown inheritance / late receipt'
function ResumeAgent([string]$Run,[string]$Name) {
 $snapshot=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$Run" @{'Authorization'="Bearer $t1"} '' 20 ($Name+'-snapshot')
 return Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$Run/resume" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} (@{expectedVersion=(($snapshot.Body|ConvertFrom-Json).data.version)}|ConvertTo-Json -Compress) 20 $Name
}
$summary=AgentSubmit 'p3-summary-failure' 'sandbox' '' '' '{"maxTokens":4000,"maxSteps":2,"maxToolCalls":6}'
[void](Wait-RunStatus $t1 $summary.Run 'WAITING_APPROVAL' 120)
$summaryAction=TicketAction $summary.Run 'p3-summary-proposal'
[void](Approve $summary.Run $summaryAction 'p3-summary-allow')
$summaryFailed=Wait-RunStatus $t1 $summary.Run 'FAILED' 120
$summaryProof=DbScalar "SELECT r.error_code,t.state FROM ai_run r JOIN ai_tool_call t USING(tenant_id,run_id) WHERE r.tenant_id='p2t1' AND r.run_id='$($summary.Run)' AND t.tool_name='sandbox_ticket';"
$summaryRetry=AgentSubmit 'p3-summary-inherit' 'sandbox' $summary.Run $summaryAction.actionId
$summaryDone=Wait-RunStatus $t1 $summaryRetry.Run 'SUCCEEDED' 120
$summaryResult=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($summaryRetry.Run)" @{'Authorization'="Bearer $t1"} '' 20 'p3-summary-inherited-result'
Add-Case 'P3-A25-write-success-summary-failure' ([bool]$summaryFailed -and $summaryProof -eq 'BUDGET_EXCEEDED|SUCCEEDED' -and [bool]$summaryDone -and $summaryResult.Body -match 'inheritedFrom' -and (ExternalKeyFact $summaryAction.actionId 'p3-summary-external') -eq '1|1') 'failed summary keeps successful external action; new run inherits verified receipt without another POST'

[void](SandboxControl '{"responseLost":1,"queryAbsent":1}' 'p3-inherit-arm-loss')
$pendingSource=AgentSubmit 'p3-pending-source' 'sandbox';[void](Wait-RunStatus $t1 $pendingSource.Run 'WAITING_APPROVAL' 120)
$pendingAction=TicketAction $pendingSource.Run 'p3-pending-proposal';[void](Approve $pendingSource.Run $pendingAction 'p3-pending-allow')
$sourceNeeds=Wait-RunStatus $t1 $pendingSource.Run 'NEEDS_RECONCILIATION' 120
$ordinary=AgentSubmit 'p3-nonterminal-ordinary-retry' 'sandbox' $pendingSource.Run
$pendingRetry=AgentSubmit 'p3-pending-inherit' 'sandbox' $pendingSource.Run $pendingAction.actionId
$retryNeeds=Wait-RunStatus $t1 $pendingRetry.Run 'NEEDS_RECONCILIATION' 120
$pendingProof=DbScalar "SELECT (SELECT count(*) FROM ai_tool_call WHERE tenant_id='p2t1' AND run_id='$($pendingRetry.Run)' AND tool_name='sandbox_ticket'),(SELECT count(*) FROM ai_action_inheritance WHERE tenant_id='p2t1' AND new_run_id='$($pendingRetry.Run)');"
$pendingAbsent=QueryAction $pendingSource.Run $pendingAction 'p3-pending-absent'
$pendingFound=QueryAction $pendingSource.Run $pendingAction 'p3-pending-found'
$pendingResume=ResumeAgent $pendingRetry.Run 'p3-pending-new-run-resume'
$pendingDone=Wait-RunStatus $t1 $pendingRetry.Run 'SUCCEEDED' 120
Add-Case 'P3-A26-unknown-source-inheritance' ([bool]$sourceNeeds -and $ordinary.Status -eq 409 -and $pendingRetry.Status -eq 202 -and [bool]$retryNeeds -and $pendingProof -eq '0|1' -and $pendingAbsent.Body -match 'UNKNOWN' -and $pendingFound.Body -match 'FOUND' -and $pendingResume.Status -eq 200 -and [bool]$pendingDone -and (ExternalKeyFact $pendingAction.actionId 'p3-pending-external') -eq '1|1') 'unknown source suspends inherited run; nonfinal absence never rePOSTs; verified source receipt resumes mapped run'
[void](Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($pendingSource.Run)/cancel" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} '{}' 20 'p3-pending-source-close')

# Observe the independent DB write and STARTED action before cancelling, in one remote process.
[void](SandboxControl '{"delayMs":10000}' 'p3-late-arm-delay')
$late=AgentSubmit 'p3-late-cancel' 'sandbox';[void](Wait-RunStatus $t1 $late.Run 'WAITING_APPROVAL' 120)
$lateAction=TicketAction $late.Run 'p3-late-proposal'
$lateKey=DbScalar "SELECT operation_key FROM ai_tool_call WHERE tenant_id='p2t1' AND action_id='$($lateAction.actionId)';"
[void](Approve $late.Run $lateAction 'p3-late-allow')
$witnessCommand=@"
set -eu
for n in `$(seq 1 60); do
 count=`$(docker exec $($script:SandboxName) python3 -c 'import sqlite3;c=sqlite3.connect("/data/sandbox.sqlite"); print(c.execute("select count(*) from tickets where operation_key=?",("$lateKey",)).fetchone()[0])')
 if test "`$count" = 1; then
   state=`$(docker exec $($script:PgName) psql -U postgres -d $($script:Db) -X -q -tA -v ON_ERROR_STOP=1 -c "select state from ai.ai_tool_call where tenant_id='p2t1' and action_id='$($lateAction.actionId)';")
   date -u +%FT%T.%NZ
   echo BEFORE_CANCEL=`$state
   test "`$state" = STARTED || exit 21
   curl -sS -m 10 -o /tmp/p2http-$($script:Tag)/late-cancel.json -w 'CANCEL_HTTP=%{http_code}
' -X POST 'http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($late.Run)/cancel' -H 'Authorization: Bearer $t1' -H 'clientid: p2c-client' -H 'Content-Type: application/json' --data '{}'
   date -u +%FT%T.%NZ
   cat /tmp/p2http-$($script:Tag)/late-cancel.json
   exit 0
 fi
 sleep 0.05
done
exit 22
"@
$lateWitness=Remote $witnessCommand 'p3-late-inflight-witness'
$lateCancelled=Wait-RunStatus $t1 $late.Run 'CANCELLED' 60
Start-Sleep -Seconds 10
$lateFound=QueryAction $late.Run $lateAction 'p3-late-terminal-query'
$lateProof=DbScalar "SELECT r.status,t.state,(SELECT count(*) FROM ai_run_event WHERE tenant_id=r.tenant_id AND run_id=r.run_id AND event_type='run.terminal') FROM ai_run r JOIN ai_tool_call t USING(tenant_id,run_id) WHERE r.tenant_id='p2t1' AND r.run_id='$($late.Run)' AND t.tool_name='sandbox_ticket';"
Add-Case 'P3-A23-A24-inflight-cancel-late-found' ($lateWitness.ExitCode -eq 0 -and $lateWitness.Output -match 'BEFORE_CANCEL=STARTED' -and $lateWitness.Output -match 'CANCEL_HTTP=200' -and [bool]$lateCancelled -and $lateFound.Body -match 'FOUND' -and $lateProof -eq 'CANCELLED|SUCCEEDED|1' -and (ExternalKeyFact $lateAction.actionId 'p3-late-external') -eq '1|1') 'observed STARTED plus committed external row before cancel; late verified receipt preserves cancelled terminal and actual write once'
[void](SandboxControl '{}' 'p3-late-clear-control')
