$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "RecoveryEvidence.psm1") -Force

$runId = "0123456789abcdef0123456789abcdef"
$runnerPath = Join-Path $PSScriptRoot "Run-Ticket06.ps1"
$runnerAst = [System.Management.Automation.Language.Parser]::ParseFile($runnerPath, [ref]$null, [ref]$null)
$modeGuardAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -ceq "Resolve-Ticket06ExecutionMode"
}, $true)
if ($null -eq $modeGuardAst) { throw "Ticket 06 runner is missing its preflight execution mode guard." }
Invoke-Expression $modeGuardAst.Extent.Text

$preparedMode = Resolve-Ticket06ExecutionMode -RunPrepared -RefreshObserver:$false -Case "task-removal"
$preparedRebootMode = Resolve-Ticket06ExecutionMode -RunPrepared -RefreshObserver:$false -Case "reboot-recovery"
$refreshMode = Resolve-Ticket06ExecutionMode -RunPrepared:$false -RefreshObserver
if ($preparedMode -cne "run-prepared" -or $preparedRebootMode -cne "run-prepared" -or $refreshMode -cne "refresh-observer") {
    throw "Ticket 06 did not accept both explicit RunPrepared cases and the independent RefreshObserver mode."
}
foreach ($invalidMode in @(
    [pscustomobject]@{ name = "neither"; expectedText = "Choose exactly one of -RunPrepared or -RefreshObserver."; arguments = @("-ConfigPath", "build/e2e-ticket06/no-such-config.json") },
    [pscustomobject]@{ name = "both"; expectedText = "Choose exactly one of -RunPrepared or -RefreshObserver."; arguments = @("-ConfigPath", "build/e2e-ticket06/no-such-config.json", "-RunPrepared", "-RefreshObserver") },
    [pscustomobject]@{ name = "missing Case"; expectedText = "RunPrepared requires an explicit -Case: task-removal or reboot-recovery."; arguments = @("-ConfigPath", "build/e2e-ticket06/no-such-config.json", "-RunPrepared") },
    [pscustomobject]@{ name = "combined Case"; expectedText = "RunPrepared requires an explicit -Case: task-removal or reboot-recovery."; arguments = @("-ConfigPath", "build/e2e-ticket06/no-such-config.json", "-RunPrepared", "-Case", "all") }
)) {
    $repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
    $artifactRoot = Join-Path $repositoryRoot "build/e2e-ticket06"
    $beforeRunDirectories = @(
        Get-ChildItem -LiteralPath $artifactRoot -Directory -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty Name |
            Sort-Object
    )
    $powershellPath = (Get-Command powershell.exe -ErrorAction Stop).Source
    $childArguments = @("-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $runnerPath) + $invalidMode.arguments
    $childOutput = @()
    try {
        $childOutput = @(& $powershellPath @childArguments 2>&1)
    } catch {
        $childOutput += $_
    }
    $childExitCode = $LASTEXITCODE
    $childText = (@($childOutput | ForEach-Object { [string]$_ }) -join "`n")
    if ($childExitCode -eq 0 -or -not $childText.Contains($invalidMode.expectedText)) {
        throw "Ticket 06 runner did not reject the $($invalidMode.name) mode before reading its deliberately missing config."
    }
    $afterRunDirectories = @(
        Get-ChildItem -LiteralPath $artifactRoot -Directory -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty Name |
            Sort-Object
    )
    if (($beforeRunDirectories -join "`n") -cne ($afterRunDirectories -join "`n")) {
        throw "Ticket 06 runner created artifacts while rejecting the $($invalidMode.name) mode."
    }
}

$ensureReverseAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -ceq "Ensure-ReversePortMapping"
}, $true)
if ($null -eq $ensureReverseAst) { throw "Ticket 06 runner is missing its bounded reverse mapping verifier." }
Invoke-Expression $ensureReverseAst.Extent.Text
$serverPort = 18765
$script:ticket06MockReversePresent = $true
$script:ticket06LastReverseVerifiedAtUtc = $null
function Get-ReversePortMapping {
    if (-not $script:ticket06MockReversePresent) { return $null }
    $script:ticket06LastReverseVerifiedAtUtc = [DateTimeOffset]::UtcNow
    return "tcp:18765 tcp:18765"
}
function Invoke-SelectedAdb([string[]]$Arguments) {
    if ($Arguments.Count -ge 2 -and $Arguments[0] -ceq "reverse" -and $Arguments[1] -ceq "tcp:18765") {
        $script:ticket06MockReversePresent = $true
    }
    return @()
}
$reconnectObservedAtUtc = [DateTimeOffset]::UtcNow.AddSeconds(-1)
$reusedReverse = Ensure-ReversePortMapping
if ($reusedReverse.status -cne "reused" -or
    [DateTimeOffset]::Parse($reusedReverse.verifiedAtUtc) -lt $script:ticket06LastReverseVerifiedAtUtc -or
    [DateTimeOffset]::Parse($reusedReverse.verifiedAtUtc) -le $reconnectObservedAtUtc) {
    throw "Ticket 06 reused reverse mapping did not record its actual post-verification ready time."
}
$script:ticket06MockReversePresent = $false
$script:ticket06LastReverseVerifiedAtUtc = $null
$restoredReverse = Ensure-ReversePortMapping
if ($restoredReverse.status -cne "restored" -or
    [DateTimeOffset]::Parse($restoredReverse.verifiedAtUtc) -lt $script:ticket06LastReverseVerifiedAtUtc -or
    [DateTimeOffset]::Parse($restoredReverse.verifiedAtUtc) -le $reconnectObservedAtUtc) {
    throw "Ticket 06 restored reverse mapping did not record its actual post-verification ready time."
}

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
    baseline = [pscustomobject]@{ complete = $true; successfulSubjects = 3; completionAlertRequested = $false; fixtureRequestStartSequence = 1; fixtureRequestEndSequence = 7 }
    cadence = [pscustomobject]@{ baselineSigninElapsedRealtimeMs = 100000; nextCycleSigninElapsedRealtimeMs = 160200; clientDeltaMs = 60200; serverDeltaMs = 60204 }
    postRemovalRequestPaths = @(
        "/v3/account/signin", "/v3/todohangeul/user", "/v3/math/user", "/v3/english/user",
        "/v3/todoschool/report/daily/hangeul", "/v3/todoschool/report/daily/math", "/v3/todoschool/report/daily/english"
    )
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
    laterCompletion = [pscustomobject]@{ subject = "한글"; completionAlertRequested = $true; notificationIdReused = $true; notificationManagerCallReturned = $true }
    foreground = [pscustomobject]@{ packageName = "com.enuma.todohangeul"; eligibleRequests = 4; actualLeaseAttachedAtDispatch = $true }
}
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$diagnosticsProducerSource = Get-Content -LiteralPath (Join-Path $repositoryRoot "app/src/debug/java/com/example/todoschooltime/LearningMonitorDiagnosticsProvider.kt") -Raw
$observerConsumerSource = Get-Content -LiteralPath (Join-Path $repositoryRoot "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket06TaskRebootRecoveryTest.kt") -Raw
$producerKeyMatch = [regex]::Match($diagnosticsProducerSource, '\.put\("(?<key>[^"]+)", event\.notificationManagerCallReturned')
$observerReadKeyMatch = [regex]::Match($observerConsumerSource, 'newCompletion\.getBoolean\("(?<key>[^"]+)"\)')
$observerOutputKeyMatch = [regex]::Match($observerConsumerSource, '\.put\("(?<key>[^"]+)", newCompletion\.getBoolean\("(?<readKey>[^"]+)"\)\)')
if (-not $producerKeyMatch.Success -or -not $observerReadKeyMatch.Success -or -not $observerOutputKeyMatch.Success -or
    $producerKeyMatch.Groups["key"].Value -cne "notificationManagerCallReturned" -or
    $observerReadKeyMatch.Groups["key"].Value -cne $producerKeyMatch.Groups["key"].Value -or
    $observerOutputKeyMatch.Groups["key"].Value -cne $producerKeyMatch.Groups["key"].Value -or
    $observerOutputKeyMatch.Groups["readKey"].Value -cne $producerKeyMatch.Groups["key"].Value) {
    throw "Ticket 06 provider, observer, and host completion notification keys no longer share the real producer contract."
}
$reconnectMoment = [DateTimeOffset]::UtcNow.AddSeconds(-10)
$reverseReadyMoment = [DateTimeOffset]::UtcNow.AddSeconds(-8)
$serviceReadyMoment = [DateTimeOffset]::UtcNow.AddSeconds(-5)
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
    reboot = [pscustomobject]@{
        commandIssuedOnce = $true
        reconnectObserved = $true
        reconnectedAtUtc = $reconnectMoment.ToString("O")
        reverseReadyAtUtc = $reverseReadyMoment.ToString("O")
        serviceReadyAtUtc = $serviceReadyMoment.ToString("O")
        bootCompleted = $true
        lockscreenNeedsUnlock = $false
        reverseRestoredBeforeServiceReady = $true
        reverseStatus = "restored-after-reboot"
    }
}
$taskExpectedPaths = @(
    "/v3/account/signin", "/v3/todohangeul/user", "/v3/math/user", "/v3/english/user",
    "/v3/todoschool/report/daily/hangeul", "/v3/todoschool/report/daily/math", "/v3/todoschool/report/daily/english"
)
$taskFixtureRequests = @()
for ($requestIndex = 0; $requestIndex -lt ($taskExpectedPaths.Count * 2); $requestIndex++) {
    $taskFixtureRequests += [pscustomobject]@{ sequence = $requestIndex + 1; path = $taskExpectedPaths[$requestIndex % $taskExpectedPaths.Count] }
}
$taskEvidence = [pscustomobject]@{
    runId = $runId
    scenario = "ticket06"
    case = "task-removal"
    taskRemoval = $pre
    serviceState = [pscustomobject]@{
        beforeTaskRemoval = [pscustomobject]@{ pid = 1234; bound = $true; crashed = $false }
        afterTaskRemoval = [pscustomobject]@{ pid = 1234; bound = $true; crashed = $false }
        leaseAfterTaskRemoval = $true
    }
    preferences = [pscustomobject]@{
        registrationPersisted = $true
        targetsPersisted = $true
        intervalPersisted = $true
        registrationBeforeTaskRemoval = $hostState.preferences.registrationLineBeforeTaskRemoval
        registrationAfterTaskRemoval = $hostState.preferences.registrationLineAfterTaskRemoval
        targetsBeforeTaskRemoval = $hostState.preferences.targetCountsLineBeforeTaskRemoval
        targetsAfterTaskRemoval = $hostState.preferences.targetCountsLineAfterTaskRemoval
        intervalBeforeTaskRemoval = $hostState.preferences.intervalLineBeforeTaskRemoval
        intervalAfterTaskRemoval = $hostState.preferences.intervalLineAfterTaskRemoval
        credentialFileRead = $false
    }
    fixture = [pscustomobject]@{ runId = $runId; scenario = "ticket06"; requests = $taskFixtureRequests }
    executionScope = [pscustomobject]@{ rebootPerformed = $false; forceStopUsed = $false; actualTodoApiCalled = $false }
}
$taskRemovalSummary = Assert-Ticket06TaskRemovalEvidence -RunId $runId -Evidence $taskEvidence
if (-not $taskRemovalSummary.pass -or $taskRemovalSummary.rebootCount -ne 0 -or $taskRemovalSummary.taskId -ne 2471) {
    throw "Valid task-only Ticket 06 evidence did not remain independent from reboot evidence."
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
function Copy-Ticket06TestObject($Value) {
    return ConvertTo-Json -InputObject $Value -Depth 16 | ConvertFrom-Json
}
function Assert-Ticket06BootRejected([string]$Name, $Seed, $Candidate, [string]$ExpectedMessage) {
    try {
        $seedResult = Assert-Ticket06BootRecoveryEvidence -RunId $runId -Preparation $Seed.preparation -PostReboot $Seed.postReboot -HostState $Seed.hostState
        if (-not $seedResult.pass) { throw "The $Name negative-case seed did not return pass=true." }
    } catch {
        throw "The $Name negative-case seed is invalid: $($_.Exception.Message)"
    }

    $actualMessage = $null
    try {
        Assert-Ticket06BootRecoveryEvidence -RunId $runId -Preparation $Candidate.preparation -PostReboot $Candidate.postReboot -HostState $Candidate.hostState | Out-Null
    } catch { $actualMessage = $_.Exception.Message }
    if ($null -eq $actualMessage) { throw "Ticket 06 reboot validator accepted $Name." }
    if ($actualMessage.IndexOf($ExpectedMessage, [StringComparison]::Ordinal) -lt 0) {
        throw "Ticket 06 reboot validator rejected $Name for an unrelated reason: $actualMessage"
    }
}
$bootValidSeed = [pscustomobject]@{
    preparation = Copy-Ticket06TestObject $bootPreparation
    postReboot = Copy-Ticket06TestObject $bootPost
    hostState = Copy-Ticket06TestObject $bootHost
}

$missingManagerCandidate = Copy-Ticket06TestObject $bootValidSeed
$missingManagerCandidate.postReboot.laterCompletion = [pscustomobject]@{
    subject = "한글"
    completionAlertRequested = $true
    notificationIdReused = $true
    activeNotificationId = 1
    completionCycleSigninElapsedRealtimeMs = 160000
}
$missingManagerExpected = "later newly achieved subject updated its existing notification"
Assert-Ticket06BootRejected "later completion with a missing producer field" $bootValidSeed $missingManagerCandidate $missingManagerExpected

$falseManagerCandidate = Copy-Ticket06TestObject $bootValidSeed
$falseManagerCandidate.postReboot.laterCompletion.notificationManagerCallReturned = $false
$falseManagerCandidate.postReboot.laterCompletion | Add-Member -NotePropertyName managerCallReturned -NotePropertyValue $true -Force
Assert-Ticket06BootRejected "later completion with a false producer field and stale alias" $bootValidSeed $falseManagerCandidate $missingManagerExpected

$manualActivityCandidate = Copy-Ticket06TestObject $bootValidSeed
$manualActivityCandidate.postReboot.productActivityLaunchedByController = $true
Assert-Ticket06BootRejected "manual product Activity launch" $bootValidSeed $manualActivityCandidate "must explicitly record silent baselines"

$earlyCycleCandidate = Copy-Ticket06TestObject $bootValidSeed
$earlyCycleCandidate.postReboot.baseline.fixtureRequestStartSequence = 20
Assert-Ticket06BootRejected "post-boot cycle before the pre-reboot fixture cursor" $bootValidSeed $earlyCycleCandidate "did not follow the pre-reboot fixture cursor"

$taskRemovalRemainsSeparate = $bootEvidence | Get-Member -Name taskRemovalMechanism -ErrorAction SilentlyContinue
if ($null -ne $taskRemovalRemainsSeparate) { throw "Ticket 06 reboot-only summary falsely claimed a task-removal mechanism." }

function Assert-Ticket06TaskRejected([string]$Name, $Seed, $Candidate, [string]$ExpectedMessage) {
    try {
        $seedResult = Assert-Ticket06TaskRemovalEvidence -RunId $runId -Evidence $Seed
        if (-not $seedResult.pass) { throw "The $Name negative-case seed did not return pass=true." }
    } catch {
        throw "The $Name negative-case seed is invalid: $($_.Exception.Message)"
    }

    $actualMessage = $null
    try { Assert-Ticket06TaskRemovalEvidence -RunId $runId -Evidence $Candidate | Out-Null }
    catch { $actualMessage = $_.Exception.Message }
    if ($null -eq $actualMessage) { throw "Ticket 06 task-removal validator accepted $Name." }
    if ($actualMessage.IndexOf($ExpectedMessage, [StringComparison]::Ordinal) -lt 0) {
        throw "Ticket 06 task-removal validator rejected $Name for an unrelated reason: $actualMessage"
    }
}
function Set-Ticket06TestPropertyByPath($Root, [string]$Path, [string]$Mutation) {
    $segments = $Path.Split('.')
    $target = $Root
    for ($index = 0; $index -lt ($segments.Count - 1); $index++) {
        $property = $target.PSObject.Properties[$segments[$index]]
        if ($null -eq $property -or $null -eq $property.Value) { throw "Test property path is missing: $Path" }
        $target = $property.Value
    }
    $propertyName = $segments[-1]
    $property = $target.PSObject.Properties[$propertyName]
    switch ($Mutation) {
        "missing" {
            if ($null -eq $property) { throw "Test property was already missing: $Path" }
            [void]$target.PSObject.Properties.Remove($propertyName)
        }
        "null" { $target.$propertyName = $null }
        "string false" { $target.$propertyName = [string]"false" }
        "number zero" { $target.$propertyName = [int]0 }
        "true" { $target.$propertyName = [bool]$true }
        default { throw "Unknown test mutation: $Mutation" }
    }
}
$requiredFalseTaskProperties = @(
    [pscustomobject]@{ path = "taskRemoval.taskRemoval.forceStopUsed"; name = "task.forceStopUsed" },
    [pscustomobject]@{ path = "taskRemoval.baseline.completionAlertRequested"; name = "task-removal baseline completionAlertRequested" },
    [pscustomobject]@{ path = "executionScope.rebootPerformed"; name = "executionScope.rebootPerformed" },
    [pscustomobject]@{ path = "executionScope.forceStopUsed"; name = "executionScope.forceStopUsed" },
    [pscustomobject]@{ path = "executionScope.actualTodoApiCalled"; name = "executionScope.actualTodoApiCalled" }
)
$invalidFalseMutations = @("missing", "null", "string false", "number zero", "true")
foreach ($field in $requiredFalseTaskProperties) {
    foreach ($mutation in $invalidFalseMutations) {
        $candidate = Copy-Ticket06TestObject $taskEvidence
        Set-Ticket06TestPropertyByPath -Root $candidate -Path $field.path -Mutation $mutation
        Assert-Ticket06TaskRejected "$($field.name)=$mutation" $taskEvidence $candidate "must explicitly record no force-stop"
    }
}

$requiredFalseBootProperties = @(
    [pscustomobject]@{ root = "preparation"; path = "baseline.completionAlertRequested"; name = "preparation baseline completionAlertRequested" },
    [pscustomobject]@{ root = "postReboot"; path = "productActivityLaunchedByController"; name = "post-reboot productActivityLaunchedByController" },
    [pscustomobject]@{ root = "postReboot"; path = "baseline.completionAlertRequested"; name = "post-reboot baseline completionAlertRequested" },
    [pscustomobject]@{ root = "hostState"; path = "reboot.lockscreenNeedsUnlock"; name = "reboot lockscreenNeedsUnlock" }
)
foreach ($field in $requiredFalseBootProperties) {
    foreach ($mutation in $invalidFalseMutations) {
        $candidate = Copy-Ticket06TestObject $bootValidSeed
        $candidateRoot = switch ($field.root) {
            "preparation" { $candidate.preparation }
            "postReboot" { $candidate.postReboot }
            "hostState" { $candidate.hostState }
        }
        Set-Ticket06TestPropertyByPath -Root $candidateRoot -Path $field.path -Mutation $mutation
        Assert-Ticket06BootRejected "$($field.name)=$mutation" $bootValidSeed $candidate "must explicitly record silent baselines"
    }
}

$recentsFallback = Copy-Ticket06TestObject $taskEvidence
$recentsFallback.taskRemoval.taskRemoval.mechanism = "actual Recents dismiss via targeted swipe"
$recentsFallback.taskRemoval.taskRemoval | Add-Member -NotePropertyName commandReportedFailure -NotePropertyValue $true -Force
$recentsFallback.taskRemoval.taskRemoval | Add-Member -NotePropertyName taskPresentAfterCommand -NotePropertyValue $true -Force
$recentsFallback.taskRemoval.taskRemoval | Add-Member -NotePropertyName recentsSwipePerformed -NotePropertyValue $true -Force
$recentsFallback.taskRemoval.taskRemoval | Add-Member -NotePropertyName recentsDismissal -NotePropertyValue ([pscustomobject]@{ method = "input swipe inside observed product card"; windowPackage = "com.android.launcher3" }) -Force
$recentsFallback.taskRemoval.taskRemoval | Add-Member -NotePropertyName recentsLabelWindowPackage -NotePropertyValue "com.android.launcher3" -Force
$fallbackEvidence = Assert-Ticket06TaskRemovalEvidence -RunId $runId -Evidence $recentsFallback
if (-not $fallbackEvidence.pass) { throw "Ticket 06 rejected a bounded targeted Recents dismissal after task-command failure." }

$unobservedFallback = Copy-Ticket06TestObject $recentsFallback
$unobservedFallback.taskRemoval.taskRemoval.recentsSwipePerformed = $false
Assert-Ticket06TaskRejected "a command failure without a targeted Recents dismissal" $recentsFallback $unobservedFallback "does not prove removal of only the observed debug task"

$wrongTaskComponent = Copy-Ticket06TestObject $taskEvidence
$wrongTaskComponent.taskRemoval.taskRemoval.taskComponent = "com.enuma.todoenglish/org.cocos2dx.cpp.MainActivity"
Assert-Ticket06TaskRejected "a task outside the isolated product MainActivity" $taskEvidence $wrongTaskComponent "does not prove removal of only the observed debug task"

$unobservedCardFallback = Copy-Ticket06TestObject $recentsFallback
$unobservedCardFallback.taskRemoval.taskRemoval.recentsViewLabelObserved = $false
Assert-Ticket06TaskRejected "a swipe without a visible exact product card label" $recentsFallback $unobservedCardFallback "does not prove removal of only the observed debug task"

$wrongWindowFallback = Copy-Ticket06TestObject $recentsFallback
$wrongWindowFallback.taskRemoval.taskRemoval.recentsLabelWindowPackage = "com.example.todoschooltime.debug"
Assert-Ticket06TaskRejected "a card label from the active product window" $recentsFallback $wrongWindowFallback "does not prove removal of only the observed debug task"

$changedOtherTask = Copy-Ticket06TestObject $recentsFallback
$changedOtherTask.taskRemoval.taskRemoval.otherTaskIdsAfterRemoval = @(1220, 1221)
Assert-Ticket06TaskRejected "a gesture that changed another recent task" $recentsFallback $changedOtherTask "does not prove removal of only the observed debug task"

$wrongTaskScenario = Copy-Ticket06TestObject $taskEvidence
$wrongTaskScenario.taskRemoval.scenario = "ticket05"
Assert-Ticket06TaskRejected "a foreign observer scenario" $taskEvidence $wrongTaskScenario "observer result has the wrong run ID, scenario, or phase"

$unexpectedTaskReboot = Copy-Ticket06TestObject $taskEvidence
$unexpectedTaskReboot.executionScope.rebootPerformed = $true
Assert-Ticket06TaskRejected "a reboot inside the task-removal case" $taskEvidence $unexpectedTaskReboot "must explicitly record no force-stop"

$taskForceStop = Copy-Ticket06TestObject $taskEvidence
$taskForceStop.executionScope.forceStopUsed = $true
Assert-Ticket06TaskRejected "force-stop usage" $taskEvidence $taskForceStop "must explicitly record no force-stop"

$detachedTaskService = Copy-Ticket06TestObject $taskEvidence
$detachedTaskService.serviceState.afterTaskRemoval.bound = $false
Assert-Ticket06TaskRejected "a detached monitor service after task removal" $taskEvidence $detachedTaskService "did not keep the actual monitor service bound with its lease attached"

$changedTaskPreference = Copy-Ticket06TestObject $taskEvidence
$changedTaskPreference.preferences.intervalAfterTaskRemoval = '<string name="interval.key">2</string>'
Assert-Ticket06TaskRejected "changed saved preferences after task removal" $taskEvidence $changedTaskPreference "did not preserve the exact filtered app-owned preference rows"

$incompleteTaskFixture = Copy-Ticket06TestObject $taskEvidence
$incompleteTaskFixture.fixture.requests = @($incompleteTaskFixture.fixture.requests | Select-Object -First 8)
Assert-Ticket06TaskRejected "an incomplete post-removal fixture cycle" $taskEvidence $incompleteTaskFixture "does not prove a full post-removal local API cycle"

$unanchoredTaskFixture = Copy-Ticket06TestObject $taskEvidence
$unanchoredTaskFixture.fixture.requests[7].sequence = 9
Assert-Ticket06TaskRejected "a fixture cycle that does not begin immediately after the completed baseline" $taskEvidence $unanchoredTaskFixture "does not prove a full post-removal local API cycle"

$taskBaselineAlert = Copy-Ticket06TestObject $taskEvidence
$taskBaselineAlert.taskRemoval.baseline.completionAlertRequested = $true
Assert-Ticket06TaskRejected "a completion notification during the completed baseline" $taskEvidence $taskBaselineAlert "must explicitly record no force-stop"

$taskCadenceMismatch = Copy-Ticket06TestObject $taskEvidence
$taskCadenceMismatch.taskRemoval.cadence.serverDeltaMs = 50000
Assert-Ticket06TaskRejected "a cadence inconsistent with the server monotonic dispatches" $taskEvidence $taskCadenceMismatch "does not match actual client elapsed-realtime and fixture server monotonic dispatches"
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
$duplicateChunkError = $null
try { Join-Ticket06ResultChunks -LogLines (@($chunkLines) + $chunkLines[0]) -RunId $runId | Out-Null }
catch { $duplicateChunkError = $_.Exception.Message }
if ($null -eq $duplicateChunkError) { throw "Ticket 06 accepted a duplicate result chunk." }
if ($duplicateChunkError.IndexOf("is duplicated.", [StringComparison]::Ordinal) -lt 0) {
    throw "Ticket 06 rejected duplicate chunks for an unrelated reason: $duplicateChunkError"
}
$missingChunkError = $null
try { Join-Ticket06ResultChunks -LogLines @($chunkLines | Select-Object -Skip 1) -RunId $runId | Out-Null }
catch { $missingChunkError = $_.Exception.Message }
if ($null -eq $missingChunkError) { throw "Ticket 06 accepted missing result chunks." }
if ($missingChunkError.IndexOf("is missing one or more chunks.", [StringComparison]::Ordinal) -lt 0) {
    throw "Ticket 06 rejected missing chunks for an unrelated reason: $missingChunkError"
}

$lateReverseCandidate = Copy-Ticket06TestObject $bootValidSeed
$lateReverseCandidate.hostState.reboot.reverseReadyAtUtc = $serviceReadyMoment.AddSeconds(1).ToString("O")
Assert-Ticket06BootRejected "a reverse mapping verification completed after service readiness" $bootValidSeed $lateReverseCandidate "did not prove one bounded reboot, ready/unlocked device, and fixture route before service readiness"

$earlyReverseCandidate = Copy-Ticket06TestObject $bootValidSeed
$earlyReverseCandidate.hostState.reboot.reverseReadyAtUtc = $reconnectMoment.AddSeconds(-1).ToString("O")
Assert-Ticket06BootRejected "a reverse mapping verification timestamp before device reconnection" $bootValidSeed $earlyReverseCandidate "did not prove one bounded reboot, ready/unlocked device, and fixture route before service readiness"

$missingPreferenceCandidate = Copy-Ticket06TestObject $bootValidSeed
$missingPreferenceCandidate.hostState.persistedIntervalAfterReboot = $false
Assert-Ticket06BootRejected "a missing persisted preference after reboot" $bootValidSeed $missingPreferenceCandidate "filtered preferences did not prove registration, targets, and interval on both sides of reboot"

$baselineAlertCandidate = Copy-Ticket06TestObject $bootValidSeed
$baselineAlertCandidate.postReboot.baseline.completionAlertRequested = $true
Assert-Ticket06BootRejected "a completion alert during the reboot baseline" $bootValidSeed $baselineAlertCandidate "must explicitly record silent baselines"
Write-Output "PASS Ticket06 independent task/reboot evidence, producer contract, and verified reverse timing."
