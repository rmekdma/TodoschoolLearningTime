[CmdletBinding()]
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot "config.json"),
    [string]$DeviceSerial,
    [ValidateSet("ticket01", "ticket02", "ticket03", "ticket04", "ticket05")]
    [string]$Scenario = "ticket01",
    [switch]$DeviceOnly,
    [switch]$PrepareDevice,
    [switch]$RunPrepared,
    [switch]$RefreshObserver,
    [ValidateSet("both", "notification", "accessibility")]
    [string]$Ticket04Case = "both",
    [string]$Ticket04NotificationEvidencePath
)

$ErrorActionPreference = "Stop"
if ($Scenario -eq "ticket04") {
    Import-Module (Join-Path $PSScriptRoot "../e2e-ticket04/PermissionRecoveryEvidence.psm1") -Force
    Import-Module (Join-Path $PSScriptRoot "../e2e-ticket04/HostLifecycle.psm1") -Force
}
if ($Scenario -eq "ticket05") {
    Import-Module (Join-Path $PSScriptRoot "../e2e-ticket05/ResultChunks.psm1") -Force
    Import-Module (Join-Path $PSScriptRoot "../e2e-ticket05/HttpAuthRecoveryEvidence.psm1") -Force
}
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$usesExternalObserver = $Scenario -in @("ticket02", "ticket03", "ticket04", "ticket05")
$configExample = Join-Path $PSScriptRoot "config.example.json"
$configFile = if (Test-Path -LiteralPath $ConfigPath) { $ConfigPath } else { $configExample }
$config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json

if ($DeviceOnly -and ($PrepareDevice -or $RunPrepared -or $RefreshObserver)) {
    throw "Choose only one phase: -PrepareDevice, -RunPrepared, -RefreshObserver, or legacy -DeviceOnly."
}
if ($PrepareDevice -and ($RunPrepared -or $RefreshObserver)) { throw "Choose only one external-observer phase: -PrepareDevice, -RunPrepared, or -RefreshObserver." }
if ($RunPrepared -and $RefreshObserver) { throw "Choose either -RunPrepared or -RefreshObserver, not both." }
if ($usesExternalObserver -and $DeviceOnly -and -not $PrepareDevice -and -not $RunPrepared -and -not $RefreshObserver) {
    $PrepareDevice = $true
}
if ($usesExternalObserver -and -not $PrepareDevice -and -not $RunPrepared -and -not $RefreshObserver) {
    $RunPrepared = $true
}
if ($Scenario -ne "ticket04" -and ($Ticket04Case -ne "both" -or $Ticket04NotificationEvidencePath)) {
    throw "-Ticket04Case and -Ticket04NotificationEvidencePath are only available for Scenario ticket04."
}
if ($Scenario -eq "ticket04" -and ($PrepareDevice -or $RefreshObserver) -and ($Ticket04Case -ne "both" -or $Ticket04NotificationEvidencePath)) {
    throw "Ticket 04 case selection applies only to -RunPrepared; preparation and observer refresh do not run cases."
}
if ($Scenario -eq "ticket04" -and $Ticket04Case -eq "accessibility" -and -not $Ticket04NotificationEvidencePath) {
    throw "Accessibility-only Ticket 04 runs require a validated notification case artifact for strict aggregation."
}
if ($Ticket04Case -ne "accessibility" -and $Ticket04NotificationEvidencePath) {
    throw "-Ticket04NotificationEvidencePath is only used when -Ticket04Case accessibility is selected."
}
if (($PrepareDevice -or $RunPrepared -or $RefreshObserver) -and -not $usesExternalObserver) {
    throw "-PrepareDevice, -RunPrepared, and -RefreshObserver are reserved for observer-based scenarios."
}

if ($config.serverHost -ne "127.0.0.1") {
    throw "E2E scenarios only accept serverHost 127.0.0.1 so test traffic cannot be sent to a remote API."
}
$serverPort = [int]$config.serverPort
if ($serverPort -lt 1024 -or $serverPort -gt 65535) {
    throw "serverPort must be in 1024..65535."
}

$apiBaseUrl = "http://127.0.0.1:$serverPort"
$debugApplicationId = "com.example.todoschooltime.debug"
$testApplicationId = "com.example.todoschooltime.debug.test"
$releaseApplicationId = "com.example.todoschooltime"
$observerApplicationId = "com.example.todoschooltime.e2eobserver"
$observerTestApplicationId = "$observerApplicationId.test"
$observerAccessibilityLabel = "토도 학습 감시 · 로컬 테스트"
$runId = [Guid]::NewGuid().ToString("N")
$ticket04PriorNotificationCaseEvidence = $null
$artifactDirectory = Join-Path $repositoryRoot "build\e2e-$Scenario\$runId"
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null

$gradleUserHome = Join-Path $repositoryRoot "build\gradle-user-home"
$androidUserHome = Join-Path $repositoryRoot "build\android-user-home"
New-Item -ItemType Directory -Path $gradleUserHome, $androidUserHome -Force | Out-Null

$javaHome = [string]$config.javaHome
$pythonPath = [string]$config.pythonPath
if (-not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
    throw "Configured Java 21 runtime is missing: $javaHome"
}
if (-not (Test-Path -LiteralPath $pythonPath)) {
    throw "Configured Python runtime is missing: $pythonPath"
}
$serverPythonPath = (& $pythonPath -c "import sys; print(sys.executable)" | Select-Object -Last 1).Trim()
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $serverPythonPath)) {
    throw "Could not resolve the configured Python runtime executable: $pythonPath"
}

$adbCommand = Get-Command adb -ErrorAction Stop
$adbPath = $adbCommand.Source
$sdkRoot = Split-Path (Split-Path $adbPath -Parent) -Parent
$buildToolsDirectory = Join-Path $sdkRoot "build-tools"
$aaptPath = Get-ChildItem -LiteralPath $buildToolsDirectory -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName "aapt.exe" } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
$apksignerPath = Get-ChildItem -LiteralPath $buildToolsDirectory -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if (-not $aaptPath) {
    throw "Could not find aapt.exe under $buildToolsDirectory"
}
if (-not $apksignerPath) { throw "Could not find apksigner.bat under $buildToolsDirectory" }

$deviceOutput = & $adbPath devices -l 2>&1
if ($LASTEXITCODE -ne 0) { throw "adb devices failed: $($deviceOutput -join "`n")" }
$connectedDevices = @(
    $deviceOutput | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device(?:\s|$)') { $Matches[1] }
    }
)
if (-not $DeviceSerial) { $DeviceSerial = [string]$config.deviceSerial }
if (-not $DeviceSerial) {
    if ($connectedDevices.Count -eq 1) {
        $DeviceSerial = $connectedDevices[0]
    } elseif ($connectedDevices -contains "T811MA256GB23418064398") {
        $DeviceSerial = "T811MA256GB23418064398"
    } else {
        throw "Set deviceSerial in config.json or pass -DeviceSerial when zero or multiple devices are connected."
    }
}
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]+$') {
    throw "Device serial contains unsupported characters: $DeviceSerial"
}
if ($connectedDevices -notcontains $DeviceSerial) {
    throw "Selected device '$DeviceSerial' is not connected and ready. Connected devices: $($connectedDevices -join ', ')"
}

function Invoke-SelectedAdb([string[]]$Arguments) {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # adb pull writes its successful transfer summary to stderr on some
        # platform-tools releases; capture it without treating exit 0 as fatal.
        $ErrorActionPreference = "Continue"
        $output = & $adbPath -s $DeviceSerial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "adb -s $DeviceSerial $($Arguments -join ' ') failed ($exitCode): $($output -join "`n")"
    }
    return $output
}

function Get-PackagePath([string]$PackageName) {
    $output = Invoke-SelectedAdb @("shell", "pm", "list", "packages", "-f", $PackageName)
    foreach ($line in $output) {
        if ($line -match '^package:(?<path>.+)=(?<package>[^=]+)$' -and $Matches.package -eq $PackageName) {
            return $Matches.path
        }
    }
    return $null
}

function Get-ApkSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ApkSignerFingerprint([string]$Path) {
    $output = & $apksignerPath verify --print-certs $Path 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed for $Path`: $($output -join "`n")" }
    $match = [regex]::Match(($output -join "`n"), "Signer #1 certificate SHA-256 digest: (?<digest>[A-Fa-f0-9]+)")
    if (-not $match.Success) { throw "Could not read the APK signer fingerprint for $Path." }
    return $match.Groups["digest"].Value.ToLowerInvariant()
}

function Get-InstalledApkSha256([string]$PackageName, [string]$ArtifactName) {
    $installedPath = Get-PackagePath $PackageName
    if (-not $installedPath) { throw "Required package is not installed: $PackageName" }
    $pulledApk = Join-Path $artifactDirectory $ArtifactName
    Invoke-SelectedAdb @("pull", $installedPath, $pulledApk) | Out-Null
    return Get-ApkSha256 $pulledApk
}

function Assert-InstalledApkEquals([string]$PackageName, [string]$LocalApk, [string]$ArtifactName) {
    $expectedHash = Get-ApkSha256 $LocalApk
    $installedHash = Get-InstalledApkSha256 $PackageName $ArtifactName
    if ($installedHash -ne $expectedHash) {
        throw "Installed $PackageName does not match the prepared APK; refusing to instrument or reinstall it. Run the observer scenario -PrepareDevice, then restore the service manually."
    }
    return $installedHash
}

function Get-DeviceMonitorState {
    if ($Scenario -eq "ticket04") {
        $pidofOutput = & $adbPath -s $DeviceSerial shell pidof $debugApplicationId 2>&1
        $pidText = Resolve-Ticket04PidofResult -ExitCode $LASTEXITCODE -Output @($pidofOutput) -PackageName $debugApplicationId
    } else {
        $pidText = (Invoke-SelectedAdb @("shell", "pidof", $debugApplicationId) | Out-String).Trim()
    }
    $dump = (Invoke-SelectedAdb @("shell", "dumpsys", "accessibility") | Out-String)
    $boundStart = $dump.IndexOf("Bound services:", [StringComparison]::Ordinal)
    $enabledStart = $dump.IndexOf("Enabled services:", [StringComparison]::Ordinal)
    $boundText = if ($boundStart -ge 0 -and $enabledStart -gt $boundStart) { $dump.Substring($boundStart, $enabledStart - $boundStart) } else { "" }
    $crashedStart = $dump.IndexOf("Crashed services:", [StringComparison]::Ordinal)
    $crashedText = if ($crashedStart -ge 0) { $dump.Substring($crashedStart) } else { "" }
    return [pscustomobject]@{
        pid = $pidText
        bound = $boundText.Contains("토도 학습 감시 · 로컬 테스트")
        crashed = $crashedText.Contains("$debugApplicationId/com.example.todoschooltime.MonitorAccessibilityService")
        accessibilityDump = $dump
    }
}

function Invoke-ExternalObserver([string]$ApiBaseUrl, [string]$RunId, [string]$InstrumentationComponent, [string]$TestClass, [string]$ResultLogTag, [int]$TimeoutMinutes, [string]$Ticket04CaseMode = "both") {
    $before = Get-DeviceMonitorState
    $beforePath = Join-Path $artifactDirectory "product-service-before-observer.txt"
    $before.accessibilityDump | Set-Content -LiteralPath $beforePath -Encoding utf8
    if (-not $before.pid -or -not $before.bound -or $before.crashed) {
        throw "Refusing to start ${Scenario}: the real debug AccessibilityService is not currently bound. PID='$($before.pid)', bound=$($before.bound), crashed=$($before.crashed). No registration or fixture API request was attempted. See $beforePath"
    }

    $stdoutPath = Join-Path $artifactDirectory "observer-instrumentation.stdout.txt"
    $stderrPath = Join-Path $artifactDirectory "observer-instrumentation.stderr.txt"
    $nativeArgumentsPath = Join-Path $artifactDirectory "observer-native-arguments.json"
    $nativeStatusPath = Join-Path $artifactDirectory "observer-native-transport.json"
    $transportStatusPath = Join-Path $artifactDirectory "observer-transport-status.json"
    $samplePath = Join-Path $artifactDirectory "observer-lifecycle-samples.json"
    $nativeArguments = @("-s", $DeviceSerial, "shell", "am", "instrument", "-w", "-r", "-e", "apiBaseUrl", $ApiBaseUrl, "-e", "runId", $RunId)
    if ($Scenario -eq "ticket04") { $nativeArguments += @("-e", "caseMode", $Ticket04CaseMode) }
    $nativeArguments += @("-e", "class", $TestClass, $InstrumentationComponent)
    $nativeCommandConfig = [pscustomobject]@{
        schemaVersion = 1
        runId = $RunId
        executablePath = $adbPath
        arguments = $nativeArguments
        statusPath = $nativeStatusPath
    }
    $nativeCommandConfig | ConvertTo-Json -Depth 5 -Compress | Set-Content -LiteralPath $nativeArgumentsPath -Encoding utf8
    $nativeCommandLauncherPath = Join-Path $PSScriptRoot "Invoke-NativeCommand.ps1"
    $powershellPath = Join-Path $PSHOME "powershell.exe"
    $launcherArguments = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        ('"{0}"' -f $nativeCommandLauncherPath), "-ConfigPath", ('"{0}"' -f $nativeArgumentsPath)
    ) -join " "
    $process = Start-Process -FilePath $powershellPath -ArgumentList $launcherArguments -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $samples = [System.Collections.Generic.List[object]]::new()
    $ticket04ProgressLines = [System.Collections.Generic.List[string]]::new()
    $ticket04ProgressPath = Join-Path $artifactDirectory "ticket04-user-actions.log"
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes($TimeoutMinutes)
    $failure = $null
    $previousSamplePid = $before.pid
    while (-not $process.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline) {
        Start-Sleep -Seconds 2
        if ($process.HasExited) { break }
        $state = Get-DeviceMonitorState
        $sample = [pscustomobject]@{
            utc = [DateTimeOffset]::UtcNow.ToString("O")
            pid = $state.pid
            bound = $state.bound
            crashed = $state.crashed
        }
        [void]$samples.Add($sample)
        if ($Scenario -eq "ticket04") {
            $progressOutput = @(Invoke-SelectedAdb @("logcat", "-d", "-s", "Ticket04Progress:I", "*:S"))
            $matchingProgress = @($progressOutput | Where-Object { $_ -match [regex]::Escape($RunId) -and $_ -match "TICKET04_PROGRESS=" })
            if ($matchingProgress.Count -gt $ticket04ProgressLines.Count) {
                foreach ($line in $matchingProgress | Select-Object -Skip $ticket04ProgressLines.Count) {
                    [void]$ticket04ProgressLines.Add([string]$line)
                    $marker = [regex]::Match([string]$line, 'TICKET04_PROGRESS=(\{.*\})')
                    if ($marker.Success) {
                        try {
                            $event = $marker.Groups[1].Value | ConvertFrom-Json
                            switch ($event.event) {
                                "WAITING_FOR_USER_ACTION" { Write-Host "TICKET04 사람 단계 [$($event.caseId)]: $($event.details -replace '^START: ', '')" }
                                "ACTION_REQUIRED" { Write-Host "TICKET04 사람 단계 [$($event.caseId)]: $($event.details)" }
                                "ACTION_EXPIRED" { Write-Warning "TICKET04 준비 화면 만료 [$($event.caseId)]: $($event.details)" }
                                "BOUNDARY_CAPTURED" {
                                    $boundary = $event.details | ConvertFrom-Json
                                    Write-Host "TICKET04 경계 기록 [$($event.caseId)]: permission OFF=$($boundary.actualPermissionEnabled -eq $false), request=$($boundary.requestBoundaryClassification), client in-flight=$($boundary.requestStillInFlight), socket open=$($boundary.clientSocketOpenAtBoundary), PID=$($boundary.productPid), elapsedRealtimeMs=$($boundary.boundaryElapsedRealtimeMs)"
                                }
                                "BOUNDARY_INVALID" { Write-Warning "TICKET04 권한 경계 유효성 실패 [$($event.caseId)]: $($event.details)" }
                                "PERMISSION_WAIT_EXPIRED" { Write-Warning "TICKET04 수동 권한 변경 확인 시간 초과 [$($event.caseId)]: $($event.details)" }
                                default { Write-Host "TICKET04 진행 [$($event.caseId)]: $($event.event)" }
                            }
                        } catch {
                            if ([string]$line -match '"event"\s*:\s*"CASE_RESULT"') {
                                Write-Host "TICKET04 case progress payload was skipped; the full case is read from the validated chunked result."
                            } else {
                                Write-Host "TICKET04 progress event was skipped because its JSON record was incomplete."
                            }
                        }
                    }
                }
                $ticket04ProgressLines | Set-Content -LiteralPath $ticket04ProgressPath -Encoding utf8
            }
        }
        if ($Scenario -eq "ticket04") {
            $lifecycleChanged = -not (Test-Ticket04HostLifecycleSample `
                -CaseMode $Ticket04CaseMode `
                -InitialPid $before.pid `
                -ObservedPid $state.pid `
                -Crashed $state.crashed)
        } else {
            $lifecycleChanged = $state.crashed -or $state.pid -ne $before.pid -or -not $state.bound
        }
        if ($Scenario -eq "ticket04" -and $state.pid -ne $previousSamplePid) {
            Write-Host "TICKET04 product lifecycle transition observed: priorSamplePID='$previousSamplePid', currentPID='$($state.pid)', bound=$($state.bound); the permission-specific result gate will decide whether it is expected."
        }
        if ($lifecycleChanged) {
            $failure = "The observed product lifecycle changed during external instrumentation: beforePID='$($before.pid)', currentPID='$($state.pid)', bound=$($state.bound), crashed=$($state.crashed)."
            break
        }
        $previousSamplePid = $state.pid
    }
    $samples | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $samplePath -Encoding utf8

    if ($failure -or -not $process.HasExited) {
        if (-not $failure) { $failure = "External observer exceeded its $TimeoutMinutes-minute limit." }
        try { Invoke-SelectedAdb @("shell", "am", "force-stop", $observerTestApplicationId) | Out-Null } catch { }
        if (-not $process.HasExited) {
            try { $process.WaitForExit(10000) | Out-Null } catch { }
            $process.Refresh()
            if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        }
        throw "$failure The observer was stopped without altering the product app. PID/binding samples: $samplePath"
    }

    $process.WaitForExit()
    $process.Refresh()
    $launcherProcessExitCode = $process.ExitCode
    if ($Scenario -eq "ticket04") {
        $finalProgressOutput = @(Invoke-SelectedAdb @("logcat", "-d", "-s", "Ticket04Progress:I", "*:S"))
        $finalMatchingProgress = @($finalProgressOutput | Where-Object { $_ -match [regex]::Escape($RunId) -and $_ -match "TICKET04_PROGRESS=" })
        foreach ($line in $finalMatchingProgress) {
            if ($ticket04ProgressLines.Contains([string]$line)) { continue }
            [void]$ticket04ProgressLines.Add([string]$line)
            $marker = [regex]::Match([string]$line, 'TICKET04_PROGRESS=(\{.*\})')
            if (-not $marker.Success) { continue }
            try {
                $event = $marker.Groups[1].Value | ConvertFrom-Json
                switch ($event.event) {
                    "WAITING_FOR_USER_ACTION" { Write-Host "TICKET04 사람 단계 [$($event.caseId)]: $($event.details -replace '^START: ', '')" }
                    "ACTION_REQUIRED" { Write-Host "TICKET04 사람 단계 [$($event.caseId)]: $($event.details)" }
                    "ACTION_EXPIRED" { Write-Warning "TICKET04 준비 화면 만료 [$($event.caseId)]: $($event.details)" }
                    "BOUNDARY_INVALID" { Write-Warning "TICKET04 권한 경계 유효성 실패 [$($event.caseId)]: $($event.details)" }
                    "PERMISSION_WAIT_EXPIRED" { Write-Warning "TICKET04 수동 권한 변경 확인 시간 초과 [$($event.caseId)]: $($event.details)" }
                    "BOUNDARY_CAPTURED" {
                        $boundary = $event.details | ConvertFrom-Json
                        Write-Host "TICKET04 경계 기록 [$($event.caseId)]: permission OFF=$($boundary.actualPermissionEnabled -eq $false), request=$($boundary.requestBoundaryClassification), client in-flight=$($boundary.requestStillInFlight), socket open=$($boundary.clientSocketOpenAtBoundary), PID=$($boundary.productPid), elapsedRealtimeMs=$($boundary.boundaryElapsedRealtimeMs)"
                    }
                    default { Write-Host "TICKET04 진행 [$($event.caseId)]: $($event.event)" }
                }
            } catch {
                if ([string]$line -match '"event"\s*:\s*"CASE_RESULT"') {
                    Write-Host "TICKET04 case progress payload was skipped; the full case is read from the validated chunked result."
                } else {
                    Write-Host "TICKET04 progress event was skipped because its JSON record was incomplete."
                }
            }
        }
        $ticket04ProgressLines | Set-Content -LiteralPath $ticket04ProgressPath -Encoding utf8
    }
    $after = Get-DeviceMonitorState
    $after.accessibilityDump | Set-Content -LiteralPath (Join-Path $artifactDirectory "product-service-after-observer.txt") -Encoding utf8
    if (-not (Test-Path -LiteralPath $nativeStatusPath)) {
        throw "External observer launcher did not save the native adb transport status. See $stdoutPath and $stderrPath"
    }
    $nativeTransport = Get-Content -LiteralPath $nativeStatusPath -Raw | ConvertFrom-Json -ErrorAction Stop
    if ($nativeTransport.runId -cne $RunId) {
        throw "External observer native transport status belongs to another run. See $nativeStatusPath"
    }
    $observerAdbExitCode = if ($null -eq $nativeTransport.nativeExitCode) { $null } else { [int]$nativeTransport.nativeExitCode }
    $transportRecord = [pscustomobject]@{
        schemaVersion = 1
        runId = $RunId
        executablePath = [string]$nativeTransport.executablePath
        nativeExitCode = $observerAdbExitCode
        nativeExitCapturedBy = "PowerShell LASTEXITCODE captured immediately after invoking adb"
        nativeExitStartedAtUtc = [string]$nativeTransport.startedAtUtc
        nativeExitCompletedAtUtc = [string]$nativeTransport.completedAtUtc
        nativeExitLaunchError = $nativeTransport.launchError
        launcherProcessId = [int]$nativeTransport.launcherProcessId
        launcherProcessExitCode = if ($null -eq $launcherProcessExitCode) { $null } else { [int]$launcherProcessExitCode }
        launcherProcessExitCodeObserved = ($null -ne $launcherProcessExitCode)
        nativeArgumentsSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $nativeArgumentsPath).Hash.ToLowerInvariant()
    }
    $transportRecord | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $transportStatusPath -Encoding utf8
    if ($null -eq $observerAdbExitCode) {
        throw "External observer native adb transport exit code is unavailable and cannot be treated as zero. See $nativeStatusPath and $stdoutPath"
    }
    if ($observerAdbExitCode -ne 0) {
        throw "External observer native adb transport exited with code $observerAdbExitCode. See $nativeStatusPath and $stdoutPath"
    }
    if ($null -ne $launcherProcessExitCode -and [int]$launcherProcessExitCode -ne $observerAdbExitCode) {
        throw "External observer launcher exit code disagrees with native adb transport exit $observerAdbExitCode. See $transportStatusPath"
    }
    if ($Scenario -eq "ticket04") {
        $stdout = (& rg --no-heading -n -C 2 -e 'OK \(1 test\)' -e 'FAILURES!!!' -e 'INSTRUMENTATION_FAILED' -e 'INSTRUMENTATION_STATUS: stack=' -e 'at com\.example\.todoschooltime\.e2eobserver\.Ticket04PermissionRecoveryTest' $stdoutPath 2>$null) -join "`n"
        $stderr = (& rg --no-heading -i -e 'error|exception|failed' $stderrPath 2>$null) -join "`n"
    } elseif ($Scenario -eq "ticket05") {
        Import-Module (Join-Path $PSScriptRoot "../e2e-ticket05/InstrumentationOutcome.psm1") -Force
        $stdout = @(Get-Ticket05InstrumentationOutput -Lines @(Get-Content -LiteralPath $stdoutPath)) -join "`n"
        $stderr = (& rg --no-heading -i -e 'error|exception|failed' $stderrPath 2>$null) -join "`n"
    } else {
        $stdout = Get-Content -LiteralPath $stdoutPath -Raw
        $stderr = Get-Content -LiteralPath $stderrPath -Raw
    }
    if ($Scenario -eq "ticket04" -and $stdout -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[0-9]') {
        throw "Ticket 04 observer instrumentation failed. Filtered test evidence:`n$stdout`n$stderr"
    }
    if ($Scenario -eq "ticket05" -and $stdout -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[0-9]') {
        throw "Ticket 05 observer instrumentation failed. Filtered test evidence:`n$stdout`n$stderr"
    }
    if ($after.crashed -or -not $after.bound -or ($Scenario -ne "ticket04" -and $after.pid -ne $before.pid)) {
        throw "The product service lifecycle failed its post-observer checks: beforePID='$($before.pid)', afterPID='$($after.pid)', bound=$($after.bound), crashed=$($after.crashed)."
    }
    $observerResultLogLines = @(Invoke-SelectedAdb @("logcat", "-d", "-s", "${ResultLogTag}:I", "*:S"))
    $observerResultLog = $observerResultLogLines | Out-String
    if ($Scenario -eq "ticket03") {
        $observerResultLog | Set-Content -LiteralPath (Join-Path $artifactDirectory "observer-result-logcat.txt") -Encoding utf8
    }
    if ($Scenario -eq "ticket05") {
        $observerResultLog | Set-Content -LiteralPath (Join-Path $artifactDirectory "observer-result-logcat.txt") -Encoding utf8
    }
    $resultMarker = switch ($Scenario) {
        "ticket03" { "TICKET03_RESULT="; break }
        "ticket04" { "TICKET04_RESULT="; break }
        "ticket05" { "TICKET05_RESULT="; break }
        default { "TICKET02_RESULT=" }
    }
    if ($Scenario -eq "ticket03") {
        Import-Module (Join-Path $PSScriptRoot "../e2e-ticket03/ResultChunks.psm1") -Force
        $structuredJson = Join-Ticket03ResultChunks -LogLines $observerResultLogLines -RunId $RunId
        $structuredResult = "$resultMarker$structuredJson"
    }
    elseif ($Scenario -eq "ticket04") {
        Import-Module (Join-Path $PSScriptRoot "../e2e-ticket04/ResultChunks.psm1") -Force
        $structuredJson = Join-Ticket04ResultChunks -LogLines $observerResultLogLines -RunId $RunId
        $structuredResult = "$resultMarker$structuredJson"
    }
    elseif ($Scenario -eq "ticket05") {
        $structuredJson = Join-Ticket05ResultChunks -LogLines $observerResultLogLines -RunId $RunId
        Import-Module (Join-Path $PSScriptRoot "../e2e-ticket05/InstrumentationOutcome.psm1") -Force
        $transportOutcome = Assert-Ticket05InstrumentationOutcome `
            -TransportStatus $transportRecord `
            -LauncherProcessExitCode $launcherProcessExitCode `
            -FilteredOutput $stdout `
            -StructuredResultJson $structuredJson `
            -RunId $RunId
        $transportOutcome | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $artifactDirectory "observer-instrumentation-outcome.json") -Encoding utf8
        $script:ticket05StructuredResultJson = $structuredJson
        $structuredResult = "$resultMarker$structuredJson"
    }
    else {
        $structuredResult = $observerResultLogLines |
            Where-Object { $_ -match [regex]::Escape($resultMarker) -and $_ -match [regex]::Escape($RunId) } |
            Select-Object -Last 1
    }
    if ($structuredResult) {
        $structuredResult | Set-Content -LiteralPath (Join-Path $artifactDirectory "observer-structured-result.log") -Encoding utf8
    }
    if ($Scenario -eq "ticket05") {
        return (($stdout + "`n" + $stderr + "`nTicket 05 structured result chunks decoded and saved.").Trim())
    }
    return (($stdout + "`n" + $stderr + "`n" + $structuredResult).Trim())
}

function Invoke-Gradle([string[]]$Tasks, [string]$LogName) {
    $gradlePath = Join-Path $repositoryRoot "gradlew.bat"
    $arguments = @(
        "--no-daemon",
        "--console=plain",
        "--max-workers=1",
        "--system-prop", "org.gradle.java.home=$javaHome",
        "--project-prop", "kotlin.compiler.execution.strategy=in-process",
        "-PlearningApiBaseUrl=$apiBaseUrl"
    ) + $Tasks
    $logPath = Join-Path $artifactDirectory $LogName
    Write-Host "Running: .\gradlew.bat $($arguments -join ' ')"
    & $gradlePath @arguments 2>&1 | Tee-Object -FilePath $logPath
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Gradle failed with exit code $exitCode. See $logPath"
    }
}

function Read-ServerRequests {
    try {
        $statePath = switch ($Scenario) {
            "ticket02" { "/__ticket02/state"; break }
            "ticket03" { "/__ticket03/state"; break }
            "ticket04" { "/__ticket04/state"; break }
            "ticket05" { "/__ticket05/state"; break }
            default { "/__ticket01/requests" }
        }
        return Invoke-RestMethod -Uri "$apiBaseUrl$statePath" -Method Get -TimeoutSec 3
    } catch {
        Write-Warning "Could not read the local server request log: $($_.Exception.Message)"
        return $null
    }
}

function Assert-Ticket03ForegroundPolling($Snapshot, [string]$InstrumentationText) {
    if ($null -eq $Snapshot) { throw "The temporary server request log is unavailable." }
    Import-Module (Join-Path $PSScriptRoot "../e2e-ticket03/ResultSchema.psm1") -Force
    Assert-Ticket03ScalarFields $Snapshot @{
        runId = "string"
        scenario = "string"
        sequence = "integer"
        reportCounts = "object"
    } "server snapshot"
    Assert-Ticket03JsonArrayFields $Snapshot @("requests", "inFlightRequests") "server snapshot"
    Assert-Ticket03ScalarFields $Snapshot.reportCounts @{
        hangeul = "integer"
        math = "integer"
        english = "integer"
    } "server snapshot.reportCounts"
    foreach ($serverRequest in @($Snapshot.requests)) {
        Assert-Ticket03ServerRequestScalars $serverRequest "server snapshot request"
    }
    foreach ($request in @($Snapshot.inFlightRequests)) {
        Assert-Ticket03ScalarFields $request @{ sequence = "integer"; path = "string" } "server snapshot in-flight request"
    }
    if ($Snapshot.runId -ne $runId -or $Snapshot.scenario -ne "ticket03") {
        throw "Connected to a server with the wrong Ticket 03 run identity."
    }
    if (@($Snapshot.inFlightRequests).Count -ne 0) {
        throw "Ticket 03 ended with fixture requests still in flight: $($Snapshot.inFlightRequests | ConvertTo-Json -Depth 5 -Compress)"
    }

    $resultMatch = [regex]::Match($InstrumentationText, 'TICKET03_RESULT=(\{[^\r\n]+\})')
    if (-not $resultMatch.Success) { throw "Observer instrumentation omitted structured Ticket 03 evidence." }
    $result = $resultMatch.Groups[1].Value | ConvertFrom-Json
    Assert-Ticket03ResultArrayShapes $result
    Assert-Ticket03ResultScalarShapes $result
    $expectedPaths = @(
        "/v3/todoschool/report/daily/hangeul",
        "/v3/todoschool/report/daily/math",
        "/v3/todoschool/report/daily/english",
        "/v3/todoschool/report/daily/hangeul",
        "/v3/todoschool/report/daily/math",
        "/v3/todoschool/report/daily/english"
    )
    $expectedCounts = @(2, 2, 2, 6, 2, 2)
    $expectedPackages = @(
        "com.enuma.todohangeul",
        "com.enuma.todomath",
        "com.enuma.todoenglish",
        "com.enuma.todohangeul",
        "com.enuma.todomath",
        "com.enuma.todoenglish"
    )
    $polls = @($result.pollRequests)
    $seoulTimeZone = [TimeZoneInfo]::FindSystemTimeZoneById("Korea Standard Time")
    $expectedSeoulDate = [int][TimeZoneInfo]::ConvertTime([DateTimeOffset]::Now, $seoulTimeZone).ToString("yyyyMMdd")
    if ($polls.Count -ne $expectedPaths.Count -or
        $result.runId -ne $runId -or
        $result.apiBaseUrl -ne $apiBaseUrl -or
        [int]$result.seoulDate -ne $expectedSeoulDate -or
        [int]$result.configuredIntervalMinutes -ne 1 -or
        -not $result.intervalMeasuredUsingRealElapsedTime -or
        -not $result.registeredChildOnly -or
        $result.registeredChild -ne "테스트 아이" -or
        [int]$result.deviceApi -ne $sdkVersion -or
        -not $result.serviceLeaseAttached -or
        -not $result.silentChannelPrepared -or
        $result.notificationChannelVibrationEnabled -or
        $result.productAlertDeliveryTested -or
        $result.actualTodoApiCalled -or
        [int]$result.noRegistrationNewReportCount -ne 0) {
        throw "Ticket 03 structured evidence does not meet the foreground-polling contract."
    }

    $requestBySequence = @{}
    foreach ($request in @($Snapshot.requests)) { $requestBySequence[[string]$request.sequence] = $request }
    $lastPeriodicCycleStartRequest = $null
    $reportUserIds = @{
        "/v3/todoschool/report/daily/hangeul" = "ticket01-hangeul"
        "/v3/todoschool/report/daily/math" = "ticket01-math"
        "/v3/todoschool/report/daily/english" = "ticket01-english"
    }
    for ($index = 0; $index -lt $polls.Count; $index++) {
        $poll = $polls[$index]
        $expectedPath = $expectedPaths[$index]
        if ($poll.requestPath -ne $expectedPath -or
            [int]$poll.servedCount -ne $expectedCounts[$index] -or
            $poll.packageName -ne $expectedPackages[$index] -or
            $poll.foregroundActiveWindowPackageAtWindowStart -ne $expectedPackages[$index] -or
            $poll.foregroundPackageAtReport -ne $expectedPackages[$index] -or
            [int64]$poll.foregroundEventSequence -gt [int64]$poll.foregroundEventSequenceAtWindowStart -or
            [int64]$poll.foregroundEventSequenceAtReport -lt [int64]$poll.foregroundEventSequence -or
            [int64]$poll.foregroundEventSequenceAtApply -lt [int64]$poll.foregroundEventSequenceAtReport -or
            [int64]$poll.requestSequenceBeforeTransition -gt [int64]$poll.requestSequenceCursorAtWindowStart -or
            [int64]$poll.requestSequenceCursorAtWindowStart -gt [int64]$poll.requestSequence -or
            [int64]$poll.requestSequenceCursorAtReport -ne [int64]$poll.requestSequence -or
            [int64]$poll.inFlightRequestsAtWindowStart -ne 0 -or
            -not $poll.serviceLeaseAttachedAtWindowStart -or
            [int64]$poll.foregroundSnapshotCapturedAtElapsedRealtimeMs -gt [int64]$poll.requestSnapshotCapturedAtElapsedRealtimeMs -or
            @($poll.foregroundPackagesObservedAtReport | Where-Object { $_ -ne $expectedPackages[$index] }).Count -gt 0 -or
            @($poll.foregroundPackagesObservedAtApply | Where-Object { $_ -ne $expectedPackages[$index] }).Count -gt 0 -or
            [int]$poll.reportRequestCountInCycle -ne 1 -or
            [int]$poll.inFlightRequestsAtEnd -ne 0) {
            throw "Ticket 03 poll record $index does not match its actual subject foreground window: $($poll | ConvertTo-Json -Compress -Depth 5)"
        }
        $transitionEvents = @($poll.foregroundEventsDuringTransition)
        $targetTransition = @($transitionEvents | Where-Object { [int64]$_.sequence -eq [int64]$poll.foregroundEventSequence })
        $transitionAfterTarget = @($transitionEvents | Where-Object { [int64]$_.sequence -ge [int64]$poll.foregroundEventSequence })
        if ($transitionEvents.Count -eq 0 -or $targetTransition.Count -ne 1 -or
            $targetTransition[0].packageName -ne $expectedPackages[$index] -or
            @($transitionAfterTarget | Where-Object { $_.packageName -ne $expectedPackages[$index] }).Count -gt 0 -or
            @($poll.foregroundEventsObservedAtReport | Where-Object { $_.packageName -ne $expectedPackages[$index] }).Count -gt 0 -or
            @($poll.foregroundEventsObservedAtApply | Where-Object { $_.packageName -ne $expectedPackages[$index] }).Count -gt 0) {
            throw "Ticket 03 poll record $index lacks a stable actual foreground-event window: $($poll | ConvertTo-Json -Compress -Depth 5)"
        }
        $transitionPaths = @($poll.requestsDuringTransition)
        $transitionSequences = @($poll.requestsDuringTransitionSequences)
        $transitionReportPaths = @($transitionPaths | Where-Object { $_ -like "/v3/todoschool/report/daily/*" })
        if ($transitionPaths.Count -ne $transitionSequences.Count -or
            $transitionReportPaths.Count -gt 1 -or
            @($transitionReportPaths | Where-Object { $_ -ne $expectedPath }).Count -gt 0) {
            throw "Ticket 03 foreground transition included an unexpected or repeated subject report: $($poll | ConvertTo-Json -Compress -Depth 5)"
        }
        for ($transitionIndex = 0; $transitionIndex -lt $transitionPaths.Count; $transitionIndex++) {
            $transitionRequest = $requestBySequence[[string]$transitionSequences[$transitionIndex]]
            if (-not $transitionRequest -or $transitionRequest.path -ne $transitionPaths[$transitionIndex] -or
                [int64]$transitionSequences[$transitionIndex] -le [int64]$poll.requestSequenceBeforeTransition -or
                [int64]$transitionSequences[$transitionIndex] -gt [int64]$poll.requestSequenceCursorAtWindowStart) {
                throw "Ticket 03 transition request evidence did not match the window-start cursor: $($poll | ConvertTo-Json -Compress -Depth 5)"
            }
        }
        $cyclePaths = @($poll.requestCyclePaths)
        $cycleSequences = @($poll.requestCycleSequences)
        $cycleReportPaths = @($cyclePaths | Where-Object { $_ -like "/v3/todoschool/report/daily/*" })
        $expectedCyclePaths = @(
            "/v3/account/signin",
            "/v3/todohangeul/user",
            "/v3/math/user",
            "/v3/english/user",
            $expectedPath
        )
        if ($cyclePaths.Count -ne $cycleSequences.Count -or
            ($cyclePaths -join "|") -ne ($expectedCyclePaths -join "|") -or
            $cycleReportPaths.Count -ne 1 -or
            $cycleReportPaths[0] -ne $expectedPath) {
            throw "Ticket 03 poll cycle $index included another subject report or an unexpected request origin: $($poll | ConvertTo-Json -Compress -Depth 5)"
        }
        $cycleStartSequence = [int64]$cycleSequences[0]
        $cycleStartRequest = $requestBySequence[[string]$cycleStartSequence]
        if (-not $cycleStartRequest -or $cycleStartRequest.path -ne "/v3/account/signin") {
            throw "Ticket 03 poll cycle $index does not start with its server-recorded sign-in request."
        }
        if ($lastPeriodicCycleStartRequest) {
            Assert-Ticket03CycleStartInterval $lastPeriodicCycleStartRequest $cycleStartRequest "periodic cycle $index cadence"
        }
        $lastPeriodicCycleStartRequest = $cycleStartRequest
        Assert-Ticket03DispatchCycle $poll $expectedCyclePaths $expectedPath $expectedPackages[$index] ([int64]$poll.foregroundCallbackAtElapsedRealtimeMs) "poll cycle $index"
        for ($cycleIndex = 0; $cycleIndex -lt $cyclePaths.Count; $cycleIndex++) {
            $cycleRequest = $requestBySequence[[string]$cycleSequences[$cycleIndex]]
            if (-not $cycleRequest -or $cycleRequest.path -ne $cyclePaths[$cycleIndex]) {
                throw "Ticket 03 poll cycle request sequence $($cycleSequences[$cycleIndex]) does not match the recorded server window."
            }
        }
        $request = $requestBySequence[[string]$poll.requestSequence]
        if (-not $request -or
            $request.path -ne $expectedPath -or
            $request.method -ne "POST" -or
            [int]$request.responseStatus -ne 200 -or
            [int]$request.servedReportCount -ne $expectedCounts[$index] -or
            $request.body.userId -ne $reportUserIds[$expectedPath] -or
            $request.headers.authorization -ne "Bearer ticket01-fake-token") {
            throw "Ticket 03 periodic request $($poll.requestSequence) did not match the expected fake child/subject response."
        }
        if ([int]$request.body.yyyymmdd -ne $expectedSeoulDate -or $request.body.languageCode -ne "ko") {
            throw "Ticket 03 request $($request.sequence) used a non-Seoul date or language."
        }
    }

    $expectedBaselinePaths = @(
        "/v3/account/signin",
        "/v3/todohangeul/user",
        "/v3/math/user",
        "/v3/english/user",
        "/v3/todoschool/report/daily/hangeul",
        "/v3/todoschool/report/daily/math",
        "/v3/todoschool/report/daily/english"
    )
    $baseline = @($result.baselineRequests)
    $baselinePaths = @($baseline | ForEach-Object { [string]$_.path })
    if ($baseline.Count -ne $expectedBaselinePaths.Count -or ($baselinePaths -join "|") -ne ($expectedBaselinePaths -join "|")) {
        throw "Ticket 03 did not finish the complete initial sign-in, catalog, and report baseline before the periodic observation window."
    }
    $observationStartSequence = [int64]$result.observationStartSequence
    $serverBaseline = @($Snapshot.requests | Where-Object { [int64]$_.sequence -le $observationStartSequence })
    if ($observationStartSequence -ne $baseline.Count -or $serverBaseline.Count -ne $baseline.Count -or
        (@($serverBaseline | ForEach-Object { [string]$_.path }) -join "|") -ne ($expectedBaselinePaths -join "|")) {
        throw "Ticket 03 observation start did not follow the exact server-recorded initial baseline."
    }
    foreach ($entry in $baseline) {
        if ([int]$entry.responseStatus -ne 200) { throw "Ticket 03 baseline request failed: $($entry | ConvertTo-Json -Compress -Depth 5)" }
        if ($entry.path -like "/v3/todoschool/report/daily/*" -and
            ([int]$entry.servedReportCount -ne 2 -or $entry.body.userId -notin @("ticket01-hangeul", "ticket01-math", "ticket01-english"))) {
            throw "Ticket 03 initial baseline did not leave every subscribed subject below its configured target."
        }
    }
    $firstBaselineRequest = $serverBaseline | Sort-Object { [int64]$_.sequence } | Select-Object -First 1
    $firstPeriodicCycleFirstSequence = [int64]$polls[0].requestCycleSequences[0]
    $firstPeriodicCycleStart = $requestBySequence[[string]$firstPeriodicCycleFirstSequence]
    if (-not $firstBaselineRequest -or
        $firstBaselineRequest.path -ne "/v3/account/signin" -or
        -not $firstPeriodicCycleStart -or
        $firstPeriodicCycleStart.path -ne "/v3/account/signin") {
        throw "Ticket 03 could not identify the baseline and first periodic cycle start from fixture HTTP records."
    }
    Assert-Ticket03CycleStartInterval $firstBaselineRequest $firstPeriodicCycleStart "baseline-to-first-periodic cycle"

    foreach ($transition in @("afterCompletion", "home", "other_app", "no_registered_children")) {
        $window = @($result.observationWindows | Where-Object { $_.transition -eq $transition })
        if ($window.Count -gt 0) {
            foreach ($entry in $window) {
                $transitionEvents = @($entry.foregroundEventsDuringTransition)
                $targetTransition = @($transitionEvents | Where-Object { [int64]$_.sequence -eq [int64]$entry.expectedForegroundEventSequence })
                $stableTransitionEvents = @($transitionEvents | Where-Object { [int64]$_.sequence -le [int64]$entry.foregroundEventSequenceAtWindowStart })
                $latestStableTransitionEvent = @($stableTransitionEvents | Sort-Object { [int64]$_.sequence } | Select-Object -Last 1)
                if ([int64]$entry.durationMs -lt 65000 -or [int]$entry.sampleCount -lt 60 -or
                    [int64]$entry.serverSequenceBefore -ne [int64]$entry.serverSequenceAfter -or
                    [int64]$entry.inFlightRequestsBefore -ne 0 -or
                    [int64]$entry.inFlightRequestsAfter -ne 0 -or
                    [int64]$entry.requestSequenceCursorAtWindowStart -ne [int64]$entry.serverSequenceBefore -or
                    [int64]$entry.requestDispatchCursorAtWindowStart -ne [int64]$entry.requestDispatchCursorAtWindowEnd -or
                    [int64]$entry.inFlightRequestsAtWindowStart -ne 0 -or
                    -not $entry.serviceLeaseAttachedAtWindowStart -or
                    $entry.activeWindowPackageAtWindowStart -ne $entry.foregroundPackage -or
                    [int64]$entry.foregroundSnapshotCapturedAtElapsedRealtimeMs -gt [int64]$entry.requestSnapshotCapturedAtElapsedRealtimeMs -or
                    @($entry.requestsDuringTransition).Count -ne 0 -or
                    @($entry.requestsDuringTransitionSequences).Count -ne 0 -or
                    $transitionEvents.Count -eq 0 -or $targetTransition.Count -ne 1 -or
                    $targetTransition[0].packageName -ne $entry.foregroundPackage -or
                    $stableTransitionEvents.Count -eq 0 -or
                    $latestStableTransitionEvent.Count -ne 1 -or
                    [int64]$latestStableTransitionEvent[0].sequence -ne [int64]$entry.foregroundEventSequenceAtWindowStart -or
                    $latestStableTransitionEvent[0].packageName -ne $entry.foregroundPackage -or
                    @($transitionEvents | Where-Object { [int64]$_.sequence -gt [int64]$entry.foregroundEventSequenceAtWindowStart }).Count -gt 0 -or
                    [int64]$entry.foregroundEventSequenceAtWindowEnd -lt [int64]$entry.foregroundEventSequenceAtWindowStart -or
                    [int64]$entry.expectedForegroundEventSequence -gt [int64]$entry.foregroundEventSequenceAtWindowStart -or
                    @($entry.foregroundEventsObservedDuringWindow | Where-Object { $_.packageName -ne $entry.foregroundPackage }).Count -gt 0 -or
                    @($entry.foregroundPackagesObservedDuringWindow | Where-Object { $_ -ne $entry.foregroundPackage }).Count -gt 0 -or
                    -not $entry.serviceLeaseAttachedAtEnd) {
                    throw "Ticket 03 observed a new or in-flight request during ${transition}: $($entry | ConvertTo-Json -Compress -Depth 5)"
                }
            }
        }
    }
    foreach ($transition in @("afterCompletion", "home", "other_app", "no_registered_children")) {
        if (@($result.observationWindows | Where-Object { $_.transition -eq $transition }).Count -ne 1) {
            throw "Ticket 03 omitted its $transition no-request observation window."
        }
    }

    $requestPath = Join-Path $artifactDirectory "server-requests.json"
    $Snapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $requestPath -Encoding utf8
    $resultPath = Join-Path $artifactDirectory "ticket03-result.json"
    $result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $resultPath -Encoding utf8
    Write-Host "PASS Ticket 03: six one-minute reports followed the real foreground subject; completion, Home, Settings, and zero-registration windows had no periodic requests."
    Write-Host "Request evidence: $requestPath"
    Write-Host "Android evidence: $resultPath"
}

function Get-Ticket04ServerEvidence($Snapshot) {
    $requests = @($Snapshot.requests | ForEach-Object {
        [ordered]@{
            sequence = $_.sequence
            path = $_.path
            holdCaseId = $_.holdCaseId
            responseStatus = $_.responseStatus
            servedReportCount = $_.servedReportCount
            deliveryStatus = $_.deliveryStatus
            clientDisconnectedAt = $_.clientDisconnectedAt
            clientDisconnectSignal = $_.clientDisconnectSignal
            clientDisconnectAfterServerReceiveMs = $_.clientDisconnectAfterServerReceiveMs
            serverReceivedMonotonicNs = $_.serverReceivedMonotonicNs
            clientDisconnectMonotonicNs = $_.clientDisconnectMonotonicNs
            yyyymmdd = $_.body.yyyymmdd
            userId = $_.body.userId
            languageCode = $_.body.languageCode
        }
    })
    return [ordered]@{
        runId = $Snapshot.runId
        scenario = $Snapshot.scenario
        serverMonotonicNs = $Snapshot.serverMonotonicNs
        reportCounts = $Snapshot.reportCounts
        requests = $requests
        releasedReportHolds = @($Snapshot.releasedReportHolds | ForEach-Object {
            [ordered]@{
                sequence = $_.sequence
                caseId = $_.caseId
                path = $_.path
                responseStatus = $_.responseStatus
                servedReportCount = $_.servedReportCount
                deliveryStatus = $_.deliveryStatus
                clientConnected = $_.clientConnected
                clientDisconnectedAt = $_.clientDisconnectedAt
                clientDisconnectSignal = $_.clientDisconnectSignal
                clientDisconnectAfterServerReceiveMs = $_.clientDisconnectAfterServerReceiveMs
                serverReceivedMonotonicNs = $_.serverReceivedMonotonicNs
                clientDisconnectMonotonicNs = $_.clientDisconnectMonotonicNs
                userId = $_.userId
                yyyymmdd = $_.yyyymmdd
                languageCode = $_.languageCode
            }
        })
        inFlightRequestCount = @($Snapshot.inFlightRequests).Count
        serverHeldResponseCount = @($Snapshot.serverHeldResponses).Count
    }
}

function Get-Ticket04SourceSnapshot {
    $sourceRelativePaths = @(
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket04PermissionRecoveryTest.kt",
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket04AccessibilitySettingsNavigator.kt",
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket04AccessibilitySettingsNavigationTest.kt",
        "e2eobserver/src/main/java/com/example/todoschooltime/e2eobserver/Ticket04ControlActivity.kt",
        "e2eobserver/src/main/java/com/example/todoschooltime/e2eobserver/Ticket04ActionToken.kt",
        "e2eobserver/src/main/java/com/example/todoschooltime/e2eobserver/Ticket04BoundaryEvidence.kt",
        "e2eobserver/src/test/java/com/example/todoschooltime/e2eobserver/Ticket04BoundaryEvidenceTest.kt",
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket04ControlActivityTest.kt",
        "tools/e2e-ticket01/Run-Ticket01.ps1",
        "tools/e2e-ticket01/server.py",
        "tools/e2e-ticket01/test_server.py",
        "tools/e2e-ticket04/PermissionRecoveryEvidence.psm1",
        "tools/e2e-ticket04/Test-ResultChunks.ps1"
    )
    $sourcePaths = @($sourceRelativePaths | ForEach-Object { Join-Path $repositoryRoot $_ })
    foreach ($sourcePath in $sourcePaths) {
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "A Ticket 04 evidence source file is missing: $sourcePath"
        }
    }
    $sourceHashMaterial = ($sourceRelativePaths | ForEach-Object {
        $absolutePath = Join-Path $repositoryRoot $_
        "$_=$((Get-FileHash -LiteralPath $absolutePath -Algorithm SHA256).Hash.ToLowerInvariant())"
    }) -join "`n"
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $sourceContentSha256 = [BitConverter]::ToString($sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($sourceHashMaterial))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
    $revisionOutput = & git -C $repositoryRoot rev-parse HEAD 2>$null
    $revisionExitCode = $LASTEXITCODE
    $sourceRevision = (@($revisionOutput) | Select-Object -First 1).Trim()
    if ($revisionExitCode -ne 0 -or $sourceRevision -notmatch '^[0-9a-f]{7,40}$') {
        throw "Could not record the source revision for Ticket 04 case evidence."
    }
    $dirtyPaths = @(& git -C $repositoryRoot status --porcelain -- $sourceRelativePaths 2>$null)
    return [pscustomobject]@{
        revision = $sourceRevision
        contentSha256 = $sourceContentSha256
        workingTreeDirty = ($dirtyPaths.Count -gt 0)
    }
}

function Save-Ticket04CaseEvidenceFromResult($CaseResult, $FixtureEvidence) {
    if ($CaseResult.runId -cne $runId -or -not (Test-Ticket04CaseResult -Case $CaseResult)) {
        throw "Ticket 04 chunked result case failed its run identity or case-level checks: caseId='$($CaseResult.caseId)'"
    }

    $caseHolds = @($fixtureEvidence.releasedReportHolds | Where-Object { $_.caseId -ceq $caseResult.caseId })
    if ($caseHolds.Count -ne 1 -or
        [int]$caseHolds[0].responseStatus -ne [int]$caseResult.heldResponseStatus -or
        [string]$caseHolds[0].deliveryStatus -cne [string]$caseResult.serverResponseWrite -or
        [int64]$caseHolds[0].sequence -ne [int64]$caseResult.serverRequestSequence -or
        [int]$fixtureEvidence.inFlightRequestCount -ne 0 -or
        [int]$fixtureEvidence.serverHeldResponseCount -ne 0) {
        throw "Ticket 04 chunked result case does not match a completed fixture release: caseId='$($CaseResult.caseId)'"
    }

    $casePath = Join-Path $artifactDirectory "ticket04-case-evidence-$($CaseResult.caseId).json"
    if (Test-Path -LiteralPath $casePath) {
        throw "Ticket 04 case evidence is immutable and already exists for this run: $casePath"
    }
    $sourceSnapshot = Get-Ticket04SourceSnapshot
    $evidence = [ordered]@{
        schemaVersion = if ($CaseResult.caseId -eq "accessibility-revocation") { 3 } else { 1 }
        contractVersion = 1
        caseId = [string]$CaseResult.caseId
        runId = [string]$CaseResult.runId
        sourceRevision = $sourceSnapshot.revision
        sourceContentSha256 = $sourceSnapshot.contentSha256
        workingTreeDirty = $sourceSnapshot.workingTreeDirty
        device = [ordered]@{
            serial = $DeviceSerial
            model = $model
            apiLevel = [int]$sdkVersion
        }
        applicationId = $debugApplicationId
        productApkSha256 = [string]$prepared.debugApkSha256
        observerApkSha256 = [string]$prepared.observerApkSha256
        observerTestApkSha256 = [string]$prepared.observerTestApkSha256
        fixture = $fixtureEvidence
        caseResult = $CaseResult
        validation = [ordered]@{
            status = "passed"
            checks = @("case-specific boundary and recovery result", "matching explicitly released fixture response", "no in-flight or held response at case completion")
        }
    }
    if (-not (Test-Ticket04CaseEvidence -Evidence ([pscustomobject]$evidence) -ExpectedDeviceSerial $DeviceSerial -ExpectedProductApkSha256 $prepared.debugApkSha256)) {
        throw "Ticket 04 case evidence failed its immutable artifact schema check: caseId='$($CaseResult.caseId)'"
    }

    $temporaryPath = "$casePath.tmp"
    $evidence | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $temporaryPath -Encoding utf8
    Move-Item -LiteralPath $temporaryPath -Destination $casePath
    Write-Host "Ticket 04 완료 case JSON saved from the validated chunked result: $casePath"
}

function Assert-Ticket04PermissionRecovery($Snapshot, [string]$InstrumentationText, [string]$CaseMode, $PriorNotificationEvidence) {
    if ($null -eq $Snapshot) { throw "The Ticket 04 loopback fixture state is unavailable." }
    if ($Snapshot.runId -ne $runId -or $Snapshot.scenario -ne "ticket04") {
        throw "Connected to a server with the wrong Ticket 04 run identity."
    }
    if (@($Snapshot.inFlightRequests).Count -ne 0 -or @($Snapshot.serverHeldResponses).Count -ne 0) {
        throw "Ticket 04 ended with an HTTP client in flight or a fixture response still held."
    }

    $resultMatch = [regex]::Match($InstrumentationText, 'TICKET04_RESULT=(\{[^\r\n]+\})')
    if (-not $resultMatch.Success) { throw "Ticket 04 observer instrumentation omitted its structured evidence." }
    $result = $resultMatch.Groups[1].Value | ConvertFrom-Json
    if ($result.runId -ne $runId -or $result.caseMode -cne $CaseMode -or $result.apiBaseUrl -ne $apiBaseUrl -or
        [int]$result.deviceApi -ne $sdkVersion -or
        -not $result.registrationPreserved -or -not $result.silentChannelPrepared -or $result.actualTodoApiCalled -or
        $result.productAlertDeliveryTested -or
        $result.callbackDeliveryObservation -notmatch 'unobserved') {
        throw "Ticket 04 top-level observer evidence did not meet its identity or preservation requirements."
    }

    $expectedCaseIds = switch ($CaseMode) {
        "notification" { @("notification-revocation"); break }
        "accessibility" { @("accessibility-revocation"); break }
        "both" { @("notification-revocation", "accessibility-revocation"); break }
        default { throw "Unsupported Ticket 04 case mode: $CaseMode" }
    }
    $caseResults = @(Get-Ticket04CaseResultsFromStructuredResult `
        -Result $result `
        -ExpectedRunId $runId `
        -ExpectedCaseIds $expectedCaseIds)
    $expectsNotification = "notification-revocation" -in $expectedCaseIds
    $expectsAccessibility = "accessibility-revocation" -in $expectedCaseIds
    if ([bool]$result.notificationProcessRestartObserved -ne $expectsNotification -or
        [bool]$result.accessibilitySamePidBoundaryObserved -ne $expectsAccessibility) {
        throw "Ticket 04 top-level case gates do not match the selected case results."
    }
    $caseById = @{}
    foreach ($case in $caseResults) {
        $caseId = [string]$case.caseId
        $caseById[$caseId] = $case
        $heldRequest = @($Snapshot.releasedReportHolds | Where-Object { $_.caseId -eq $caseId })
        if ($heldRequest.Count -ne 1 -or
            $heldRequest[0].responseStatus -ne $case.heldResponseStatus -or
            $heldRequest[0].deliveryStatus -ne $case.serverResponseWrite -or
            $heldRequest[0].sequence -ne $case.serverRequestSequence) {
            throw "Fixture evidence did not match the explicitly released $caseId response."
        }
    }

    $fixtureEvidence = Get-Ticket04ServerEvidence $Snapshot
    $caseArtifacts = @()
    foreach ($caseId in $expectedCaseIds) {
        if (-not $caseById.ContainsKey($caseId)) { throw "Ticket 04 omitted required case: $caseId" }
        $caseArtifactPath = Join-Path $artifactDirectory "ticket04-case-evidence-$caseId.json"
        if (-not (Test-Path -LiteralPath $caseArtifactPath)) {
            Save-Ticket04CaseEvidenceFromResult $caseById[$caseId] $fixtureEvidence
        }
        $caseArtifact = Get-Content -LiteralPath $caseArtifactPath -Raw | ConvertFrom-Json -ErrorAction Stop
        if (-not (Test-Ticket04CaseEvidence -Evidence $caseArtifact -ExpectedDeviceSerial $DeviceSerial -ExpectedProductApkSha256 $prepared.debugApkSha256) -or
            $caseArtifact.runId -cne $runId -or $caseArtifact.caseId -cne $caseId -or
            ($caseArtifact.caseResult | ConvertTo-Json -Compress -Depth 15) -cne ($caseById[$caseId] | ConvertTo-Json -Compress -Depth 15)) {
            throw "Ticket 04 case evidence artifact failed strict validation: $caseArtifactPath"
        }
        $caseArtifacts += $caseArtifact
    }

    $serverEvidencePath = Join-Path $artifactDirectory "ticket04-server-evidence.json"
    (Get-Ticket04ServerEvidence $Snapshot) | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $serverEvidencePath -Encoding utf8
    if ($CaseMode -eq "notification") {
        $casePath = Join-Path $artifactDirectory "ticket04-case-evidence-notification-revocation.json"
        $notificationCase = $caseById["notification-revocation"]
        Write-Host "PASS Ticket 04 notification case: OFF caused the observed process restart, with registration preserved and silent recovery verified. Accessibility remains untested; this result is not a two-case PASS."
        Write-Host "Notification request at OFF: $($notificationCase.requestBoundaryClassification), age=$($notificationCase.requestAgeAtBoundaryMs)ms; in-flight cancellation was not established and is not an acceptance gate for this case."
        Write-Host "Immutable notification evidence: $casePath"
        Write-Host "Fixture evidence (selected fields): $serverEvidencePath"
        return
    }

    if ($CaseMode -eq "accessibility") {
        if (-not (Test-Ticket04CaseEvidence -Evidence $PriorNotificationEvidence -ExpectedDeviceSerial $DeviceSerial -ExpectedProductApkSha256 $prepared.debugApkSha256)) {
            throw "The prior notification case artifact failed strict schema, fixture, device, source and product-binary validation."
        }
        $caseArtifacts = @($PriorNotificationEvidence) + @($caseArtifacts)
    }
    $sourceCompatibilityProof = $null
    if ($CaseMode -eq "accessibility") {
        $sourceCompatibilityProof = Get-Content -LiteralPath (Join-Path $artifactDirectory "ticket04-source-compatibility.json") -Raw | ConvertFrom-Json -ErrorAction Stop
    }
    $aggregateCases = @(Join-Ticket04CaseEvidence `
        -Evidence $caseArtifacts `
        -ExpectedDeviceSerial $DeviceSerial `
        -ExpectedProductApkSha256 $prepared.debugApkSha256 `
        -SourceCompatibilityProof $sourceCompatibilityProof `
        -RepositoryRoot $repositoryRoot `
        -PythonPath $pythonPath)
    $aggregate = [ordered]@{
        schemaVersion = 1
        contractVersion = 1
        status = "passed"
        completedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
        applicationId = $debugApplicationId
        productApkSha256 = [string]$prepared.debugApkSha256
        device = $aggregateCases[0].device
        caseIds = @($aggregateCases | ForEach-Object { $_.caseId })
        cases = $aggregateCases
        sourceCompatibilityProof = $sourceCompatibilityProof
    }
    $resultPath = Join-Path $artifactDirectory "ticket04-result.json"
    $aggregate | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $resultPath -Encoding utf8
    Write-Host "PASS Ticket 04: the notification and Accessibility cases each passed their own immutable evidence gates on the same product APK, device, source contract and fixture schema."
    Write-Host "Fixture evidence (selected fields): $serverEvidencePath"
    Write-Host "Strict two-case aggregate evidence: $resultPath"
}

function Assert-ServerRoundTrip($Snapshot) {
    if ($null -eq $Snapshot) { throw "The temporary server request log is unavailable." }
    if ($Snapshot.runId -ne $runId) { throw "Connected to a server owned by another run." }
    $requests = @($Snapshot.requests)
    $expectedPaths = @(
        "/v3/account/signin",
        "/v3/todohangeul/user",
        "/v3/math/user",
        "/v3/english/user",
        "/v3/todoschool/report/daily/hangeul",
        "/v3/todoschool/report/daily/math",
        "/v3/todoschool/report/daily/english"
    )
    $actualPaths = @($requests | ForEach-Object { $_.path })
    if (($actualPaths -join "`n") -ne ($expectedPaths -join "`n")) {
        throw "Unexpected HTTP request order. Expected $($expectedPaths -join ', '); received $($actualPaths -join ', ')."
    }

    foreach ($request in $requests) {
        if ($request.method -ne "POST" -or $request.responseStatus -ne 200) {
            throw "HTTP request $($request.sequence) did not complete successfully."
        }
        if ($request.headers.'content-type' -notmatch '^application/json') {
            throw "HTTP request $($request.sequence) omitted application/json content type."
        }
    }

    $login = $requests[0]
    if ($login.body.email -ne "ticket01@example.test" -or
        $login.body.password -ne "not-a-real-password" -or
        $login.headers.authorization) {
        throw "The sign-in request did not use the fake test credentials or unexpectedly sent an auth header."
    }
    foreach ($request in $requests | Select-Object -Skip 1) {
        if ($request.headers.authorization -ne "Bearer ticket01-fake-token") {
            throw "HTTP request $($request.sequence) did not carry the fake bearer token."
        }
    }

    $seoulTimeZone = [TimeZoneInfo]::FindSystemTimeZoneById("Korea Standard Time")
    $expectedDate = [int][TimeZoneInfo]::ConvertTime([DateTimeOffset]::Now, $seoulTimeZone).ToString("yyyyMMdd")
    foreach ($request in $requests | Select-Object -Skip 4) {
        if ([int]$request.body.yyyymmdd -ne $expectedDate) {
            throw "Report request $($request.sequence) used $($request.body.yyyymmdd), expected Seoul date $expectedDate."
        }
    }

    if ($requests[1].body.user.accountId -ne "ticket01-fake-account") {
        throw "Catalog request did not use the fake account id."
    }
    if ($requests[4].body.userId -ne "ticket01-hangeul" -or
        $requests[5].body.userId -ne "ticket01-math" -or
        $requests[6].body.userId -ne "ticket01-english") {
        throw "Report requests did not use the server-provided child subscription IDs."
    }

    $snapshotPath = Join-Path $artifactDirectory "server-requests.json"
    $Snapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $snapshotPath -Encoding utf8
    Write-Host "PASS server observed login, catalog and all three report requests for Seoul date $expectedDate."
    Write-Host "Request evidence: $snapshotPath"
}

function Assert-Ticket02BaselineCompletion($Snapshot, [string]$InstrumentationText) {
    if ($null -eq $Snapshot) { throw "The temporary server state is unavailable." }
    if ($Snapshot.runId -ne $runId -or $Snapshot.scenario -ne "ticket02") {
        throw "Connected to a server with the wrong Ticket 02 run identity."
    }

    $requests = @($Snapshot.requests)
    $expectedPaths = @(
        "/v3/account/signin",
        "/v3/todohangeul/user",
        "/v3/math/user",
        "/v3/english/user",
        "/v3/todoschool/report/daily/hangeul",
        "/v3/todoschool/report/daily/math",
        "/v3/todoschool/report/daily/english",
        "/v3/account/signin",
        "/v3/todohangeul/user",
        "/v3/math/user",
        "/v3/english/user",
        "/v3/todoschool/report/daily/hangeul"
    )
    $actualPaths = @($requests | ForEach-Object { $_.path })
    if (($actualPaths -join "`n") -ne ($expectedPaths -join "`n")) {
        throw "Ticket 02 expected a full baseline followed by one real monitor cycle (sign-in, three catalogs, and one hangeul poll). Received $($actualPaths -join ', ')."
    }

    foreach ($request in $requests) {
        if ($request.method -ne "POST" -or $request.responseStatus -ne 200) {
            throw "Ticket 02 API request $($request.sequence) did not complete successfully."
        }
        if ($request.headers.'content-type' -notmatch '^application/json') {
            throw "Ticket 02 API request $($request.sequence) omitted application/json content type."
        }
    }

    foreach ($request in $requests) {
        if ($request.path -eq "/v3/account/signin") {
            if ($request.body.email -ne "ticket01@example.test" -or
                $request.body.password -ne "not-a-real-password" -or
                $request.headers.authorization) {
                throw "Ticket 02 sign-in did not use the fixture credentials."
            }
        } elseif ($request.headers.authorization -ne "Bearer ticket01-fake-token") {
            throw "Ticket 02 request $($request.sequence) did not use the fixture bearer token."
        }
    }

    foreach ($request in $requests | Where-Object { $_.path -in @("/v3/todohangeul/user", "/v3/math/user", "/v3/english/user") }) {
        if ($request.body.user.accountId -ne "ticket01-fake-account") {
            throw "Ticket 02 catalog request $($request.sequence) did not use the isolated fixture account."
        }
    }
    $reportRequests = @($requests | Where-Object { $_.path -like "/v3/todoschool/report/daily/*" })
    $expectedDate = [int][TimeZoneInfo]::ConvertTime(
        [DateTimeOffset]::Now,
        [TimeZoneInfo]::FindSystemTimeZoneById("Korea Standard Time")
    ).ToString("yyyyMMdd")
    foreach ($request in $reportRequests) {
        if ([int]$request.body.yyyymmdd -ne $expectedDate -or $request.body.languageCode -ne "ko") {
            throw "Ticket 02 request $($request.sequence) did not use today's Seoul report date and language."
        }
    }
    if ($reportRequests.Count -ne 4 -or
        $reportRequests[0].body.userId -ne "ticket01-hangeul" -or $reportRequests[0].servedReportCount -ne 2 -or
        $reportRequests[1].body.userId -ne "ticket01-math" -or $reportRequests[1].servedReportCount -ne 5 -or
        $reportRequests[2].body.userId -ne "ticket01-english" -or $reportRequests[2].servedReportCount -ne 7 -or
        $reportRequests[3].body.userId -ne "ticket01-hangeul" -or $reportRequests[3].servedReportCount -ne 6) {
        throw "Ticket 02 did not preserve the completed/incomplete baseline and changed hangeul response."
    }

    $resultMatch = [regex]::Match($InstrumentationText, 'TICKET02_RESULT=(\{[^\r\n]+\})')
    if (-not $resultMatch.Success) { throw "Instrumentation omitted structured Ticket 02 evidence." }
    $result = $resultMatch.Groups[1].Value | ConvertFrom-Json
    if ($result.runId -ne $runId -or
        $result.baselineNotificationId -ne $result.completionNotificationId -or
        $result.baselineCompletionRequests -ne 0 -or
        $result.followUpCompletionRequests -ne 1 -or
        -not $result.accessibilityServiceLeaseAttached -or
        $result.foregroundPackageObserved -ne "com.enuma.todohangeul" -or
        -not $result.silentChannelPrepared -or
        $result.notificationChannelVibrationEnabled -or
        -not (
            [int]$result.notificationChannelImportance -in @(1, 2) -or
            ([int]$result.notificationChannelImportance -eq 3 -and -not $result.notificationChannelHasSoundUri)
        ) -or
        $result.postedNotificationCount -ne 1 -or
        $result.productAlertDeliveryTested) {
        throw "Ticket 02 structured evidence does not meet the silent baseline/update contract."
    }

    $requestPath = Join-Path $artifactDirectory "server-requests.json"
    $Snapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $requestPath -Encoding utf8
    $resultPath = Join-Path $artifactDirectory "ticket02-result.json"
    $result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $resultPath -Encoding utf8
    Write-Host "PASS Ticket 02: silent complete baseline, one real foreground hangeul poll, completion intent, and one updated child notification."
    Write-Host "Request evidence: $requestPath"
    Write-Host "Android evidence: $resultPath"
}

function Save-AppScreenshot {
    $destination = Join-Path $artifactDirectory "ticket01-server-screen.png"
    $logOutput = Invoke-SelectedAdb @("logcat", "-d", "-s", "Ticket01Screenshot:I")
    $logPath = Join-Path $artifactDirectory "screenshot-logcat.txt"
    $logOutput | Set-Content -LiteralPath $logPath -Encoding utf8

    $chunks = @{}
    $expectedChunkCount = 0
    foreach ($line in $logOutput) {
        if ($line -match "Ticket01Screenshot.*TICKET01_SCREENSHOT_CHUNK:${runId}:(?<index>\d+)/(?<count>\d+):(?<payload>[A-Za-z0-9+/=]+)") {
            $index = [int]$Matches.index
            $count = [int]$Matches.count
            if ($expectedChunkCount -ne 0 -and $expectedChunkCount -ne $count) {
                throw "Screenshot log chunks disagree about the expected chunk count. See $logPath"
            }
            $expectedChunkCount = $count
            $chunks[$index] = $Matches.payload
        }
    }
    if ($expectedChunkCount -lt 1 -or $chunks.Count -ne $expectedChunkCount) {
        throw "Could not reconstruct the actual screen screenshot from instrumentation output. See $logPath"
    }

    $encodedScreenshot = ((1..$expectedChunkCount | ForEach-Object { $chunks[$_] }) -join "")
    $screenshotBytes = [Convert]::FromBase64String($encodedScreenshot)
    if ($screenshotBytes.Length -lt 1000 -or
        $screenshotBytes[0] -ne 0x89 -or $screenshotBytes[1] -ne 0x50 -or
        $screenshotBytes[2] -ne 0x4e -or $screenshotBytes[3] -ne 0x47) {
        throw "The instrumentation screenshot is not a valid PNG. See $logPath"
    }
    [System.IO.File]::WriteAllBytes($destination, $screenshotBytes)
    Write-Host "Screen evidence: $destination"
}

$oldJavaHome = $env:JAVA_HOME
$oldGradleUserHome = $env:GRADLE_USER_HOME
$oldAndroidHome = $env:ANDROID_HOME
$oldAndroidSdkRoot = $env:ANDROID_SDK_ROOT
$oldAndroidUserHome = $env:ANDROID_USER_HOME
$oldAndroidSerial = $env:ANDROID_SERIAL
$serverProcess = $null
$reverseOwned = $false
$serverStarted = $false
$serverLog = Join-Path $artifactDirectory "server.stdout.log"
$serverErrorLog = Join-Path $artifactDirectory "server.stderr.log"

try {
    $env:JAVA_HOME = $javaHome
    $env:GRADLE_USER_HOME = $gradleUserHome
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $env:ANDROID_USER_HOME = $androidUserHome
    $env:ANDROID_SERIAL = $DeviceSerial

    $sdkVersion = [int](Invoke-SelectedAdb @("shell", "getprop", "ro.build.version.sdk") | Select-Object -First 1)
    $model = (Invoke-SelectedAdb @("shell", "getprop", "ro.product.model") | Select-Object -First 1).Trim()
    $regularPackageBefore = Get-PackagePath $releaseApplicationId
    $debugPackageBefore = Get-PackagePath $debugApplicationId
    Write-Host "Device: $DeviceSerial ($model), API $sdkVersion"
    Write-Host "Test package: $debugApplicationId; production package install path before test: $regularPackageBefore"
    if ($debugPackageBefore) { Write-Host "Existing isolated debug package is installed at: $debugPackageBefore" }

    if ($RunPrepared) {
        Write-Host "Prepared observer-run mode: reusing the installed APKs without rebuilding or reinstalling the product app."
        $gradleTasks = @()
    } elseif ($PrepareDevice) {
        $gradleTasks = @(
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":app:assembleRelease",
            ":app:lintDebug",
            ":app:lintRelease",
            ":e2eobserver:assembleDebug",
            ":e2eobserver:assembleDebugAndroidTest"
        )
        $gradleLogName = "observer-device-preparation-build.log"
    } elseif ($RefreshObserver) {
        $gradleTasks = @(
            ":e2eobserver:assembleDebug",
            ":e2eobserver:assembleDebugAndroidTest"
        )
        $gradleLogName = "observer-refresh-build.log"
    } elseif ($DeviceOnly) {
        $gradleTasks = @(
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":app:assembleRelease"
        )
        if ($usesExternalObserver) { $gradleTasks += @(":e2eobserver:assembleDebug", ":e2eobserver:assembleDebugAndroidTest") }
        $gradleLogName = "device-apk-build.log"
    } else {
        $gradleTasks = @(
            ":app:testDebugUnitTest",
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":app:assembleRelease",
            ":app:lintDebug",
            ":app:lintRelease"
        )
        if ($usesExternalObserver) { $gradleTasks += @(":e2eobserver:assembleDebug", ":e2eobserver:assembleDebugAndroidTest") }
        $gradleLogName = "build-and-unit-tests.log"
    }
    if ($gradleTasks.Count -gt 0) { Invoke-Gradle $gradleTasks $gradleLogName }

    $debugApk = Join-Path $repositoryRoot "app\build\outputs\apk\debug\app-debug.apk"
    $testApk = Join-Path $repositoryRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    $releaseApk = Join-Path $repositoryRoot "app\build\outputs\apk\release\app-release.apk"
    $observerApk = Join-Path $repositoryRoot "e2eobserver\build\outputs\apk\debug\e2eobserver-debug.apk"
    $observerTestApk = Join-Path $repositoryRoot "e2eobserver\build\outputs\apk\androidTest\debug\e2eobserver-debug-androidTest.apk"
    foreach ($apk in @($debugApk, $testApk, $releaseApk)) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "Expected APK was not built: $apk" }
    }
    if (($usesExternalObserver -or $PrepareDevice -or $RunPrepared -or $RefreshObserver) -and
        (-not (Test-Path -LiteralPath $observerApk) -or -not (Test-Path -LiteralPath $observerTestApk))) {
        throw "External observer APKs are missing; build them or run -PrepareDevice first."
    }

    $debugBadging = (& $aaptPath dump badging $debugApk 2>&1) -join "`n"
    $testBadging = (& $aaptPath dump badging $testApk 2>&1) -join "`n"
    $observerBadging = if (Test-Path -LiteralPath $observerApk) { (& $aaptPath dump badging $observerApk 2>&1) -join "`n" } else { "" }
    $observerTestBadging = if (Test-Path -LiteralPath $observerTestApk) { (& $aaptPath dump badging $observerTestApk 2>&1) -join "`n" } else { "" }
    $releaseBadging = (& $aaptPath dump badging $releaseApk 2>&1) -join "`n"
    if ($debugBadging -notmatch "package: name='$([regex]::Escape($debugApplicationId))'") {
        throw "Built debug APK has an unexpected application ID; refusing device installation."
    }
    if ($debugBadging -notmatch "application-label:'토도 학습시간 · 로컬 테스트'") {
        throw "Built debug APK does not have a clearly isolated local-test app label."
    }
    if ($releaseBadging -notmatch "package: name='$([regex]::Escape($releaseApplicationId))'") {
        throw "Built release APK has an unexpected application ID."
    }
    if ($testBadging -notmatch "package: name='$([regex]::Escape($testApplicationId))'") {
        throw "Built instrumentation APK has an unexpected application ID."
    }
    if ($usesExternalObserver -and
        ($observerBadging -notmatch "package: name='com.example.todoschooltime.e2eobserver'" -or
         $observerTestBadging -notmatch "package: name='com.example.todoschooltime.e2eobserver.test'")) {
        throw "External observer APK package identities are unexpected."
    }
    if ($usesExternalObserver) {
        $debugSigner = Get-ApkSignerFingerprint $debugApk
        $observerSigner = Get-ApkSignerFingerprint $observerApk
        $observerTestSigner = Get-ApkSignerFingerprint $observerTestApk
        if ($debugSigner -ne $observerSigner -or $debugSigner -ne $observerTestSigner) {
            throw "Product debug, observer and observer-test APKs must share a signing certificate for the read-only signature permission."
        }
        Write-Host "PASS product debug and observer APKs share signer SHA-256 $debugSigner."
    }
    $releaseManifest = (& $aaptPath dump xmltree $releaseApk AndroidManifest.xml 2>&1) -join "`n"
    $debugManifest = (& $aaptPath dump xmltree $debugApk AndroidManifest.xml 2>&1) -join "`n"
    $observerManifest = if ($usesExternalObserver) { (& $aaptPath dump xmltree $observerApk AndroidManifest.xml 2>&1) -join "`n" } else { "" }
    if ($releaseManifest -match "android:networkSecurityConfig") {
        throw "Release APK unexpectedly includes the debug network security configuration."
    }
    if ($releaseManifest -match "LearningMonitorDiagnosticsProvider|READ_MONITOR_DIAGNOSTICS|monitor-diagnostics") {
        throw "Release APK unexpectedly includes the debug-only diagnostics provider or permission."
    }
    if ($usesExternalObserver -and
        ($debugManifest -notmatch "LearningMonitorDiagnosticsProvider" -or
         $debugManifest -notmatch "READ_MONITOR_DIAGNOSTICS" -or
         $debugManifest -notmatch "protectionLevel.*0x2")) {
        throw "Debug APK is missing its signature-protected read-only diagnostics provider."
    }
    if ($usesExternalObserver -and
        ($observerManifest -notmatch "E: queries[\s\S]*?E: package[\s\S]*?com\.example\.todoschooltime\.debug" -or
         $observerManifest -notmatch "com\.enuma\.todohangeul" -or
         $observerManifest -notmatch "com\.enuma\.todoenglish" -or
         $observerManifest -notmatch "com\.enuma\.todomath" -or
         $observerManifest -notmatch "READ_MONITOR_DIAGNOSTICS" -or
         $observerManifest -notmatch "android:networkSecurityConfig" -or
         $observerManifest -notmatch "android:usesCleartextTraffic.*\(type 0x12\)0x0")) {
        throw "External observer APK must query the debug and subject packages, request its diagnostics signature permission, and deny cleartext by default."
    }
    if ($usesExternalObserver) {
        Write-Host "PASS observer manifest declares visibility for the debug and subject packages, requests its signature permission, and denies cleartext by default."
    }
    if ($debugManifest -notmatch "android:networkSecurityConfig") {
        throw "Debug APK is missing its loopback-only network security configuration."
    }
    if ($debugManifest -notmatch "android:usesCleartextTraffic.*\(type 0x12\)0x0" -or
        $releaseManifest -notmatch "android:usesCleartextTraffic.*\(type 0x12\)0x0") {
        throw "Debug and release APKs must both declare cleartext traffic disabled by default."
    }
    $debugNetworkConfig = (& $aaptPath dump xmltree $debugApk "res/xml/ticket01_network_security_config.xml" 2>&1) -join "`n"
    if ($debugNetworkConfig -notmatch "E: base-config[\s\S]*?cleartextTrafficPermitted.*0x0" -or
        $debugNetworkConfig -notmatch "E: domain-config[\s\S]*?cleartextTrafficPermitted.*0xffffffff" -or
        $debugNetworkConfig -notmatch 'C: "127\.0\.0\.1"' -or
        $debugNetworkConfig -notmatch "includeSubdomains.*0x0") {
        throw "Debug APK does not contain the expected cleartext exception for only 127.0.0.1."
    }
    $releasePermissions = (& $aaptPath dump permissions $releaseApk 2>&1) -join "`n"
    $debugPermissions = (& $aaptPath dump permissions $debugApk 2>&1) -join "`n"
    if ($releasePermissions -notmatch "android.permission.INTERNET" -or
        $debugPermissions -notmatch "android.permission.INTERNET") {
        throw "Release and debug APKs must both keep INTERNET permission."
    }
    if ($usesExternalObserver) {
        $observerPermissions = (& $aaptPath dump permissions $observerApk 2>&1) -join "`n"
        if ($observerPermissions -notmatch "android.permission.INTERNET") {
            throw "External observer APK needs INTERNET permission to call only the host-mapped loopback fixture."
        }
        $observerNetworkConfig = (& $aaptPath dump xmltree $observerApk "res/xml/ticket02_network_security_config.xml" 2>&1) -join "`n"
        if ($observerNetworkConfig -notmatch "E: base-config[\s\S]*?cleartextTrafficPermitted.*0x0" -or
            $observerNetworkConfig -notmatch "E: domain-config[\s\S]*?cleartextTrafficPermitted.*0xffffffff" -or
            $observerNetworkConfig -notmatch 'C: "127\.0\.0\.1"' -or
            $observerNetworkConfig -notmatch "includeSubdomains.*0x0") {
            throw "External observer APK must allow cleartext only to 127.0.0.1 for the temporary fixture."
        }
        Write-Host "PASS observer network access is limited to cleartext loopback fixture traffic."
    }
    & $pythonPath (Join-Path $PSScriptRoot "inspect_apks.py") `
        --debug-apk $debugApk --release-apk $releaseApk --api-base-url $apiBaseUrl
    if ($LASTEXITCODE -ne 0) { throw "Debug/release API URL artifact inspection failed." }
    Write-Host "PASS debug/release APK identity, INTERNET permission, API URL, and network-policy checks."

    # Ticket 03 intentionally reuses Ticket 02's prepared package identities and product APK.
    $preparedDeviceDirectory = Join-Path $repositoryRoot "build\e2e-ticket02"
    $preparedDeviceManifestPath = Join-Path $preparedDeviceDirectory "prepared-device.json"
    if ($RefreshObserver) {
        if (-not (Test-Path -LiteralPath $preparedDeviceManifestPath)) {
            throw "No prepared external-observer installation was found. Run Ticket 02 preparation once before -RefreshObserver."
        }
        $prepared = Get-Content -LiteralPath $preparedDeviceManifestPath -Raw | ConvertFrom-Json
        if ($prepared.applicationId -ne $debugApplicationId -or
            $prepared.observerApplicationId -ne $observerApplicationId -or
            $prepared.observerTestApplicationId -ne $observerTestApplicationId -or
            $prepared.deviceSerial -ne $DeviceSerial -or
            $prepared.apiBaseUrl -ne $apiBaseUrl -or
            $prepared.debugApkSha256 -ne (Get-ApkSha256 $debugApk)) {
            throw "Prepared product APK or device configuration changed; refusing to refresh only the observer. Run -PrepareDevice and reconnect accessibility manually."
        }
        $before = Get-DeviceMonitorState
        $before.accessibilityDump | Set-Content -LiteralPath (Join-Path $artifactDirectory "product-service-before-observer-refresh.txt") -Encoding utf8
        if (-not $before.pid -or -not $before.bound -or $before.crashed) {
            throw "Refusing observer refresh because the product AccessibilityService is not currently bound. PID='$($before.pid)', bound=$($before.bound), crashed=$($before.crashed)."
        }
        Assert-InstalledApkEquals $debugApplicationId $debugApk "installed-debug.apk" | Out-Null
        $observerNeedsInstall = $true
        if (Get-PackagePath $observerApplicationId) {
            $observerNeedsInstall = (Get-InstalledApkSha256 $observerApplicationId "installed-observer.apk") -ne (Get-ApkSha256 $observerApk)
        }
        $observerTestNeedsInstall = $true
        if (Get-PackagePath $observerTestApplicationId) {
            $observerTestNeedsInstall = (Get-InstalledApkSha256 $observerTestApplicationId "installed-observer-test.apk") -ne (Get-ApkSha256 $observerTestApk)
        }
        if ($observerNeedsInstall) { Invoke-SelectedAdb @("install", "-r", $observerApk) | Out-Null }
        if ($observerTestNeedsInstall) { Invoke-SelectedAdb @("install", "-r", $observerTestApk) | Out-Null }
        Assert-InstalledApkEquals $observerApplicationId $observerApk "installed-observer.apk" | Out-Null
        Assert-InstalledApkEquals $observerTestApplicationId $observerTestApk "installed-observer-test.apk" | Out-Null
        $after = Get-DeviceMonitorState
        $after.accessibilityDump | Set-Content -LiteralPath (Join-Path $artifactDirectory "product-service-after-observer-refresh.txt") -Encoding utf8
        if ($after.pid -ne $before.pid -or -not $after.bound -or $after.crashed) {
            throw "Observer refresh changed the product service lifecycle. PID before='$($before.pid)', after='$($after.pid)', bound=$($after.bound), crashed=$($after.crashed)."
        }
        $prepared.observerApkSha256 = Get-ApkSha256 $observerApk
        $prepared.observerTestApkSha256 = Get-ApkSha256 $observerTestApk
        $prepared | Add-Member -NotePropertyName observerRefreshedAtUtc -NotePropertyValue ([DateTimeOffset]::UtcNow.ToString("O")) -Force
        $prepared | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $preparedDeviceManifestPath -Encoding utf8
        Write-Host "External observer APKs were refreshed; product APK was not reinstalled. Product service PID $($before.pid) remained bound. Observer installs updated=$observerNeedsInstall, observer-test install updated=$observerTestNeedsInstall."
        Write-Host "Preparation manifest: $preparedDeviceManifestPath"
        return
    }
    if ($PrepareDevice) {
        New-Item -ItemType Directory -Path $preparedDeviceDirectory -Force | Out-Null
        Invoke-SelectedAdb @("install", "-r", $debugApk) | Out-Null
        Invoke-SelectedAdb @("install", "-r", $observerApk) | Out-Null
        Invoke-SelectedAdb @("install", "-r", $observerTestApk) | Out-Null
        $prepared = [ordered]@{
            applicationId = $debugApplicationId
            observerApplicationId = $observerApplicationId
            observerTestApplicationId = $observerTestApplicationId
            deviceSerial = $DeviceSerial
            apiBaseUrl = $apiBaseUrl
            debugApkSha256 = Get-ApkSha256 $debugApk
            observerApkSha256 = Get-ApkSha256 $observerApk
            observerTestApkSha256 = Get-ApkSha256 $observerTestApk
            preparedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
        }
        $prepared | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $preparedDeviceManifestPath -Encoding utf8
        Write-Host "Observer scenario APKs are installed and verified. Accessibility reconnect requires the human's settings action; no scenario ran and the local server was not started."
        Write-Host "Preparation manifest: $preparedDeviceManifestPath"
        return
    }

    if ($RunPrepared) {
        if (-not (Test-Path -LiteralPath $preparedDeviceManifestPath)) {
            throw "No prepared observer installation was found. Prepare Ticket 02 once, then restore accessibility manually."
        }
        $prepared = Get-Content -LiteralPath $preparedDeviceManifestPath -Raw | ConvertFrom-Json
        if ($prepared.applicationId -ne $debugApplicationId -or
            $prepared.observerApplicationId -ne $observerApplicationId -or
            $prepared.observerTestApplicationId -ne $observerTestApplicationId -or
            $prepared.deviceSerial -ne $DeviceSerial -or
            $prepared.apiBaseUrl -ne $apiBaseUrl) {
            throw "Prepared observer APKs do not match the selected device or loopback server configuration."
        }
        if ($prepared.debugApkSha256 -ne (Get-ApkSha256 $debugApk) -or
            $prepared.observerApkSha256 -ne (Get-ApkSha256 $observerApk) -or
            $prepared.observerTestApkSha256 -ne (Get-ApkSha256 $observerTestApk)) {
            throw "Prepared APK build outputs changed. Refresh only the observer when the product APK hash still matches; otherwise stop for manual recovery."
        }
        Assert-InstalledApkEquals $debugApplicationId $debugApk "installed-debug.apk" | Out-Null
        Assert-InstalledApkEquals $observerApplicationId $observerApk "installed-observer.apk" | Out-Null
        Assert-InstalledApkEquals $observerTestApplicationId $observerTestApk "installed-observer-test.apk" | Out-Null
        if ($Scenario -eq "ticket04" -and $Ticket04Case -eq "accessibility") {
            $resolvedPriorEvidencePath = (Resolve-Path -LiteralPath $Ticket04NotificationEvidencePath -ErrorAction Stop).Path
            $ticket04PriorNotificationCaseEvidence = Get-Content -LiteralPath $resolvedPriorEvidencePath -Raw | ConvertFrom-Json -ErrorAction Stop
            if ($ticket04PriorNotificationCaseEvidence.caseId -cne "notification-revocation" -or
                -not (Test-Ticket04CaseEvidence -Evidence $ticket04PriorNotificationCaseEvidence -ExpectedDeviceSerial $DeviceSerial -ExpectedProductApkSha256 $prepared.debugApkSha256)) {
                throw "The prior notification case artifact failed strict schema, source, fixture, device, or product-binary validation."
            }
            $currentSourceSnapshot = Get-Ticket04SourceSnapshot
            $ticket04SourceCompatibilityProof = New-Ticket04SourceCompatibilityProof `
                -NotificationEvidence $ticket04PriorNotificationCaseEvidence `
                -CurrentSourceSnapshot $currentSourceSnapshot `
                -ExpectedProductApkSha256 $prepared.debugApkSha256 `
                -ExpectedDeviceSerial $DeviceSerial `
                -ExpectedApiLevel $sdkVersion `
                -RepositoryRoot $repositoryRoot `
                -PythonPath $pythonPath
            $compatibilityPath = Join-Path $artifactDirectory "ticket04-source-compatibility.json"
            if (Test-Path -LiteralPath $compatibilityPath) {
                throw "Ticket 04 source compatibility proof is immutable and already exists: $compatibilityPath"
            }
            $ticket04SourceCompatibilityProof | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $compatibilityPath -Encoding utf8
            Write-Host "PASS prior notification evidence and current accessibility contract match the same device/product APK; the committed source diff is limited to the verified observer, fixture and validator paths."
        }
        Write-Host "PASS prepared debug, observer and observer-test APK hashes match the installed packages; no product install will occur."
    }

    $serverScript = Join-Path $PSScriptRoot "server.py"
    $serverArguments = @(
        "-u",
        "`"$serverScript`"",
        "--host", "127.0.0.1",
        "--port", [string]$serverPort,
        "--run-id", $runId,
        "--scenario", $Scenario
    )
    $serverProcess = Start-Process -FilePath $serverPythonPath -ArgumentList $serverArguments `
        -WorkingDirectory $repositoryRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrorLog
    $serverStarted = $true
    $serverReady = $false
    $serverDeadline = [DateTimeOffset]::UtcNow.AddSeconds(15)
    while ([DateTimeOffset]::UtcNow -lt $serverDeadline) {
        if ($serverProcess.HasExited) {
            throw "Temporary server exited before becoming ready. See $serverErrorLog"
        }
        try {
            $healthPath = switch ($Scenario) {
                "ticket02" { "/__ticket02/health"; break }
                "ticket03" { "/__ticket03/health"; break }
                "ticket04" { "/__ticket04/health"; break }
                "ticket05" { "/__ticket05/health"; break }
                default { "/__ticket01/health" }
            }
            $health = Invoke-RestMethod -Uri "$apiBaseUrl$healthPath" -TimeoutSec 2
            if ($health.ok -and $health.runId -eq $runId -and $health.scenario -eq $Scenario) { $serverReady = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    if (-not $serverReady) { throw "Temporary server did not become ready at $apiBaseUrl; no device install was attempted." }

    $reverseListing = Invoke-SelectedAdb @("reverse", "--list")
    $portMapping = $reverseListing | Where-Object {
        $_ -match "(?:^|\s)tcp:$serverPort\s+tcp:(?<remote>\d+)(?:\s|$)"
    } | Select-Object -First 1
    if ($portMapping) {
        if ($portMapping -notmatch "tcp:$serverPort\s+tcp:$serverPort(?:\s|$)") {
            throw "Device already has a conflicting reverse for tcp:$serverPort; leaving that mapping untouched."
        }
        Write-Host "Reusing pre-existing adb reverse mapping for tcp:$serverPort; it will not be removed."
    } else {
        Invoke-SelectedAdb @("reverse", "tcp:$serverPort", "tcp:$serverPort") | Out-Null
        $reverseOwned = $true
    }

    Invoke-SelectedAdb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
    Start-Sleep -Milliseconds 750
    $powerState = (Invoke-SelectedAdb @("shell", "dumpsys", "power") | Out-String)
    if ($powerState -notmatch "mWakefulness=Awake") {
        throw "The device display did not wake. Wake it manually and retry; the runner will not unlock the device."
    }
    $windowPolicy = (Invoke-SelectedAdb @("shell", "dumpsys", "window", "policy") | Out-String)
    if ($windowPolicy -match "mIsShowing=true" -or $windowPolicy -match "(?m)^\s*showing=true\s*$") {
        throw "The device is at a secure lock screen. Unlock it manually and retry; the runner will not bypass device security."
    }

    if ($Scenario -eq "ticket02") {
        $instrumentationComponent = "$observerTestApplicationId/androidx.test.runner.AndroidJUnitRunner"
        $testClass = "com.example.todoschooltime.e2eobserver.Ticket02BaselineCompletionTest"
        $instrumentationText = Invoke-ExternalObserver $apiBaseUrl $runId $instrumentationComponent $testClass "Ticket02E2E" 6
        $instrumentationOutput = @($instrumentationText -split "`r?`n")
    } elseif ($Scenario -eq "ticket03") {
        $instrumentationComponent = "$observerTestApplicationId/androidx.test.runner.AndroidJUnitRunner"
        $testClass = "com.example.todoschooltime.e2eobserver.Ticket03ForegroundPollingTest"
        $instrumentationText = Invoke-ExternalObserver $apiBaseUrl $runId $instrumentationComponent $testClass "Ticket03E2E" 22
        $instrumentationOutput = @($instrumentationText -split "`r?`n")
    } elseif ($Scenario -eq "ticket04") {
        $instrumentationComponent = "$observerTestApplicationId/androidx.test.runner.AndroidJUnitRunner"
        $testClass = "com.example.todoschooltime.e2eobserver.Ticket04PermissionRecoveryTest"
        $instrumentationText = Invoke-ExternalObserver $apiBaseUrl $runId $instrumentationComponent $testClass "Ticket04Result" 45 $Ticket04Case
        $instrumentationOutput = @($instrumentationText -split "`r?`n")
    } elseif ($Scenario -eq "ticket05") {
        $instrumentationComponent = "$observerTestApplicationId/androidx.test.runner.AndroidJUnitRunner"
        $testClass = "com.example.todoschooltime.e2eobserver.Ticket05HttpAuthRecoveryTest"
        $script:ticket05StructuredResultJson = $null
        $instrumentationText = Invoke-ExternalObserver $apiBaseUrl $runId $instrumentationComponent $testClass "Ticket05Result" 15
        $instrumentationOutput = @($instrumentationText -split "`r?`n")
    } else {
        Invoke-SelectedAdb @("install", "-r", $debugApk) | Out-Null
        Invoke-SelectedAdb @("install", "-r", $testApk) | Out-Null
        $instrumentationComponent = "$testApplicationId/androidx.test.runner.AndroidJUnitRunner"
        $instrumentationArguments = @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "expectedApiBaseUrl", $apiBaseUrl,
            "-e", "expectedRunId", $runId
        )
        $testClass = "com.example.todoschooltime.Ticket01ServerScreenRoundTripTest"
        $instrumentationArguments += @("-e", "class", $testClass, $instrumentationComponent)
        $instrumentationOutput = Invoke-SelectedAdb $instrumentationArguments
        $instrumentationText = $instrumentationOutput -join "`n"
    }
    $instrumentationLog = Join-Path $artifactDirectory "connected-device-test.log"
    $instrumentationOutput | Set-Content -LiteralPath $instrumentationLog -Encoding utf8
    if ($instrumentationText -notmatch "OK \(1 test\)" -or $instrumentationText -match "FAILURES!!!|INSTRUMENTATION_FAILED") {
        $testDescription = switch ($Scenario) {
            "ticket02" { "Ticket 02 external observer test"; break }
            "ticket03" { "Ticket 03 foreground-polling observer test"; break }
            "ticket04" { "Ticket 04 permission-recovery observer test"; break }
            "ticket05" { "Ticket 05 HTTP authentication-recovery observer test"; break }
            default { "Ticket 01 server-screen test" }
        }
        throw "$testDescription did not report one passing test. See $instrumentationLog"
    }

    $snapshot = Read-ServerRequests
    if ($null -ne $snapshot) {
        if ($Scenario -eq "ticket04") {
            (Get-Ticket04ServerEvidence $snapshot) | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $artifactDirectory "ticket04-server-evidence.json") -Encoding utf8
        } else {
            $snapshotPath = Join-Path $artifactDirectory "server-requests.json"
            $snapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $snapshotPath -Encoding utf8
        }
    }
    if ($Scenario -eq "ticket02") {
        Assert-Ticket02BaselineCompletion $snapshot $instrumentationText
    } elseif ($Scenario -eq "ticket03") {
        Assert-Ticket03ForegroundPolling $snapshot $instrumentationText
    } elseif ($Scenario -eq "ticket04") {
        Assert-Ticket04PermissionRecovery $snapshot $instrumentationText $Ticket04Case $ticket04PriorNotificationCaseEvidence
    } elseif ($Scenario -eq "ticket05") {
        if ($null -eq $snapshot -or -not $script:ticket05StructuredResultJson) {
            throw "Ticket 05 did not produce both the real fixture request log and complete observer result chunks."
        }
        $nativeResult = $script:ticket05StructuredResultJson | ConvertFrom-Json -ErrorAction Stop
        $nativeResultPath = Join-Path $artifactDirectory "ticket05-observer-result.json"
        if (Test-Path -LiteralPath $nativeResultPath) { throw "Ticket 05 observer result is immutable and already exists: $nativeResultPath" }
        $nativeResult | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $nativeResultPath -Encoding utf8
        $ticket05Evidence = Assert-Ticket05HttpAuthRecovery -Snapshot $snapshot -Result $nativeResult -RunId $runId -ApiBaseUrl $apiBaseUrl
        $sourceFiles = @(
            "tools/e2e-ticket01/server.py",
            "tools/e2e-ticket01/test_server.py",
            "tools/e2e-ticket01/Run-Ticket01.ps1",
            "tools/e2e-ticket01/Invoke-NativeCommand.ps1",
            "tools/e2e-ticket05/ResultChunks.psm1",
            "tools/e2e-ticket05/HttpAuthRecoveryEvidence.psm1",
            "tools/e2e-ticket05/InstrumentationOutcome.psm1",
            "tools/e2e-ticket05/Run-Ticket05.ps1",
            "tools/e2e-ticket05/README.md",
            "tools/e2e-ticket05/Test-ExternalObserverOutcome.ps1",
            "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket05HttpAuthRecoveryTest.kt"
        )
        $sourceHashes = [ordered]@{}
        foreach ($relativePath in $sourceFiles) {
            $sourcePath = Join-Path $repositoryRoot $relativePath
            if (-not (Test-Path -LiteralPath $sourcePath)) { throw "Ticket 05 provenance source file is missing: $relativePath" }
            $sourceHashes[$relativePath] = Get-ApkSha256 $sourcePath
        }
        $gitHead = (& git -C $repositoryRoot rev-parse HEAD 2>&1 | Select-Object -First 1).Trim()
        if ($LASTEXITCODE -ne 0) { throw "Could not record Ticket 05 source revision." }
        $provenance = [pscustomobject]@{
            sourceRevision = $gitHead
            sourceFileSha256 = $sourceHashes
            deviceSerial = $DeviceSerial
            deviceModel = $model
            deviceApiLevel = [int]$sdkVersion
            preparedProductApkSha256 = [string]$prepared.debugApkSha256
            installedProductApkSha256 = [string]$prepared.debugApkSha256
            observerApkSha256 = [string]$prepared.observerApkSha256
            observerTestApkSha256 = [string]$prepared.observerTestApkSha256
            fixtureAddress = $apiBaseUrl
            runId = $runId
            resultCapturedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
        }
        $ticket05Evidence | Add-Member -NotePropertyName provenance -NotePropertyValue $provenance
        $evidencePath = Join-Path $artifactDirectory "ticket05-evidence.json"
        if (Test-Path -LiteralPath $evidencePath) { throw "Ticket 05 evidence is immutable and already exists: $evidencePath" }
        $ticket05Evidence | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $evidencePath -Encoding utf8
    } else {
        Assert-ServerRoundTrip $snapshot
        Save-AppScreenshot
    }

    $regularPackageAfter = Get-PackagePath $releaseApplicationId
    if ($regularPackageAfter -ne $regularPackageBefore) {
        throw "The installed production application changed during the isolated debug run."
    }
    $debugPackageAfter = Get-PackagePath $debugApplicationId
    if (-not $debugPackageAfter) { throw "The isolated debug app was not installed." }

    $apiDescription = if ($sdkVersion -eq 35) { "Android 15" } elseif ($sdkVersion -eq 33) { "Android 13 substitute" } else { "API $sdkVersion" }
    if ($Scenario -eq "ticket04" -and $Ticket04Case -eq "notification") {
        Write-Host "PASS Ticket 04 notification case only on $apiDescription. Accessibility was not run; no two-case aggregate is reported."
    } elseif ($Scenario -eq "ticket04") {
        Write-Host "PASS Ticket 04 two-case aggregate on $apiDescription after manual permission revocation and restoration."
    } elseif ($Scenario -eq "ticket05") {
        Write-Host "PASS Ticket 05 actual HTTP failure, authentication pause, UI re-login, and completion recovery on $apiDescription. Product APK was reused without reinstall; sound/HUN delivery was not tested."
    } else {
        Write-Host "PASS $Scenario on $apiDescription. No notification or accessibility permissions were changed."
    }
    Write-Host "Artifacts: $artifactDirectory"
} catch {
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $artifactDirectory "failure.txt") -Encoding utf8
    Write-Error $_
    throw
} finally {
    if ($serverStarted) {
        $finalSnapshot = Read-ServerRequests
        if ($null -ne $finalSnapshot) {
            if ($Scenario -eq "ticket04") {
                (Get-Ticket04ServerEvidence $finalSnapshot) | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $artifactDirectory "ticket04-server-evidence-final.json") -Encoding utf8
            } else {
                $finalSnapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $artifactDirectory "server-requests-final.json") -Encoding utf8
            }
        }
    }
    if ($reverseOwned) {
        try { Invoke-SelectedAdb @("reverse", "--remove", "tcp:$serverPort") | Out-Null }
        catch { Write-Warning "Could not remove the adb reverse mapping created by this run: $($_.Exception.Message)" }
    }
    if ($serverProcess -and -not $serverProcess.HasExited) {
        Stop-Process -Id $serverProcess.Id -Force
    }
    if ($serverProcess) {
        $serverProcess.WaitForExit(5000) | Out-Null
    }

    $env:JAVA_HOME = $oldJavaHome
    $env:GRADLE_USER_HOME = $oldGradleUserHome
    $env:ANDROID_HOME = $oldAndroidHome
    $env:ANDROID_SDK_ROOT = $oldAndroidSdkRoot
    $env:ANDROID_USER_HOME = $oldAndroidUserHome
    $env:ANDROID_SERIAL = $oldAndroidSerial
}
