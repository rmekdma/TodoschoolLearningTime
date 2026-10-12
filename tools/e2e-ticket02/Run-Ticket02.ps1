[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$DeviceSerial,
    [switch]$DeviceOnly,
    [switch]$PrepareDevice,
    [switch]$RunPrepared,
    [switch]$RefreshObserver
)

$ErrorActionPreference = "Stop"
if ((@($DeviceOnly, $PrepareDevice, $RunPrepared, $RefreshObserver) | Where-Object { $_ }).Count -gt 1) {
    throw "Choose one Ticket 02 phase: default/-RunPrepared, -PrepareDevice, -RefreshObserver, or legacy -DeviceOnly."
}
$runner = Join-Path $PSScriptRoot "../e2e-ticket01/Run-Ticket01.ps1"
$arguments = @{ Scenario = "ticket02" }
if ($PSBoundParameters.ContainsKey("ConfigPath")) { $arguments.ConfigPath = $ConfigPath }
if ($PSBoundParameters.ContainsKey("DeviceSerial")) { $arguments.DeviceSerial = $DeviceSerial }
if ($RefreshObserver) { $arguments.RefreshObserver = $true }
elseif ($PrepareDevice -or $DeviceOnly) { $arguments.PrepareDevice = $true }
else { $arguments.RunPrepared = $true }

& $runner @arguments
