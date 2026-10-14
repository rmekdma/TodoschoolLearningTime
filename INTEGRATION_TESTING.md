# Android integration testing

## Supported workflows

The device scenarios use the production debug app and its real HTTP repository against a temporary fixture server bound to `127.0.0.1`. The host runner exposes that fixture through `adb reverse`; no scenario should call the production TodoSchool API. Fixture accounts and reports are synthetic, while registration, goals, intervals, permission changes, and app navigation use the real app UI where the scenario requires them.

Ticket 01 exercises the real sign-in, catalog, report, and screen path. Its runner installs or updates the debug and instrumentation APKs and runs product instrumentation, so use it only on a device isolated from a prepared observer session. Tickets 03–06 use the external read-only observer. On a prepared device, refresh only the observer APKs and then run the prepared scenario:

```powershell
.\tools\e2e-ticket03\Run-Ticket03.ps1 -RefreshObserver
.\tools\e2e-ticket03\Run-Ticket03.ps1 -RunPrepared
```

Ticket 04 requires the person running the test to revoke and restore the requested permission. Ticket 06 includes a user-performed Recents removal and reboot. Their exact commands and manual boundaries are in the [Ticket 04 guide](tools/e2e-ticket04/README.md) and [Ticket 06 guide](tools/e2e-ticket06/README.md). Ticket 05's prepared path is in the [Ticket 05 guide](tools/e2e-ticket05/README.md).

## App and observer configuration

The debug app installs as `com.example.todoschooltime.debug`. Its `learningApiBaseUrl` property accepts only `http://127.0.0.1:<port>`; the runner uses the same port for the fixture server and `adb reverse`. Debug cleartext access is limited to loopback. The release app uses `https://api.todomath.com`, rejects cleartext traffic, and does not include the debug diagnostics provider. Do not install or run a release build as part of these fixture scenarios.

The separate observer package is `com.example.todoschooltime.e2eobserver`. The debug-only diagnostics provider exposes engine and Android state read-only, protected by the shared signing certificate. Refreshing the observer does not install the product debug APK; prepared runs verify the product APK hash and accessibility service state before execution.

The runner configuration in `tools/e2e-ticket01/config.json` controls device selection, local fixture port, and Java/Python paths. See [Ticket 01](tools/e2e-ticket01/README.md) for setup and the default-port build property.

## Replaced fake controls and retained coverage

The retired `e2e` app variant and its broadcast command API have no supported invocation path. Scenario setup now uses the debug app UI and a run-scoped HTTP fixture; observation uses the separate read-only observer. Fixture reset clears only that run's server state and never resets app preferences or product data.

| Retired harness behavior | Current replacement and coverage |
| --- | --- |
| Fake account, catalog, and report responses (`E2eFakeRepository`) | The loopback fixture feeds the real HTTP repository. Ticket 01 covers sign-in, catalog, and report screens; Ticket 03 verifies reports follow the foreground subject; Ticket 05 covers HTTP 500, expired authentication, UI sign-in, and recovery. |
| `setReport` count, outcome, and delay inputs | Fixture report counts and held responses exercise real HTTP callbacks. Ticket 01 and Ticket 03 cover report inputs and cadence; Ticket 04 covers a pending request and permission boundary; Ticket 05 covers server and authentication failures. `LearningMonitorEngineTest` retains delayed stale-response and rejected-authentication regressions. |
| Register/unregister, target and interval changes, and app `reset` | Scenarios perform supported actions through the real debug UI. Tickets 03 and 06 cover registration and removal; Ticket 06 covers target/interval setup and reboot restoration. `LearningMonitorEngineTest`, `TargetCountStoreTest`, and `SubjectSubscriptionTest` retain engine and persistence coverage. A fixture reset is not an app-state reset. |
| `getState` for engine, service, permission, and active notification state | The debug-only `LearningMonitorDiagnosticsProvider` reads the real engine snapshot and Android state. `Ticket02ProviderDiagnosticsTest` and `LearningMonitorDiagnosticsTest` exercise the read-only diagnostics contract. |
| `getEvents`/`clearEvents`, fake query audit, and notification audit | The observer reads real foreground and HTTP-dispatch events, notification requests, service lease, and Android active notifications. The fixture records received paths and in-flight requests. `LearningMonitorDiagnosticsTest` covers the debug event cursor and release exclusion; no fake event buffer or clear command remains. |
| App fake clock and `advanceDate` | Server scenarios use the device's current date and do not advance either clock. `DateHelperTest` and `LearningMonitorEngineTest` keep fake-clock coverage for Seoul date boundaries, date reevaluation, and stale results. App-level runtime date advancement remains unobserved. |

## Date coverage and limits

Device scenarios use the device's actual date through `DateHelper`'s `Asia/Seoul` calendar; the server fixture does not advance the app or device clock. `DateHelperTest` and `LearningMonitorEngineTest` retain fake-clock coverage for Seoul date boundaries, date reevaluation, and stale result handling. Runtime date advancement inside the app is not observed by the server-backed scenarios.

The completed device records are API 33 evidence. API 35 remains unverified. The silent observer scenarios do not establish actual sound or heads-up delivery. Each ticket's completion record distinguishes device execution, host result parsing, manual steps, and any unobserved behavior; a build alone is not a scenario pass.
