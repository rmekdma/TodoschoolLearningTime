$ErrorActionPreference = "Stop"
$modulePath = Join-Path $PSScriptRoot "ResultChunks.psm1"
Import-Module $modulePath -Force
Import-Module (Join-Path $PSScriptRoot "ResultSchema.psm1") -Force

$runId = "a1b2c3d4e5f647f8a9b0c1d2e3f40506"
$expected = [ordered]@{
    runId = $runId
    subject = "테스트 아이"
    observations = @(
        [ordered]@{ packageName = "com.enuma.todohangeul"; path = "/v3/todoschool/report/daily/hangeul" }
    )
    largeEvidence = "가나다🚸" * 12000
} | ConvertTo-Json -Depth 8 -Compress
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($expected))
$chunkSize = 3000
$parts = [System.Collections.Generic.List[string]]::new()
for ($offset = 0; $offset -lt $encoded.Length; $offset += $chunkSize) {
    $parts.Add($encoded.Substring($offset, [Math]::Min($chunkSize, $encoded.Length - $offset)))
}
$lines = @(
    "10-12 15:00:00.000 100 101 I Ticket03E2E: TICKET03_RESULT_CHUNK:another-run:1/1:eyJydW5JZCI6ImFub3RoZXItcnVuIn0="
)
for ($index = 0; $index -lt $parts.Count; $index++) {
    $line = "10-12 15:00:01.000 100 101 I Ticket03E2E: TICKET03_RESULT_CHUNK:$runId`:$($index + 1)/$($parts.Count):$($parts[$index])"
    if ([Text.Encoding]::UTF8.GetByteCount($line) -ge 4096) { throw "A structured result chunk exceeds the logcat entry limit." }
    $lines += $line
}

$actual = Join-Ticket03ResultChunks -LogLines $lines -RunId $runId
if ($actual -cne $expected) { throw "Long UTF-8 result changed during chunk reconstruction." }

$missingChunkRejected = $false
try { Join-Ticket03ResultChunks -LogLines $lines[0..($lines.Count - 2)] -RunId $runId | Out-Null }
catch { $missingChunkRejected = $true }
if (-not $missingChunkRejected) { throw "A missing chunk was accepted." }

$duplicateChunkRejected = $false
try { Join-Ticket03ResultChunks -LogLines ($lines + $lines[1]) -RunId $runId | Out-Null }
catch { $duplicateChunkRejected = $true }
if (-not $duplicateChunkRejected) { throw "A duplicate chunk was accepted." }

$mixedRunRejected = $false
$mixedRunLines = @($lines | Where-Object { $_ -notmatch "TICKET03_RESULT_CHUNK:$runId`:" })
$mixedRunLines += "10-12 15:00:02.000 100 101 I Ticket03E2E: TICKET03_RESULT_CHUNK:$runId`:1/1:eyJydW5JZCI6Im90aGVyIn0="
try { Join-Ticket03ResultChunks -LogLines $mixedRunLines -RunId $runId | Out-Null }
catch { $mixedRunRejected = $true }
if (-not $mixedRunRejected) { throw "Chunks with a different payload runId were accepted." }

$inconsistentCountRejected = $false
$inconsistentCountLines = @($lines[0]) + @($lines[1..($lines.Count - 1)])
$inconsistentCountLines[-1] = $inconsistentCountLines[-1] -replace "/$($parts.Count):", "/$($parts.Count + 1):"
try { Join-Ticket03ResultChunks -LogLines $inconsistentCountLines -RunId $runId | Out-Null }
catch { $inconsistentCountRejected = $true }
if (-not $inconsistentCountRejected) { throw "Inconsistent chunk counts were accepted." }

$validSchemaFixture = [ordered]@{
    baselineRequests = @()
    pollRequests = @(
        [ordered]@{
            requestPath = "/v3/todoschool/report/daily/hangeul"
            foregroundPackagesObservedDuringTransition = @("com.enuma.todohangeul")
            foregroundEventsDuringTransition = @()
            httpRequestDispatchEventsDuringCycle = @()
            requestsVisibleAtForegroundEventObservation = @()
            requestsDuringTransitionSequences = @(11, 12)
            requestsDuringTransition = @()
            foregroundEventsObservedAtReport = @()
            foregroundPackagesObservedAtReport = @("com.enuma.todohangeul")
            foregroundEventsObservedAtApply = @()
            foregroundPackagesObservedAtApply = @("com.enuma.todohangeul")
            requestCycleSequences = @(1, 2, 3, 4, 5)
            requestCyclePaths = @("/v3/account/signin", "/v3/todoschool/report/daily/hangeul")
        }
    )
    observationWindows = @(
        [ordered]@{
            foregroundPackagesObservedDuringTransition = @("com.android.settings")
            foregroundEventsDuringTransition = @()
            requestsDuringTransition = @()
            requestsDuringTransitionSequences = @(20, 21)
            foregroundEventsObservedDuringWindow = @()
            foregroundPackagesObservedDuringWindow = @("com.android.settings")
        }
    )
    foregroundEvents = @()
    httpRequestDispatchEvents = @()
    stayAwake = [ordered]@{ foregroundEventsDuringAcquire = @() }
} | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$validSchemaFixture.observationWindows = @($validSchemaFixture.pollRequests[0]) + @($validSchemaFixture.observationWindows)
Assert-Ticket03ResultArrayShapes $validSchemaFixture

$malformedSchemaFixture = $validSchemaFixture | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$malformedSchemaFixture.pollRequests[0].requestsDuringTransitionSequences = "[]"
$stringifiedArrayRejected = $false
try { Assert-Ticket03ResultArrayShapes $malformedSchemaFixture }
catch { $stringifiedArrayRejected = $_.Exception.Message -like "*requestsDuringTransitionSequences*actual JSON array*" }
if (-not $stringifiedArrayRejected) { throw "A stringified empty array was accepted by the Ticket 03 schema validator." }

$missingDispatchArrayRejected = $false
$missingDispatchArrayFixture = $validSchemaFixture | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$missingDispatchArrayFixture.pollRequests[0].PSObject.Properties.Remove("httpRequestDispatchEventsDuringCycle")
try { Assert-Ticket03ResultArrayShapes $missingDispatchArrayFixture }
catch { $missingDispatchArrayRejected = $_.Exception.Message -like "*httpRequestDispatchEventsDuringCycle*" }
if (-not $missingDispatchArrayRejected) { throw "A missing actual HTTP dispatch evidence array was accepted." }

$malformedObservationWindow = $validSchemaFixture | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$malformedObservationWindow.observationWindows[0].requestsDuringTransitionSequences = "[]"
$stringifiedWindowArrayRejected = $false
try { Assert-Ticket03ResultArrayShapes $malformedObservationWindow }
catch { $stringifiedWindowArrayRejected = $_.Exception.Message -match 'observationWindows\[0\].*poll record.*requestsDuringTransitionSequences.*actual JSON array' }
if (-not $stringifiedWindowArrayRejected) { throw "A stringified transition array in the observationWindows poll variant was accepted." }

$stringCycleSequencesRejected = $false
$stringCycleSequencesFixture = $validSchemaFixture | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$stringCycleSequencesFixture.pollRequests[0].requestCycleSequences = @("1", "2", "3", "4", "5")
try { Assert-Ticket03ResultArrayShapes $stringCycleSequencesFixture }
catch { $stringCycleSequencesRejected = $_.Exception.Message -match "pollRequests\[0\].*requestCycleSequences.*\[0\].*integer" }
if (-not $stringCycleSequencesRejected) { throw "String elements in a numeric requestCycleSequences array were accepted." }

$nullTransitionSequenceRejected = $false
$nullTransitionSequenceFixture = $validSchemaFixture | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$nullTransitionSequenceFixture.observationWindows[1].requestsDuringTransitionSequences = @($null)
try { Assert-Ticket03ResultArrayShapes $nullTransitionSequenceFixture }
catch { $nullTransitionSequenceRejected = $_.Exception.Message -match "observationWindows\[1\].*requestsDuringTransitionSequences.*\[0\].*integer" }
if (-not $nullTransitionSequenceRejected) { throw "A null element in requestsDuringTransitionSequences was accepted." }

$numberPackageRejected = $false
$numberPackageFixture = $validSchemaFixture | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$numberPackageFixture.pollRequests[0].foregroundPackagesObservedDuringTransition = @(17)
try { Assert-Ticket03ResultArrayShapes $numberPackageFixture }
catch { $numberPackageRejected = $_.Exception.Message -match "foregroundPackagesObservedDuringTransition.*\[0\].*string" }
if (-not $numberPackageRejected) { throw "A numeric foreground package array element was accepted." }

$nullCyclePathRejected = $false
$nullCyclePathFixture = $validSchemaFixture | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$nullCyclePathFixture.pollRequests[0].requestCyclePaths = @("/v3/account/signin", $null)
try { Assert-Ticket03ResultArrayShapes $nullCyclePathFixture }
catch { $nullCyclePathRejected = $_.Exception.Message -match "requestCyclePaths.*\[1\].*string" }
if (-not $nullCyclePathRejected) { throw "A null request path array element was accepted." }

$pollScalarFixture = [ordered]@{
    subject = "한글"
    packageName = "com.enuma.todohangeul"
    foregroundEventSequence = 1
    foregroundEventCursorBefore = 0
    appLaunchRequestedAtUtc = "2026-10-12T00:00:00Z"
    appLaunchRequestedAtElapsedRealtimeMs = 1000
    foregroundEventObservedAtUtc = "2026-10-12T00:00:00Z"
    foregroundEventObservedAtElapsedRealtimeMs = 1100
    foregroundEventSequenceAtWindowStart = 1
    requestSequenceBeforeTransition = 7
    requestSequenceAtForegroundEventObservation = 7
    inFlightRequestsAtForegroundEventObservation = 0
    foregroundCallbackAtElapsedRealtimeMs = 1000
    eventObservationFixtureSnapshotStartedAtUtc = "2026-10-12T00:00:00Z"
    eventObservationFixtureSnapshotFinishedAtUtc = "2026-10-12T00:00:00Z"
    eventObservationFixtureSnapshotStartedAtElapsedRealtimeMs = 1100
    eventObservationFixtureSnapshotFinishedAtElapsedRealtimeMs = 1110
    requestSequenceCursorAtWindowStart = 7
    requestSequenceCursorAtReport = 12
    inFlightRequestsAtWindowStart = 0
    expectedReportObservedBeforeStableWindow = $false
    requestTimingInterpretation = "device elapsed time only"
    observationWindowStartedAtElapsedRealtimeMs = 1200
    foregroundSnapshotCapturedAtElapsedRealtimeMs = 1200
    requestSnapshotCapturedAtElapsedRealtimeMs = 1210
    foregroundActiveWindowPackageAtWindowStart = "com.enuma.todohangeul"
    serviceLeaseAttachedAtWindowStart = $true
    foregroundEventSequenceAtReport = 1
    foregroundPackageAtReport = "com.enuma.todohangeul"
    foregroundEventSequenceAtApply = 1
    automationActiveWindowPackageAtReport = "com.enuma.todohangeul"
    foregroundObservedAtUtc = "2026-10-12T00:00:00Z"
    foregroundObservedAtElapsedRealtimeMs = 1200
    requestSequence = 12
    requestPath = "/v3/todoschool/report/daily/hangeul"
    requestDispatchCursorBeforeTransition = 0
    requestDispatchSequenceAtReport = 5
    requestDispatchElapsedRealtimeMsAtReport = 1104
    foregroundEventSequenceAtReportDispatch = 1
    foregroundEventElapsedRealtimeMsAtReportDispatch = 1000
    foregroundPackageAtReportDispatch = "com.enuma.todohangeul"
    serviceLeaseAttachedAtReportDispatch = $true
    reportRequestCountInCycle = 1
    servedCount = 2
    status = "BELOW_TARGET"
    requestReceivedAt = "2026-10-12T00:01:00Z"
    cycleObservationStartedAtUtc = "2026-10-12T00:00:59Z"
    cycleObservationStartedAtElapsedRealtimeMs = 59000
    cycleObservationElapsedThroughApplyMs = 1500
    resultAppliedAtElapsedRealtimeMs = 60500
    foregroundActiveElapsedTimeMs = 59300
    serviceLeaseAttached = $true
    inFlightRequestsAtEnd = 0
    foregroundPackagesObservedDuringTransition = @("com.enuma.todohangeul")
    foregroundEventsDuringTransition = @([ordered]@{ sequence = 1; packageName = "com.enuma.todohangeul"; elapsedRealtimeMs = 1000 })
    httpRequestDispatchEventsDuringCycle = @(
        [ordered]@{ sequence = 1; path = "/v3/account/signin"; elapsedRealtimeMs = 1100; foregroundEventSequence = 1; foregroundPackage = "com.enuma.todohangeul"; foregroundEventElapsedRealtimeMs = 1000; serviceLeaseAttached = $true; serviceLeaseObservedAtElapsedRealtimeMs = 900 },
        [ordered]@{ sequence = 2; path = "/v3/todohangeul/user"; elapsedRealtimeMs = 1101; foregroundEventSequence = 1; foregroundPackage = "com.enuma.todohangeul"; foregroundEventElapsedRealtimeMs = 1000; serviceLeaseAttached = $true; serviceLeaseObservedAtElapsedRealtimeMs = 900 },
        [ordered]@{ sequence = 3; path = "/v3/math/user"; elapsedRealtimeMs = 1102; foregroundEventSequence = 1; foregroundPackage = "com.enuma.todohangeul"; foregroundEventElapsedRealtimeMs = 1000; serviceLeaseAttached = $true; serviceLeaseObservedAtElapsedRealtimeMs = 900 },
        [ordered]@{ sequence = 4; path = "/v3/english/user"; elapsedRealtimeMs = 1103; foregroundEventSequence = 1; foregroundPackage = "com.enuma.todohangeul"; foregroundEventElapsedRealtimeMs = 1000; serviceLeaseAttached = $true; serviceLeaseObservedAtElapsedRealtimeMs = 900 },
        [ordered]@{ sequence = 5; path = "/v3/todoschool/report/daily/hangeul"; elapsedRealtimeMs = 1104; foregroundEventSequence = 1; foregroundPackage = "com.enuma.todohangeul"; foregroundEventElapsedRealtimeMs = 1000; serviceLeaseAttached = $true; serviceLeaseObservedAtElapsedRealtimeMs = 900 }
    )
    requestsVisibleAtForegroundEventObservation = @()
    requestsDuringTransitionSequences = @()
    requestsDuringTransition = @()
    foregroundEventsObservedAtReport = @([ordered]@{ sequence = 1; packageName = "com.enuma.todohangeul"; elapsedRealtimeMs = 1000 })
    foregroundPackagesObservedAtReport = @("com.enuma.todohangeul")
    foregroundEventsObservedAtApply = @([ordered]@{ sequence = 1; packageName = "com.enuma.todohangeul"; elapsedRealtimeMs = 1000 })
    foregroundPackagesObservedAtApply = @("com.enuma.todohangeul")
    requestCycleSequences = @(8, 9, 10, 11, 12)
    requestCyclePaths = @("/v3/account/signin", "/v3/todohangeul/user", "/v3/math/user", "/v3/english/user", "/v3/todoschool/report/daily/hangeul")
} | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$validScalarResultFixture = [ordered]@{
    runId = $runId
    apiBaseUrl = "http://127.0.0.1:18765"
    deviceApi = 33
    deviceModel = "fixture-device"
    seoulDate = 20261012
    configuredIntervalMinutes = 1
    intervalMeasuredUsingRealElapsedTime = $true
    registeredChild = "테스트 아이"
    registeredChildOnly = $true
    subjectPackages = [ordered]@{ hangeul = "com.enuma.todohangeul"; math = "com.enuma.todomath"; english = "com.enuma.todoenglish" }
    baselineRequests = @()
    observationStartSequence = 0
    pollRequests = @($pollScalarFixture)
    observationWindows = @()
    noRegistrationNewReportCount = 0
    serviceLeaseAttached = $true
    foregroundEvents = @()
    httpRequestDispatchCursor = 5
    httpRequestDispatchEvents = @(
        [ordered]@{ sequence = 1; path = "/v3/account/signin"; elapsedRealtimeMs = 1100; foregroundEventSequence = 1; foregroundPackage = "com.enuma.todohangeul"; foregroundEventElapsedRealtimeMs = 1000; serviceLeaseAttached = $true; serviceLeaseObservedAtElapsedRealtimeMs = 900 },
        [ordered]@{ sequence = 2; path = "/v3/todohangeul/user"; elapsedRealtimeMs = 1101; foregroundEventSequence = 1; foregroundPackage = "com.enuma.todohangeul"; foregroundEventElapsedRealtimeMs = 1000; serviceLeaseAttached = $true; serviceLeaseObservedAtElapsedRealtimeMs = 900 },
        [ordered]@{ sequence = 3; path = "/v3/math/user"; elapsedRealtimeMs = 1102; foregroundEventSequence = 1; foregroundPackage = "com.enuma.todohangeul"; foregroundEventElapsedRealtimeMs = 1000; serviceLeaseAttached = $true; serviceLeaseObservedAtElapsedRealtimeMs = 900 },
        [ordered]@{ sequence = 4; path = "/v3/english/user"; elapsedRealtimeMs = 1103; foregroundEventSequence = 1; foregroundPackage = "com.enuma.todohangeul"; foregroundEventElapsedRealtimeMs = 1000; serviceLeaseAttached = $true; serviceLeaseObservedAtElapsedRealtimeMs = 900 },
        [ordered]@{ sequence = 5; path = "/v3/todoschool/report/daily/hangeul"; elapsedRealtimeMs = 1104; foregroundEventSequence = 1; foregroundPackage = "com.enuma.todohangeul"; foregroundEventElapsedRealtimeMs = 1000; serviceLeaseAttached = $true; serviceLeaseObservedAtElapsedRealtimeMs = 900 }
    )
    silentChannelPrepared = $true
    notificationChannelImportance = 2
    notificationChannelHasSoundUri = $true
    notificationChannelVibrationEnabled = $false
    productAlertDeliveryTested = $false
    actualTodoApiCalled = $false
    stayAwake = [ordered]@{
        runId = $runId; mechanism = "partial_wake_lock"; permissionScope = "observer"
        acquired = $true; acquiredAtElapsedRealtimeMs = 1; timeoutMs = 900000
        displayInteractiveBefore = $true; displayInteractiveAfter = $true
        foregroundPackageBefore = "com.enuma.todohangeul"; foregroundPackageAfter = "com.enuma.todohangeul"
        foregroundEventCursorBefore = 1; foregroundEventCursorAfter = 1
        serviceLeaseAttachedBefore = $true; serviceLeaseAttachedAfter = $true
        foregroundEventsDuringAcquire = @()
    }
    observerPackage = "com.example.todoschooltime.e2eobserver"
} | ConvertTo-Json -Depth 12 -Compress | ConvertFrom-Json
Assert-Ticket03ResultArrayShapes $validScalarResultFixture
Assert-Ticket03ResultScalarShapes $validScalarResultFixture
Assert-Ticket03DispatchCycle $pollScalarFixture @(
    "/v3/account/signin", "/v3/todohangeul/user", "/v3/math/user", "/v3/english/user",
    "/v3/todoschool/report/daily/hangeul"
) "/v3/todoschool/report/daily/hangeul" "com.enuma.todohangeul" 1000 "focused fixture"

$wrongDispatchPackageRejected = $false
$wrongDispatchPackageFixture = $pollScalarFixture | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$wrongDispatchPackageFixture.httpRequestDispatchEventsDuringCycle[4].foregroundPackage = "com.enuma.todomath"
try {
    Assert-Ticket03DispatchCycle $wrongDispatchPackageFixture @(
        "/v3/account/signin", "/v3/todohangeul/user", "/v3/math/user", "/v3/english/user",
        "/v3/todoschool/report/daily/hangeul"
    ) "/v3/todoschool/report/daily/hangeul" "com.enuma.todohangeul" 1000 "wrong-package fixture"
} catch { $wrongDispatchPackageRejected = $_.Exception.Message -like "*current target foreground callback*" }
if (-not $wrongDispatchPackageRejected) { throw "A report dispatch captured for another subject package was accepted." }

$stringFalseRejected = $false
$badBooleanFixture = $validScalarResultFixture | ConvertTo-Json -Depth 12 -Compress | ConvertFrom-Json
$badBooleanFixture.intervalMeasuredUsingRealElapsedTime = "false"
try { Assert-Ticket03ResultScalarShapes $badBooleanFixture }
catch { $stringFalseRejected = $_.Exception.Message -like "*intervalMeasuredUsingRealElapsedTime*JSON type 'boolean'*" }
if (-not $stringFalseRejected) { throw "A stringified false value was accepted for a required JSON boolean." }

$missingScalarRejected = $false
$badMissingFixture = $validScalarResultFixture | ConvertTo-Json -Depth 12 -Compress | ConvertFrom-Json
$badMissingFixture.PSObject.Properties.Remove("configuredIntervalMinutes")
try { Assert-Ticket03ResultScalarShapes $badMissingFixture }
catch { $missingScalarRejected = $_.Exception.Message -like "*missing mandatory scalar field 'configuredIntervalMinutes'*" }
if (-not $missingScalarRejected) { throw "A missing required numeric scalar was accepted." }

$stringNumberRejected = $false
$badNumberFixture = $validScalarResultFixture | ConvertTo-Json -Depth 12 -Compress | ConvertFrom-Json
$badNumberFixture.pollRequests[0].requestSequence = "12"
try { Assert-Ticket03ResultScalarShapes $badNumberFixture }
catch { $stringNumberRejected = $_.Exception.Message -match "pollRequests\[0\].*requestSequence.*JSON type 'integer'" }
if (-not $stringNumberRejected) { throw "A stringified number was accepted for a required numeric poll field." }

Assert-Ticket03IntervalRange "2026-10-12T00:00:00Z" "2026-10-12T00:01:00Z" "baseline-to-first-periodic cycle"
$tooEarlyFirstCycleRejected = $false
try { Assert-Ticket03IntervalRange "2026-10-12T00:00:00Z" "2026-10-12T00:00:57.999Z" "baseline-to-first-periodic cycle" }
catch { $tooEarlyFirstCycleRejected = $_.Exception.Message -like "*interval was outside the configured cadence range*" }
if (-not $tooEarlyFirstCycleRejected) { throw "A too-early first periodic cycle was accepted." }

$slowCycleFixture = [ordered]@{
    earlierCycleStart = [ordered]@{ path = "/v3/account/signin"; receivedAt = "2026-10-12T00:00:00Z" }
    earlierReport = [ordered]@{ path = "/v3/todoschool/report/daily/hangeul"; receivedAt = "2026-10-12T00:00:40Z" }
    laterCycleStart = [ordered]@{ path = "/v3/account/signin"; receivedAt = "2026-10-12T00:00:50Z" }
    laterReport = [ordered]@{ path = "/v3/todoschool/report/daily/math"; receivedAt = "2026-10-12T00:01:40Z" }
} | ConvertTo-Json -Depth 4 -Compress | ConvertFrom-Json
Assert-Ticket03IntervalRange $slowCycleFixture.earlierReport.receivedAt $slowCycleFixture.laterReport.receivedAt "slow catalog/report end-time fixture"
$slowCycleStartRejected = $false
try {
    Assert-Ticket03CycleStartInterval $slowCycleFixture.earlierCycleStart $slowCycleFixture.laterCycleStart "slow catalog/report cycle-start fixture"
} catch { $slowCycleStartRejected = $_.Exception.Message -like "*interval was outside the configured cadence range*" }
if (-not $slowCycleStartRejected) { throw "A 50-second cycle-start gap was hidden by slow catalog/report requests and accepted." }

Write-Output "PASS result chunk/schema validation (complete UTF-8 payload, strict arrays/scalars, malformed types rejected, cycle-start cadence checked)"
