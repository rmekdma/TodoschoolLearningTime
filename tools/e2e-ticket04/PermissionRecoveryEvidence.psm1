function Test-Ticket04CaseBoundaryEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Case
    )

    $requiredProperties = @(
        "caseId",
        "actualPermissionEnabledAtBoundary",
        "requestAgeAtBoundaryMs",
        "requestBoundaryClassification",
        "requestStillInFlight",
        "clientSocketOpenAtPermissionBoundary",
        "sameProductPidAtBoundary",
        "processRestartExpected",
        "processRestartedAfterBoundary",
        "productPidBefore",
        "productPidAtBoundary",
        "productPidAfterReturn"
    )
    if (@($Case.PSObject.Properties.Name | Where-Object { $_ -in $requiredProperties }).Count -ne $requiredProperties.Count) {
        return $false
    }

    foreach ($booleanProperty in @(
        "actualPermissionEnabledAtBoundary",
        "requestStillInFlight",
        "clientSocketOpenAtPermissionBoundary",
        "sameProductPidAtBoundary",
        "processRestartExpected",
        "processRestartedAfterBoundary"
    )) {
        if ($Case.PSObject.Properties[$booleanProperty].Value -isnot [bool]) { return $false }
    }
    if ($Case.actualPermissionEnabledAtBoundary -ne $false) { return $false }

    $ageValue = $Case.PSObject.Properties["requestAgeAtBoundaryMs"].Value
    if ($ageValue -isnot [ValueType] -or $ageValue -is [bool]) { return $false }
    try {
        $requestAge = [int64]$ageValue
        if ([double]$ageValue -ne [double]$requestAge) { return $false }
    }
    catch {
        return $false
    }
    if ($requestAge -lt 0) { return $false }

    $expectedBoundaryClassification = if (-not $Case.requestStillInFlight) {
        "no-client-request-at-permission-boundary"
    } elseif (-not $Case.clientSocketOpenAtPermissionBoundary) {
        "client-socket-closed-at-permission-boundary"
    } elseif ($requestAge -lt 13500) {
        "client-request-in-flight-with-open-socket-within-evidence-window"
    } else {
        "client-request-in-flight-with-open-socket-outside-evidence-window"
    }
    if ($Case.requestBoundaryClassification -cne $expectedBoundaryClassification) { return $false }

    $pidBefore = [string]$Case.productPidBefore
    $pidAtBoundary = [string]$Case.productPidAtBoundary
    $pidAfterReturn = [string]$Case.productPidAfterReturn
    if ([string]::IsNullOrWhiteSpace($pidBefore) -or
        $Case.sameProductPidAtBoundary -ne (-not [string]::IsNullOrWhiteSpace($pidAtBoundary) -and $pidAtBoundary -eq $pidBefore)) {
        return $false
    }

    if ($Case.caseId -eq "notification-revocation") {
        return $Case.processRestartExpected -and
            $Case.processRestartedAfterBoundary -and
            -not [string]::IsNullOrWhiteSpace($pidAfterReturn) -and
            $pidAfterReturn -ne $pidBefore
    }

    if ($Case.caseId -eq "accessibility-revocation") {
        return -not $Case.processRestartExpected -and
            $Case.requestStillInFlight -and
            $Case.clientSocketOpenAtPermissionBoundary -and
            $Case.sameProductPidAtBoundary -and
            -not $Case.processRestartedAfterBoundary -and
            $pidAtBoundary -eq $pidBefore -and
            $pidAfterReturn -eq $pidBefore -and
            $Case.requestBoundaryClassification -eq "client-request-in-flight-with-open-socket-within-evidence-window" -and
            $requestAge -ge 0 -and $requestAge -lt 13500
    }

    return $false
}

function Test-Ticket04CaseResult {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Case
    )

    if (-not (Test-Ticket04CaseBoundaryEvidence -Case $Case)) { return $false }
    $requiredProperties = @(
        "runId",
        "heldResponseStatus",
        "permissionPauseUiObserved",
        "authenticationPauseAfterRelease",
        "staleCompletionRequests",
        "registeredChildrenPreserved",
        "serviceLeaseAtDispatch",
        "serviceLeaseAtPause",
        "subjectStateComparison",
        "serverResponseWrite",
        "userAcknowledgementElapsedRealtimeMs",
        "permissionBoundaryElapsedRealtimeMs",
        "responseReleaseElapsedRealtimeMs",
        "restoredServiceLease",
        "recoverySilentBaseline",
        "laterCompletionCount"
    )
    if (@($Case.PSObject.Properties.Name | Where-Object { $_ -in $requiredProperties }).Count -ne $requiredProperties.Count) {
        return $false
    }
    if ([string]$Case.runId -notmatch '^[0-9a-f]{32}$') { return $false }

    $isNotificationCase = $Case.caseId -eq "notification-revocation"
    $expectedStatus = if ($isNotificationCase) { 200 } else { 401 }
    $expectedComparison = if ($isNotificationCase) {
        "unchanged-after-release-in-restarted-process"
    } else {
        "unchanged-after-release-in-same-process"
    }
    if ([int]$Case.heldResponseStatus -ne $expectedStatus -or
        $Case.permissionPauseUiObserved -isnot [bool] -or -not $Case.permissionPauseUiObserved -or
        $Case.authenticationPauseAfterRelease -isnot [bool] -or $Case.authenticationPauseAfterRelease -or
        [int]$Case.staleCompletionRequests -ne 0 -or
        $Case.registeredChildrenPreserved -isnot [bool] -or -not $Case.registeredChildrenPreserved -or
        $Case.serviceLeaseAtDispatch -isnot [bool] -or -not $Case.serviceLeaseAtDispatch -or
        $Case.serviceLeaseAtPause -isnot [bool] -or $Case.serviceLeaseAtPause -ne $isNotificationCase -or
        [string]$Case.subjectStateComparison -cne $expectedComparison -or
        [string]$Case.serverResponseWrite -notin @("written", "write-failed") -or
        $Case.restoredServiceLease -isnot [bool] -or -not $Case.restoredServiceLease -or
        $Case.recoverySilentBaseline -isnot [bool] -or -not $Case.recoverySilentBaseline -or
        [int]$Case.laterCompletionCount -ne 1) {
        return $false
    }
    if ([string]$Case.connectionCloseClassification -notin @(
        "client-still-connected-at-explicit-release; response-delivery-outcome-will-be-observed",
        "client-connection-closed-after-permission-boundary-within-15s-window; cancellation-consistent, cause-unconfirmed",
        "late-client-connection-close; timeout-or-other-close-ambiguous",
        "client-connection-closed-before-permission-boundary-or-boundary-order-ambiguous",
        "client-still-connected-near-or-after-read-timeout; outcome-ambiguous"
    )) { return $false }

    foreach ($property in @("userAcknowledgementElapsedRealtimeMs", "permissionBoundaryElapsedRealtimeMs", "responseReleaseElapsedRealtimeMs")) {
        $value = $Case.PSObject.Properties[$property].Value
        if ($value -isnot [ValueType] -or $value -is [bool]) { return $false }
        try {
            $integer = [int64]$value
            if ([double]$value -ne [double]$integer -or $integer -lt 0) { return $false }
        } catch { return $false }
    }
    if ([int64]$Case.userAcknowledgementElapsedRealtimeMs -le [int64]$Case.permissionBoundaryElapsedRealtimeMs -or
        [int64]$Case.responseReleaseElapsedRealtimeMs -le [int64]$Case.userAcknowledgementElapsedRealtimeMs) {
        return $false
    }
    return $true
}

function Test-Ticket04CaseEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Evidence,
        [string]$ExpectedDeviceSerial,
        [string]$ExpectedProductApkSha256
    )

    $requiredProperties = @(
        "schemaVersion", "contractVersion", "caseId", "runId", "sourceRevision",
        "sourceContentSha256", "workingTreeDirty", "device", "applicationId", "productApkSha256",
        "observerApkSha256", "observerTestApkSha256", "fixture", "caseResult", "validation"
    )
    if (@($Evidence.PSObject.Properties.Name | Where-Object { $_ -in $requiredProperties }).Count -ne $requiredProperties.Count) { return $false }
    if ($Evidence.schemaVersion -ne 1 -or $Evidence.contractVersion -ne 1 -or
        [string]$Evidence.caseId -notin @("notification-revocation", "accessibility-revocation") -or
        [string]$Evidence.runId -notmatch '^[0-9a-f]{32}$' -or
        [string]$Evidence.sourceRevision -notmatch '^[0-9a-f]{7,40}$' -or
        [string]$Evidence.sourceContentSha256 -notmatch '^[0-9a-f]{64}$' -or
        [string]$Evidence.applicationId -cne "com.example.todoschooltime.debug") { return $false }
    foreach ($property in @("productApkSha256", "observerApkSha256", "observerTestApkSha256")) {
        if ([string]$Evidence.$property -notmatch '^[0-9a-f]{64}$') { return $false }
    }
    if ($ExpectedProductApkSha256 -and $Evidence.productApkSha256 -cne $ExpectedProductApkSha256) { return $false }
    if ($Evidence.workingTreeDirty -isnot [bool] -or
        $null -eq $Evidence.device -or $null -eq $Evidence.fixture -or $null -eq $Evidence.validation -or
        [string]::IsNullOrWhiteSpace([string]$Evidence.device.model)) { return $false }
    if ([string]$Evidence.device.serial -notmatch '^[A-Za-z0-9._:-]+$' -or
        ($ExpectedDeviceSerial -and $Evidence.device.serial -cne $ExpectedDeviceSerial) -or
        [int]$Evidence.device.apiLevel -lt 1 -or
        [string]$Evidence.fixture.scenario -cne "ticket04" -or
        [string]$Evidence.fixture.runId -cne [string]$Evidence.runId -or
        [string]$Evidence.validation.status -cne "passed" -or
        [string]$Evidence.caseResult.caseId -cne [string]$Evidence.caseId -or
        [string]$Evidence.caseResult.runId -cne [string]$Evidence.runId -or
        -not (Test-Ticket04CaseResult -Case $Evidence.caseResult)) { return $false }

    $caseHolds = @($Evidence.fixture.releasedReportHolds | Where-Object { $_.caseId -ceq $Evidence.caseId })
    if ($caseHolds.Count -ne 1 -or
        [int]$caseHolds[0].responseStatus -ne [int]$Evidence.caseResult.heldResponseStatus -or
        [string]$caseHolds[0].deliveryStatus -cne [string]$Evidence.caseResult.serverResponseWrite -or
        [int64]$caseHolds[0].sequence -ne [int64]$Evidence.caseResult.serverRequestSequence -or
        [int]$Evidence.fixture.inFlightRequestCount -ne 0 -or
        [int]$Evidence.fixture.serverHeldResponseCount -ne 0) { return $false }
    return $true
}

function Join-Ticket04CaseEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Evidence,
        [string]$ExpectedDeviceSerial,
        [string]$ExpectedProductApkSha256
    )

    $items = @($Evidence)
    if ($items.Count -ne 2) { throw "Ticket 04 aggregate evidence must contain exactly two completed case artifacts." }
    $byCase = @{}
    foreach ($item in $items) {
        if (-not (Test-Ticket04CaseEvidence -Evidence $item -ExpectedDeviceSerial $ExpectedDeviceSerial -ExpectedProductApkSha256 $ExpectedProductApkSha256)) {
            throw "Ticket 04 case artifact failed schema, identity, fixture, or result validation: caseId='$($item.caseId)' runId='$($item.runId)'"
        }
        if ($byCase.ContainsKey([string]$item.caseId)) { throw "Ticket 04 aggregate contains a duplicate case artifact: $($item.caseId)" }
        $byCase[[string]$item.caseId] = $item
    }
    foreach ($caseId in @("notification-revocation", "accessibility-revocation")) {
        if (-not $byCase.ContainsKey($caseId)) { throw "Ticket 04 aggregate omits its required case artifact: $caseId" }
    }

    $first = $items[0]
    foreach ($item in $items | Select-Object -Skip 1) {
        if ($item.applicationId -cne $first.applicationId -or
            $item.productApkSha256 -cne $first.productApkSha256 -or
            $item.device.serial -cne $first.device.serial -or
            [int]$item.device.apiLevel -ne [int]$first.device.apiLevel -or
            $item.contractVersion -ne $first.contractVersion -or
            $item.sourceRevision -cne $first.sourceRevision -or
            $item.sourceContentSha256 -cne $first.sourceContentSha256 -or
            $item.workingTreeDirty -ne $first.workingTreeDirty) {
            throw "Ticket 04 cases cannot be combined across different product binaries, devices, API levels, contracts, or test-source snapshots."
        }
    }
    return @($byCase["notification-revocation"], $byCase["accessibility-revocation"])
}

Export-ModuleMember -Function Test-Ticket04CaseBoundaryEvidence, Test-Ticket04CaseResult, Test-Ticket04CaseEvidence, Join-Ticket04CaseEvidence
