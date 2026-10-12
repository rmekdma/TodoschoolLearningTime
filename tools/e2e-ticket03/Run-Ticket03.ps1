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
    throw "Choose one Ticket 03 phase: default/-RunPrepared, -RefreshObserver, -PrepareDevice, or legacy -DeviceOnly."
}
$runner = Join-Path $PSScriptRoot "../e2e-ticket01/Run-Ticket01.ps1"
$arguments = @{ Scenario = "ticket03" }
if ($PSBoundParameters.ContainsKey("ConfigPath")) { $arguments.ConfigPath = $ConfigPath }
if ($PSBoundParameters.ContainsKey("DeviceSerial")) { $arguments.DeviceSerial = $DeviceSerial }
if ($RefreshObserver) { $arguments.RefreshObserver = $true }
elseif ($PrepareDevice -or $DeviceOnly) { $arguments.PrepareDevice = $true }
else { $arguments.RunPrepared = $true }

& $runner @arguments
