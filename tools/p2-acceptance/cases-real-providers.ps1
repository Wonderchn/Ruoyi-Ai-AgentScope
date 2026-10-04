Write-Step 'Real-provider controlled acceptance: shared persistent20CNY envelope, synthetic text only'
$real=@($aiReal|Where-Object{$_ -notmatch '^--p2\.(chat\.mode|embedding\.mode|chat\.egress\.)'})+@('--p2.chat.mode=real','--p2.embedding.mode=real','--p2.chat.egress.enabled=true','--p2.chat.egress.allowed-providers=deepseek,dashscope','--p2.providers.spend.enabled=true','--p2.providers.spend.execution-id=p2p3-cont20261003','--p2.providers.spend.cap-cny=20','--p3.enabled=true','--p3.synthetic-model=false','--p3.sandbox.enabled=false','--p3.approval.initiator-enabled=false')
$realTenant='p2t2';$realToken=$t2
Restart-ProductAi $real
if($RealAgentOnly) {
 if($RealAgentKb -notmatch '^[A-Za-z0-9_-]{1,64}$'){throw 'exact owned real Agent KB required'}
 $realKb=$RealAgentKb
 $exists=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases/$realKb" @{'Authorization'="Bearer $realToken"} '' 20 'real-agent-kb-control'
 if($exists.Status -ne 200){throw 'real Agent source not currently accessible'}
} else {
$kb=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases" @{'Content-Type'='application/json';'Authorization'="Bearer $realToken"} '{"name":"real-synthetic-provider-control"}' 20 'real-kb'
$realKb=Extract $kb.Body 'kbId'
Add-Case 'REAL-KB' ($kb.Status -eq 200 -and $realKb) 'server-selected text-embedding-v4/1536 KB; no HTTP until ingestion'
if(-not $realKb){throw 'real model KB control unavailable'}
$upload=UploadPdf $realToken $realKb 'real-synthetic.pdf' 'real-upload' "real-upload-$CaseAttempt-$($script:Tag)"
$ingestion=Ingest $upload.Doc $upload.Upload ('real-ingestion-'+$CaseAttempt) $realToken
$ingested=Wait-RunStatus $realToken $ingestion.Run 'SUCCEEDED' 180
$calls=Sql "SELECT provider,model,state,provider_request_id,usage_raw FROM ai_model_call WHERE tenant_id='$realTenant' AND run_id='$($ingestion.Run)' ORDER BY created_at; SELECT execution_id,cap_cny,reserved_cny FROM ai_provider_envelope; SELECT provider,state,provider_request_id,usage_raw FROM ai_provider_spend ORDER BY created_at;" 'real-embedding-usage'
Add-Case 'A30-real-embedding' ([bool]$ingested -and $calls.Output -match 'text-embedding-v4\|SETTLED') 'actual Beijing provider + local MinerU +1536 PG publication; failures retained separately'
if(-not $ingested){Save-Evidence 'real-provider-blocked.txt' 'Embedding success control failed. No semantic/RAG claim and no automatic regional/model fallback.';return}
$questions=@(
 @{text='What is the marker code for project phase two?';marker='P2-MARKER-XYZZY';page=1},
 @{text='What is the synthetic anchor on page two?';marker='Marker-Beta-2026';page=2},
 @{text='项目第二阶段的标记代码是什么？';marker='P2-MARKER-XYZZY';page=1},
 @{text='第二页的合成内容标记是什么？';marker='Marker-Beta-2026';page=2})
$questionIndex=0
foreach($expected in $questions) {
 $question=$expected.text;$name="real-quality-$CaseAttempt-$questionIndex";$questionIndex++
 $body=@{schemaVersion=1;action='rag.chat';input=@{text=$question};resourceRefs=@(@{type='knowledge_base';id=$realKb});budget=@{maxTokens=256}}|ConvertTo-Json -Compress -Depth 8
 $run=Chat $name $body $realToken;$finished=Wait-RunStatus $realToken $run.Run 'SUCCEEDED' 150
 $result=Sql "SELECT json_build_object('status',status,'answer',terminal_result->>'answer','expectedPageCitations',(SELECT count(*) FROM jsonb_array_elements(terminal_result->'citations') c WHERE c->>'docId'='$($upload.Doc)' AND c->>'versionId'='$($upload.Version)' AND (c->>'pageFrom')::int=$($expected.page))) FROM ai_run WHERE tenant_id='$realTenant' AND run_id='$($run.Run)';" ($name+'-result')
 $usage=Sql "SELECT provider,model,state,provider_request_id,usage_raw FROM ai_model_call WHERE tenant_id='$realTenant' AND run_id='$($run.Run)' ORDER BY created_at; SELECT seq,event_type FROM ai_run_event WHERE tenant_id='$realTenant' AND run_id='$($run.Run)' ORDER BY seq;" ($name+'-usage-events')
 $quality=$result.Output.Trim()|ConvertFrom-Json
 Add-Case ('A34/A35-'+$name) ([bool]$finished -and $quality.answer -match [regex]::Escape($expected.marker) -and $quality.expectedPageCitations -gt 0 -and $usage.Output -match 'deepseek-flash\|SETTLED') 'frozen expected marker and actual current doc/version/page citation; actual selected providers and settled usage'
}
$irrelevant=@{schemaVersion=1;action='rag.chat';input=@{text='Which planet has the highest atmospheric neon concentration, and what is its exact value?'};resourceRefs=@(@{type='knowledge_base';id=$realKb});budget=@{maxTokens=256}}|ConvertTo-Json -Compress -Depth 8
$unrelated=Chat ('real-unrelated-'+$CaseAttempt) $irrelevant $realToken
$unrelatedDone=Wait-RunStatus $realToken $unrelated.Run 'SUCCEEDED' 150
$unrelatedResult=Sql "SELECT terminal_result::text FROM ai_run WHERE tenant_id='$realTenant' AND run_id='$($unrelated.Run)';" 'real-unrelated-result'
$refusal=$unrelatedResult.Output.Trim()|ConvertFrom-Json
Add-Case 'A34-unrelated-evidence-insufficient' ([bool]$unrelatedDone -and ($refusal.evidenceInsufficient -eq $true -or $refusal.answer -match '(?is)insufficient|cannot|not.{0,40}(contain|provide|available)|无法|没有.{0,20}(信息|证据)|未.{0,20}(提供|包含)')) 'unrelated frozen question is refused from evidence rather than answered from unsupported knowledge'
}
$agentBody=@{schemaVersion=1;action='agent.run';agentVersion='core-v1';input=@{text='What is the marker code for project phase two?';mode='read'};resourceRefs=@(@{type='knowledge_base';id=$realKb});budget=@{maxTokens=8192;maxSteps=6;maxToolCalls=4}}|ConvertTo-Json -Compress -Depth 8
$realAgent=Chat ('real-agent-read-'+$CaseAttempt) $agentBody $realToken
$realAgentDone=Wait-RunStatus $realToken $realAgent.Run 'SUCCEEDED' 180
$realAgentFacts=Sql "SELECT r.status,r.terminal_result::text,(SELECT count(*) FROM ai_tool_call t WHERE t.tenant_id=r.tenant_id AND t.run_id=r.run_id AND t.tool_name='kb_search' AND t.state='SUCCEEDED') FROM ai_run r WHERE r.tenant_id='$realTenant' AND r.run_id='$($realAgent.Run)'; SELECT provider,model,state,provider_request_id,usage_raw FROM ai_model_call WHERE tenant_id='$realTenant' AND run_id='$($realAgent.Run)' ORDER BY created_at;" 'real-agent-read-facts'
Add-Case 'P3-A02-real-model-read-control' ($realAgent.Status -eq 202 -and [bool]$realAgentDone -and $realAgentFacts.Output -match 'P2-MARKER-XYZZY' -and $realAgentFacts.Output -match 'deepseek-flash\|SETTLED' -and $realAgentFacts.Output -match '"citations"') 'actual DeepSeek strict tool protocol, actual embedding/PG read tool, formal AgentScope run and server-bound citations; sandbox closed'
[void](Sql 'SELECT execution_id,cap_cny,reserved_cny FROM ai_provider_envelope; SELECT provider,model,state,provider_request_id,usage_raw FROM ai_provider_spend ORDER BY created_at;' 'real-envelope-final')
Save-Evidence 'real-results.json' ($script:Results|ConvertTo-Json -Depth 6)
