function Assert-Ticket05HttpAuthRecovery {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Snapshot,
        [Parameter(Mandatory = $true)]$Result,
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-f]{32}$')]
        [string]$RunId,
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^http://127\.0\.0\.1:[0-9]+$')]
        [string]$ApiBaseUrl
    )

    if ($Snapshot.runId -cne $RunId -or $Result.runId -cne $RunId) { throw "Ticket 05 evidence run IDs do not match '$RunId'." }
    if ($Snapshot.scenario -cne "ticket05" -or $Result.scenario -cne "ticket05") { throw "Ticket 05 evidence came from an unexpected scenario." }
    if ($Result.apiBaseUrl -cne $ApiBaseUrl -or $Result.productPackage -cne "com.example.todoschooltime.debug") {
        throw "Ticket 05 observer result does not identify the prepared debug app and loopback fixture."
    }
    if (-not $Result.fakeCredentialsUsed -or $Result.fixtureAccountId -cne "ticket01-fake-account") {
        throw "Ticket 05 did not prove use of the local fixture fake account."
    }
    if ($Result.screenStartupUiPackage -cne "com.example.todoschooltime.debug" -or
        $Result.screenStartupAfterAuthPauseUiPackage -cne "com.example.todoschooltime.debug" -or
        $Result.explicitUiReauthenticationScreenPackage -cne "com.example.todoschooltime.debug") {
        throw "Ticket 05 screen request batches were not observed with the actual debug MainActivity visible."
    }

    foreach ($batchName in @("screenStartupDispatches", "screenStartupAfterAuthPauseDispatches", "explicitUiReauthenticationScreenDispatches")) {
        $batch = @($Result.$batchName)
        if ($batch.Count -ne 7) { throw "Ticket 05 screen dispatch batch '$batchName' did not contain exactly seven requests." }
        for ($index = 0; $index -lt $batch.Count; $index++) {
            if ($batch[$index].path -cne @(
                "/v3/account/signin", "/v3/todohangeul/user", "/v3/math/user", "/v3/english/user",
                "/v3/todoschool/report/daily/hangeul", "/v3/todoschool/report/daily/math", "/v3/todoschool/report/daily/english"
            )[$index] -or -not $batch[$index].serviceLeaseAttached -or
                [long]$batch[$index].foregroundEventElapsedRealtimeMs -gt [long]$batch[$index].elapsedRealtimeMs -or
                [long]$batch[$index].serviceLeaseObservedAtElapsedRealtimeMs -gt [long]$batch[$index].elapsedRealtimeMs) {
                throw "Ticket 05 screen dispatch batch '$batchName' did not match the actual MainActivity request window and service lease."
            }
        }
    }

    $requests = @($Snapshot.requests)
    if ($requests.Count -ne 48) { throw "Ticket 05 fixture recorded $($requests.Count) API requests; expected exactly 48 separated screen and monitor requests." }
    $expectedPaths = @(
        "/v3/account/signin", "/v3/todohangeul/user", "/v3/math/user", "/v3/english/user",
        "/v3/todoschool/report/daily/hangeul", "/v3/todoschool/report/daily/math", "/v3/todoschool/report/daily/english"
    )
    $regularHangeulPaths = @($expectedPaths[0..3]) + @($expectedPaths[4])
    $regularEnglishPaths = @($expectedPaths[0..3]) + @($expectedPaths[6])
    $checkCycle = {
        param([long]$First, [string[]]$Paths, [string]$Label)
        $actual = @($requests | Where-Object { [long]$_.sequence -ge $First -and [long]$_.sequence -lt ($First + $Paths.Count) } | Sort-Object sequence)
        if ($actual.Count -ne $Paths.Count -or (($actual | ForEach-Object { [string]$_.path }) -join "`n") -cne ($Paths -join "`n")) {
            throw "$Label has an unexpected request path sequence."
        }
        return $actual
    }

    $cycles = [ordered]@{}
    $cycles.initialMonitorBaseline = & $checkCycle 1 $expectedPaths "Initial monitor baseline"
    $cycles.temporaryHttpFailure = & $checkCycle 8 $regularHangeulPaths "HTTP 500 failure cycle"
    $cycles.retryRecovery = & $checkCycle 13 $regularHangeulPaths "Retry recovery cycle"
    $cycles.authenticationFailure = & $checkCycle 18 $regularHangeulPaths "HTTP 401 authentication cycle"
    $cycles.screenStartupAfterPause = & $checkCycle 23 $expectedPaths "Screen startup after authentication pause"
    $cycles.uiReauthenticationScreen = & $checkCycle 30 $expectedPaths "Explicit UI reauthentication screen request"
    $cycles.monitorRecoveryBaseline = & $checkCycle 37 $expectedPaths "Automatic monitor recovery baseline"
    $cycles.newEnglishCompletion = & $checkCycle 44 $regularEnglishPaths "New English completion cycle"

    for ($index = 0; $index -lt $requests.Count; $index++) {
        $request = $requests[$index]
        if ([long]$request.sequence -ne ($index + 1)) { throw "Ticket 05 fixture request sequence is incomplete or out of order." }
        if ($request.method -cne "POST" -or [string]$request.path -notin @($expectedPaths)) {
            throw "Ticket 05 recorded a request outside the expected Todo API path set."
        }
        if ([string]$request.path -eq "/v3/account/signin") {
            if ($request.body.email -cne "ticket01@example.test" -or $request.body.password -cne "not-a-real-password") {
                throw "Ticket 05 fixture observed non-fixture sign-in credentials."
            }
            if ($request.responseStatus -ne 200) { throw "Ticket 05 fixture sign-in did not succeed with the fake account." }
        } else {
            if ($request.headers.authorization -cne "Bearer ticket01-fake-token") {
                throw "Ticket 05 protected Todo API request did not carry the fixture bearer token."
            }
            if ($request.body.accountId -and $request.body.accountId -cne "ticket01-fake-account") {
                throw "Ticket 05 fixture observed a non-fixture account identifier."
            }
            if ([string]$request.path -in @("/v3/todohangeul/user", "/v3/math/user", "/v3/english/user") -and
                $request.body.user.accountId -cne "ticket01-fake-account") {
                throw "Ticket 05 catalog request did not use the fixture fake account."
            }
            if ($request.body.yyyymmdd -and [long]$request.body.yyyymmdd -ne [long]$Result.seoulDateYyyymmdd) {
                throw "Ticket 05 report request date differs from the observer's Asia/Seoul date."
            }
        }
        if ($null -eq $request.responseStatus -or $request.deliveryStatus -cne "written") {
            throw "Ticket 05 fixture has an incomplete or undelivered Todo API response."
        }
    }

    $failure = $cycles.temporaryHttpFailure[-1]
    $retry = $cycles.retryRecovery[-1]
    $authFailure = $cycles.authenticationFailure[-1]
    $recoveryBaseline = $cycles.monitorRecoveryBaseline
    $newCompletion = $cycles.newEnglishCompletion[-1]
    if ($failure.caseId -cne "temporary-http-500" -or $failure.responseStatus -ne 500 -or $failure.headers.authorization -cne "Bearer ticket01-fake-token") {
        throw "Ticket 05's temporary failure was not the actual HTTP 500 report response."
    }
    if ($retry.responseStatus -ne 200 -or $retry.servedReportCount -ne 2) { throw "Ticket 05 retry did not recover the original Hangeul result." }
    if ($authFailure.caseId -cne "expired-auth-401" -or $authFailure.responseStatus -ne 401) { throw "Ticket 05's authentication error was not an actual HTTP 401 response." }
    if ($recoveryBaseline[4].responseStatus -ne 200 -or $recoveryBaseline[4].servedReportCount -ne 6) {
        throw "Ticket 05 UI reauthentication baseline did not include the disconnected completion."
    }
    if ($newCompletion.responseStatus -ne 200 -or $newCompletion.servedReportCount -ne 8) { throw "Ticket 05 new English completion was not returned by the fixture." }

    foreach ($cycle in @(
        @{ items = $cycles.initialMonitorBaseline; lastStatus = 200 },
        @{ items = $cycles.retryRecovery; lastStatus = 200 },
        @{ items = $cycles.screenStartupAfterPause; lastStatus = 200 },
        @{ items = $cycles.uiReauthenticationScreen; lastStatus = 200 },
        @{ items = $cycles.monitorRecoveryBaseline; lastStatus = 200 },
        @{ items = $cycles.newEnglishCompletion; lastStatus = 200 }
    )) {
        if (@($cycle.items | Where-Object { $_.responseStatus -ne 200 }).Count -ne 0) {
            throw "A successful Ticket 05 cycle included a non-200 response."
        }
    }
    if (@($cycles.temporaryHttpFailure[0..($cycles.temporaryHttpFailure.Count - 2)] | Where-Object { $_.responseStatus -ne 200 }).Count -ne 0 -or
        @($cycles.authenticationFailure[0..($cycles.authenticationFailure.Count - 2)] | Where-Object { $_.responseStatus -ne 200 }).Count -ne 0) {
        throw "A Ticket 05 failure cycle had an unexpected failed sign-in or catalog response."
    }
    if ($cycles.initialMonitorBaseline[4].servedReportCount -ne 2 -or
        $cycles.initialMonitorBaseline[5].servedReportCount -ne 5 -or
        $cycles.initialMonitorBaseline[6].servedReportCount -ne 7 -or
        $cycles.monitorRecoveryBaseline[4].servedReportCount -ne 6 -or
        $cycles.newEnglishCompletion[-1].servedReportCount -ne 8) {
        throw "Ticket 05 fixture report counts did not match the initial, disconnected-completion, or new-completion states."
    }

    $failureCycleStartRequest = $cycles.temporaryHttpFailure[0]
    $retryCycleStartRequest = $cycles.retryRecovery[0]
    $failureCycleDispatches = @($Result.failureCycleDispatches)
    $retryCycleDispatches = @($Result.recoveryCycleDispatches)
    $expectedRetryDispatchPaths = @($expectedPaths[0..3]) + @($expectedPaths[4])
    foreach ($batch in @(
        @{ name = "500 failure"; items = $failureCycleDispatches },
        @{ name = "500 recovery"; items = $retryCycleDispatches }
    )) {
        if ($batch.items.Count -ne $expectedRetryDispatchPaths.Count) {
            throw "Ticket 05 $($batch.name) product dispatch evidence did not contain one complete request cycle."
        }
        for ($index = 0; $index -lt $expectedRetryDispatchPaths.Count; $index++) {
            $dispatch = $batch.items[$index]
            if ($dispatch.path -cne $expectedRetryDispatchPaths[$index] -or
                $dispatch.foregroundPackage -cne "com.enuma.todohangeul" -or
                -not $dispatch.serviceLeaseAttached -or
                [long]$dispatch.foregroundEventElapsedRealtimeMs -gt [long]$dispatch.elapsedRealtimeMs -or
                [long]$dispatch.serviceLeaseObservedAtElapsedRealtimeMs -gt [long]$dispatch.elapsedRealtimeMs) {
                throw "Ticket 05 $($batch.name) product HTTP body dispatch was not tied to the actual Hangeul foreground callback and service lease."
            }
        }
    }

    $failureCycleStartDispatch = $failureCycleDispatches[0]
    $retryCycleStartDispatch = $retryCycleDispatches[0]
    $serverCycleStartIntervalNs = [long]$retryCycleStartRequest.serverReceivedMonotonicNs - [long]$failureCycleStartRequest.serverReceivedMonotonicNs
    $dispatchCycleStartIntervalMs = [long]$retryCycleStartDispatch.elapsedRealtimeMs - [long]$failureCycleStartDispatch.elapsedRealtimeMs
    $reportToReportReceiveIntervalMs = [long]$cycles.retryRecovery[-1].serverReceivedMonotonicNs - [long]$cycles.temporaryHttpFailure[-1].serverReceivedMonotonicNs
    if ($failureCycleStartRequest.path -cne "/v3/account/signin" -or $retryCycleStartRequest.path -cne "/v3/account/signin" -or
        [long]$Result.temporaryFailure.failedCycleStartServerSequence -ne [long]$failureCycleStartRequest.sequence -or
        [long]$Result.temporaryFailure.retryCycleStartServerSequence -ne [long]$retryCycleStartRequest.sequence -or
        [long]$Result.temporaryFailure.failedCycleStartServerReceivedMonotonicNs -ne [long]$failureCycleStartRequest.serverReceivedMonotonicNs -or
        [long]$Result.temporaryFailure.retryCycleStartServerReceivedMonotonicNs -ne [long]$retryCycleStartRequest.serverReceivedMonotonicNs -or
        [long]$Result.temporaryFailure.serverCycleStartIntervalMs -ne [long][Math]::Floor($serverCycleStartIntervalNs / 1000000.0) -or
        [long]$Result.temporaryFailure.failedCycleStartDispatchSequence -ne [long]$failureCycleStartDispatch.sequence -or
        [long]$Result.temporaryFailure.retryCycleStartDispatchSequence -ne [long]$retryCycleStartDispatch.sequence -or
        [long]$Result.temporaryFailure.failedCycleStartDispatchElapsedRealtimeMs -ne [long]$failureCycleStartDispatch.elapsedRealtimeMs -or
        [long]$Result.temporaryFailure.retryCycleStartDispatchElapsedRealtimeMs -ne [long]$retryCycleStartDispatch.elapsedRealtimeMs -or
        [long]$Result.temporaryFailure.dispatchCycleStartIntervalMs -ne $dispatchCycleStartIntervalMs -or
        [long]$Result.temporaryFailure.reportToReportReceiveIntervalMs -ne [long][Math]::Floor($reportToReportReceiveIntervalMs / 1000000.0) -or
        -not $Result.temporaryFailure.cadenceMeasuredFromActualSignInBodyDispatch -or
        $serverCycleStartIntervalNs -le 0 -or $serverCycleStartIntervalNs -ge 140000000000L -or
        $dispatchCycleStartIntervalMs -lt 60000 -or $dispatchCycleStartIntervalMs -ge 140000) {
        throw "Ticket 05 one-minute retry cadence did not match the server-recorded sign-ins and actual product body-dispatch elapsed times."
    }

    $temporary = $Result.temporaryFailure
    if ($temporary.responseStatus -ne 500 -or $temporary.activityCountAfterFailure -ne 2 -or
        -not $temporary.lastCheckedAtPreserved -or -not $temporary.errorVisibleInPostedNotification -or
        $temporary.completionAlertRequested -or -not $temporary.recovered -or
        [long]$temporary.retryRequestSequence -ne [long]$cycles.retryRecovery[-1].sequence) {
        throw "Ticket 05 actual product state did not prove preservation, visible failure, and eligible retry recovery."
    }

    $authentication = $Result.authenticationRecovery
    if ($authentication.responseStatus -ne 401 -or -not $authentication.registrationPreserved -or
        -not $authentication.reloginRequiredVisible -or [int]$authentication.periodicRequestsDuringPause -ne 0 -or
        [long]$authentication.pauseWindowMs -lt 60000 -or
        [long]$authentication.pauseServerSequenceAtStart -ne [long]$authentication.pauseServerSequenceAtEnd -or
        -not $authentication.humanLoginViaUi -or -not $authentication.registrationPreservedAfterLogin -or
        [int]$authentication.recoveryBaselineHangeulCount -ne 6 -or
        [int]$authentication.recoveryBaselineCompletionAlerts -ne 0) {
        throw "Ticket 05 did not prove authentication pause, preserved registration, and silent UI-login recovery."
    }

    $pauseStart = [long]$authentication.pauseServerMonotonicNsAtStart
    $pauseEnd = [long]$authentication.pauseServerMonotonicNsAtEnd
    if ($pauseEnd -le $pauseStart) { throw "Ticket 05 authentication pause has invalid fixture monotonic boundaries." }
    $duringPause = @($requests | Where-Object { [long]$_.serverReceivedMonotonicNs -gt $pauseStart -and [long]$_.serverReceivedMonotonicNs -le $pauseEnd })
    if ($duringPause.Count -ne 0) { throw "Ticket 05 fixture received Todo API calls during authentication pause." }

    $completion = $Result.newCompletion
    if ($completion.subject -cne "영어" -or $completion.activityCount -ne 8 -or $completion.completionAlertRequests -ne 1 -or
        -not $completion.notificationManagerCallReturned -or -not $completion.notificationIdReused) {
        throw "Ticket 05 did not prove one actual NotificationManager completion update for the new English completion."
    }
    if ($Result.finalActiveNotificationCount -ne 1 -or $Result.productAlertSoundOrHeadsUpTested -ne $false) {
        throw "Ticket 05 final notification count or declared sound/heads-up limitation is inconsistent."
    }

    $requestSummary = foreach ($request in $requests) {
        [pscustomobject]@{
            sequence = [long]$request.sequence
            path = [string]$request.path
            responseStatus = [int]$request.responseStatus
            servedReportCount = if ($null -eq $request.servedReportCount) { $null } else { [int]$request.servedReportCount }
            caseId = if ($request.caseId) { [string]$request.caseId } else { $null }
            fixtureTokenVerified = ([string]$request.path -ne "/v3/account/signin" -and $request.headers.authorization -ceq "Bearer ticket01-fake-token")
            fakeCredentialsVerified = ([string]$request.path -ne "/v3/account/signin" -or ($request.body.email -ceq "ticket01@example.test" -and $request.body.password -ceq "not-a-real-password"))
            serverReceivedMonotonicNs = [long]$request.serverReceivedMonotonicNs
        }
    }
    return [pscustomobject]@{
        schemaVersion = 1
        runId = $RunId
        scenario = "ticket05"
        apiBaseUrl = $ApiBaseUrl
        sequenceCount = $requests.Count
        cycleRanges = [pscustomobject]@{
            initialMonitorBaseline = @(1, 7)
            temporaryHttpFailure = @(8, 12)
            retryRecovery = @(13, 17)
            authenticationFailure = @(18, 22)
            screenStartupAfterPause = @(23, 29)
            uiReauthenticationScreen = @(30, 36)
            monitorRecoveryBaseline = @(37, 43)
            newEnglishCompletion = @(44, 48)
        }
        requests = @($requestSummary)
        checks = [pscustomobject]@{
            fakeCredentialsAndBearer = $true
            onlyFixtureTodoApiPaths = $true
            asiaSeoulReportDate = [int]$Result.seoulDateYyyymmdd
            actualHttp500PreserveAndRetry = $true
            failedCycleStartServerSequence = [long]$failureCycleStartRequest.sequence
            retryCycleStartServerSequence = [long]$retryCycleStartRequest.sequence
            serverCycleStartIntervalMs = [long][Math]::Floor($serverCycleStartIntervalNs / 1000000.0)
            failedCycleStartDispatchSequence = [long]$failureCycleStartDispatch.sequence
            retryCycleStartDispatchSequence = [long]$retryCycleStartDispatch.sequence
            dispatchCycleStartIntervalMs = $dispatchCycleStartIntervalMs
            reportToReportReceiveIntervalMs = [long][Math]::Floor($reportToReportReceiveIntervalMs / 1000000.0)
            cadenceMeasuredFromActualSignInBodyDispatch = $true
            actualHttp401PauseAndUiReauthentication = $true
            disconnectedCompletionBaselineSilent = $true
            newCompletionNotificationManagerUpdate = $true
            soundOrHeadsUpNotTested = $true
        }
    }
}

Export-ModuleMember -Function Assert-Ticket05HttpAuthRecovery
