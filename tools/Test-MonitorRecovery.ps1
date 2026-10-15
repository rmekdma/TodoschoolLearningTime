$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'MonitorRecovery.ps1')

$checks = 0
function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -cne $Expected) { throw $Message }
    $script:checks++
}
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    try { & $Action } catch { $script:checks++; return }
    throw $Message
}

$accountKey = 'dGlja2V0MDEtZmFrZS1hY2NvdW50'

$registrationRows = @(
    "<set name=`"registrations.$accountKey`">",
    '    <string>테스트 아이</string>',
    '</set>'
)
$targetRow = '<string name="테스트 아이">{' + "`n  &quot;한글&quot;: 6,`n  &quot;수학&quot;: 5,`n  &quot;영어&quot;: 8`n" + '}</string>'
$valid = ConvertFrom-AppOwnedPreferenceRows -RegistrationRows $registrationRows `
    -IntervalRows @("<string name=`"interval.$accountKey`">1</string>") -TargetRows @($targetRow) `
    -AccountKey $accountKey -ChildName '테스트 아이'
Assert-True ($valid.registrationPersisted -and $valid.intervalPersisted -and $valid.targetsPersisted) 'Valid multiline, XML-escaped preference rows must parse.'
Assert-Equal $valid.registrationChildren.Count 1 'Exactly one registered child must be observed.'
Assert-Equal $valid.intervalMinutes 1 'Only the explicitly saved one-minute interval is accepted.'
Assert-Equal $valid.targetCounts.english 8 'Target JSON must be decoded from XML text.'
Assert-Equal $valid.registrationLine ($registrationRows -join "`n") 'All three filtered registration rows must be preserved for exact comparison.'

$wrongChild = ConvertFrom-AppOwnedPreferenceRows -RegistrationRows @(
    "<set name=`"registrations.$accountKey`">", '    <string>다른 아이</string>', '</set>'
) -IntervalRows @("<int name=`"interval.$accountKey`" value=`"1`" />") -TargetRows @($targetRow) `
    -AccountKey $accountKey -ChildName '테스트 아이'
Assert-True (-not $wrongChild.registrationPersisted -and $wrongChild.intervalPersisted) 'A different child must not satisfy the exact registration check.'
$wrongRowCount = ConvertFrom-AppOwnedPreferenceRows -RegistrationRows @(
    "<set name=`"registrations.$accountKey`">", '    <string>테스트 아이</string>', '    <string>다른 아이</string>', '</set>'
) -IntervalRows @("<string name=`"interval.$accountKey`">1</string>") -TargetRows @($targetRow) `
    -AccountKey $accountKey -ChildName '테스트 아이'
Assert-True (-not $wrongRowCount.registrationPersisted) 'Registration parsing must require exactly the three filtered rows.'

$malformed = ConvertFrom-AppOwnedPreferenceRows -RegistrationRows $registrationRows `
    -IntervalRows @('<string name="interval.bad">1</string>') -TargetRows @('<string name="broken">') `
    -AccountKey $accountKey -ChildName '테스트 아이'
Assert-True (-not $malformed.intervalPersisted -and -not $malformed.targetsPersisted) 'Malformed or mis-keyed XML must fail closed.'
Assert-Throws { Assert-SameAppOwnedPreferenceRows -Before $valid -After $wrongChild -Transition 'across reboot' } 'Changed preference rows must be rejected.'

$same = ConvertFrom-AppOwnedPreferenceRows -RegistrationRows $registrationRows `
    -IntervalRows @("<string name=`"interval.$accountKey`">1</string>") -TargetRows @($targetRow) `
    -AccountKey $accountKey -ChildName '테스트 아이'
Assert-SameAppOwnedPreferenceRows -Before $valid -After $same -Transition 'across reboot'
$checks++

$mapping = New-VerifiedFixtureReverseMapping -Status restored -LocalPort 18765 `
    -Mapping 'emulator-5554 tcp:18765 tcp:18765' -AttemptCount 2
Assert-Equal $mapping.status 'restored' 'Verified loopback mapping status must be retained.'
Assert-Throws { New-VerifiedFixtureReverseMapping -Status restored -LocalPort 18765 -Mapping 'tcp:18765 tcp:18766' -AttemptCount 1 } 'A mismatched reverse mapping must be rejected.'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repositoryCommit = Get-CurrentRepositoryCommit -RepositoryRoot $repositoryRoot
Assert-True ($repositoryCommit -match '^(?:[0-9a-f]{40}|[0-9a-f]{64})$') 'The current repository commit must be captured as a full Git object ID.'
$missingRepository = Join-Path ([IO.Path]::GetTempPath()) ('ticket06-no-repository-' + [Guid]::NewGuid().ToString('N'))
$gitFailure = try { Get-CurrentRepositoryCommit -RepositoryRoot $missingRepository; '' } catch { $_.Exception.Message }
Assert-True ($gitFailure -match 'git rev-parse exit code [1-9][0-9]*' -and $gitFailure -match 'fatal:') 'A failed repository lookup must preserve its nonzero exit code and Git diagnostic.'

Write-Output "PASS Test-MonitorRecovery ($checks focused host checks)"
