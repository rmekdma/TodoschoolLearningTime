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

Write-Host "PASS Ticket 04 result chunks and permission-specific boundary gates, including inconsistent-state and Accessibility restart/cutoff negatives."
