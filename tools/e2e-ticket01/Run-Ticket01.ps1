[CmdletBinding()]
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot "config.json"),
    [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$configExample = Join-Path $PSScriptRoot "config.example.json"
$configFile = if (Test-Path -LiteralPath $ConfigPath) { $ConfigPath } else { $configExample }
$config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json

if ($config.serverHost -ne "127.0.0.1") {
    throw "Ticket 01 only accepts serverHost 127.0.0.1 so test traffic cannot be sent to a remote API."
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
$runId = [Guid]::NewGuid().ToString("N")
$artifactDirectory = Join-Path $repositoryRoot "build\e2e-ticket01\$runId"
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
if (-not $aaptPath) {
    throw "Could not find aapt.exe under $buildToolsDirectory"
}

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
    if ($debugPackageBefore) { Write-Host "Existing isolated debug package will be updated without clearing its app data: $debugPackageBefore" }

    Invoke-Gradle @(
        ":app:testDebugUnitTest",
        ":app:testE2eUnitTest",
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
        ":app:assembleE2e",
        ":app:assembleRelease",
        ":app:lintDebug",
        ":app:lintRelease"
    ) "build-and-unit-tests.log"

    $debugApk = Join-Path $repositoryRoot "app\build\outputs\apk\debug\app-debug.apk"
    $testApk = Join-Path $repositoryRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    $releaseApk = Join-Path $repositoryRoot "app\build\outputs\apk\release\app-release.apk"
    $e2eApk = Join-Path $repositoryRoot "app\build\outputs\apk\e2e\app-e2e.apk"
    foreach ($apk in @($debugApk, $testApk, $releaseApk, $e2eApk)) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "Expected APK was not built: $apk" }
    }

    $debugBadging = (& $aaptPath dump badging $debugApk 2>&1) -join "`n"
    $testBadging = (& $aaptPath dump badging $testApk 2>&1) -join "`n"
    $releaseBadging = (& $aaptPath dump badging $releaseApk 2>&1) -join "`n"
    $e2eBadging = (& $aaptPath dump badging $e2eApk 2>&1) -join "`n"
    if ($debugBadging -notmatch "package: name='$([regex]::Escape($debugApplicationId))'") {
        throw "Built debug APK has an unexpected application ID; refusing device installation."
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
    $releaseManifest = (& $aaptPath dump xmltree $releaseApk AndroidManifest.xml 2>&1) -join "`n"
    $debugManifest = (& $aaptPath dump xmltree $debugApk AndroidManifest.xml 2>&1) -join "`n"
    if ($releaseManifest -match "android:networkSecurityConfig") {
        throw "Release APK unexpectedly includes the debug network security configuration."
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
    & $pythonPath (Join-Path $PSScriptRoot "inspect_apks.py") `
        --debug-apk $debugApk --release-apk $releaseApk --api-base-url $apiBaseUrl
    if ($LASTEXITCODE -ne 0) { throw "Debug/release API URL artifact inspection failed." }
    Write-Host "PASS debug and release APK settings; existing fake E2E app ID and no-INTERNET boundary remain intact."

    $serverScript = Join-Path $PSScriptRoot "server.py"
    $serverArguments = @(
        "-u",
        "`"$serverScript`"",
        "--host", "127.0.0.1",
        "--port", [string]$serverPort,
        "--run-id", $runId
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
            $health = Invoke-RestMethod -Uri "$apiBaseUrl/__ticket01/health" -TimeoutSec 2
            if ($health.ok -and $health.runId -eq $runId) { $serverReady = $true; break }
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

    Invoke-SelectedAdb @("install", "-r", $debugApk) | Out-Null
    Invoke-SelectedAdb @("install", "-r", $testApk) | Out-Null
    $instrumentationComponent = "$testApplicationId/androidx.test.runner.AndroidJUnitRunner"
    $instrumentationOutput = Invoke-SelectedAdb @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "expectedApiBaseUrl", $apiBaseUrl,
        "-e", "expectedRunId", $runId,
        $instrumentationComponent
    )
    $instrumentationLog = Join-Path $artifactDirectory "connected-device-test.log"
    $instrumentationOutput | Set-Content -LiteralPath $instrumentationLog -Encoding utf8
    $instrumentationText = $instrumentationOutput -join "`n"
    if ($instrumentationText -notmatch "OK \(1 test\)" -or $instrumentationText -match "FAILURES!!!|INSTRUMENTATION_FAILED") {
        throw "Android instrumentation did not report one passing screen test. See $instrumentationLog"
    }

    $snapshot = Read-ServerRequests
    $snapshotPath = Join-Path $artifactDirectory "server-requests.json"
    if ($null -ne $snapshot) { $snapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $snapshotPath -Encoding utf8 }
    Assert-ServerRoundTrip $snapshot
    Save-AppScreenshot

    $regularPackageAfter = Get-PackagePath $releaseApplicationId
    if ($regularPackageAfter -ne $regularPackageBefore) {
        throw "The installed production application changed during the isolated debug run."
    }
    $debugPackageAfter = Get-PackagePath $debugApplicationId
    if (-not $debugPackageAfter) { throw "The isolated debug app was not installed." }

    $apiDescription = if ($sdkVersion -eq 35) { "Android 15" } elseif ($sdkVersion -eq 33) { "Android 13 substitute" } else { "API $sdkVersion" }
    Write-Host "PASS server-to-screen round trip on $apiDescription. No notification or accessibility permissions were changed."
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
