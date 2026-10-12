function Assert-Ticket03JsonArrayFields {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string[]]$Fields,
        [Parameter(Mandatory = $true)][string]$Context
    )

    foreach ($field in $Fields) {
        $property = $Value.PSObject.Properties[$field]
        if ($null -eq $property) {
            throw "Ticket 03 $Context is missing collection field '$field'."
        }
        if ($property.Value -isnot [System.Array]) {
            throw "Ticket 03 $Context field '$field' must be an actual JSON array."
        }
    }
}

function Assert-Ticket03ResultArrayShapes {
    param([Parameter(Mandatory = $true)]$Result)

    Assert-Ticket03JsonArrayFields $Result @("baselineRequests", "pollRequests", "observationWindows", "foregroundEvents") "result"
    $pollFields = @(
        "foregroundPackagesObservedDuringTransition", "foregroundEventsDuringTransition",
        "requestsVisibleAtForegroundEventObservation", "requestsDuringTransitionSequences",
        "requestsDuringTransition", "foregroundEventsObservedAtReport",
        "foregroundPackagesObservedAtReport", "foregroundEventsObservedAtApply",
        "foregroundPackagesObservedAtApply", "requestCycleSequences", "requestCyclePaths"
    )
    for ($index = 0; $index -lt $Result.pollRequests.Count; $index++) {
        Assert-Ticket03JsonArrayFields $Result.pollRequests[$index] $pollFields "pollRequests[$index]"
    }

    $windowFields = @(
        "foregroundPackagesObservedDuringTransition", "foregroundEventsDuringTransition",
        "requestsDuringTransition", "requestsDuringTransitionSequences",
        "foregroundEventsObservedDuringWindow", "foregroundPackagesObservedDuringWindow"
    )
    for ($index = 0; $index -lt $Result.observationWindows.Count; $index++) {
        $entry = $Result.observationWindows[$index]
        if ($null -ne $entry.PSObject.Properties["requestPath"]) {
            Assert-Ticket03JsonArrayFields $entry $pollFields "observationWindows[$index] poll record"
        } else {
            Assert-Ticket03JsonArrayFields $entry $windowFields "observationWindows[$index] no-poll window"
        }
    }

    if ($null -ne $Result.stayAwake) {
        Assert-Ticket03JsonArrayFields $Result.stayAwake @("foregroundEventsDuringAcquire") "stayAwake"
    }
}

Export-ModuleMember -Function Assert-Ticket03JsonArrayFields, Assert-Ticket03ResultArrayShapes
