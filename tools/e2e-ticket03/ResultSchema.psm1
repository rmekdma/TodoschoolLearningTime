function Assert-Ticket03JsonArrayFields {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string[]]$Fields,
        [Parameter(Mandatory = $true)][string]$Context
    )

    foreach ($field in $Fields) {
        $property = $Value.PSObject.Properties[$field]
        if ($null -eq $property) {
            throw "Ticket 03 $Context is missing collection field '$field'."
        }
        if ($property.Value -isnot [System.Array]) {
            throw "Ticket 03 $Context field '$field' must be an actual JSON array."
        }
    }
}

function Assert-Ticket03ScalarFields {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Fields,
        [Parameter(Mandatory = $true)][string]$Context
    )

    foreach ($field in $Fields.Keys) {
        $property = $Value.PSObject.Properties[[string]$field]
        if ($null -eq $property) {
            throw "Ticket 03 $Context is missing mandatory scalar field '$field'."
        }

        $actual = $property.Value
        $valid = switch ([string]$Fields[$field]) {
            "string" { $actual -is [string] }
            "nullableString" { $null -eq $actual -or $actual -is [string] }
            "boolean" { $actual -is [bool] }
            "integer" {
                $actual -is [byte] -or $actual -is [sbyte] -or
                $actual -is [int16] -or $actual -is [uint16] -or
                $actual -is [int32] -or $actual -is [uint32] -or
                $actual -is [int64] -or $actual -is [uint64]
            }
            "nullableInteger" {
                $null -eq $actual -or $actual -is [byte] -or $actual -is [sbyte] -or
                $actual -is [int16] -or $actual -is [uint16] -or
                $actual -is [int32] -or $actual -is [uint32] -or
                $actual -is [int64] -or $actual -is [uint64]
            }
            "timestamp" {
                if ($actual -is [DateTime] -or $actual -is [DateTimeOffset]) {
                    $true
                } elseif ($actual -is [string]) {
                    $parsed = [DateTimeOffset]::MinValue
                    [DateTimeOffset]::TryParse($actual, [ref]$parsed)
                } else {
                    $false
                }
            }
            "object" {
                $null -ne $actual -and (
                    $actual -is [System.Collections.IDictionary] -or
                    $actual -is [System.Management.Automation.PSCustomObject]
                )
            }
            default { throw "Ticket 03 $Context schema has unsupported type '$($Fields[$field])' for '$field'." }
        }

        if (-not $valid) {
            throw "Ticket 03 $Context field '$field' must have JSON type '$($Fields[$field])'."
        }
    }
}

function Assert-Ticket03EventArrayScalars {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Field,
        [Parameter(Mandatory = $true)][string]$Context
    )

    for ($index = 0; $index -lt $Value.$Field.Count; $index++) {
        Assert-Ticket03ScalarFields $Value.$Field[$index] @{
            sequence = "integer"
            packageName = "nullableString"
            elapsedRealtimeMs = "integer"
        } "$Context.$Field[$index]"
    }
}

function Assert-Ticket03DispatchArrayScalars {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Field,
        [Parameter(Mandatory = $true)][string]$Context
    )

    for ($index = 0; $index -lt $Value.$Field.Count; $index++) {
        $dispatch = $Value.$Field[$index]
        Assert-Ticket03ScalarFields $dispatch @{
            sequence = "integer"
            path = "string"
            elapsedRealtimeMs = "integer"
            foregroundEventSequence = "integer"
            foregroundPackage = "nullableString"
            foregroundEventElapsedRealtimeMs = "integer"
            serviceLeaseAttached = "boolean"
            serviceLeaseObservedAtElapsedRealtimeMs = "integer"
        } "$Context.$Field[$index]"
        if ([int64]$dispatch.foregroundEventElapsedRealtimeMs -gt [int64]$dispatch.elapsedRealtimeMs) {
            throw "Ticket 03 $Context.$Field[$index] foreground callback timestamp follows its HTTP dispatch timestamp."
        }
        if ([int64]$dispatch.serviceLeaseObservedAtElapsedRealtimeMs -gt [int64]$dispatch.elapsedRealtimeMs) {
            throw "Ticket 03 $Context.$Field[$index] service-lease snapshot follows its HTTP dispatch timestamp."
        }
    }
}

function Assert-Ticket03DispatchCycle {
    param(
        [Parameter(Mandatory = $true)]$Poll,
        [Parameter(Mandatory = $true)][string[]]$ExpectedPaths,
        [Parameter(Mandatory = $true)][string]$ExpectedReportPath,
        [Parameter(Mandatory = $true)][string]$ExpectedPackage,
        [Parameter(Mandatory = $true)][long]$TargetForegroundCallbackAtElapsedRealtimeMs,
        [Parameter(Mandatory = $true)][string]$Context
    )

    $events = @($Poll.httpRequestDispatchEventsDuringCycle)
    $actualPaths = @($events | ForEach-Object { [string]$_.path })
    if ($events.Count -ne $ExpectedPaths.Count -or ($actualPaths -join "|") -ne ($ExpectedPaths -join "|")) {
        throw "Ticket 03 $Context actual HTTP dispatch paths did not match the complete expected cycle."
    }

    $previousSequence = [int64]$Poll.requestDispatchCursorBeforeTransition
    $previousElapsedRealtimeMs = -1L
    foreach ($event in $events) {
        if ([int64]$event.sequence -ne ($previousSequence + 1) -or
            [int64]$event.elapsedRealtimeMs -lt $previousElapsedRealtimeMs -or
            [int64]$event.foregroundEventSequence -lt [int64]$Poll.foregroundEventSequence -or
            $event.foregroundPackage -ne $ExpectedPackage -or
            [int64]$event.foregroundEventElapsedRealtimeMs -lt $TargetForegroundCallbackAtElapsedRealtimeMs -or
            [int64]$event.foregroundEventElapsedRealtimeMs -gt [int64]$event.elapsedRealtimeMs -or
            -not $event.serviceLeaseAttached) {
            throw "Ticket 03 $Context HTTP dispatch $($event.sequence) did not capture the current target foreground callback and connected service lease."
        }
        $previousSequence = [int64]$event.sequence
        $previousElapsedRealtimeMs = [int64]$event.elapsedRealtimeMs
    }

    $reportDispatches = @($events | Where-Object { $_.path -eq $ExpectedReportPath })
    if ($reportDispatches.Count -ne 1) {
        throw "Ticket 03 $Context must contain exactly one actual report HTTP dispatch."
    }
    $reportDispatch = $reportDispatches[0]
    if ([int64]$Poll.requestDispatchSequenceAtReport -ne [int64]$reportDispatch.sequence -or
        [int64]$Poll.requestDispatchElapsedRealtimeMsAtReport -ne [int64]$reportDispatch.elapsedRealtimeMs -or
        [int64]$Poll.foregroundEventSequenceAtReportDispatch -ne [int64]$reportDispatch.foregroundEventSequence -or
        [int64]$Poll.foregroundEventElapsedRealtimeMsAtReportDispatch -ne [int64]$reportDispatch.foregroundEventElapsedRealtimeMs -or
        $Poll.foregroundPackageAtReportDispatch -ne $reportDispatch.foregroundPackage -or
        [bool]$Poll.serviceLeaseAttachedAtReportDispatch -ne [bool]$reportDispatch.serviceLeaseAttached) {
        throw "Ticket 03 $Context report-dispatch summary does not match its captured diagnostics event."
    }
}

function Assert-Ticket03RequestArrayScalars {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Field,
        [Parameter(Mandatory = $true)][string]$Context
    )

    for ($index = 0; $index -lt $Value.$Field.Count; $index++) {
        Assert-Ticket03ScalarFields $Value.$Field[$index] @{
            sequence = "integer"
            path = "string"
            receivedAt = "timestamp"
            responseStatus = "integer"
        } "$Context.$Field[$index]"
    }
}

function Assert-Ticket03ServerRequestScalars {
    param([Parameter(Mandatory = $true)]$Request, [Parameter(Mandatory = $true)][string]$Context)

    Assert-Ticket03ScalarFields $Request @{
        sequence = "integer"
        receivedAt = "timestamp"
        method = "string"
        path = "string"
        bodyText = "string"
        body = "object"
        headers = "object"
        responseStatus = "integer"
        servedReportCount = "nullableInteger"
    } $Context
}

function Assert-Ticket03PollScalars {
    param([Parameter(Mandatory = $true)]$Poll, [Parameter(Mandatory = $true)][string]$Context)

    Assert-Ticket03ScalarFields $Poll @{
        subject = "string"
        packageName = "string"
        foregroundEventSequence = "integer"
        foregroundEventCursorBefore = "integer"
        appLaunchRequestedAtUtc = "timestamp"
        appLaunchRequestedAtElapsedRealtimeMs = "integer"
        foregroundEventObservedAtUtc = "timestamp"
        foregroundEventObservedAtElapsedRealtimeMs = "integer"
        foregroundEventSequenceAtWindowStart = "integer"
        requestSequenceBeforeTransition = "integer"
        requestSequenceAtForegroundEventObservation = "integer"
        inFlightRequestsAtForegroundEventObservation = "integer"
        foregroundCallbackAtElapsedRealtimeMs = "integer"
        eventObservationFixtureSnapshotStartedAtUtc = "timestamp"
        eventObservationFixtureSnapshotFinishedAtUtc = "timestamp"
        eventObservationFixtureSnapshotStartedAtElapsedRealtimeMs = "integer"
        eventObservationFixtureSnapshotFinishedAtElapsedRealtimeMs = "integer"
        requestSequenceCursorAtWindowStart = "integer"
        requestSequenceCursorAtReport = "integer"
        inFlightRequestsAtWindowStart = "integer"
        expectedReportObservedBeforeStableWindow = "boolean"
        requestTimingInterpretation = "string"
        observationWindowStartedAtElapsedRealtimeMs = "integer"
        foregroundSnapshotCapturedAtElapsedRealtimeMs = "integer"
        requestSnapshotCapturedAtElapsedRealtimeMs = "integer"
        foregroundActiveWindowPackageAtWindowStart = "string"
        serviceLeaseAttachedAtWindowStart = "boolean"
        foregroundEventSequenceAtReport = "integer"
        foregroundPackageAtReport = "string"
        foregroundEventSequenceAtApply = "integer"
        automationActiveWindowPackageAtReport = "nullableString"
        foregroundObservedAtUtc = "timestamp"
        foregroundObservedAtElapsedRealtimeMs = "integer"
        requestSequence = "integer"
        requestPath = "string"
        requestDispatchCursorBeforeTransition = "integer"
        requestDispatchSequenceAtReport = "integer"
        requestDispatchElapsedRealtimeMsAtReport = "integer"
        foregroundEventSequenceAtReportDispatch = "integer"
        foregroundEventElapsedRealtimeMsAtReportDispatch = "integer"
        foregroundPackageAtReportDispatch = "string"
        serviceLeaseAttachedAtReportDispatch = "boolean"
        reportRequestCountInCycle = "integer"
        servedCount = "integer"
        status = "string"
        requestReceivedAt = "timestamp"
        resultAppliedAtElapsedRealtimeMs = "integer"
        foregroundActiveElapsedTimeMs = "integer"
        serviceLeaseAttached = "boolean"
        inFlightRequestsAtEnd = "integer"
    } $Context

    $hasCycleObservationTimes =
        $null -ne $Poll.PSObject.Properties["cycleObservationStartedAtUtc"] -and
        $null -ne $Poll.PSObject.Properties["cycleObservationStartedAtElapsedRealtimeMs"] -and
        $null -ne $Poll.PSObject.Properties["cycleObservationElapsedThroughApplyMs"]
    $hasLegacyObservationTimes =
        $null -ne $Poll.PSObject.Properties["requestStartedAtUtc"] -and
        $null -ne $Poll.PSObject.Properties["requestStartedAtElapsedRealtimeMs"] -and
        $null -ne $Poll.PSObject.Properties["waitedForRealElapsedTimeMs"]
    if ($hasCycleObservationTimes -eq $hasLegacyObservationTimes) {
        throw "Ticket 03 $Context must contain exactly one recognized cycle-observation timing field set."
    }
    if ($hasCycleObservationTimes) {
        Assert-Ticket03ScalarFields $Poll @{
            cycleObservationStartedAtUtc = "timestamp"
            cycleObservationStartedAtElapsedRealtimeMs = "integer"
            cycleObservationElapsedThroughApplyMs = "integer"
        } $Context
    } else {
        # Older evidence called the cycle observation start a request start. It is not HTTP dispatch timing.
        Assert-Ticket03ScalarFields $Poll @{
            requestStartedAtUtc = "timestamp"
            requestStartedAtElapsedRealtimeMs = "integer"
            waitedForRealElapsedTimeMs = "integer"
        } "$Context legacy cycle-observation timing"
    }

    foreach ($field in @("foregroundEventsDuringTransition", "foregroundEventsObservedAtReport", "foregroundEventsObservedAtApply")) {
        Assert-Ticket03EventArrayScalars $Poll $field $Context
    }
    Assert-Ticket03DispatchArrayScalars $Poll "httpRequestDispatchEventsDuringCycle" $Context
    Assert-Ticket03RequestArrayScalars $Poll "requestsVisibleAtForegroundEventObservation" $Context
}

function Assert-Ticket03NoPollWindowScalars {
    param([Parameter(Mandatory = $true)]$Window, [Parameter(Mandatory = $true)][string]$Context)

    Assert-Ticket03ScalarFields $Window @{
        transition = "string"
        foregroundPackage = "string"
        activeWindowPackageAtWindowStart = "string"
        foregroundCursorBeforeTransition = "integer"
        foregroundEventSequence = "integer"
        foregroundCallbackAtElapsedRealtimeMs = "integer"
        requestDispatchCursorAtWindowStart = "integer"
        requestDispatchCursorAtWindowEnd = "integer"
        foregroundEventCursorAtWindowStart = "integer"
        requestSequenceBeforeTransition = "integer"
        requestSequenceCursorAtWindowStart = "integer"
        inFlightRequestsAtWindowStart = "integer"
        serviceLeaseAttachedAtWindowStart = "boolean"
        observationWindowStartedAtElapsedRealtimeMs = "integer"
        foregroundSnapshotCapturedAtElapsedRealtimeMs = "integer"
        requestSnapshotCapturedAtElapsedRealtimeMs = "integer"
        foregroundEventSequenceAtWindowStart = "integer"
        expectedForegroundEventSequence = "integer"
        foregroundEventSequenceAtWindowEnd = "integer"
        startedAtUtc = "timestamp"
        durationMs = "integer"
        sampleCount = "integer"
        serverSequenceBefore = "integer"
        serverSequenceAfter = "integer"
        inFlightRequestsBefore = "integer"
        inFlightRequestsAfter = "integer"
        serviceLeaseAttachedAtEnd = "boolean"
    } $Context
    foreach ($field in @("foregroundEventsDuringTransition", "foregroundEventsObservedDuringWindow")) {
        Assert-Ticket03EventArrayScalars $Window $field $Context
    }
}

function Assert-Ticket03ResultScalarShapes {
    param([Parameter(Mandatory = $true)]$Result)

    Assert-Ticket03ScalarFields $Result @{
        runId = "string"
        apiBaseUrl = "string"
        deviceApi = "integer"
        deviceModel = "string"
        seoulDate = "integer"
        configuredIntervalMinutes = "integer"
        intervalMeasuredUsingRealElapsedTime = "boolean"
        registeredChild = "string"
        registeredChildOnly = "boolean"
        subjectPackages = "object"
        observationStartSequence = "integer"
        noRegistrationNewReportCount = "integer"
        serviceLeaseAttached = "boolean"
        httpRequestDispatchCursor = "integer"
        silentChannelPrepared = "boolean"
        notificationChannelImportance = "integer"
        notificationChannelHasSoundUri = "boolean"
        notificationChannelVibrationEnabled = "boolean"
        productAlertDeliveryTested = "boolean"
        actualTodoApiCalled = "boolean"
        stayAwake = "object"
        observerPackage = "string"
    } "result"
    Assert-Ticket03ScalarFields $Result.subjectPackages @{
        hangeul = "string"
        math = "string"
        english = "string"
    } "result.subjectPackages"
    Assert-Ticket03ScalarFields $Result.stayAwake @{
        runId = "string"
        mechanism = "string"
        permissionScope = "string"
        acquired = "boolean"
        acquiredAtElapsedRealtimeMs = "integer"
        timeoutMs = "integer"
        displayInteractiveBefore = "boolean"
        displayInteractiveAfter = "boolean"
        foregroundPackageBefore = "string"
        foregroundPackageAfter = "string"
        foregroundEventCursorBefore = "integer"
        foregroundEventCursorAfter = "integer"
        serviceLeaseAttachedBefore = "boolean"
        serviceLeaseAttachedAfter = "boolean"
    } "result.stayAwake"

    foreach ($field in @("baselineRequests")) {
        for ($index = 0; $index -lt $Result.$field.Count; $index++) {
            Assert-Ticket03ServerRequestScalars $Result.$field[$index] "result.$field[$index]"
        }
    }
    for ($index = 0; $index -lt $Result.foregroundEvents.Count; $index++) {
        Assert-Ticket03ScalarFields $Result.foregroundEvents[$index] @{
            sequence = "integer"
            packageName = "nullableString"
            elapsedRealtimeMs = "integer"
        } "result.foregroundEvents[$index]"
    }
    Assert-Ticket03DispatchArrayScalars $Result "httpRequestDispatchEvents" "result"
    for ($index = 0; $index -lt $Result.pollRequests.Count; $index++) {
        Assert-Ticket03PollScalars $Result.pollRequests[$index] "result.pollRequests[$index]"
    }
    for ($index = 0; $index -lt $Result.observationWindows.Count; $index++) {
        $window = $Result.observationWindows[$index]
        if ($null -ne $window.PSObject.Properties["requestPath"]) {
            Assert-Ticket03PollScalars $window "result.observationWindows[$index] poll record"
        } else {
            Assert-Ticket03NoPollWindowScalars $window "result.observationWindows[$index] no-poll window"
        }
    }
}

function Assert-Ticket03IntervalRange {
    param(
        [Parameter(Mandatory = $true)]$Earlier,
        [Parameter(Mandatory = $true)]$Later,
        [Parameter(Mandatory = $true)][string]$Context,
        [long]$MinimumMs = 58000,
        [long]$MaximumMs = 180000
    )

    $earlierAt = [DateTimeOffset]::Parse([string]$Earlier)
    $laterAt = [DateTimeOffset]::Parse([string]$Later)
    $elapsedMs = ($laterAt - $earlierAt).TotalMilliseconds
    if ($elapsedMs -lt $MinimumMs -or $elapsedMs -gt $MaximumMs) {
        throw "Ticket 03 $Context interval was outside the configured cadence range: $elapsedMs ms."
    }
}

function Assert-Ticket03ResultArrayShapes {
    param([Parameter(Mandatory = $true)]$Result)

    Assert-Ticket03JsonArrayFields $Result @("baselineRequests", "pollRequests", "observationWindows", "foregroundEvents", "httpRequestDispatchEvents") "result"
    $pollFields = @(
        "foregroundPackagesObservedDuringTransition", "foregroundEventsDuringTransition",
        "requestsVisibleAtForegroundEventObservation", "requestsDuringTransitionSequences", "httpRequestDispatchEventsDuringCycle",
        "requestsDuringTransition", "foregroundEventsObservedAtReport",
        "foregroundPackagesObservedAtReport", "foregroundEventsObservedAtApply",
        "foregroundPackagesObservedAtApply", "requestCycleSequences", "requestCyclePaths"
    )
    for ($index = 0; $index -lt $Result.pollRequests.Count; $index++) {
        Assert-Ticket03JsonArrayFields $Result.pollRequests[$index] $pollFields "pollRequests[$index]"
    }

    $windowFields = @(
        "foregroundPackagesObservedDuringTransition", "foregroundEventsDuringTransition",
        "requestsDuringTransition", "requestsDuringTransitionSequences",
        "foregroundEventsObservedDuringWindow", "foregroundPackagesObservedDuringWindow"
    )
    for ($index = 0; $index -lt $Result.observationWindows.Count; $index++) {
        $entry = $Result.observationWindows[$index]
        if ($null -ne $entry.PSObject.Properties["requestPath"]) {
            Assert-Ticket03JsonArrayFields $entry $pollFields "observationWindows[$index] poll record"
        } else {
            Assert-Ticket03JsonArrayFields $entry $windowFields "observationWindows[$index] no-poll window"
        }
    }

    if ($null -ne $Result.stayAwake) {
        Assert-Ticket03JsonArrayFields $Result.stayAwake @("foregroundEventsDuringAcquire") "stayAwake"
    }
}

Export-ModuleMember -Function Assert-Ticket03JsonArrayFields, Assert-Ticket03ScalarFields, Assert-Ticket03ServerRequestScalars, Assert-Ticket03ResultArrayShapes, Assert-Ticket03ResultScalarShapes, Assert-Ticket03IntervalRange, Assert-Ticket03DispatchCycle
