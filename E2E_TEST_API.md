# ADB E2E test API

The `e2e` build type installs as `com.example.todoschooltime.e2e`. It uses the production `LearningMonitorEngine`, runtime adapter, stores, Activity, accessibility service, and Android notification sink. Its Application supplies a fake report repository, a simulated Seoul date input, and a notification audit decorator. The E2E manifest removes `INTERNET`; it cannot call the TodoSchool server. The ordinary `debug` and `release` variants do not package the ADB receiver or E2E command classes and keep the production repository.

Build the installable variant with `:app:assembleE2e`. The explicit receiver action and component are:

```text
action:    com.example.todoschooltime.e2e.COMMAND
component: com.example.todoschooltime.e2e/com.example.todoschooltime.E2eCommandReceiver
extra:     request_b64=<unpadded base64url of UTF-8 JSON>
```

The receiver requires the Android `DUMP` permission, accepts an explicit ordered broadcast, runs commands off the main thread, and finishes within an eight second deadline. It returns result code `0` for success and `1` for an error. The result data is `E2E1:` followed by unpadded base64url-encoded UTF-8 JSON so Korean state strings survive PowerShell and `adb` shell quoting. Error responses have this shape:

The timeout bounds only the ADB acknowledgement. If a state-changing command times out after it was queued, it may still finish in the engine; read `getState` before retrying it.

```json
{"version":1,"ok":false,"error":{"code":"invalid_request","message":"unknown command"}}
```

## PowerShell helper

```powershell
function Invoke-E2eCommand([string]$Json) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Json)
    $encoded = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $output = & adb shell am broadcast `
        --include-stopped-packages `
        --receiver-foreground `
        --receiver-include-background `
        --user current `
        -a com.example.todoschooltime.e2e.COMMAND `
        -n com.example.todoschooltime.e2e/com.example.todoschooltime.E2eCommandReceiver `
        --es request_b64 $encoded 2>&1
    $adbExitCode = $LASTEXITCODE
    $joined = $output -join "`n"
    if ($adbExitCode -ne 0) { throw "ADB broadcast failed with exit code ${adbExitCode}: $joined" }
    $match = [regex]::Match($joined, 'data="?E2E1:(?<payload>[A-Za-z0-9_-]+)"?')
    if (-not $match.Success) { throw "ADB returned no E2E1 response; the E2E command did not complete successfully: $joined" }
    $response = $match.Groups['payload'].Value.Replace('-', '+').Replace('_', '/')
    $response += '=' * ((4 - ($response.Length % 4)) % 4)
    $responseBytes = [Convert]::FromBase64String($response)
    return [Text.Encoding]::UTF8.GetString($responseBytes) | ConvertFrom-Json
}

Invoke-E2eCommand '{"version":1,"command":"getState"}'
Invoke-E2eCommand '{"version":1,"command":"setReport","child":"child-a","subject":"math","count":8,"outcome":"success","delayMs":0}'
Invoke-E2eCommand '{"version":1,"command":"registerChild","child":"child-a"}'
```

The request must be one strict JSON object with no trailing text, comments, single-quoted strings, duplicate keys, unquoted names, or leading-zero numbers. The command object must contain exactly the fields listed below. JSON integers must be unquoted base-10 integers. Unknown fields, commands, aliases, versions, and values are rejected before state changes.

## Commands

| Command | Required fields | Effect |
| --- | --- | --- |
| `getState` | `version`, `command` | Reads one engine snapshot after an engine queue fence, actual Android permission settings, service lease state, and this app's active notifications. |
| `getEvents` | `version`, `command` | Reads the bounded fake-query and notification-request audit. |
| `clearEvents` | `version`, `command` | Clears the event buffer. The sequence counter remains monotonic. |
| `reset` | `version`, `command` | Restores the default fake reports, clears simulated date and saved goals, unregisters the fake account's children through `LearningMonitor`, resets its interval to one minute, reauthenticates the fixed fake credentials to clear an authentication-denied latch, then clears request events produced by reset. |
| `setReport` | `version`, `command`, `child`, `subject`, `count`, `outcome`, `delayMs` | Persists a fake report input for one child and subject. It takes effect when the product engine next requests that report; it does not start a check itself. |
| `registerChild` | `version`, `command`, `child` | Calls the product engine's child registration method. |
| `unregisterChild` | `version`, `command`, `child` | Calls the product engine's child unregistration method. |
| `setInterval` | `version`, `command`, `minutes` | Calls the product engine with a positive integer minute interval, without a product-specific maximum. |
| `setGoal` | `version`, `command`, `child`, `targets` | Saves targets using `TargetCountStore` and calls the product engine. Values match the app's `0..99` goal input; omitted subjects keep their saved value or use the app default. |
| `advanceDate` | `version`, `command`, `days` | Advances the E2E engine clock by `1..3650` Seoul calendar days and asks the product runtime to reevaluate the date. This changes only fake clock input, never device time. |

`child` accepts `child-a` or `child-b`. `subject` and keys in `targets` accept `hangeul`, `math`, or `english`, which map to the product names 한글, 수학, and 영어. The fake catalog has two children, each subscribed to those three subjects. Its initial report count is zero for every child and subject.

`setReport` accepts `outcome` values `success`, `temporaryFailure`, and `authenticationDenied`; `count` must be a non-negative 32-bit integer, and `delayMs` must be `0..30000`. The fake repository captures the selected report when each request starts. Changing fake data while a delayed request is in flight affects later requests only, so stale-result tests exercise the engine's own cancellation and freshness checks.

## Response timing and state provenance

State-changing commands call the existing product surface and then subscribe once to `LearningMonitor.observe()`. The initial snapshot is captured on the engine's serial queue after commands already queued by that broadcast. The subscription closes after that fence. `engineRevision` identifies the returned snapshot. A delayed fake network operation is separate work: its response can arrive after the fence and will appear in a later `getState` result only after the engine accepts and publishes it.

`getState` groups data into `date`, `monitor`, `activeNotifications`, and `fakeWorld`:

- `monitor` is the actual product engine snapshot. Its registered children, statuses, target counts, and revision are not synthesized by the test API. Permission values are read directly from current Android settings, and `serviceLeaseAttached` reflects the actual AccessibilityService connection.
- `activeNotifications` comes from Android's active notifications for the E2E package only. This is posted state, separate from `getEvents` notification request records.
- `fakeWorld` is persisted test input. The fixed test account is `e2e-test-account`; the E2E Application stores only its isolated fake credentials and uses a fake repository.
- `date.source` is `Asia/Seoul_device_clock` until `advanceDate` is called, then `simulated`. Until simulated, `checkedAt` and midnight cadence use the actual `DateHelper` Asia/Seoul clock. In simulated mode, `checkedAt` is a deterministic noon value and midnight cadence is a fixed 24 hours. The simulated date is persisted until `reset`; no device clock or timezone setting is changed.

`getEvents` returns at most the latest 500 entries plus `truncated` and `lastSequence`. `kind: fake_query` records fake sign-in, catalog, and subject-report calls. `kind: notification_request` records calls made by the product engine to its notification sink. The decorator still delegates to the actual `AndroidChildNotificationSink`. The E2E composition pre-creates that package's product channel at low importance with sound and vibration disabled, and also suppresses completion alerts. A request record does not prove Android posted a notification; use `activeNotifications` for that. Event payloads contain only fake child/subject data, never content from other apps.

## Android setup

Basic ADB commands and `getState` work without notification or accessibility permission. To exercise screen report queries through the real product Activity, open `com.example.todoschooltime.e2e/com.example.todoschooltime.MainActivity` with the host's ADB `am start`. To exercise product monitoring and actual notification posting, enable the E2E app's notification permission and its `토도 학습 상태 알림 서비스` AccessibilityService in Android Settings yourself. The API can read those settings but cannot grant or revoke them. Its notification posting path preserves real Android permission behavior; a missing permission can leave the engine paused and the active-notification list empty. Device boot and task removal are controlled by the host's ADB/device actions, not by fake engine flags.

## Device validation and limits

On a real Android API 33 device, the documented broadcast options returned `getState` and a structured `invalid_request` response both with the E2E process initially cold and after launching the E2E `MainActivity`. During this smoke check, notification and accessibility permissions were false, the service lease was detached, and the E2E app had no active notifications. This does not validate the permission-enabled monitoring service or actual notification-posting integration. The real Activity's report-query flow and the `authenticationDenied` → `reset` recovery flow were not verified end to end, and API 35 was not tested.

After an E2E package reinstall/data clear, the API 33 device once recorded the manifest receiver as skipped and `am broadcast` returned no `E2E1` result data. Rebooting restored successful cold and post-Activity responses; the cause of the earlier skip is unknown. The PowerShell helper throws on an `adb` failure or missing `E2E1` response, so a skipped receiver is not reported as a passing command. A structured API error is returned with `ok: false` and must also be treated as failure by the calling test unless that error is expected.
