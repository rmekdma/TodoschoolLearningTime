package com.example.todoschooltime.e2eobserver

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real accessibility foreground events while an independent package observes the product. */
@RunWith(AndroidJUnit4::class)
class ForegroundPollingTest {
    private val accessibilitySupport = ObserverAccessibilitySupport(pollIntervalMs = POLL_INTERVAL_MS)
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var screenWakeAutomation: UiAutomation? = null
    private var stayAwakeEvidence: JSONObject? = null

    private data class ForegroundTransition(
        val packageName: String,
        val eventSequence: Long,
        val cursorBeforeTransition: Long,
        val launchRequestedAtUtc: String? = null,
        val launchRequestedAtElapsedRealtimeMs: Long? = null,
        val foregroundEventObservedAtUtc: String? = null,
        val foregroundEventObservedAtElapsedRealtimeMs: Long? = null,
        val foregroundCallbackAtElapsedRealtimeMs: Long? = null,
    )

    private data class ForegroundEventObservation(
        val packageName: String,
        val sequence: Long,
        val observedAtUtc: String,
        val observedAtElapsedRealtimeMs: Long,
        val callbackAtElapsedRealtimeMs: Long,
        val activeWindowPackage: String,
    )

    @Before
    fun keepObserverScreenInteractive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val observerContext = instrumentation.context
        val productContext = instrumentation.targetContext
        assertEquals(OBSERVER_PACKAGE, productContext.packageName)
        assertTrue("The device must already be awake and unlocked before the observer starts", isInteractive(productContext))

        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        screenWakeAutomation = automation
        val before = readProductState(productContext)
        assertReadyProduct(before)
        val beforeCursor = lastSequence(before.getJSONArray("foregroundEvents"))
        val packageBefore = observedForegroundPackage(automation, before)

        val powerManager = observerContext.getSystemService(PowerManager::class.java)
            ?: throw AssertionError("The observer could not access PowerManager")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "$OBSERVER_PACKAGE:ticket03-screen",
        ).apply { setReferenceCounted(false) }
        screenWakeLock = wakeLock
        var handedOffToTest = false
        try {
            wakeLock.acquire(SCREEN_WAKE_LOCK_TIMEOUT_MS)
            val acquiredAtElapsedMs = SystemClock.elapsedRealtime()
            SystemClock.sleep(WAKE_LOCK_SETTLE_MS)

            assertTrue("The observer screen wake lock did not keep the already-awake display interactive", isInteractive(productContext))
            val after = readProductState(productContext)
            assertReadyProduct(after)
            val afterEvents = after.getJSONArray("foregroundEvents").objects()
            val packageAfter = observedForegroundPackage(automation, after)
            val eventsDuringAcquire = afterEvents.filter { it.getLong("sequence") > beforeCursor }
            assertEquals("Acquiring the observer screen wake lock changed the foreground package", packageBefore, packageAfter)
            assertTrue(
                "The observer screen wake lock produced a foreground event for another package: $eventsDuringAcquire",
                eventsDuringAcquire.all { it.getString("packageName") == packageBefore },
            )
            assertTrue("The product AccessibilityService lease detached while the observer acquired its wake lock", after.getBoolean("serviceLeaseAttached"))

            val runId = InstrumentationRegistry.getArguments().getString(ARG_RUN_ID)
            stayAwakeEvidence = JSONObject()
                .put("runId", runId)
                .put("mechanism", "external-observer SCREEN_BRIGHT_WAKE_LOCK")
                .put("permissionScope", "external observer package only; never requested by product APK")
                .put("acquired", true)
                .put("acquiredAtElapsedRealtimeMs", acquiredAtElapsedMs)
                .put("timeoutMs", SCREEN_WAKE_LOCK_TIMEOUT_MS)
                .put("displayInteractiveBefore", true)
                .put("displayInteractiveAfter", isInteractive(productContext))
                .put("foregroundPackageBefore", packageBefore)
                .put("foregroundPackageAfter", packageAfter)
                .put("foregroundEventCursorBefore", beforeCursor)
                .put("foregroundEventCursorAfter", lastSequence(after.getJSONArray("foregroundEvents")))
                .putJsonArray("foregroundEventsDuringAcquire", eventsDuringAcquire)
                .put("serviceLeaseAttachedBefore", before.getBoolean("serviceLeaseAttached"))
                .put("serviceLeaseAttachedAfter", after.getBoolean("serviceLeaseAttached"))
            LogResult.sample("TICKET03_STAY_AWAKE", stayAwakeEvidence!!)
            handedOffToTest = true
        } finally {
            if (!handedOffToTest) {
                try {
                    if (wakeLock.isHeld) wakeLock.release()
                } finally {
                    screenWakeLock = null
                }
            }
        }
    }

    @After
    fun releaseObserverScreenWakeLock() {
        val wakeLock = screenWakeLock ?: return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val productContext = instrumentation.targetContext
        val automation = screenWakeAutomation
        val before = runCatching { readProductState(productContext) }.getOrNull()
        val packageBefore = before?.let { state -> automation?.let { ui -> runCatching { observedForegroundPackage(ui, state) }.getOrNull() } }
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } finally {
            screenWakeLock = null
        }
        val after = runCatching { readProductState(productContext) }.getOrNull()
        val packageAfter = after?.let { state -> automation?.let { ui -> runCatching { observedForegroundPackage(ui, state) }.getOrNull() } }
        val cursorBefore = before?.let { lastSequence(it.getJSONArray("foregroundEvents")) }
        val eventsAfterRelease = if (after != null && cursorBefore != null) {
            after.getJSONArray("foregroundEvents").objects().filter { it.getLong("sequence") > cursorBefore }
        } else {
            emptyList()
        }
        val evidence = JSONObject()
            .put("runId", InstrumentationRegistry.getArguments().getString(ARG_RUN_ID))
            .put("released", true)
            .put("foregroundPackageBefore", packageBefore)
            .put("foregroundPackageAfter", packageAfter)
            .putJsonArray("foregroundEventsAfterRelease", eventsAfterRelease)
            .put("releaseConfirmed", !wakeLock.isHeld)
            .put("serviceLeaseAttachedAfter", after?.optBoolean("serviceLeaseAttached"))
        LogResult.sample("TICKET03_STAY_AWAKE_RELEASE", evidence)
    }

    @Test
    fun onlyRegisteredIncompleteForegroundSubjectIsPolledAtOneMinuteIntervals() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val apiBaseUrl = arguments.getString(ARG_API_BASE_URL)
            ?: throw AssertionError("Missing apiBaseUrl instrumentation argument")
        val runId = arguments.getString(ARG_RUN_ID)
            ?: throw AssertionError("Missing runId instrumentation argument")
        assertTrue("Ticket 03 requires the local loopback fixture", apiBaseUrl.startsWith(LOOPBACK_PREFIX))
        val startDate = seoulDateNumber()

        val context = instrumentation.targetContext
        assertEquals(OBSERVER_PACKAGE, context.packageName)
        val packageManager = context.packageManager
        val debugInfo = packageManager.getApplicationInfo(PRODUCT_PACKAGE, 0)
        assertEquals(DEBUG_APPLICATION_LABEL, debugInfo.loadLabel(packageManager).toString())
        for (packageName in SUBJECT_PACKAGES.values) {
            assertNotNull("Actual TodoSchool subject app is not installed: $packageName", packageManager.getLaunchIntentForPackage(packageName))
        }
        assertNotNull("Android Settings app is not installed", packageManager.getLaunchIntentForPackage(SETTINGS_PACKAGE))

        val beforeAutomation = readProductState(context)
        assertReadyProduct(beforeAutomation)
        val initialRegistrations = beforeAutomation.getJSONArray("registeredChildren").strings().toSet()
        assertTrue(
            "Only this scenario's previous child registration is allowed: $beforeAutomation",
            initialRegistrations.isEmpty() || initialRegistrations == setOf(CHILD_NAME),
        )
        val initialNotifications = beforeAutomation.getJSONArray("activeNotifications").objects()
        assertTrue(
            "Only this scenario's previous child notification is allowed: $initialNotifications",
            initialNotifications.size <= 1 && initialNotifications.all { it.optString("title").contains(CHILD_NAME) },
        )
        val channel = requireSilentChannel(beforeAutomation)
        println("TICKET03_LIFECYCLE beforeUiAutomation=$beforeAutomation")

        val automation = screenWakeAutomation
            ?: instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertReadyProduct(readProductState(context))
        println("TICKET03_LIFECYCLE afterUiAutomation=${readProductState(context)}")

        val health = fixtureRequest(apiBaseUrl, "GET", "/__ticket03/health")
        assertEquals(runId, health.getString("runId"))
        assertEquals("ticket03", health.getString("scenario"))

        openProductApp(instrumentation)
        enterFakeCredentialsIfNeeded(automation)
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString()?.contains(CHILD_NAME) == true }
        val accountState = waitForProductState(context, TIMEOUT_MS) { it.optString("accountId") == ACCOUNT_ID }
        assertReadyProduct(accountState)
        val registrationsBeforeReset = accountState.getJSONArray("registeredChildren").strings().toSet()
        assertTrue("Only this scenario's previous child registration may be present", registrationsBeforeReset.isEmpty() || registrationsBeforeReset == setOf(CHILD_NAME))
        if (registrationsBeforeReset.isNotEmpty()) {
            clickDescription(automation, "$CHILD_NAME 감시 해제")
            val resetState = waitForProductState(context, TIMEOUT_MS) {
                it.getJSONArray("registeredChildren").length() == 0 && it.getJSONArray("activeNotifications").length() == 0
            }
            assertReadyProduct(resetState)
        }

        writeDefaultTargetsThroughTheApp(automation)
        ensureOneMinuteInterval(automation)
        requireSilentChannel(readProductState(context))
        val beforeRegistration = readProductState(context)
        assertEquals(0, beforeRegistration.getJSONArray("registeredChildren").length())
        assertEquals(0, beforeRegistration.getJSONArray("activeNotifications").length())

        val reset = fixtureRequest(apiBaseUrl, "POST", "/__ticket03/reset-requests", JSONObject())
        assertTrue(reset.optBoolean("ok"))
        assertEquals(runId, reset.getString("runId"))
        clickDescription(automation, "$CHILD_NAME 감시 등록")
        val baseline = waitForProductState(context, TIMEOUT_MS) {
            it.getJSONArray("registeredChildren").strings().toSet() == setOf(CHILD_NAME) && isExpectedIncompleteBaseline(it)
        }
        assertReadyProduct(baseline)
        requireAllSubjectsBelowTarget(baseline)
        val initialServer = awaitFixtureState(apiBaseUrl, runId, TIMEOUT_MS) { state ->
            state.getJSONArray("requests").objects().count { it.optString("path") in REPORT_PATHS } == 3 &&
                state.getJSONArray("inFlightRequests").length() == 0
        }
        assertBaselineRequests(initialServer.getJSONArray("requests").objects())
        assertEquals(startDate, seoulDateNumber())
        val observationStartSequence = initialServer.getLong("sequence")
        val windows = mutableListOf<JSONObject>()
        val pollRecords = mutableListOf<JSONObject>()

        pollRecords += pollSubject(
            instrumentation, automation, apiBaseUrl, runId, SUBJECT_HANGEUL, expectedCount = 2,
            expectedStatus = STATUS_BELOW_TARGET, expectedAfterSequence = observationStartSequence, expectedSeoulDate = startDate,
        ).also(windows::add)
        pollRecords += pollSubject(
            instrumentation, automation, apiBaseUrl, runId, SUBJECT_MATH, expectedCount = 2,
            expectedStatus = STATUS_BELOW_TARGET, expectedSeoulDate = startDate,
        ).also(windows::add)
        pollRecords += pollSubject(
            instrumentation, automation, apiBaseUrl, runId, SUBJECT_ENGLISH, expectedCount = 2,
            expectedStatus = STATUS_BELOW_TARGET, expectedSeoulDate = startDate,
        ).also(windows::add)

        val completionChange = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket03/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", TARGET_HANGEUL)),
        )
        assertEquals(runId, completionChange.getString("runId"))
        assertEquals(TARGET_HANGEUL, completionChange.getJSONObject("reportCounts").getInt("hangeul"))
        pollRecords += pollSubject(
            instrumentation, automation, apiBaseUrl, runId, SUBJECT_HANGEUL, expectedCount = TARGET_HANGEUL,
            expectedStatus = STATUS_COMPLETE, expectedSeoulDate = startDate,
        ).also(windows::add)

        val completedHangeulTransition = ForegroundTransition(
            HANGEUL_APP_PACKAGE,
            pollRecords.last().getLong("foregroundEventSequence"),
            pollRecords.last().getLong("foregroundEventSequence") - 1,
            foregroundCallbackAtElapsedRealtimeMs = pollRecords.last().getLong("foregroundCallbackAtElapsedRealtimeMs"),
        )
        val afterCompletion = awaitNoReportsForDuration(
            automation = automation,
            context = context,
            apiBaseUrl = apiBaseUrl,
            runId = runId,
            durationMs = NO_POLL_WINDOW_MS,
            expectedPackage = HANGEUL_APP_PACKAGE,
            registeredChildren = setOf(CHILD_NAME),
            foregroundTransition = completedHangeulTransition,
            requestSequenceBeforeTransition = pollRecords.last().getLong("requestSequence"),
            expectedSequence = pollRecords.last().getLong("requestSequence"),
            expectedSeoulDate = startDate,
        ).put("transition", "afterCompletion")
        windows += afterCompletion
        val completedState = readProductState(context)
        assertReadyProduct(completedState)
        assertEquals(STATUS_COMPLETE, subject(completedState, HANGEUL_SUBJECT).getString("status"))
        assertEquals(STATUS_BELOW_TARGET, subject(completedState, MATH_SUBJECT).getString("status"))
        assertEquals(STATUS_BELOW_TARGET, subject(completedState, ENGLISH_SUBJECT).getString("status"))

        pollRecords += pollSubject(
            instrumentation, automation, apiBaseUrl, runId, SUBJECT_MATH, expectedCount = 2,
            expectedStatus = STATUS_BELOW_TARGET, expectedSeoulDate = startDate,
            previouslyCompleteSubjects = setOf(SUBJECT_HANGEUL),
        ).also(windows::add)

        val homeForeground = launchHomeAndWaitForNonSubject(automation, context, apiBaseUrl, runId)
        val homeWindow = awaitNoReportsForDuration(
            automation = automation, context = context, apiBaseUrl = apiBaseUrl, runId = runId,
            durationMs = NO_POLL_WINDOW_MS, expectedPackage = homeForeground.packageName,
            registeredChildren = setOf(CHILD_NAME), foregroundTransition = homeForeground,
            requestSequenceBeforeTransition = pollRecords.last().getLong("requestSequence"),
            expectedSequence = pollRecords.last().getLong("requestSequence"), expectedSeoulDate = startDate,
        ).put("transition", "home")
            .put("launchRequestedAtUtc", homeForeground.launchRequestedAtUtc)
            .put("launchRequestedAtElapsedRealtimeMs", homeForeground.launchRequestedAtElapsedRealtimeMs)
            .put("foregroundEventSequence", homeForeground.eventSequence)
            .put("foregroundCallbackAtElapsedRealtimeMs", homeForeground.foregroundCallbackAtElapsedRealtimeMs)
            .put("foregroundEventObservedAtUtc", homeForeground.foregroundEventObservedAtUtc)
            .put("foregroundEventObservedAtElapsedRealtimeMs", homeForeground.foregroundEventObservedAtElapsedRealtimeMs)
            .put("activeWindowPackageAtForegroundEventObservation", homeForeground.packageName)
        windows += homeWindow

        pollRecords += pollSubject(
            instrumentation, automation, apiBaseUrl, runId, SUBJECT_ENGLISH, expectedCount = 2,
            expectedStatus = STATUS_BELOW_TARGET, expectedSeoulDate = startDate,
            previouslyCompleteSubjects = setOf(SUBJECT_HANGEUL),
        ).also(windows::add)

        val settingsForeground = launchSettingsAndWait(automation, context)
        val settingsWindow = awaitNoReportsForDuration(
            automation = automation, context = context, apiBaseUrl = apiBaseUrl, runId = runId,
            durationMs = NO_POLL_WINDOW_MS, expectedPackage = SETTINGS_PACKAGE,
            registeredChildren = setOf(CHILD_NAME), foregroundTransition = settingsForeground,
            requestSequenceBeforeTransition = pollRecords.last().getLong("requestSequence"),
            expectedSequence = pollRecords.last().getLong("requestSequence"), expectedSeoulDate = startDate,
        ).put("foregroundEventSequence", settingsForeground.eventSequence)
            .put("foregroundCallbackAtElapsedRealtimeMs", settingsForeground.foregroundCallbackAtElapsedRealtimeMs)
            .put("transition", "other_app")
        windows += settingsWindow

        openProductApp(instrumentation)
        enterFakeCredentialsIfNeeded(automation)
        waitForCurrentReportScreen(automation)
        val beforeUnregister = waitForProductState(context, TIMEOUT_MS) {
            it.getJSONArray("registeredChildren").strings().toSet() == setOf(CHILD_NAME)
        }
        assertReadyProduct(beforeUnregister)
        clickDescription(automation, "$CHILD_NAME 감시 해제")
        val unregistered = waitForProductState(context, TIMEOUT_MS) {
            it.getJSONArray("registeredChildren").length() == 0 && it.getJSONArray("activeNotifications").length() == 0
        }
        assertReadyProduct(unregistered)
        val quietServer = awaitFixtureState(apiBaseUrl, runId, TIMEOUT_MS) {
            it.getJSONArray("inFlightRequests").length() == 0
        }
        val noRegistrationStartSequence = quietServer.getLong("sequence")
        val quietConfirmation = awaitFixtureQuietForDuration(
            apiBaseUrl, runId, QUIET_CONFIRMATION_MS, noRegistrationStartSequence,
        )
        assertEquals(noRegistrationStartSequence, quietConfirmation.getLong("sequence"))

        val noRegistrationForeground = launchActualSubjectApp(
            automation, context, SUBJECT_ENGLISH, afterForegroundSequence = lastSequence(unregistered.getJSONArray("foregroundEvents")),
        )
        val noRegistrationWindow = awaitNoReportsForDuration(
            automation = automation, context = context, apiBaseUrl = apiBaseUrl, runId = runId,
            durationMs = NO_POLL_WINDOW_MS, expectedPackage = noRegistrationForeground.packageName,
            registeredChildren = emptySet(), foregroundTransition = noRegistrationForeground,
            requestSequenceBeforeTransition = noRegistrationStartSequence,
            expectedSequence = noRegistrationStartSequence, expectedSeoulDate = startDate,
        ).put("foregroundEventSequence", noRegistrationForeground.eventSequence)
            .put("foregroundCallbackAtElapsedRealtimeMs", noRegistrationForeground.foregroundCallbackAtElapsedRealtimeMs)
            .put("transition", "no_registered_children")
        windows += noRegistrationWindow
        val finalState = readProductState(context)
        assertReadyProduct(finalState)
        assertEquals(0, finalState.getJSONArray("registeredChildren").length())
        val finalServer = fixtureState(apiBaseUrl, runId)
        assertEquals(noRegistrationStartSequence, finalServer.getLong("sequence"))
        assertEquals(0, finalServer.getJSONArray("inFlightRequests").length())

        assertPollIntervals(pollRecords)
        assertEquals(startDate, seoulDateNumber())
        assertTrue("The device display went to sleep during the test", isInteractive(context))

        val result = JSONObject()
            .put("runId", runId)
            .put("apiBaseUrl", apiBaseUrl)
            .put("deviceApi", android.os.Build.VERSION.SDK_INT)
            .put("deviceModel", android.os.Build.MODEL)
            .put("seoulDate", startDate)
            .put("configuredIntervalMinutes", 1)
            .put("intervalMeasuredUsingRealElapsedTime", true)
            .put("registeredChild", CHILD_NAME)
            .put("registeredChildOnly", true)
            .put("subjectPackages", JSONObject(SUBJECT_PACKAGES))
            .putJsonArray("baselineRequests", initialServer.getJSONArray("requests").objects())
            .put("observationStartSequence", observationStartSequence)
            .putJsonArray("pollRequests", pollRecords)
            .putJsonArray("observationWindows", windows)
            .put("noRegistrationNewReportCount", 0)
            .put("serviceLeaseAttached", finalState.getBoolean("serviceLeaseAttached"))
            .put("foregroundEvents", finalState.getJSONArray("foregroundEvents"))
            .put("httpRequestDispatchCursor", finalState.getLong("httpRequestDispatchCursor"))
            .putJsonArray("httpRequestDispatchEvents", finalState.getJSONArray("httpRequestDispatchEvents").objects())
            .put("silentChannelPrepared", true)
            .put("notificationChannelImportance", channel.getInt("importance"))
            .put("notificationChannelHasSoundUri", channel.optString("soundUri") != "null")
            .put("notificationChannelVibrationEnabled", channel.optBoolean("vibrationEnabled"))
            .put("productAlertDeliveryTested", false)
            .put("actualTodoApiCalled", false)
            .put("stayAwake", stayAwakeEvidence)
            .put("observerPackage", OBSERVER_PACKAGE)
        LogResult.write(result)
    }

    private fun pollSubject(
        instrumentation: Instrumentation,
        automation: UiAutomation,
        apiBaseUrl: String,
        runId: String,
        subjectKey: String,
        expectedCount: Int,
        expectedStatus: String,
        expectedAfterSequence: Long? = null,
        expectedSeoulDate: Int,
        previouslyCompleteSubjects: Set<String> = emptySet(),
    ): JSONObject {
        val context = instrumentation.targetContext
        ensureScenarioClock(context, expectedDate = expectedSeoulDate)
        val beforeProduct = readProductState(context)
        assertReadyProduct(beforeProduct)
        assertEquals(setOf(CHILD_NAME), beforeProduct.getJSONArray("registeredChildren").strings().toSet())
        requireSubjectStates(beforeProduct, previouslyCompleteSubjects)
        val serverBefore = fixtureState(apiBaseUrl, runId)
        assertEquals("A prior fixture request is still in flight at a foreground transition: $serverBefore", 0, serverBefore.getJSONArray("inFlightRequests").length())
        val requestSequenceBeforeTransition = serverBefore.getLong("sequence")
        expectedAfterSequence?.let { assertEquals(it, requestSequenceBeforeTransition) }
        val foregroundCursor = lastSequence(beforeProduct.getJSONArray("foregroundEvents"))
        val cycleObservationStartedAtElapsedMs = SystemClock.elapsedRealtime()
        val cycleObservationStartedAtUtc = Instant.now().toString()
        val requestDispatchCursorBeforeTransition = beforeProduct.getLong("httpRequestDispatchCursor")

        val foregroundTransition = launchActualSubjectApp(
            automation, context, subjectKey, foregroundCursor,
        )
        val foregroundEventSequence = foregroundTransition.eventSequence
        val expectedPackage = foregroundTransition.packageName
        val eventObservationSnapshotStartedAtUtc = Instant.now().toString()
        val eventObservationSnapshotStartedAtElapsedMs = SystemClock.elapsedRealtime()
        val serverAtForegroundEventObservation = fixtureState(apiBaseUrl, runId)
        val eventObservationSnapshotFinishedAtElapsedMs = SystemClock.elapsedRealtime()
        val eventObservationSnapshotFinishedAtUtc = Instant.now().toString()
        val requestsVisibleAtForegroundEventObservation = serverAtForegroundEventObservation.getJSONArray("requests").objects().filter {
            it.optLong("sequence") > requestSequenceBeforeTransition
        }.map { request ->
            JSONObject()
                .put("sequence", request.getLong("sequence"))
                .put("path", request.getString("path"))
                .put("receivedAt", request.getString("receivedAt"))
                .put("responseStatus", request.getInt("responseStatus"))
        }
        val observationStart = awaitStableForegroundWindowStart(
            automation = automation,
            context = context,
            apiBaseUrl = apiBaseUrl,
            runId = runId,
            expectedPackage = expectedPackage,
            foregroundCursorBeforeTransition = foregroundTransition.cursorBeforeTransition,
            foregroundEventSequence = foregroundEventSequence,
            requestSequenceBeforeTransition = requestSequenceBeforeTransition,
            registeredChildren = setOf(CHILD_NAME),
        )
        val startSequence = observationStart.getLong("requestSequenceCursorAtWindowStart")
        val requestsDuringTransition = observationStart.getJSONArray("requestsDuringTransition").objects()
        val path = SUBJECT_REPORT_PATHS.getValue(subjectKey)
        val unexpectedTransitionReports = requestsDuringTransition.filter {
            it.optString("path") in REPORT_PATHS && it.optString("path") != path
        }
        assertTrue(
            "A foreground transition polled another subject before the $subjectKey window began: $unexpectedTransitionReports",
            unexpectedTransitionReports.isEmpty(),
        )
        val foregroundObservedAtElapsedMs = observationStart.getLong("observationWindowStartedAtElapsedRealtimeMs")
        val stableWindowForegroundCursor = observationStart.getLong("foregroundEventCursorAtWindowStart")
        val foregroundObservedAtUtc = Instant.now().toString()
        val requestState = if (requestsDuringTransition.any { it.optString("path") == path }) {
            observationStart.getJSONObject("fixtureStateAtWindowStart")
        } else {
            awaitFixtureState(apiBaseUrl, runId, POLL_TIMEOUT_MS) { state ->
                state.getJSONArray("requests").objects().any { it.optLong("sequence") > requestSequenceBeforeTransition && it.optString("path") == path } &&
                state.getJSONArray("inFlightRequests").length() == 0
            }
        }
        val requests = requestState.getJSONArray("requests").objects().filter {
            it.optLong("sequence") > requestSequenceBeforeTransition
        }
        val reportRequests = requests.filter { it.optString("path") in REPORT_PATHS }
        val expectedCyclePaths = listOf(
            SIGN_IN_PATH,
            HANGEUL_CATALOG_PATH,
            MATH_CATALOG_PATH,
            ENGLISH_CATALOG_PATH,
            path,
        )
        assertEquals(
            "The real monitor cycle must preserve sign-in, all subject catalog requests, and the foreground subject report: $requests",
            expectedCyclePaths,
            requests.map { it.getString("path") },
        )
        assertEquals(
            "Only this foreground subject's report may be periodic; do not count auth/catalog cycle calls as reports: $requests",
            listOf(path),
            reportRequests.map { it.getString("path") },
        )
        for (request in requests) {
            assertEquals("A monitoring-cycle request failed: $request", 200, request.getInt("responseStatus"))
            assertEquals("POST", request.getString("method"))
            val headers = request.getJSONObject("headers")
            when (request.getString("path")) {
                SIGN_IN_PATH -> {
                    assertEquals(TEST_EMAIL, request.getJSONObject("body").getString("email"))
                    assertEquals(TEST_PASSWORD, request.getJSONObject("body").getString("password"))
                    assertEquals("", headers.optString("authorization"))
                }
                HANGEUL_CATALOG_PATH, MATH_CATALOG_PATH, ENGLISH_CATALOG_PATH -> {
                    assertEquals(AUTHORIZATION, headers.getString("authorization"))
                    assertEquals(ACCOUNT_ID, request.getJSONObject("body").getJSONObject("user").getString("accountId"))
                }
                else -> assertTrue("Unexpected non-report request in the monitor cycle: $request", request.getString("path") in REPORT_PATHS)
            }
        }
        val report = reportRequests.single()
        assertEquals("POST", report.getString("method"))
        assertEquals(200, report.getInt("responseStatus"))
        assertEquals(expectedCount, report.getInt("servedReportCount"))
        assertEquals(SUBJECT_USER_IDS.getValue(subjectKey), report.getJSONObject("body").getString("userId"))
        assertEquals(expectedSeoulDate, report.getJSONObject("body").getInt("yyyymmdd"))
        assertEquals("ko", report.getJSONObject("body").getString("languageCode"))
        assertEquals(AUTHORIZATION, report.getJSONObject("headers").getString("authorization"))

        val foregroundState = readProductState(context)
        assertReadyProduct(foregroundState)
        val foregroundEventsAtReport = assertForegroundPackageStayedActive(
            foregroundState,
            automation,
            expectedPackage,
            afterForegroundSequence = stableWindowForegroundCursor,
        )

        val subjectName = SUBJECT_NAMES.getValue(subjectKey)
        val applied = waitForProductState(context, POLL_TIMEOUT_MS) { state ->
            state.getBoolean("serviceLeaseAttached") && subject(state, subjectName).let {
                it.optString("status") == expectedStatus && it.optInt("activityCount", -1) == expectedCount
            }
        }
        assertReadyProduct(applied)
        requireOnlyThisSubjectApplied(applied, subjectKey, expectedStatus, expectedCount, previouslyCompleteSubjects)
        val finishedAtElapsedMs = SystemClock.elapsedRealtime()
        val appliedForeground = readProductState(context)
        assertReadyProduct(appliedForeground)
        val foregroundEventsAtApply = assertForegroundPackageStayedActive(
            appliedForeground,
            automation,
            expectedPackage,
            afterForegroundSequence = stableWindowForegroundCursor,
        )
        val cycleDispatchEvents = appliedForeground.getJSONArray("httpRequestDispatchEvents").objects().filter {
            it.getLong("sequence") > requestDispatchCursorBeforeTransition
        }
        assertEquals("The actual HTTP dispatches must match the server-recorded full monitor cycle", expectedCyclePaths, cycleDispatchEvents.map { it.getString("path") })
        val reportDispatch = cycleDispatchEvents.single { it.getString("path") == path }
        assertTrue("The foreground callback sequence was not current at report dispatch", reportDispatch.getLong("foregroundEventSequence") >= foregroundEventSequence)
        assertEquals(expectedPackage, reportDispatch.getString("foregroundPackage"))
        assertTrue("The target foreground callback occurred after the report HTTP dispatch", reportDispatch.getLong("foregroundEventElapsedRealtimeMs") >= foregroundTransition.foregroundCallbackAtElapsedRealtimeMs!!)
        assertTrue("The report HTTP dispatch preceded its captured foreground callback", reportDispatch.getLong("foregroundEventElapsedRealtimeMs") <= reportDispatch.getLong("elapsedRealtimeMs"))
        assertTrue("The product AccessibilityService lease was absent at report dispatch", reportDispatch.getBoolean("serviceLeaseAttached"))
        val dispatchForegroundEvent = appliedForeground.getJSONArray("foregroundEvents").objects().single {
            it.getLong("sequence") == reportDispatch.getLong("foregroundEventSequence")
        }
        assertEquals(reportDispatch.getString("foregroundPackage"), dispatchForegroundEvent.getString("packageName"))
        assertEquals(reportDispatch.getLong("foregroundEventElapsedRealtimeMs"), dispatchForegroundEvent.getLong("elapsedRealtimeMs"))
        val record = JSONObject()
            .put("subject", subjectName)
            .put("packageName", SUBJECT_PACKAGES.getValue(subjectKey))
            .put("foregroundEventSequence", foregroundEventSequence)
            .put("foregroundCallbackAtElapsedRealtimeMs", foregroundTransition.foregroundCallbackAtElapsedRealtimeMs)
            .put("foregroundEventCursorBefore", foregroundCursor)
            .put("appLaunchRequestedAtUtc", foregroundTransition.launchRequestedAtUtc)
            .put("appLaunchRequestedAtElapsedRealtimeMs", foregroundTransition.launchRequestedAtElapsedRealtimeMs)
            .put("foregroundEventObservedAtUtc", foregroundTransition.foregroundEventObservedAtUtc)
            .put("foregroundEventObservedAtElapsedRealtimeMs", foregroundTransition.foregroundEventObservedAtElapsedRealtimeMs)
            .put("foregroundPackagesObservedDuringTransition", observationStart.getJSONArray("foregroundPackagesObservedDuringTransition"))
            .put("foregroundEventsDuringTransition", observationStart.getJSONArray("foregroundEventsDuringTransition"))
            .put("foregroundEventSequenceAtWindowStart", observationStart.getLong("foregroundEventCursorAtWindowStart"))
            .put("requestSequenceBeforeTransition", requestSequenceBeforeTransition)
            .put("requestDispatchCursorBeforeTransition", requestDispatchCursorBeforeTransition)
            .putJsonArray("httpRequestDispatchEventsDuringCycle", cycleDispatchEvents)
            .put("requestDispatchSequenceAtReport", reportDispatch.getLong("sequence"))
            .put("requestDispatchElapsedRealtimeMsAtReport", reportDispatch.getLong("elapsedRealtimeMs"))
            .put("foregroundEventSequenceAtReportDispatch", reportDispatch.getLong("foregroundEventSequence"))
            .put("foregroundEventElapsedRealtimeMsAtReportDispatch", reportDispatch.getLong("foregroundEventElapsedRealtimeMs"))
            .put("foregroundPackageAtReportDispatch", reportDispatch.getString("foregroundPackage"))
            .put("serviceLeaseAttachedAtReportDispatch", reportDispatch.getBoolean("serviceLeaseAttached"))
            .put("requestSequenceAtForegroundEventObservation", serverAtForegroundEventObservation.getLong("sequence"))
            .put("inFlightRequestsAtForegroundEventObservation", serverAtForegroundEventObservation.getJSONArray("inFlightRequests").length())
            .put("eventObservationFixtureSnapshotStartedAtUtc", eventObservationSnapshotStartedAtUtc)
            .put("eventObservationFixtureSnapshotFinishedAtUtc", eventObservationSnapshotFinishedAtUtc)
            .put("eventObservationFixtureSnapshotStartedAtElapsedRealtimeMs", eventObservationSnapshotStartedAtElapsedMs)
            .put("eventObservationFixtureSnapshotFinishedAtElapsedRealtimeMs", eventObservationSnapshotFinishedAtElapsedMs)
            .putJsonArray("requestsVisibleAtForegroundEventObservation", requestsVisibleAtForegroundEventObservation)
            .put("requestSequenceCursorAtWindowStart", startSequence)
            .putJsonArray("requestsDuringTransitionSequences", requestsDuringTransition.map { it.getLong("sequence") })
            .put("requestSequenceCursorAtReport", report.getLong("sequence"))
            .put("inFlightRequestsAtWindowStart", observationStart.getInt("inFlightRequestsAtWindowStart"))
            .putJsonArray("requestsDuringTransition", requestsDuringTransition.map { it.getString("path") })
            .put("expectedReportObservedBeforeStableWindow", requestsDuringTransition.any { it.optString("path") == path })
            .put("requestTimingInterpretation", "Transition requests are included in the target foreground cycle; device and fixture wall clocks are not compared")
            .put("observationWindowStartedAtElapsedRealtimeMs", foregroundObservedAtElapsedMs)
            .put("foregroundSnapshotCapturedAtElapsedRealtimeMs", observationStart.getLong("foregroundSnapshotCapturedAtElapsedRealtimeMs"))
            .put("requestSnapshotCapturedAtElapsedRealtimeMs", observationStart.getLong("requestSnapshotCapturedAtElapsedRealtimeMs"))
            .put("foregroundActiveWindowPackageAtWindowStart", observationStart.getString("activeWindowPackageAtWindowStart"))
            .put("serviceLeaseAttachedAtWindowStart", observationStart.getBoolean("serviceLeaseAttachedAtWindowStart"))
            .put("foregroundEventSequenceAtReport", lastSequence(foregroundState.getJSONArray("foregroundEvents")))
            .put("foregroundPackageAtReport", expectedPackage)
            .putJsonArray("foregroundEventsObservedAtReport", foregroundEventsAtReport)
            .putJsonArray("foregroundPackagesObservedAtReport", foregroundEventsAtReport.map { it.getString("packageName") })
            .put("foregroundEventSequenceAtApply", lastSequence(appliedForeground.getJSONArray("foregroundEvents")))
            .putJsonArray("foregroundEventsObservedAtApply", foregroundEventsAtApply)
            .putJsonArray("foregroundPackagesObservedAtApply", foregroundEventsAtApply.map { it.getString("packageName") })
            .put("automationActiveWindowPackageAtReport", automation.rootInActiveWindow?.packageName?.toString())
            .put("foregroundObservedAtUtc", foregroundObservedAtUtc)
            .put("foregroundObservedAtElapsedRealtimeMs", foregroundObservedAtElapsedMs)
            .put("requestSequence", report.getLong("sequence"))
            .put("requestPath", path)
            .putJsonArray("requestCycleSequences", requests.map { it.getLong("sequence") })
            .putJsonArray("requestCyclePaths", requests.map { it.getString("path") })
            .put("reportRequestCountInCycle", reportRequests.size)
            .put("servedCount", expectedCount)
            .put("status", expectedStatus)
            .put("requestReceivedAt", report.getString("receivedAt"))
            .put("cycleObservationStartedAtUtc", cycleObservationStartedAtUtc)
            .put("cycleObservationStartedAtElapsedRealtimeMs", cycleObservationStartedAtElapsedMs)
            .put("resultAppliedAtElapsedRealtimeMs", finishedAtElapsedMs)
            .put("cycleObservationElapsedThroughApplyMs", finishedAtElapsedMs - cycleObservationStartedAtElapsedMs)
            .put("foregroundActiveElapsedTimeMs", finishedAtElapsedMs - foregroundObservedAtElapsedMs)
            .put("serviceLeaseAttached", applied.getBoolean("serviceLeaseAttached"))
            .put("inFlightRequestsAtEnd", requestState.getJSONArray("inFlightRequests").length())
        LogResult.sample(
            "TICKET03_POLL",
            JSONObject()
                .put("subject", subjectName)
                .put("packageName", expectedPackage)
                .put("foregroundEventSequence", foregroundEventSequence)
                .put("foregroundCursorBeforeTransition", foregroundCursor)
                .put("foregroundEventSequenceAtWindowStart", observationStart.getLong("foregroundEventCursorAtWindowStart"))
                .put("requestSequenceBeforeTransition", requestSequenceBeforeTransition)
                .put("requestSequenceAtForegroundEventObservation", serverAtForegroundEventObservation.getLong("sequence"))
                .putJsonArray("requestsVisibleAtForegroundEventObservation", requestsVisibleAtForegroundEventObservation.map { it.getLong("sequence") })
                .put("requestSequenceCursorAtWindowStart", startSequence)
                .put("requestSequence", report.getLong("sequence"))
                .putJsonArray("requestCycleSequences", requests.map { it.getLong("sequence") })
                .putJsonArray("requestCyclePaths", requests.map { it.getString("path") })
                .put("requestPath", path)
                .put("cycleObservationStartedAtElapsedRealtimeMs", cycleObservationStartedAtElapsedMs)
                .put("cycleObservationElapsedThroughApplyMs", finishedAtElapsedMs - cycleObservationStartedAtElapsedMs)
                .put("foregroundActiveElapsedTimeMs", finishedAtElapsedMs - foregroundObservedAtElapsedMs)
                .put("foregroundPackagesObservedDuringTransition", observationStart.getJSONArray("foregroundPackagesObservedDuringTransition"))
                .putJsonArray("foregroundPackagesObservedAtReport", foregroundEventsAtReport.map { it.getString("packageName") })
                .put("status", expectedStatus)
                .put("serviceLeaseAttached", applied.getBoolean("serviceLeaseAttached"))
                .put("inFlightRequestsAtEnd", requestState.getJSONArray("inFlightRequests").length()),
        )
        return record
    }

    private fun awaitNoReportsForDuration(
        automation: UiAutomation,
        context: android.content.Context,
        apiBaseUrl: String,
        runId: String,
        durationMs: Long,
        expectedPackage: String,
        registeredChildren: Set<String>,
        foregroundTransition: ForegroundTransition,
        requestSequenceBeforeTransition: Long,
        expectedSequence: Long,
        expectedSeoulDate: Int,
    ): JSONObject {
        val observationStart = awaitStableForegroundWindowStart(
            automation = automation,
            context = context,
            apiBaseUrl = apiBaseUrl,
            runId = runId,
            expectedPackage = expectedPackage,
            foregroundCursorBeforeTransition = foregroundTransition.cursorBeforeTransition,
            foregroundEventSequence = foregroundTransition.eventSequence,
            requestSequenceBeforeTransition = requestSequenceBeforeTransition,
            registeredChildren = registeredChildren,
        )
        assertEquals(expectedPackage, foregroundTransition.packageName)
        assertEquals(expectedSequence, observationStart.getLong("requestSequenceCursorAtWindowStart"))
        assertEquals(0, observationStart.getInt("inFlightRequestsAtWindowStart"))
        val transitionRequests = observationStart.getJSONArray("requestsDuringTransition").objects()
        assertTrue("A request occurred during the foreground transition before the no-poll window: $transitionRequests", transitionRequests.isEmpty())
        val startedAt = observationStart.getLong("observationWindowStartedAtElapsedRealtimeMs")
        val startedAtUtc = Instant.now().toString()
        val foregroundCursor = observationStart.getLong("foregroundEventCursorAtWindowStart")
        val initial = fixtureState(apiBaseUrl, runId)
        assertEquals("The no-poll window started with an in-flight request", 0, initial.getJSONArray("inFlightRequests").length())
        assertEquals(expectedSequence, initial.getLong("sequence"))
        var samples = 0
        var lastLifecycleCheck = startedAt
        while (SystemClock.elapsedRealtime() - startedAt < durationMs) {
            ensureScenarioClock(context, expectedSeoulDate)
            assertTrue("The device display went to sleep during a no-poll window", isInteractive(context))
            val activePackage = automation.rootInActiveWindow?.packageName?.toString()
            assertEquals("Unexpected foreground package during no-poll window", expectedPackage, activePackage)
            val state = fixtureState(apiBaseUrl, runId)
            assertEquals("A new request was received while $expectedPackage was foreground: $state", expectedSequence, state.getLong("sequence"))
            assertEquals("A fixture request remained in flight while $expectedPackage was foreground", 0, state.getJSONArray("inFlightRequests").length())
            samples += 1
            if (SystemClock.elapsedRealtime() - lastLifecycleCheck >= LIFECYCLE_SAMPLE_MS) {
                val product = readProductState(context)
                assertTrue("The real AccessibilityService disconnected during $expectedPackage: $product", product.getBoolean("serviceLeaseAttached"))
                assertEquals(registeredChildren, product.getJSONArray("registeredChildren").strings().toSet())
                assertForegroundPackageStayedActive(product, automation, expectedPackage, foregroundCursor, allowNoNewEvents = true)
                lastLifecycleCheck = SystemClock.elapsedRealtime()
            }
            SystemClock.sleep(NO_POLL_SAMPLE_MS)
        }
        val endServer = fixtureState(apiBaseUrl, runId)
        assertEquals(expectedSequence, endServer.getLong("sequence"))
        assertEquals(0, endServer.getJSONArray("inFlightRequests").length())
        val finalProduct = readProductState(context)
        assertTrue(finalProduct.getBoolean("serviceLeaseAttached"))
        assertEquals(registeredChildren, finalProduct.getJSONArray("registeredChildren").strings().toSet())
        val foregroundEventsDuringWindow = assertForegroundPackageStayedActive(
            finalProduct, automation, expectedPackage, foregroundCursor, allowNoNewEvents = true,
        )
        return JSONObject()
            .put("foregroundPackage", expectedPackage)
            .put("activeWindowPackageAtWindowStart", observationStart.getString("activeWindowPackageAtWindowStart"))
            .put("foregroundCursorBeforeTransition", foregroundTransition.cursorBeforeTransition)
            .put("foregroundEventSequence", foregroundTransition.eventSequence)
            .put("foregroundCallbackAtElapsedRealtimeMs", foregroundTransition.foregroundCallbackAtElapsedRealtimeMs)
            .put("requestDispatchCursorAtWindowStart", observationStart.getLong("requestDispatchCursorAtWindowStart"))
            .put("requestDispatchCursorAtWindowEnd", finalProduct.getLong("httpRequestDispatchCursor"))
            .put("foregroundPackagesObservedDuringTransition", observationStart.getJSONArray("foregroundPackagesObservedDuringTransition"))
            .put("foregroundEventsDuringTransition", observationStart.getJSONArray("foregroundEventsDuringTransition"))
            .put("foregroundEventCursorAtWindowStart", foregroundCursor)
            .put("requestSequenceBeforeTransition", requestSequenceBeforeTransition)
            .put("requestSequenceCursorAtWindowStart", initial.getLong("sequence"))
            .putJsonArray("requestsDuringTransition", transitionRequests.map { it.getString("path") })
            .putJsonArray("requestsDuringTransitionSequences", transitionRequests.map { it.getLong("sequence") })
            .put("inFlightRequestsAtWindowStart", observationStart.getInt("inFlightRequestsAtWindowStart"))
            .put("serviceLeaseAttachedAtWindowStart", observationStart.getBoolean("serviceLeaseAttachedAtWindowStart"))
            .put("observationWindowStartedAtElapsedRealtimeMs", startedAt)
            .put("foregroundSnapshotCapturedAtElapsedRealtimeMs", observationStart.getLong("foregroundSnapshotCapturedAtElapsedRealtimeMs"))
            .put("requestSnapshotCapturedAtElapsedRealtimeMs", observationStart.getLong("requestSnapshotCapturedAtElapsedRealtimeMs"))
            .put("foregroundEventSequenceAtWindowStart", foregroundCursor)
            .put("expectedForegroundEventSequence", foregroundTransition.eventSequence)
            .put("foregroundEventSequenceAtWindowEnd", lastSequence(finalProduct.getJSONArray("foregroundEvents")))
            .putJsonArray("foregroundEventsObservedDuringWindow", foregroundEventsDuringWindow)
            .putJsonArray("foregroundPackagesObservedDuringWindow", foregroundEventsDuringWindow.map { it.getString("packageName") })
            .put("startedAtUtc", startedAtUtc)
            .put("durationMs", SystemClock.elapsedRealtime() - startedAt)
            .put("sampleCount", samples)
            .put("serverSequenceBefore", initial.getLong("sequence"))
            .put("serverSequenceAfter", endServer.getLong("sequence"))
            .put("inFlightRequestsBefore", initial.getJSONArray("inFlightRequests").length())
            .put("inFlightRequestsAfter", endServer.getJSONArray("inFlightRequests").length())
            .put("serviceLeaseAttachedAtEnd", finalProduct.getBoolean("serviceLeaseAttached"))
    }

    private fun awaitStableForegroundWindowStart(
        automation: UiAutomation,
        context: android.content.Context,
        apiBaseUrl: String,
        runId: String,
        expectedPackage: String,
        foregroundCursorBeforeTransition: Long,
        foregroundEventSequence: Long,
        requestSequenceBeforeTransition: Long,
        registeredChildren: Set<String>,
    ): JSONObject {
        val expectedAfterSequence = foregroundEventSequence - 1
        val deadline = SystemClock.uptimeMillis() + POLL_TIMEOUT_MS
        var stableSinceElapsedMs = -1L
        var lastObservedForegroundSequence = expectedAfterSequence
        var lastActivePackage: String? = null
        var lastForegroundEvidence: List<ForegroundEventEvidence> = emptyList()
        var lastServiceLeaseAttached = false
        var lastServerSequence = -1L
        var lastInFlightCount = -1
        do {
            val activePackage = automation.rootInActiveWindow?.packageName?.toString()
            val product = readProductState(context)
            assertReadyProduct(product)
            assertTrue("The real AccessibilityService disconnected while opening $expectedPackage: $product", product.getBoolean("serviceLeaseAttached"))
            assertEquals(registeredChildren, product.getJSONArray("registeredChildren").strings().toSet())
            val foregroundEvents = product.getJSONArray("foregroundEvents").objects()
            val foregroundEvidence = foregroundEvents.map {
                ForegroundEventEvidence(it.getLong("sequence"), it.getString("packageName"))
            }
            val noForeignEventSinceLastSample = foregroundEventsMatchPackageAfterCursor(
                foregroundEvidence,
                expectedPackage,
                lastObservedForegroundSequence,
            )
            val latestEventMatches = latestForegroundEventMatches(
                foregroundEvidence,
                expectedPackage,
                expectedAfterSequence,
            )
            if (activePackage != expectedPackage || !latestEventMatches || !noForeignEventSinceLastSample) {
                stableSinceElapsedMs = -1L
            } else {
                if (stableSinceElapsedMs < 0L) stableSinceElapsedMs = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - stableSinceElapsedMs >= FOREGROUND_STABILITY_MS) {
                    val serverBeforeConfirm = fixtureState(apiBaseUrl, runId)
                    if (serverBeforeConfirm.getJSONArray("inFlightRequests").length() == 0) {
                        val productConfirm = readProductState(context)
                        val activePackageConfirm = automation.rootInActiveWindow?.packageName?.toString()
                        assertReadyProduct(productConfirm)
                        assertTrue("The real AccessibilityService disconnected at the start of $expectedPackage window", productConfirm.getBoolean("serviceLeaseAttached"))
                        assertEquals(registeredChildren, productConfirm.getJSONArray("registeredChildren").strings().toSet())
                        val confirmEvents = productConfirm.getJSONArray("foregroundEvents").objects()
                        val confirmEvidence = confirmEvents.map {
                            ForegroundEventEvidence(it.getLong("sequence"), it.getString("packageName"))
                        }
                        val confirmHasNoForeignEvents = foregroundEventsMatchPackageAfterCursor(
                            confirmEvidence,
                            expectedPackage,
                            lastObservedForegroundSequence,
                        )
                        val confirmLatestMatches = latestForegroundEventMatches(
                            confirmEvidence,
                            expectedPackage,
                            expectedAfterSequence,
                        )
                        if (activePackageConfirm == expectedPackage && confirmHasNoForeignEvents && confirmLatestMatches) {
                            val serverConfirm = fixtureState(apiBaseUrl, runId)
                            val inFlightAtWindowStart = serverConfirm.getJSONArray("inFlightRequests").length()
                            if (serverConfirm.getLong("sequence") == serverBeforeConfirm.getLong("sequence") && inFlightAtWindowStart == 0) {
                                val dispatchCursorAtWindowStart = readProductState(context).getLong("httpRequestDispatchCursor")
                                val foregroundSnapshotElapsedMs = SystemClock.elapsedRealtime()
                                val transitionEvents = confirmEvents.filter {
                                    it.getLong("sequence") > foregroundCursorBeforeTransition
                                }
                                val requestsDuringTransition = serverConfirm.getJSONArray("requests").objects().filter {
                                    it.getLong("sequence") > requestSequenceBeforeTransition &&
                                        it.getLong("sequence") <= serverConfirm.getLong("sequence")
                                }
                                val requestSnapshotElapsedMs = SystemClock.elapsedRealtime()
                                return JSONObject()
                                    .put("foregroundEventSequence", foregroundEventSequence)
                                    .put("foregroundCursorBeforeTransition", foregroundCursorBeforeTransition)
                                    .put("foregroundEventCursorAtWindowStart", lastSequence(productConfirm.getJSONArray("foregroundEvents")))
                                    .put("requestDispatchCursorAtWindowStart", dispatchCursorAtWindowStart)
                                    .putJsonArray("foregroundPackagesObservedDuringTransition", transitionEvents.map { it.getString("packageName") })
                                    .putJsonArray("foregroundEventsDuringTransition", transitionEvents)
                                    .put("requestSequenceBeforeTransition", requestSequenceBeforeTransition)
                                    .put("requestSequenceCursorAtWindowStart", serverConfirm.getLong("sequence"))
                                    .putJsonArray("requestsDuringTransition", requestsDuringTransition)
                                    .put("fixtureStateAtWindowStart", serverConfirm)
                                    .put("inFlightRequestsAtWindowStart", inFlightAtWindowStart)
                                    .put("activeWindowPackageAtWindowStart", activePackageConfirm)
                                    .put("serviceLeaseAttachedAtWindowStart", productConfirm.getBoolean("serviceLeaseAttached"))
                                    .put("foregroundSnapshotCapturedAtElapsedRealtimeMs", foregroundSnapshotElapsedMs)
                                    .put("requestSnapshotCapturedAtElapsedRealtimeMs", requestSnapshotElapsedMs)
                                    .put("observationWindowStartedAtElapsedRealtimeMs", requestSnapshotElapsedMs)
                            }
                        } else {
                            stableSinceElapsedMs = -1L
                            lastObservedForegroundSequence = maxOf(
                                lastObservedForegroundSequence,
                                confirmEvidence.maxOfOrNull { it.sequence } ?: lastObservedForegroundSequence,
                            )
                        }
                    }
                }
            }
            lastObservedForegroundSequence = maxOf(
                lastObservedForegroundSequence,
                foregroundEvidence.maxOfOrNull { it.sequence } ?: lastObservedForegroundSequence,
            )
            lastActivePackage = activePackage
            lastForegroundEvidence = foregroundEvidence
            lastServiceLeaseAttached = product.getBoolean("serviceLeaseAttached")
            val lastServerState = fixtureState(apiBaseUrl, runId)
            lastServerSequence = lastServerState.getLong("sequence")
            lastInFlightCount = lastServerState.getJSONArray("inFlightRequests").length()
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(
            "Could not capture a stable $expectedPackage foreground window with no in-flight fixture request: " +
                "active=$lastActivePackage, latestEvent=${lastForegroundEvidence.maxByOrNull { it.sequence }}, " +
                "foregroundCursor=${lastForegroundEvidence.maxOfOrNull { it.sequence }}, serviceLease=$lastServiceLeaseAttached, " +
                "serverSequence=$lastServerSequence, inFlightRequests=$lastInFlightCount",
        )
    }

    private fun awaitFixtureQuietForDuration(
        apiBaseUrl: String,
        runId: String,
        durationMs: Long,
        expectedSequence: Long,
    ): JSONObject {
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < durationMs) {
            val state = fixtureState(apiBaseUrl, runId)
            assertEquals(expectedSequence, state.getLong("sequence"))
            assertEquals(0, state.getJSONArray("inFlightRequests").length())
            SystemClock.sleep(NO_POLL_SAMPLE_MS)
        }
        return fixtureState(apiBaseUrl, runId)
    }

    private fun launchActualSubjectApp(
        automation: UiAutomation,
        context: android.content.Context,
        subjectKey: String,
        afterForegroundSequence: Long = lastSequence(readProductState(context).getJSONArray("foregroundEvents")),
    ): ForegroundTransition {
        val packageName = SUBJECT_PACKAGES.getValue(subjectKey)
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw AssertionError("Actual subject app has no visible launcher activity: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val launchRequestedAtUtc = Instant.now().toString()
        val launchRequestedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        context.startActivity(intent)
        val eventObservation = waitForActualForegroundAndConnectedService(automation, context, afterForegroundSequence, packageName)
        return ForegroundTransition(
            packageName = packageName,
            eventSequence = eventObservation.sequence,
            cursorBeforeTransition = afterForegroundSequence,
            launchRequestedAtUtc = launchRequestedAtUtc,
            launchRequestedAtElapsedRealtimeMs = launchRequestedAtElapsedRealtimeMs,
            foregroundEventObservedAtUtc = eventObservation.observedAtUtc,
            foregroundEventObservedAtElapsedRealtimeMs = eventObservation.observedAtElapsedRealtimeMs,
            foregroundCallbackAtElapsedRealtimeMs = eventObservation.callbackAtElapsedRealtimeMs,
        )
    }

    private fun launchHomeAndWaitForNonSubject(
        automation: UiAutomation,
        context: android.content.Context,
        apiBaseUrl: String,
        runId: String,
    ): ForegroundTransition {
        val before = readProductState(context)
        val cursor = lastSequence(before.getJSONArray("foregroundEvents"))
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertNotNull("Android has no default Home activity", context.packageManager.resolveActivity(homeIntent, 0))
        val launchRequestedAtUtc = Instant.now().toString()
        val launchRequestedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        context.startActivity(homeIntent)
        val eventObservation = waitForNonSubjectForegroundAndConnectedService(automation, context, cursor)
        val productAtObservation = readProductState(context)
        val eventsAfterCursor = productAtObservation.getJSONArray("foregroundEvents").objects().filter {
            it.getLong("sequence") > cursor
        }
        val serverAtObservation = fixtureState(apiBaseUrl, runId)
        LogResult.sample(
            "TICKET03_HOME_TRANSITION",
            JSONObject()
                .put("runId", runId)
                .put("launchRequestedAtUtc", launchRequestedAtUtc)
                .put("launchRequestedAtElapsedRealtimeMs", launchRequestedAtElapsedRealtimeMs)
                .put("foregroundCursorBeforeTransition", cursor)
                .put("foregroundEventSequence", eventObservation.sequence)
                .put("foregroundEventPackage", eventObservation.packageName)
                .put("foregroundEventObservedAtUtc", eventObservation.observedAtUtc)
                .put("foregroundEventObservedAtElapsedRealtimeMs", eventObservation.observedAtElapsedRealtimeMs)
                .put("activeWindowPackageAtForegroundEventObservation", eventObservation.activeWindowPackage)
                .put("activeWindowPackageAfterEventSnapshot", automation.rootInActiveWindow?.packageName?.toString())
                .putJsonArray("foregroundEventsObservedAfterCursor", eventsAfterCursor.map {
                    JSONObject().put("sequence", it.getLong("sequence")).put("packageName", it.getString("packageName"))
                })
                .put("foregroundEventCursorAfterSnapshot", lastSequence(productAtObservation.getJSONArray("foregroundEvents")))
                .put("serviceLeaseAttached", productAtObservation.getBoolean("serviceLeaseAttached"))
                .put("requestSequenceAtEventObservation", serverAtObservation.getLong("sequence"))
                .put("inFlightRequestsAtEventObservation", serverAtObservation.getJSONArray("inFlightRequests").length()),
        )
        return ForegroundTransition(
            packageName = eventObservation.packageName,
            eventSequence = eventObservation.sequence,
            cursorBeforeTransition = cursor,
            launchRequestedAtUtc = launchRequestedAtUtc,
            launchRequestedAtElapsedRealtimeMs = launchRequestedAtElapsedRealtimeMs,
            foregroundEventObservedAtUtc = eventObservation.observedAtUtc,
            foregroundEventObservedAtElapsedRealtimeMs = eventObservation.observedAtElapsedRealtimeMs,
            foregroundCallbackAtElapsedRealtimeMs = eventObservation.callbackAtElapsedRealtimeMs,
        )
    }

    private fun launchSettingsAndWait(automation: UiAutomation, context: android.content.Context): ForegroundTransition {
        val before = readProductState(context)
        val cursor = lastSequence(before.getJSONArray("foregroundEvents"))
        val intent = context.packageManager.getLaunchIntentForPackage(SETTINGS_PACKAGE)
            ?: throw AssertionError("Android Settings has no visible launcher activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        val eventObservation = waitForActualForegroundAndConnectedService(automation, context, cursor, SETTINGS_PACKAGE)
        return ForegroundTransition(
            packageName = SETTINGS_PACKAGE,
            eventSequence = eventObservation.sequence,
            cursorBeforeTransition = cursor,
            foregroundCallbackAtElapsedRealtimeMs = eventObservation.callbackAtElapsedRealtimeMs,
        )
    }

    private fun waitForActualForegroundAndConnectedService(
        automation: UiAutomation,
        context: android.content.Context,
        afterForegroundSequence: Long,
        expectedPackage: String,
    ): ForegroundEventObservation {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var activePackage: String? = null
        var state = readProductState(context)
        do {
            activePackage = automation.rootInActiveWindow?.packageName?.toString()
            state = readProductState(context)
            val event = state.getJSONArray("foregroundEvents").objects().firstOrNull {
                it.optString("packageName") == expectedPackage && it.optLong("sequence") > afterForegroundSequence
            }
            if (activePackage == expectedPackage && event != null && state.optBoolean("serviceLeaseAttached")) {
                return ForegroundEventObservation(
                    packageName = expectedPackage,
                    sequence = event.getLong("sequence"),
                    observedAtUtc = Instant.now().toString(),
                    observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    callbackAtElapsedRealtimeMs = event.getLong("elapsedRealtimeMs"),
                    activeWindowPackage = activePackage.orEmpty(),
                )
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Actual foreground event or real AccessibilityService lease was not observed: expected=$expectedPackage, active=$activePackage, state=$state")
    }

    private fun waitForNonSubjectForegroundAndConnectedService(
        automation: UiAutomation,
        context: android.content.Context,
        afterForegroundSequence: Long,
    ): ForegroundEventObservation {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var activePackage: String? = null
        var state = readProductState(context)
        do {
            activePackage = automation.rootInActiveWindow?.packageName?.toString()
            state = readProductState(context)
            val event = state.getJSONArray("foregroundEvents").objects().firstOrNull {
                it.optLong("sequence") > afterForegroundSequence && it.optString("packageName") !in SUBJECT_PACKAGES.values
            }
            if (activePackage != null && activePackage !in SUBJECT_PACKAGES.values && event?.optString("packageName") == activePackage && state.optBoolean("serviceLeaseAttached")) {
                return ForegroundEventObservation(
                    packageName = activePackage.orEmpty(),
                    sequence = event.getLong("sequence"),
                    observedAtUtc = Instant.now().toString(),
                    observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    activeWindowPackage = activePackage.orEmpty(),
                    callbackAtElapsedRealtimeMs = event.getLong("elapsedRealtimeMs"),
                )
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("A real non-subject foreground event or service lease was not observed: active=$activePackage, state=$state")
    }

    private fun waitForCurrentReportScreen(automation: UiAutomation) {
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString()?.contains(CHILD_NAME) == true }
        val text = visibleText(automation).joinToString("\n")
        assertTrue("The screen omitted completed hangeul: $text", text.contains("✔ 한글 ($TARGET_HANGEUL/$TARGET_HANGEUL)"))
        assertTrue("The screen omitted incomplete math: $text", text.contains("수학 (2/$TARGET_MATH)"))
        assertTrue("The screen omitted incomplete English: $text", text.contains("영어 (2/$TARGET_ENGLISH)"))
    }

    private fun writeDefaultTargetsThroughTheApp(automation: UiAutomation) {
        clickDescription(automation, "$CHILD_NAME 목표 설정")
        val fields = findAllNodes(automation, TIMEOUT_MS) { it.className == EDIT_TEXT }
        assertEquals("The target dialog should show the three subscribed subjects", 3, fields.size)
        listOf(TARGET_HANGEUL, TARGET_MATH, TARGET_ENGLISH).forEachIndexed { index, target -> setField(fields[index], target.toString()) }
        clickText(automation, "저장")
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString()?.contains("한글 (2/$TARGET_HANGEUL)") == true }
    }

    private fun ensureOneMinuteInterval(automation: UiAutomation) {
        val expectedLabel = "감시 간격 1분"
        val interval = waitForNode(automation, TIMEOUT_MS) { it.text?.toString()?.startsWith("감시 간격 ") == true && it.isClickable }
        if (interval.text?.toString() != expectedLabel) {
            assertTrue("Could not open the common interval dialog", interval.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val field = waitForNode(automation, TIMEOUT_MS) { it.className == EDIT_TEXT }
            setField(field, "1")
            clickText(automation, "저장")
        }
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString() == expectedLabel }
    }

    private fun enterFakeCredentialsIfNeeded(automation: UiAutomation) {
        val email = findNodeOrNull(automation, LOGIN_FORM_TIMEOUT_MS) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 이메일" || it.text?.toString() == "토도스쿨 이메일")
        } ?: return
        setField(email, TEST_EMAIL)
        val password = waitForNode(automation, LOGIN_FORM_TIMEOUT_MS) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 비밀번호" || it.text?.toString() == "토도스쿨 비밀번호")
        }
        setField(password, TEST_PASSWORD)
        clickText(automation, "확인")
    }

    private fun openProductApp(instrumentation: Instrumentation) {
        val intent = instrumentation.targetContext.packageManager.getLaunchIntentForPackage(PRODUCT_PACKAGE)
            ?: throw AssertionError("Debug app has no visible launcher activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.targetContext.startActivity(intent)
    }

    private fun assertBaselineRequests(requests: List<JSONObject>) {
        val expectedPaths = listOf(SIGN_IN_PATH, HANGEUL_CATALOG_PATH, MATH_CATALOG_PATH, ENGLISH_CATALOG_PATH, HANGEUL_REPORT_PATH, MATH_REPORT_PATH, ENGLISH_REPORT_PATH)
        assertEquals("The entire initial subscription baseline must finish before periodic polling starts", expectedPaths, requests.map { it.getString("path") })
        val counts = mapOf(HANGEUL_REPORT_PATH to 2, MATH_REPORT_PATH to 2, ENGLISH_REPORT_PATH to 2)
        requests.forEach { request ->
            assertEquals(200, request.getInt("responseStatus"))
            if (request.getString("path") in REPORT_PATHS) assertEquals(counts[request.getString("path")], request.getInt("servedReportCount"))
            if (request.getString("path") in CATALOG_PATHS) assertEquals(ACCOUNT_ID, request.getJSONObject("body").getJSONObject("user").getString("accountId"))
            if (request.getString("path") in REPORT_PATHS) {
                assertEquals(seoulDateNumber(), request.getJSONObject("body").getInt("yyyymmdd"))
                assertEquals("ko", request.getJSONObject("body").getString("languageCode"))
            }
        }
    }

    private fun assertPollIntervals(polls: List<JSONObject>) {
        assertEquals("Each real foreground polling window should yield exactly one report request", 6, polls.size)
        for (index in 1 until polls.size) {
            val previous = Instant.parse(polls[index - 1].getString("requestReceivedAt"))
            val current = Instant.parse(polls[index].getString("requestReceivedAt"))
            val elapsed = Duration.between(previous, current).toMillis()
            assertTrue("Polls were less than one configured minute apart: ${polls[index - 1]} then ${polls[index]}", elapsed >= MIN_INTERVAL_GAP_MS)
            assertTrue("A minute cadence window exceeded the bounded polling deadline: $elapsed ms", elapsed <= MAX_INTERVAL_GAP_MS)
        }
    }

    private fun requireOnlyThisSubjectApplied(
        state: JSONObject,
        subjectKey: String,
        expectedStatus: String,
        expectedCount: Int,
        previouslyCompleteSubjects: Set<String>,
    ) {
        for (key in SUBJECT_NAMES.keys) {
            val item = subject(state, SUBJECT_NAMES.getValue(key))
            if (key == subjectKey) {
                assertEquals(expectedStatus, item.getString("status"))
                assertEquals(expectedCount, item.getInt("activityCount"))
            } else if (key in previouslyCompleteSubjects || (key == SUBJECT_HANGEUL && expectedStatus == STATUS_COMPLETE)) {
                assertEquals(STATUS_COMPLETE, item.getString("status"))
                assertEquals(targetForSubject(key), item.getInt("targetCount"))
                val completedCount = if (key == SUBJECT_HANGEUL) TARGET_HANGEUL else targetForSubject(key)
                assertEquals(completedCount, item.getInt("activityCount"))
            } else {
                assertEquals("A poll for $subjectKey changed unrelated subject $key", STATUS_BELOW_TARGET, item.getString("status"))
                assertEquals(2, item.getInt("activityCount"))
            }
        }
    }

    private fun requireAllSubjectsBelowTarget(state: JSONObject) {
        requireSubjectStates(state, emptySet())
    }

    private fun requireSubjectStates(state: JSONObject, completedSubjects: Set<String>) {
        for ((key, subjectName) in SUBJECT_NAMES) {
            val item = subject(state, subjectName)
            if (key in completedSubjects) {
                assertEquals(STATUS_COMPLETE, item.getString("status"))
                assertEquals(targetForSubject(key), item.getInt("activityCount"))
            } else {
                assertEquals("Baseline must leave $subjectName eligible for periodic polling", STATUS_BELOW_TARGET, item.getString("status"))
                assertEquals(2, item.getInt("activityCount"))
            }
        }
    }

    private fun targetForSubject(subjectKey: String): Int = when (subjectKey) {
        SUBJECT_HANGEUL -> TARGET_HANGEUL
        SUBJECT_MATH -> TARGET_MATH
        SUBJECT_ENGLISH -> TARGET_ENGLISH
        else -> throw AssertionError("Unknown subject key: $subjectKey")
    }

    private fun assertReadyProduct(state: JSONObject) {
        assertEquals(PRODUCT_PACKAGE, state.getString("applicationId"))
        assertTrue("The actual AccessibilityService lease is detached: $state", state.getBoolean("serviceLeaseAttached"))
        assertTrue("Accessibility permission is disabled: $state", state.getBoolean("accessibilityEnabled"))
        assertTrue("Notification permission is disabled: $state", state.getBoolean("notificationsEnabled"))
        assertTrue("POST_NOTIFICATIONS was not granted: $state", state.getBoolean("postNotificationsPermissionGranted"))
        assertTrue("The screen is not on", isInteractive(InstrumentationRegistry.getInstrumentation().targetContext))
    }

    private fun requireSilentChannel(state: JSONObject): JSONObject {
        assertTrue("Product notifications are disabled", state.optBoolean("notificationsEnabled"))
        val channel = state.getJSONObject("channel")
        assertTrue("The product learning status channel is missing", channel.optBoolean("exists"))
        val importance = channel.getInt("importance")
        val hasSoundUri = channel.optString("soundUri") != "null"
        val vibrationEnabled = channel.optBoolean("vibrationEnabled")
        val silent = !vibrationEnabled && when (importance) {
            IMPORTANCE_MIN, IMPORTANCE_LOW -> true
            IMPORTANCE_DEFAULT -> !hasSoundUri
            else -> false
        }
        assertTrue("The configured channel is not safe for a silent monitoring test: $channel", silent)
        return channel
    }

    private fun ensureScenarioClock(context: android.content.Context, expectedDate: Int) {
        assertEquals("The Seoul learning date changed during the scenario; re-prepare and rerun", expectedDate, seoulDateNumber())
        assertTrue("The device display is not interactive", isInteractive(context))
    }

    private fun isInteractive(context: android.content.Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun observedForegroundPackage(automation: UiAutomation, state: JSONObject): String {
        val activeWindowPackage = automation.rootInActiveWindow?.packageName?.toString()
        val latestEventPackage = state.getJSONArray("foregroundEvents").objects().maxByOrNull { it.getLong("sequence") }
            ?.getString("packageName")
        return activeWindowPackage ?: latestEventPackage
            ?: throw AssertionError("The observer could not identify the current foreground package")
    }

    private fun isExpectedIncompleteBaseline(state: JSONObject): Boolean = runCatching {
        SUBJECT_NAMES.values.all { subjectName -> subject(state, subjectName).let {
            it.optString("status") == STATUS_BELOW_TARGET && it.optInt("activityCount", -1) == 2
        } }
    }.getOrDefault(false)

    private fun subject(state: JSONObject, subjectName: String): JSONObject =
        state.getJSONObject("children").getJSONObject(CHILD_NAME).getJSONObject("subjects").getJSONObject(subjectName)

    private fun readProductState(context: android.content.Context): JSONObject = ObserverTestSupport.readProductState(context)


    private fun waitForProductState(context: android.content.Context, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitUntil(timeoutMs, "the requested product state") { readProductState(context).takeIf(predicate) }

    private fun awaitFixtureState(apiBaseUrl: String, runId: String, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitUntil(timeoutMs, "the matching fixture state") { fixtureState(apiBaseUrl, runId).takeIf(predicate) }

    private fun fixtureState(apiBaseUrl: String, runId: String): JSONObject =
        fixtureRequest(apiBaseUrl, "GET", "/__ticket03/state").also { assertEquals(runId, it.getString("runId")) }

    private fun <T : Any> waitUntil(timeoutMs: Long, description: String, block: () -> T?): T =
        ObserverTestSupport.waitForValue(timeoutMs, POLL_INTERVAL_MS, description, block)


    private fun fixtureRequest(apiBaseUrl: String, method: String, path: String, body: JSONObject? = null): JSONObject =
        ObserverTestSupport.fixtureRequest(apiBaseUrl, method, path, SERVER_TIMEOUT_MS, body)


    private fun setField(field: AccessibilityNodeInfo, value: String) =
        accessibilitySupport.setField(field, value, "Could not enter text in the product UI")

    private fun clickDescription(automation: UiAutomation, description: String) =
        accessibilitySupport.clickDescription(automation, description, TIMEOUT_MS, "Could not tap '" + description + "'")

    private fun clickText(automation: UiAutomation, text: String) =
        accessibilitySupport.clickText(automation, text, TIMEOUT_MS, "Could not tap '" + text + "'")

    private fun waitForNode(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        accessibilitySupport.waitForNode(automation, timeoutMs, predicate, "Timed out waiting for a product UI control")

    private fun findNodeOrNull(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        accessibilitySupport.findNodeOrNull(automation, timeoutMs, predicate)

    private fun findAllNodes(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> =
        accessibilitySupport.findAllNodes(automation, timeoutMs, predicate, "No active window contains the requested UI fields")

    private fun visibleText(automation: UiAutomation): List<String> =
        accessibilitySupport.visibleText(automation)

    private fun assertForegroundPackageStayedActive(
        state: JSONObject,
        automation: UiAutomation,
        expectedPackage: String,
        afterForegroundSequence: Long,
        allowNoNewEvents: Boolean = false,
    ): List<JSONObject> {
        assertEquals(
            "Unexpected active foreground package",
            expectedPackage,
            automation.rootInActiveWindow?.packageName?.toString(),
        )
        val events = state.getJSONArray("foregroundEvents").objects()
        val observed = try {
            foregroundEventsAfterCursor(
                events.map { ForegroundEventEvidence(it.getLong("sequence"), it.getString("packageName")) },
                expectedPackage,
                afterForegroundSequence,
                allowNoNewEvents,
            )
        } catch (failure: IllegalArgumentException) {
            throw AssertionError("${failure.message}: $state", failure)
        }
        val observedSequences = observed.map { it.sequence }.toSet()
        return events.filter { it.getLong("sequence") in observedSequences }
    }

    private object LogResult {
        fun sample(name: String, value: JSONObject) {
            android.util.Log.i(RESULT_LOG_TAG, "$name=${value}")
        }

        fun write(value: JSONObject) {
            val runId = value.getString("runId")
            val serializedResult = JSONObject(value.toString())
            listOf("baselineRequests", "pollRequests", "observationWindows", "foregroundEvents").forEach { field ->
                assertTrue("$field must remain a JSON array in the emitted result", serializedResult.get(field) is JSONArray)
            }
            val pollArrayFields = listOf(
                "foregroundPackagesObservedDuringTransition",
                "foregroundEventsDuringTransition",
                "requestsVisibleAtForegroundEventObservation",
                "requestsDuringTransitionSequences",
                "requestsDuringTransition",
                "foregroundEventsObservedAtReport",
                "foregroundPackagesObservedAtReport",
                "foregroundEventsObservedAtApply",
                "foregroundPackagesObservedAtApply",
                "requestCycleSequences",
                "requestCyclePaths",
            )
            val polls = serializedResult.getJSONArray("pollRequests")
            for (index in 0 until polls.length()) {
                val poll = polls.getJSONObject(index)
                pollArrayFields.forEach { field ->
                    assertTrue("pollRequests[$index].$field must remain a JSON array", poll.get(field) is JSONArray)
                }
            }
            val encoded = android.util.Base64.encodeToString(
                serializedResult.toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
            val chunks = encoded.chunked(RESULT_CHUNK_SIZE)
            chunks.forEachIndexed { index, payload ->
                android.util.Log.i(
                    RESULT_LOG_TAG,
                    "TICKET03_RESULT_CHUNK:$runId:${index + 1}/${chunks.size}:$payload",
                )
            }
        }
    }

    private companion object {
        const val ARG_API_BASE_URL = "apiBaseUrl"
        const val ARG_RUN_ID = "runId"
        const val PRODUCT_PACKAGE = "com.example.todoschooltime.debug"
        const val DEBUG_APPLICATION_LABEL = "토도 학습시간 · 로컬 테스트"
        const val TEST_EMAIL = "ticket01@example.test"
        const val TEST_PASSWORD = "not-a-real-password"
        const val AUTHORIZATION = "Bearer ticket01-fake-token"
        const val ACCOUNT_ID = "ticket01-fake-account"
        const val CHILD_NAME = "테스트 아이"
        const val OBSERVER_PACKAGE = "com.example.todoschooltime.e2eobserver"
        const val HANGEUL_APP_PACKAGE = "com.enuma.todohangeul"
        const val ENGLISH_APP_PACKAGE = "com.enuma.todoenglish"
        const val MATH_APP_PACKAGE = "com.enuma.todomath"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val SUBJECT_HANGEUL = "hangeul"
        const val SUBJECT_MATH = "math"
        const val SUBJECT_ENGLISH = "english"
        const val HANGEUL_SUBJECT = "한글"
        const val MATH_SUBJECT = "수학"
        const val ENGLISH_SUBJECT = "영어"
        const val STATUS_BELOW_TARGET = "BELOW_TARGET"
        const val STATUS_COMPLETE = "COMPLETE"
        const val TARGET_HANGEUL = 6
        const val TARGET_MATH = 5
        const val TARGET_ENGLISH = 8
        const val HANGEUL_USER_ID = "ticket01-hangeul"
        const val MATH_USER_ID = "ticket01-math"
        const val ENGLISH_USER_ID = "ticket01-english"
        const val HANGEUL_REPORT_PATH = "/v3/todoschool/report/daily/hangeul"
        const val MATH_REPORT_PATH = "/v3/todoschool/report/daily/math"
        const val ENGLISH_REPORT_PATH = "/v3/todoschool/report/daily/english"
        const val HANGEUL_CATALOG_PATH = "/v3/todohangeul/user"
        const val MATH_CATALOG_PATH = "/v3/math/user"
        const val ENGLISH_CATALOG_PATH = "/v3/english/user"
        const val SIGN_IN_PATH = "/v3/account/signin"
        const val LOOPBACK_PREFIX = "http://127.0.0.1:"
        const val RESULT_LOG_TAG = "Ticket03E2E"
        const val RESULT_CHUNK_SIZE = 3000
        const val SCREEN_WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L
        const val WAKE_LOCK_SETTLE_MS = 750L
        const val TIMEOUT_MS = 30_000L
        const val POLL_TIMEOUT_MS = 145_000L
        const val LOGIN_FORM_TIMEOUT_MS = 2_000L
        const val SERVER_TIMEOUT_MS = 3_000
        const val POLL_INTERVAL_MS = 100L
        const val FOREGROUND_STABILITY_MS = 300L
        const val NO_POLL_SAMPLE_MS = 1_000L
        const val LIFECYCLE_SAMPLE_MS = 5_000L
        const val NO_POLL_WINDOW_MS = 65_000L
        const val QUIET_CONFIRMATION_MS = 3_000L
        const val MIN_INTERVAL_GAP_MS = 58_000L
        const val MAX_INTERVAL_GAP_MS = 180_000L
        const val IMPORTANCE_MIN = 1
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_DEFAULT = 3
        const val EDIT_TEXT = "android.widget.EditText"
        val SUBJECT_PACKAGES = mapOf(
            SUBJECT_HANGEUL to HANGEUL_APP_PACKAGE,
            SUBJECT_MATH to MATH_APP_PACKAGE,
            SUBJECT_ENGLISH to ENGLISH_APP_PACKAGE,
        )
        val SUBJECT_NAMES = mapOf(
            SUBJECT_HANGEUL to HANGEUL_SUBJECT,
            SUBJECT_MATH to MATH_SUBJECT,
            SUBJECT_ENGLISH to ENGLISH_SUBJECT,
        )
        val SUBJECT_USER_IDS = mapOf(
            SUBJECT_HANGEUL to HANGEUL_USER_ID,
            SUBJECT_MATH to MATH_USER_ID,
            SUBJECT_ENGLISH to ENGLISH_USER_ID,
        )
        val REPORT_PATHS = setOf(HANGEUL_REPORT_PATH, MATH_REPORT_PATH, ENGLISH_REPORT_PATH)
        val CATALOG_PATHS = setOf(HANGEUL_CATALOG_PATH, MATH_CATALOG_PATH, ENGLISH_CATALOG_PATH)
        val SUBJECT_REPORT_PATHS = mapOf(
            SUBJECT_HANGEUL to HANGEUL_REPORT_PATH,
            SUBJECT_MATH to MATH_REPORT_PATH,
            SUBJECT_ENGLISH to ENGLISH_REPORT_PATH,
        )
    }
}
