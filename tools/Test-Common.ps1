$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')

function Assert-Rejected([scriptblock]$Action, [string]$Name) {
    try { & $Action | Out-Null }
    catch { return }
    throw "The shared integration runner accepted $Name."
}

function New-ChunkLines([string]$Scenario, [string]$RunId, [string]$Json, [int]$Width = 13) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Json))
    $parts = @()
    for ($offset = 0; $offset -lt $encoded.Length; $offset += $Width) {
        $length = [Math]::Min($Width, $encoded.Length - $offset)
        $parts += $encoded.Substring($offset, $length)
    }

    $marker = $Scenario.ToUpperInvariant() + '_RESULT_CHUNK:'
    $lines = New-Object 'System.Collections.Generic.List[string]'
    [void]$lines.Add("I/Observer: ${marker}abcdef0123456789abcdef0123456789`:1/1:e30=")
    for ($index = $parts.Count - 1; $index -ge 0; $index--) {
        [void]$lines.Add("I/Observer: ${marker}${RunId}:$($index + 1)/$($parts.Count):$($parts[$index])")
    }
    return $lines.ToArray()
}

$runId = '0123456789abcdef0123456789abcdef'
$foreignRunId = 'abcdef0123456789abcdef0123456789'
$testClass = 'com.example.todoschooltime.e2eobserver.ForegroundPollingTest'

# Every observer schema crosses the same chunk parser; input order is deliberately reversed.
foreach ($scenario in @('ticket03', 'ticket04', 'ticket05', 'ticket06')) {
    $json = '{"runId":"' + $runId + '","scenario":"' + $scenario + '","phase":"run"}'
    $lines = New-ChunkLines $scenario $runId $json
    if ((Join-IntegrationResultChunks -LogLines $lines -RunId $runId -Scenario $scenario) -cne $json -or
        (Get-IntegrationResultJson -LogLines $lines -RunId $runId -Scenario $scenario) -cne $json) {
        throw "The shared parser changed the $scenario result or rejected correctly indexed chunks arriving out of order."
    }
}

$scenario = 'ticket03'
$json = '{"runId":"' + $runId + '","scenario":"' + $scenario + '","phase":"run"}'
$validLines = New-ChunkLines $scenario $runId $json
Assert-Rejected { Join-IntegrationResultChunks -LogLines @() -RunId $runId -Scenario $scenario } 'an absent result'
Assert-Rejected { Join-IntegrationResultChunks -LogLines ($validLines + $validLines[1]) -RunId $runId -Scenario $scenario } 'a duplicate chunk'
Assert-Rejected { Join-IntegrationResultChunks -LogLines @($validLines | Select-Object -Skip 1 -First ($validLines.Count - 2)) -RunId $runId -Scenario $scenario } 'a missing chunk'
Assert-Rejected { Join-IntegrationResultChunks -LogLines @("TICKET03_RESULT_CHUNK:$runId`:bad") -RunId $runId -Scenario $scenario } 'a malformed current-run header'
Assert-Rejected { Join-IntegrationResultChunks -LogLines @("TICKET03_RESULT_CHUNK:$runId`:0/1:e30=") -RunId $runId -Scenario $scenario } 'an invalid chunk index'
Assert-Rejected { Join-IntegrationResultChunks -LogLines @("TICKET03_RESULT_CHUNK:$runId`:1/4097:e30=") -RunId $runId -Scenario $scenario } 'an excessive declared chunk count'
Assert-Rejected { Join-IntegrationResultChunks -LogLines @("TICKET03_RESULT_CHUNK:$runId`:1/1:e30=tail") -RunId $runId -Scenario $scenario } 'a payload with trailing data'
Assert-Rejected { Join-IntegrationResultChunks -LogLines @("TICKET03_RESULT_CHUNK:$runId`:1/2:e30=", "TICKET03_RESULT_CHUNK:$runId`:2/3:e30=") -RunId $runId -Scenario $scenario } 'chunks with inconsistent totals'
$mixedLine = "TICKET04_RESULT_CHUNK:$runId`:1/1:e30="
Assert-Rejected { Join-IntegrationResultChunks -LogLines ($validLines + $mixedLine) -RunId $runId -Scenario $scenario } 'a current-run chunk marked for another scenario'
$wrongRun = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"runId":"' + $foreignRunId + '","scenario":"ticket03"}'))
Assert-Rejected { Join-IntegrationResultChunks -LogLines @("TICKET03_RESULT_CHUNK:$runId`:1/1:$wrongRun") -RunId $runId -Scenario $scenario } 'a payload with another run ID'
$wrongScenario = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"runId":"' + $runId + '","scenario":"ticket04"}'))
Assert-Rejected { Join-IntegrationResultChunks -LogLines @("TICKET03_RESULT_CHUNK:$runId`:1/1:$wrongScenario") -RunId $runId -Scenario $scenario } 'a result for another scenario'
$badUtf8 = [Convert]::ToBase64String([byte[]]@(0xff, 0xfe))
Assert-Rejected { Join-IntegrationResultChunks -LogLines @("TICKET03_RESULT_CHUNK:$runId`:1/1:$badUtf8") -RunId $runId -Scenario $scenario } 'invalid UTF-8'
$badJson = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{'))
Assert-Rejected { Join-IntegrationResultChunks -LogLines @("TICKET03_RESULT_CHUNK:$runId`:1/1:$badJson") -RunId $runId -Scenario $scenario } 'malformed JSON'

$baselineJson = '{"runId":"' + $runId + '","apiBaseUrl":"http://127.0.0.1:18765"}'
$baselineLines = @()
$baselineLines += 'I/Baseline: TICKET02_RESULT={"runId":"' + $foreignRunId + '"}'
$baselineLines += 'I/Baseline: TICKET02_RESULT=' + $baselineJson
if ((Get-IntegrationResultJson -LogLines $baselineLines -RunId $runId -Scenario 'ticket02') -cne $baselineJson) {
    throw 'The shared parser failed to select the current Ticket 02 result.'
}
Assert-Rejected { Get-IntegrationResultJson -LogLines @('TICKET02_RESULT=' + $baselineJson, 'TICKET02_RESULT=' + $baselineJson) -RunId $runId -Scenario 'ticket02' } 'duplicate direct result records'
Assert-Rejected { Get-IntegrationResultJson -LogLines @('TICKET02_RESULT={"runId":"' + $runId + '"') -RunId $runId -Scenario 'ticket02' } 'malformed direct result JSON'

$fixture = [pscustomobject]@{ runId = $runId; scenario = 'ticket03'; inFlightRequests = @() }
if ((Assert-IntegrationFixtureSnapshot $fixture $runId 'ticket03').runId -cne $runId) { throw 'The fixture check changed a current-run identity.' }
$foreignFixture = [pscustomobject]@{ runId = $foreignRunId; scenario = 'ticket03' }
$unfinishedFixture = [pscustomobject]@{ runId = $runId; scenario = 'ticket03'; inFlightRequests = @([pscustomobject]@{ sequence = 1 }) }
Assert-Rejected { Assert-IntegrationFixtureSnapshot -Snapshot $foreignFixture -RunId $runId -Scenario 'ticket03' } 'a fixture from another run'
Assert-Rejected { Assert-IntegrationFixtureSnapshot -Snapshot $unfinishedFixture -RunId $runId -Scenario 'ticket03' } 'a fixture with an unfinished request'

$transport = [pscustomobject]@{ runId = $runId; nativeExitCode = 0 }
$instrumentation = "INSTRUMENTATION_STATUS: class=$testClass`nINSTRUMENTATION_STATUS_CODE: 0`nOK (1 test)`nINSTRUMENTATION_CODE: -1"
$accepted = Assert-IntegrationInstrumentationOutcome -TransportStatus $transport -LauncherProcessExitCode 0 `
    -Output $instrumentation -ResultJson $json -RunId $runId -Scenario $scenario -ExpectedTestClass $testClass
if ($accepted.nativeTransportExitCode -ne 0 -or $accepted.instrumentationTestCount -ne 1) { throw 'The shared outcome changed a successful run.' }
Assert-Rejected { Assert-IntegrationInstrumentationOutcome $transport 0 ($instrumentation -replace "`nINSTRUMENTATION_CODE: -1", '') $json $runId $scenario $testClass } 'a missing instrumentation completion code'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome $transport 0 "OK (1 test)`nINSTRUMENTATION_CODE: -1" $json $runId $scenario $testClass } 'a result without the expected test class'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome $transport 0 ($instrumentation -replace 'OK \(1 test\)', 'OK (2 tests)') $json $runId $scenario $testClass } 'a result with the wrong test count'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome $transport 0 ($instrumentation + "`nINSTRUMENTATION_STATUS_CODE: -2") $json $runId $scenario $testClass } 'a failed status followed by success'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome $transport 0 ($instrumentation + "`nFAILURES!!!") $json $runId $scenario $testClass } 'a failure banner followed by success'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome $transport 0 ($instrumentation -replace 'INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: -2') $json $runId $scenario $testClass } 'a failed instrumentation completion code'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome ([pscustomobject]@{ runId = $runId }) 0 $instrumentation $json $runId $scenario $testClass } 'an absent native exit code'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome ([pscustomobject]@{ runId = $runId; nativeExitCode = 'unknown' }) 0 $instrumentation $json $runId $scenario $testClass } 'an unknown native exit code'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome ([pscustomobject]@{ runId = $runId; nativeExitCode = $null }) 0 $instrumentation $json $runId $scenario $testClass } 'a null native exit code'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome ([pscustomobject]@{ runId = $runId; nativeExitCode = 17 }) 17 $instrumentation $json $runId $scenario $testClass } 'a nonzero native exit code'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome $transport 2 $instrumentation $json $runId $scenario $testClass } 'a launcher/native exit mismatch'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome ([pscustomobject]@{ runId = $foreignRunId; nativeExitCode = 0 }) 0 $instrumentation $json $runId $scenario $testClass } 'transport metadata from another run'
Assert-Rejected { Assert-IntegrationInstrumentationOutcome $transport 0 $instrumentation '{' $runId $scenario $testClass } 'malformed result JSON'

# Exercise the native sidecar contract with local cmd.exe children only; each wait has a hard bound.
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('common-native-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $powershellPath = (Get-Command powershell.exe -ErrorAction Stop).Source
    $launcherPath = Join-Path $PSScriptRoot 'Invoke-NativeCommand.ps1'
    $cmdPath = Join-Path $env:WINDIR 'System32\cmd.exe'
    foreach ($expectedExit in @(0, 7)) {
        $statusPath = Join-Path $testRoot "status-$expectedExit.json"
        $configPath = Join-Path $testRoot "config-$expectedExit.json"
        [pscustomobject]@{ runId = $runId; executablePath = $cmdPath; arguments = @('/d', '/c', "exit $expectedExit"); statusPath = $statusPath } |
            ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $configPath -Encoding utf8
        $start = New-Object System.Diagnostics.ProcessStartInfo
        $start.FileName = $powershellPath
        $start.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $launcherPath + '" -ConfigPath "' + $configPath + '"'
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $process = [Diagnostics.Process]::Start($start)
        if (-not $process.WaitForExit(10000)) {
            $process.Kill()
            throw 'The native command launcher exceeded its 10 second test bound.'
        }
        $processExit = $process.ExitCode
        $process.Dispose()
        $status = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json -ErrorAction Stop
        if ($processExit -ne $expectedExit -or $status.nativeExitCode -ne $expectedExit -or $status.runId -cne $runId) {
            throw "Native child exit $expectedExit was not preserved in the process and sidecar."
        }
    }

    $missingStatusPath = Join-Path $testRoot 'status-missing.json'
    $missingConfigPath = Join-Path $testRoot 'config-missing.json'
    [pscustomobject]@{ runId = $runId; executablePath = (Join-Path $testRoot 'missing.exe'); arguments = @(); statusPath = $missingStatusPath } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $missingConfigPath -Encoding utf8
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $powershellPath
    $start.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $launcherPath + '" -ConfigPath "' + $missingConfigPath + '"'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($start)
    if (-not $process.WaitForExit(10000)) {
        $process.Kill()
        throw 'The missing-native-command case exceeded its 10 second test bound.'
    }
    $processExit = $process.ExitCode
    $process.Dispose()
    $missingStatus = Get-Content -LiteralPath $missingStatusPath -Raw | ConvertFrom-Json -ErrorAction Stop
    if ($processExit -ne 125 -or $null -ne $missingStatus.nativeExitCode -or [string]::IsNullOrWhiteSpace($missingStatus.launchError)) {
        throw 'A missing native command was incorrectly reported as exit code zero.'
    }
}
finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedTestRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedTestRoot)) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}

Write-Output 'PASS shared result assembly, instrumentation validation, and native exit sidecars.'
