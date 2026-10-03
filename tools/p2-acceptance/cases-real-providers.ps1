Write-Step 'Real-provider controlled acceptance: shared persistent20CNY envelope, synthetic text only'
$real=@($aiReal|Where-Object{$_ -notmatch '^--p2\.(chat\.mode|embedding\.mode|chat\.egress\.)'})+@('--p2.chat.mode=real','--p2.embedding.mode=real','--p2.chat.egress.enabled=true','--p2.chat.egress.allowed-providers=deepseek,dashscope','--p2.providers.spend.enabled=true','--p2.providers.spend.execution-id=p2p3-cont20261003','--p2.providers.spend.cap-cny=20')
$realTenant='p2t2';$realToken=$t2
Restart-ProductAi $real
$kb=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases" @{'Content-Type'='application/json';'Authorization'="Bearer $realToken"} '{"name":"real-synthetic-provider-control"}' 20 'real-kb'
$realKb=Extract $kb.Body 'kbId'
Add-Case 'REAL-KB' ($kb.Status -eq 200 -and $realKb) 'server-selected text-embedding-v4/1536 KB; no HTTP until ingestion'
if(-not $realKb){throw 'real model KB control unavailable'}
$upload=UploadPdf $realToken $realKb 'real-synthetic.pdf' 'real-upload' "real-upload-$($script:Tag)"
$ingestion=Ingest $upload.Doc $upload.Upload 'real-ingestion' $realToken
$ingested=Wait-RunStatus $realToken $ingestion.Run 'SUCCEEDED' 180
$calls=Sql "SELECT provider,model,state,provider_request_id,usage_raw FROM ai_model_call WHERE tenant_id='$realTenant' AND run_id='$($ingestion.Run)' ORDER BY created_at; SELECT execution_id,cap_cny,reserved_cny FROM ai_provider_envelope; SELECT provider,state,provider_request_id,usage_raw FROM ai_provider_spend ORDER BY created_at;" 'real-embedding-usage'
Add-Case 'A30-real-embedding' ([bool]$ingested -and $calls.Output -match 'text-embedding-v4\|SETTLED') 'actual Beijing provider + local MinerU +1536 PG publication; failures retained separately'
if(-not $ingested){Save-Evidence 'real-provider-blocked.txt' 'Embedding success control failed. No semantic/RAG claim and no automatic regional/model fallback.';return}
foreach($question in @('What is P2-MARKER-XYZZY?','What is Marker-Alpha-2026?')) {
 $name=if($question -like '*XYZZY*'){'real-qa-marker'}else{'real-qa-alpha'}
 $body=@{schemaVersion=1;action='rag.chat';input=@{text=$question};resourceRefs=@(@{type='knowledge_base';id=$realKb});budget=@{maxTokens=256}}|ConvertTo-Json -Compress -Depth 8
 $run=Chat $name $body $realToken;$finished=Wait-RunStatus $realToken $run.Run 'SUCCEEDED' 150
 $result=Sql "SELECT status,terminal_result->>'answer',jsonb_array_length(terminal_result->'citations') FROM ai_run WHERE tenant_id='$realTenant' AND run_id='$($run.Run)'; SELECT provider,model,state,provider_request_id,usage_raw FROM ai_model_call WHERE tenant_id='$realTenant' AND run_id='$($run.Run)' ORDER BY created_at;" ($name+'-result')
 Add-Case ('A34/A35-'+$name) ([bool]$finished -and $result.Output -match 'deepseek-flash\|SETTLED' -and $result.Output -match 'P2-MARKER|Marker-Alpha') 'actual selected DeepSeek output/citations/usage over real embedding; explicit synthetic question'
}
[void](Sql 'SELECT execution_id,cap_cny,reserved_cny FROM ai_provider_envelope; SELECT provider,model,state,provider_request_id,usage_raw FROM ai_provider_spend ORDER BY created_at;' 'real-envelope-final')
Save-Evidence 'real-results.json' ($script:Results|ConvertTo-Json -Depth 6)
