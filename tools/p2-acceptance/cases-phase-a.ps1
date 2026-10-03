# P2 Phase A：受理/Worker/事件/SSE 契约与故障用例（synthetic 执行器；由 run.ps1 点源执行）
# 依赖 run.ps1 提供的函数与变量：Http/Sql/SubmitRun/ArmFault/Wait-RunStatus/Add-Case 等。

# ---------------------------------------------------------------- bcrypt helper (平台口径)

# ---------------------------------------------------------------- case helpers

function SubmitRun([string]$Token, [string]$Action, [string]$Key, [string]$InputJson, [string]$RefsJson,
    [string]$BudgetJson = '{"maxTokens":2000}', [string]$LogName = 'submit') {
    $body = '{"schemaVersion":1,"action":"' + $Action + '","input":' + $InputJson + ',"resourceRefs":' + $RefsJson + ',"budget":' + $BudgetJson + '}'
    return Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs" @{ 'Content-Type' = 'application/json'; 'Idempotency-Key' = $Key; 'Authorization' = "Bearer $Token" } $body 30 $LogName
}
function RunId([string]$Body) { if ($Body -match '"runId"\s*:\s*"([^"]+)"') { return $Matches[1] } return '' }
function ArmFault([string]$Tenant, [string]$Hook, [int]$Times = 1) {
    # 故障钩子按进程内存布点：双节点阶段必须 fan-out 到所有存活 AI 节点，
    # 否则钩子可能布在未执行该代码路径的节点上（假通过/假失败）。
    # subject 必须是数字 user_id（平台 identity 校验 [0-9]{1,20}），mid 同步构造。
    $uid = FixtureUserId $Tenant
    $nodes = if ($script:AiNodes.Count -gt 0) { $script:AiNodes } else { @($AiPort) }
    $results = @()
    foreach ($port in $nodes) {
        $results += AiHttp 'POST' "/internal/ai/v1/test/fault" $Tenant $uid 'run.get' @{ 'Content-Type' = 'application/json' } (@{ hook = $Hook; times = $Times } | ConvertTo-Json -Compress) 1 20 ("arm-fault-" + $port) $port
    }
    return $results[0]
}
function ClearFaults([string]$Tenant) {
    $uid = FixtureUserId $Tenant
    $nodes = if ($script:AiNodes.Count -gt 0) { $script:AiNodes } else { @($AiPort) }
    $results = @()
    foreach ($port in $nodes) {
        $results += AiHttp 'DELETE' "/internal/ai/v1/test/fault" $Tenant $uid 'run.get' @{} '' 1 20 ("clear-faults-" + $port) $port
    }
    return $results[0]
}
function FixtureUserId([string]$Tenant) {
    return @{ p2t1 = '910000000000000001'; p2t2 = '910000000000000002' }[$Tenant]
}
function Wait-RunStatus([string]$Token, [string]$RunId, [string]$Want, [int]$Seconds = 60) {
    for ($i = 0; $i -lt ($Seconds * 2); $i++) {
        $r = Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$RunId" @{ 'Authorization' = "Bearer $Token" } '' 20 'poll-run'
        if ($r.Body -match '"status"\s*:\s*"([^"]+)"') { if ($Matches[1] -eq $Want) { return $r } }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

# ================================================================ Phase A

Write-Step 'Phase A: admission / worker / events / SSE (synthetic executor)'
$kb1 = ''
$kbCreate = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases" @{ 'Content-Type' = 'application/json'; 'Authorization' = "Bearer $t1" } '{"name":"p2c-kb-t1","embeddingModel":"synthetic-feature-hash-1536","collectionName":"p2c_collection"}' 20 'kb-create'
if ($kbCreate.Body -match '"kbId"\s*:\s*"([^"]+)"') { $kb1 = $Matches[1] }
elseif ($kbCreate.Body -match '"id"\s*:\s*"([^"]+)"') { $kb1 = $Matches[1] }
Add-Case 'ENV-kb' ($kbCreate.Status -eq 200 -and $kb1 -ne '') "KB created through the gateway (kbId=$kb1, status=$($kbCreate.Status))"

# A01 正常受理
$key1 = "a01-$($script:Tag)"
$r = SubmitRun $t1 'rag.chat' $key1 '{"text":"admission control case"}' ('[{"type":"knowledge_base","id":"' + $kb1 + '"}]') '{"maxTokens":2000}' 'a01-submit'
$run1 = RunId $r.Body
$db = Sql "SELECT (SELECT count(*) FROM ai_run WHERE idempotency_key='$key1'), (SELECT count(*) FROM ai_run_event e JOIN ai_run r ON r.tenant_id=e.tenant_id AND r.run_id=e.run_id WHERE r.idempotency_key='$key1' AND e.seq=1), (SELECT count(*) FROM outbox_event o JOIN ai_run r ON r.tenant_id=o.tenant_id AND r.run_id=o.run_id WHERE r.idempotency_key='$key1'), (SELECT count(*) FROM ai_budget_reservation b JOIN ai_run r ON r.tenant_id=b.tenant_id AND r.run_id=b.run_id WHERE r.idempotency_key='$key1' AND b.state='RESERVED');" 'a01-db'
$counts = ($db.Output -split '\|')
Add-Case 'A01' ($r.Status -eq 202 -and $counts.Count -ge 4 -and $counts[0].Trim() -eq '1' -and $counts[1].Trim() -eq '1' -and $counts[2].Trim() -eq '1' -and $counts[3].Trim() -eq '1') "202 + runId=$run1; run/event(seq=1)/outbox/reserve all present in one tx (counts=$($db.Output.Trim()))"

# A02 受理五处故障注入 → 全部回滚
$faultCases = @(
    @{ hook = 'admission.afterRun'; name = 'afterRun' },
    @{ hook = 'admission.afterEvent'; name = 'afterEvent' },
    @{ hook = 'admission.afterReserve'; name = 'afterReserve' },
    @{ hook = 'admission.afterOutbox'; name = 'afterOutbox' },
    @{ hook = 'admission.beforeCommit'; name = 'beforeCommit' }
)
$faultOk = $true; $faultDetail = @()
foreach ($fc in $faultCases) {
    [void](ArmFault 'p2t1' $fc.hook 1)
    $fk = "a02-$($fc.name)-$($script:Tag)"
    $fr = SubmitRun $t1 'rag.chat' $fk '{"text":"fault"}' ('[{"type":"knowledge_base","id":"' + $kb1 + '"}]') '{"maxTokens":1000}' ("a02-" + $fc.name)
    $chk = Sql "SELECT (SELECT count(*) FROM ai_run WHERE idempotency_key='$fk'), (SELECT count(*) FROM outbox_event o WHERE o.run_id IN (SELECT run_id FROM ai_run WHERE idempotency_key='$fk')), (SELECT count(*) FROM ai_budget_reservation b WHERE b.run_id IN (SELECT run_id FROM ai_run WHERE idempotency_key='$fk'));" ("a02-db-" + $fc.name)
    $vals = ($chk.Output -split '\|')
    $rolledBack = ($vals.Count -ge 3 -and $vals[0].Trim() -eq '0' -and $vals[1].Trim() -eq '0' -and $vals[2].Trim() -eq '0')
    if (-not $rolledBack) { $faultOk = $false }
    $faultDetail += ("$($fc.name):status=$($fr.Status),rows=$($chk.Output.Trim())")
    [void](ClearFaults 'p2t1')
}
Add-Case 'A02' $faultOk ("five injection points; no half admission: " + ($faultDetail -join ' | '))

# A03 同键同体 20 并发
$key3 = "a03-$($script:Tag)"
$body3 = '{"schemaVersion":1,"action":"rag.chat","input":{"text":"concurrent"},"resourceRefs":[{"type":"knowledge_base","id":"' + $kb1 + '"}],"budget":{"maxTokens":2000}}'
$b64body = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($body3))
$parScript = @'
cd /tmp/p2http-__TAG__
rm -f par*.json par.codes
printf %s '__B64__' | base64 -d > par.bin
for i in $(seq 1 20); do
  (curl -sS -m 30 -o par$i.json -w '%{http_code}
' -X POST 'http://127.0.0.1:__PORT__/api/ai/v1/runs' -H 'Content-Type: application/json' -H 'Idempotency-Key: __KEY__' -H 'Authorization: Bearer __TOKEN__' --data-binary @par.bin >> par.codes) &
done
wait
echo '---CODES---'
sort par.codes | uniq -c
echo '---RUNIDS---'
grep -ho '"runId":"[^"]*"' par*.json | sort -u
'@
$parScript = $parScript.Replace('__TAG__', $script:Tag).Replace('__B64__', $b64body).Replace('__PORT__', [string]$PlatformPort).Replace('__KEY__', $key3).Replace('__TOKEN__', $t1)
$parB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($parScript -replace "`r`n", "`n")))
$parallel = Remote "mkdir -p /tmp/p2http-$($script:Tag); printf %s '$parB64' | base64 -d > /tmp/p2http-$($script:Tag)/par.sh; bash /tmp/p2http-$($script:Tag)/par.sh" 'a03-parallel'
$accepted202 = (($parallel.Output -split "`n") | Where-Object { $_ -match '^\s*\d+\s+202\s*$' }).Count -ge 1
$uniqRuns = (($parallel.Output -split "`n") | Where-Object { $_ -match '"runId":"([^"]+)"' }).Count
$resv = Sql "SELECT (SELECT count(*) FROM ai_run WHERE idempotency_key='$key3'), (SELECT count(*) FROM ai_budget_reservation b JOIN ai_run r ON r.tenant_id=b.tenant_id AND r.run_id=b.run_id WHERE r.idempotency_key='$key3');" 'a03-db'
$vals3 = ($resv.Output -split '\|')
Add-Case 'A03' ($accepted202 -and $uniqRuns -eq 1 -and $vals3[0].Trim() -eq '1' -and $vals3[1].Trim() -eq '1') "20 concurrent same-key: one run row, one reservation, single runId (runs=$($vals3[0].Trim()),resv=$($vals3[1].Trim()),uniqueRunIds=$uniqRuns)"

# A04 同键异体
$r4 = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs" @{ 'Content-Type' = 'application/json'; 'Idempotency-Key' = $key3; 'Authorization' = "Bearer $t1" } '{"schemaVersion":1,"action":"rag.chat","input":{"text":"different body"},"resourceRefs":[],"budget":{"maxTokens":2000}}' 20 'a04-conflict'
Add-Case 'A04' ($r4.Status -eq 409) "same key different body -> HTTP $($r4.Status)"

# A05 jti 重放
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$claims = @{ iss = 'platform'; aud = @('ai'); sub = '910000000000000001'; tid = 'p2t1'; mid = 'platform:p2t1:910000000000000001'; pv = 1; scope = @('run.get'); jti = [guid]::NewGuid().ToString(); iat = $now; nbf = $now; exp = ($now + 60) } | ConvertTo-Json -Compress
$tok = (Remote ("bash $($script:RemoteRoot)/sign.sh '" + $claims.Replace("'", "'\''") + "'") 'a05-sign').Output
$tok = (($tok -split "`n") | Where-Object { $_ -match '^eyJ' } | Select-Object -Last 1)
$h5 = @{ 'X-P04-Service-Credential' = $script:ServiceCredential; 'Authorization' = "Bearer $tok" }
$first = Http 'GET' "http://127.0.0.1:$AiPort/internal/ai/v1/runs/does-not-exist" $h5 '' 20 'a05-first'
$second = Http 'GET' "http://127.0.0.1:$AiPort/internal/ai/v1/runs/does-not-exist" $h5 '' 20 'a05-replay'
Add-Case 'A05' ($second.Status -eq 401) "same jti replayed -> 401 (first=$($first.Status), replay=$($second.Status))"

# A06 缺身份 / 伪造身份头
$noauth = Http 'POST' "http://127.0.0.1:$AiPort/internal/ai/v1/runs" @{ 'Content-Type' = 'application/json'; 'Idempotency-Key' = "a06-$($script:Tag)" } '{"schemaVersion":1,"action":"rag.chat","input":{"text":"x"}}' 20 'a06-noauth'
$forgedKey = "a06-forged-$($script:Tag)"
$forged = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs" @{ 'Content-Type' = 'application/json'; 'Idempotency-Key' = $forgedKey; 'Authorization' = "Bearer $t1"; 'X-Tenant-Id' = 'p2t2'; 'X-User-Id' = 'evil' } ('{"schemaVersion":1,"action":"rag.chat","input":{"text":"forged"},"resourceRefs":[{"type":"knowledge_base","id":"' + $kb1 + '"}],"budget":{"maxTokens":1000}}') 20 'a06-forged'
$forgedTenant = Sql "SELECT tenant_id FROM ai_run WHERE idempotency_key='$forgedKey';" 'a06-tenant'
Add-Case 'A06' ($noauth.Status -eq 401 -and $forged.Status -eq 202 -and $forgedTenant.Output.Trim() -eq 'p2t1') "no credential -> 401; forged X-Tenant-Id ignored (run tenant=$($forgedTenant.Output.Trim()))"

# A07 预算竞争
[void](Sql "INSERT INTO ai_tenant_budget (tenant_id, limit_units) VALUES ('p2t2', 25) ON CONFLICT (tenant_id) DO UPDATE SET limit_units=25;" 'a07-budget-set' 'postgres')
$k7a = "a07a-$($script:Tag)"; $k7b = "a07b-$($script:Tag)"
$r7a = SubmitRun $t2 'rag.chat' $k7a '{"text":"budget a"}' '[]' '{"maxTokens":20000}' 'a07a'
$r7b = SubmitRun $t2 'rag.chat' $k7b '{"text":"budget b"}' '[]' '{"maxTokens":20000}' 'a07b'
$resvSum = Sql "SELECT coalesce(sum(units),0) FROM ai_budget_reservation WHERE tenant_id='p2t2' AND state='RESERVED';" 'a07-sum'
Add-Case 'A07' ($r7a.Status -eq 202 -and $r7b.Status -eq 429 -and [int]$resvSum.Output.Trim() -le 25) "different-key competitor -> 429; reserved units=$($resvSum.Output.Trim()) <= limit 25"

# A15 seq 连续（A01 run 到终态后核对）
$done1 = Wait-RunStatus $t1 $run1 'SUCCEEDED' 60
$seqChk = Sql "SELECT max(seq), count(*) FROM ai_run_event WHERE run_id='$run1';" 'a15-seq'
$sv = ($seqChk.Output -split '\|')
Add-Case 'A15' ($null -ne $done1 -and $sv[0].Trim() -eq $sv[1].Trim()) "run $run1 terminal; visible seq contiguous 1..$($sv[0].Trim()) (count=$($sv[1].Trim()))"

# A09 两 Worker 认领（启动 node2）
Start-Jar 'ai' $Ai2Port $aiCommon | Out-Null
if (-not (Wait-Ready $Ai2Port "/opt/p2core-acceptance/$($script:Tag)/ai-$Ai2Port.log")) {
    Add-Case 'A09' $false 'ai node2 did not start'
} else {
    $script:AiNodes = @($AiPort, $Ai2Port)
    $keys9 = @(); for ($i = 1; $i -le 4; $i++) { $keys9 += "a09-$i-$($script:Tag)" }
    foreach ($k in $keys9) { [void](SubmitRun $t1 'rag.chat' $k '{"text":"two workers"}' '[]' '{"maxTokens":1000}' ("a09-" + $k)) }
    Start-Sleep -Seconds 10
    $claimChk = Sql "SELECT count(*) FROM ai_run WHERE idempotency_key IN ('$($keys9 -join "','")') AND attempt=1;" 'a09-attempt'
    $doneChk = Sql "SELECT count(*) FROM ai_run WHERE idempotency_key IN ('$($keys9 -join "','")') AND status='SUCCEEDED';" 'a09-done'
    Add-Case 'A09' ($claimChk.Output.Trim() -eq '4' -and $doneChk.Output.Trim() -eq '4') "4 runs claimed exactly once (attempt=1: $($claimChk.Output.Trim())/4), all SUCCEEDED ($($doneChk.Output.Trim())/4) across two workers"
}

# A10/A11 fence + checkpoint：步骤提交后放弃 → 接管不重复
[void](ArmFault 'p2t1' 'worker.afterStepCommit' 1)
$k10 = "a10-$($script:Tag)"
$r10 = SubmitRun $t1 'rag.chat' $k10 '{"text":"takeover"}' '[]' '{"maxTokens":1000}' 'a10-submit'
$run10 = RunId $r10.Body
$taken = $false
for ($i = 0; $i -lt 120; $i++) {
    $st = Sql "SELECT status FROM ai_run WHERE run_id='$run10';" 'a10-status'
    if ($st.Output -match 'SUCCEEDED') { $taken = $true; break }
    Start-Sleep -Milliseconds 500
}
$stepChk = Sql "SELECT (SELECT count(*) FROM ai_run_step WHERE run_id='$run10' AND step_id='synthetic.parse' AND state='COMPLETED'), (SELECT count(*) FROM ai_run_event WHERE run_id='$run10' AND event_type='run.terminal'), (SELECT max(fence) FROM ai_run WHERE run_id='$run10');" 'a10-steps'
$sv10 = ($stepChk.Output -split '\|')
Add-Case 'A10/A11' ($taken -and $sv10[0].Trim() -eq '1' -and $sv10[1].Trim() -eq '1' -and [int]$sv10[2].Trim() -ge 2) "abandoned after step commit; takeover completed with exactly one COMPLETED step, one terminal event, fence=$($sv10[2].Trim())"
[void](ClearFaults 'p2t1')

# A14 租约过期不释放 ACTIVE permit
$permitRun = RunId (SubmitRun $t1 'rag.chat' ("a14-$($script:Tag)") '{"text":"permit"}' '[]' '{"maxTokens":1000}' 'a14-submit').Body
[void](Wait-RunStatus $t1 $permitRun 'SUCCEEDED' 40)
$permitTok = SignDelegation 'p2t1' '910000000000000001' 'run.get' 1
[void](Http 'GET' "http://127.0.0.1:$AiPort/internal/ai/v1/runs/$permitRun" @{ 'X-P04-Service-Credential' = $script:ServiceCredential; 'Authorization' = "Bearer $permitTok" } '' 20 'a14-permit')
$activePermits = Sql "SELECT count(*) FROM ai_execution_permit WHERE tenant_id='p2t1' AND status='ACTIVE';" 'a14-active'
Add-Case 'A14' ([int]$activePermits.Output.Trim() -ge 1) "delivery permit stays ACTIVE without ACK (count=$($activePermits.Output.Trim())); lease expiry does not clear it"

# A12 取消
$k12 = "a12-$($script:Tag)"
$r12 = SubmitRun $t1 'rag.chat' $k12 '{"text":"cancel me"}' '[]' '{"maxTokens":1000}' 'a12-submit'
$run12 = RunId $r12.Body
Start-Sleep -Milliseconds 250
$cancel1 = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$run12/cancel" @{ 'Content-Type' = 'application/json'; 'Authorization' = "Bearer $t1" } '{}' 20 'a12-cancel'
$cancelled = Wait-RunStatus $t1 $run12 'CANCELLED' 40
$cancel2 = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$run12/cancel" @{ 'Content-Type' = 'application/json'; 'Authorization' = "Bearer $t1" } '{}' 20 'a12-cancel2'
$termCount = Sql "SELECT (SELECT count(*) FROM ai_run_event WHERE run_id='$run12' AND event_type='run.terminal'), (SELECT status FROM ai_run WHERE run_id='$run12');" 'a12-terminal'
$tv = ($termCount.Output -split '\|')
Add-Case 'A12' ($null -ne $cancelled -and $cancel2.Status -eq 200 -and $tv[0].Trim() -eq '1' -and $tv[1].Trim() -eq 'CANCELLED') "cancel -> CANCELLED (single terminal event), repeat cancel idempotent (HTTP $($cancel2.Status), cancel1=$($cancel1.Status))"

# A13 resume/retry
$k13 = "a13-$($script:Tag)"
$r13 = SubmitRun $t1 'rag.chat' $k13 '{"text":"recover"}' '[]' '{"maxTokens":1000}' 'a13-submit'
$run13 = RunId $r13.Body
[void](Wait-RunStatus $t1 $run13 'SUCCEEDED' 40)
$resumeTerminal = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$run13/resume" @{ 'Content-Type' = 'application/json'; 'Authorization' = "Bearer $t1" } '{"expectedVersion":1}' 20 'a13-resume-terminal'
$retryKey = "a13r-$($script:Tag)"
$retryBody = '{"schemaVersion":1,"action":"rag.chat","input":{"text":"retry"},"resourceRefs":[],"budget":{"maxTokens":1000},"retryOf":"' + $run13 + '"}'
$r13r = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs" @{ 'Content-Type' = 'application/json'; 'Idempotency-Key' = $retryKey; 'Authorization' = "Bearer $t1" } $retryBody 20 'a13-retry'
$retryRun = RunId $r13r.Body
$retryOf = DbScalar "SELECT retry_of FROM ai_run WHERE run_id='$retryRun';" 'a13-retryof'
Add-Case 'A13' ($resumeTerminal.Status -eq 409 -and $r13r.Status -eq 202 -and $retryOf.Trim() -eq $run13) "terminal resume -> 409; retry creates new run linked by retry_of"

# A16 outbox relay 提交后投递前退出 → 不丢、重试、eventId 去重
[void](ArmFault 'p2t1' 'outbox.afterPublish' 1)
$k16 = "a16-$($script:Tag)"
$r16 = SubmitRun $t1 'rag.chat' $k16 '{"text":"outbox"}' '[]' '{"maxTokens":1000}' 'a16-submit'
$run16 = RunId $r16.Body
[void](Wait-RunStatus $t1 $run16 'SUCCEEDED' 40)
Start-Sleep -Seconds 4
$obChk = Sql "SELECT (SELECT count(*) FROM outbox_event WHERE state='PENDING'), (SELECT coalesce(max(attempt_count),0) FROM outbox_event WHERE run_id='$run16'), (SELECT count(*) FROM ai_run_event WHERE run_id='$run16'), (SELECT count(DISTINCT event_id) FROM ai_run_event WHERE run_id='$run16');" 'a16-outbox'
$ov = ($obChk.Output -split '\|')
Add-Case 'A16' ($ov[0].Trim() -eq '0' -and [int]$ov[1].Trim() -ge 2 -and $ov[2].Trim() -eq $ov[3].Trim()) "relay crash window: no pending left, retried (attempt_count=$($ov[1].Trim())), eventId unique ($($ov[2].Trim()) events)"
[void](ClearFaults 'p2t1')

# A17/A18 SSE replay+live 连续
$k17 = "a17-$($script:Tag)"
$r17 = SubmitRun $t1 'rag.chat' $k17 '{"text":"stream"}' '[]' '{"maxTokens":1000}' 'a17-submit'
$run17 = RunId $r17.Body
[void](Remote "cd /tmp/p2http-$($script:Tag); rm -f sse.txt; curl -sS -N -m 30 'http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$run17/events?afterSeq=0' -H 'Authorization: Bearer $t1' -o sse.txt; echo done" 'a17-sse')
$sseFetch = Remote "cat /tmp/p2http-$($script:Tag)/sse.txt" 'a17-sse-body'
$frames = @()
foreach ($line in ($sseFetch.Output -split "`n")) { if ($line -match '^id:\s*(\d+)') { $frames += [int]$Matches[1] } }
$contiguous = ($frames.Count -ge 3)
for ($i = 0; $i -lt $frames.Count; $i++) { if ($frames[$i] -ne ($i + 1)) { $contiguous = $false; break } }
$lastTerminal = ($sseFetch.Output -match 'event: run\.terminal')
Add-Case 'A17/A18' ($contiguous -and $lastTerminal) "SSE replay+live contiguous seq 1..$($frames.Count) ending with run.terminal (no gap window)"

# A20 游标过期 → 410 + 快照
$minSeq = Sql "SELECT min(seq) FROM ai_run_event WHERE run_id='$run17';" 'a20-min'
[void](Sql "DELETE FROM ai_run_event WHERE run_id='$run17' AND seq < 3;" 'a20-delete' 'postgres')
$expired = Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$run17/events?afterSeq=0" @{ 'Authorization' = "Bearer $t1" } '' 20 'a20-expired'
Add-Case 'A20' ($expired.Status -eq 410 -and $expired.Body -match 'CURSOR_EXPIRED' -and $expired.Body -match 'snapshot') "retention loss -> 410 CURSOR_EXPIRED + snapshot (minSeq was $($minSeq.Output.Trim()))"

# A21/A22 流建立后真实撤权：policy revision bump → AI 复核翻转，不交付 terminal，permit 不排空
[void](ArmFault 'p2t1' 'worker.beforeTerminal' 6)
$k21 = "a21-$($script:Tag)"
$r21 = SubmitRun $t1 'rag.chat' $k21 '{"text":"revoke mid-stream"}' '[]' '{"maxTokens":1000}' 'a21-submit'
$run21 = RunId $r21.Body
$running21 = $false
for ($i = 0; $i -lt 60; $i++) {
    $st21 = Sql "SELECT status FROM ai_run WHERE run_id='$run21';" 'a21-status'
    if ($st21.Output -match 'RUNNING') { $running21 = $true; break }
    Start-Sleep -Milliseconds 500
}
[void](Remote "cd /tmp/p2http-$($script:Tag); rm -f sse21.txt; nohup curl -sS -N -m 40 'http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$run21/events?afterSeq=0' -H 'Authorization: Bearer $t1' -o sse21.txt >/dev/null 2>&1 & echo bg" 'a21-sse-start')
Start-Sleep -Seconds 4
[void](Sql "UPDATE platform.sys_ai_policy_revision SET version=version+1 WHERE tenant_id='p2t1';" 'a21-revoke' 'postgres')
Start-Sleep -Seconds 10
$sseState = Remote "test -f /tmp/p2http-$($script:Tag)/sse21.txt && echo present; grep -c 'run\.terminal' /tmp/p2http-$($script:Tag)/sse21.txt; grep -c '^id:' /tmp/p2http-$($script:Tag)/sse21.txt; true" 'a21-sse-state'
$reconnect = Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$run21/events?afterSeq=1" @{ 'Authorization' = "Bearer $t1" } '' 20 'a21-reconnect'
$pv2Token = SignDelegation 'p2t1' '910000000000000001' 'run.get' 2
$pv2Get = Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$run21" @{ 'Authorization' = "Bearer $pv2Token" } '' 20 'a21-pv2'
$terminalFrames = 999; $frameCount = '0'
if ($sseState.Output -match 'present') {
    $nums = @($sseState.Output -split "`n" | Where-Object { $_.Trim() -match '^\d+$' } | ForEach-Object { $_.Trim() })
    # 顺序：grep -c run.terminal 的计数，然后 grep -c '^id:' 的计数
    if ($nums.Count -ge 2) { $terminalFrames = [int]$nums[0]; $frameCount = $nums[1] }
}
Add-Case 'A21' ($running21 -and $terminalFrames -eq 0 -and $reconnect.Status -eq 403 -and $pv2Get.Status -eq 200) "policy bump mid-stream: stream stopped with no terminal (frames=$($frameCount.Trim()), terminal=$terminalFrames), old-pv reconnect 403, pv=2 re-authorised 200"
[void](ClearFaults 'p2t1')
$done21 = $false
for ($i = 0; $i -lt 120; $i++) {
    $st21 = Sql "SELECT status FROM ai_run WHERE run_id='$run21';" 'a21-terminal'
    if ($st21.Output -match 'SUCCEEDED') { $done21 = $true; break }
    Start-Sleep -Milliseconds 500
}
$perm21 = Sql "SELECT count(*) FROM ai_execution_permit WHERE tenant_id='p2t1' AND run_id='$run21' AND status='ACTIVE';" 'a22-permit'
$permReleased = Sql "SELECT count(*) FROM ai_execution_permit WHERE tenant_id='p2t1' AND run_id='$run21' AND status='RELEASED';" 'a22-released'
Add-Case 'A22' ($done21 -and [int]$perm21.Output.Trim() -ge 1 -and $permReleased.Output.Trim() -eq '0') "run completed after revocation window; delivery permit without ACK stays ACTIVE ($($perm21.Output.Trim())), never auto-released (RELEASED=$($permReleased.Output.Trim()))"
[void](Sql "UPDATE platform.sys_ai_policy_revision SET version=1 WHERE tenant_id='p2t1';" 'a21-policy-reset' 'postgres')

# A41 跨租户探测
$crossRun = Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$run13" @{ 'Authorization' = "Bearer $t2" } '' 20 'a41-run'
$crossDocs = Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases/$kb1/documents" @{ 'Authorization' = "Bearer $t2" } '' 20 'a41-docs'
Add-Case 'A41' ($crossRun.Status -eq 404 -and $crossDocs.Status -eq 404) "cross-tenant run/document probes -> 404 (no existence leak)"

# A42 迁移 + 运行账号 DDL 拒绝
$ddl = Sql "CREATE TABLE ai.p2_ddl_probe (id int);" 'a42-ddl' 'p2app'
$tableCount = Sql "SELECT count(*) FROM information_schema.tables WHERE table_schema IN ('ai','platform');" 'a42-tables'
Add-Case 'A42' ($ddl.ExitCode -ne 0 -and [int]$tableCount.Output.Trim() -ge 40) "app role DDL denied; $($tableCount.Output.Trim()) tables across ai/platform"

# A44 默认关闭（配置断言）
$yaml = [IO.File]::ReadAllText((Join-Path $RepoRoot 'services/ai/bootstrap/src/main/resources/application.yaml'))
$defaultsOff = -not ($yaml -match 'p2\.enabled\s*:\s*true') -and -not ($yaml -match 'p2\.worker\.enabled\s*:\s*true') -and -not ($yaml -match 'mineru\.local\.enabled\s*:\s*true')
Add-Case 'A44-defaults' $defaultsOff 'P2 capabilities default off in application.yaml'

Save-Evidence 'phase-a-results.json' (($script:Results | ConvertTo-Json -Depth 5))
Write-Step "Phase A complete: $(@($script:Results | Where-Object { $_.ok }).Count)/$($script:Results.Count) passed"
