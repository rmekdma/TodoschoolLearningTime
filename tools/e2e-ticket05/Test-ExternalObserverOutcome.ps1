$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "InstrumentationOutcome.psm1") -Force

$runId = "0123456789abcdef0123456789abcdef"
$goodOutputLines = @(
    "",
    "INSTRUMENTATION_STATUS_CODE: 0",
    "INSTRUMENTATION_STATUS: class=com.example.todoschooltime.e2eobserver.Ticket05HttpAuthRecoveryTest",
    "",
    "OK (1 test)",
    "INSTRUMENTATION_CODE: -1",
    ""
)
$goodOutput = @(Get-Ticket05InstrumentationOutput -Lines $goodOutputLines) -join "`n"
$goodResult = '{"runId":"0123456789abcdef0123456789abcdef","scenario":"ticket05"}'

function Assert-Rejected {
    param([scriptblock]$Action, [string]$Context)
    $rejected = $false
    try { & $Action }
    catch { $rejected = $true }
    if (-not $rejected) { throw "Ticket 05 instrumentation outcome accepted $Context." }
}

$nullStatus = [pscustomobject]@{ runId = $runId; nativeExitCode = $null }
Assert-Rejected { Assert-Ticket05InstrumentationOutcome $nullStatus 0 $goodOutput $goodResult $runId } "a missing native transport exit code"

$failedStatus = [pscustomobject]@{ runId = $runId; nativeExitCode = 17 }
Assert-Rejected { Assert-Ticket05InstrumentationOutcome $failedStatus 17 $goodOutput $goodResult $runId } "a nonzero native transport exit code"

$goodStatus = [pscustomobject]@{ runId = $runId; nativeExitCode = 0 }
Assert-Rejected { Assert-Ticket05InstrumentationOutcome $goodStatus $null "OK (1 test)" $goodResult $runId } "an incomplete instrumentation summary"
Assert-Rejected { Assert-Ticket05InstrumentationOutcome $goodStatus 2 $goodOutput $goodResult $runId } "a launcher/native exit-code disagreement"
Assert-Rejected { Assert-Ticket05InstrumentationOutcome $goodStatus 0 $goodOutput '{"runId":"0123456789abcdef0123456789abcdef","scenario":"ticket04"}' $runId } "a structured result from another scenario"

$conflictingOutputLines = @(
    "",
    "INSTRUMENTATION_STATUS_CODE: -2",
    "INSTRUMENTATION_STATUS: class=com.example.todoschooltime.e2eobserver.Ticket05HttpAuthRecoveryTest",
    "FAILURES!!!",
    "OK (1 test)",
    "INSTRUMENTATION_CODE: -1",
    ""
)
$filteredConflictingOutput = @(Get-Ticket05InstrumentationOutput -Lines $conflictingOutputLines) -join "`n"
if ($filteredConflictingOutput -notmatch 'INSTRUMENTATION_STATUS_CODE:\s*-2') {
    throw "The production Ticket 05 output filter dropped the instrumentation failure status code."
}
Assert-Rejected {
    Assert-Ticket05InstrumentationOutcome $goodStatus 0 $filteredConflictingOutput $goodResult $runId
} "a negative instrumentation status code followed by an OK marker"

$accepted = Assert-Ticket05InstrumentationOutcome $goodStatus $null $goodOutput $goodResult $runId
if ($accepted.nativeTransportExitCode -ne 0 -or $accepted.launcherProcessExitCodeObserved -or
    -not $accepted.instrumentationTestPassed -or $accepted.instrumentationTestCount -ne 1) {
    throw "Ticket 05 instrumentation outcome accepted without preserving native exit and test-result provenance."
}

$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ("ticket05-transport-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
try {
    $helperPath = Join-Path $PSScriptRoot "../e2e-ticket01/Invoke-NativeCommand.ps1"
    $powershellPath = Join-Path $PSHOME "powershell.exe"
    $commandPath = Join-Path $env:SystemRoot "System32/cmd.exe"
    foreach ($expectedCode in @(0, 17)) {
        $configPath = Join-Path $temporaryDirectory "config-$expectedCode.json"
        $statusPath = Join-Path $temporaryDirectory "status-$expectedCode.json"
        $config = [pscustomobject]@{
            runId = $runId
            executablePath = $commandPath
            arguments = @("/d", "/c", "exit $expectedCode")
            statusPath = $statusPath
        }
        $config | ConvertTo-Json -Depth 4 -Compress | Set-Content -LiteralPath $configPath -Encoding utf8
        & $powershellPath -NoProfile -ExecutionPolicy Bypass -File $helperPath -ConfigPath $configPath
        $helperExitCode = $LASTEXITCODE
        $status = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
        if ($helperExitCode -ne $expectedCode -or [int]$status.nativeExitCode -ne $expectedCode -or $status.runId -cne $runId) {
            throw "Native command helper did not preserve exact exit code $expectedCode."
        }
    }

    $startConfigPath = Join-Path $temporaryDirectory "start-process-config.json"
    $startStatusPath = Join-Path $temporaryDirectory "start-process-status.json"
    $startOutputPath = Join-Path $temporaryDirectory "start-process.stdout.txt"
    $startErrorPath = Join-Path $temporaryDirectory "start-process.stderr.txt"
    [pscustomobject]@{
        runId = $runId
        executablePath = $commandPath
        arguments = @("/d", "/c", "exit 0")
        statusPath = $startStatusPath
    } | ConvertTo-Json -Depth 4 -Compress | Set-Content -LiteralPath $startConfigPath -Encoding utf8
    $launcherArguments = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        ('"{0}"' -f $helperPath), "-ConfigPath", ('"{0}"' -f $startConfigPath)
    ) -join " "
    $launcher = Start-Process -FilePath $powershellPath -ArgumentList $launcherArguments -WindowStyle Hidden `
        -RedirectStandardOutput $startOutputPath -RedirectStandardError $startErrorPath -PassThru
    $launcher.WaitForExit()
    $launcher.Refresh()
    $launcherExitCode = $launcher.ExitCode
    $transportStatus = Get-Content -LiteralPath $startStatusPath -Raw | ConvertFrom-Json
    if ([int]$transportStatus.nativeExitCode -ne 0 -or
        ($null -ne $launcherExitCode -and [int]$launcherExitCode -ne 0)) {
        throw "Start-Process did not preserve the actual native command exit status."
    }
    $startAccepted = Assert-Ticket05InstrumentationOutcome `
        -TransportStatus $transportStatus `
        -LauncherProcessExitCode $launcherExitCode `
        -FilteredOutput $goodOutput `
        -StructuredResultJson $goodResult `
        -RunId $runId
    if ($startAccepted.nativeTransportExitCode -ne 0) {
        throw "Start-Process transport sidecar did not pass the actual native exit code to the outcome gate."
    }
}
finally {
    Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$syntaxFiles = @(
    "tools/e2e-ticket01/Invoke-NativeCommand.ps1",
    "tools/e2e-ticket01/Run-Ticket01.ps1",
    "tools/e2e-ticket05/InstrumentationOutcome.psm1",
    "tools/e2e-ticket05/HttpAuthRecoveryEvidence.psm1",
    "tools/e2e-ticket05/ResultChunks.psm1",
    "tools/e2e-ticket05/Run-Ticket05.ps1",
    "tools/e2e-ticket05/Test-ExternalObserverOutcome.ps1",
    "tools/e2e-ticket05/Test-HttpAuthRecoveryEvidence.ps1",
    "tools/e2e-ticket05/Test-ResultChunks.ps1"
)
foreach ($relativePath in $syntaxFiles) {
    $tokens = $null
    $parseErrors = $null
    $syntaxPath = Join-Path $repositoryRoot $relativePath
    [System.Management.Automation.Language.Parser]::ParseFile($syntaxPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
    if (@($parseErrors).Count -gt 0) {
        throw "PowerShell parser rejected $relativePath`: $($parseErrors[0].Message)"
    }
}

Write-Output "PASS Ticket05 strict transport/instrumentation outcome gate (missing, nonzero, mismatch, incomplete, valid zero, actual native exit helper, PowerShell syntax)."
