function ConvertFrom-IntegrationResult {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Json,
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-f]{32}$')]
        [string]$RunId,
        [Parameter(Mandatory = $true)]
        [ValidateSet('ticket02', 'ticket03', 'ticket04', 'ticket05', 'ticket06')]
        [string]$Scenario
    )

    try {
        $result = $Json | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "The $Scenario observer result is not valid JSON: $($_.Exception.Message)"
    }

    if ($null -eq $result -or $null -eq $result.PSObject.Properties['runId'] -or $result.runId -cne $RunId) {
        throw "The $Scenario observer result does not belong to run '$RunId'."
    }
    $scenarioProperty = $result.PSObject.Properties['scenario']
    if ($null -ne $scenarioProperty -and $scenarioProperty.Value -cne $Scenario) {
        throw "The observer result belongs to scenario '$($scenarioProperty.Value)', not '$Scenario'."
    }

    return $result
}

function Join-IntegrationResultChunks {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$LogLines,
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-f]{32}$')]
        [string]$RunId,
        [Parameter(Mandatory = $true)]
        [ValidateSet('ticket03', 'ticket04', 'ticket05', 'ticket06')]
        [string]$Scenario
    )

    $marker = $Scenario.ToUpperInvariant() + '_RESULT_CHUNK:'
    $markerPattern = 'TICKET0[3-6]_RESULT_CHUNK:'
    $chunkPattern = '^(?<runId>[^:\s]+):(?<index>[0-9]+)/(?<count>[0-9]+):(?<payload>[A-Za-z0-9+/=]+)$'
    $chunks = @{}
    $expectedCount = $null
    foreach ($rawLine in $LogLines) {
        $line = [string]$rawLine
        $markers = [regex]::Matches($line, $markerPattern)
        if ($markers.Count -eq 0) { continue }
        if ($markers.Count -gt 1) {
            throw "$Scenario result log for run '$RunId' contains multiple chunk markers on one line."
        }

        $lineMarker = $markers[0].Value
        $chunkText = $line.Substring($markers[0].Index + $lineMarker.Length)
        if ($lineMarker -cne $marker) {
            if ($chunkText.StartsWith("$RunId`:", [StringComparison]::Ordinal)) {
                throw "$Scenario result log for run '$RunId' contains a chunk marker for another scenario."
            }
            continue
        }

        $match = [regex]::Match($chunkText, $chunkPattern)
        if (-not $match.Success) {
            if ($chunkText.StartsWith($RunId, [StringComparison]::Ordinal)) {
                throw "A $Scenario result chunk for run '$RunId' has an invalid header or payload."
            }
            continue
        }
        if ($match.Groups['runId'].Value -cne $RunId) { continue }

        $index = 0
        $count = 0
        if (-not [int]::TryParse($match.Groups['index'].Value, [ref]$index) -or
            -not [int]::TryParse($match.Groups['count'].Value, [ref]$count)) {
            throw "$Scenario result chunks for run '$RunId' have an invalid index or total count."
        }
        $payload = $match.Groups['payload'].Value
        if ($count -lt 1 -or $count -gt 4096 -or $index -lt 1 -or $index -gt $count -or [string]::IsNullOrEmpty($payload)) {
            throw "$Scenario result chunks for run '$RunId' have an invalid index, count, or empty payload."
        }
        if ($null -ne $expectedCount -and $expectedCount -ne $count) {
            throw "$Scenario result chunks for run '$RunId' disagree about the total chunk count."
        }
        if ($chunks.ContainsKey($index)) {
            throw "$Scenario result chunk $index/$count for run '$RunId' is duplicated."
        }
        $expectedCount = $count
        $chunks[$index] = $payload
    }

    if ($null -eq $expectedCount -or $chunks.Count -ne $expectedCount) {
        throw "$Scenario structured result for run '$RunId' is missing one or more chunks."
    }

    $encoded = ((1..$expectedCount | ForEach-Object { $chunks[$_] }) -join '')
    try {
        $bytes = [Convert]::FromBase64String($encoded)
        $json = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
    }
    catch {
        throw "$Scenario result chunks for run '$RunId' are not valid UTF-8 JSON: $($_.Exception.Message)"
    }
    [void](ConvertFrom-IntegrationResult -Json $json -RunId $RunId -Scenario $Scenario)
    return $json
}

function Get-IntegrationResultJson {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$LogLines,
        [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{32}$')][string]$RunId,
        [Parameter(Mandatory = $true)][ValidateSet('ticket02', 'ticket03', 'ticket04', 'ticket05', 'ticket06')][string]$Scenario
    )

    if ($Scenario -ne 'ticket02') {
        return Join-IntegrationResultChunks -LogLines $LogLines -RunId $RunId -Scenario $Scenario
    }

    $marker = 'TICKET02_RESULT='
    $currentRunResults = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $LogLines) {
        $markerIndex = $line.IndexOf($marker, 0, [StringComparison]::Ordinal)
        if ($markerIndex -lt 0) { continue }
        $json = $line.Substring($markerIndex + $marker.Length).Trim()
        $runIdPattern = '"runId"\s*:\s*"' + [regex]::Escape($RunId) + '"'
        if ($json -notmatch $runIdPattern) { continue }
        [void]$currentRunResults.Add($json)
    }
    if ($currentRunResults.Count -ne 1) {
        throw "Ticket 02 run '$RunId' must emit exactly one structured result; found $($currentRunResults.Count)."
    }
    [void](ConvertFrom-IntegrationResult -Json $currentRunResults[0] -RunId $RunId -Scenario $Scenario)
    return $currentRunResults[0]
}

function Assert-IntegrationFixtureSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Snapshot,
        [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{32}$')][string]$RunId,
        [Parameter(Mandatory = $true)][ValidateSet('ticket01', 'ticket02', 'ticket03', 'ticket04', 'ticket05', 'ticket06')][string]$Scenario
    )

    if ($null -eq $Snapshot -or $null -eq $Snapshot.PSObject.Properties['runId'] -or $Snapshot.runId -cne $RunId) {
        throw "The $Scenario fixture snapshot is missing or belongs to another run."
    }
    if ($null -ne $Snapshot.PSObject.Properties['scenario'] -and $Snapshot.scenario -cne $Scenario) {
        throw "The fixture snapshot belongs to scenario '$($Snapshot.scenario)', not '$Scenario'."
    }
    $inFlight = $Snapshot.PSObject.Properties['inFlightRequests']
    if ($null -ne $inFlight -and @($inFlight.Value).Count -gt 0) {
        throw "The $Scenario fixture still has requests in flight at scenario completion."
    }
    return $Snapshot
}

function Assert-IntegrationInstrumentationOutcome {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$TransportStatus,
        [AllowNull()]$LauncherProcessExitCode,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Output,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ResultJson,
        [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{32}$')][string]$RunId,
        [Parameter(Mandatory = $true)][ValidateSet('ticket02', 'ticket03', 'ticket04', 'ticket05', 'ticket06')][string]$Scenario,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ExpectedTestClass,
        [string]$ExpectedPhase
    )

    $transportRunId = $TransportStatus.PSObject.Properties['runId']
    if ($null -eq $transportRunId -or $transportRunId.Value -cne $RunId) {
        throw "$Scenario native transport status belongs to another run."
    }
    $nativeExitProperty = $TransportStatus.PSObject.Properties['nativeExitCode']
    if ($null -eq $nativeExitProperty -or $null -eq $nativeExitProperty.Value) {
        throw "$Scenario native adb transport exit code is unavailable; it cannot be treated as zero."
    }
    $nativeExitCode = 0
    if (-not [int]::TryParse([string]$nativeExitProperty.Value, [ref]$nativeExitCode)) {
        throw "$Scenario native adb transport exit code is invalid; it cannot be treated as zero."
    }
    if ($nativeExitCode -ne 0) {
        throw "$Scenario native adb transport exited with code $nativeExitCode."
    }

    if ($null -ne $LauncherProcessExitCode) {
        $launcherExitCode = 0
        if (-not [int]::TryParse([string]$LauncherProcessExitCode, [ref]$launcherExitCode) -or $launcherExitCode -ne $nativeExitCode) {
            throw "$Scenario launcher process exit code disagrees with the native adb transport status."
        }
    }

    $successLines = [regex]::Matches($Output, '(?m)^\s*OK\s+\((?<count>[0-9]+)\s+tests?\)\s*$')
    $completionCodes = [regex]::Matches($Output, '(?m)^\s*INSTRUMENTATION_CODE:\s*(?<code>-?[0-9]+)\s*$')
    $expectedClassLine = '(?m)^\s*INSTRUMENTATION_STATUS:\s*class=' + [regex]::Escape($ExpectedTestClass) + '\s*$'
    $hasFailedStatus = $Output -match '(?m)^\s*INSTRUMENTATION_STATUS_CODE:\s*-[0-9]+\s*$'
    if ($successLines.Count -ne 1 -or $successLines[0].Groups['count'].Value -cne '1' -or
        $completionCodes.Count -ne 1 -or $completionCodes[0].Groups['code'].Value -cne '-1' -or
        $Output -cnotmatch $expectedClassLine -or
        $Output -match 'FAILURES!!!|INSTRUMENTATION_FAILED' -or $hasFailedStatus) {
        throw "$Scenario observer output did not prove one completed test of '$ExpectedTestClass' without a failed instrumentation status."
    }

    $result = ConvertFrom-IntegrationResult -Json $ResultJson -RunId $RunId -Scenario $Scenario
    if ($ExpectedPhase) {
        $phase = $result.PSObject.Properties['phase']
        if ($null -eq $phase -or $phase.Value -cne $ExpectedPhase) {
            throw "$Scenario structured result has the wrong or missing phase."
        }
    }
    return [pscustomobject]@{
        runId = $RunId
        scenario = $Scenario
        nativeTransportExitCode = [int]$TransportStatus.nativeExitCode
        launcherProcessExitCode = if ($null -eq $LauncherProcessExitCode) { $null } else { [int]$LauncherProcessExitCode }
        launcherProcessExitCodeObserved = ($null -ne $LauncherProcessExitCode)
        instrumentationTestCount = 1
        expectedTestClass = $ExpectedTestClass
        resultScenario = if ($null -eq $result.PSObject.Properties['scenario']) { $null } else { [string]$result.scenario }
    }
}

function Write-IntegrationJson {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Path
    )

    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $Value | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Invoke-IntegrationBuild {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][ValidateSet('screen', 'baseline', 'foreground', 'authentication', 'persistence')][string]$Scenario,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ConfigPath
    )

    $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json -ErrorAction Stop
    $javaHome = [string]$config.javaHome
    if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
        throw "Configured Java 21 runtime is missing: $javaHome"
    }

    $gradlePath = Join-Path $RepositoryRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $gradlePath)) { throw "Gradle wrapper is missing: $gradlePath" }
    $gradleUserHome = Join-Path $RepositoryRoot 'build\gradle-user-home'
    $androidUserHome = Join-Path $RepositoryRoot 'build\android-user-home'
    New-Item -ItemType Directory -Path $gradleUserHome, $androidUserHome -Force | Out-Null

    $sdkRoot = $env:ANDROID_HOME
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = $env:ANDROID_SDK_ROOT }
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        $adbCommand = Get-Command adb.exe -ErrorAction Stop
        $sdkRoot = Split-Path (Split-Path $adbCommand.Source -Parent) -Parent
    }

    $oldJavaHome = $env:JAVA_HOME
    $oldGradleUserHome = $env:GRADLE_USER_HOME
    $oldAndroidHome = $env:ANDROID_HOME
    $oldAndroidSdkRoot = $env:ANDROID_SDK_ROOT
    $oldAndroidUserHome = $env:ANDROID_USER_HOME
    try {
        $env:JAVA_HOME = $javaHome
        $env:GRADLE_USER_HOME = $gradleUserHome
        $env:ANDROID_HOME = $sdkRoot
        $env:ANDROID_SDK_ROOT = $sdkRoot
        $env:ANDROID_USER_HOME = $androidUserHome

        $tasks = @(
            ':app:testDebugUnitTest',
            ':app:assembleDebug',
            ':app:assembleDebugAndroidTest',
            ':app:assembleRelease',
            ':app:lintDebug',
            ':app:lintRelease'
        )
        if ($Scenario -ne 'screen') {
            $tasks += @(
                ':e2eobserver:testDebugUnitTest',
                ':e2eobserver:assembleDebug',
                ':e2eobserver:assembleDebugAndroidTest'
            )
        }
        $arguments = @(
            '--no-daemon', '--console=plain', '--max-workers=1',
            '--system-prop', "org.gradle.java.home=$javaHome",
            '--project-prop', 'kotlin.compiler.execution.strategy=in-process',
            '-PlearningApiBaseUrl=http://127.0.0.1:18765'
        ) + $tasks
        $logPath = Join-Path $RepositoryRoot "build\integration-build-$Scenario.log"
        Write-Host "Building scenario '$Scenario' without contacting a device. Log: $logPath"
        & $gradlePath @arguments 2>&1 | Tee-Object -FilePath $logPath
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) { throw "Gradle build failed with exit code $exitCode. See $logPath" }
    }
    finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:GRADLE_USER_HOME = $oldGradleUserHome
        $env:ANDROID_HOME = $oldAndroidHome
        $env:ANDROID_SDK_ROOT = $oldAndroidSdkRoot
        $env:ANDROID_USER_HOME = $oldAndroidUserHome
    }
}
