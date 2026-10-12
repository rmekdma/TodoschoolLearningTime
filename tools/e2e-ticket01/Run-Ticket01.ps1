[CmdletBinding()]
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot "config.json"),
    [string]$DeviceSerial,
    [ValidateSet("ticket01", "ticket02")]
    [string]$Scenario = "ticket01",
    [switch]$DeviceOnly,
    [switch]$PrepareDevice,
    [switch]$RunPrepared,
    [switch]$RefreshObserver
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$configExample = Join-Path $PSScriptRoot "config.example.json"
$configFile = if (Test-Path -LiteralPath $ConfigPath) { $ConfigPath } else { $configExample }
$config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json

if ($DeviceOnly -and ($PrepareDevice -or $RunPrepared -or $RefreshObserver)) {
    throw "Choose only one Ticket 02 phase: -PrepareDevice, -RunPrepared, -RefreshObserver, or legacy -DeviceOnly."
}
if ($PrepareDevice -and ($RunPrepared -or $RefreshObserver)) { throw "Choose only one Ticket 02 phase: -PrepareDevice, -RunPrepared, or -RefreshObserver." }
if ($RunPrepared -and $RefreshObserver) { throw "Choose either -RunPrepared or -RefreshObserver, not both." }
if ($Scenario -eq "ticket02" -and $DeviceOnly -and -not $PrepareDevice -and -not $RunPrepared -and -not $RefreshObserver) {
    $PrepareDevice = $true
}
if ($Scenario -eq "ticket02" -and -not $PrepareDevice -and -not $RunPrepared -and -not $RefreshObserver) {
    $RunPrepared = $true
}
if (($PrepareDevice -or $RunPrepared -or $RefreshObserver) -and $Scenario -ne "ticket02") {
    throw "-PrepareDevice, -RunPrepared, and -RefreshObserver are reserved for Ticket 02."
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
$e2eApplicationId = "com.example.todoschooltime.e2e"
$observerApplicationId = "com.example.todoschooltime.e2eobserver"
$observerTestApplicationId = "$observerApplicationId.test"
$observerAccessibilityLabel = "토도 학습 감시 · 로컬 테스트"
$runId = [Guid]::NewGuid().ToString("N")
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
    $output = & $adbPath -s $DeviceSerial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
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
        throw "Installed $PackageName does not match the prepared APK; refusing to instrument or reinstall it. Run Ticket 02 -PrepareDevice, then restore the service manually."
    }
    return $installedHash
}

function Get-DeviceMonitorState {
    $pidText = (Invoke-SelectedAdb @("shell", "pidof", $debugApplicationId) | Out-String).Trim()
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

function Invoke-Ticket02Observer([string]$ApiBaseUrl, [string]$RunId, [string]$InstrumentationComponent, [string]$TestClass) {
    $before = Get-DeviceMonitorState
    $beforePath = Join-Path $artifactDirectory "product-service-before-observer.txt"
    $before.accessibilityDump | Set-Content -LiteralPath $beforePath -Encoding utf8
    if (-not $before.pid -or -not $before.bound -or $before.crashed) {
        throw "Refusing to start Ticket 02: the real debug AccessibilityService is not currently bound. PID='$($before.pid)', bound=$($before.bound), crashed=$($before.crashed). No registration or fixture API request was attempted. See $beforePath"
    }

    $stdoutPath = Join-Path $artifactDirectory "observer-instrumentation.stdout.txt"
    $stderrPath = Join-Path $artifactDirectory "observer-instrumentation.stderr.txt"
    $samplePath = Join-Path $artifactDirectory "observer-lifecycle-samples.json"
    $argumentText = "-s $DeviceSerial shell am instrument -w -r -e apiBaseUrl $ApiBaseUrl -e runId $RunId -e class $TestClass $InstrumentationComponent"
    $process = Start-Process -FilePath $adbPath -ArgumentList $argumentText -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $samples = [System.Collections.Generic.List[object]]::new()
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(6)
    $failure = $null
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
        if ($state.pid -ne $before.pid -or -not $state.bound -or $state.crashed) {
            $failure = "The observed product lifecycle changed during external instrumentation: beforePID='$($before.pid)', currentPID='$($state.pid)', bound=$($state.bound), crashed=$($state.crashed)."
            break
        }
    }
    $samples | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $samplePath -Encoding utf8

    if ($failure -or -not $process.HasExited) {
        if (-not $failure) { $failure = "External observer exceeded its six-minute limit." }
        try { Invoke-SelectedAdb @("shell", "am", "force-stop", $observerTestApplicationId) | Out-Null } catch { }
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        throw "$failure The observer was stopped without altering the product app. PID/binding samples: $samplePath"
    }

    $process.WaitForExit()
    $after = Get-DeviceMonitorState
    $after.accessibilityDump | Set-Content -LiteralPath (Join-Path $artifactDirectory "product-service-after-observer.txt") -Encoding utf8
    if ($after.pid -ne $before.pid -or -not $after.bound -or $after.crashed) {
        throw "The product service PID or binding changed during external instrumentation: beforePID='$($before.pid)', afterPID='$($after.pid)', bound=$($after.bound), crashed=$($after.crashed)."
    }
    if ($process.ExitCode -ne 0) {
        throw "External observer adb process exited with code $($process.ExitCode). See $stdoutPath and $stderrPath"
    }
    $stdout = Get-Content -LiteralPath $stdoutPath -Raw
    $stderr = Get-Content -LiteralPath $stderrPath -Raw
    $observerResultLog = (Invoke-SelectedAdb @("logcat", "-d", "-s", "Ticket02E2E:I", "*:S") | Out-String)
    $structuredResult = $observerResultLog -split "`r?`n" |
        Where-Object { $_ -match "TICKET02_RESULT=" -and $_ -match [regex]::Escape($RunId) } |
        Select-Object -Last 1
    if ($structuredResult) {
        $structuredResult | Set-Content -LiteralPath (Join-Path $artifactDirectory "observer-structured-result.log") -Encoding utf8
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
        return Invoke-RestMethod -Uri "$apiBaseUrl/__ticket01/requests" -Method Get -TimeoutSec 3
    } catch {
        Write-Warning "Could not read the local server request log: $($_.Exception.Message)"
        return $null
    }
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
        Write-Host "Ticket 02 prepared-run mode: reusing the installed APKs without rebuilding or reinstalling the product app."
        $gradleTasks = @()
    } elseif ($PrepareDevice) {
        $gradleTasks = @(
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":app:assembleE2e",
            ":app:assembleRelease",
            ":app:lintDebug",
            ":app:lintRelease",
            ":e2eobserver:assembleDebug",
            ":e2eobserver:assembleDebugAndroidTest"
        )
        $gradleLogName = "ticket02-device-preparation-build.log"
    } elseif ($RefreshObserver) {
        $gradleTasks = @(
            ":e2eobserver:assembleDebug",
            ":e2eobserver:assembleDebugAndroidTest"
        )
        $gradleLogName = "ticket02-observer-refresh-build.log"
    } elseif ($DeviceOnly) {
        $gradleTasks = @(
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":app:assembleE2e",
            ":app:assembleRelease"
        )
        if ($Scenario -eq "ticket02") { $gradleTasks += @(":e2eobserver:assembleDebug", ":e2eobserver:assembleDebugAndroidTest") }
        $gradleLogName = "device-apk-build.log"
    } else {
        $gradleTasks = @(
            ":app:testDebugUnitTest",
            ":app:testE2eUnitTest",
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":app:assembleE2e",
            ":app:assembleRelease",
            ":app:lintDebug",
            ":app:lintRelease"
        )
        if ($Scenario -eq "ticket02") { $gradleTasks += @(":e2eobserver:assembleDebug", ":e2eobserver:assembleDebugAndroidTest") }
        $gradleLogName = "build-and-unit-tests.log"
    }
    if ($gradleTasks.Count -gt 0) { Invoke-Gradle $gradleTasks $gradleLogName }

    $debugApk = Join-Path $repositoryRoot "app\build\outputs\apk\debug\app-debug.apk"
    $testApk = Join-Path $repositoryRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    $releaseApk = Join-Path $repositoryRoot "app\build\outputs\apk\release\app-release.apk"
    $e2eApk = Join-Path $repositoryRoot "app\build\outputs\apk\e2e\app-e2e.apk"
    $observerApk = Join-Path $repositoryRoot "e2eobserver\build\outputs\apk\debug\e2eobserver-debug.apk"
    $observerTestApk = Join-Path $repositoryRoot "e2eobserver\build\outputs\apk\androidTest\debug\e2eobserver-debug-androidTest.apk"
    foreach ($apk in @($debugApk, $testApk, $releaseApk, $e2eApk)) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "Expected APK was not built: $apk" }
    }
    if (($Scenario -eq "ticket02" -or $PrepareDevice -or $RunPrepared) -and
        (-not (Test-Path -LiteralPath $observerApk) -or -not (Test-Path -LiteralPath $observerTestApk))) {
        throw "Ticket 02 observer APKs are missing; prepare them with -PrepareDevice first."
    }

    $debugBadging = (& $aaptPath dump badging $debugApk 2>&1) -join "`n"
    $testBadging = (& $aaptPath dump badging $testApk 2>&1) -join "`n"
    $observerBadging = if (Test-Path -LiteralPath $observerApk) { (& $aaptPath dump badging $observerApk 2>&1) -join "`n" } else { "" }
    $observerTestBadging = if (Test-Path -LiteralPath $observerTestApk) { (& $aaptPath dump badging $observerTestApk 2>&1) -join "`n" } else { "" }
    $releaseBadging = (& $aaptPath dump badging $releaseApk 2>&1) -join "`n"
    $e2eBadging = (& $aaptPath dump badging $e2eApk 2>&1) -join "`n"
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
    if ($e2eBadging -notmatch "package: name='$([regex]::Escape($e2eApplicationId))'") {
        throw "Existing E2E APK package identity changed."
    }
    if ($Scenario -eq "ticket02" -and
        ($observerBadging -notmatch "package: name='com.example.todoschooltime.e2eobserver'" -or
         $observerTestBadging -notmatch "package: name='com.example.todoschooltime.e2eobserver.test'")) {
        throw "Ticket 02 observer APK package identities are unexpected."
    }
    if ($Scenario -eq "ticket02") {
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
    $observerManifest = if ($Scenario -eq "ticket02") { (& $aaptPath dump xmltree $observerApk AndroidManifest.xml 2>&1) -join "`n" } else { "" }
    if ($releaseManifest -match "android:networkSecurityConfig") {
        throw "Release APK unexpectedly includes the debug network security configuration."
    }
    if ($releaseManifest -match "LearningMonitorDiagnosticsProvider|READ_MONITOR_DIAGNOSTICS|monitor-diagnostics") {
        throw "Release APK unexpectedly includes the debug-only diagnostics provider or permission."
    }
    if ($Scenario -eq "ticket02" -and
        ($debugManifest -notmatch "LearningMonitorDiagnosticsProvider" -or
         $debugManifest -notmatch "READ_MONITOR_DIAGNOSTICS" -or
         $debugManifest -notmatch "protectionLevel.*0x2")) {
        throw "Debug APK is missing its signature-protected read-only diagnostics provider."
    }
    if ($Scenario -eq "ticket02" -and
        ($observerManifest -notmatch "E: queries[\s\S]*?E: package[\s\S]*?com\.example\.todoschooltime\.debug" -or
         $observerManifest -notmatch "com\.enuma\.todohangeul" -or
         $observerManifest -notmatch "com\.enuma\.todoenglish" -or
         $observerManifest -notmatch "com\.enuma\.todomath" -or
         $observerManifest -notmatch "READ_MONITOR_DIAGNOSTICS" -or
         $observerManifest -notmatch "android:networkSecurityConfig" -or
         $observerManifest -notmatch "android:usesCleartextTraffic.*\(type 0x12\)0x0")) {
        throw "Ticket 02 observer APK must query the debug and subject packages, request its diagnostics signature permission, and deny cleartext by default."
    }
    if ($Scenario -eq "ticket02") {
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
    $e2ePermissions = (& $aaptPath dump permissions $e2eApk 2>&1) -join "`n"
    if ($e2ePermissions -match "android.permission.INTERNET") {
        throw "The existing fake E2E APK unexpectedly regained INTERNET permission."
    }
    if ($Scenario -eq "ticket02") {
        $observerPermissions = (& $aaptPath dump permissions $observerApk 2>&1) -join "`n"
        if ($observerPermissions -notmatch "android.permission.INTERNET") {
            throw "Ticket 02 observer APK needs INTERNET permission to call only the host-mapped loopback fixture."
        }
        $observerNetworkConfig = (& $aaptPath dump xmltree $observerApk "res/xml/ticket02_network_security_config.xml" 2>&1) -join "`n"
        if ($observerNetworkConfig -notmatch "E: base-config[\s\S]*?cleartextTrafficPermitted.*0x0" -or
            $observerNetworkConfig -notmatch "E: domain-config[\s\S]*?cleartextTrafficPermitted.*0xffffffff" -or
            $observerNetworkConfig -notmatch 'C: "127\.0\.0\.1"' -or
            $observerNetworkConfig -notmatch "includeSubdomains.*0x0") {
            throw "Ticket 02 observer APK must allow cleartext only to 127.0.0.1 for the temporary fixture."
        }
        Write-Host "PASS observer network access is limited to cleartext loopback fixture traffic."
    }
    & $pythonPath (Join-Path $PSScriptRoot "inspect_apks.py") `
        --debug-apk $debugApk --release-apk $releaseApk --api-base-url $apiBaseUrl
    if ($LASTEXITCODE -ne 0) { throw "Debug/release API URL artifact inspection failed." }
    Write-Host "PASS debug and release APK settings; existing fake E2E app ID and no-INTERNET boundary remain intact."

    $preparedDeviceDirectory = Join-Path $repositoryRoot "build\e2e-ticket02"
    $preparedDeviceManifestPath = Join-Path $preparedDeviceDirectory "prepared-device.json"
    if ($RefreshObserver) {
        if (-not (Test-Path -LiteralPath $preparedDeviceManifestPath)) {
            throw "No prepared Ticket 02 installation was found. Run -PrepareDevice once before -RefreshObserver."
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
        Write-Host "Ticket 02 observer APKs were refreshed; product APK was not reinstalled. Product service PID $($before.pid) remained bound. Observer installs updated=$observerNeedsInstall, observer-test install updated=$observerTestNeedsInstall."
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
        Write-Host "Ticket 02 APKs are installed and verified. Accessibility reconnect requires the human's settings action; no scenario ran and the local server was not started."
        Write-Host "Preparation manifest: $preparedDeviceManifestPath"
        return
    }

    if ($RunPrepared) {
        if (-not (Test-Path -LiteralPath $preparedDeviceManifestPath)) {
            throw "No prepared Ticket 02 installation was found. Run Run-Ticket02.ps1 -PrepareDevice, then restore accessibility manually."
        }
        $prepared = Get-Content -LiteralPath $preparedDeviceManifestPath -Raw | ConvertFrom-Json
        if ($prepared.applicationId -ne $debugApplicationId -or
            $prepared.observerApplicationId -ne $observerApplicationId -or
            $prepared.observerTestApplicationId -ne $observerTestApplicationId -or
            $prepared.deviceSerial -ne $DeviceSerial -or
            $prepared.apiBaseUrl -ne $apiBaseUrl) {
            throw "Prepared Ticket 02 APKs do not match the selected device or loopback server configuration. Re-run -PrepareDevice."
        }
        if ($prepared.debugApkSha256 -ne (Get-ApkSha256 $debugApk) -or
            $prepared.observerApkSha256 -ne (Get-ApkSha256 $observerApk) -or
            $prepared.observerTestApkSha256 -ne (Get-ApkSha256 $observerTestApk)) {
            throw "Ticket 02 APK build outputs changed after preparation. Re-run -PrepareDevice and reconnect accessibility manually."
        }
        Assert-InstalledApkEquals $debugApplicationId $debugApk "installed-debug.apk" | Out-Null
        Assert-InstalledApkEquals $observerApplicationId $observerApk "installed-observer.apk" | Out-Null
        Assert-InstalledApkEquals $observerTestApplicationId $observerTestApk "installed-observer-test.apk" | Out-Null
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
            $healthPath = if ($Scenario -eq "ticket02") { "/__ticket02/health" } else { "/__ticket01/health" }
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
        $instrumentationText = Invoke-Ticket02Observer $apiBaseUrl $runId $instrumentationComponent $testClass
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
        $testDescription = if ($Scenario -eq "ticket02") { "Ticket 02 external observer test" } else { "Ticket 01 server-screen test" }
        throw "$testDescription did not report one passing test. See $instrumentationLog"
    }

    $snapshot = Read-ServerRequests
    $snapshotPath = Join-Path $artifactDirectory "server-requests.json"
    if ($null -ne $snapshot) { $snapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $snapshotPath -Encoding utf8 }
    if ($Scenario -eq "ticket02") {
        Assert-Ticket02BaselineCompletion $snapshot $instrumentationText
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
    Write-Host "PASS $Scenario on $apiDescription. No notification or accessibility permissions were changed."
    Write-Host "Artifacts: $artifactDirectory"
} catch {
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $artifactDirectory "failure.txt") -Encoding utf8
    Write-Error $_
    throw
} finally {
    if ($serverStarted) {
        $finalSnapshot = Read-ServerRequests
        if ($null -ne $finalSnapshot) {
            $finalSnapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $artifactDirectory "server-requests-final.json") -Encoding utf8
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
