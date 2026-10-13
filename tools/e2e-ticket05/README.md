# Ticket 05 local HTTP/authentication recovery test

This scenario verifies the real debug-app HTTP and monitoring path against the loopback fixture. It injects one HTTP 500 for a Hangeul report, checks that the prior result and checked time remain visible, and waits for the configured one-minute retry. Cadence is measured from the first actual sign-in body dispatch of the failed monitor cycle to the first actual sign-in body dispatch of its retry, using the product's elapsed-realtime dispatch records; the host also verifies the corresponding fixture sign-in sequence and monotonic receive times. The report-to-report receive interval is retained as diagnostic context, not used as the scheduler boundary. It then injects an HTTP 401, checks that the existing child registration remains paused without periodic requests, and exercises the visible fake-account login UI. The recovery baseline treats the completion made while disconnected as silent; a later English completion must update the real Android notification once.

The product debug APK is the already prepared artifact from Ticket 02. `-RefreshObserver` only builds or installs the external observer APKs after verifying that the product APK and AccessibilityService are unchanged and still bound. `-RunPrepared` verifies all installed APK hashes, wakes the display without unlocking it, and refuses to run on a secure lock screen. The fixture is served only at `http://127.0.0.1:<configured-port>` and records fake credentials, fake bearer delivery, paths, statuses, report dates, and monotonic request times. The runner joins only complete, unique 3,000-character observer result chunks from the current run ID before creating the evidence artifact.

From the repository root, run the observer refresh and then the prepared scenario:

```powershell
powershell -ExecutionPolicy Bypass -File tools/e2e-ticket05/Run-Ticket05.ps1 -RefreshObserver
powershell -ExecutionPolicy Bypass -File tools/e2e-ticket05/Run-Ticket05.ps1 -RunPrepared
```

The run saves the native observer result, fixture request history, filtered instrumentation output, lifecycle samples, and `ticket05-evidence.json` under `build/e2e-ticket05/<runId>/`. The evidence records source revision and file hashes, product and observer APK hashes, device serial/API, and fixture run ID. Actual sound or heads-up presentation is not measured by this scenario; the prepared device is configured for a low-importance, silent channel.

The shared runner records the native `adb` exit status through a small launcher sidecar and checks it independently from the PowerShell launcher status. Ticket 05 also requires the completed `OK (1 test)` instrumentation marker and the strict, current-run chunked result before it accepts fixture evidence. Run `powershell -ExecutionPolicy Bypass -File tools/e2e-ticket05/Test-ExternalObserverOutcome.ps1` to verify that missing/nonzero exit codes, launcher mismatches, incomplete output, and wrong-run structured results are rejected.
