# Owned product path: actual local MinerU; explicitly synthetic embedding/chat.
# Subcases do not constitute complete matrix or real-provider sign-off.
function Extract([string]$body,[string]$field) {
    if($body -match ('"'+$field+'"\s*:\s*"([^"]+)"')){return $Matches[1]};return ''
}
function UploadPdf([string]$Token,[string]$Kb,[string]$Filename,[string]$Name,[string]$Key,
    [string]$File='sample.pdf',[string]$Doc='') {
    $docArg=if($Doc){" -F 'docId=$Doc'"}else{''}
    $command="cd /tmp/p2http-$($script:Tag); rm -f up.json; code=`$(curl -sS -m 120 -o up.json -w '%{http_code}' -X POST 'http://127.0.0.1:$PlatformPort/api/ai/v1/documents/uploads' -H 'clientid: p2c-client' -H 'Authorization: Bearer $Token' -H 'Idempotency-Key: $Key' -F 'kbId=$Kb'$docArg -F 'file=@$($script:RemoteRoot)/$File;type=application/pdf;filename=$Filename'); echo P2STATUS=`$code; test ! -f up.json || cat up.json"
    $reply=Remote $command $Name;$status=0
    if($reply.Output -match 'P2STATUS=(\d{3})'){$status=[int]$Matches[1]}
    return [pscustomobject]@{Status=$status;Body=$reply.Output;Doc=(Extract $reply.Output 'docId');Upload=(Extract $reply.Output 'uploadId');Version=(Extract $reply.Output 'versionId')}
}
function Ingest([string]$Doc,[string]$Upload,[string]$Name,[string]$Token=$t1) {
    $reply=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/documents/$Doc/ingestions" @{'Content-Type'='application/json';'Idempotency-Key'="$Name-$($script:Tag)";'Authorization'="Bearer $Token"} ('{"uploadId":"'+$Upload+'"}') 30 $Name
    return [pscustomobject]@{Status=$reply.Status;Run=(RunId $reply.Body)}
}
function Restart-ProductAi([string[]]$Config) {
    foreach($port in @($AiPort,$Ai2Port)){Stop-OwnedJar $port | Out-Null}
    for($i=0;$i -lt 30 -and (Get-PortOwnerPid $AiPort) -gt 0;$i++){Start-Sleep -Seconds 1}
    Start-Jar 'ai' $AiPort $Config | Out-Null
    if(-not(Wait-Ready $AiPort "$($script:RemoteRoot)/ai-$AiPort.log")){throw 'owned product AI did not start'}
    $script:AiNodes=@($AiPort)
    for($warm=0;$warm -lt 3;$warm++) {
        $read=Http 'GET' "http://127.0.0.1:$PlatformPort/api/ai/v1/knowledge-bases" @{'Authorization'="Bearer $t1"} '' 20 ("restart-warm-read-$warm")
        if($read.Status -eq 200){break}
    }
}
function Chat([string]$Name,[string]$Body,[string]$Token=$t1) {
    $reply=Http 'POST' "http://127.0.0.1:$PlatformPort/api/ai/v1/runs" @{'Authorization'="Bearer $Token";'Content-Type'='application/json';'Idempotency-Key'="$Name-$($script:Tag)"} $Body 30 $Name
    return [pscustomobject]@{Status=$reply.Status;Run=(RunId $reply.Body)}
}
