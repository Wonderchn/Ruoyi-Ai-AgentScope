Write-Step 'Phase C: adversarial inputs, real Basic parser, checkpoints, history and capacity'
$open=@($aiReal|Where-Object{$_ -notmatch '^--p2.chat.egress\.'})+@('--p2.chat.egress.enabled=true','--p2.chat.egress.allowed-providers=synthetic','--p2.worker.batch=10')
$basic=@($open|Where-Object{$_ -notmatch '^--mineru.local.tier='})+@('--mineru.local.tier=basic')
Restart-ProductAi $open
if(-not $ParserRecoveryOnly) {
# A24 pairs valid product upload with rejected multipart input and zero business changes.
$before=DbScalar "SELECT count(*) FROM ai_document;" 'c24-before'
foreach($test in @(@{id='mime';file='sample.pdf';type='image/png';name='mime.pdf';extra=''},@{id='path';file='sample.pdf';type='application/pdf';name='../traversal.pdf';extra=''},@{id='identity';file='sample.pdf';type='application/pdf';name='identity.pdf';extra=" -F 'tenantId=p2t2'"},@{id='object';file='sample.pdf';type='application/pdf';name='object.pdf';extra=" -F 'objectKey=/etc/passwd'"},@{id='url';file='sample.pdf';type='application/pdf';name='url.pdf';extra=" -F 'url=http://127.0.0.1:1/private'"})) {
 $reply=Remote "cd /tmp/p2http-$($script:Tag); rm -f c24.json; code=`$(curl -sS -m 120 -o c24.json -w '%{http_code}' -X POST http://127.0.0.1:$PlatformPort/api/ai/v1/documents/uploads -H 'clientid: p2c-client' -H 'Authorization: Bearer $t1' -H 'Idempotency-Key: c24-$($test.id)-$($script:Tag)' -F 'kbId=$kb1' -F 'file=@$($script:RemoteRoot)/$($test.file);type=$($test.type);filename=$($test.name)'$($test.extra)); echo P2STATUS=`$code; cat c24.json" ('c24-'+$test.id)
 $after=DbScalar "SELECT count(*) FROM ai_document;" ('c24-'+$test.id+'-rows')
 Add-Case ('A24-'+$test.id) ($reply.Output -match 'P2STATUS=400' -and $before.Trim() -eq $after.Trim()) 'input rejected400 before business document side effects'
}
Restart-ProductAi $basic
$base=UploadPdf $t1 $kb1 'basic-control.pdf' 'c27-upload' "c27-$($script:Tag)"
$baseRun=Ingest $base.Doc $base.Upload 'c27-ingest';$baseDone=Wait-RunStatus $t1 $baseRun.Run 'SUCCEEDED' 240
$parse=Sql "SELECT v.parse_ref->>'tier',v.parse_ref->>'jobId',c.content,c.page_from,c.page_to FROM ai_document_version v JOIN ai_document_chunk c ON c.tenant_id=v.tenant_id AND c.version_id=v.version_id WHERE v.tenant_id='p2t1' AND v.version_id='$($base.Version)';" 'c27-actual-basic'
Add-Case 'A27-basic' ([bool]$baseDone -and $parse.Output -match 'basic\|job_' -and $parse.Output -match 'P2-MARKER-XYZZY') 'actual local Basic parse retained original marker/page refs; synthetic embedding only'
if(-not $baseDone){throw 'Basic parser success control failed'}
Restart-ProductAi $open
[void](ArmFault 'p2t1' 'publish.beforeSwap' 1)
$swap=UploadPdf $t1 $kb1 'before-swap.pdf' 'c32-upload' "c32-$($script:Tag)"
$swapRun=Ingest $swap.Doc $swap.Upload 'c32-ingest';$swapDone=Wait-RunStatus $t1 $swapRun.Run 'SUCCEEDED' 180
$swapOnce=Sql "SELECT attempt,(SELECT count(*) FROM ai_model_call WHERE tenant_id='p2t1' AND run_id='$($swapRun.Run)'),(SELECT count(*) FROM ai_run_event WHERE tenant_id='p2t1' AND run_id='$($swapRun.Run)' AND event_type='run.terminal') FROM ai_run WHERE tenant_id='p2t1' AND run_id='$($swapRun.Run)';" 'c32-once'
Add-Case 'A32-before-swap' ([bool]$swapDone -and $swapOnce.Output -match '^[2-9][0-9]*\|1\|1\s*$') 'takeover uses complete embed checkpoint; one call/terminal/publication'
[void](ClearFaults 'p2t1')
# Stage rollback occurs after a returned provider result: cost unknown survives recovery.
[void](ArmFault 'p2t1' 'embedding.afterStagingChunk' 1)
$stage=UploadPdf $t1 $kb1 'staging-rollback.pdf' 'c31-upload' "c31-$($script:Tag)" 'sample.pdf' $base.Doc
$stageRun=Ingest $stage.Doc $stage.Upload 'c31-ingest';$needs=Wait-RunStatus $t1 $stageRun.Run 'NEEDS_RECONCILIATION' 180
$stageRows=Sql "SELECT (SELECT count(*) FROM ai_document_chunk WHERE tenant_id='p2t1' AND version_id='$($stage.Version)'),(SELECT published_version_id FROM ai_document WHERE tenant_id='p2t1' AND doc_id='$($base.Doc)'),(SELECT count(*) FROM ai_model_call WHERE tenant_id='p2t1' AND run_id='$($stageRun.Run)' AND state IN ('STARTED','PENDING_RECONCILIATION'));" 'c31-rollback'
Add-Case 'A31-staging-rollback' ([bool]$needs -and $stageRows.Output.Trim() -eq "0|$($base.Version)|1") 'entire batch rolled back; previous published source survives; call not repeated or billed zero'
[void](ClearFaults 'p2t1')
# Historical citations remain subject to exact current document version.
$question='{"schemaVersion":1,"action":"rag.chat","input":{"text":"What is P2-MARKER-XYZZY?"},"resourceRefs":[{"type":"knowledge_base","id":"'+$kb1+'"}],"budget":{"maxTokens":2000}}'
$history=Chat 'c38-chat' $question;$historyDone=Wait-RunStatus $t1 $history.Run 'SUCCEEDED' 120
$prior=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($history.Run)" @{'Authorization'="Bearer $t1"} '' 20 'c38-prior-visible'
[void](Sql "SELECT terminal_result->'citations' FROM ai_run WHERE tenant_id='p2t1' AND run_id='$($history.Run)';" 'c38-citations')
# Tombstone all cited documents through the product endpoint, never bypass current authorization.
$refs=($prior.Body|ConvertFrom-Json).data.terminalResult.citations
foreach($ref in $refs){[void](Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/documents/$($ref.docId)/tombstone" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} '{}' 20 ('c38-delete-'+$ref.docId))}
$hidden=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($history.Run)" @{'Authorization'="Bearer $t1"} '' 20 'c38-hidden-history'
Add-Case 'A38-history-source-delete' ([bool]$historyDone -and $prior.Status -eq 200 -and $refs.Count -gt 0 -and $hidden.Status -eq 404) 'historical completed answer/citations denied after product source deletion'
Save-Evidence 'phase-c-before-capacity.json' ($script:Results|ConvertTo-Json -Depth 6)
}
# Real job loss: freeze after durable parse-job, prove local Basic processing, SIGKILL owned AI, restart owned parser.
& scp -q (Join-Path $PSScriptRoot 'create-multipage-pdf.py') "${RemoteHost}:$($script:RemoteRoot)/create-multipage-pdf.py"
if($LASTEXITCODE -ne 0){throw 'owned multipage fixture upload failed'}
[void](Remote "python $($script:RemoteRoot)/create-multipage-pdf.py $($script:RemoteRoot)/restart30.pdf 30" 'c28-fixture')
Restart-ProductAi $basic
$paused=AiHttp 'POST' '/internal/ai/v1/test/fault' 'p2t1' (FixtureUserId 'p2t1') 'run.get' @{'Content-Type'='application/json'} '{"hook":"mineru.afterJobCreate","times":1,"pauseMillis":30000}' 1 20 'c28-arm-pause'
$restart=UploadPdf $t1 $kb1 'restart30.pdf' 'c28-upload' "c28-$CaseAttempt-$($script:Tag)" 'restart30.pdf'
$restartRun=Ingest $restart.Doc $restart.Upload ('c28-ingest-'+$CaseAttempt)
$jobId='';for($wait=0;$wait -lt 120;$wait++) {
 $jobId=DbScalar "SELECT ref->>'jobId' FROM ai_run_step WHERE tenant_id='p2t1' AND run_id='$($restartRun.Run)' AND step_id='parse-job' AND state='COMPLETED' ORDER BY attempt DESC LIMIT 1;" 'c28-durable-job'
 $jobId=$jobId.Trim();if($jobId -match '^job_'){break};Start-Sleep -Milliseconds 500
}
if($jobId -notmatch '^job_'){throw 'no real durable parser job observed'}
$processing=Remote "token=`$(cat /opt/ragent-ai-lab-20261003/secrets/mineru_token); curl -sS -m 20 http://127.0.0.1:18000/v1/parse/jobs/$jobId -H `"Authorization: Bearer `$token`"" 'c28-processing-control'
Stop-OwnedJar $AiPort $true
$restartParser=Remote "test `"`$(docker inspect -f '{{index .Config.Labels `"io.ragent.owner`"}}' ragent-ai-lab-20261003-mineru-1)`" = ragent-ai-lab-20261003 || exit 4; test `"`$(docker inspect -f '{{index .Config.Labels `"com.docker.compose.project`"}}' ragent-ai-lab-20261003-mineru-1)`" = ragent-ai-lab-20261003 || exit 4; docker restart ragent-ai-lab-20261003-mineru-1" 'c28-owned-parser-restart'
if($restartParser.ExitCode -ne 0){throw 'owned parser restart not confirmed'}
$healthy=$false;for($wait=0;$wait -lt 90;$wait++){ $health=Remote 'curl -sS -m 2 http://127.0.0.1:18000/v1/health' '';if($health.Output -match '"status"\s*:\s*"ok"'){ $healthy=$true;break };Start-Sleep -Seconds 1 }
if(-not $healthy){throw 'owned parser did not become healthy'}
Restart-ProductAi $open
$lost=Wait-RunStatus $t1 $restartRun.Run 'FAILED' 120
$lostFacts=Sql "SELECT status,error_code,attempt,(SELECT count(*) FROM ai_run_step WHERE tenant_id='p2t1' AND run_id='$($restartRun.Run)' AND step_id='parse-job') FROM ai_run WHERE tenant_id='p2t1' AND run_id='$($restartRun.Run)';" 'c28-loss-classification'
$again=Ingest $restart.Doc $restart.Upload ('c28-explicit-new-run-'+$CaseAttempt);$againDone=Wait-RunStatus $t1 $again.Run 'SUCCEEDED' 240
Add-Case 'A28-real-parser-restart' ($healthy -and [bool]$lost -and $lostFacts.Output -match 'FAILED\|MINERU_JOB_LOST\|[2-9][0-9]*\|1' -and $again.Run -ne $restartRun.Run -and [bool]$againDone -and $processing.Output -match '"status"\s*:\s*"(processing|running|queued)"') 'actual Basic job processing, owned JVM hard exit/parser restart, durable lost-job FAILED and new-run pure parse success'
[void](ClearFaults 'p2t1')
# Cancellation is observed while a durable real Basic job is processing.
Restart-ProductAi $basic
$cancelUpload=UploadPdf $t1 $kb1 'cancel30.pdf' 'c29-upload' "c29-$CaseAttempt-$($script:Tag)" 'restart30.pdf'
$cancelRun=Ingest $cancelUpload.Doc $cancelUpload.Upload ('c29-ingest-'+$CaseAttempt)
for($wait=0;$wait -lt 120;$wait++) {$started=DbScalar "SELECT count(*) FROM ai_run_step WHERE tenant_id='p2t1' AND run_id='$($cancelRun.Run)' AND step_id='parse-job';" 'c29-job';if($started.Trim() -eq '1'){break};Start-Sleep -Milliseconds 500}
 $cancelJob=(DbScalar "SELECT ref->>'jobId' FROM ai_run_step WHERE tenant_id='p2t1' AND run_id='$($cancelRun.Run)' AND step_id='parse-job' ORDER BY attempt DESC LIMIT 1;" 'c29-job-id').Trim()
$cancelProcessing=Remote "token=`$(cat /opt/ragent-ai-lab-20261003/secrets/mineru_token); curl -sS -m 20 'http://127.0.0.1:18000/v1/parse/jobs/$cancelJob' -H `"Authorization: Bearer `$token`"" 'c29-processing-control'
$snapshot=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($cancelRun.Run)" @{'Authorization'="Bearer $t1"} '' 20 'c29-before-cancel'
$version=($snapshot.Body|ConvertFrom-Json).data.version
Start-Sleep -Seconds 4
$afterHeartbeats=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($cancelRun.Run)" @{'Authorization'="Bearer $t1"} '' 20 'c29-after-heartbeats'
$leaseVersionStable=(($afterHeartbeats.Body|ConvertFrom-Json).data.version -eq $version)
Add-Case 'A29-heartbeat-cas' $leaseVersionStable 'lease maintenance keeps lifecycle command version stable' 
$cancelReply=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($cancelRun.Run)/cancel" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} (@{expectedVersion=$version}|ConvertTo-Json -Compress) 20 'c29-cancel'
$cancelled=Wait-RunStatus $t1 $cancelRun.Run 'CANCELLED' 120
$cancelledFacts=Sql "SELECT (SELECT count(*) FROM ai_run_event WHERE tenant_id='p2t1' AND run_id='$($cancelRun.Run)' AND event_type='run.terminal'),(SELECT published_version_id FROM ai_document WHERE tenant_id='p2t1' AND doc_id='$($cancelUpload.Doc)') IS NULL;" 'c29-result'
Add-Case 'A29-real-parse-cancel' ($leaseVersionStable -and $cancelProcessing.Output -match '"status"\s*:\s*"(processing|running|queued)"' -and $cancelReply.Status -eq 200 -and [bool]$cancelled -and $cancelledFacts.Output.Trim() -eq '1|t') 'actual parser-stage cancel, one terminal, no publication; no external IO rollback claim'
Restart-ProductAi $open

if($ParserRecoveryOnly) {Save-Evidence 'parser-recovery-results.json' ($script:Results|ConvertTo-Json -Depth 6);return}
# Two independently ingested tenant sources; capacity fixtures clone only their own published synthetic vectors.
$kb2Reply=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases" @{'Authorization'="Bearer $t2";'Content-Type'='application/json'} '{"name":"capacity-tenant2"}' 20 'c43-kb2'
$kb2=Extract $kb2Reply.Body 'kbId'
$seed1=UploadPdf $t1 $kb1 'capacity1.pdf' 'c43-upload1' "c43-1-$($script:Tag)"
$seed2=UploadPdf $t2 $kb2 'capacity2.pdf' 'c43-upload2' "c43-2-$($script:Tag)"
$seedRun1=Ingest $seed1.Doc $seed1.Upload 'c43-ingest1';$seedDone1=Wait-RunStatus $t1 $seedRun1.Run 'SUCCEEDED' 180
$seedRun2=Ingest $seed2.Doc $seed2.Upload 'c43-ingest2' $t2;$seedDone2=Wait-RunStatus $t2 $seedRun2.Run 'SUCCEEDED' 180
if(-not $seedDone1 -or -not $seedDone2){throw 'both tenant source controls required before capacity fixture'}
foreach($pair in @(@{tenant='p2t1';seed=$seed1},@{tenant='p2t2';seed=$seed2})) {
 $tenant=$pair.tenant;$seed=$pair.seed
 $fill=Sql "WITH n AS (SELECT count(*)::int AS n FROM ai_document_chunk c JOIN ai_document d ON d.tenant_id=c.tenant_id AND d.doc_id=c.doc_id JOIN ai_document_version v ON v.tenant_id=c.tenant_id AND v.version_id=c.version_id WHERE c.tenant_id='$tenant' AND d.tombstoned_at IS NULL AND v.state NOT IN ('TOMBSTONED','SUPERSEDED')), src AS (SELECT * FROM ai_document_chunk WHERE tenant_id='$tenant' AND version_id='$($seed.Version)' LIMIT 1) INSERT INTO ai_document_chunk(tenant_id,version_id,chunk_key,chunk_index,doc_id,kb_id,content,content_hash,char_count,page_from,page_to,state,embedding,embedding_model) SELECT tenant_id,version_id,'capacity-'||g,g,doc_id,kb_id,content,content_hash,char_count,page_from,page_to,state,embedding,embedding_model FROM src,n,generate_series(1,50000-n.n) g; ANALYZE ai_document_chunk;" ('c43-fill-'+$tenant)
 if($fill.ExitCode -ne 0){throw 'capacity fixture insert failed'}
}
$count=Sql "SELECT c.tenant_id,count(*) FROM ai_document_chunk c JOIN ai_document d ON d.tenant_id=c.tenant_id AND d.doc_id=c.doc_id JOIN ai_document_version v ON v.tenant_id=c.tenant_id AND v.version_id=c.version_id WHERE d.tombstoned_at IS NULL AND v.state NOT IN ('TOMBSTONED','SUPERSEDED') GROUP BY c.tenant_id ORDER BY c.tenant_id;" 'c43-count'
Add-Case 'A43-50000-per-tenant' ($count.Output -match 'p2t1\|50000' -and $count.Output -match 'p2t2\|50000') 'actual PG records at declared tenant capacity; synthetic vector copy fixture explicitly marked'
$jobs=@()
for($i=0;$i -lt 10;$i++) {
 $token=if($i -lt 5){$t1}else{$t2};$kb=if($i -lt 5){$kb1}else{$kb2}
 $body=@{schemaVersion=1;action='rag.chat';input=@{text='What is P2-MARKER-XYZZY?'};resourceRefs=@(@{type='knowledge_base';id=$kb});budget=@{maxTokens=2000}}|ConvertTo-Json -Compress -Depth 8
 $b64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($body))
 $cmd="printf %s '$b64' | base64 -d | curl -sS -m 30 -X POST http://127.0.0.1:$PlatformPort/api/ai/v1/runs -H 'Content-Type: application/json' -H 'clientid: p2c-client' -H 'Authorization: Bearer $token' -H 'Idempotency-Key: capacity-$i-$($script:Tag)' --data-binary @-"
 $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($cmd))
 $jobs+=Start-Job -ScriptBlock {param($hostName,$command) & ssh -o BatchMode=yes -o LogLevel=ERROR $hostName "printf %s $command | base64 -d | bash"} -ArgumentList $RemoteHost,$encoded
}
$ids=@();foreach($job in $jobs){$reply=Receive-Job -Job $job -Wait -AutoRemoveJob|Out-String;$ids+=RunId $reply}
$success=0;for($i=0;$i -lt 10;$i++) {$token=if($i -lt 5){$t1}else{$t2};if($ids[$i] -and (Wait-RunStatus $token $ids[$i] 'SUCCEEDED' 180)){$success++}}
$isolated=Sql "SELECT tenant_id,count(*),bool_and(terminal_result->>'answer' LIKE '%P2-MARKER-XYZZY%'),bool_and(jsonb_array_length(terminal_result->'citations')>0) FROM ai_run WHERE idempotency_key LIKE 'capacity-%-$($script:Tag)' AND status='SUCCEEDED' GROUP BY tenant_id ORDER BY tenant_id;" 'c43-concurrent-results'
Add-Case 'A43-ten-concurrent' ($success -eq 10 -and $isolated.Output -match 'p2t1\|5\|t\|t' -and $isolated.Output -match 'p2t2\|5\|t\|t') '10 concurrent authenticated Q&A submissions, two tenants50000each; persisted answer/source checks'
$over=UploadPdf $t1 $kb1 'capacity-over.pdf' 'c43-over-upload' "c43-over-$($script:Tag)"
$overRun=Ingest $over.Doc $over.Upload 'c43-over-ingest';$overFailed=Wait-RunStatus $t1 $overRun.Run 'FAILED' 180
$notPublished=Sql "SELECT error_code,(SELECT published_version_id FROM ai_document WHERE tenant_id='p2t1' AND doc_id='$($over.Doc)') IS NULL,(SELECT count(*) FROM ai_document_chunk WHERE tenant_id='p2t1' AND version_id='$($over.Version)') FROM ai_run WHERE tenant_id='p2t1' AND run_id='$($overRun.Run)';" 'c43-limit-reject'
Add-Case 'A43-capacity-reject' ([bool]$overFailed -and $notPublished.Output.Trim() -eq 'BUDGET_EXCEEDED|t|0') '50001st chunk rejected atomically without publication or partial batch'
Save-Evidence 'phase-c-results.json' ($script:Results|ConvertTo-Json -Depth 6)
