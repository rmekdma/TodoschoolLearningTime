function Test-Ticket04CaseBoundaryEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Case
    )

    $requiredProperties = @(
        "caseId",
        "actualPermissionEnabledAtBoundary",
        "requestAgeAtBoundaryMs",
        "requestBoundaryClassification",
        "requestStillInFlight",
        "clientSocketOpenAtPermissionBoundary",
        "sameProductPidAtBoundary",
        "processRestartExpected",
        "processRestartedAfterBoundary",
        "productPidBefore",
        "productPidAtBoundary",
        "productPidAfterReturn"
    )
    if (@($Case.PSObject.Properties.Name | Where-Object { $_ -in $requiredProperties }).Count -ne $requiredProperties.Count) {
        return $false
    }

    foreach ($booleanProperty in @(
        "actualPermissionEnabledAtBoundary",
        "requestStillInFlight",
        "clientSocketOpenAtPermissionBoundary",
        "sameProductPidAtBoundary",
        "processRestartExpected",
        "processRestartedAfterBoundary"
    )) {
        if ($Case.PSObject.Properties[$booleanProperty].Value -isnot [bool]) { return $false }
    }
    if ($Case.actualPermissionEnabledAtBoundary -ne $false) { return $false }

    $ageValue = $Case.PSObject.Properties["requestAgeAtBoundaryMs"].Value
    if ($ageValue -isnot [ValueType] -or $ageValue -is [bool]) { return $false }
    try {
        $requestAge = [int64]$ageValue
        if ([double]$ageValue -ne [double]$requestAge) { return $false }
    }
    catch {
        return $false
    }
    if ($requestAge -lt 0) { return $false }

    $expectedBoundaryClassification = if (-not $Case.requestStillInFlight) {
        "no-client-request-at-permission-boundary"
    } elseif (-not $Case.clientSocketOpenAtPermissionBoundary) {
        "client-socket-closed-at-permission-boundary"
    } elseif ($requestAge -lt 13500) {
        "client-request-in-flight-with-open-socket-within-evidence-window"
    } else {
        "client-request-in-flight-with-open-socket-outside-evidence-window"
    }
    if ($Case.requestBoundaryClassification -cne $expectedBoundaryClassification) { return $false }

    $pidBefore = [string]$Case.productPidBefore
    $pidAtBoundary = [string]$Case.productPidAtBoundary
    $pidAfterReturn = [string]$Case.productPidAfterReturn
    if ([string]::IsNullOrWhiteSpace($pidBefore) -or
        $Case.sameProductPidAtBoundary -ne (-not [string]::IsNullOrWhiteSpace($pidAtBoundary) -and $pidAtBoundary -eq $pidBefore)) {
        return $false
    }

    if ($Case.caseId -eq "notification-revocation") {
        return $Case.processRestartExpected -and
            $Case.processRestartedAfterBoundary -and
            -not [string]::IsNullOrWhiteSpace($pidAfterReturn) -and
            $pidAfterReturn -ne $pidBefore
    }

    if ($Case.caseId -eq "accessibility-revocation") {
        return -not $Case.processRestartExpected -and
            $Case.requestStillInFlight -and
            $Case.clientSocketOpenAtPermissionBoundary -and
            $Case.sameProductPidAtBoundary -and
            -not $Case.processRestartedAfterBoundary -and
            $pidAtBoundary -eq $pidBefore -and
            $pidAfterReturn -eq $pidBefore -and
            $Case.requestBoundaryClassification -eq "client-request-in-flight-with-open-socket-within-evidence-window" -and
            $requestAge -ge 0 -and $requestAge -lt 13500
    }

    return $false
}

Export-ModuleMember -Function Test-Ticket04CaseBoundaryEvidence
