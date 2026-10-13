function Join-Ticket05ResultChunks {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$LogLines,
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-f]{32}$')]
        [string]$RunId
    )

    $marker = "TICKET05_RESULT_CHUNK:"
    $chunks = @{}
    $expectedCount = $null
    $sawTargetChunk = $false
    foreach ($line in $LogLines) {
        if (-not $line.Contains($marker)) { continue }
        $match = [regex]::Match(
            $line,
            'TICKET05_RESULT_CHUNK:(?<runId>[^:\s]+):(?<index>[0-9]+)/(?<count>[0-9]+):(?<payload>[A-Za-z0-9+/=]*)'
        )
        if (-not $match.Success) {
            if ($line.Contains("$marker$RunId`:")) {
                throw "A Ticket 05 result chunk for run '$RunId' has an invalid header or payload."
            }
            continue
        }
        if ($match.Groups['runId'].Value -cne $RunId) { continue }

        $sawTargetChunk = $true
        $index = [int]$match.Groups['index'].Value
        $count = [int]$match.Groups['count'].Value
        $payload = $match.Groups['payload'].Value
        if ($count -lt 1 -or $index -lt 1 -or $index -gt $count -or [string]::IsNullOrEmpty($payload)) {
            throw "Ticket 05 result chunks for run '$RunId' have an invalid index, count, or empty payload."
        }
        if ($null -ne $expectedCount -and $expectedCount -ne $count) {
            throw "Ticket 05 result chunks for run '$RunId' disagree about the total chunk count."
        }
        if ($chunks.ContainsKey($index)) {
            throw "Ticket 05 result chunk $index/$count for run '$RunId' is duplicated."
        }
        $expectedCount = $count
        $chunks[$index] = $payload
    }

    if (-not $sawTargetChunk -or $null -eq $expectedCount -or $chunks.Count -ne $expectedCount) {
        throw "Ticket 05 structured result for run '$RunId' is missing one or more chunks."
    }

    $encoded = ((1..$expectedCount | ForEach-Object { $chunks[$_] }) -join '')
    try {
        $bytes = [Convert]::FromBase64String($encoded)
        $utf8 = [Text.UTF8Encoding]::new($false, $true)
        $json = $utf8.GetString($bytes)
        $result = $json | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "Ticket 05 result chunks for run '$RunId' are not valid UTF-8 JSON: $($_.Exception.Message)"
    }
    if ($result.runId -cne $RunId) {
        throw "Ticket 05 result chunks for run '$RunId' contain payload for run '$($result.runId)'."
    }
    return $json
}

Export-ModuleMember -Function Join-Ticket05ResultChunks
