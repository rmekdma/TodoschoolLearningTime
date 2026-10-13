$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "RecoveryEvidence.psm1") -Force

$runId = "0123456789abcdef0123456789abcdef"
$preferenceSelectors = Get-Ticket06PreferenceSelectors -AccountKey 'dGlja2V0MDEtZmFrZS1hY2NvdW50' -ChildName '테스트 아이'
if ($preferenceSelectors.registrationPattern -cne 'registrations.dGlja2V0MDEtZmFrZS1hY2NvdW50' -or
    $preferenceSelectors.intervalPattern -cne 'interval.dGlja2V0MDEtZmFrZS1hY2NvdW50' -or
    $preferenceSelectors.targetsPattern -cne '테스트\ 아이') {
    throw "Ticket 06 preference selectors did not preserve the exact account key and Korean child name."
}
$storedPreferenceRows = ConvertFrom-Ticket06PreferenceRows `
    -RegistrationRows @('<set name="registrations.dGlja2V0MDEtZmFrZS1hY2NvdW50">', '    <string>테스트 아이</string>', '    </set>') `
    -IntervalRows @('<string name="interval.dGlja2V0MDEtZmFrZS1hY2NvdW50">1</string>') `
    -TargetRows @('<string name="테스트 아이">{&quot;한글&quot;:6,&quot;수학&quot;:5,&quot;영어&quot;:8}</string>')
if (-not $storedPreferenceRows.registrationPersisted -or -not $storedPreferenceRows.intervalPersisted -or
    -not $storedPreferenceRows.targetsPersisted -or $storedPreferenceRows.targetCounts.hangeul -ne 6 -or
    $storedPreferenceRows.targetCounts.math -ne 5 -or $storedPreferenceRows.targetCounts.english -ne 8) {
    throw "Ticket 06 did not decode the exact saved child, interval, and XML-escaped target JSON rows."
}
$integerInterval = ConvertFrom-Ticket06PreferenceRows `
    -RegistrationRows @('<set name="registrations.dGlja2V0MDEtZmFrZS1hY2NvdW50">', '    <string>테스트 아이</string>', '    </set>') `
    -IntervalRows @('<int name="interval.dGlja2V0MDEtZmFrZS1hY2NvdW50" value="1" />') `
    -TargetRows @('<string name="테스트 아이">{&quot;한글&quot;:6,&quot;수학&quot;:5,&quot;영어&quot;:8}</string>')
if (-not $integerInterval.intervalPersisted) { throw "Ticket 06 rejected the exact one-minute interval stored as an Android integer row." }
$ampersandChild = ConvertFrom-Ticket06PreferenceRows `
    -RegistrationRows @('<set name="registrations.dGlja2V0MDEtZmFrZS1hY2NvdW50">', '    <string>테스트 &amp; 아이</string>', '    </set>') `
    -IntervalRows @('<string name="interval.dGlja2V0MDEtZmFrZS1hY2NvdW50">1</string>') `
    -TargetRows @('<string name="테스트 &amp; 아이">{&quot;한글&quot;:6,&quot;수학&quot;:5,&quot;영어&quot;:8}</string>') `
    -ChildName "테스트 & 아이"
if (-not $ampersandChild.registrationPersisted -or -not $ampersandChild.targetsPersisted) {
    throw "Ticket 06 did not decode XML ampersands in the registered child and target row."
}
$wrongPreferenceAccount = ConvertFrom-Ticket06PreferenceRows `
    -RegistrationRows @('<set name="registrations.ZmFrZS1hY2NvdW50">', '    <string>테스트 아이</string>', '    </set>') `
    -IntervalRows @('<string name="interval.ZmFrZS1hY2NvdW50">1</string>') `
    -TargetRows @('<string name="테스트 아이">{&quot;한글&quot;:6,&quot;수학&quot;:5,&quot;영어&quot;:8}</string>')
if ($wrongPreferenceAccount.registrationPersisted -or $wrongPreferenceAccount.intervalPersisted) {
    throw "Ticket 06 accepted preference rows stored under a different account key."
}
$wrongStoredChild = ConvertFrom-Ticket06PreferenceRows `
    -RegistrationRows @('<set name="registrations.dGlja2V0MDEtZmFrZS1hY2NvdW50">', '    <string>다른 아이</string>', '    </set>') `
    -IntervalRows @('<string name="interval.dGlja2V0MDEtZmFrZS1hY2NvdW50">1</string>') `
    -TargetRows @('<string name="테스트 아이">{&quot;한글&quot;:6,&quot;수학&quot;:5,&quot;영어&quot;:8}</string>')
if ($wrongStoredChild.registrationPersisted) { throw "Ticket 06 accepted a different registered child." }
$wrongStoredInterval = ConvertFrom-Ticket06PreferenceRows `
    -RegistrationRows @('<set name="registrations.dGlja2V0MDEtZmFrZS1hY2NvdW50">', '    <string>테스트 아이</string>', '    </set>') `
    -IntervalRows @('<int name="interval.dGlja2V0MDEtZmFrZS1hY2NvdW50" value="2" />') `
    -TargetRows @('<string name="테스트 아이">{&quot;한글&quot;:6,&quot;수학&quot;:5,&quot;영어&quot;:8}</string>')
if ($wrongStoredInterval.intervalPersisted) { throw "Ticket 06 accepted a non-one-minute interval stored as an integer preference." }
$wrongStoredGoals = ConvertFrom-Ticket06PreferenceRows `
    -RegistrationRows @('<set name="registrations.dGlja2V0MDEtZmFrZS1hY2NvdW50">', '    <string>테스트 아이</string>', '    </set>') `
    -IntervalRows @('<string name="interval.dGlja2V0MDEtZmFrZS1hY2NvdW50">1</string>') `
    -TargetRows @('<string name="테스트 아이">{&quot;한글&quot;:7,&quot;수학&quot;:5,&quot;영어&quot;:8}</string>')
if ($wrongStoredGoals.targetsPersisted) { throw "Ticket 06 accepted a target count that differs from the configured value." }
$missingStoredInterval = ConvertFrom-Ticket06PreferenceRows `
    -RegistrationRows @('<set name="registrations.dGlja2V0MDEtZmFrZS1hY2NvdW50">', '    <string>테스트 아이</string>', '    </set>') `
    -IntervalRows @() `
    -TargetRows @('<string name="테스트 아이">{&quot;한글&quot;:6,&quot;수학&quot;:5,&quot;영어&quot;:8}</string>')
if ($missingStoredInterval.intervalPersisted) { throw "Ticket 06 accepted a missing interval row." }

$pre = [pscustomobject]@{
    runId = $runId
    scenario = "ticket06"
    phase = "task-removal"
    taskRemoval = [pscustomobject]@{
        recentsObserved = $true
        recentsViewLabelObserved = $true
        mechanism = "adb shell am task remove"
        taskId = 2471
        taskComponent = "com.example.todoschooltime.debug/com.example.todoschooltime.MainActivity"
        otherTasksUnchanged = $true
        otherTaskIdsBeforeRemoval = @(1220)
        otherTaskIdsAfterRemoval = @(1220)
        taskAbsentAfterRemoval = $true
        forceStopUsed = $false
    }
    registration = @("테스트 아이")
    targets = [pscustomobject]@{ hangeul = 6; math = 5; english = 8 }
    interval = [pscustomobject]@{ minutes = 1; persistedExplicitly = $true }
    baseline = [pscustomobject]@{ complete = $true; successfulSubjects = 3; completionAlertRequested = $false }
    cadence = [pscustomobject]@{ baselineSigninElapsedRealtimeMs = 100000; nextCycleSigninElapsedRealtimeMs = 160200; clientDeltaMs = 60200; serverDeltaMs = 60204 }
}
$post = [pscustomobject]@{
    runId = $runId
    scenario = "ticket06"
    phase = "post-reboot"
    automaticServiceBound = $true
    productActivityLaunchedByController = $false
    serviceLeaseAttached = $true
    registration = @("테스트 아이")
    targets = [pscustomobject]@{ hangeul = 6; math = 5; english = 8 }
    interval = [pscustomobject]@{ minutes = 1; persistedExplicitly = $true }
    baseline = [pscustomobject]@{ complete = $true; successfulSubjects = 3; alreadyCompleteSubjects = @("수학"); completionAlertRequested = $false }
    laterCompletion = [pscustomobject]@{ subject = "한글"; completionAlertRequested = $true; notificationIdReused = $true; managerCallReturned = $true }
    foreground = [pscustomobject]@{ packageName = "com.enuma.todohangeul"; eligibleRequests = 4; actualLeaseAttachedAtDispatch = $true }
}
$hostState = [pscustomobject]@{
    runId = $runId
    device = [pscustomobject]@{ apiLevel = 33; manufacturer = "Alldocube" }
    preRemovalPreferenceGate = [pscustomobject]@{
        stage = "registered-before-task-removal"
        runId = $runId
        capturedByHostAtUtc = "2026-10-13T00:00:00Z"
        fixtureAcknowledged = $true
        acknowledgementRunId = $runId
    }
    persistedRegistrationBeforeReboot = $true
    persistedRegistrationAfterReboot = $true
    persistedIntervalBeforeReboot = $true
    persistedIntervalAfterReboot = $true
    persistedTargetsBeforeReboot = $true
    persistedTargetsAfterReboot = $true
    persistedRegistrationBeforeTaskRemoval = $true
    persistedIntervalBeforeTaskRemoval = $true
    persistedTargetsBeforeTaskRemoval = $true
    preferences = [pscustomobject]@{
        registrationLineBeforeTaskRemoval = '<set name="registrations.key"><string>테스트 아이</string></set>'
        registrationLineAfterTaskRemoval = '<set name="registrations.key"><string>테스트 아이</string></set>'
        targetCountsLineBeforeTaskRemoval = '<string name="테스트 아이">6-5-8</string>'
        targetCountsLineAfterTaskRemoval = '<string name="테스트 아이">6-5-8</string>'
        intervalLineBeforeTaskRemoval = '<string name="interval.key">1</string>'
        intervalLineAfterTaskRemoval = '<string name="interval.key">1</string>'
        registrationLineBeforeReboot = '<set name="registrations.key"><string>테스트 아이</string></set>'
        registrationLineAfterReboot = '<set name="registrations.key"><string>테스트 아이</string></set>'
        targetCountsLineBeforeReboot = '<string name="테스트 아이">6-5-8</string>'
        targetCountsLineAfterReboot = '<string name="테스트 아이">6-5-8</string>'
        intervalLineBeforeReboot = '<string name="interval.key">1</string>'
        intervalLineAfterReboot = '<string name="interval.key">1</string>'
    }
    reboot = [pscustomobject]@{ commandIssuedOnce = $true; reconnectObserved = $true; bootCompleted = $true; lockscreenNeedsUnlock = $false; reverseRestoredBeforeServiceReady = $true; reverseStatus = "restored-after-reboot" }
}

$bootPreparation = [pscustomobject]@{
    runId = $runId
    scenario = "ticket06"
    phase = "reboot-preparation"
    serviceLeaseAttached = $true
    registration = @("테스트 아이")
    targets = [pscustomobject]@{ hangeul = 6; math = 5; english = 8 }
    interval = [pscustomobject]@{ minutes = 1; persistedExplicitly = $true }
    baseline = [pscustomobject]@{ complete = $true; successfulSubjects = 3; completionAlertRequested = $false }
}
$bootHost = $hostState | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$bootHost | Add-Member -NotePropertyName preRebootPreferenceGate -NotePropertyValue ([pscustomobject]@{
    stage = "registered-before-reboot"
    runId = $runId
    fixtureAcknowledged = $true
    acknowledgementRunId = $runId
}) -Force
$bootHost | Add-Member -NotePropertyName fixture -NotePropertyValue ([pscustomobject]@{ sequenceBeforeReboot = 20 }) -Force
$bootHost.fixture | Add-Member -NotePropertyName sequenceBeforeReboot -NotePropertyValue 20 -Force
$bootPost = $post | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$bootPost.baseline | Add-Member -NotePropertyName fixtureRequestStartSequence -NotePropertyValue 21 -Force
$bootPost.baseline | Add-Member -NotePropertyName requestPaths -NotePropertyValue @(
    "/v3/account/signin", "/v3/todohangeul/user", "/v3/math/user", "/v3/english/user",
    "/v3/todoschool/report/daily/hangeul", "/v3/todoschool/report/daily/math", "/v3/todoschool/report/daily/english"
) -Force
$bootEvidence = Assert-Ticket06BootRecoveryEvidence -RunId $runId -Preparation $bootPreparation -PostReboot $bootPost -HostState $bootHost
if (-not $bootEvidence.pass -or $bootEvidence.rebootCount -ne 1 -or $bootEvidence.taskRemovalCase -ne "not-run-in-reboot-case") {
    throw "Valid reboot-only Ticket 06 evidence did not remain independent from task-removal evidence."
}

$bootPost.productActivityLaunchedByController = $true
$manualBootActivityRejected = $false
try { Assert-Ticket06BootRecoveryEvidence -RunId $runId -Preparation $bootPreparation -PostReboot $bootPost -HostState $bootHost | Out-Null }
catch { $manualBootActivityRejected = $true }
if (-not $manualBootActivityRejected) { throw "Ticket 06 reboot-only evidence accepted a manually opened product Activity." }

$bootPost = $post | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$bootPost.baseline | Add-Member -NotePropertyName fixtureRequestStartSequence -NotePropertyValue 20 -Force
$bootPost.baseline | Add-Member -NotePropertyName requestPaths -NotePropertyValue @(
    "/v3/account/signin", "/v3/todohangeul/user", "/v3/math/user", "/v3/english/user",
    "/v3/todoschool/report/daily/hangeul", "/v3/todoschool/report/daily/math", "/v3/todoschool/report/daily/english"
) -Force
$earlyBootCycleRejected = $false
try { Assert-Ticket06BootRecoveryEvidence -RunId $runId -Preparation $bootPreparation -PostReboot $bootPost -HostState $bootHost | Out-Null }
catch { $earlyBootCycleRejected = $true }
if (-not $earlyBootCycleRejected) { throw "Ticket 06 reboot-only evidence accepted a cycle that did not follow the pre-reboot fixture cursor." }

$taskRemovalRemainsSeparate = $bootEvidence | Get-Member -Name taskRemovalMechanism -ErrorAction SilentlyContinue
if ($null -ne $taskRemovalRemainsSeparate) { throw "Ticket 06 reboot-only summary falsely claimed a task-removal mechanism." }

$evidence = Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $pre -PostReboot $post -HostState $hostState
if (-not $evidence.pass -or $evidence.phaseCount -ne 2 -or $evidence.apiLevel -ne 33 -or $evidence.manufacturer -ne "Alldocube") {
    throw "Valid Ticket 06 evidence did not preserve both phases and the actual API/manufacturer facts."
}

$recentsFallback = $pre | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$recentsFallback.taskRemoval.mechanism = "actual Recents dismiss via targeted swipe"
$recentsFallback.taskRemoval | Add-Member -NotePropertyName commandReportedFailure -NotePropertyValue $true -Force
$recentsFallback.taskRemoval | Add-Member -NotePropertyName taskPresentAfterCommand -NotePropertyValue $true -Force
$recentsFallback.taskRemoval | Add-Member -NotePropertyName recentsSwipePerformed -NotePropertyValue $true -Force
$recentsFallback.taskRemoval | Add-Member -NotePropertyName recentsDismissal -NotePropertyValue ([pscustomobject]@{ method = "input swipe inside observed product card"; windowPackage = "com.android.launcher3" }) -Force
$recentsFallback.taskRemoval | Add-Member -NotePropertyName recentsLabelWindowPackage -NotePropertyValue "com.android.launcher3" -Force
$fallbackEvidence = Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $recentsFallback -PostReboot $post -HostState $hostState
if (-not $fallbackEvidence.pass) { throw "Ticket 06 rejected a bounded targeted Recents dismissal after task-command failure." }
$unobservedFallback = $recentsFallback | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$unobservedFallback.taskRemoval.recentsSwipePerformed = $false
$rejectedUnobservedFallback = $false
try { Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $unobservedFallback -PostReboot $post -HostState $hostState | Out-Null }
catch { $rejectedUnobservedFallback = $true }
if (-not $rejectedUnobservedFallback) { throw "Ticket 06 accepted command failure without a targeted Recents dismissal." }

$wrongTaskComponent = $pre | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$wrongTaskComponent.taskRemoval.taskComponent = "com.enuma.todoenglish/org.cocos2dx.cpp.MainActivity"
$rejectedWrongTaskComponent = $false
try { Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $wrongTaskComponent -PostReboot $post -HostState $hostState | Out-Null }
catch { $rejectedWrongTaskComponent = $true }
if (-not $rejectedWrongTaskComponent) { throw "Ticket 06 accepted removal of a task outside the isolated product MainActivity." }

$unobservedCardFallback = $recentsFallback | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$unobservedCardFallback.taskRemoval.recentsViewLabelObserved = $false
$rejectedUnobservedCardFallback = $false
try { Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $unobservedCardFallback -PostReboot $post -HostState $hostState | Out-Null }
catch { $rejectedUnobservedCardFallback = $true }
if (-not $rejectedUnobservedCardFallback) { throw "Ticket 06 accepted a swipe without a visible exact product card label." }

$wrongWindowFallback = $recentsFallback | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$wrongWindowFallback.taskRemoval.recentsLabelWindowPackage = "com.example.todoschooltime.debug"
$rejectedProductWindowFallback = $false
try { Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $wrongWindowFallback -PostReboot $post -HostState $hostState | Out-Null }
catch { $rejectedProductWindowFallback = $true }
if (-not $rejectedProductWindowFallback) { throw "Ticket 06 accepted a card label from the active product window as Recents evidence." }

$changedOtherTask = $recentsFallback | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$changedOtherTask.taskRemoval.otherTaskIdsAfterRemoval = @(1220, 1221)
$rejectedOtherTaskChange = $false
try { Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $changedOtherTask -PostReboot $post -HostState $hostState | Out-Null }
catch { $rejectedOtherTaskChange = $true }
if (-not $rejectedOtherTaskChange) { throw "Ticket 06 accepted a task-removal gesture that changed another recent task." }

$chunkPayload = [ordered]@{ runId = $runId; scenario = "ticket06"; phase = "post-reboot"; text = ("A" * 4500) } | ConvertTo-Json -Compress
$chunkBytes = [Text.Encoding]::UTF8.GetBytes($chunkPayload)
$chunkText = [Convert]::ToBase64String($chunkBytes)
$chunkList = [System.Collections.Generic.List[string]]::new()
for ($start = 0; $start -lt $chunkText.Length; $start += 3000) {
    [void]$chunkList.Add($chunkText.Substring($start, [Math]::Min(3000, $chunkText.Length - $start)))
}
$chunkLines = for ($index = 0; $index -lt $chunkList.Count; $index++) {
    "I/Ticket06Result(123): TICKET06_RESULT_CHUNK:$runId`:$($index + 1)/$($chunkList.Count):$($chunkList[$index])"
}
if ((Join-Ticket06ResultChunks -LogLines $chunkLines -RunId $runId) -cne $chunkPayload) {
    throw "Ticket 06 structured result chunks did not round-trip exactly."
}
$duplicateChunksRejected = $false
try { Join-Ticket06ResultChunks -LogLines (@($chunkLines) + $chunkLines[0]) -RunId $runId | Out-Null }
catch { $duplicateChunksRejected = $true }
if (-not $duplicateChunksRejected) { throw "Ticket 06 accepted a duplicate result chunk." }
$missingChunksRejected = $false
try { Join-Ticket06ResultChunks -LogLines @($chunkLines | Select-Object -Skip 1) -RunId $runId | Out-Null }
catch { $missingChunksRejected = $true }
if (-not $missingChunksRejected) { throw "Ticket 06 accepted missing result chunks." }

$wrongScenario = $pre | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$wrongScenario.scenario = "ticket05"
$rejectedWrongScenario = $false
try { Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $wrongScenario -PostReboot $post -HostState $hostState | Out-Null }
catch { $rejectedWrongScenario = $true }
if (-not $rejectedWrongScenario) { throw "Ticket 06 accepted a foreign scenario result." }

$manualOpen = $post | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$manualOpen.productActivityLaunchedByController = $true
$rejectedManualOpen = $false
try { Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $pre -PostReboot $manualOpen -HostState $hostState | Out-Null }
catch { $rejectedManualOpen = $true }
if (-not $rejectedManualOpen) { throw "Ticket 06 accepted a post-reboot manual activity launch as automatic recovery." }

$hostBeforeReverse = $hostState | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$hostBeforeReverse.reboot.reverseRestoredBeforeServiceReady = $false
$rejectedLateReverse = $false
try { Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $pre -PostReboot $post -HostState $hostBeforeReverse | Out-Null }
catch { $rejectedLateReverse = $true }
if (-not $rejectedLateReverse) { throw "Ticket 06 accepted service readiness before restoring the fixture route." }

$missingPreference = $hostState | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$missingPreference.persistedIntervalAfterReboot = $false
$rejectedMissingPreference = $false
try { Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $pre -PostReboot $post -HostState $missingPreference | Out-Null }
catch { $rejectedMissingPreference = $true }
if (-not $rejectedMissingPreference) { throw "Ticket 06 accepted a missing persisted preference after reboot." }

$baselineAlert = $post | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$baselineAlert.baseline.completionAlertRequested = $true
$rejectedBaselineAlert = $false
try { Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $pre -PostReboot $baselineAlert -HostState $hostState | Out-Null }
catch { $rejectedBaselineAlert = $true }
if (-not $rejectedBaselineAlert) { throw "Ticket 06 accepted a completion alert during a completed-subject baseline." }

Write-Output "PASS Ticket06 recovery evidence gate (task removal, automatic reboot, route order, silent baseline)."
