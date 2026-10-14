function Get-PersistencePreferenceSelectors {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$AccountKey,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ChildName
    )

    [pscustomobject]@{
        registrationPattern = "registrations.$AccountKey"
        intervalPattern = "interval.$AccountKey"
        targetsPattern = $ChildName.Replace(' ', '\ ')
    }
}

function ConvertFrom-AppOwnedPreferenceRows {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$RegistrationRows,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$IntervalRows,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$TargetRows,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$AccountKey,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ChildName
    )

    $registrationChildren = @()
    if ($RegistrationRows.Count -eq 3) {
        try {
            $xml = [xml]('<map>' + ($RegistrationRows -join "`n") + '</map>')
            $set = $xml.DocumentElement.SelectSingleNode("set[@name='registrations.$AccountKey']")
            if ($null -ne $set) {
                $registrationChildren = @($set.SelectNodes('string') | ForEach-Object { [string]$_.InnerText })
            }
        } catch { }
    }

    $intervalMinutes = $null
    if ($IntervalRows.Count -eq 1) {
        try {
            $row = ([xml]$IntervalRows[0].Trim()).DocumentElement
            if ($row.GetAttribute('name') -ceq "interval.$AccountKey") {
                $raw = switch -CaseSensitive ($row.LocalName) {
                    'string' { [string]$row.InnerText; break }
                    'int' { [string]$row.GetAttribute('value'); break }
                    'long' { [string]$row.GetAttribute('value'); break }
                    default { '' }
                }
                $parsed = 0
                if ([int]::TryParse($raw, [ref]$parsed)) { $intervalMinutes = $parsed }
            }
        } catch { }
    }

    $targetCounts = $null
    if ($TargetRows.Count -eq 1) {
        try {
            $row = ([xml]$TargetRows[0].Trim()).DocumentElement
            if ($row.LocalName -ceq 'string' -and $row.GetAttribute('name') -ceq $ChildName) {
                $counts = ConvertFrom-Json -InputObject $row.InnerText -ErrorAction Stop
                $targetCounts = [pscustomobject]@{
                    hangeul = [int]$counts.PSObject.Properties['한글'].Value
                    math = [int]$counts.PSObject.Properties['수학'].Value
                    english = [int]$counts.PSObject.Properties['영어'].Value
                }
            }
        } catch { }
    }

    [pscustomobject]@{
        registrationLine = if ($RegistrationRows.Count -gt 0) { $RegistrationRows -join "`n" } else { $null }
        intervalLine = if ($IntervalRows.Count -eq 1) { [string]$IntervalRows[0] } else { $null }
        targetCountsLine = if ($TargetRows.Count -eq 1) { [string]$TargetRows[0] } else { $null }
        registrationChildren = $registrationChildren
        registrationPersisted = ($registrationChildren.Count -eq 1 -and $registrationChildren[0] -ceq $ChildName)
        intervalMinutes = $intervalMinutes
        intervalPersisted = ($null -ne $intervalMinutes -and $intervalMinutes -eq 1)
        targetCounts = $targetCounts
        targetsPersisted = ($null -ne $targetCounts -and $targetCounts.hangeul -eq 6 -and $targetCounts.math -eq 5 -and $targetCounts.english -eq 8)
        credentialFileRead = $false
    }
}

function Assert-SameAppOwnedPreferenceRows {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Before,
        [Parameter(Mandatory = $true)]$After,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Transition
    )

    foreach ($name in @('registrationLine', 'intervalLine', 'targetCountsLine')) {
        if ($Before.$name -cne $After.$name) {
            throw "The filtered app-owned $name changed $Transition."
        }
    }
}

function New-VerifiedFixtureReverseMapping {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][ValidateSet('reused', 'restored')][string]$Status,
        [Parameter(Mandatory = $true)][ValidateRange(1024, 65535)][int]$LocalPort,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Mapping,
        [Parameter(Mandatory = $true)][ValidateRange(1, 1000)][int]$AttemptCount,
        [DateTimeOffset]$VerifiedAtUtc = [DateTimeOffset]::UtcNow
    )

    if ($Mapping -notmatch "(?:^|\s)tcp:$LocalPort\s+tcp:$LocalPort(?:\s|$)") {
        throw "The verified reverse mapping does not match local tcp:$LocalPort."
    }
    [pscustomobject]@{
        status = $Status
        mapping = $Mapping
        verifiedAtUtc = $VerifiedAtUtc.ToString('O')
        attemptCount = $AttemptCount
    }
}
