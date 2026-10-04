Write-Step 'Phase B: persistent private uploads, actual local MinerU, synthetic model product path'
$open=@($aiReal | Where-Object{$_ -notmatch '^--p2.chat.egress\.'})+@('--p2.chat.egress.enabled=true','--p2.chat.egress.allowed-providers=synthetic')
Restart-ProductAi $open
$first=UploadPdf $t1 $kb1 'sample.pdf' 'a23-upload' "a23-$($script:Tag)"
$docId=$first.Doc;$uploadId=$first.Upload
$binding=Sql "SELECT u.size_bytes,u.sha256,d.member_id,r.parent_id FROM ai_document_upload u JOIN ai_document d USING(tenant_id,doc_id) JOIN ai_resource r ON r.tenant_id=d.tenant_id AND r.resource_type='DOCUMENT' AND r.resource_id=d.doc_id WHERE u.tenant_id='p2t1' AND u.upload_id='$uploadId';" 'a23-binding'
Add-Case 'A23-upload' ($first.Status -eq 201 -and $docId -ne '' -and $binding.ExitCode -eq 0 -and $binding.Output -match $kb1) 'private upload + authoritative DOCUMENT/KB/owner binding'
if($first.Status -ne 201 -or -not $docId){throw 'upload success control failed; downstream cases not claimed'}

foreach($window in @(@{hook='upload.afterObjectStore';name='object'},@{hook='upload.afterDb';name='db'})) {
    $key="a25-$($window.name)-$($script:Tag)";$filename="intent-$($window.name).pdf"
    [void](ArmFault 'p2t1' $window.hook 1)
    $lost=UploadPdf $t1 $kb1 $filename ('a25-'+$window.name+'-lost') $key
    [void](ClearFaults 'p2t1')
    $retry=UploadPdf $t1 $kb1 $filename ('a25-'+$window.name+'-retry') $key
    $again=UploadPdf $t1 $kb1 $filename ('a25-'+$window.name+'-replay') $key
    $changed=UploadPdf $t1 $kb1 'changed.pdf' ('a25-'+$window.name+'-changed') $key
    $one=Sql "SELECT (SELECT count(*) FROM ai_upload_intent WHERE tenant_id='p2t1' AND idempotency_key='$key' AND state='STORED'),(SELECT count(*) FROM ai_document_upload WHERE tenant_id='p2t1' AND upload_id='$($retry.Upload)'),(SELECT count(*) FROM ai_document_version WHERE tenant_id='p2t1' AND upload_id='$($retry.Upload)');" ('a25-'+$window.name+'-one')
    Add-Case ('A25-'+$window.name) ($lost.Status -in @(500,503) -and $retry.Status -eq 201 -and $again.Status -eq 201 -and $retry.Doc -eq $again.Doc -and $retry.Version -eq $again.Version -and $changed.Status -eq 409 -and $one.Output.Trim() -eq '1|1|1') "original key restores one doc/upload/version after $($window.hook); changed hash409 (lost=$($lost.Status))"
}
$ingest=Ingest $docId $uploadId 'a26-ingest';$done=Wait-RunStatus $t1 $ingest.Run 'SUCCEEDED' 180
$replayed=Ingest $docId $uploadId 'a26-ingest'
$pub=Sql "SELECT v.state,v.parse_ref->>'tier',v.parse_ref->>'jobId',(SELECT count(*) FROM ai_document_chunk WHERE tenant_id=d.tenant_id AND version_id=v.version_id AND state='PUBLISHED') FROM ai_document d JOIN ai_document_version v ON v.tenant_id=d.tenant_id AND v.version_id=d.published_version_id WHERE d.tenant_id='p2t1' AND d.doc_id='$docId';" 'a26-publication'
Add-Case 'A26/A27-flash' ($done -and $ingest.Status -eq 202 -and $replayed.Run -eq $ingest.Run -and $pub.Output -match 'PUBLISHED\|flash\|job_' -and $pub.ExitCode -eq 0) 'actual local flash parse, vector publication, same-key ingestion replay'
if($done) {
    $download=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/documents/$docId/source" @{'Authorization'="Bearer $t1"} '' 30 'a23-download'
    Add-Case 'A23-download' ($download.Status -eq 200 -and $download.Body.StartsWith('%PDF')) 'current published private source downloaded through final delivery ACK gateway'
}
if(-not $done){throw 'parser/publish success control failed; no RAG success claimed'}

$old=UploadPdf $t1 $kb1 'ordered-old.pdf' 'a26-old-upload' "a26-old-$($script:Tag)" 'sample.pdf' $docId
$new=UploadPdf $t1 $kb1 'ordered-new.pdf' 'a26-new-upload' "a26-new-$($script:Tag)" 'sample.pdf' $docId
$newIngest=Ingest $docId $new.Upload 'a26-new-ingest';$newDone=Wait-RunStatus $t1 $newIngest.Run 'SUCCEEDED' 180
$oldIngest=Ingest $docId $old.Upload 'a26-old-ingest';$oldDone=Wait-RunStatus $t1 $oldIngest.Run 'FAILED' 180
$pointer=DbScalar "SELECT published_version_id FROM ai_document WHERE tenant_id='p2t1' AND doc_id='$docId';" 'a26-pointer'
Add-Case 'A26-version-order' ($old.Doc -eq $docId -and $new.Doc -eq $docId -and $newDone -and $oldDone -and $pointer.Trim() -eq $new.Version) 'same-document newer version wins; late old job cannot replace publication pointer'

[void](ArmFault 'p2t1' 'publish.afterSwap' 1)
$after=UploadPdf $t1 $kb1 'after-swap.pdf' 'a32-upload' "a32-$($script:Tag)"
$afterIngest=Ingest $after.Doc $after.Upload 'a32-ingest';$afterDone=Wait-RunStatus $t1 $afterIngest.Run 'SUCCEEDED' 180
$once=Sql "SELECT attempt,(SELECT count(*) FROM ai_run_event WHERE tenant_id='p2t1' AND run_id='$($afterIngest.Run)' AND event_type='run.terminal'),(SELECT count(*) FROM ai_document_version WHERE tenant_id='p2t1' AND version_id='$($after.Version)' AND state='PUBLISHED') FROM ai_run WHERE tenant_id='p2t1' AND run_id='$($afterIngest.Run)';" 'a32-once'
$values=$once.Output.Trim() -split '\|'
Add-Case 'A32-after-swap' ($afterDone -and $values.Count -eq 3 -and [int]$values[0] -ge 2 -and $values[1] -eq '1' -and $values[2] -eq '1') 'takeover reuses published version, one terminal event'
[void](ClearFaults 'p2t1')
$delete=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/documents/$($after.Doc)/tombstone" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} '{}' 20 'a33-delete'
$late=Ingest $after.Doc $after.Upload 'a33-late'
$noPublish=DbScalar "SELECT count(*) FROM ai_document_version WHERE tenant_id='p2t1' AND doc_id='$($after.Doc)' AND state='PUBLISHED';" 'a33-none'
Add-Case 'A33-tombstone' ($delete.Status -eq 200 -and $late.Status -eq 404 -and $noPublish.Trim() -eq '0') 'product tombstone prevents late ingestion without SQL bypass'

$body='{"schemaVersion":1,"action":"rag.chat","input":{"text":"What is P2-MARKER-XYZZY?"},"resourceRefs":[{"type":"knowledge_base","id":"'+$kb1+'"}],"budget":{"maxTokens":2000}}'
$chat=Chat 'a34-chat' $body;$chatDone=Wait-RunStatus $t1 $chat.Run 'SUCCEEDED' 120
$answer=Sql "SELECT terminal_result->>'answer',jsonb_array_length(terminal_result->'citations') FROM ai_run WHERE tenant_id='p2t1' AND run_id='$($chat.Run)';" 'a34-answer'
Add-Case 'A34-synthetic' ($chatDone -and $answer.Output -match 'P2-MARKER-XYZZY' -and $answer.Output -match '\|[1-9][0-9]*\s*$') 'PG retrieval + persisted answer/citations with synthetic models; no semantic-provider claim'
[void](ArmFault 'p2t1' 'chat.afterProvider' 1)
$unknown=Chat 'a37-chat' $body;$pending=Wait-RunStatus $t1 $unknown.Run 'NEEDS_RECONCILIATION' 120
$snap=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($unknown.Run)" @{'Authorization'="Bearer $t1"} '' 20 'a37-snapshot'
$version=($snap.Body|ConvertFrom-Json).data.version
$resume=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs/$($unknown.Run)/resume" @{'Authorization'="Bearer $t1";'Content-Type'='application/json'} (@{expectedVersion=$version}|ConvertTo-Json -Compress) 20 'a37-resume'
[void](Wait-RunStatus $t1 $unknown.Run 'NEEDS_RECONCILIATION' 60)
$noReplay=Sql "SELECT (SELECT count(*) FROM ai_model_call WHERE tenant_id='p2t1' AND run_id='$($unknown.Run)' AND kind='CHAT'),(SELECT state FROM ai_budget_reservation WHERE tenant_id='p2t1' AND run_id='$($unknown.Run)');" 'a37-one-call'
Add-Case 'A37-synthetic-unknown' ($pending -and $resume.Status -eq 200 -and $noReplay.Output.Trim() -eq '1|RESERVED') 'response-loss preserves unknown usage; manual resume does not blindly call or settle zero'
[void](ClearFaults 'p2t1')
$cross=Chat 'a38-cross' $body $t2
$noRun=DbScalar "SELECT count(*) FROM ai_run WHERE tenant_id='p2t2' AND idempotency_key='a38-cross-$($script:Tag)';" 'a38-zero'
Add-Case 'A38-cross-source' ($cross.Status -eq 404 -and $noRun.Trim() -eq '0') 'cross-tenant source denied before admission; zero run/model side effects'

& scp -q (Join-Path $PSScriptRoot 'create-boundary-pdf.py') "${RemoteHost}:$($script:RemoteRoot)/create-boundary-pdf.py"
if($LASTEXITCODE -ne 0){throw 'owned PDF fixture upload failed'}
$fixtures=Remote "set -e; python $($script:RemoteRoot)/create-boundary-pdf.py $($script:RemoteRoot)/boundary50.pdf 52428800; python $($script:RemoteRoot)/create-boundary-pdf.py $($script:RemoteRoot)/boundary51.pdf 53477376; sha256sum $($script:RemoteRoot)/boundary50.pdf" 'a43-file-fixtures'
if($fixtures.ExitCode -ne 0){throw 'valid boundary fixtures failed'}
$boundary=UploadPdf $t1 $kb1 'boundary50.pdf' 'a43-50-upload' "a43-50-$($script:Tag)" 'boundary50.pdf'
$oversize=UploadPdf $t1 $kb1 'boundary51.pdf' 'a24-51-reject' "a24-51-$($script:Tag)" 'boundary51.pdf'
$boundaryIngest=Ingest $boundary.Doc $boundary.Upload 'a43-50-ingest';$boundaryDone=Wait-RunStatus $t1 $boundaryIngest.Run 'SUCCEEDED' 240
Add-Case 'A24/A43-file-boundary' ($boundary.Status -eq 201 -and $boundaryDone -and $oversize.Status -eq 400) 'valid exactly50MiB one-page PDF uploaded/parsed; valid51MiB rejected; attachment supplies byte load'
if($boundaryDone) {
    $bytes=Remote "code=`$(curl -sS -m 120 -o /tmp/p2http-$($script:Tag)/download50.pdf -w '%{http_code}' http://127.0.0.1:$PlatformPort/api/ai/v1/documents/$($boundary.Doc)/source -H 'clientid: p2c-client' -H 'Authorization: Bearer $t1'); echo P2STATUS=`$code; cmp $($script:RemoteRoot)/boundary50.pdf /tmp/p2http-$($script:Tag)/download50.pdf; echo CMP=`$?; stat -c %s /tmp/p2http-$($script:Tag)/download50.pdf" 'a23-private-50-download'
    Add-Case 'A23-private-50-download' ($bytes.Output -match 'P2STATUS=200' -and $bytes.Output -match 'CMP=0' -and $bytes.Output -match '52428800') 'exact50MiB private source delivered with matching SHA/bytes, bounded dedicated gateway'
}
Restart-ProductAi $aiReal
$denied=Chat 'a36-chat' $body;$deniedDone=Wait-RunStatus $t1 $denied.Run 'FAILED' 90
$zeroChat=DbScalar "SELECT count(*) FROM ai_model_call WHERE tenant_id='p2t1' AND run_id='$($denied.Run)' AND kind='CHAT';" 'a36-zero-chat'
Add-Case 'A36-chat-closed' ($deniedDone -and $zeroChat.Trim() -eq '0') 'empty egress denies CHAT; actual zero-HTTP transport denial tested independently'
Save-Evidence 'phase-b-results.json' ($script:Results|ConvertTo-Json -Depth 6)
