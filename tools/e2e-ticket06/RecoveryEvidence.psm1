function Join-Ticket06ResultChunks {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$LogLines,
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-f]{32}$')]
        [string]$RunId
    )

    $marker = "TICKET06_RESULT_CHUNK:"
    $chunks = @{}
    $expectedCount = $null
    $sawTargetChunk = $false
    foreach ($line in $LogLines) {
        if (-not $line.Contains($marker)) { continue }
        $match = [regex]::Match(
            $line,
            'TICKET06_RESULT_CHUNK:(?<runId>[^:\s]+):(?<index>[0-9]+)/(?<count>[0-9]+):(?<payload>[A-Za-z0-9+/=]*)'
        )
        if (-not $match.Success) {
            if ($line.Contains("$marker$RunId`:")) {
                throw "A Ticket 06 result chunk for run '$RunId' has an invalid header or payload."
            }
            continue
        }
        if ($match.Groups['runId'].Value -cne $RunId) { continue }

        $sawTargetChunk = $true
        $index = [int]$match.Groups['index'].Value
        $count = [int]$match.Groups['count'].Value
        $payload = $match.Groups['payload'].Value
        if ($count -lt 1 -or $index -lt 1 -or $index -gt $count -or [string]::IsNullOrEmpty($payload)) {
            throw "Ticket 06 result chunks for run '$RunId' have an invalid index, count, or empty payload."
        }
        if ($null -ne $expectedCount -and $expectedCount -ne $count) {
            throw "Ticket 06 result chunks for run '$RunId' disagree about the total chunk count."
        }
        if ($chunks.ContainsKey($index)) {
            throw "Ticket 06 result chunk $index/$count for run '$RunId' is duplicated."
        }
        $expectedCount = $count
        $chunks[$index] = $payload
    }

    if (-not $sawTargetChunk -or $null -eq $expectedCount -or $chunks.Count -ne $expectedCount) {
        throw "Ticket 06 structured result for run '$RunId' is missing one or more chunks."
    }

    $encoded = ((1..$expectedCount | ForEach-Object { $chunks[$_] }) -join '')
    try {
        $bytes = [Convert]::FromBase64String($encoded)
        $utf8 = [Text.UTF8Encoding]::new($false, $true)
        $json = $utf8.GetString($bytes)
        $result = $json | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "Ticket 06 result chunks for run '$RunId' are not valid UTF-8 JSON: $($_.Exception.Message)"
    }
    if ($result.runId -cne $RunId) {
        throw "Ticket 06 result chunks for run '$RunId' contain payload for another run."
    }
    return $json
}

function Get-Ticket06PreferenceSelectors {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$AccountKey,
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$ChildName
    )

    return [pscustomobject]@{
        registrationPattern = 'registrations.' + $AccountKey
        intervalPattern = 'interval.' + $AccountKey
        targetsPattern = $ChildName.Replace(' ', '\ ')
    }
}

function ConvertFrom-Ticket06PreferenceRows {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$RegistrationRows,
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$IntervalRows,
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$TargetRows,
        [string]$AccountKey = 'dGlja2V0MDEtZmFrZS1hY2NvdW50',
        [string]$ChildName = '테스트 아이'
    )

    $registrationPersisted = $false
    $registrationChildren = @()
    if ($RegistrationRows.Count -eq 3) {
        try {
            $registrationXml = [xml]("<map>" + ($RegistrationRows -join "`n") + "</map>")
            $registrationSet = $registrationXml.DocumentElement.SelectSingleNode("set[@name='registrations.$AccountKey']")
            if ($null -ne $registrationSet) {
                $registrationChildren = @($registrationSet.SelectNodes('string') | ForEach-Object { [string]$_.InnerText })
                $registrationPersisted = $registrationChildren.Count -eq 1 -and $registrationChildren[0] -ceq $ChildName
            }
        } catch { }
    }

    $intervalPersisted = $false
    $intervalMinutes = $null
    if ($IntervalRows.Count -eq 1) {
        try {
            $intervalXml = [xml]$IntervalRows[0].Trim()
            $intervalRow = $intervalXml.DocumentElement
            if ($intervalRow.GetAttribute('name') -ceq "interval.$AccountKey") {
                $rawInterval = if ($intervalRow.LocalName -ceq 'string') { [string]$intervalRow.InnerText }
                    elseif ($intervalRow.LocalName -in @('int', 'long')) { [string]$intervalRow.GetAttribute('value') }
                    else { '' }
                $parsedInterval = 0
                if ([int]::TryParse($rawInterval, [ref]$parsedInterval) -and $parsedInterval -eq 1) {
                    $intervalPersisted = $true
                    $intervalMinutes = $parsedInterval
                }
            }
        } catch { }
    }

    $targetsPersisted = $false
    $targetCounts = $null
    if ($TargetRows.Count -eq 1) {
        try {
            $targetXml = [xml]$TargetRows[0].Trim()
            $targetRow = $targetXml.DocumentElement
            if ($targetRow.LocalName -ceq 'string' -and $targetRow.GetAttribute('name') -ceq $ChildName) {
                $counts = $targetRow.InnerText | ConvertFrom-Json -ErrorAction Stop
                $hangeul = $counts.PSObject.Properties['한글'].Value
                $math = $counts.PSObject.Properties['수학'].Value
                $english = $counts.PSObject.Properties['영어'].Value
                $targetCounts = [pscustomobject]@{ hangeul = $hangeul; math = $math; english = $english }
                $targetsPersisted = [int]$hangeul -eq 6 -and [int]$math -eq 5 -and [int]$english -eq 8
            }
        } catch { }
    }

    return [pscustomobject]@{
        registrationLine = if ($RegistrationRows.Count -gt 0) { $RegistrationRows -join "`n" } else { $null }
        intervalLine = if ($IntervalRows.Count -eq 1) { [string]$IntervalRows[0] } else { $null }
        targetCountsLine = if ($TargetRows.Count -eq 1) { [string]$TargetRows[0] } else { $null }
        registrationChildren = $registrationChildren
        registrationPersisted = $registrationPersisted
        intervalMinutes = $intervalMinutes
        intervalPersisted = $intervalPersisted
        targetCounts = $targetCounts
        targetsPersisted = $targetsPersisted
        credentialFileRead = $false
    }
}

function New-Ticket06VerifiedReverseMapping {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('reused', 'restored')]
        [string]$Status,
        [Parameter(Mandatory = $true)]
        [ValidateRange(1024, 65535)]
        [int]$LocalPort,
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string]$Mapping,
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 1000)]
        [int]$AttemptCount,
        [DateTimeOffset]$VerifiedAtUtc = [DateTimeOffset]::UtcNow
    )

    if ($Mapping -notmatch "(?:^|\s)tcp:$LocalPort\s+tcp:$LocalPort(?:\s|$)") {
        throw "The reverse mapping evidence does not match local tcp:$LocalPort."
    }
    return [pscustomobject]@{
        status = $Status
        mapping = $Mapping
        verifiedAtUtc = $VerifiedAtUtc.ToString('O')
        attemptCount = $AttemptCount
    }
}

function Assert-Ticket06BootRecoveryEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-f]{32}$')]
        [string]$RunId,
        [Parameter(Mandatory = $true)]$Preparation,
        [Parameter(Mandatory = $true)]$PostReboot,
        [Parameter(Mandatory = $true)]$HostState
    )

    foreach ($phase in @(
        [pscustomobject]@{ name = 'reboot-preparation'; result = $Preparation },
        [pscustomobject]@{ name = 'post-reboot'; result = $PostReboot }
    )) {
        if ($phase.result.runId -cne $RunId -or $phase.result.scenario -cne 'ticket06' -or $phase.result.phase -cne $phase.name) {
            throw "Ticket 06 $($phase.name) result has the wrong run ID, scenario, or phase."
        }
    }
    if ($HostState.runId -cne $RunId) { throw 'Ticket 06 reboot-only host evidence belongs to another run.' }
    try {
        $reconnectedAt = [DateTimeOffset]::Parse([string]$HostState.reboot.reconnectedAtUtc, [Globalization.CultureInfo]::InvariantCulture)
        $reverseReadyAt = [DateTimeOffset]::Parse([string]$HostState.reboot.reverseReadyAtUtc, [Globalization.CultureInfo]::InvariantCulture)
        $serviceReadyAt = [DateTimeOffset]::Parse([string]$HostState.reboot.serviceReadyAtUtc, [Globalization.CultureInfo]::InvariantCulture)
    } catch {
        throw 'Ticket 06 reboot-only host evidence is missing a valid reconnect, verified reverse-ready, or service-ready timestamp.'
    }
    $reverseReadyBeforeService = $reverseReadyAt -ge $reconnectedAt -and $reverseReadyAt -le $serviceReadyAt
    if ($HostState.preRebootPreferenceGate.runId -cne $RunId -or
        $HostState.preRebootPreferenceGate.acknowledgementRunId -cne $RunId -or
        $HostState.preRebootPreferenceGate.stage -cne 'registered-before-reboot' -or
        -not $HostState.preRebootPreferenceGate.fixtureAcknowledged) {
        throw 'Ticket 06 reboot-only case did not capture and acknowledge saved preferences before reboot.'
    }
    if (-not $Preparation.serviceLeaseAttached -or
        @($Preparation.registration).Count -lt 1 -or
        -not $Preparation.baseline.complete -or [int]$Preparation.baseline.successfulSubjects -ne 3 -or
        $Preparation.baseline.completionAlertRequested -or
        -not $Preparation.interval.persistedExplicitly -or [int]$Preparation.interval.minutes -lt 1) {
        throw 'Ticket 06 reboot preparation did not establish an actual lease, saved registration, explicit interval, and silent complete baseline.'
    }
    if (-not $PostReboot.automaticServiceBound -or $PostReboot.productActivityLaunchedByController -or
        -not $PostReboot.serviceLeaseAttached) {
        throw 'Ticket 06 reboot-only case did not observe the real service automatically before opening the product Activity.'
    }
    if (-not $HostState.reboot.commandIssuedOnce -or -not $HostState.reboot.reconnectObserved -or
        -not $HostState.reboot.bootCompleted -or $HostState.reboot.lockscreenNeedsUnlock -or
        -not $HostState.reboot.reverseRestoredBeforeServiceReady -or -not $reverseReadyBeforeService -or
        $HostState.reboot.reverseStatus -notin @('reused-before-reboot', 'restored-after-reboot')) {
        throw 'Ticket 06 reboot-only host evidence did not prove one bounded reboot, ready/unlocked device, and fixture route before service readiness.'
    }
    if (-not $HostState.persistedRegistrationBeforeReboot -or -not $HostState.persistedRegistrationAfterReboot -or
        -not $HostState.persistedIntervalBeforeReboot -or -not $HostState.persistedIntervalAfterReboot -or
        -not $HostState.persistedTargetsBeforeReboot -or -not $HostState.persistedTargetsAfterReboot) {
        throw 'Ticket 06 reboot-only filtered preferences did not prove registration, targets, and interval on both sides of reboot.'
    }
    if ($HostState.preferences.registrationLineBeforeReboot -cne $HostState.preferences.registrationLineAfterReboot -or
        $HostState.preferences.targetCountsLineBeforeReboot -cne $HostState.preferences.targetCountsLineAfterReboot -or
        $HostState.preferences.intervalLineBeforeReboot -cne $HostState.preferences.intervalLineAfterReboot) {
        throw 'Ticket 06 reboot-only saved preference rows changed across reboot.'
    }
    $expectedPaths = @(
        '/v3/account/signin', '/v3/todohangeul/user', '/v3/math/user', '/v3/english/user',
        '/v3/todoschool/report/daily/hangeul', '/v3/todoschool/report/daily/math', '/v3/todoschool/report/daily/english'
    )
    $actualPaths = @($PostReboot.baseline.requestPaths | ForEach-Object { [string]$_ })
    $postBootBaselineValid = $PostReboot.baseline.complete -and
        [int]$PostReboot.baseline.successfulSubjects -eq 3 -and
        -not $PostReboot.baseline.completionAlertRequested -and
        @($PostReboot.baseline.alreadyCompleteSubjects) -contains '수학' -and
        [long]$PostReboot.baseline.fixtureRequestStartSequence -gt [long]$HostState.fixture.sequenceBeforeReboot -and
        $actualPaths.Count -eq $expectedPaths.Count -and
        [string]::Join("`n", $actualPaths) -ceq [string]::Join("`n", $expectedPaths)
    if (-not $postBootBaselineValid) {
        throw 'Ticket 06 reboot-only first successful post-boot cycle was incomplete, notified an already-complete subject, or did not follow the pre-reboot fixture cursor.'
    }
    if (-not $PostReboot.interval.persistedExplicitly -or
        [int]$PostReboot.interval.minutes -ne [int]$Preparation.interval.minutes -or
        (($PostReboot.registration | Sort-Object) -join "`n") -cne (($Preparation.registration | Sort-Object) -join "`n") -or
        (ConvertTo-Json -InputObject $PostReboot.targets -Compress -Depth 4) -cne
        (ConvertTo-Json -InputObject $Preparation.targets -Compress -Depth 4)) {
        throw 'Ticket 06 reboot-only registration, target counts, or explicitly saved common interval changed across reboot.'
    }
    if ($PostReboot.foreground.packageName -cne 'com.enuma.todohangeul' -or
        [int]$PostReboot.foreground.eligibleRequests -lt 1 -or
        -not $PostReboot.foreground.actualLeaseAttachedAtDispatch) {
        throw 'Ticket 06 reboot-only case did not prove an eligible real Todo foreground request with the actual service lease.'
    }
    if ($PostReboot.laterCompletion.subject -cne '한글' -or
        -not $PostReboot.laterCompletion.completionAlertRequested -or
        -not $PostReboot.laterCompletion.notificationIdReused -or
        -not $PostReboot.laterCompletion.notificationManagerCallReturned) {
        throw 'Ticket 06 reboot-only case did not prove a later newly achieved subject updated its existing notification.'
    }

    return [pscustomobject]@{
        pass = $true
        runId = $RunId
        scenario = 'ticket06'
        case = 'reboot-recovery'
        rebootCount = 1
        taskRemovalCase = 'not-run-in-reboot-case'
        registrationPersisted = $true
        targetsPersisted = $true
        explicitIntervalMinutes = [int]$PostReboot.interval.minutes
        postRebootAutomaticServiceBound = $true
        postRebootBaselineSilent = $true
        laterNotificationUpdated = $true
    }
}

function Assert-Ticket06TaskRemovalEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-f]{32}$')]
        [string]$RunId,
        [Parameter(Mandatory = $true)]$Evidence
    )

    if ($Evidence.runId -cne $RunId -or $Evidence.scenario -cne 'ticket06' -or $Evidence.case -cne 'task-removal') {
        throw 'Ticket 06 task-removal evidence has the wrong run ID, scenario, or case.'
    }
    $observer = $Evidence.taskRemoval
    if ($observer.runId -cne $RunId -or $observer.scenario -cne 'ticket06' -or $observer.phase -cne 'task-removal') {
        throw 'Ticket 06 task-removal observer result has the wrong run ID, scenario, or phase.'
    }

    $task = $observer.taskRemoval
    $taskRemovalMechanism = [string]$task.mechanism
    $commandRemoval = $taskRemovalMechanism -ceq 'adb shell am task remove'
    $recentsDismissal = $taskRemovalMechanism -ceq 'actual Recents dismiss via targeted swipe'
    $fallbackHasEvidence = [bool]$task.recentsSwipePerformed -and
        ([bool]$task.commandReportedFailure -or [bool]$task.taskPresentAfterCommand) -and
        $null -ne $task.recentsDismissal -and $task.recentsDismissal -isnot [System.DBNull]
    $expectedTaskComponent = 'com.example.todoschooltime.debug/com.example.todoschooltime.MainActivity'
    $expectedOtherTaskIds = @($task.otherTaskIdsBeforeRemoval | ForEach-Object { [string]$_ })
    $actualOtherTaskIds = @($task.otherTaskIdsAfterRemoval | ForEach-Object { [string]$_ })
    if (-not $task.recentsObserved -or
        [int]$task.taskId -lt 1 -or
        [string]$task.taskComponent -cne $expectedTaskComponent -or
        (-not $commandRemoval -and -not $recentsDismissal) -or
        ($commandRemoval -and [bool]$task.recentsSwipePerformed) -or
        ($recentsDismissal -and (-not $fallbackHasEvidence -or -not $task.recentsViewLabelObserved -or
            [string]::IsNullOrWhiteSpace([string]$task.recentsLabelWindowPackage) -or
            [string]$task.recentsLabelWindowPackage -ceq 'com.example.todoschooltime.debug')) -or
        -not $task.taskAbsentAfterRemoval -or
        -not $task.otherTasksUnchanged -or
        [string]::Join("`n", $expectedOtherTaskIds) -cne [string]::Join("`n", $actualOtherTaskIds) -or
        $task.forceStopUsed) {
        throw 'Ticket 06 task-removal evidence does not prove removal of only the observed debug task without force-stop.'
    }
    if (-not $observer.baseline.complete -or [int]$observer.baseline.successfulSubjects -ne 3 -or
        $observer.baseline.completionAlertRequested) {
        throw 'Ticket 06 task-removal baseline is incomplete or requested an unexpected completion alert.'
    }
    if (-not $observer.interval.persistedExplicitly -or [int]$observer.interval.minutes -ne 1 -or
        @($observer.registration) -notcontains '테스트 아이' -or
        [int]$observer.targets.hangeul -ne 6 -or [int]$observer.targets.math -ne 5 -or [int]$observer.targets.english -ne 8) {
        throw 'Ticket 06 task-removal case did not preserve the registered child, target counts, and explicit one-minute interval.'
    }

    $clientDelta = [long]$observer.cadence.nextCycleSigninElapsedRealtimeMs - [long]$observer.cadence.baselineSigninElapsedRealtimeMs
    if ($clientDelta -ne [long]$observer.cadence.clientDeltaMs -or
        $clientDelta -lt ([long]$observer.interval.minutes * 60000L) -or
        $clientDelta -ge ([long]($observer.interval.minutes + 2) * 60000L) -or
        [long]$observer.cadence.serverDeltaMs -le 0 -or
        [Math]::Abs($clientDelta - [long]$observer.cadence.serverDeltaMs) -ge 5000L) {
        throw 'Ticket 06 task-removal cadence does not match actual client elapsed-realtime and fixture server monotonic dispatches.'
    }

    $serviceBefore = $Evidence.serviceState.beforeTaskRemoval
    $serviceAfter = $Evidence.serviceState.afterTaskRemoval
    if ([int]$serviceBefore.pid -lt 1 -or [int]$serviceBefore.pid -ne [int]$serviceAfter.pid -or
        -not $serviceBefore.bound -or -not $serviceAfter.bound -or $serviceBefore.crashed -or $serviceAfter.crashed -or
        -not $Evidence.serviceState.leaseAfterTaskRemoval) {
        throw 'Ticket 06 task-removal case did not keep the actual monitor service bound with its lease attached.'
    }

    $preferences = $Evidence.preferences
    if (-not $preferences.registrationPersisted -or -not $preferences.targetsPersisted -or -not $preferences.intervalPersisted -or
        $preferences.registrationBeforeTaskRemoval -cne $preferences.registrationAfterTaskRemoval -or
        $preferences.targetsBeforeTaskRemoval -cne $preferences.targetsAfterTaskRemoval -or
        $preferences.intervalBeforeTaskRemoval -cne $preferences.intervalAfterTaskRemoval -or
        $preferences.credentialFileRead) {
        throw 'Ticket 06 task-removal case did not preserve the exact filtered app-owned preference rows.'
    }

    $baselineEndSequence = [long]$observer.baseline.fixtureRequestEndSequence
    $postBaselineRequests = @($Evidence.fixture.requests |
        Where-Object { [long]$_.sequence -gt $baselineEndSequence } |
        Sort-Object { [long]$_.sequence })
    $postRemovalCycle = @($postBaselineRequests | Select-Object -First 7)
    $expectedPostRemovalSequences = @((1..7) | ForEach-Object { $baselineEndSequence + [long]$_ })
    $actualPostRemovalSequences = @($postRemovalCycle | ForEach-Object { [long]$_.sequence })
    $postRemovalPathsFromFixture = @($postRemovalCycle | ForEach-Object { [string]$_.path })
    $postRemovalPaths = @($observer.postRemovalRequestPaths | ForEach-Object { [string]$_ })
    $expectedPaths = @(
        '/v3/account/signin', '/v3/todohangeul/user', '/v3/math/user', '/v3/english/user',
        '/v3/todoschool/report/daily/hangeul', '/v3/todoschool/report/daily/math', '/v3/todoschool/report/daily/english'
    )
    if ($Evidence.fixture.runId -cne $RunId -or $Evidence.fixture.scenario -cne 'ticket06' -or
        $baselineEndSequence -lt 1 -or
        $postRemovalCycle.Count -ne $expectedPaths.Count -or
        [string]::Join(',', $actualPostRemovalSequences) -cne [string]::Join(',', $expectedPostRemovalSequences) -or
        [string]::Join("`n", $postRemovalPathsFromFixture) -cne [string]::Join("`n", $expectedPaths) -or
        [string]::Join("`n", $postRemovalPaths) -cne [string]::Join("`n", $expectedPaths) -or
        [string]::Join("`n", $postRemovalPathsFromFixture) -cne [string]::Join("`n", $postRemovalPaths)) {
        throw 'Ticket 06 task-removal fixture does not prove a full post-removal local API cycle.'
    }
    if ($Evidence.executionScope.rebootPerformed -or $Evidence.executionScope.forceStopUsed -or $Evidence.executionScope.actualTodoApiCalled) {
        throw 'Ticket 06 task-removal case used a reboot, force-stop, or real Todo API.'
    }

    return [pscustomobject]@{
        pass = $true
        runId = $RunId
        scenario = 'ticket06'
        case = 'task-removal'
        taskId = [int]$task.taskId
        taskRemovalMechanism = $taskRemovalMechanism
        rebootCount = 0
        actualTodoApiCalled = $false
        notificationOrAccessibilityPermissionChanged = $false
        postRemovalRequestCount = $expectedPaths.Count
    }
}

Export-ModuleMember -Function Join-Ticket06ResultChunks, Get-Ticket06PreferenceSelectors, ConvertFrom-Ticket06PreferenceRows, New-Ticket06VerifiedReverseMapping, Assert-Ticket06BootRecoveryEvidence, Assert-Ticket06TaskRemovalEvidence
