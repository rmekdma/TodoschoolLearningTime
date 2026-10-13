function Assert-Ticket05InstrumentationOutcome {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$TransportStatus,
        [AllowNull()][Nullable[int]]$LauncherProcessExitCode,
        [Parameter(Mandatory = $true)][string]$FilteredOutput,
        [Parameter(Mandatory = $true)][string]$StructuredResultJson,
        [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{32}$')][string]$RunId
    )

    if ($TransportStatus.runId -cne $RunId) {
        throw "Ticket 05 native transport status belongs to another run."
    }
    if ($null -eq $TransportStatus.nativeExitCode) {
        throw "Ticket 05 native adb transport exit code is unavailable; it cannot be treated as zero."
    }
    if ([int]$TransportStatus.nativeExitCode -ne 0) {
        throw "Ticket 05 native adb transport exited with code $($TransportStatus.nativeExitCode)."
    }
    if ($null -ne $LauncherProcessExitCode -and [int]$LauncherProcessExitCode -ne [int]$TransportStatus.nativeExitCode) {
        throw "Ticket 05 launcher process exit code disagrees with the native adb transport status."
    }
    if ($FilteredOutput -notmatch 'OK \(1 test\)' -or
        $FilteredOutput -notmatch 'INSTRUMENTATION_CODE:\s*-1' -or
        $FilteredOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE:\s*-[0-9]') {
        throw "Ticket 05 observer output did not prove exactly one completed instrumentation test."
    }

    try {
        $result = $StructuredResultJson | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "Ticket 05 observer structured result is not valid JSON: $($_.Exception.Message)"
    }
    if ($result.runId -cne $RunId -or $result.scenario -cne "ticket05") {
        throw "Ticket 05 structured result does not match this run and scenario."
    }

    return [pscustomobject]@{
        nativeTransportExitCode = [int]$TransportStatus.nativeExitCode
        launcherProcessExitCode = if ($null -eq $LauncherProcessExitCode) { $null } else { [int]$LauncherProcessExitCode }
        launcherProcessExitCodeObserved = ($null -ne $LauncherProcessExitCode)
        instrumentationTestCount = 1
        instrumentationTestPassed = $true
        structuredResultRunId = [string]$result.runId
    }
}

Export-ModuleMember -Function Assert-Ticket05InstrumentationOutcome
