$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "ResultChunks.psm1") -Force
Import-Module (Join-Path $PSScriptRoot "PermissionRecoveryEvidence.psm1") -Force

$runId = "0123456789abcdef0123456789abcdef"
$json = [ordered]@{
    runId = $runId
    apiBaseUrl = "http://127.0.0.1:18080"
    notificationProcessRestartObserved = $true
    accessibilitySamePidBoundaryObserved = $true
    cases = @(
        [ordered]@{
            caseId = "notification-revocation"
            actualPermissionEnabledAtBoundary = $false
            requestAgeAtBoundaryMs = 15200
            requestBoundaryClassification = "no-client-request-at-permission-boundary"
            requestStillInFlight = $false
            clientSocketOpenAtPermissionBoundary = $false
            sameProductPidAtBoundary = $false
            processRestartExpected = $true
            processRestartedAfterBoundary = $true
            productPidBefore = "5054"
            productPidAtBoundary = ""
            productPidAfterReturn = "14438"
        }
        [ordered]@{
            caseId = "accessibility-revocation"
            actualPermissionEnabledAtBoundary = $false
            processRestartExpected = $false
            processRestartedAfterBoundary = $false
            requestAgeAtBoundaryMs = 12000
            requestBoundaryClassification = "client-request-in-flight-with-open-socket-within-evidence-window"
            requestStillInFlight = $true
            clientSocketOpenAtPermissionBoundary = $true
            sameProductPidAtBoundary = $true
            productPidBefore = "14438"
            productPidAtBoundary = "14438"
            productPidAfterReturn = "14438"
        }
    )
} | ConvertTo-Json -Compress -Depth 5
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
$chunks = [System.Collections.Generic.List[string]]::new()
for ($offset = 0; $offset -lt $encoded.Length; $offset += 17) {
    $length = [Math]::Min(17, $encoded.Length - $offset)
    [void]$chunks.Add($encoded.Substring($offset, $length))
}
$lines = @($chunks | ForEach-Object -Begin { $index = 0 } -Process {
    $index++
    "I/Ticket04Result: TICKET04_RESULT_CHUNK:$runId`:$index/$($chunks.Count):$_"
})
$reconstructed = Join-Ticket04ResultChunks -LogLines $lines -RunId $runId
if ($reconstructed -cne $json) { throw "Ticket 04 chunk reconstruction changed the JSON payload." }
$reconstructedResult = $reconstructed | ConvertFrom-Json
foreach ($case in $reconstructedResult.cases) {
    if (-not (Test-Ticket04CaseBoundaryEvidence -Case $case)) {
        throw "Ticket 04 rejected parsed boundary evidence for $($case.caseId)."
    }
}

$duplicateRejected = $false
try { Join-Ticket04ResultChunks -LogLines ($lines + $lines[0]) -RunId $runId | Out-Null }
catch { $duplicateRejected = $true }
if (-not $duplicateRejected) { throw "Ticket 04 accepted a duplicate result chunk." }

$emptyInputReportedAsMissingResult = $false
try { Join-Ticket04ResultChunks -LogLines @() -RunId $runId | Out-Null }
catch { $emptyInputReportedAsMissingResult = $_.Exception.Message -match 'missing one or more chunks' }
if (-not $emptyInputReportedAsMissingResult) {
    throw "Ticket 04 empty instrumentation output did not produce the missing-result diagnostic."
}

$notificationEvidence = [pscustomobject]@{
    caseId = "notification-revocation"
    actualPermissionEnabledAtBoundary = $false
    requestAgeAtBoundaryMs = 15200
    requestBoundaryClassification = "no-client-request-at-permission-boundary"
    requestStillInFlight = $false
    clientSocketOpenAtPermissionBoundary = $false
    processRestartExpected = $true
    processRestartedAfterBoundary = $true
    sameProductPidAtBoundary = $false
    productPidBefore = "5054"
    productPidAtBoundary = ""
    productPidAfterReturn = "14438"
}
if (-not (Test-Ticket04CaseBoundaryEvidence -Case $notificationEvidence)) {
    throw "Ticket 04 did not accept notification-off evidence based on an observed OS process restart."
}

$accessibilityEvidence = [pscustomobject]@{
    caseId = "accessibility-revocation"
    actualPermissionEnabledAtBoundary = $false
    requestAgeAtBoundaryMs = 12000
    requestBoundaryClassification = "client-request-in-flight-with-open-socket-within-evidence-window"
    requestStillInFlight = $true
    clientSocketOpenAtPermissionBoundary = $true
    sameProductPidAtBoundary = $true
    processRestartExpected = $false
    processRestartedAfterBoundary = $false
    productPidBefore = "14438"
    productPidAtBoundary = "14438"
    productPidAfterReturn = "14438"
}
if (-not (Test-Ticket04CaseBoundaryEvidence -Case $accessibilityEvidence)) {
    throw "Ticket 04 rejected valid same-PID in-flight Accessibility boundary evidence."
}

$accessibilityRestartEvidence = $accessibilityEvidence.PSObject.Copy()
$accessibilityRestartEvidence.sameProductPidAtBoundary = $false
$accessibilityRestartEvidence.processRestartedAfterBoundary = $true
$accessibilityRestartEvidence.productPidAfterReturn = "21975"
if (Test-Ticket04CaseBoundaryEvidence -Case $accessibilityRestartEvidence) {
    throw "Ticket 04 accepted a process restart as an Accessibility same-PID boundary."
}

$lateAccessibilityEvidence = $accessibilityEvidence.PSObject.Copy()
$lateAccessibilityEvidence.requestAgeAtBoundaryMs = 13500
$lateAccessibilityEvidence.requestBoundaryClassification = "client-request-in-flight-with-open-socket-outside-evidence-window"
if (Test-Ticket04CaseBoundaryEvidence -Case $lateAccessibilityEvidence) {
    throw "Ticket 04 accepted an Accessibility boundary at the 13.5-second cutoff."
}

$accessibilityWrongPidEvidence = $accessibilityEvidence.PSObject.Copy()
$accessibilityWrongPidEvidence.productPidAtBoundary = "21975"
if (Test-Ticket04CaseBoundaryEvidence -Case $accessibilityWrongPidEvidence) {
    throw "Ticket 04 accepted mismatched Accessibility PIDs at the actual permission boundary."
}

$notificationLiveLateEvidence = $notificationEvidence.PSObject.Copy()
$notificationLiveLateEvidence.requestAgeAtBoundaryMs = 15200
$notificationLiveLateEvidence.requestBoundaryClassification = "client-request-in-flight-with-open-socket-outside-evidence-window"
$notificationLiveLateEvidence.requestStillInFlight = $true
$notificationLiveLateEvidence.clientSocketOpenAtPermissionBoundary = $true
if (-not (Test-Ticket04CaseBoundaryEvidence -Case $notificationLiveLateEvidence)) {
    throw "Ticket 04 incorrectly applied the Accessibility evidence cutoff to the notification restart case."
}

$notificationInconsistentEvidence = $notificationEvidence.PSObject.Copy()
$notificationInconsistentEvidence.requestBoundaryClassification = "client-request-in-flight-with-open-socket-within-evidence-window"
if (Test-Ticket04CaseBoundaryEvidence -Case $notificationInconsistentEvidence) {
    throw "Ticket 04 accepted a request classification that disagreed with its in-flight and socket evidence."
}

$notificationUnknownAgeEvidence = $notificationEvidence.PSObject.Copy()
$notificationUnknownAgeEvidence.requestAgeAtBoundaryMs = -1
$notificationUnknownAgeEvidence.requestBoundaryClassification = "invalid-request-age"
if (Test-Ticket04CaseBoundaryEvidence -Case $notificationUnknownAgeEvidence) {
    throw "Ticket 04 accepted an invalid request age at the permission boundary."
}

$notificationCaseResult = [pscustomobject]@{
    runId = "0123456789abcdef0123456789abcdef"
    caseId = "notification-revocation"
    actualPermissionEnabledAtBoundary = $false
    requestAgeAtBoundaryMs = 5488
    requestBoundaryClassification = "no-client-request-at-permission-boundary"
    requestStillInFlight = $false
    clientSocketOpenAtPermissionBoundary = $false
    sameProductPidAtBoundary = $false
    processRestartExpected = $true
    processRestartedAfterBoundary = $true
    productPidBefore = "21975"
    productPidAtBoundary = "26127"
    productPidAfterReturn = "26127"
    heldResponseStatus = 200
    permissionPauseUiObserved = $true
    authenticationPauseAfterRelease = $false
    staleCompletionRequests = 0
    registeredChildrenPreserved = $true
    serviceLeaseAtDispatch = $true
    serviceLeaseAtPause = $true
    subjectStateComparison = "unchanged-after-release-in-restarted-process"
    serverResponseWrite = "write-failed"
    connectionCloseClassification = "client-connection-closed-after-permission-boundary-within-15s-window; cancellation-consistent, cause-unconfirmed"
    userAcknowledgementElapsedRealtimeMs = 108518700
    permissionBoundaryElapsedRealtimeMs = 108518352
    responseReleaseElapsedRealtimeMs = 108518710
    restoredServiceLease = $true
    recoverySilentBaseline = $true
    laterCompletionCount = 1
    serverRequestSequence = 5
}
$accessibilityCaseResult = [pscustomobject]@{
    runId = "fedcba9876543210fedcba9876543210"
    caseId = "accessibility-revocation"
    actualPermissionEnabledAtBoundary = $false
    requestAgeAtBoundaryMs = 8000
    requestBoundaryClassification = "client-request-in-flight-with-open-socket-within-evidence-window"
    requestStillInFlight = $true
    clientSocketOpenAtPermissionBoundary = $true
    sameProductPidAtBoundary = $true
    processRestartExpected = $false
    processRestartedAfterBoundary = $false
    productPidBefore = "26127"
    productPidAtBoundary = "26127"
    productPidAfterReturn = "26127"
    heldResponseStatus = 401
    permissionPauseUiObserved = $true
    authenticationPauseAfterRelease = $false
    staleCompletionRequests = 0
    registeredChildrenPreserved = $true
    serviceLeaseAtDispatch = $true
    serviceLeaseAtPause = $false
    subjectStateComparison = "unchanged-after-release-in-same-process"
    serverResponseWrite = "write-failed"
    connectionCloseClassification = "client-still-connected-at-explicit-release; response-delivery-outcome-will-be-observed"
    userAcknowledgementElapsedRealtimeMs = 209000
    permissionBoundaryElapsedRealtimeMs = 208500
    responseReleaseElapsedRealtimeMs = 209010
    restoredServiceLease = $true
    recoverySilentBaseline = $true
    laterCompletionCount = 1
    serverRequestSequence = 9
}

function New-Ticket04CaseArtifact($CaseResult, [string]$CaseId, [string]$CaseRunId, [long]$Sequence, [int]$ResponseStatus, [string]$DeliveryStatus) {
    $fixture = [pscustomobject]@{
        runId = $CaseRunId
        scenario = "ticket04"
        releasedReportHolds = @([pscustomobject]@{
            caseId = $CaseId
            responseStatus = $ResponseStatus
            deliveryStatus = $DeliveryStatus
            sequence = $Sequence
        })
        inFlightRequestCount = 0
        serverHeldResponseCount = 0
    }
    return [pscustomobject]@{
        schemaVersion = 1
        contractVersion = 1
        caseId = $CaseId
        runId = $CaseRunId
        sourceRevision = "7eb0839c76ed8c40fd1c8fe107465f4bea2a08fc"
        sourceContentSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        workingTreeDirty = $false
        device = [pscustomobject]@{ serial = "T811MA256GB23418064398"; model = "SM-T733"; apiLevel = 33 }
        applicationId = "com.example.todoschooltime.debug"
        productApkSha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        observerApkSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        observerTestApkSha256 = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        fixture = $fixture
        caseResult = $CaseResult
        validation = [pscustomobject]@{ status = "passed"; checks = @("case", "fixture") }
    }
}

if (-not (Test-Ticket04CaseResult -Case $notificationCaseResult) -or -not (Test-Ticket04CaseResult -Case $accessibilityCaseResult)) {
    throw "Ticket 04 rejected a complete case result even though the notification and Accessibility cases satisfy their independent contracts."
}
$notificationCaseArtifact = New-Ticket04CaseArtifact $notificationCaseResult "notification-revocation" $notificationCaseResult.runId 5 200 "write-failed"
$accessibilityCaseArtifact = New-Ticket04CaseArtifact $accessibilityCaseResult "accessibility-revocation" $accessibilityCaseResult.runId 9 401 "write-failed"
if (-not (Test-Ticket04CaseEvidence -Evidence $notificationCaseArtifact -ExpectedDeviceSerial $notificationCaseArtifact.device.serial -ExpectedProductApkSha256 $notificationCaseArtifact.productApkSha256) -or
    -not (Test-Ticket04CaseEvidence -Evidence $accessibilityCaseArtifact -ExpectedDeviceSerial $notificationCaseArtifact.device.serial -ExpectedProductApkSha256 $notificationCaseArtifact.productApkSha256)) {
    throw "Ticket 04 rejected valid immutable per-case artifacts."
}
$combinedCases = @(Join-Ticket04CaseEvidence -Evidence @($accessibilityCaseArtifact, $notificationCaseArtifact) -ExpectedDeviceSerial $notificationCaseArtifact.device.serial -ExpectedProductApkSha256 $notificationCaseArtifact.productApkSha256)
if ($combinedCases.Count -ne 2 -or $combinedCases[0].caseId -cne "notification-revocation" -or $combinedCases[1].caseId -cne "accessibility-revocation") {
    throw "Ticket 04 did not combine the notification and Accessibility cases in canonical order."
}

$missingCaseRejected = $false
try { Join-Ticket04CaseEvidence -Evidence @($notificationCaseArtifact) | Out-Null } catch { $missingCaseRejected = $_.Exception.Message -match 'exactly two' }
if (-not $missingCaseRejected) { throw "Ticket 04 converted a missing case into a complete aggregate." }
$duplicateCaseRejected = $false
try { Join-Ticket04CaseEvidence -Evidence @($notificationCaseArtifact, $notificationCaseArtifact) | Out-Null } catch { $duplicateCaseRejected = $true }
if (-not $duplicateCaseRejected) { throw "Ticket 04 accepted duplicate case evidence." }
$differentProductRejected = $accessibilityCaseArtifact.PSObject.Copy()
$differentProductRejected.productApkSha256 = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
$differentProductRejected.caseResult = $accessibilityCaseResult.PSObject.Copy()
$differentProductRejected.caseResult.runId = $differentProductRejected.runId
$differentProductRejected.fixture = New-Ticket04CaseArtifact $differentProductRejected.caseResult "accessibility-revocation" $differentProductRejected.runId 9 401 "write-failed" | Select-Object -ExpandProperty fixture
$differentProductRejected.fixture.runId = $differentProductRejected.runId
$binaryMismatchRejected = $false
try { Join-Ticket04CaseEvidence -Evidence @($notificationCaseArtifact, $differentProductRejected) | Out-Null } catch { $binaryMismatchRejected = $true }
if (-not $binaryMismatchRejected) { throw "Ticket 04 combined evidence from different product APKs." }
$differentSourceSnapshot = $accessibilityCaseArtifact.PSObject.Copy()
$differentSourceSnapshot.sourceContentSha256 = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
$sourceMismatchRejected = $false
try { Join-Ticket04CaseEvidence -Evidence @($notificationCaseArtifact, $differentSourceSnapshot) | Out-Null } catch { $sourceMismatchRejected = $true }
if (-not $sourceMismatchRejected) { throw "Ticket 04 combined case evidence from different test-source snapshots." }
$failedValidationRejected = $accessibilityCaseArtifact.PSObject.Copy()
$failedValidationRejected.validation = [pscustomobject]@{ status = "failed"; checks = @() }
if (Test-Ticket04CaseEvidence -Evidence $failedValidationRejected) { throw "Ticket 04 accepted a failed case artifact as complete." }

Write-Host "PASS Ticket 04 case-level result validation, immutable artifact schema, strict two-case aggregation, and incomplete/duplicate/mismatched-binary negatives."

Write-Host "PASS Ticket 04 result chunks and permission-specific boundary gates, including inconsistent-state and Accessibility restart/cutoff negatives."
