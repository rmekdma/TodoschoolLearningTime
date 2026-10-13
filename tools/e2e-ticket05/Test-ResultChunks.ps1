$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "ResultChunks.psm1") -Force

$runId = "0123456789abcdef0123456789abcdef"
$payload = ([ordered]@{ runId = $runId; scenario = "ticket05"; text = ("A" * 4500) } | ConvertTo-Json -Compress)
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
$chunks = [System.Collections.Generic.List[string]]::new()
for ($start = 0; $start -lt $encoded.Length; $start += 3000) {
    [void]$chunks.Add($encoded.Substring($start, [Math]::Min(3000, $encoded.Length - $start)))
}
if ($chunks.Count -lt 2) { throw "Test fixture did not create multiple result chunks." }
$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot "Test-ResultChunks.ps1"), [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count) { throw "Ticket 05 chunk test script does not parse: $($errors[0].Message)" }
$lines = for ($index = 0; $index -lt $chunks.Count; $index++) {
    "I/Ticket05Result(123): TICKET05_RESULT_CHUNK:$runId`:$($index + 1)/$($chunks.Count):$($chunks[$index])"
}
$joined = Join-Ticket05ResultChunks -LogLines $lines -RunId $runId
if ($joined -cne $payload) { throw "Ticket 05 valid chunk sequence did not round-trip exactly." }

$duplicate = @($lines) + $lines[0]
$rejectedDuplicate = $false
try { Join-Ticket05ResultChunks -LogLines $duplicate -RunId $runId | Out-Null }
catch { $rejectedDuplicate = $true }
if (-not $rejectedDuplicate) { throw "Ticket 05 accepted a duplicate chunk." }

$missing = @($lines | Select-Object -Skip 1)
$rejectedMissing = $false
try { Join-Ticket05ResultChunks -LogLines $missing -RunId $runId | Out-Null }
catch { $rejectedMissing = $true }
if (-not $rejectedMissing) { throw "Ticket 05 accepted a missing chunk." }

$foreignRun = "fedcba9876543210fedcba9876543210"
$mixed = @($lines) + "I/Ticket05Result(124): TICKET05_RESULT_CHUNK:$foreignRun`:1/1:$([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"runId":"' + $foreignRun + '"}')))"
if ((Join-Ticket05ResultChunks -LogLines $mixed -RunId $runId) -cne $payload) {
    throw "A foreign-run chunk affected the current run result."
}

Write-Output "PASS Ticket05 result chunk strict parser (valid, duplicate, missing, foreign-run cases)."
