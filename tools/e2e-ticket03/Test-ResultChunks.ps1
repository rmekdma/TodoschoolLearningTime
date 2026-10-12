$ErrorActionPreference = "Stop"
$modulePath = Join-Path $PSScriptRoot "ResultChunks.psm1"
Import-Module $modulePath -Force
Import-Module (Join-Path $PSScriptRoot "ResultSchema.psm1") -Force

$runId = "a1b2c3d4e5f647f8a9b0c1d2e3f40506"
$expected = [ordered]@{
    runId = $runId
    subject = "테스트 아이"
    observations = @(
        [ordered]@{ packageName = "com.enuma.todohangeul"; path = "/v3/todoschool/report/daily/hangeul" }
    )
    largeEvidence = "가나다🚸" * 12000
} | ConvertTo-Json -Depth 8 -Compress
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($expected))
$chunkSize = 3000
$parts = [System.Collections.Generic.List[string]]::new()
for ($offset = 0; $offset -lt $encoded.Length; $offset += $chunkSize) {
    $parts.Add($encoded.Substring($offset, [Math]::Min($chunkSize, $encoded.Length - $offset)))
}
$lines = @(
    "10-12 15:00:00.000 100 101 I Ticket03E2E: TICKET03_RESULT_CHUNK:another-run:1/1:eyJydW5JZCI6ImFub3RoZXItcnVuIn0="
)
for ($index = 0; $index -lt $parts.Count; $index++) {
    $line = "10-12 15:00:01.000 100 101 I Ticket03E2E: TICKET03_RESULT_CHUNK:$runId`:$($index + 1)/$($parts.Count):$($parts[$index])"
    if ([Text.Encoding]::UTF8.GetByteCount($line) -ge 4096) { throw "A structured result chunk exceeds the logcat entry limit." }
    $lines += $line
}

$actual = Join-Ticket03ResultChunks -LogLines $lines -RunId $runId
if ($actual -cne $expected) { throw "Long UTF-8 result changed during chunk reconstruction." }

$missingChunkRejected = $false
try { Join-Ticket03ResultChunks -LogLines $lines[0..($lines.Count - 2)] -RunId $runId | Out-Null }
catch { $missingChunkRejected = $true }
if (-not $missingChunkRejected) { throw "A missing chunk was accepted." }

$duplicateChunkRejected = $false
try { Join-Ticket03ResultChunks -LogLines ($lines + $lines[1]) -RunId $runId | Out-Null }
catch { $duplicateChunkRejected = $true }
if (-not $duplicateChunkRejected) { throw "A duplicate chunk was accepted." }

$mixedRunRejected = $false
$mixedRunLines = @($lines | Where-Object { $_ -notmatch "TICKET03_RESULT_CHUNK:$runId`:" })
$mixedRunLines += "10-12 15:00:02.000 100 101 I Ticket03E2E: TICKET03_RESULT_CHUNK:$runId`:1/1:eyJydW5JZCI6Im90aGVyIn0="
try { Join-Ticket03ResultChunks -LogLines $mixedRunLines -RunId $runId | Out-Null }
catch { $mixedRunRejected = $true }
if (-not $mixedRunRejected) { throw "Chunks with a different payload runId were accepted." }

$inconsistentCountRejected = $false
$inconsistentCountLines = @($lines[0]) + @($lines[1..($lines.Count - 1)])
$inconsistentCountLines[-1] = $inconsistentCountLines[-1] -replace "/$($parts.Count):", "/$($parts.Count + 1):"
try { Join-Ticket03ResultChunks -LogLines $inconsistentCountLines -RunId $runId | Out-Null }
catch { $inconsistentCountRejected = $true }
if (-not $inconsistentCountRejected) { throw "Inconsistent chunk counts were accepted." }

$validSchemaFixture = [ordered]@{
    baselineRequests = @()
    pollRequests = @(
        [ordered]@{
            requestPath = "/v3/todoschool/report/daily/hangeul"
            foregroundPackagesObservedDuringTransition = @()
            foregroundEventsDuringTransition = @()
            requestsVisibleAtForegroundEventObservation = @()
            requestsDuringTransitionSequences = @()
            requestsDuringTransition = @()
            foregroundEventsObservedAtReport = @()
            foregroundPackagesObservedAtReport = @("com.enuma.todohangeul")
            foregroundEventsObservedAtApply = @()
            foregroundPackagesObservedAtApply = @()
            requestCycleSequences = @(1, 2, 3, 4, 5)
            requestCyclePaths = @("/v3/account/signin", "/v3/todoschool/report/daily/hangeul")
        }
    )
    observationWindows = @(
        [ordered]@{
            foregroundPackagesObservedDuringTransition = @()
            foregroundEventsDuringTransition = @()
            requestsDuringTransition = @()
            requestsDuringTransitionSequences = @()
            foregroundEventsObservedDuringWindow = @()
            foregroundPackagesObservedDuringWindow = @()
        }
    )
    foregroundEvents = @()
    stayAwake = [ordered]@{ foregroundEventsDuringAcquire = @() }
} | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$validSchemaFixture.observationWindows = @($validSchemaFixture.pollRequests[0]) + @($validSchemaFixture.observationWindows)
Assert-Ticket03ResultArrayShapes $validSchemaFixture

$malformedSchemaFixture = $validSchemaFixture | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$malformedSchemaFixture.pollRequests[0].requestsDuringTransitionSequences = "[]"
$stringifiedArrayRejected = $false
try { Assert-Ticket03ResultArrayShapes $malformedSchemaFixture }
catch { $stringifiedArrayRejected = $_.Exception.Message -like "*requestsDuringTransitionSequences*actual JSON array*" }
if (-not $stringifiedArrayRejected) { throw "A stringified empty array was accepted by the Ticket 03 schema validator." }

$malformedObservationWindow = $validSchemaFixture | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
$malformedObservationWindow.observationWindows[0].requestsDuringTransitionSequences = "[]"
$stringifiedWindowArrayRejected = $false
try { Assert-Ticket03ResultArrayShapes $malformedObservationWindow }
catch { $stringifiedWindowArrayRejected = $_.Exception.Message -match 'observationWindows\[0\].*poll record.*requestsDuringTransitionSequences.*actual JSON array' }
if (-not $stringifiedWindowArrayRejected) { throw "A stringified transition array in the observationWindows poll variant was accepted." }

Write-Output "PASS result chunk and schema validation (large UTF-8 payload, strict chunk identities, empty/nonempty arrays, stringified arrays rejected)"
