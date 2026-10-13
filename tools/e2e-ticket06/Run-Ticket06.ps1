[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$DeviceSerial,
    [switch]$RunPrepared,
    [switch]$RefreshObserver,
    [ValidateSet("all", "task-removal", "reboot-recovery")]
    [string]$Case = "all"
)

$ErrorActionPreference = "Stop"
if ($RunPrepared -and $RefreshObserver) { throw "Choose either -RunPrepared or -RefreshObserver." }

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
if ($RefreshObserver) {
    $refreshRunner = Join-Path $PSScriptRoot "../e2e-ticket05/Run-Ticket05.ps1"
    $refreshArguments = @{ RefreshObserver = $true }
    if ($ConfigPath) { $refreshArguments.ConfigPath = $ConfigPath }
    if ($DeviceSerial) { $refreshArguments.DeviceSerial = $DeviceSerial }
    & $refreshRunner @refreshArguments
    if (-not $?) { exit 1 }
    exit 0
}

Import-Module (Join-Path $PSScriptRoot "RecoveryEvidence.psm1") -Force
if (-not $ConfigPath) {
    $candidate = Join-Path $repositoryRoot "tools/e2e-ticket01/config.json"
    $ConfigPath = if (Test-Path -LiteralPath $candidate) { $candidate } else { Join-Path $repositoryRoot "tools/e2e-ticket01/config.example.json" }
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
$hostState = $null
$prePreferenceState = $null
$preRemovalPreferenceState = $null
$preRemovalStageSeenAtUtc = $null
$preRemovalPreferenceAck = $null
$preRebootPreferenceState = $null
$preRebootStageSeenAtUtc = $null
$preRebootPreferenceAck = $null
$script:recentsDiagnosticCaptured = $false
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
                return [pscustomobject]@{ status = "reused"; mapping = $existing; restoredAtUtc = $null; attemptCount = $attempts }
            }
            Invoke-SelectedAdb @("reverse", "tcp:$serverPort", "tcp:$serverPort") | Out-Null
            $verified = Get-ReversePortMapping
            if ($verified) {
                return [pscustomobject]@{ status = "restored"; mapping = $verified; restoredAtUtc = [DateTimeOffset]::UtcNow.ToString("O"); attemptCount = $attempts }
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
            if ($state.pid -and $state.bound -and -not $state.crashed) { return $state }
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
    $snapshot = Invoke-RestMethod -Method Get -Uri "$apiBaseUrl/__ticket06/state" -TimeoutSec 5
    if ($snapshot.runId -cne $runId -or $snapshot.scenario -cne "ticket06") { throw "Fixture state belongs to another run or scenario." }
    return $snapshot
}

function Write-JsonFile($Value, [string]$Path) {
    $Value | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $Path -Encoding utf8
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
    $selectors = Get-Ticket06PreferenceSelectors -AccountKey $accountKey -ChildName $expectedChildName
    $intervalLines = @(Read-PreferenceMatches "learning_monitor.xml" $selectors.intervalPattern)
    $registrationLines = @(Read-PreferenceMatches "learning_monitor.xml" $selectors.registrationPattern 2)
    $targetLines = @(Read-PreferenceMatches "target_counts.xml" $selectors.targetsPattern)
    return ConvertFrom-Ticket06PreferenceRows -RegistrationRows $registrationLines -IntervalRows $intervalLines `
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

function Get-SourceProvenance {
    $sourceFiles = @(
        "tools/e2e-ticket01/server.py",
        "tools/e2e-ticket01/test_server.py",
        "tools/e2e-ticket06/RecoveryEvidence.psm1",
        "tools/e2e-ticket06/Test-RecoveryEvidence.ps1",
        "tools/e2e-ticket06/Run-Ticket06.ps1",
        "tools/e2e-ticket06/README.md",
        "e2eobserver/src/androidTest/java/com/example/todoschooltime/e2eobserver/Ticket06TaskRebootRecoveryTest.kt"
    )
    $hashes = [ordered]@{}
    foreach ($relativePath in $sourceFiles) {
        $path = Join-Path $repositoryRoot $relativePath
        if (-not (Test-Path -LiteralPath $path)) { throw "Ticket 06 provenance source is missing: $relativePath" }
        $hashes[$relativePath] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $headOutput = @(& git -C $repositoryRoot rev-parse HEAD 2>&1)
    $headExitCode = $LASTEXITCODE
    $head = (@($headOutput | ForEach-Object { [string]$_ } | Select-Object -First 1) -join "").Trim()
    if ($headExitCode -ne 0) { throw "Could not record the current source revision." }
    $statusOutput = @(& git -C $repositoryRoot status --porcelain=v1 --untracked-files=all 2>&1)
    $statusExitCode = $LASTEXITCODE
    $statusLines = @($statusOutput | ForEach-Object { [string]$_ })
    if ($statusExitCode -ne 0) { throw "Could not record current source dirty status." }
    return [pscustomobject]@{
        sourceRevision = $head
        workingTreeDirty = ($statusLines.Count -gt 0)
        workingTreeStatus = $statusLines
        sourceFileSha256 = $hashes
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    }
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
    $arguments += @("-e", "class", "com.example.todoschooltime.e2eobserver.Ticket06TaskRebootRecoveryTest", "$observerTestApplicationId/androidx.test.runner.AndroidJUnitRunner")
    $nativeConfig = [pscustomobject]@{
        schemaVersion = 1
        runId = $runId
        executablePath = $script:AdbPath
        arguments = $arguments
        statusPath = $nativeStatusPath
    }
    Write-JsonFile $nativeConfig $nativeArgumentsPath
    $launcher = Join-Path $repositoryRoot "tools/e2e-ticket01/Invoke-NativeCommand.ps1"
    $powershellPath = (Get-Command powershell.exe -ErrorAction Stop).Source
    $launcherArguments = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ('"{0}"' -f $launcher),
        "-ConfigPath", ('"{0}"' -f $nativeArgumentsPath)
    ) -join " "
    $process = Start-Process -FilePath $powershellPath -ArgumentList $launcherArguments -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(8)
    $stageDeadline = if ($Phase -in @("task-removal", "reboot-preparation")) { [DateTimeOffset]::UtcNow.AddSeconds(135) } else { [DateTimeOffset]::MaxValue }
    while (-not $process.HasExited -and [DateTimeOffset]::UtcNow -lt $deadline) {
        if ($Phase -eq "task-removal" -and -not $script:recentsDiagnosticCaptured) {
            $recentsStageLines = @(Invoke-SelectedAdb @("logcat", "-d", "-s", "Ticket06Stage:I", "*:S") |
                Where-Object { $_.Contains($runId) -and $_.Contains(":after-global-recents") })
            if ($recentsStageLines.Count -gt 0) {
                $recentsScreenshotPath = Join-Path $artifactDirectory "screen-after-global-recents.png"
                $screenshotCapture = & $pythonPath -c "import pathlib,subprocess,sys; p=subprocess.run([sys.argv[1],'-s',sys.argv[2],'exec-out','screencap','-p'],capture_output=True,check=False); pathlib.Path(sys.argv[3]).write_bytes(p.stdout); print('exit='+str(p.returncode),'bytes='+str(len(p.stdout)),'png='+str(p.stdout.startswith(bytes([137,80,78,71]))),'stderr='+p.stderr.decode(errors='replace')[-240:])" $script:AdbPath $script:DeviceSerial $recentsScreenshotPath
                $windowFocus = @(Invoke-SelectedAdb @("shell", "dumpsys", "window") |
                    Where-Object { $_ -match "mCurrentFocus|mFocusedApp|mTopFocusedDisplayId|mObscuringWindow" } |
                    Select-Object -First 12)
                Write-JsonFile ([pscustomobject]@{
                    runId = $runId
                    stage = "after-global-recents"
                    capturedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
                    stageLog = [string]($recentsStageLines | Select-Object -Last 1)
                    screenshotPath = $recentsScreenshotPath
                    screenshotCapture = @($screenshotCapture)
                    selectedWindowFocus = $windowFocus
                }) (Join-Path $artifactDirectory "recents-screen-diagnostic.json")
                $script:recentsDiagnosticCaptured = $true
            }
        }
        if ($Phase -eq "task-removal" -and $null -eq $script:preRemovalPreferenceState) {
            $stageLines = @(Invoke-SelectedAdb @("logcat", "-d", "-s", "Ticket06Stage:I", "*:S") |
                Where-Object { $_.Contains($runId) -and $_.Contains(":registered-before-task-removal") })
            if ($stageLines.Count -gt 0) {
                $script:preRemovalPreferenceState = Wait-ForPersistedScenarioPreferences
                $script:preRemovalStageSeenAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
                if (-not $script:preRemovalPreferenceState.registrationPersisted -or
                    -not $script:preRemovalPreferenceState.intervalPersisted -or
                    -not $script:preRemovalPreferenceState.targetsPersisted) {
                    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
                    throw "The actual pre-removal observer stage was reached, but filtered read-only preferences did not show registration, explicit interval, and targets."
                }
                Write-JsonFile ([pscustomobject]@{
                    runId = $runId
                    phase = $Phase
                    observerStage = "registered-before-task-removal"
                    observerStageLog = [string]($stageLines | Select-Object -Last 1)
                    capturedAtUtc = $script:preRemovalStageSeenAtUtc
                    preferences = $script:preRemovalPreferenceState
                }) (Join-Path $artifactDirectory "preferences-before-task-removal.json")
                $script:preRemovalPreferenceAck = Invoke-RestMethod -Method Post -Uri "$apiBaseUrl/__ticket06/ack-pre-removal-preferences" `
                    -ContentType "application/json" -Body (@{ runId = $runId } | ConvertTo-Json -Compress) -TimeoutSec 5
                if (-not $script:preRemovalPreferenceAck.ok -or
                    $script:preRemovalPreferenceAck.runId -cne $runId -or
                    -not $script:preRemovalPreferenceAck.readyForTaskRemoval) {
                    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
                    throw "Ticket 06 fixture did not acknowledge the exact pre-removal preference capture."
                }
            } elseif ([DateTimeOffset]::UtcNow -ge $stageDeadline) {
                if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
                throw "The observer did not reach its registered-before-task-removal stage within 135 seconds."
            }
        }
        if ($Phase -eq "reboot-preparation" -and $null -eq $script:preRebootPreferenceState) {
            $stageLines = @(Invoke-SelectedAdb @("logcat", "-d", "-s", "Ticket06Stage:I", "*:S") |
                Where-Object { $_.Contains($runId) -and $_.Contains(":registered-before-reboot") })
            if ($stageLines.Count -gt 0) {
                $script:preRebootPreferenceState = Wait-ForPersistedScenarioPreferences
                $script:preRebootStageSeenAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
                Write-JsonFile ([pscustomobject]@{
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
        throw "Ticket 06 $Phase observer exceeded its bounded eight-minute limit."
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
    Write-JsonFile $transport $transportPath

    $filteredOutput = @(Select-String -LiteralPath $stdoutPath -Pattern 'OK \(1 test\)|INSTRUMENTATION_CODE:|INSTRUMENTATION_STATUS: class=.*Ticket06TaskRebootRecoveryTest|INSTRUMENTATION_STATUS: stack=|FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[0-9]' | ForEach-Object { $_.Line })
    $filteredText = $filteredOutput -join "`n"
    $filteredPath = Join-Path $artifactDirectory "observer-$phaseKey-filtered.txt"
    $filteredText | Set-Content -LiteralPath $filteredPath -Encoding utf8
    if ($filteredText -notmatch 'OK \(1 test\)' -or $filteredText -notmatch 'INSTRUMENTATION_CODE:\s*-1' -or
        $filteredText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE:\s*-[0-9]') {
        throw "Ticket 06 $Phase observer did not prove exactly one completed instrumentation test. Filtered evidence: $filteredPath"
    }
    $resultLines = @(Invoke-SelectedAdb @("logcat", "-d", "-s", "Ticket06Result:I", "*:S") |
        Where-Object { $_.Contains($runId) -and $_.Contains("TICKET06_RESULT_CHUNK:") })
    $resultLines | Set-Content -LiteralPath $logLinesPath -Encoding utf8
    $resultJson = Join-Ticket06ResultChunks -LogLines $resultLines -RunId $runId
    $result = $resultJson | ConvertFrom-Json -ErrorAction Stop
    if ($result.scenario -cne "ticket06" -or $result.phase -cne $Phase) { throw "Ticket 06 observer returned the wrong scenario or phase." }
    Write-JsonFile $result $resultPath
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
                reverseReadyAtUtc = if ($mapping.restoredAtUtc) { $mapping.restoredAtUtc } else { $reconnectedAt.ToString("O") }
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
    $serverScript = Join-Path $repositoryRoot "tools/e2e-ticket01/server.py"
    $arguments = @("-u", ('"{0}"' -f $serverScript), "--host", "127.0.0.1", "--port", [string]$serverPort, "--run-id", $runId, "--scenario", "ticket06")
    $script:serverProcess = Start-Process -FilePath $serverPythonPath -ArgumentList $arguments -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrorLog
    $script:serverStarted = $true
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(15)
    do {
        if ($serverProcess.HasExited) { throw "Ticket 06 loopback fixture exited before it was ready; see $serverErrorLog" }
        try {
            $health = Invoke-RestMethod -Method Get -Uri "$apiBaseUrl/__ticket06/health" -TimeoutSec 2
            if ($health.ok -and $health.runId -ceq $runId -and $health.scenario -ceq "ticket06") { return }
        } catch { }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Ticket 06 loopback fixture did not become ready at $apiBaseUrl."
}

function Save-FinalFixtureSnapshot {
    if (-not $serverStarted) { return }
    try { Write-JsonFile (Get-FixtureState) (Join-Path $artifactDirectory "fixture-final.json") }
    catch { "Final fixture snapshot failed: $($_.Exception.Message)" | Set-Content -LiteralPath (Join-Path $artifactDirectory "fixture-final-error.txt") -Encoding utf8 }
}

try {
    $source = Get-SourceProvenance
    Write-JsonFile $source (Join-Path $artifactDirectory "source-provenance-before-run.json")
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
    if ($preparedProductHash -cne "6edf1fcc9e545f58ddc647d637000f373a54b109faa1cbe714febeb77a8b9fb3") {
        throw "The prepared product APK hash does not match the approved Ticket 02 artifact."
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
    Write-JsonFile $deviceArtifact (Join-Path $artifactDirectory "device-apk-provenance.json")

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

    if ($Case -eq "reboot-recovery") {
        $preState = $initialServiceState
        $preResult = Invoke-ObserverPhase "reboot-preparation"
        $serviceAfterTaskCase = $null
        $prePreferenceState = Wait-ForPersistedScenarioPreferences
        if (-not $preRebootPreferenceState -or
            $preRebootPreferenceState.registrationLine -cne $prePreferenceState.registrationLine -or
            $preRebootPreferenceState.intervalLine -cne $prePreferenceState.intervalLine -or
            $preRebootPreferenceState.targetCountsLine -cne $prePreferenceState.targetCountsLine) {
            throw "Filtered registration, target, or interval rows changed between reboot preparation and the immediate pre-reboot read."
        }
        if (-not $prePreferenceState.registrationPersisted -or -not $prePreferenceState.intervalPersisted -or -not $prePreferenceState.targetsPersisted) {
            throw "The required child registration, explicitly saved interval, or target values were not stored in their expected app-owned preferences."
        }
        $preFixture = Get-FixtureState
        if (@($preFixture.requests).Count -lt 7) { throw "Reboot preparation did not finish one complete local fixture baseline before reboot." }
        Write-JsonFile $preFixture (Join-Path $artifactDirectory "fixture-before-reboot.json")
    }
    else {
        $preState = Get-DeviceMonitorState
        if ($preState.pid -ne $initialServiceState.pid -or -not $preState.bound -or $preState.crashed) {
            throw "The real service lifecycle changed before the observer started."
        }
        $preResult = Invoke-ObserverPhase "task-removal"
        $serviceAfterTaskCase = Get-DeviceMonitorState
        if ($serviceAfterTaskCase.pid -ne $preState.pid -or -not $serviceAfterTaskCase.bound -or $serviceAfterTaskCase.crashed) {
            throw "The product AccessibilityService did not remain attached after actual task removal."
        }
        $prePreferenceState = Get-PersistedScenarioPreferences
        if (-not $preRemovalPreferenceState -or
            $preRemovalPreferenceState.registrationLine -cne $prePreferenceState.registrationLine -or
            $preRemovalPreferenceState.intervalLine -cne $prePreferenceState.intervalLine -or
            $preRemovalPreferenceState.targetCountsLine -cne $prePreferenceState.targetCountsLine) {
            throw "Filtered registration, target, or interval rows were not identical immediately before task removal and immediately after it."
        }
        if (-not $prePreferenceState.registrationPersisted -or -not $prePreferenceState.intervalPersisted -or -not $prePreferenceState.targetsPersisted) {
            throw "The required child registration, explicit one-minute interval, or target values were not stored in their expected app-owned preferences."
        }
        $preFixture = Get-FixtureState
        if ($Case -eq "task-removal") {
            Write-JsonFile $preFixture (Join-Path $artifactDirectory "fixture-after-task-removal.json")
            $finalInstalledProductHash = Get-InstalledPackageHash $debugApplicationId "installed-debug-after-run.apk"
            if ($finalInstalledProductHash -cne $preparedProductHash) { throw "The product debug APK changed during Ticket 06 task removal." }
            $taskCaseEvidence = [pscustomobject]@{
                schemaVersion = 1
                pass = $true
                scenario = "ticket06"
                case = "task-removal"
                runId = $runId
                device = $deviceArtifact
                source = $source
                taskRemoval = $preResult
                serviceState = [pscustomobject]@{ beforeTaskRemoval = $preState; afterTaskRemoval = $serviceAfterTaskCase }
                preferences = [pscustomobject]@{
                    registrationBeforeTaskRemoval = $preRemovalPreferenceState.registrationLine
                    registrationAfterTaskRemoval = $prePreferenceState.registrationLine
                    targetsBeforeTaskRemoval = $preRemovalPreferenceState.targetCountsLine
                    targetsAfterTaskRemoval = $prePreferenceState.targetCountsLine
                    intervalBeforeTaskRemoval = $preRemovalPreferenceState.intervalLine
                    intervalAfterTaskRemoval = $prePreferenceState.intervalLine
                    credentialFileRead = $false
                }
                fixture = $preFixture
                executionScope = [pscustomobject]@{ rebootPerformed = $false; forceStopUsed = [bool]$preResult.taskRemoval.forceStopUsed; actualTodoApiCalled = $false }
                resultCapturedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
            }
            Write-JsonFile $taskCaseEvidence (Join-Path $artifactDirectory "ticket06-task-removal-evidence.json")
            Write-Host "PASS Ticket 06 task-removal case on Android API $actualApi; reboot recovery was not run in this case."
            Write-Host "Artifacts: $artifactDirectory"
            return
        }
        Write-JsonFile $preFixture (Join-Path $artifactDirectory "fixture-before-reboot.json")
    }

    $rebootRequestedAt = [DateTimeOffset]::UtcNow
    $rebootOutput = Invoke-SelectedAdb @("reboot")
    $rebootReturnedAt = [DateTimeOffset]::UtcNow
    $reconnect = Wait-ForRebootReconnectAndRestoreReverse
    $bootCompletedAt = Wait-ForBootComplete 180
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
    $serviceReadyAt = [DateTimeOffset]::UtcNow
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
    Write-JsonFile $postFixture (Join-Path $artifactDirectory "fixture-after-reboot.json")

    if ($prePreferenceState.registrationLine -cne $postPreferenceState.registrationLine -or
        $prePreferenceState.intervalLine -cne $postPreferenceState.intervalLine -or
        $prePreferenceState.targetCountsLine -cne $postPreferenceState.targetCountsLine) {
        throw "Read-only saved registration, target, or interval preference lines changed across reboot."
    }
    $releasePathAfter = Get-OptionalPackagePath $releaseApplicationId
    if (($null -ne $releasePathAfter) -ne ($null -ne $releasePathBefore) -or $releasePathAfter -cne $releasePathBefore) {
        throw "The release package installation state changed during Ticket 06."
    }
    $deviceArtifact | Add-Member -NotePropertyName releasePackageInstalledAfter -NotePropertyValue ($null -ne $releasePathAfter)
    $deviceArtifact | Add-Member -NotePropertyName releasePackagePathAfter -NotePropertyValue $releasePathAfter
    $finalInstalledProductHash = Get-InstalledPackageHash $debugApplicationId "installed-debug-after-run.apk"
    if ($finalInstalledProductHash -cne $preparedProductHash) { throw "The product debug APK changed during Ticket 06." }

    $reverseRestoredBeforeServiceReady = [DateTimeOffset]::Parse($reconnect.reverseReadyAtUtc) -le $serviceReadyAt
    $reverseStatus = [string]$reconnect.reverseStatus
    $hostState = [pscustomobject]@{
        runId = $runId
        device = [pscustomobject]@{ apiLevel = $actualApi; manufacturer = $actualManufacturer; model = $actualModel; serial = $DeviceSerial }
        preRemovalPreferenceGate = [pscustomobject]@{
            stage = "registered-before-task-removal"
            runId = $runId
            capturedByHostAtUtc = $preRemovalStageSeenAtUtc
            fixtureAcknowledged = [bool]$preRemovalPreferenceAck.readyForTaskRemoval
            acknowledgementRunId = [string]$preRemovalPreferenceAck.runId
        }
        preRebootPreferenceGate = [pscustomobject]@{
            stage = if ($Case -eq "reboot-recovery") { "registered-before-reboot" } else { $null }
            runId = $runId
            capturedByHostAtUtc = $preRebootStageSeenAtUtc
            fixtureAcknowledged = [bool]$preRebootPreferenceAck.readyForReboot
            acknowledgementRunId = [string]$preRebootPreferenceAck.runId
        }
        persistedRegistrationBeforeReboot = [bool]$prePreferenceState.registrationPersisted
        persistedRegistrationAfterReboot = [bool]$postPreferenceState.registrationPersisted
        persistedIntervalBeforeReboot = [bool]$prePreferenceState.intervalPersisted
        persistedIntervalAfterReboot = [bool]$postPreferenceState.intervalPersisted
        persistedTargetsBeforeReboot = [bool]$prePreferenceState.targetsPersisted
        persistedTargetsAfterReboot = [bool]$postPreferenceState.targetsPersisted
        persistedRegistrationBeforeTaskRemoval = [bool]$preRemovalPreferenceState.registrationPersisted
        persistedIntervalBeforeTaskRemoval = [bool]$preRemovalPreferenceState.intervalPersisted
        persistedTargetsBeforeTaskRemoval = [bool]$preRemovalPreferenceState.targetsPersisted
        preferences = [pscustomobject]@{
            registrationLineBeforeTaskRemoval = $preRemovalPreferenceState.registrationLine
            registrationLineAfterTaskRemoval = $prePreferenceState.registrationLine
            targetCountsLineBeforeTaskRemoval = $preRemovalPreferenceState.targetCountsLine
            targetCountsLineAfterTaskRemoval = $prePreferenceState.targetCountsLine
            intervalLineBeforeTaskRemoval = $preRemovalPreferenceState.intervalLine
            intervalLineAfterTaskRemoval = $prePreferenceState.intervalLine
            registrationLineBeforeReboot = $prePreferenceState.registrationLine
            registrationLineAfterReboot = $postPreferenceState.registrationLine
            targetCountsLineBeforeReboot = $prePreferenceState.targetCountsLine
            targetCountsLineAfterReboot = $postPreferenceState.targetCountsLine
            intervalLineBeforeReboot = $prePreferenceState.intervalLine
            intervalLineAfterReboot = $postPreferenceState.intervalLine
            credentialFileRead = $false
        }
        reboot = [pscustomobject]@{
            commandIssuedOnce = $true
            commandStartedAtUtc = $rebootRequestedAt.ToString("O")
            commandReturnedAtUtc = $rebootReturnedAt.ToString("O")
            reconnectObserved = [bool]$reconnect.sawUnavailable
            reconnectedAtUtc = $reconnect.reconnectedAtUtc
            bootCompleted = $true
            bootCompletedAtUtc = $bootCompletedAt
            lockscreenNeedsUnlock = [bool]$lockAfterBoot.needsUnlock
            lockscreenEvidence = $lockAfterBoot.evidence
            reverseRestoredBeforeServiceReady = [bool]$reverseRestoredBeforeServiceReady
            reverseStatus = $reverseStatus
            reverseMapping = $reconnect.reverseMapping
            reverseReadyAtUtc = $reconnect.reverseReadyAtUtc
            reverseRestoreAttemptCount = [int]$reconnect.reverseRestoreAttemptCount
            serviceReadyAtUtc = $serviceReadyAt.ToString("O")
            servicePidBeforeReboot = $preState.pid
            servicePidAfterReboot = $serviceAfterBoot.pid
            bootWakefulness = $bootWakefulness
            controllerProductActivityLaunchesAfterReboot = 0
            nativeRebootOutputTail = @($rebootOutput | Select-Object -Last 3)
        }
        serviceState = [pscustomobject]@{
            beforeTaskRemoval = $preState
            afterTaskRemoval = $serviceAfterTaskCase
            beforeRebootPreparation = if ($Case -eq "reboot-recovery") { $initialServiceState } else { $null }
            afterBootBeforeObserver = $serviceAfterBoot
            afterPostObserver = $serviceAfterPost
        }
        power = [pscustomobject]@{ initialWakefulness = $initialWakefulness; wakeKeySent = $true; brightnessLockScopedToObserver = $true }
        fixture = [pscustomobject]@{
            address = $apiBaseUrl
            runId = $runId
            scenario = "ticket06"
            sequenceBeforeReboot = $postSequence
            source = "tools/e2e-ticket01/server.py"
            reverseStatusBeforeTaskRemoval = $initialReverseStatus
            reverseMappingBeforeTaskRemoval = $initialReverseMapping
        }
    }
    Write-JsonFile $hostState (Join-Path $artifactDirectory "host-lifecycle-and-preferences.json")

    if ($Case -eq "reboot-recovery") {
        $validatedSummary = Assert-Ticket06BootRecoveryEvidence -RunId $runId -Preparation $preResult -PostReboot $postResult -HostState $hostState
        $ticket06Evidence = [pscustomobject]@{
            schemaVersion = 1
            pass = $true
            scenario = "ticket06"
            case = "reboot-recovery"
            runId = $runId
            device = $deviceArtifact
            source = $source
            host = $hostState
            rebootPreparation = $preResult
            taskRemovalCase = "not-run-in-this-case"
            postReboot = $postResult
            summary = $validatedSummary
            fixture = [pscustomobject]@{ beforeReboot = $preFixture; afterReboot = $postFixture }
            instrumentation = $instrumentationResults.ToArray()
            executionScope = [pscustomobject]@{
                actualTodoApiCalled = $false
                productDebugApkInstalledOrReplaced = $false
                productTargetInstrumentationUsed = $false
                notificationOrAccessibilityPermissionChanged = $false
                actualSoundOrHeadsUpDeliveryTested = $false
                api35Claimed = $false
            }
            resultCapturedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
        }
        $evidencePath = Join-Path $artifactDirectory "ticket06-reboot-recovery-evidence.json"
    }
    else {
        $validatedSummary = Assert-Ticket06RecoveryEvidence -RunId $runId -PreReboot $preResult -PostReboot $postResult -HostState $hostState
        $ticket06Evidence = [pscustomobject]@{
            schemaVersion = 1
            pass = $true
            scenario = "ticket06"
            runId = $runId
            device = $deviceArtifact
            source = $source
            host = $hostState
            taskRemoval = $preResult
            postReboot = $postResult
            summary = $validatedSummary
            fixture = [pscustomobject]@{ beforeReboot = $preFixture; afterReboot = $postFixture }
            instrumentation = $instrumentationResults.ToArray()
            executionScope = [pscustomobject]@{
                actualTodoApiCalled = $false
                productDebugApkInstalledOrReplaced = $false
                productTargetInstrumentationUsed = $false
                notificationOrAccessibilityPermissionChanged = $false
                actualSoundOrHeadsUpDeliveryTested = $false
                api35Claimed = $false
            }
            resultCapturedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
        }
        $evidencePath = Join-Path $artifactDirectory "ticket06-evidence.json"
    }
    if (Test-Path -LiteralPath $evidencePath) { throw "Ticket 06 evidence is immutable and already exists: $evidencePath" }
    Write-JsonFile $ticket06Evidence $evidencePath
    if ($Case -eq "reboot-recovery") {
        Write-Host "PASS Ticket 06 reboot-recovery case on Android API $actualApi ($actualManufacturer $actualModel)."
        Write-Host "Task removal was not run in this separate reboot case; no force-stop was used."
    }
    else {
        Write-Host "PASS Ticket 06 task-removal and automatic reboot recovery on Android API $actualApi ($actualManufacturer $actualModel)."
        Write-Host "Task removal used the actual Recents observation plus the supported task command or a verified Recents swipe; no force-stop was used."
    }
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
    if ($serverProcess -and -not $serverProcess.HasExited) {
        Stop-Process -Id $serverProcess.Id -ErrorAction SilentlyContinue
    }
}
