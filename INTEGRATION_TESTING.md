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

## Date coverage and limits

Device scenarios use the device's actual date through `DateHelper`'s `Asia/Seoul` calendar; the server fixture does not advance the app or device clock. `DateHelperTest` and `LearningMonitorEngineTest` retain fake-clock coverage for Seoul date boundaries, date reevaluation, and stale result handling. Runtime date advancement inside the app is not observed by the server-backed scenarios.

The completed device records are API 33 evidence. API 35 remains unverified. The silent observer scenarios do not establish actual sound or heads-up delivery. Each ticket's completion record distinguishes device execution, host result parsing, manual steps, and any unobserved behavior; a build alone is not a scenario pass.
