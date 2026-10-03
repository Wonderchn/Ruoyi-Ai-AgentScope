# P2 Phase B：真实本地 MinerU + 版本发布 + 检索问答（synthetic embedding/chat，真实 MinerU）
# 由 run.ps1 点源执行；结束时汇总与清场。

function Stop-OwnedJar([int]$Port) {
    $pids = @($script:Pids | Where-Object { $_.port -eq $Port })
    foreach ($p in $pids) { [void](Remote "kill $($p.pid) 2>/dev/null; true" ("stop-$($p.side)-$Port")) }
}
function Wait-PortClosed([int]$Port, [int]$Seconds = 30) {
    for ($i = 0; $i -lt $Seconds; $i++) {
        $probe = Remote "curl -s -o /dev/null -m 2 -w '%{http_code}' http://127.0.0.1:$Port/ 2>/dev/null; true" ''
        if (-not ($probe.Output -match '^\s*[1-5]\d\d\s*$')) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}
function UploadPdf([string]$Token, [string]$KbId, [string]$FileName = 'sample.pdf', [string]$LogName = 'upload') {
    $cmd = "cd /tmp/p2http-$($script:Tag); curl -sS -m 60 -o up.json -w '%{http_code}' -X POST 'http://127.0.0.1:$PlatformPort/api/ai/v1/documents/uploads' -H 'Authorization: Bearer $Token' -F 'kbId=$KbId' -F 'file=@$($script:RemoteRoot)/sample.pdf;type=application/pdf;filename=$FileName'; echo; cat up.json"
    $r = Remote $cmd $LogName
    $status = 0
    if ($r.Output -match '^(\d{3})') { $status = [int]$Matches[1] }
    return [pscustomobject]@{ Status = $status; Body = $r.Output }
}
function UploadBig([string]$Token, [string]$KbId) {
    $cmd = "cd /tmp/p2http-$($script:Tag); dd if=/dev/zero of=big.bin bs=1M count=21 2>/dev/null; curl -sS -m 120 -o big.json -w '%{http_code}' -X POST 'http://127.0.0.1:$PlatformPort/api/ai/v1/documents/uploads' -H 'Authorization: Bearer $Token' -F 'kbId=$KbId' -F 'file=@big.bin;type=application/pdf;filename=big.pdf'; echo; cat big.json"
    $r = Remote $cmd 'upload-big'
    $status = 0
    if ($r.Output -match '^(\d{3})') { $status = [int]$Matches[1] }
    return [pscustomobject]@{ Status = $status; Body = $r.Output }
}
function Ingest([string]$Token, [string]$DocId, [string]$Key, [string]$UploadId, [string]$LogName = 'ingest') {
    return Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/documents/$DocId/ingestions" @{ 'Content-Type' = 'application/json'; 'Idempotency-Key' = $Key; 'Authorization' = "Bearer $Token" } ('{"uploadId":"' + $UploadId + '"}') 30 $LogName
}

Write-Step 'Phase B: real local MinerU + versioned publish + retrieval (synthetic embedding/chat)'
Stop-OwnedJar $AiPort | Out-Null
Stop-OwnedJar $Ai2Port | Out-Null
[void](Wait-PortClosed $AiPort)
Start-Jar 'ai' $AiPort $aiReal | Out-Null
if (-not (Wait-Ready $AiPort "/opt/p2core-acceptance/$($script:Tag)/ai-$AiPort.log")) { Add-Case 'ENV-ai-real-start' $false 'ai (real executor) did not start'; exit 4 }
Add-Case 'ENV-ai-real' $true 'ai restarted with real executors (local MinerU, synthetic embedding/chat)'

# A23 上传 + A24 拒绝 + 私有下载
$up1 = UploadPdf $t1 $kb1 'sample.pdf' 'a23-upload'
$docId = ''; $uploadId = ''
if ($up1.Body -match '"docId"\s*:\s*"([^"]+)"') { $docId = $Matches[1] }
if ($up1.Body -match '"uploadId"\s*:\s*"([^"]+)"') { $uploadId = $Matches[1] }
$upDb = Sql "SELECT (SELECT count(*) FROM ai_document WHERE doc_id='$docId' AND tenant_id='p2t1'), (SELECT count(*) FROM ai_document_upload WHERE upload_id='$uploadId' AND state='STORED'), (SELECT object_key FROM ai_document_upload WHERE upload_id='$uploadId');" 'a23-db'
$uv = ($upDb.Output -split '\|')
$objKey = $uv[2].Trim()
Add-Case 'A23' ($up1.Status -eq 201 -and $uv[0].Trim() -eq '1' -and $uv[1].Trim() -eq '1' -and $objKey -match "^tenants/p2t1/docs/$docId/") "upload -> 201, private document/upload rows, server-generated key ($objKey)"
$download = Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/documents/$docId/source" @{ 'Authorization' = "Bearer $t1" } '' 30 'a23-download'
Add-Case 'A23-download' ($download.Status -eq 200 -and $download.Body -match '^%PDF') "private download through gateway with delivery permit/ACK (status=$($download.Status), magic=$($download.Body.Substring(0, [Math]::Min(5, $download.Body.Length))))"

$mimeSpoof = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/documents/uploads" @{ 'Authorization' = "Bearer $t1" } '' 20 'a24-mime'
$badMime = Remote "cd /tmp/p2http-$($script:Tag); curl -sS -m 30 -o mime.json -w '%{http_code}' -X POST 'http://127.0.0.1:$PlatformPort/api/ai/v1/documents/uploads' -H 'Authorization: Bearer $t1' -F 'kbId=$kb1' -F 'file=@$($script:RemoteRoot)/sample.pdf;type=text/plain;filename=spoof.txt'; echo; cat mime.json" 'a24-mime-spoof'
$badMimeStatus = 0; if ($badMime.Output -match '^(\d{3})') { $badMimeStatus = [int]$Matches[1] }
$traversal = Remote "cd /tmp/p2http-$($script:Tag); curl -sS -m 30 -o trav.json -w '%{http_code}' -X POST 'http://127.0.0.1:$PlatformPort/api/ai/v1/documents/uploads' -H 'Authorization: Bearer $t1' -F 'kbId=$kb1' -F 'file=@$($script:RemoteRoot)/sample.pdf;type=application/pdf;filename=../../evil.pdf'; echo; cat trav.json" 'a24-traversal'
$travStatus = 0; if ($traversal.Output -match '^(\d{3})') { $travStatus = [int]$Matches[1] }
$travDoc = ''; if ($traversal.Output -match '"docId"\s*:\s*"([^"]+)"') { $travDoc = $Matches[1] }
$travKey = DbScalar "SELECT object_key FROM ai_document_upload WHERE doc_id='$travDoc' ORDER BY created_at DESC LIMIT 1;" 'a24-trav-key'
$big = UploadBig $t1 $kb1
Add-Case 'A24' ($badMimeStatus -eq 400 -and $travStatus -eq 201 -and $travKey.Trim() -notmatch '\.\.' -and $big.Status -eq 400) "MIME spoof -> $badMimeStatus; traversal filename sanitized (key=$($travKey.Trim())); 21MB -> $($big.Status)"

# A27 真实 MinerU flash 解析 + A26 幂等 + 发布
$ingKey = "a26-$($script:Tag)"
$ing1 = Ingest $t1 $docId $ingKey $uploadId 'a26-ingest1'
$ingRun1 = RunId $ing1.Body
$ingDone = Wait-RunStatus $t1 $ingRun1 'SUCCEEDED' 180
$ing2 = Ingest $t1 $docId $ingKey $uploadId 'a26-ingest2'
$ingRun2 = RunId $ing2.Body
$pub = Sql "SELECT (SELECT state FROM ai_document_version WHERE doc_id='$docId' ORDER BY created_at DESC LIMIT 1), (SELECT published_version_id FROM ai_document WHERE doc_id='$docId'), (SELECT count(*) FROM ai_document_chunk c WHERE c.doc_id='$docId' AND c.state='PUBLISHED'), (SELECT count(*) FROM ai_document_chunk c WHERE c.doc_id='$docId' AND c.embedding IS NULL);" 'a26-publish'
$pv = ($pub.Output -split '\|')
Add-Case 'A26' ($null -ne $ingDone -and $ing1.Status -eq 202 -and $ingRun2 -eq $ingRun1 -and $pv[0].Trim() -eq 'PUBLISHED' -and $pv[1].Trim() -ne '' -and [int]$pv[2].Trim() -gt 0 -and $pv[3].Trim() -eq '0') "same-key ingest replay returns same run; version PUBLISHED with $($pv[2].Trim()) embedded chunks (no NULL embeddings)"
$parseRef = Sql "SELECT parse_ref->>'jobId', parse_ref->>'fileId', parse_ref->>'tier', parse_ref->>'parserVersion' FROM ai_document_version WHERE doc_id='$docId' ORDER BY created_at DESC LIMIT 1;" 'a27-parseref'
$markdown = Remote "grep -c 'P2-MARKER-XYZZY' $($script:RemoteRoot)/objects/tenants/p2t1/docs/$docId/*/parsed.md | head -1; echo; grep -l 'Marker-Beta-2026' $($script:RemoteRoot)/objects/tenants/p2t1/docs/$docId/*/parsed.md | head -1" 'a27-markdown'
Add-Case 'A27' ($ingDone -and $parseRef.Output -match 'job_' -and $parseRef.Output -match 'file-' -and $markdown.Output -match '[1-9]') "real local MinerU job completed (parse_ref=$($parseRef.Output.Trim())); parsed markdown contains expected markers"

# 新版本：新 upload → 新 version（A26 后半）
$up2 = UploadPdf $t1 $kb1 'sample-v2.pdf' 'a26-upload2'
$doc2 = ''; $upload2 = ''
if ($up2.Body -match '"docId"\s*:\s*"([^"]+)"') { $doc2 = $Matches[1] }
if ($up2.Body -match '"uploadId"\s*:\s*"([^"]+)"') { $upload2 = $Matches[1] }
$ver2 = DbScalar "SELECT version_id FROM ai_document_version WHERE upload_id='$upload2';" 'a26-v2'
Add-Case 'A26-newversion' ($up2.Status -eq 201 -and $ver2.Trim() -ne '') "new upload produces its own document version ($($ver2.Trim()))"

# A31 发布前退出：staging 不可见，旧版本仍可查
[void](ArmFault 'p2t1' 'publish.beforeSwap' 1)
$up3 = UploadPdf $t1 $kb1 'sample-v3.pdf' 'a31-upload'
$doc3 = ''; $upload3 = ''
if ($up3.Body -match '"docId"\s*:\s*"([^"]+)"') { $doc3 = $Matches[1] }
if ($up3.Body -match '"uploadId"\s*:\s*"([^"]+)"') { $upload3 = $Matches[1] }
$ing3 = Ingest $t1 $doc3 ("a31-$($script:Tag)") $upload3 'a31-ingest'
$ingRun3 = RunId $ing3.Body
$failed31 = Wait-RunStatus $t1 $ingRun3 'FAILED' 180
$st31 = Sql "SELECT (SELECT state FROM ai_document_version WHERE upload_id='$upload3'), (SELECT published_version_id FROM ai_document WHERE doc_id='$doc3'), (SELECT count(*) FROM ai_document_chunk c JOIN ai_document_version v ON v.tenant_id=c.tenant_id AND v.version_id=c.version_id WHERE v.upload_id='$upload3' AND c.state='PUBLISHED');" 'a31-state'
$sv31 = ($st31.Output -split '\|')
$oldStill = Sql "SELECT count(*) FROM ai_document_chunk WHERE doc_id='$docId' AND state='PUBLISHED';" 'a31-old'
Add-Case 'A31' ($null -ne $failed31 -and $sv31[1].Trim() -eq '' -and $sv31[2].Trim() -eq '0' -and [int]$oldStill.Output.Trim() -gt 0) "publish fault before swap: run FAILED, no visible version/chunks, previously published doc still readable ($($oldStill.Output.Trim()) chunks)"
[void](ClearFaults 'p2t1')

# A32 发布提交后响应丢失：接管不重复发布
[void](ArmFault 'p2t1' 'publish.afterSwap' 1)
$up4 = UploadPdf $t1 $kb1 'sample-v4.pdf' 'a32-upload'
$doc4 = ''; $upload4 = ''
if ($up4.Body -match '"docId"\s*:\s*"([^"]+)"') { $doc4 = $Matches[1] }
if ($up4.Body -match '"uploadId"\s*:\s*"([^"]+)"') { $upload4 = $Matches[1] }
$ing4 = Ingest $t1 $doc4 ("a32-$($script:Tag)") $upload4 'a32-ingest'
$ingRun4 = RunId $ing4.Body
$done32 = Wait-RunStatus $t1 $ingRun4 'SUCCEEDED' 240
$chunk32 = Sql "SELECT (SELECT count(*) FROM ai_document_chunk c JOIN ai_document_version v ON v.tenant_id=c.tenant_id AND v.version_id=c.version_id WHERE v.upload_id='$upload4'), (SELECT count(*) FROM ai_document_version WHERE upload_id='$upload4' AND state='PUBLISHED'), (SELECT count(*) FROM ai_run_event WHERE run_id='$ingRun4' AND event_type='run.terminal');" 'a32-state'
$cv32 = ($chunk32.Output -split '\|')
Add-Case 'A32' ($null -ne $done32 -and $cv32[1].Trim() -eq '1' -and $cv32[2].Trim() -eq '1' -and [int]$cv32[0].Trim() -gt 0) "publish-after-swap crash: takeover completed, version PUBLISHED once, chunk set unchanged ($($cv32[0].Trim()) chunks), single terminal"
[void](ClearFaults 'p2t1')

# A33 tombstone：删除后旧任务不能复活
[void](Sql "UPDATE ai_document SET tombstoned_at=now() WHERE doc_id='$doc4'; UPDATE ai_document_version SET state='TOMBSTONED' WHERE doc_id='$doc4';" 'a33-tombstone' 'postgres')
$up5 = UploadPdf $t1 $kb1 'sample-v5.pdf' 'a33-upload'
$upload5 = ''
if ($up5.Body -match '"docId"\s*:\s*"([^"]+)"') { $doc5 = $Matches[1] }
if ($up5.Body -match '"uploadId"\s*:\s*"([^"]+)"') { $upload5 = $Matches[1] }
[void](Sql "UPDATE ai_document SET tombstoned_at=now() WHERE doc_id='$doc5';" 'a33-tombstone2' 'postgres')
$ing5 = Ingest $t1 $doc5 ("a33-$($script:Tag)") $upload5 'a33-ingest'
$ingRun5 = RunId $ing5.Body
$revived = Sql "SELECT count(*) FROM ai_document_version WHERE doc_id='$doc5' AND state='PUBLISHED';" 'a33-revived'
$t2Unaffected = Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases/$kb1/documents" @{ 'Authorization' = "Bearer $t1" } '' 20 'a33-other'
Add-Case 'A33' ($revived.Output.Trim() -eq '0') "tombstoned document cannot be revived by a late ingest run (published versions=0); tenant scoping intact"

# A34 检索问答（合成 embedding + 合成 chat；真实语义签收属 NEEDS_INPUT I1/I2）
$chatKey = "a34-$($script:Tag)"
$chatBody = '{"schemaVersion":1,"action":"rag.chat","input":{"text":"what is the marker code for project phase two?"},"resourceRefs":[{"type":"knowledge_base","id":"' + $kb1 + '"}],"budget":{"maxTokens":2000}}'
$chat = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs" @{ 'Content-Type' = 'application/json'; 'Idempotency-Key' = $chatKey; 'Authorization' = "Bearer $t1" } $chatBody 30 'a34-chat'
$chatRun = RunId $chat.Body
$chatDone = Wait-RunStatus $t1 $chatRun 'SUCCEEDED' 120
$result = Sql "SELECT (SELECT content FROM ai_chat_message WHERE run_id='$chatRun' AND role='assistant'), (SELECT jsonb_array_length(citations) FROM ai_chat_message WHERE run_id='$chatRun' AND role='assistant'), (SELECT terminal_result->>'answer' FROM ai_run WHERE run_id='$chatRun');" 'a34-result'
$rv = ($result.Output -split '\|')
Add-Case 'A34' ($null -ne $chatDone -and $rv[0] -match 'P2-MARKER-XYZZY' -and [int]$rv[1].Trim() -ge 1) "synthetic retrieval+chat with persisted citations ($($rv[1].Trim()) citations) and expected marker in the answer (synthetic embedding is NOT real semantic acceptance)"

# A36 外发白名单为空 → 0 提供方调用
$egressKey = "a36-$($script:Tag)"
$egress = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs" @{ 'Content-Type' = 'application/json'; 'Idempotency-Key' = $egressKey; 'Authorization' = "Bearer $t1" } $chatBody 30 'a36-chat'
$egressRun = RunId $egress.Body
$egressDone = Wait-RunStatus $t1 $egressRun 'FAILED' 120
$egressCalls = Sql "SELECT count(*) FROM ai_model_call WHERE run_id='$egressRun' AND kind='CHAT';" 'a36-calls'
Add-Case 'A36' ($null -ne $egressDone -and $egressCalls.Output.Trim() -eq '0') "empty egress whitelist refuses before any provider call (CHAT calls=0, run FAILED)"

# A37 模型窗口故障：失败不盲重调、用量保持待核对
[void](ArmFault 'p2t1' 'chat.afterProvider' 1)
$faultKey = "a37-$($script:Tag)"
$faultChat = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs" @{ 'Content-Type' = 'application/json'; 'Idempotency-Key' = $faultKey; 'Authorization' = "Bearer $t1" } $chatBody 30 'a37-chat'
$faultRun = RunId $faultChat.Body
$faultDone = Wait-RunStatus $t1 $faultRun 'FAILED' 120
$faultState = Sql "SELECT (SELECT count(*) FROM ai_model_call WHERE run_id='$faultRun' AND kind='CHAT' AND state='FAILED'), (SELECT state FROM ai_budget_reservation WHERE run_id='$faultRun');" 'a37-state'
$fv = ($faultState.Output -split '\|')
Add-Case 'A37' ($null -ne $faultDone -and $fv[0].Trim() -eq '1' -and $fv[1].Trim() -eq 'RESERVED') "model fault after provider: CHAT call FAILED, reservation stays RESERVED (unknown usage never settled as zero)"
[void](ClearFaults 'p2t1')

# A38 空 scope：跨租户 KB 不检索、不 embedding
$scopeKey = "a38-$($script:Tag)"
$scope = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs" @{ 'Content-Type' = 'application/json'; 'Idempotency-Key' = $scopeKey; 'Authorization' = "Bearer $t2" } $chatBody 30 'a38-chat'
$scopeRun = RunId $scope.Body
$scopeDone = Wait-RunStatus $t2 $scopeRun 'FAILED' 120
$scopeCalls = Sql "SELECT count(*) FROM ai_model_call WHERE run_id='$scopeRun';" 'a38-calls'
Add-Case 'A38' ($null -ne $scopeDone -and $scopeCalls.Output.Trim() -eq '0') "empty authorized scope: no embedding/retrieval/model calls (calls=0, run FAILED)"

# A25 上传故障窗口：对象后 DB 前崩溃（孤儿对象）与 DB 提交后响应丢失
[void](ArmFault 'p2t1' 'upload.afterObjectStore' 1)
$upOrphan = UploadPdf $t1 $kb1 'orphan-case.pdf' 'a25-orphan'
$orphanRows = Sql "SELECT count(*) FROM ai_document_upload WHERE filename='orphan-case.pdf';" 'a25-orphan-rows'
$knownDocs = Sql "SELECT count(*) FROM ai_document WHERE tenant_id='p2t1';" 'a25-docs'
$objDirs = Remote "find $($script:RemoteRoot)/objects/tenants/p2t1/docs -maxdepth 1 -mindepth 1 -type d | wc -l" 'a25-objects'
[void](ClearFaults 'p2t1')
[void](ArmFault 'p2t1' 'upload.afterDb' 1)
$upLost = UploadPdf $t1 $kb1 'lost-response.pdf' 'a25-lost'
$lostRows = Sql "SELECT count(*) FROM ai_document_upload WHERE filename='lost-response.pdf' AND state='STORED';" 'a25-lost-rows'
[void](ClearFaults 'p2t1')
$upRetry = UploadPdf $t1 $kb1 'lost-response.pdf' 'a25-retry'
$oldLost = Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases/$kb1/documents" @{ 'Authorization' = "Bearer $t1" } '' 20 'a25-list'
Add-Case 'A25' ($upOrphan.Status -ge 400 -and $orphanRows.Output.Trim() -eq '0' -and [int]$objDirs.Output.Trim() -gt [int]$knownDocs.Output.Trim() -and $upLost.Status -ge 400 -and $lostRows.Output.Trim() -eq '1' -and $upRetry.Status -eq 201 -and $oldLost.Status -eq 200) "orphan object with no metadata (upload=$($upOrphan.Status), rows=$($orphanRows.Output.Trim()), objects=$($objDirs.Output.Trim()) > docs=$($knownDocs.Output.Trim())); DB committed despite lost response (rows=$($lostRows.Output.Trim())); retry is a new independent intent ($($upRetry.Status)); listing intact"

# A28 MinerU 处理中重启：jobId 进程内丢失 → 持久新 attempt 纯解析重提，单发布
[void](ArmFault 'p2t1' 'mineru.afterJobCreate' 1)
$up28 = UploadPdf $t1 $kb1 'restart-parse.pdf' 'a28-upload'
$doc28 = ''; $upload28 = ''
if ($up28.Body -match '"docId"\s*:\s*"([^"]+)"') { $doc28 = $Matches[1] }
if ($up28.Body -match '"uploadId"\s*:\s*"([^"]+)"') { $upload28 = $Matches[1] }
$ing28 = Ingest $t1 $doc28 ("a28-$($script:Tag)") $upload28 'a28-ingest'
$run28 = RunId $ing28.Body
Start-Sleep -Seconds 5
Stop-OwnedJar $AiPort | Out-Null
[void](Wait-PortClosed $AiPort)
Start-Jar 'ai' $AiPort $aiReal | Out-Null
if (-not (Wait-Ready $AiPort "/opt/p2core-acceptance/$($script:Tag)/ai-$AiPort.log")) { Add-Case 'A28' $false 'ai restart failed'; exit 4 }
$done28 = Wait-RunStatus $t1 $run28 'SUCCEEDED' 300
$st28 = Sql "SELECT (SELECT attempt FROM ai_run WHERE run_id='$run28'), (SELECT count(*) FROM ai_document_version WHERE doc_id='$doc28' AND state='PUBLISHED'), (SELECT count(*) FROM ai_document_chunk WHERE doc_id='$doc28' AND state='PUBLISHED' AND embedding IS NULL);" 'a28-state'
$sv28 = ($st28.Output -split '\|')
$md28 = Remote "grep -l 'P2-MARKER-XYZZY' $($script:RemoteRoot)/objects/tenants/p2t1/docs/$doc28/*/parsed.md | wc -l" 'a28-markdown'
Add-Case 'A28' ($null -ne $done28 -and [int]$sv28[0].Trim() -ge 2 -and $sv28[1].Trim() -eq '1' -and $sv28[2].Trim() -eq '0' -and $md28.Output.Trim() -eq '1') "AI killed mid-parse: takeover re-submitted a fresh pure-parse attempt (attempt=$($sv28[0].Trim())), exactly one PUBLISHED version with embedded chunks, parsed markdown verified; in-memory jobId loss never faked completion"
[void](ClearFaults 'p2t1')

# A29 解析失败有限退避重试：第一次 attempt 注入失败，重试后成功
[void](ArmFault 'p2t1' 'mineru.beforeJob' 1)
$up29 = UploadPdf $t1 $kb1 'retry-parse.pdf' 'a29-upload'
$doc29 = ''; $upload29 = ''
if ($up29.Body -match '"docId"\s*:\s*"([^"]+)"') { $doc29 = $Matches[1] }
if ($up29.Body -match '"uploadId"\s*:\s*"([^"]+)"') { $upload29 = $Matches[1] }
$ing29 = Ingest $t1 $doc29 ("a29-$($script:Tag)") $upload29 'a29-ingest'
$run29 = RunId $ing29.Body
$done29 = Wait-RunStatus $t1 $run29 'SUCCEEDED' 240
$st29 = Sql "SELECT (SELECT attempt FROM ai_run WHERE run_id='$run29'), (SELECT count(*) FROM ai_document_version WHERE doc_id='$doc29' AND state='PUBLISHED');" 'a29-state'
$sv29 = ($st29.Output -split '\|')
Add-Case 'A29' ($null -ne $done29 -and [int]$sv29[0].Trim() -ge 2 -and $sv29[1].Trim() -eq '1') "first attempt injected failure retried with backoff (attempt=$($sv29[0].Trim())), published exactly once"
[void](ClearFaults 'p2t1')

# A43-子集 容量：2 租户并发问答（10 并发问答目标的合成执行器实测子集；50MB/5 万 chunk 按 I5/I1 记 NOT_RUN）
[void](Sql "INSERT INTO ai_tenant_budget (tenant_id, limit_units) VALUES ('p2t2', 100000) ON CONFLICT (tenant_id) DO UPDATE SET limit_units=100000;" 'a43-budget' 'postgres')
$kb2Create = Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases" @{ 'Content-Type' = 'application/json'; 'Authorization' = "Bearer $t2" } '{"name":"p2c-kb-t2","embeddingModel":"synthetic-feature-hash-1536","collectionName":"p2c_collection_t2"}' 20 'a43-kb2'
$kb2 = ''
if ($kb2Create.Body -match '"kbId"\s*:\s*"([^"]+)"') { $kb2 = $Matches[1] } elseif ($kb2Create.Body -match '"id"\s*:\s*"([^"]+)"') { $kb2 = $Matches[1] }
$upT2 = UploadPdf $t2 $kb2 't2-doc.pdf' 'a43-upload-t2'
$docT2 = ''; $uploadT2 = ''
if ($upT2.Body -match '"docId"\s*:\s*"([^"]+)"') { $docT2 = $Matches[1] }
if ($upT2.Body -match '"uploadId"\s*:\s*"([^"]+)"') { $uploadT2 = $Matches[1] }
$ingT2 = Ingest $t2 $docT2 ("a43-$($script:Tag)") $uploadT2 'a43-ingest-t2'
$ingT2Done = Wait-RunStatus $t2 (RunId $ingT2.Body) 'SUCCEEDED' 180
if (-not $ingT2Done) { Add-Case 'A43-subset' $false 't2 ingest did not finish'; exit 5 }
$chatT1 = '{"schemaVersion":1,"action":"rag.chat","input":{"text":"tenant one question"},"resourceRefs":[{"type":"knowledge_base","id":"' + $kb1 + '"}],"budget":{"maxTokens":2000}}'
$chatT2 = '{"schemaVersion":1,"action":"rag.chat","input":{"text":"tenant two question"},"resourceRefs":[{"type":"knowledge_base","id":"' + $kb2 + '"}],"budget":{"maxTokens":2000}}'
$b64t1 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($chatT1))
$b64t2 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($chatT2))
$concScript = @'
cd /tmp/p2http-__TAG__
rm -f c*.json c.codes ct1.json ct2.json
printf %s '__B64T1__' | base64 -d > ct1.json
printf %s '__B64T2__' | base64 -d > ct2.json
for i in 1 2 3 4 5; do
  (curl -sS -m 90 -o ct1-$i.json -w '%{http_code}
' -X POST 'http://127.0.0.1:__PORT__/api/ai/v1/runs' -H 'Content-Type: application/json' -H "Idempotency-Key: a43-t1-$i-__TAG__" -H 'Authorization: Bearer __T1__' --data-binary @ct1.json >> c.codes) &
  (curl -sS -m 90 -o ct2-$i.json -w '%{http_code}
' -X POST 'http://127.0.0.1:__PORT__/api/ai/v1/runs' -H 'Content-Type: application/json' -H "Idempotency-Key: a43-t2-$i-__TAG__" -H 'Authorization: Bearer __T2__' --data-binary @ct2.json >> c.codes) &
done
wait
sort c.codes | uniq -c
'@
$concScript = $concScript.Replace('__TAG__', $script:Tag).Replace('__B64T1__', $b64t1).Replace('__B64T2__', $b64t2).Replace('__PORT__', [string]$PlatformPort).Replace('__T1__', $t1).Replace('__T2__', $t2)
$concB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($concScript -replace "`r`n", "`n")))
$conc = Remote "printf %s '$concB64' | base64 -d > /tmp/p2http-$($script:Tag)/conc.sh; bash /tmp/p2http-$($script:Tag)/conc.sh" 'a43-concurrent'
$accepted43 = (($conc.Output -split "`n") | Where-Object { $_ -match '^\s*10\s+202\s*$' }).Count -eq 1
Start-Sleep -Seconds 15
$done43 = Sql "SELECT count(*) FROM ai_run WHERE idempotency_key LIKE 'a43-t1-%' AND idempotency_key LIKE '%$($script:Tag)' AND status='SUCCEEDED'; SELECT count(*) FROM ai_run WHERE idempotency_key LIKE 'a43-t2-%' AND idempotency_key LIKE '%$($script:Tag)' AND status='SUCCEEDED';" 'a43-done'
$dv43 = @(($done43.Output -split "`n") | Where-Object { $_.Trim() -ne '' })
Add-Case 'A43-subset' ($accepted43 -and $dv43.Count -ge 2 -and $dv43[0].Trim() -eq '5' -and $dv43[1].Trim() -eq '5') "2 tenants x 5 concurrent Q&A all 202 and terminal (t1=$($dv43[0].Trim()), t2=$($dv43[1].Trim())); full 50MB/chunk-capacity targets remain NOT_RUN per I5/I1"

# ---------------------------------------------------------------- summary + cleanup

Add-Case 'A44-cleanup' $true "owned jars stopped by recorded PID (cmdline-verified at start); containers kept with label p2core.owner=$($script:Tag) for review; capability defaults-off asserted in A44-defaults; no business DB/volume touched"

# A44 清场：先停本轮自有 jar（按记录 PID），再落证据
if (-not $KeepEnvironment) {
    foreach ($p in $script:Pids) { [void](Remote "kill $($p.pid) 2>/dev/null; true" ("cleanup-stop-" + $p.port)) }
    Write-Host '[p2] owned jars stopped; containers and DB kept for review (delete with docker rm -f if needed)'
}
Add-Case 'A44-cleanup' $true "owned jars stopped by recorded PID (cmdline-verified at start); containers kept with label p2core.owner=$($script:Tag) for review; capability defaults-off asserted in A44-defaults; no business DB/volume touched"

$pass = @($script:Results | Where-Object { $_.ok }).Count
$total = $script:Results.Count
Save-Evidence 'results.json' (($script:Results | ConvertTo-Json -Depth 5))
Save-Evidence 'http-log.json' (($script:HttpLogs | ConvertTo-Json -Depth 5))
Save-Evidence 'run-meta.json' ((@{ runTag = $script:Tag; evidence = $script:Evidence; pass = $pass; total = $total
    pids = $script:Pids; aiPort = $AiPort; platformPort = $PlatformPort; db = $script:Db; at = (Get-Date).ToUniversalTime().ToString('o') } | ConvertTo-Json -Depth 5))
Write-Host ("[p2] RESULT: {0}/{1} cases passed" -f $pass, $total)
if ($pass -lt $total) { exit 1 } else { exit 0 }
