# Shared, fail-closed validation for the only expected nonzero acceptance command.
function Test-P04MissingLabEvidence {
    param([string]$EvidencePath, [string]$ExpectedContainer, [string]$ExpectedRunTag,
        [int]$EntryExitCode)
    $answer = [ordered]@{ valid = $false; reason = 'MISSING_LAB_CONTAINER'; evidenceDir = $EvidencePath
        container = $ExpectedContainer; runTag = $ExpectedRunTag; entryExitCode = $EntryExitCode
        manifestSha256 = ''; resultsSha256 = ''; nativeCommandsSha256 = ''; error = '' }
    try {
        if ($EntryExitCode -ne 1) { throw 'missing-lab entry must exit 1' }
        if (-not $ExpectedContainer -or -not $ExpectedRunTag) { throw 'missing expected lab identity' }
        $manifestPath = Join-Path $EvidencePath 'manifest.json'
        $resultsPath = Join-Path $EvidencePath 'results.json'
        $nativePath = Join-Path $EvidencePath 'native-commands.json'
        $m = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $results = Get-Content -LiteralPath $resultsPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $native = Get-Content -LiteralPath $nativePath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($m.mode -ne 'Integration' -or $m.runTag -cne $ExpectedRunTag -or
                $m.lab.container -cne $ExpectedContainer -or [int]$m.harnessEntryExitCode -ne $EntryExitCode) {
            throw 'preflight manifest identity or entry exit mismatch'
        }
        $why = $m.missingLabEvidence
        if ($null -eq $why -or $why.reason -cne 'MISSING_LAB_CONTAINER' -or
                $why.container -cne $ExpectedContainer -or $why.confirmedMissing -ne $true -or
                [int]$why.probeExitCode -ne 0 -or -not $why.failureDetail) {
            throw 'missing independent successful Docker inventory probe'
        }
        $failedIndex = [int]$why.failedCommandIndex
        $probeIndex = [int]$why.probeCommandIndex
        if ($failedIndex -lt 0 -or $failedIndex -ge $native.Count -or
                $probeIndex -le $failedIndex -or $probeIndex -ge $native.Count) {
            throw 'invalid missing-lab native command indices'
        }
        $failed = $native[$failedIndex]; $probe = $native[$probeIndex]
        if ($failed.executable -ne 'ssh' -or [int]$failed.exitCode -eq 0 -or
                @($failed.arguments)[-1] -cne "docker exec $ExpectedContainer printenv POSTGRES_PASSWORD" -or
                $probe.executable -ne 'ssh' -or [int]$probe.exitCode -ne 0 -or
                @($probe.arguments)[-1] -cne "docker container ls -a --format '{{.Names}}'") {
            throw 'native records do not match the lab query and inventory probe'
        }
        $failedCommands = @($native | Where-Object { [int]$_.exitCode -ne 0 })
        if ($failedCommands.Count -ne 1) { throw 'preflight contains an unrelated native failure' }
        if (@($m.ownedProcessIds).Count -ne 0 -or @($m.completedCaseSnapshots).Count -ne 0 -or
                (Test-Path -LiteralPath (Join-Path $EvidencePath 'http.jsonl'))) {
            throw 'missing-lab preflight must not start applications or run HTTP cases'
        }
        foreach ($id in @('BUILD-platform-clean-verify', 'BUILD-ai-clean-verify', 'ENV-fresh-ai-artifacts')) {
            $found = @($results | Where-Object { $_.id -ceq $id })
            if ($found.Count -ne 1 -or $found[0].status -cne 'PASS') { throw "preflight prerequisite failed: $id" }
        }
        $lab = @($results | Where-Object { $_.id -ceq 'ENV-lab' })
        $harness = @($results | Where-Object { $_.id -ceq 'HARNESS' })
        $complete = @($results | Where-Object { $_.id -ceq 'CASES-complete' })
        if ($lab.Count -ne 1 -or $lab[0].status -cne 'NOT_RUN' -or
                $lab[0].detail -cne ('synthetic lab unavailable: ' + $why.failureDetail) -or
                $harness.Count -ne 1 -or $harness[0].status -cne 'FAIL' -or
                $harness[0].detail -cne ('unhandled: ' + $why.failureDetail) -or
                $complete.Count -ne 1 -or $complete[0].status -cne 'FAIL') {
            throw 'preflight failure is not exclusively the confirmed missing lab'
        }
        $cases = @('P01','N01','N02','N03','N04','N05','N06','N07','I01','I02','I03','I04','I05','I06','F01','F02','F03','T01','T02')
        if (@($m.requiredCases).Count -ne $cases.Count -or
                @($cases | Where-Object { @($m.requiredCases) -cnotcontains $_ }).Count -ne 0) {
            throw 'preflight required-case inventory mismatch'
        }
        $allowed = @('ENV-lab', 'HARNESS', 'CASES-complete', 'ACCEPTANCE-invocation-log')
        foreach ($case in $cases) {
            $id = 'CASE-' + $case; $allowed += $id
            $found = @($results | Where-Object { $_.id -ceq $id })
            if ($found.Count -ne 1 -or $found[0].status -cne 'NOT_RUN') { throw "unexpected case execution: $case" }
        }
        if (@($results | Where-Object { $_.status -ne 'PASS' -and $allowed -cnotcontains $_.id }).Count -ne 0) {
            throw 'preflight contains an unrelated assertion failure or skipped prerequisite'
        }
        $answer.manifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLower()
        $answer.resultsSha256 = (Get-FileHash -LiteralPath $resultsPath -Algorithm SHA256).Hash.ToLower()
        $answer.nativeCommandsSha256 = (Get-FileHash -LiteralPath $nativePath -Algorithm SHA256).Hash.ToLower()
        $answer.valid = $true
    } catch { $answer.error = $_.Exception.Message }
    return [pscustomobject]$answer
}

function Test-P04AcceptanceRecord {
    param($Record, [string]$ExpectedRunTag, [int]$PreflightEntryExitCode = -1)
    if ($Record.label -cne 'preflight-missing-lab') {
        return ($Record.expectsNonZero -is [bool] -and -not $Record.expectsNonZero -and
            $null -ne $Record.exitCode -and [int]$Record.exitCode -eq 0)
    }
    if ($Record.expectsNonZero -isnot [bool] -or -not $Record.expectsNonZero -or
            $null -eq $Record.exitCode -or [int]$Record.exitCode -ne 1 -or
            $PreflightEntryExitCode -ne [int]$Record.exitCode -or
            $Record.mode -cne 'Integration' -or $Record.runTag -cne $ExpectedRunTag -or
            $Record.expectedFailure -cne 'MISSING_LAB_CONTAINER' -or
            $Record.labContainer -cne ("p04-pg-$ExpectedRunTag-missing") -or
            $null -eq $Record.preflightValidation -or $Record.preflightValidation.valid -ne $true) { return $false }
    $fresh = Test-P04MissingLabEvidence -EvidencePath $Record.preflightValidation.evidenceDir `
        -ExpectedContainer $Record.labContainer -ExpectedRunTag $ExpectedRunTag -EntryExitCode ([int]$Record.exitCode)
    return ($fresh.valid -and $fresh.manifestSha256 -ceq $Record.preflightValidation.manifestSha256 -and
        $fresh.resultsSha256 -ceq $Record.preflightValidation.resultsSha256 -and
        $fresh.nativeCommandsSha256 -ceq $Record.preflightValidation.nativeCommandsSha256)
}
