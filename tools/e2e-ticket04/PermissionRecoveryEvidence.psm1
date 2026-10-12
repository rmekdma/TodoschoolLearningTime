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

    $expectedBoundaryClassification = if ($Case.caseId -eq "accessibility-revocation") {
        "cancellation-consistent-within-observed-transition-interval"
    } elseif (-not $Case.requestStillInFlight) {
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
            -not $Case.requestStillInFlight -and
            -not $Case.clientSocketOpenAtPermissionBoundary -and
            $Case.sameProductPidAtBoundary -and
            -not $Case.processRestartedAfterBoundary -and
            $pidAtBoundary -eq $pidBefore -and
            $pidAfterReturn -eq $pidBefore -and
            $Case.requestBoundaryClassification -ceq "cancellation-consistent-within-observed-transition-interval" -and
            $requestAge -ge 0 -and $requestAge -lt 13500 -and
            (Test-Ticket04AccessibilityTransitionEvidence -Case $Case)
    }

    return $false
}

function Test-Ticket04AccessibilityTransitionEvidence {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object]$Case)

    $evidence = $Case.accessibilityBoundaryEvidence
    if ($null -eq $evidence -or
        [string]$evidence.classification -cne "cancellation-consistent-within-observed-transition-interval" -or
        $evidence.qualifies -isnot [bool] -or -not $evidence.qualifies -or
        [int64]$evidence.maximumTransitionIntervalMs -ne 1500 -or
        [int64]$evidence.maximumFixtureRequestRttMs -ne 500 -or
        [int64]$evidence.readTimeoutMs -ne 15000) { return $false }

    $live = $evidence.lastLiveSample
    $off = $evidence.firstOffSample
    if ($null -eq $live -or $null -eq $off) { return $false }
    $requiredSampleProperties = @(
        "caseId", "requestSequence", "deviceStartElapsedRealtimeNs", "deviceEndElapsedRealtimeNs",
        "fixtureRequestRttNs", "permissionEnabledBefore", "permissionEnabledAfter", "serviceLeaseAttached",
        "productPid", "expectedProductPid", "requestInFlight", "serverResponseHeld", "clientConnected",
        "serverSnapshotMonotonicNs", "serverReceivedMonotonicNs", "clientDisconnectMonotonicNs", "clientDisconnectSignal"
    )
    foreach ($sample in @($live, $off)) {
        if (@($sample.PSObject.Properties.Name | Where-Object { $_ -in $requiredSampleProperties }).Count -ne $requiredSampleProperties.Count) {
            return $false
        }
        foreach ($booleanProperty in @("permissionEnabledBefore", "permissionEnabledAfter", "serviceLeaseAttached", "requestInFlight", "serverResponseHeld", "clientConnected")) {
            if ($sample.PSObject.Properties[$booleanProperty].Value -isnot [bool]) { return $false }
        }
        foreach ($numericProperty in @(
            "requestSequence", "deviceStartElapsedRealtimeNs", "deviceEndElapsedRealtimeNs", "fixtureRequestRttNs",
            "serverSnapshotMonotonicNs", "serverReceivedMonotonicNs"
        )) {
            $value = $sample.PSObject.Properties[$numericProperty].Value
            if ($value -isnot [ValueType] -or $value -is [bool]) { return $false }
            try {
                $integer = [int64]$value
                if ([double]$value -ne [double]$integer -or $integer -lt 0) { return $false }
            } catch { return $false }
        }
        if ([int64]$sample.fixtureRequestRttNs -gt 500000000 -or
            [int64]$sample.deviceEndElapsedRealtimeNs -lt [int64]$sample.deviceStartElapsedRealtimeNs -or
            [string]$sample.caseId -cne [string]$Case.caseId -or
            [int64]$sample.requestSequence -ne [int64]$Case.serverRequestSequence -or
            [string]$sample.expectedProductPid -cne [string]$Case.productPidBefore -or
            [string]$sample.productPid -cne [string]$Case.productPidBefore) { return $false }
    }

    if (-not $live.permissionEnabledBefore -or -not $live.permissionEnabledAfter -or
        -not $live.serviceLeaseAttached -or -not $live.requestInFlight -or -not $live.serverResponseHeld -or -not $live.clientConnected -or
        $off.permissionEnabledBefore -or $off.permissionEnabledAfter -or
        $off.serviceLeaseAttached -or $off.requestInFlight -or -not $off.serverResponseHeld -or $off.clientConnected) {
        return $false
    }
    if ([int64]$live.serverReceivedMonotonicNs -ne [int64]$off.serverReceivedMonotonicNs -or
        [int64]$live.serverSnapshotMonotonicNs -lt [int64]$live.serverReceivedMonotonicNs -or
        [int64]$off.serverSnapshotMonotonicNs -le [int64]$live.serverSnapshotMonotonicNs) { return $false }

    $transitionNs = [int64]$off.deviceStartElapsedRealtimeNs - [int64]$live.deviceEndElapsedRealtimeNs
    if ($transitionNs -lt 0 -or $transitionNs -gt 1500000000) { return $false }
    $transitionMs = [int64][Math]::Floor($transitionNs / 1000000.0)
    if ([int64]$evidence.transitionIntervalMs -ne $transitionMs) { return $false }

    $requestAgeMs = [int64][Math]::Floor((([int64]$live.serverSnapshotMonotonicNs - [int64]$live.serverReceivedMonotonicNs) / 1000000.0))
    if ($requestAgeMs -lt 0 -or $requestAgeMs -ge 13500 -or
        [int64]$evidence.requestAgeAtLastLiveMs -ne $requestAgeMs -or
        [int64]$Case.requestAgeAtBoundaryMs -ne $requestAgeMs) { return $false }

    $disconnectNsValue = $off.PSObject.Properties["clientDisconnectMonotonicNs"].Value
    if ($null -eq $disconnectNsValue -or $disconnectNsValue -is [bool]) { return $false }
    try {
        $disconnectNs = [int64]$disconnectNsValue
        if ([double]$disconnectNsValue -ne [double]$disconnectNs -or $disconnectNs -le [int64]$live.serverSnapshotMonotonicNs -or
            $disconnectNs -gt [int64]$off.serverSnapshotMonotonicNs) { return $false }
    } catch { return $false }
    if ([string]$off.clientDisconnectSignal -notin @("eof", "socket-exception", "socket-error")) { return $false }
    $disconnectAgeMs = [int64][Math]::Floor((($disconnectNs - [int64]$off.serverReceivedMonotonicNs) / 1000000.0))
    if ($disconnectAgeMs -lt 0 -or $disconnectAgeMs -ge 15000 -or
        [int64]$evidence.disconnectAgeMs -ne $disconnectAgeMs) { return $false }
    return $true
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
    $expectedSchemaVersion = if ($Evidence.caseId -ceq "accessibility-revocation") { 2 } else { 1 }
    if ($Evidence.schemaVersion -ne $expectedSchemaVersion -or $Evidence.contractVersion -ne 1 -or
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

function Get-Ticket04SourcePaths([string]$Version) {
    $legacy = @(
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket04PermissionRecoveryTest.kt",
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket04AccessibilitySettingsNavigator.kt",
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket04AccessibilitySettingsNavigationTest.kt",
        "e2eobserver/src/main/java/com/example/todoschooltime/e2eobserver/Ticket04ControlActivity.kt",
        "e2eobserver/src/main/java/com/example/todoschooltime/e2eobserver/Ticket04ActionToken.kt",
        "e2eobserver/src/main/java/com/example/todoschooltime/e2eobserver/Ticket04BoundaryEvidence.kt",
        "tools/e2e-ticket01/Run-Ticket01.ps1",
        "tools/e2e-ticket04/PermissionRecoveryEvidence.psm1",
        "tools/e2e-ticket04/Test-ResultChunks.ps1"
    )
    $current = @($legacy[0..5]) + @(
        "e2eobserver/src/test/java/com/example/todoschooltime/e2eobserver/Ticket04BoundaryEvidenceTest.kt"
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket04ControlActivityTest.kt"
    ) + @($legacy[6]) + @(
        "tools/e2e-ticket01/server.py",
        "tools/e2e-ticket01/test_server.py"
    ) + @($legacy[7..8])
    if ($Version -eq "legacy") { return $legacy }
    if ($Version -eq "current") { return $current }
    throw "Unknown Ticket 04 source-snapshot version '$Version'."
}

function Get-Ticket04SourceContentHashAtRevision {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$Revision,
        [Parameter(Mandatory = $true)][string]$PythonPath,
        [Parameter(Mandatory = $true)][string]$Version
    )
    $paths = @(Get-Ticket04SourcePaths $Version)
    $gitPath = (Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $script = @'
import base64
import hashlib
import json
import subprocess
import sys

payload = json.loads(base64.b64decode(sys.argv[1]).decode("utf-8"))
root = payload["root"]
revision = payload["revision"]
git_path = payload["gitPath"]
paths = payload["paths"]
parts = []
for path in paths:
    try:
        result = subprocess.run(
            [git_path, "-C", root, "show", revision + ":" + path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except Exception as error:
        print(json.dumps({
            "error": "git-show-launch-failed",
            "revision": revision,
            "path": path,
            "exceptionType": type(error).__name__,
            "errno": getattr(error, "errno", None),
            "winerror": getattr(error, "winerror", None),
        }))
        sys.exit(1)
    if result.returncode != 0:
        print(json.dumps({
            "error": "git-show-failed",
            "revision": revision,
            "path": path,
            "exitCode": result.returncode,
        }))
        sys.exit(1)
    content = result.stdout
    parts.append(path + "=" + hashlib.sha256(content).hexdigest())
print(hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest())
'@
    $scriptBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($script))
    $payloadJson = ConvertTo-Json -InputObject ([ordered]@{
        root = $RepositoryRoot
        revision = $Revision
        gitPath = $gitPath
        paths = $paths
    }) -Compress
    $payloadBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payloadJson))
    $bootstrap = "exec(compile(__import__('base64').b64decode('$scriptBase64'), '<ticket04-source-hash>', 'exec'))"
    $output = & $PythonPath -c $bootstrap $payloadBase64 2>$null
    if ($LASTEXITCODE -ne 0) {
        $failureDetail = @($output | ForEach-Object { [string]$_ } | Where-Object { $_ -match 'git-show-(?:launch-)?failed' } | Select-Object -Last 1)
        if ($failureDetail.Count -gt 0) {
            $failure = $failureDetail[0] | ConvertFrom-Json -ErrorAction SilentlyContinue
            if ($failure.error -in @("git-show-failed", "git-show-launch-failed") -and $failure.revision -ceq $Revision -and $failure.path -match '^[A-Za-z0-9_./-]+$') {
                if ($failure.error -ceq "git-show-launch-failed") {
                    throw "Could not hash the Ticket 04 source snapshot at revision '$Revision': git could not start for '$($failure.path)' ($($failure.exceptionType), winerror $($failure.winerror))."
                }
                throw "Could not hash the Ticket 04 source snapshot at revision '$Revision': git show failed for '$($failure.path)' (exit $($failure.exitCode))."
            }
        }
        throw "Could not hash the Ticket 04 source snapshot at revision '$Revision'."
    }
    $hash = (@($output) | Select-Object -Last 1).Trim()
    if ($hash -notmatch '^[0-9a-f]{64}$') { throw "The Ticket 04 source snapshot hash at '$Revision' is invalid." }
    return $hash
}

function Get-Ticket04AllowedCompatibilityPaths {
    return @(
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket04PermissionRecoveryTest.kt",
        "e2eobserver/src/main/java/com/example/todoschooltime/e2eobserver/Ticket04ControlActivity.kt",
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket04ControlActivityTest.kt",
        "e2eobserver/src/main/java/com/example/todoschooltime/e2eobserver/Ticket04BoundaryEvidence.kt",
        "e2eobserver/src/test/java/com/example/todoschooltime/e2eobserver/Ticket04BoundaryEvidenceTest.kt",
        "tools/e2e-ticket01/Run-Ticket01.ps1",
        "tools/e2e-ticket01/server.py",
        "tools/e2e-ticket01/test_server.py",
        "tools/e2e-ticket04/PermissionRecoveryEvidence.psm1",
        "tools/e2e-ticket04/Test-ResultChunks.ps1"
    )
}

function New-Ticket04SourceCompatibilityProof {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$NotificationEvidence,
        [Parameter(Mandatory = $true)][object]$CurrentSourceSnapshot,
        [Parameter(Mandatory = $true)][string]$ExpectedProductApkSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedDeviceSerial,
        [Parameter(Mandatory = $true)][int]$ExpectedApiLevel,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$PythonPath
    )
    if (-not (Test-Ticket04CaseEvidence -Evidence $NotificationEvidence -ExpectedDeviceSerial $ExpectedDeviceSerial -ExpectedProductApkSha256 $ExpectedProductApkSha256) -or
        $NotificationEvidence.caseId -cne "notification-revocation" -or
        [int]$NotificationEvidence.schemaVersion -ne 1 -or
        [int]$NotificationEvidence.contractVersion -ne 1 -or
        [int]$NotificationEvidence.device.apiLevel -ne $ExpectedApiLevel -or
        [string]$NotificationEvidence.applicationId -cne "com.example.todoschooltime.debug" -or
        [bool]$NotificationEvidence.workingTreeDirty) {
        throw "The prior notification artifact is not a complete, clean schema-1 case on the expected device, contract and product binary."
    }
    if ($CurrentSourceSnapshot.workingTreeDirty -isnot [bool] -or $CurrentSourceSnapshot.workingTreeDirty) {
        throw "Source compatibility requires a clean, committed observer/server/validator snapshot."
    }
    $head = (@(& git -C $RepositoryRoot rev-parse HEAD 2>$null) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or $head -cne [string]$CurrentSourceSnapshot.revision) {
        throw "Current Ticket 04 source snapshot must match the clean repository HEAD."
    }
    $status = @(& git -C $RepositoryRoot status --porcelain 2>$null)
    if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) { throw "Source compatibility requires a completely clean repository working tree." }
    $ancestorOutput = & git -C $RepositoryRoot merge-base --is-ancestor ([string]$NotificationEvidence.sourceRevision) $head 2>$null
    if ($LASTEXITCODE -ne 0) { throw "The notification source revision is not an ancestor of the current source revision." }
    $diffPaths = @(& git -C $RepositoryRoot diff --name-only "$($NotificationEvidence.sourceRevision)..$head" 2>$null | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0 -or $diffPaths.Count -eq 0) { throw "The observer source compatibility diff could not be read or is empty." }
    $allowed = @(Get-Ticket04AllowedCompatibilityPaths)
    $unexpected = @($diffPaths | Where-Object { $_ -notin $allowed })
    if ($unexpected.Count -gt 0) { throw "Ticket 04 compatibility refuses changes outside the reviewed observer/server/validator paths: $($unexpected -join ', ')" }

    $legacyHash = Get-Ticket04SourceContentHashAtRevision -RepositoryRoot $RepositoryRoot -Revision $NotificationEvidence.sourceRevision -PythonPath $PythonPath -Version legacy
    if ($legacyHash -cne [string]$NotificationEvidence.sourceContentSha256) {
        throw "The notification artifact source hash does not match its recorded source revision and legacy snapshot contract."
    }
    $currentHash = Get-Ticket04SourceContentHashAtRevision -RepositoryRoot $RepositoryRoot -Revision $head -PythonPath $PythonPath -Version current
    if ($currentHash -cne [string]$CurrentSourceSnapshot.contentSha256) {
        throw "The current Ticket 04 source hash does not match its committed revision and version-2 source snapshot contract."
    }
    return [ordered]@{
        status = "verified"
        notificationSourceRevision = [string]$NotificationEvidence.sourceRevision
        notificationSourceContentSha256 = [string]$NotificationEvidence.sourceContentSha256
        notificationSchemaVersion = [int]$NotificationEvidence.schemaVersion
        accessibilitySourceRevision = $head
        accessibilitySourceContentSha256 = $currentHash
        accessibilitySchemaVersion = 2
        contractVersion = 1
        applicationId = "com.example.todoschooltime.debug"
        productApkSha256 = $ExpectedProductApkSha256
        deviceSerial = $ExpectedDeviceSerial
        deviceApiLevel = $ExpectedApiLevel
        changedFiles = @($diffPaths | Sort-Object -Unique)
        scope = "observer-fixture-validator-only"
    }
}

function Test-Ticket04SourceCompatibilityProof {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Proof,
        [Parameter(Mandatory = $true)][object[]]$Evidence,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$PythonPath
    )
    $byCase = @{}
    foreach ($item in $Evidence) { $byCase[[string]$item.caseId] = $item }
    if (-not $byCase.ContainsKey("notification-revocation") -or -not $byCase.ContainsKey("accessibility-revocation")) { return $false }
    $notification = $byCase["notification-revocation"]
    $accessibility = $byCase["accessibility-revocation"]
    if ($Proof.status -cne "verified" -or
        [string]$Proof.notificationSourceRevision -cne [string]$notification.sourceRevision -or
        [string]$Proof.notificationSourceContentSha256 -cne [string]$notification.sourceContentSha256 -or
        [int]$Proof.notificationSchemaVersion -ne [int]$notification.schemaVersion -or
        [string]$Proof.accessibilitySourceRevision -cne [string]$accessibility.sourceRevision -or
        [string]$Proof.accessibilitySourceContentSha256 -cne [string]$accessibility.sourceContentSha256 -or
        [int]$Proof.accessibilitySchemaVersion -ne [int]$accessibility.schemaVersion -or
        [int]$Proof.contractVersion -ne [int]$notification.contractVersion -or
        [int]$Proof.contractVersion -ne [int]$accessibility.contractVersion -or
        [string]$Proof.applicationId -cne [string]$notification.applicationId -or
        [string]$Proof.applicationId -cne [string]$accessibility.applicationId -or
        [string]$Proof.productApkSha256 -cne [string]$notification.productApkSha256 -or
        [string]$Proof.productApkSha256 -cne [string]$accessibility.productApkSha256 -or
        [string]$Proof.deviceSerial -cne [string]$notification.device.serial -or
        [string]$Proof.deviceSerial -cne [string]$accessibility.device.serial -or
        [int]$Proof.deviceApiLevel -ne [int]$notification.device.apiLevel -or
        [int]$Proof.deviceApiLevel -ne [int]$accessibility.device.apiLevel -or
        [string]$Proof.scope -cne "observer-fixture-validator-only") { return $false }
    try {
        $expectedCurrentHash = Get-Ticket04SourceContentHashAtRevision -RepositoryRoot $RepositoryRoot -Revision ([string]$Proof.accessibilitySourceRevision) -PythonPath $PythonPath -Version current
        $expectedLegacyHash = Get-Ticket04SourceContentHashAtRevision -RepositoryRoot $RepositoryRoot -Revision ([string]$Proof.notificationSourceRevision) -PythonPath $PythonPath -Version legacy
    } catch { return $false }
    if ($expectedCurrentHash -cne [string]$accessibility.sourceContentSha256 -or
        $expectedLegacyHash -cne [string]$notification.sourceContentSha256) { return $false }
    $head = (@(& git -C $RepositoryRoot rev-parse HEAD 2>$null) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or $head -cne [string]$accessibility.sourceRevision) { return $false }
    $status = @(& git -C $RepositoryRoot status --porcelain 2>$null)
    if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) { return $false }
    & git -C $RepositoryRoot merge-base --is-ancestor ([string]$notification.sourceRevision) $head 2>$null
    if ($LASTEXITCODE -ne 0) { return $false }
    $actualPaths = @(& git -C $RepositoryRoot diff --name-only "$($notification.sourceRevision)..$head" 2>$null | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Sort-Object -Unique)
    if ($LASTEXITCODE -ne 0 -or $actualPaths.Count -eq 0) { return $false }
    $allowed = @(Get-Ticket04AllowedCompatibilityPaths)
    if (@($actualPaths | Where-Object { $_ -notin $allowed }).Count -gt 0) { return $false }
    $proofPaths = @($Proof.changedFiles | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    if (($proofPaths -join "`n") -cne ($actualPaths -join "`n")) { return $false }
    return $true
}

function Join-Ticket04CaseEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Evidence,
        [string]$ExpectedDeviceSerial,
        [string]$ExpectedProductApkSha256,
        [object]$SourceCompatibilityProof,
        [string]$RepositoryRoot,
        [string]$PythonPath
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
    $requiresCompatibilityProof = $false
    foreach ($item in $items | Select-Object -Skip 1) {
        if ($item.applicationId -cne $first.applicationId -or
            $item.productApkSha256 -cne $first.productApkSha256 -or
            $item.device.serial -cne $first.device.serial -or
            [int]$item.device.apiLevel -ne [int]$first.device.apiLevel -or
            $item.contractVersion -ne $first.contractVersion) {
            throw "Ticket 04 cases cannot be combined across different product binaries, devices, API levels, or contracts."
        }
        if ($item.sourceRevision -cne $first.sourceRevision -or
            $item.sourceContentSha256 -cne $first.sourceContentSha256 -or
            $item.workingTreeDirty -ne $first.workingTreeDirty) {
            $requiresCompatibilityProof = $true
        }
    }
    if ($requiresCompatibilityProof -and
        ($null -eq $SourceCompatibilityProof -or [string]::IsNullOrWhiteSpace($RepositoryRoot) -or
            [string]::IsNullOrWhiteSpace($PythonPath) -or
            -not (Test-Ticket04SourceCompatibilityProof -Proof $SourceCompatibilityProof -Evidence $items -RepositoryRoot $RepositoryRoot -PythonPath $PythonPath))) {
        throw "Ticket 04 source snapshots differ and no verifiable observer/server/validator-only compatibility proof was supplied."
    }
    return @($byCase["notification-revocation"], $byCase["accessibility-revocation"])
}

Export-ModuleMember -Function Test-Ticket04CaseBoundaryEvidence, Test-Ticket04CaseResult, Test-Ticket04CaseEvidence, Join-Ticket04CaseEvidence, New-Ticket04SourceCompatibilityProof, Test-Ticket04SourceCompatibilityProof
