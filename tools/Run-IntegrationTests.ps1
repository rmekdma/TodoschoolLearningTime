<#
.SYNOPSIS
Runs one isolated Android learning-monitor integration scenario.

.DESCRIPTION
Scenario selectors are functional names. The runner maps them to stable fixture protocol IDs internally.
Observer scenarios use explicit build, prepare, refresh-observer, and run modes. Selecting persistence runs
the automated reboot-recovery flow and reboots the selected device.

.PARAMETER Scenario
One of screen, baseline, foreground, authentication, or persistence.

.PARAMETER Mode
One of build, prepare, refresh-observer, or run.

.EXAMPLE
.\tools\Run-IntegrationTests.ps1 -Scenario baseline -Mode build

.EXAMPLE
.\tools\Run-IntegrationTests.ps1 -Scenario persistence -Mode run
#>
[CmdletBinding()]
param(
    [ValidateSet('screen', 'baseline', 'foreground', 'authentication', 'persistence')]
    [string]$Scenario,
    [ValidateSet('build', 'prepare', 'refresh-observer', 'run')]
    [string]$Mode,
    [string]$ConfigPath,
    [string]$DeviceSerial
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Scenario) -or [string]::IsNullOrWhiteSpace($Mode)) {
    throw 'Usage: Run-IntegrationTests.ps1 -Scenario screen|baseline|foreground|authentication|persistence -Mode build|prepare|refresh-observer|run. See Get-Help .\tools\Run-IntegrationTests.ps1 -Full.'
}

$scenarioIds = @{
    screen = 'ticket01'
    baseline = 'ticket02'
    foreground = 'ticket03'
    authentication = 'ticket05'
    persistence = 'ticket06'
}
$wireScenario = [string]$scenarioIds[$Scenario]
$observerScenario = $Scenario -ne 'screen'
if (-not $observerScenario -and $Mode -in @('prepare', 'refresh-observer')) {
    throw "Mode '$Mode' is only available for observer scenarios."
}
if ($Scenario -eq 'persistence' -and $Mode -eq 'prepare') {
    throw 'Persistence uses the baseline prepared-device manifest; run baseline -Mode prepare first, then persistence -Mode refresh-observer.'
}
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($PSBoundParameters.ContainsKey('ConfigPath')) {
    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        throw "Configuration file does not exist: $ConfigPath"
    }
    $resolvedConfigPath = (Resolve-Path -LiteralPath $ConfigPath).Path
} else {
    $resolvedConfigPath = Join-Path $PSScriptRoot 'config.json'
    if (-not (Test-Path -LiteralPath $resolvedConfigPath -PathType Leaf)) {
        $resolvedConfigPath = Join-Path $PSScriptRoot 'config.example.json'
    }
}

if ($Mode -eq 'build') {
    . (Join-Path $PSScriptRoot 'Common.ps1')
    Invoke-IntegrationBuild -RepositoryRoot $repositoryRoot -Scenario $Scenario -ConfigPath $resolvedConfigPath
    exit 0
}

$implementationPath = if ($Scenario -eq 'persistence') {
    Join-Path $PSScriptRoot 'Invoke-MonitorRecovery.ps1'
} else {
    Join-Path $PSScriptRoot 'Invoke-IntegrationScenarios.ps1'
}
$arguments = @{ ConfigPath = $resolvedConfigPath }
if ($Scenario -ne 'persistence') { $arguments.Scenario = $wireScenario }
if ($DeviceSerial) { $arguments.DeviceSerial = $DeviceSerial }

switch ($Mode) {
    'prepare' { $arguments.PrepareDevice = $true }
    'refresh-observer' { $arguments.RefreshObserver = $true }
    'run' {
        if ($observerScenario) { $arguments.RunPrepared = $true }
    }
}

& $implementationPath @arguments
if (-not $?) { exit 1 }
