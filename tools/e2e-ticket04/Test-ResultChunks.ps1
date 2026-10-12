$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "ResultChunks.psm1") -Force

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

Write-Host "PASS Ticket 04 result chunk reconstruction and duplicate rejection."
