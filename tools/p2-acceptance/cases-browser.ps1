# Source preparation only; browser assertions are separate evidence.
$browserConfig=@($aiReal|Where-Object{$_ -notmatch '^--p2.chat.egress\.'})+@('--p2.chat.egress.enabled=true','--p2.chat.egress.allowed-providers=synthetic','--p3.enabled=false')
Restart-ProductAi $browserConfig
$source=UploadPdf $t1 $kb1 'browser-current.pdf' 'browser-upload' "browser-source-$CaseAttempt-$($script:Tag)"
$sourceRun=Ingest $source.Doc $source.Upload ('browser-ingest-'+$CaseAttempt)
$done=Wait-RunStatus $t1 $sourceRun.Run 'SUCCEEDED' 180
$pages=DbScalar "SELECT count(*),max(page_from),bool_and(page_from=page_to) FROM ai_document_chunk WHERE tenant_id='p2t1' AND doc_id='$($source.Doc)' AND state='PUBLISHED';" 'browser-pages'
Add-Case 'BROWSER-owned-source-preparation' ([bool]$done -and $pages -match '^([2-9]|[1-9][0-9]+)\|2\|t$') 'formal private upload/current publication with actual MinerU page1/page2; no browser or real-provider claim'
if(-not $done){throw 'browser source preparation failed'}
Save-Evidence 'browser-context.json' (@{tenant='p2t1';kb=$kb1;doc=$source.Doc;upload=$source.Upload;version=$source.Version;ingestRun=$sourceRun.Run;syntheticProvider=$true;aiPort=$AiPort;platformPort=$PlatformPort;runTag=$script:Tag}|ConvertTo-Json)
