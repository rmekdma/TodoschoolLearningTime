param(
    [Parameter(Mandatory = $true)]
    [string]$ConfigPath
)

$ErrorActionPreference = "Stop"
$config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json -ErrorAction Stop
$startedAtUtc = [DateTimeOffset]::UtcNow
$nativeExitCode = $null
$launchError = $null

try {
    $nativeArguments = @($config.arguments | ForEach-Object { [string]$_ })
    & ([string]$config.executablePath) @nativeArguments
    if ($null -eq $LASTEXITCODE) {
        throw "The native command did not publish an exit code."
    }
    $nativeExitCode = [int]$LASTEXITCODE
}
catch {
    $launchError = $_.Exception.Message
}

$status = [pscustomobject]@{
    schemaVersion = 1
    runId = [string]$config.runId
    executablePath = [string]$config.executablePath
    nativeExitCode = $nativeExitCode
    launcherProcessId = $PID
    startedAtUtc = $startedAtUtc.ToString("O")
    completedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    launchError = $launchError
}
$statusJson = $status | ConvertTo-Json -Depth 4 -Compress
$encoding = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText([string]$config.statusPath, $statusJson, $encoding)

if ($null -eq $nativeExitCode) { exit 125 }
exit $nativeExitCode
