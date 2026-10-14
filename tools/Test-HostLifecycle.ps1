$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $thrown = $false
    try { & $Action } catch { $thrown = $true }
    Assert-True $thrown $Message
}

$transientNoPid = Resolve-Ticket04PidofResult -ExitCode 1 -Output @() -PackageName "com.example.todoschooltime.debug"
Assert-True ($transientNoPid -eq "") "A pidof exit 1 with empty output should represent a temporarily absent process."
Assert-True (Test-Ticket04HostLifecycleSample -CaseMode notification -InitialPid "7348" -ObservedPid $transientNoPid -Crashed $false) `
    "The notification case should tolerate a transient no-PID sample while Android restarts the process."
Assert-True (Test-Ticket04HostLifecycleSample -CaseMode notification -InitialPid "7348" -ObservedPid "17737" -Crashed $false) `
    "The notification case should tolerate the new PID after the OS restart."
Assert-True (Test-Ticket04HostLifecycleSample -CaseMode accessibility -InitialPid "7348" -ObservedPid "7348" -Crashed $false) `
    "The Accessibility case should accept the original live product PID."
Assert-True (-not (Test-Ticket04HostLifecycleSample -CaseMode accessibility -InitialPid "7348" -ObservedPid "" -Crashed $false)) `
    "The Accessibility case must reject a missing product PID."
Assert-True (-not (Test-Ticket04HostLifecycleSample -CaseMode accessibility -InitialPid "7348" -ObservedPid "17737" -Crashed $false)) `
    "The Accessibility case must reject a restarted product PID."
Assert-True (-not (Test-Ticket04HostLifecycleSample -CaseMode notification -InitialPid "7348" -ObservedPid "17737" -Crashed $true)) `
    "A reported crashed AccessibilityService must fail even when notification restart is allowed."

Assert-Throws {
    Resolve-Ticket04PidofResult -ExitCode 1 -Output @("adb: device offline") -PackageName "com.example.todoschooltime.debug"
} "A pidof exit 1 with diagnostic output must remain an ADB error."
Assert-Throws {
    Resolve-Ticket04PidofResult -ExitCode 2 -Output @("adb: device offline") -PackageName "com.example.todoschooltime.debug"
} "An unexpected pidof ADB exit code must remain an error."
Assert-Throws {
    Resolve-Ticket04PidofResult -ExitCode 0 -Output @() -PackageName "com.example.todoschooltime.debug"
} "A successful pidof call without a PID must not be treated as a restart gap."

Write-Host "Ticket 04 host lifecycle fixtures passed."
