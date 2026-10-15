[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$DeviceSerial,
    [switch]$RunPrepared,
    [switch]$RefreshObserver
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Common.ps1")
. (Join-Path $PSScriptRoot "MonitorRecovery.ps1")
function Resolve-Ticket06ExecutionMode {
    [CmdletBinding()]
    param(
        [switch]$RunPrepared,
        [switch]$RefreshObserver
    )

    if ([bool]$RunPrepared -eq [bool]$RefreshObserver) {
        throw "Choose exactly one of -RunPrepared or -RefreshObserver."
    }
    if ($RefreshObserver) { return "refresh-observer" }
    return "run-prepared"
}

# Fail closed before resolving paths, reading configuration, starting the fixture, or invoking ADB.
$executionMode = Resolve-Ticket06ExecutionMode -RunPrepared:$RunPrepared -RefreshObserver:$RefreshObserver

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gitCommit = Get-CurrentRepositoryCommit -RepositoryRoot $repositoryRoot
if ($executionMode -eq "refresh-observer") {
    $refreshRunner = Join-Path $PSScriptRoot "Invoke-IntegrationScenarios.ps1"
    $refreshArguments = @{ RefreshObserver = $true }
    if ($ConfigPath) { $refreshArguments.ConfigPath = $ConfigPath }
    if ($DeviceSerial) { $refreshArguments.DeviceSerial = $DeviceSerial }
    $refreshArguments.Scenario = "ticket05"
    & $refreshRunner @refreshArguments
    if (-not $?) { exit 1 }
    exit 0
}

if (-not $ConfigPath) {
    $candidate = Join-Path $PSScriptRoot "config.json"
    $ConfigPath = if (Test-Path -LiteralPath $candidate) { $candidate } else { Join-Path $PSScriptRoot "config.example.json" }
}
$config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json -ErrorAction Stop
if ($config.serverHost -ne "127.0.0.1") { throw "Ticket 06 accepts only the local fixture host 127.0.0.1." }
$serverPort = [int]$config.serverPort
if ($serverPort -lt 1024 -or $serverPort -gt 65535) { throw "Fixture port must be in 1024..65535." }
$apiBaseUrl = "http://127.0.0.1:$serverPort"

$preparedManifestPath = Join-Path $repositoryRoot "build/e2e-ticket02/prepared-device.json"
if (-not (Test-Path -LiteralPath $preparedManifestPath)) {
    throw "Ticket 02 prepared-device manifest is missing; no device action was attempted: $preparedManifestPath"
}
$prepared = Get-Content -LiteralPath $preparedManifestPath -Raw | ConvertFrom-Json -ErrorAction Stop
if ([string]$prepared.apiBaseUrl -and [string]$prepared.apiBaseUrl -cne $apiBaseUrl) {
    throw "Prepared debug APK is configured for a different loopback fixture address."
}
if (-not $DeviceSerial) { $DeviceSerial = if ($config.deviceSerial) { [string]$config.deviceSerial } else { [string]$prepared.deviceSerial } }
if ($DeviceSerial -cne [string]$prepared.deviceSerial) { throw "Selected device does not match the prepared-device manifest." }
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]+$') { throw "Device serial contains unsupported characters." }

$script:DeviceSerial = $DeviceSerial
$script:AdbPath = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
if (-not $script:AdbPath) { $script:AdbPath = "C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe" }
if (-not (Test-Path -LiteralPath $script:AdbPath)) { throw "Android Debug Bridge is missing: $script:AdbPath" }
$pythonPath = [string]$config.pythonPath
if (-not (Test-Path -LiteralPath $pythonPath)) { throw "Configured Python runtime is missing: $pythonPath" }
$pythonExecutableOutput = @(& $pythonPath -c "import sys; print(sys.executable)" 2>&1)
$pythonExecutableExitCode = $LASTEXITCODE
$serverPythonPath = (@($pythonExecutableOutput | ForEach-Object { [string]$_ } | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -Last 1) -join "").Trim()
if ($pythonExecutableExitCode -ne 0 -or -not (Test-Path -LiteralPath $serverPythonPath)) { throw "Configured Python runtime could not be resolved." }

$debugApplicationId = "com.example.todoschooltime.debug"
$observerApplicationId = "com.example.todoschooltime.e2eobserver"
$observerTestApplicationId = "com.example.todoschooltime.e2eobserver.test"
$releaseApplicationId = "com.example.todoschooltime"
$observerAccessibilityLabel = "토도 학습 감시 · 로컬 테스트"
$runId = [Guid]::NewGuid().ToString("N")
$artifactDirectory = Join-Path $repositoryRoot "build/e2e-ticket06/$runId"
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null

$serverProcess = $null
$serverStarted = $false
$originalReversePresent = $false
$reverseOwned = $false
$serverPort = [int]$serverPort
$initialWakefulness = $null
$preResult = $null
$postResult = $null
$prePreferenceState = $null
$preRebootPreferenceState = $null
$preRebootStageSeenAtUtc = $null
$preRebootPreferenceAck = $null
$postPreferenceState = $null
$instrumentationResults = [System.Collections.Generic.List[object]]::new()

function Invoke-SelectedAdb([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $script:AdbPath -s $script:DeviceSerial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) { throw "adb $($Arguments -join ' ') failed ($exitCode): $(($output | Select-Object -Last 8) -join "`n")" }
    return @($output | ForEach-Object { [string]$_ })
}

function Get-ConnectedDevices {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $script:AdbPath devices -l 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($exitCode -ne 0) { throw "adb devices failed ($exitCode)." }
    $devices = @{}
    foreach ($line in $output) {
        if ([string]$line -match '^([^\s]+)\s+(device|offline|unauthorized)(?:\s|$)') {
            $devices[$Matches[1]] = $Matches[2]
        }
    }
    return $devices
}

function Get-ReversePortMapping {
    $lines = Invoke-SelectedAdb @("reverse", "--list")
    $matches = @($lines | Where-Object { $_ -match "(?:^|\s)tcp:$serverPort\s+tcp:(?<remote>\d+)(?:\s|$)" })
    if ($matches.Count -gt 1) { throw "Multiple adb reverse entries use local tcp:$serverPort; no mapping was changed." }
    if ($matches.Count -eq 0) { return $null }
    if ($matches[0] -notmatch "tcp:$serverPort\s+tcp:$serverPort(?:\s|$)") {
        throw "A conflicting adb reverse uses local tcp:$serverPort; no mapping was changed."
    }
    return [string]$matches[0]
}

function Ensure-ReversePortMapping {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(12)
    $attempts = 0
    $lastError = "No ADB reverse mapping was observed."
    do {
        $attempts++
        try {
            $existing = Get-ReversePortMapping
            if ($existing) {
                return New-VerifiedFixtureReverseMapping -Status reused -LocalPort $serverPort -Mapping $existing `
                    -AttemptCount $attempts -VerifiedAtUtc ([DateTimeOffset]::UtcNow)
            }
            Invoke-SelectedAdb @("reverse", "tcp:$serverPort", "tcp:$serverPort") | Out-Null
            $verified = Get-ReversePortMapping
            if ($verified) {
                return New-VerifiedFixtureReverseMapping -Status restored -LocalPort $serverPort -Mapping $verified `
                    -AttemptCount $attempts -VerifiedAtUtc ([DateTimeOffset]::UtcNow)
            }
            $lastError = "adb reverse tcp:$serverPort was not present after the command."
        } catch {
            if ($_.Exception.Message -match "conflicting adb reverse|Multiple adb reverse entries") { throw }
            $lastError = $_.Exception.Message
        }
        if ([DateTimeOffset]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 500 }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "adb reverse tcp:$serverPort was not ready after $attempts bounded attempts: $lastError"
}

function Get-PackagePath([string]$PackageName) {
    $output = Invoke-SelectedAdb @("shell", "pm", "path", $PackageName)
    foreach ($line in $output) {
        if ($line -match '^package:(?<path>.+)$') { return $Matches.path }
    }
    return $null
}

function Get-OptionalPackagePath([string]$PackageName) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $script:AdbPath -s $script:DeviceSerial shell pm path $PackageName 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($exitCode -notin @(0, 1)) { throw "Could not read optional package path for $PackageName (adb exit $exitCode)." }
    foreach ($line in $output) {
        if ([string]$line -match '^package:(?<path>.+)$') { return $Matches.path }
    }
    return $null
}

function Get-InstalledPackageHash([string]$PackageName, [string]$ArtifactName) {
    $remotePath = Get-PackagePath $PackageName
    if (-not $remotePath) { throw "Required prepared package is not installed: $PackageName" }
    $localPath = Join-Path $artifactDirectory $ArtifactName
    Invoke-SelectedAdb @("pull", $remotePath, $localPath) | Out-Null
    return (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-DeviceMonitorState {
    $pidOutput = @()
    $pidExitCode = 0
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $pidOutput = & $script:AdbPath -s $script:DeviceSerial shell pidof $debugApplicationId 2>&1
        $pidExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($pidExitCode -notin @(0, 1)) { throw "Could not read the debug service PID (adb exit $pidExitCode)." }
    $pidText = (@($pidOutput) -join " ").Trim()
    $dump = (Invoke-SelectedAdb @("shell", "dumpsys", "accessibility") | Out-String)
    $boundStart = $dump.IndexOf("Bound services:", [StringComparison]::Ordinal)
    $enabledStart = $dump.IndexOf("Enabled services:", [StringComparison]::Ordinal)
    $boundText = if ($boundStart -ge 0 -and $enabledStart -gt $boundStart) { $dump.Substring($boundStart, $enabledStart - $boundStart) } else { "" }
    $crashedStart = $dump.IndexOf("Crashed services:", [StringComparison]::Ordinal)
    $crashedText = if ($crashedStart -ge 0) { $dump.Substring($crashedStart) } else { "" }
    return [pscustomobject]@{
        pid = $pidText
        bound = $boundText.Contains("$debugApplicationId/com.example.todoschooltime.MonitorAccessibilityService") -or $boundText.Contains($observerAccessibilityLabel)
        crashed = $crashedText.Contains("$debugApplicationId/com.example.todoschooltime.MonitorAccessibilityService")
        matchingServiceLines = @($dump -split "`r?`n" | Where-Object { $_.Contains($debugApplicationId) -or $_.Contains($observerAccessibilityLabel) })
    }
}

function Get-Property([string]$Name) {
    return ((Invoke-SelectedAdb @("shell", "getprop", $Name) | Select-Object -First 1) -join "").Trim()
}

function Get-BootCount {
    $value = ((Invoke-SelectedAdb @("shell", "settings", "get", "global", "boot_count") | Select-Object -First 1) -join "").Trim()
    $count = 0
    if (-not [int]::TryParse($value, [ref]$count)) { throw "Android did not return a readable global boot_count." }
    return $count
}

function Get-WindowLockState {
    $policy = (Invoke-SelectedAdb @("shell", "dumpsys", "window", "policy") | Out-String)
    $showing = $policy -match "mIsShowing=true" -or $policy -match "(?m)^\s*showing=true\s*$"
    return [pscustomobject]@{ needsUnlock = [bool]$showing; evidence = @($policy -split "`r?`n" | Where-Object { $_ -match "mIsShowing=|^\s*showing=" }) }
}

function Wait-ForBootComplete([int]$TimeoutSeconds) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ((Get-ConnectedDevices)[$script:DeviceSerial] -ne "device") { Start-Sleep -Seconds 1; continue }
        try {
            if ((Get-Property "sys.boot_completed") -eq "1") { return [DateTimeOffset]::UtcNow.ToString("O") }
        } catch { }
        Start-Sleep -Seconds 1
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "The device did not report sys.boot_completed=1 within $TimeoutSeconds seconds."
}

function Wait-ForBoundMonitor([int]$TimeoutSeconds) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ((Get-ConnectedDevices)[$script:DeviceSerial] -ne "device") { Start-Sleep -Seconds 1; continue }
        try {
            $state = Get-DeviceMonitorState
            if ($state.pid -and $state.bound -and -not $state.crashed) {
                $state | Add-Member -NotePropertyName observedAtUtc -NotePropertyValue ([DateTimeOffset]::UtcNow.ToString("O"))
                return $state
            }
        } catch { }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "The real AccessibilityService did not bind automatically within $TimeoutSeconds seconds; no product Activity or permission setting was opened."
}

function Assert-ServerPortFree([int]$Port) {
    $listeners = @()
    try { $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop) }
    catch { $listeners = @() }
    if ($listeners.Count -gt 0) { throw "Local port $Port already has a listener; the existing fixture was left untouched." }
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if ($pending.AsyncWaitHandle.WaitOne(500)) {
            try { $client.EndConnect($pending); throw "Local port $Port is already accepting connections; the existing process was left untouched." }
            catch [System.Net.Sockets.SocketException] { }
        }
    } finally { $client.Close() }
}

function Get-FixtureState {
    if ($script:serverStarted -and ($null -eq $script:serverProcess -or $script:serverProcess.HasExited)) {
        throw "The original loopback fixture process exited during this run."
    }
    $snapshot = Invoke-RestMethod -Method Get -Uri "$apiBaseUrl/__ticket06/state" -TimeoutSec 5
    if ($snapshot.runId -cne $runId -or $snapshot.scenario -cne "ticket06") { throw "Fixture state belongs to another run or scenario." }
    return $snapshot
}

function Read-PreferenceMatches([string]$FileName, [string]$Pattern, [int]$AfterContext = 0) {
    if ($Pattern.Contains("'")) { throw "Filtered preference patterns cannot contain shell quote characters." }
    if ($AfterContext -lt 0 -or $AfterContext -gt 3) { throw "Filtered preference context must remain within three adjacent rows." }
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $grepContext = if ($AfterContext -gt 0) { "-A $AfterContext " } else { "" }
        $remoteCommand = "run-as $debugApplicationId grep ${grepContext}-F $Pattern shared_prefs/$FileName"
        $output = & $script:AdbPath -s $script:DeviceSerial shell $remoteCommand 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($exitCode -eq 1) { return @() }
    if ($exitCode -ne 0) { throw "Could not read filtered app preference line from $FileName (adb exit $exitCode)." }
    return @($output | ForEach-Object { [string]$_ } | Where-Object { $_.Trim().Length -gt 0 })
}

function Get-PersistedScenarioPreferences {
    $accountKey = 'dGlja2V0MDEtZmFrZS1hY2NvdW50'
    $expectedChildName = '테스트 아이'
    $selectors = Get-PersistencePreferenceSelectors -AccountKey $accountKey -ChildName $expectedChildName
    $intervalLines = @(Read-PreferenceMatches "learning_monitor.xml" $selectors.intervalPattern)
    $registrationLines = @(Read-PreferenceMatches "learning_monitor.xml" $selectors.registrationPattern 2)
    $targetLines = @(Read-PreferenceMatches "target_counts.xml" $selectors.targetsPattern)
    return ConvertFrom-AppOwnedPreferenceRows -RegistrationRows $registrationLines -IntervalRows $intervalLines `
        -TargetRows $targetLines -AccountKey $accountKey -ChildName $expectedChildName
}

function Wait-ForPersistedScenarioPreferences([int]$TimeoutSeconds = 10) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $state = Get-PersistedScenarioPreferences
        if ($state.registrationPersisted -and $state.intervalPersisted -and $state.targetsPersisted) { return $state }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    return $state
}

function Invoke-ObserverPhase([string]$Phase, [long]$PostRebootSequence = -1) {
    $phaseKey = $Phase -replace '[^a-z0-9-]', '-'
    $stdoutPath = Join-Path $artifactDirectory "observer-$phaseKey.stdout.txt"
    $stderrPath = Join-Path $artifactDirectory "observer-$phaseKey.stderr.txt"
    $nativeArgumentsPath = Join-Path $artifactDirectory "observer-$phaseKey-native-arguments.json"
    $nativeStatusPath = Join-Path $artifactDirectory "observer-$phaseKey-native-status.json"
    $transportPath = Join-Path $artifactDirectory "observer-$phaseKey-transport.json"
    $logLinesPath = Join-Path $artifactDirectory "observer-$phaseKey-result-chunks.txt"
    $resultPath = Join-Path $artifactDirectory "observer-$phaseKey-result.json"
    $arguments = @(
        "-s", $DeviceSerial, "shell", "am", "instrument", "-w", "-r",
        "-e", "apiBaseUrl", $apiBaseUrl,
        "-e", "runId", $runId,
        "-e", "phase", $Phase
    )
    if ($Phase -eq "post-reboot") { $arguments += @("-e", "postRebootSequence", [string]$PostRebootSequence) }
    $arguments += @("-e", "class", "com.example.todoschooltime.e2eobserver.RebootRecoveryTest", "$observerTestApplicationId/androidx.test.runner.AndroidJUnitRunner")
    $nativeConfig = [pscustomobject]@{
        schemaVersion = 1
        runId = $runId
        executablePath = $script:AdbPath
        arguments = $arguments
        statusPath = $nativeStatusPath
    }
    Write-IntegrationJson $nativeConfig $nativeArgumentsPath
    $launcher = Join-Path $PSScriptRoot "Invoke-NativeCommand.ps1"
    $powershellPath = (Get-Command powershell.exe -ErrorAction Stop).Source
    $launcherArguments = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ('"{0}"' -f $launcher),
        "-ConfigPath", ('"{0}"' -f $nativeArgumentsPath)
    ) -join " "
    $process = Start-Process -FilePath $powershellPath -ArgumentList $launcherArguments -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $observerTimeoutMinutes = 8
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes($observerTimeoutMinutes)
    $stageDeadline = [DateTimeOffset]::UtcNow.AddSeconds(135)
    while (-not $process.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline) {
        if ($script:serverProcess.HasExited) { throw "The original Ticket 06 loopback fixture process exited during $Phase." }
        if ($Phase -eq "reboot-preparation" -and $null -eq $script:preRebootPreferenceState) {
            $stageLines = @(Invoke-SelectedAdb @("logcat", "-d", "-s", "Ticket06Stage:I", "*:S") |
                Where-Object { $_.Contains($runId) -and $_.Contains(":registered-before-reboot") })
            if ($stageLines.Count -gt 0) {
                $script:preRebootPreferenceState = Wait-ForPersistedScenarioPreferences
                $script:preRebootStageSeenAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
                Write-IntegrationJson ([pscustomobject]@{
                    runId = $runId
                    phase = $Phase
                    observerStage = "registered-before-reboot"
                    observerStageLog = [string]($stageLines | Select-Object -Last 1)
                    capturedAtUtc = $script:preRebootStageSeenAtUtc
                    preferences = $script:preRebootPreferenceState
                }) (Join-Path $artifactDirectory "preferences-before-reboot.json")
                if (-not $script:preRebootPreferenceState.registrationPersisted -or
                    -not $script:preRebootPreferenceState.intervalPersisted -or
                    -not $script:preRebootPreferenceState.targetsPersisted) {
                    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
                    $state = $script:preRebootPreferenceState
                    throw "The actual pre-reboot observer stage was reached, but filtered read-only preferences failed strict parsing (registration=$($state.registrationPersisted), interval=$($state.intervalPersisted), targets=$($state.targetsPersisted), rows=$(@($state.registrationChildren).Count)/$([bool]$state.intervalLine)/$([bool]$state.targetCountsLine))."
                }
                $script:preRebootPreferenceAck = Invoke-RestMethod -Method Post -Uri "$apiBaseUrl/__ticket06/ack-pre-reboot-preferences" `
                    -ContentType "application/json" -Body (@{ runId = $runId } | ConvertTo-Json -Compress) -TimeoutSec 5
                if (-not $script:preRebootPreferenceAck.ok -or
                    $script:preRebootPreferenceAck.runId -cne $runId -or
                    -not $script:preRebootPreferenceAck.readyForReboot) {
                    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
                    throw "Ticket 06 fixture did not acknowledge the exact pre-reboot preference capture."
                }
            } elseif ([DateTimeOffset]::UtcNow -ge $stageDeadline) {
                if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
                throw "The observer did not reach its registered-before-reboot stage within 135 seconds."
            }
        }
        Start-Sleep -Milliseconds 500
        $process.Refresh()
    }
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Ticket 06 $Phase observer exceeded its bounded $observerTimeoutMinutes-minute limit."
    }
    $process.WaitForExit()
    $process.Refresh()
    if (-not (Test-Path -LiteralPath $nativeStatusPath)) { throw "Ticket 06 $Phase did not produce a native adb exit sidecar." }
    $nativeStatus = Get-Content -LiteralPath $nativeStatusPath -Raw | ConvertFrom-Json -ErrorAction Stop
    if ($nativeStatus.runId -cne $runId -or $null -eq $nativeStatus.nativeExitCode -or [int]$nativeStatus.nativeExitCode -ne 0) {
        throw "Ticket 06 $Phase native adb transport failed or its exit code is unavailable."
    }
    if ($null -ne $process.ExitCode -and [int]$process.ExitCode -ne [int]$nativeStatus.nativeExitCode) {
        throw "Ticket 06 $Phase launcher and native adb exit codes disagree."
    }
    $transport = [pscustomobject]@{
        runId = $runId
        phase = $Phase
        nativeExitCode = [int]$nativeStatus.nativeExitCode
        nativeExitCapturedBy = "PowerShell LASTEXITCODE"
        launcherProcessExitCode = [int]$process.ExitCode
        launcherProcessId = [int]$process.Id
        startedAtUtc = [string]$nativeStatus.startedAtUtc
        completedAtUtc = [string]$nativeStatus.completedAtUtc
    }
    Write-IntegrationJson $transport $transportPath

    $filteredOutput = @(Select-String -LiteralPath $stdoutPath -Pattern 'OK \(1 test\)|INSTRUMENTATION_CODE:|INSTRUMENTATION_STATUS: class=.*RebootRecoveryTest|INSTRUMENTATION_STATUS: stack=|FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[0-9]' | ForEach-Object { $_.Line })
    $filteredText = $filteredOutput -join "`n"
    $filteredPath = Join-Path $artifactDirectory "observer-$phaseKey-filtered.txt"
    $filteredText | Set-Content -LiteralPath $filteredPath -Encoding utf8
    $resultLines = @(Invoke-SelectedAdb @("logcat", "-d", "-s", "Ticket06Result:I", "*:S") |
        Where-Object { $_.Contains($runId) -and $_.Contains("TICKET06_RESULT_CHUNK:") })
    $resultLines | Set-Content -LiteralPath $logLinesPath -Encoding utf8
    $resultJson = Get-IntegrationResultJson -LogLines $resultLines -RunId $runId -Scenario ticket06
    $testClass = "com.example.todoschooltime.e2eobserver.RebootRecoveryTest"
    $instrumentationOutcome = Assert-IntegrationInstrumentationOutcome `
        -TransportStatus $transport `
        -LauncherProcessExitCode $process.ExitCode `
        -Output $filteredText `
        -ResultJson $resultJson `
        -RunId $runId `
        -Scenario ticket06 `
        -ExpectedTestClass $testClass `
        -ExpectedPhase $Phase
    $result = ConvertFrom-IntegrationResult -Json $resultJson -RunId $runId -Scenario ticket06
    Write-IntegrationJson $instrumentationOutcome (Join-Path $artifactDirectory "observer-$phaseKey-outcome.json")
    Write-IntegrationJson $result $resultPath
    [void]$instrumentationResults.Add([pscustomobject]@{ phase = $Phase; resultPath = $resultPath; filteredOutputPath = $filteredPath; transportPath = $transportPath })
    return $result
}

function Wait-ForRebootReconnectAndRestoreReverse {
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
    $sawUnavailable = $false
    $reconnectedAt = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $deviceMap = Get-ConnectedDevices
        $state = if ($deviceMap.ContainsKey($script:DeviceSerial)) { [string]$deviceMap[$script:DeviceSerial] } else { "absent" }
        if ($state -ne "device") {
            $sawUnavailable = $true
        } elseif ($sawUnavailable) {
            $reconnectedAt = [DateTimeOffset]::UtcNow
            $mapping = Ensure-ReversePortMapping
            if ($mapping.status -eq "restored") { $script:reverseOwned = -not $script:originalReversePresent }
            return [pscustomobject]@{
                sawUnavailable = $true
                reconnectedAtUtc = $reconnectedAt.ToString("O")
                reverseStatus = if ($mapping.status -eq "reused" -and $script:originalReversePresent) { "reused-before-reboot" } else { "restored-after-reboot" }
                reverseMapping = $mapping.mapping
                reverseReadyAtUtc = $mapping.verifiedAtUtc
                reverseRestoreAttemptCount = [int]$mapping.attemptCount
            }
        }
        Start-Sleep -Seconds 1
    }
    throw "ADB did not report an offline/absent interval followed by a ready device reconnect within three minutes."
}

function Start-LoopbackFixture {
    Assert-ServerPortFree $serverPort
    $serverLog = Join-Path $artifactDirectory "fixture-server.stdout.txt"
    $serverErrorLog = Join-Path $artifactDirectory "fixture-server.stderr.txt"
    $serverScript = Join-Path $PSScriptRoot "server.py"
    $arguments = @("-u", ('"{0}"' -f $serverScript), "--host", "127.0.0.1", "--port", [string]$serverPort, "--run-id", $runId, "--scenario", "ticket06")
    $script:serverProcess = Start-Process -FilePath $serverPythonPath -ArgumentList $arguments -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrorLog
    $script:serverStarted = $true
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(15)
    do {
        if ($script:serverProcess.HasExited) { throw "Ticket 06 loopback fixture exited before it was ready; see $serverErrorLog" }
        try {
            $health = Invoke-RestMethod -Method Get -Uri "$apiBaseUrl/__ticket06/health" -TimeoutSec 2
            if ($health.ok -and $health.runId -ceq $runId -and $health.scenario -ceq "ticket06") { return }
        } catch { }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Ticket 06 loopback fixture did not become ready at $apiBaseUrl."
}

function Save-FinalFixtureSnapshot {
    if (-not $script:serverStarted) { return }
    try { Write-IntegrationJson (Get-FixtureState) (Join-Path $artifactDirectory "fixture-final.json") }
    catch { "Final fixture snapshot failed: $($_.Exception.Message)" | Set-Content -LiteralPath (Join-Path $artifactDirectory "fixture-final-error.txt") -Encoding utf8 }
}

try {
    $connected = Get-ConnectedDevices
    if ($connected[$DeviceSerial] -ne "device") { throw "Prepared device '$DeviceSerial' is not currently connected and ready." }

    $actualModel = Get-Property "ro.product.model"
    $actualManufacturer = Get-Property "ro.product.manufacturer"
    $actualApi = [int](Get-Property "ro.build.version.sdk")
    if ($actualModel -ne "iPlay50_mini_Pro" -or $actualManufacturer -ne "Alldocube" -or $actualApi -ne 33) {
        throw "The selected device differs from the read-only preflight: manufacturer='$actualManufacturer', model='$actualModel', API=$actualApi."
    }
    $preparedProductHash = [string]$prepared.debugApkSha256
    $preparedObserverHash = [string]$prepared.observerApkSha256
    $preparedObserverTestHash = [string]$prepared.observerTestApkSha256
    $currentProductApk = Join-Path $repositoryRoot "app/build/outputs/apk/debug/app-debug.apk"
    if (-not (Test-Path -LiteralPath $currentProductApk) -or
        $preparedProductHash -cne (Get-FileHash -LiteralPath $currentProductApk -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw "The prepared product APK does not match the current local build; prepare the observer scenario with this source before running persistence."
    }
    $debugHash = Get-InstalledPackageHash $debugApplicationId "installed-debug.apk"
    if ($debugHash -cne $preparedProductHash) { throw "Installed product debug APK differs from the prepared binary; no product install was attempted." }
    $observerHash = Get-InstalledPackageHash $observerApplicationId "installed-observer.apk"
    $observerTestHash = Get-InstalledPackageHash $observerTestApplicationId "installed-observer-test.apk"
    if ($observerHash -cne $preparedObserverHash -or $observerTestHash -cne $preparedObserverTestHash) {
        throw "Installed observer APKs differ from the prepared manifest; run -RefreshObserver first."
    }
    $releasePathBefore = Get-OptionalPackagePath $releaseApplicationId
    $deviceArtifact = [pscustomobject]@{
        serial = $DeviceSerial
        manufacturer = $actualManufacturer
        model = $actualModel
        apiLevel = $actualApi
        releasePackageInstalledBefore = ($null -ne $releasePathBefore)
        releasePackagePathBefore = $releasePathBefore
        preparedProductApkSha256 = $preparedProductHash
        installedProductApkSha256 = $debugHash
        preparedObserverApkSha256 = $preparedObserverHash
        installedObserverApkSha256 = $observerHash
        preparedObserverTestApkSha256 = $preparedObserverTestHash
        installedObserverTestApkSha256 = $observerTestHash
    }
    Write-IntegrationJson $deviceArtifact (Join-Path $artifactDirectory "device-apk-provenance.json")

    $powerBefore = (Invoke-SelectedAdb @("shell", "dumpsys", "power") | Out-String)
    $initialWakefulness = if ($powerBefore -match "mWakefulness=(?<value>[A-Za-z]+)") { $Matches.value } else { "unknown" }
    $initialServiceState = Get-DeviceMonitorState
    if (-not $initialServiceState.pid -or -not $initialServiceState.bound -or $initialServiceState.crashed) {
        throw "The real debug AccessibilityService is not bound before the test. No scenario API or product UI was attempted."
    }
    $lockBefore = Get-WindowLockState
    if ($lockBefore.needsUnlock) { throw "Device validation requires manual unlock; no unlock or settings operation was attempted." }

    Start-LoopbackFixture
    $reverseBefore = Get-ReversePortMapping
    $originalReversePresent = ($null -ne $reverseBefore)
    if ($reverseBefore) {
        $initialReverseStatus = "reused"
        $initialReverseMapping = $reverseBefore
        Write-Host "기존 adb reverse tcp:$serverPort 매핑을 재사용합니다."
    }
    else {
        $created = Ensure-ReversePortMapping
        $reverseOwned = $true
        $initialReverseStatus = "created"
        $initialReverseMapping = $created.mapping
    }
    Invoke-SelectedAdb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
    $wakeDeadline = [DateTimeOffset]::UtcNow.AddSeconds(8)
    $awake = $false
    do {
        $powerState = (Invoke-SelectedAdb @("shell", "dumpsys", "power") | Out-String)
        if ($powerState -match "mWakefulness=Awake") { $awake = $true; break }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $wakeDeadline)
    if (-not $awake) { throw "The device did not wake for the bounded UI test; it was not unlocked." }
    if ((Get-WindowLockState).needsUnlock) { throw "Device validation requires manual unlock; no unlock or settings operation was attempted." }

    $bootCountBefore = Get-BootCount
    $preResult = Invoke-ObserverPhase "reboot-preparation"
    $preState = Get-DeviceMonitorState
    if ($preState.pid -ne $initialServiceState.pid -or -not $preState.bound -or $preState.crashed) {
        throw "The real AccessibilityService PID or binding changed during reboot preparation."
    }
    $prePreferenceState = Wait-ForPersistedScenarioPreferences
    if (-not $preRebootPreferenceState) { throw "The observer did not capture the pre-reboot preference rows." }
    Assert-SameAppOwnedPreferenceRows -Before $preRebootPreferenceState -After $prePreferenceState -Transition "between reboot preparation and reboot"
    if (-not $prePreferenceState.registrationPersisted -or -not $prePreferenceState.intervalPersisted -or -not $prePreferenceState.targetsPersisted) {
        throw "The required child registration, explicitly saved interval, or target values were not stored in their expected app-owned preferences."
    }
    $preFixture = Get-FixtureState
    if (@($preFixture.requests).Count -lt 7) { throw "Reboot preparation did not finish one complete local fixture baseline before reboot." }
    Write-IntegrationJson $preFixture (Join-Path $artifactDirectory "fixture-before-reboot.json")
    $rebootRequestedAt = [DateTimeOffset]::UtcNow
    Invoke-SelectedAdb @("reboot") | Out-Null
    $rebootReturnedAt = [DateTimeOffset]::UtcNow
    $reconnect = Wait-ForRebootReconnectAndRestoreReverse
    $bootCompletedAt = Wait-ForBootComplete 180
    $bootCountAfter = Get-BootCount
    if ($bootCountAfter -ne ($bootCountBefore + 1)) {
        throw "Android boot_count changed from $bootCountBefore to $bootCountAfter; exactly one reboot must be observed."
    }
    $lockAfterBoot = Get-WindowLockState
    if ($lockAfterBoot.needsUnlock) { throw "Secure lock screen appeared after reboot; user interaction is required and no bypass was attempted." }
    $bootPower = (Invoke-SelectedAdb @("shell", "dumpsys", "power") | Out-String)
    $bootWakefulness = if ($bootPower -match "mWakefulness=(?<value>[A-Za-z]+)") { $Matches.value } else { "unknown" }
    if ($bootWakefulness -ne "Awake") {
        Invoke-SelectedAdb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
        $bootPower = (Invoke-SelectedAdb @("shell", "dumpsys", "power") | Out-String)
        $bootWakefulness = if ($bootPower -match "mWakefulness=(?<value>[A-Za-z]+)") { $Matches.value } else { "unknown" }
    }
    if ($bootWakefulness -ne "Awake") { throw "The device did not wake after reboot for the bounded observer; it was not unlocked." }
    $serviceAfterBoot = Wait-ForBoundMonitor 120
    $serviceReadyAt = [DateTimeOffset]::Parse([string]$serviceAfterBoot.observedAtUtc)
    $reverseReadyAt = [DateTimeOffset]::Parse([string]$reconnect.reverseReadyAtUtc)
    if ($reverseReadyAt -lt [DateTimeOffset]::Parse([string]$reconnect.reconnectedAtUtc) -or $reverseReadyAt -gt $serviceReadyAt) {
        throw "The loopback reverse mapping was not verified after device reconnect and before AccessibilityService readiness."
    }
    $postPreferenceState = Wait-ForPersistedScenarioPreferences
    if (-not $postPreferenceState.registrationPersisted -or -not $postPreferenceState.intervalPersisted -or -not $postPreferenceState.targetsPersisted) {
        throw "Filtered read-only preferences did not show the registration, interval, and targets after reboot."
    }
    $fixtureHealthAfterBoot = Invoke-RestMethod -Method Get -Uri "$apiBaseUrl/__ticket06/health" -TimeoutSec 5
    if (-not $fixtureHealthAfterBoot.ok -or $fixtureHealthAfterBoot.runId -cne $runId) { throw "The PC fixture was not reachable after boot; no app UI was opened." }
    $postSequence = [long]$preFixture.sequence
    $postResult = Invoke-ObserverPhase "post-reboot" $postSequence
    $serviceAfterPost = Get-DeviceMonitorState
    if ($serviceAfterPost.pid -ne $serviceAfterBoot.pid -or -not $serviceAfterPost.bound -or $serviceAfterPost.crashed) {
        throw "The real AccessibilityService lifecycle changed during the post-reboot external observation."
    }
    $postFixture = Get-FixtureState
    $postFixture = Assert-IntegrationFixtureSnapshot -Snapshot $postFixture -RunId $runId -Scenario ticket06
    Write-IntegrationJson $postFixture (Join-Path $artifactDirectory "fixture-after-reboot.json")

    Assert-SameAppOwnedPreferenceRows -Before $prePreferenceState -After $postPreferenceState -Transition "across reboot"
    $releasePathAfter = Get-OptionalPackagePath $releaseApplicationId
    if (($null -ne $releasePathAfter) -ne ($null -ne $releasePathBefore) -or $releasePathAfter -cne $releasePathBefore) {
        throw "The release package installation state changed during Ticket 06."
    }
    $deviceArtifact | Add-Member -NotePropertyName releasePackageInstalledAfter -NotePropertyValue ($null -ne $releasePathAfter)
    $deviceArtifact | Add-Member -NotePropertyName releasePackagePathAfter -NotePropertyValue $releasePathAfter
    $finalInstalledProductHash = Get-InstalledPackageHash $debugApplicationId "installed-debug-after-run.apk"
    if ($finalInstalledProductHash -cne $preparedProductHash) { throw "The product debug APK changed during Ticket 06." }

    $ticket06Evidence = [pscustomobject]@{
        schemaVersion = 1
        pass = $true
        scenario = "ticket06"
        runId = $runId
        gitCommit = $gitCommit
        device = $deviceArtifact
        fixtureServer = [pscustomobject]@{ processId = [int]$script:serverProcess.Id; runId = $runId; address = $apiBaseUrl }
        reboot = [pscustomobject]@{
            commandStartedAtUtc = $rebootRequestedAt.ToString("O")
            commandReturnedAtUtc = $rebootReturnedAt.ToString("O")
            bootCountBefore = [int]$bootCountBefore
            bootCountAfter = [int]$bootCountAfter
            bootCompletedAtUtc = $bootCompletedAt
            reconnectObserved = [bool]$reconnect.sawUnavailable
            reconnectedAtUtc = $reconnect.reconnectedAtUtc
            reverseMapping = $reconnect.reverseMapping
            reverseReadyAtUtc = $reconnect.reverseReadyAtUtc
            serviceReadyAtUtc = $serviceReadyAt.ToString("O")
            bootWakefulness = $bootWakefulness
            secureLockscreenNeedsUnlock = [bool]$lockAfterBoot.needsUnlock
            hostLaunchedProductActivity = $false
        }
        serviceState = [pscustomobject]@{
            beforePreparation = $initialServiceState
            beforeReboot = $preState
            afterBoot = $serviceAfterBoot
            afterObserver = $serviceAfterPost
        }
        preferences = [pscustomobject]@{
            registrationPersistedBeforeReboot = [bool]$prePreferenceState.registrationPersisted
            registrationPersistedAfterReboot = [bool]$postPreferenceState.registrationPersisted
            intervalPersistedBeforeReboot = [bool]$prePreferenceState.intervalPersisted
            intervalPersistedAfterReboot = [bool]$postPreferenceState.intervalPersisted
            targetsPersistedBeforeReboot = [bool]$prePreferenceState.targetsPersisted
            targetsPersistedAfterReboot = [bool]$postPreferenceState.targetsPersisted
            rowsBefore = $prePreferenceState
            rowsAfter = $postPreferenceState
            credentialFileRead = $false
        }
        fixture = [pscustomobject]@{ beforeReboot = $preFixture; afterReboot = $postFixture }
        results = @($preResult, $postResult)
        instrumentation = $instrumentationResults.ToArray()
    }
    $evidencePath = Join-Path $artifactDirectory "ticket06-reboot-recovery-evidence.json"
    if (Test-Path -LiteralPath $evidencePath) { throw "Ticket 06 evidence is immutable and already exists: $evidencePath" }
    Write-IntegrationJson $ticket06Evidence $evidencePath
    Write-Host "PASS Ticket 06 reboot recovery on Android API $actualApi ($actualManufacturer $actualModel). No force-stop was used."
    Write-Host "First post-boot check was silent; a later Hangeul completion updated the existing Android notification. Sound/HUN was not tested."
    Write-Host "Artifacts: $artifactDirectory"
} catch {
    if (Test-Path -LiteralPath $artifactDirectory) {
        $_ | Out-String | Set-Content -LiteralPath (Join-Path $artifactDirectory "failure.txt") -Encoding utf8
    }
    Write-Error $_
    throw
} finally {
    Save-FinalFixtureSnapshot
    if ($reverseOwned -and -not $originalReversePresent) {
        try {
            if ((Get-ConnectedDevices)[$DeviceSerial] -eq "device") { Invoke-SelectedAdb @("reverse", "--remove", "tcp:$serverPort") | Out-Null }
        } catch { Write-Warning "Could not remove the adb reverse mapping created by this run; it is recorded in the run artifacts." }
    }
    if ($script:serverProcess -and -not $script:serverProcess.HasExited) {
        Stop-Process -Id $script:serverProcess.Id -ErrorAction SilentlyContinue
    }
}
