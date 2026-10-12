# Ticket 04 permission recovery

Ticket 04 uses the loopback fixture and external observer with the already prepared Ticket 02 product installation. It does not rebuild or install the product APK during the prepared run.

First rebuild and refresh only the observer APKs. The runner verifies that the prepared product APK still matches its manifest and that the product process/service lifecycle is unchanged:

```powershell
.\tools\e2e-ticket01\Run-Ticket01.ps1 -Scenario ticket04 -RefreshObserver
```

When the device is ready for the human steps, run:

```powershell
.\tools\e2e-ticket01\Run-Ticket01.ps1 -Scenario ticket04 -RunPrepared
```

The runner starts a fake loopback API only. It never calls the TodoSchool API, changes a permission through ADB, or force-stops the product app. The observer keeps the already-awake screen on with its bounded 15-minute wake lock.

For each case, the **학습 감시 E2E 검사** screen first displays a **시험 시작** button, before the held report request starts. Press it only when ready. The observer then starts one real product report request against the held fixture response and opens the relevant Android Settings page after the server confirms that request is pending.

For the notification case, turn off the product app's notifications. The observer waits up to five minutes for the real permission change; this wait does not change the permission automatically. It records the request age, whether the client request and socket were still active, and the process IDs separately. If the request ended before notification access was turned off, the test makes no cancellation claim and still checks the OS process restart, registration retention, and silent recovery.

For the Accessibility case, turn off the product service and confirm Android's warning within the **13.5-second evidence window**. This case requires the held request and client socket to remain open at the actual permission boundary, with the same product PID before and after the user disables the service. A permission change after the window does not count as an in-flight cancellation boundary.

After the observer reads the actual permission as OFF and verifies the paused product state, it automatically opens a separate **학습 감시 E2E 검사** screen with **권한 OFF 확인 · 계속**. You do not need to search for this button inside TodoSchool. Confirm the permission remains off and press it to acknowledge the result. The fixture response is not released before that human acknowledgement. The server has no automatic release timer.

The 13.5-second cutoff leaves margin before the product's 15-second read timeout and applies to the Accessibility case's mandatory in-flight boundary. The notification case does not use that cutoff as a process-restart gate. If a client disconnects after the permission boundary and before the read timeout, the result calls it **cancellation-consistent, cause unconfirmed**. A late close, earlier close, or open socket near release is classified separately; none is relabeled as a proven cancellation.

When asked, restore the permission yourself in Android Settings. The test verifies the full silent baseline, registration and progress preservation, then a new completion for the same child. It repeats the process for the other permission. If the runner stops with a permission still off, restore it manually before ending the session.

The per-case result records permission state, request sequence and age, an explicit request-boundary classification, in-flight/socket state, PID before/boundary/after returning, process expectation, acknowledgement and release times, disconnect evidence, preserved registration and subject state, silent recovery baseline, and later completion. Same-PID, in-flight, socket-open evidence is mandatory only for Accessibility OFF. Notification OFF requires an observed process restart with registration and silent recovery preserved; its request state is recorded without claiming same-process cancellation. Its new process may begin with pending subject vectors because its prior in-memory baseline was lost; the test compares that new-process state only before and after release of the old held response. Accessibility compares against its same-process baseline before and after release. The stale success/error side-effect guards remain backed by the product engine unit tests. If HTTP cancellation prevents a callback from being delivered, that delivery remains unobserved and is not forced.

The observer result and selected, redacted fixture evidence are written under `build/e2e-ticket04/<runId>/`. Raw fixture request bodies and authorization headers are not copied into the Ticket 04 summary artifact.
