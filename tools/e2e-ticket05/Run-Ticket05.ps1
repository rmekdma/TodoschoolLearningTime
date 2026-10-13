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
$runner = Join-Path $PSScriptRoot "../e2e-ticket01/Run-Ticket01.ps1"
$runnerArguments = @{ Scenario = "ticket05" }
if ($ConfigPath) { $runnerArguments.ConfigPath = $ConfigPath }
if ($DeviceSerial) { $runnerArguments.DeviceSerial = $DeviceSerial }
if ($DeviceOnly) { $runnerArguments.DeviceOnly = $true }
if ($PrepareDevice) { $runnerArguments.PrepareDevice = $true }
if ($RunPrepared) { $runnerArguments.RunPrepared = $true }
if ($RefreshObserver) { $runnerArguments.RefreshObserver = $true }
& $runner @runnerArguments
