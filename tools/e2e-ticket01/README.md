# Ticket 01: local server to real screen

This workflow runs the production sign-in, catalog, report parsing, and `MainActivity` screen path against a temporary fixture server bound only to `127.0.0.1`. It never contacts the real TodoSchool API.

## Run

Copy `config.example.json` to `config.json`, set `deviceSerial` if more than one ADB device is connected, then run from PowerShell:

```powershell
Copy-Item tools/e2e-ticket01/config.example.json tools/e2e-ticket01/config.json
# Edit config.json only if the device serial, local port, Java, or Python paths differ.
.\tools\e2e-ticket01\Run-Ticket01.ps1
```

You can also pass `-DeviceSerial <serial>`. With one connected device the script selects it. If several are connected, it uses the configured serial or the project tablet serial `T811MA256GB23418064398`; otherwise it stops before building or installing anything.

The script builds and tests the debug, e2e, and release variants. It checks APK package IDs, INTERNET permission, the debug-only network security configuration, and the compiled URL literals before installing the debug APK and test APK with `adb install -r`. It then starts the fixture server, verifies readiness, adds an `adb reverse` mapping only when needed, and runs the instrumentation screen check directly so the debug package and its app data remain installed. The runner wakes the display, but stops if the device still shows a secure lock screen; unlock it manually before retrying. It reads the server request log and the Activity screenshot from run-specific instrumentation log chunks into an ignored folder under `build/e2e-ticket01/`. On exit it stops only its own server process and removes only the reverse mapping it created. It does not clear app data, uninstall apps, or grant or revoke permissions.

## Installed app and data isolation

The test APK uses `com.example.todoschooltime.debug`, its own Android application sandbox, preferences, and Android Keystore namespace. The installed production package remains `com.example.todoschooltime`; the workflow records its APK path before and after the test and fails if it changed. The test signs in with `ticket01@example.test` and a fixed fake password. Do not enter a real TodoSchool account in this debug app.

The `e2e` build remains `com.example.todoschooltime.e2e`. It continues to use `E2eFakeRepository` and the existing broadcast API documented in [`../../E2E_TEST_API.md`](../../E2E_TEST_API.md); that variant removes INTERNET permission. The Ticket 01 server is separate and does not replace the old harness.

## Addresses and network policy

`learningApiBaseUrl` is a build-time Gradle property and accepts only `http://127.0.0.1:<port>` with no path or credentials. The runner passes the same host and port to Gradle, the server, and `adb reverse`. A missing server fails the real HTTP request; there is no production URL fallback.

The debug manifest selects a network security configuration whose base policy rejects cleartext and whose only cleartext domain is `127.0.0.1`. The release manifest has no debug network configuration, explicitly rejects cleartext, keeps INTERNET permission, and compiles `https://api.todomath.com`. The release APK is inspected but never launched or installed by this workflow.

Ordinary debug builds use `http://127.0.0.1:18765` by default, so running the app outside the script requires a temporary server on that port and the matching `adb reverse tcp:18765 tcp:18765` mapping. To build for a different local port, pass `-PlearningApiBaseUrl=http://127.0.0.1:<port>`; a remote host, HTTPS override, malformed URL, or production endpoint is rejected during Gradle configuration.

## Screen evidence and limits

The instrumentation check uses the Android framework `UiAutomation` API with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` before reading or operating the visible UI. It enters the fake credentials when the login dialog is shown, waits for the actual child card text, checks activity counts from the fixture responses, and captures the real screen. It does not synthesize product state. This avoids the default UiAutomation behavior that suppresses accessibility services while tests run. When later monitoring scenarios reuse this screen observer, they must separately verify the product service is actually connected and that foreground events continue; this Ticket 01 run does not enable or validate those permissions.

The runner reports the connected Android API level. API 33 results are Android 13 substitute evidence, not Android 15 results. It does not test audible notifications, alert delivery, accessibility permission recovery, or actual TodoSchool backend compatibility.
