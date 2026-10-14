<#
.SYNOPSIS
Runs one isolated Android learning-monitor integration scenario.

.DESCRIPTION
Scenario selectors are functional names. The runner maps them to stable fixture protocol IDs internally.
Observer scenarios use explicit build, prepare, refresh-observer, and run modes. Persistence runs require
an explicit task-removal or reboot-recovery case; the runner never starts a reboot scenario by default.

.PARAMETER Scenario
One of screen, baseline, foreground, permissions, authentication, or persistence.

.PARAMETER Mode
One of build, prepare, refresh-observer, or run.

.PARAMETER Case
Required for permissions run (notification, accessibility, or both) and persistence run
(task-removal or reboot-recovery).

.EXAMPLE
.\tools\Run-IntegrationTests.ps1 -Scenario baseline -Mode build

.EXAMPLE
.\tools\Run-IntegrationTests.ps1 -Scenario persistence -Mode run -Case task-removal
#>
[CmdletBinding()]
param(
    [ValidateSet('screen', 'baseline', 'foreground', 'permissions', 'authentication', 'persistence')]
    [string]$Scenario,
    [ValidateSet('build', 'prepare', 'refresh-observer', 'run')]
    [string]$Mode,
    [ValidateSet('both', 'notification', 'accessibility', 'task-removal', 'reboot-recovery')]
    [string]$Case,
    [string]$ConfigPath,
    [string]$DeviceSerial
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Scenario) -or [string]::IsNullOrWhiteSpace($Mode)) {
    throw 'Usage: Run-IntegrationTests.ps1 -Scenario screen|baseline|foreground|permissions|authentication|persistence -Mode build|prepare|refresh-observer|run [-Case ...]. See Get-Help .\tools\Run-IntegrationTests.ps1 -Full.'
}

$scenarioIds = @{
    screen = 'ticket01'
    baseline = 'ticket02'
    foreground = 'ticket03'
    permissions = 'ticket04'
    authentication = 'ticket05'
    persistence = 'ticket06'
}
$wireScenario = [string]$scenarioIds[$Scenario]
$observerScenario = $Scenario -ne 'screen'
$caseSupplied = $PSBoundParameters.ContainsKey('Case')

if (-not $observerScenario -and $Mode -in @('prepare', 'refresh-observer')) {
    throw "Mode '$Mode' is only available for observer scenarios."
}
if ($Scenario -eq 'persistence' -and $Mode -eq 'prepare') {
    throw 'Persistence uses the baseline prepared-device manifest; run baseline -Mode prepare first, then persistence -Mode refresh-observer.'
}
if ($Mode -eq 'run' -and $Scenario -eq 'permissions' -and -not $caseSupplied) {
    throw 'Permission runs require -Case notification, accessibility, or both.'
}
if ($Mode -eq 'run' -and $Scenario -eq 'persistence' -and -not $caseSupplied) {
    throw 'Persistence runs require an explicit -Case task-removal or reboot-recovery.'
}
if ($caseSupplied -and $Mode -ne 'run') {
    throw '-Case is only valid with -Mode run.'
}
if ($caseSupplied -and $Scenario -eq 'permissions' -and $Case -notin @('notification', 'accessibility', 'both')) {
    throw 'Permission -Case must be notification, accessibility, or both.'
}
if ($caseSupplied -and $Scenario -eq 'persistence' -and $Case -notin @('task-removal', 'reboot-recovery')) {
    throw 'Persistence -Case must be task-removal or reboot-recovery.'
}
if ($caseSupplied -and $Scenario -notin @('permissions', 'persistence')) {
    throw '-Case is only valid for permissions and persistence runs.'
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
        if ($Scenario -eq 'permissions') { $arguments.Ticket04Case = $Case }
        if ($Scenario -eq 'persistence') {
            $arguments.Case = $Case
            $arguments.RunPrepared = $true
        }
    }
}

& $implementationPath @arguments
if (-not $?) { exit 1 }
