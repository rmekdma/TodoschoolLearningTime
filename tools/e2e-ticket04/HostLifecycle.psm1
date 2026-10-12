function Resolve-Ticket04PidofResult {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [int]$ExitCode,
        [string[]]$Output = @(),
        [Parameter(Mandatory = $true)]
        [string]$PackageName
    )

    $pidText = (@($Output) | ForEach-Object { [string]$_ }) -join "`n"
    $pidText = $pidText.Trim()
    if ($ExitCode -eq 0) {
        if ([string]::IsNullOrWhiteSpace($pidText) -or $pidText -notmatch '^\d+(?:\s+\d+)*$') {
            throw "adb shell pidof $PackageName returned success without a valid process ID: '$pidText'"
        }
        return ($pidText -replace '\s+', ' ')
    }

    # Toybox pidof returns 1 with no output when the package process is absent.
    # This is expected briefly when Android restarts the product after notification revocation.
    if ($ExitCode -eq 1 -and [string]::IsNullOrWhiteSpace($pidText)) { return "" }

    throw "adb shell pidof $PackageName failed ($ExitCode): $pidText"
}

function Test-Ticket04HostLifecycleSample {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("both", "notification", "accessibility")]
        [string]$CaseMode,
        [Parameter(Mandatory = $true)]
        [string]$InitialPid,
        [AllowEmptyString()]
        [string]$ObservedPid,
        [Parameter(Mandatory = $true)]
        [bool]$Crashed
    )

    if ($Crashed) { return $false }
    if ($CaseMode -eq "accessibility") {
        return -not [string]::IsNullOrWhiteSpace($ObservedPid) -and $ObservedPid -ceq $InitialPid
    }

    # Notification revocation can terminate and restart the product process. The
    # Android case result separately requires a changed PID and a recovered lease.
    # In combined mode the per-case instrumentation assertions remain authoritative.
    return $true
}

Export-ModuleMember -Function Resolve-Ticket04PidofResult, Test-Ticket04HostLifecycleSample
