$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "HttpAuthRecoveryEvidence.psm1") -Force

$runId = "0123456789abcdef0123456789abcdef"
$apiBaseUrl = "http://127.0.0.1:18765"
$paths = @(
    "/v3/account/signin",
    "/v3/todohangeul/user",
    "/v3/math/user",
    "/v3/english/user",
    "/v3/todoschool/report/daily/hangeul",
    "/v3/todoschool/report/daily/math",
    "/v3/todoschool/report/daily/english"
)
$screenDispatches = foreach ($path in $paths) {
    [pscustomobject]@{
        path = $path
        foregroundPackage = "android.settings"
        foregroundEventElapsedRealtimeMs = 100
        elapsedRealtimeMs = 110
        serviceLeaseAttached = $true
        serviceLeaseObservedAtElapsedRealtimeMs = 105
    }
}
$failureCycleDispatches = for ($index = 0; $index -lt 5; $index++) {
    $dispatchElapsed = 10000L + ($index * 1000L)
    [pscustomobject]@{
        sequence = 101L + $index
        path = $paths[$index]
        foregroundPackage = "com.enuma.todohangeul"
        foregroundEventElapsedRealtimeMs = $dispatchElapsed - 1
        elapsedRealtimeMs = $dispatchElapsed
        serviceLeaseAttached = $true
        serviceLeaseObservedAtElapsedRealtimeMs = $dispatchElapsed - 1
    }
}
$retryCycleDispatches = for ($index = 0; $index -lt 5; $index++) {
    $dispatchElapsed = 70000L + ($index * 1000L)
    [pscustomobject]@{
        sequence = 106L + $index
        path = $paths[$index]
        foregroundPackage = "com.enuma.todohangeul"
        foregroundEventElapsedRealtimeMs = $dispatchElapsed - 1
        elapsedRealtimeMs = $dispatchElapsed
        serviceLeaseAttached = $true
        serviceLeaseObservedAtElapsedRealtimeMs = $dispatchElapsed - 1
    }
}
$cycles = @(
    @{ paths = $paths; case = $null },
    @{ paths = @($paths[0..3]) + $paths[4]; case = "temporary-http-500" },
    @{ paths = @($paths[0..3]) + $paths[4]; case = $null },
    @{ paths = @($paths[0..3]) + $paths[4]; case = "expired-auth-401" },
    @{ paths = $paths; case = $null },
    @{ paths = $paths; case = $null },
    @{ paths = $paths; case = $null },
    @{ paths = @($paths[0..3]) + $paths[6]; case = $null }
)
$requests = [System.Collections.Generic.List[object]]::new()
$sequence = 0
foreach ($cycleIndex in 0..($cycles.Count - 1)) {
    foreach ($path in $cycles[$cycleIndex].paths) {
        $sequence++
        $status = 200
        $caseId = $null
        if ($cycleIndex -eq 1 -and $path -eq $paths[4]) { $status = 500; $caseId = "temporary-http-500" }
        if ($cycleIndex -eq 3 -and $path -eq $paths[4]) { $status = 401; $caseId = "expired-auth-401" }
        $body = if ($path -eq $paths[0]) {
            [pscustomobject]@{ email = "ticket01@example.test"; password = "not-a-real-password" }
        } elseif ($path -in @($paths[1..3])) {
            [pscustomobject]@{ user = [pscustomobject]@{ accountId = "ticket01-fake-account" } }
        } else {
            [pscustomobject]@{ yyyymmdd = 20261013; languageCode = "ko"; userId = "ticket01-hangeul" }
        }
        $reportCount = if ($path -eq $paths[4]) { 2 } elseif ($path -eq $paths[5]) { 5 } elseif ($path -eq $paths[6]) { 7 } else { $null }
        if ($sequence -eq 41) { $reportCount = 6 }
        if ($sequence -eq 48) { $reportCount = 8 }
        $receivedNs = if ($sequence -le 7) {
            [long]$sequence * 1000000000
        } elseif ($sequence -le 12) {
            10000000000L + ([long]$sequence - 8L) * 1000000000L
        } elseif ($sequence -le 17) {
            70000000000L + ([long]$sequence - 13L) * 1000000000L
        } elseif ($sequence -le 22) {
            80000000000L + ([long]$sequence - 18L) * 1000000000L
        } else {
            160000000000L + ([long]$sequence - 23L) * 1000000000L
        }
        if ($sequence -eq 17) { $receivedNs = 73995000000L }
        [void]$requests.Add([pscustomobject]@{
            sequence = $sequence
            method = "POST"
            path = $path
            body = $body
            headers = [pscustomobject]@{ authorization = if ($path -eq $paths[0]) { "" } else { "Bearer ticket01-fake-token" } }
            responseStatus = $status
            deliveryStatus = "written"
            servedReportCount = if ($status -eq 200) { $reportCount } else { $null }
            caseId = $caseId
            serverReceivedMonotonicNs = $receivedNs
        })
    }
}

$result = [pscustomobject]@{
    scenario = "ticket05"
    runId = $runId
    apiBaseUrl = $apiBaseUrl
    productPackage = "com.example.todoschooltime.debug"
    fixtureAccountId = "ticket01-fake-account"
    fakeCredentialsUsed = $true
    seoulDateYyyymmdd = 20261013
    screenStartupUiPackage = "com.example.todoschooltime.debug"
    screenStartupAfterAuthPauseUiPackage = "com.example.todoschooltime.debug"
    explicitUiReauthenticationScreenPackage = "com.example.todoschooltime.debug"
    screenStartupDispatches = @($screenDispatches)
    screenStartupAfterAuthPauseDispatches = @($screenDispatches)
    explicitUiReauthenticationScreenDispatches = @($screenDispatches)
    failureCycleDispatches = @($failureCycleDispatches)
    recoveryCycleDispatches = @($retryCycleDispatches)
    temporaryFailure = [pscustomobject]@{
        responseStatus = 500; activityCountAfterFailure = 2; lastCheckedAtPreserved = $true
        errorVisibleInPostedNotification = $true; completionAlertRequested = $false
        recovered = $true; retryRequestSequence = 17
        failedCycleStartServerSequence = 8; retryCycleStartServerSequence = 13
        failedCycleStartServerReceivedMonotonicNs = 10000000000L
        retryCycleStartServerReceivedMonotonicNs = 70000000000L
        serverCycleStartIntervalMs = 60000
        failedCycleStartDispatchSequence = 101; retryCycleStartDispatchSequence = 106
        failedCycleStartDispatchElapsedRealtimeMs = 10000; retryCycleStartDispatchElapsedRealtimeMs = 70000
        dispatchCycleStartIntervalMs = 60000; reportToReportReceiveIntervalMs = 59995
        cadenceMeasuredFromActualSignInBodyDispatch = $true
    }
    authenticationRecovery = [pscustomobject]@{
        responseStatus = 401; registrationPreserved = $true; reloginRequiredVisible = $true
        periodicRequestsDuringPause = 0; pauseWindowMs = 75000
        pauseServerSequenceAtStart = 22; pauseServerSequenceAtEnd = 22
        pauseServerMonotonicNsAtStart = 84000000000L; pauseServerMonotonicNsAtEnd = 159000000000L
        humanLoginViaUi = $true; registrationPreservedAfterLogin = $true
        recoveryBaselineHangeulCount = 6; recoveryBaselineCompletionAlerts = 0
    }
    newCompletion = [pscustomobject]@{
        subject = "영어"; activityCount = 8; completionAlertRequests = 1
        notificationManagerCallReturned = $true; notificationIdReused = $true
    }
    finalActiveNotificationCount = 1
    productAlertSoundOrHeadsUpTested = $false
}
$snapshot = [pscustomobject]@{ runId = $runId; scenario = "ticket05"; requests = $requests.ToArray() }
$evidence = Assert-Ticket05HttpAuthRecovery -Snapshot $snapshot -Result $result -RunId $runId -ApiBaseUrl $apiBaseUrl
if ($evidence.sequenceCount -ne 48 -or $evidence.checks.onlyFixtureTodoApiPaths -ne $true) {
    throw "Ticket 05 host evidence did not validate the 48-request synthetic fixture contract."
}

Write-Output "PASS Ticket05 host evidence schema and 48-request cycle gate."
