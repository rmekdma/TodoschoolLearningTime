package com.example.todoschooltime.e2eobserver

import android.app.Notification
import android.app.UiAutomation
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Checks foreground subject polling through the observed app, real fixture requests, and active notifications. */
@RunWith(AndroidJUnit4::class)
class ForegroundPollingTest {
    private val accessibility = ObserverAccessibilitySupport(pollIntervalMs = POLL_INTERVAL_MS)
    private var screenWakeLock: PowerManager.WakeLock? = null

    @Before
    fun keepObserverScreenInteractive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(OBSERVER_PACKAGE, context.packageName)
        assertTrue("The device must already be awake and unlocked", isInteractive(context))
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

        val powerManager = instrumentation.context.getSystemService(PowerManager::class.java)
            ?: throw AssertionError("The observer cannot access PowerManager")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "$OBSERVER_PACKAGE:foreground-polling",
        ).apply { setReferenceCounted(false) }
        screenWakeLock = wakeLock
        wakeLock.acquire(SCREEN_WAKE_LOCK_TIMEOUT_MS)
        SystemClock.sleep(WAKE_LOCK_SETTLE_MS)
        assertTrue("The observer wake lock did not keep the display interactive", isInteractive(context))
    }

    @After
    fun releaseObserverScreenWakeLock() {
        val wakeLock = screenWakeLock ?: return
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } finally {
            screenWakeLock = null
        }
        assertTrue("The observer wake lock remained held after release", !wakeLock.isHeld)
    }

    @Test
    fun onlyRegisteredIncompleteForegroundSubjectIsPolledAtOneMinuteIntervals() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val apiBaseUrl = arguments.getString(ARG_API_BASE_URL)
            ?: throw AssertionError("Missing apiBaseUrl instrumentation argument")
        val runId = arguments.getString(ARG_RUN_ID)
            ?: throw AssertionError("Missing runId instrumentation argument")
        val context = instrumentation.targetContext
        val statePath = "/__ticket03/state"
        val startDate = ObserverTestSupport.seoulDateNumber()

        SUBJECT_PACKAGES.values.forEach { packageName ->
            assertNotNull("The real subject app is not installed: " + packageName, context.packageManager.getLaunchIntentForPackage(packageName))
        }
        assertNotNull("Android Settings is not installed", context.packageManager.getLaunchIntentForPackage(SETTINGS_PACKAGE))
        ObserverTestSupport.requireSilentChannel(ObserverTestSupport.readNotificationSnapshot(context))
        assertEquals(runId, fixtureRequest(apiBaseUrl, "GET", "/__ticket03/health").getString("runId"))

        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ObserverProductUi.openProductApp(instrumentation)
        enterProductUi(automation)
        ObserverProductUi.ensureUnregistered(automation, accessibility, context, TIMEOUT_MS)
        ObserverProductUi.writeTargets(automation, accessibility, TIMEOUT_MS)
        ObserverProductUi.ensureOneMinuteInterval(automation, accessibility, TIMEOUT_MS)

        fixtureRequest(apiBaseUrl, "POST", "/__ticket03/reset-requests", JSONObject())
        val countsReset = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket03/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", 2).put("math", 2).put("english", 2)),
        )
        assertEquals(runId, countsReset.getString("runId"))
        ObserverProductUi.ensureRegistered(automation, accessibility, TIMEOUT_MS)

        val baseline = ObserverTestSupport.awaitFixtureState(apiBaseUrl, statePath, runId, BASELINE_TIMEOUT_MS) { state ->
            val reports = ObserverTestSupport.reportRequests(state)
            SUBJECT_REPORT_PATHS.values.all { path ->
                reports.any { it.optString("path") == path && it.optInt("responseStatus", -1) == 200 }
            }
        }
        val baselineRequests = ObserverTestSupport.requests(baseline)
        ObserverTestSupport.assertFixtureRequestContract(baselineRequests)
        SUBJECT_REPORT_PATHS.values.forEach { path -> assertSuccessfulCount(baselineRequests, path, 2) }
        assertEquals(startDate, ObserverTestSupport.seoulDateNumber())
        assertBaselineVisible(automation)
        val baselineNotification = ObserverTestSupport.waitForNotification(context, BASELINE_TIMEOUT_MS) {
            it.optString("title") == CHILD_NAME + " 학습 상태" &&
                it.optString("bigText").contains("한글 미달성 2/6")
        }
        val baselineBody = baselineNotification.getString("bigText")
        assertTrue(baselineBody.contains("수학 미달성 2/5"))
        assertTrue(baselineBody.contains("영어 미달성 2/8"))
        assertTrue(
            "The baseline notification should retain repeat-update status flags",
            (baselineNotification.getInt("flags") and Notification.FLAG_ONLY_ALERT_ONCE) != 0,
        )
        val baselineId = baselineNotification.getInt("id")
        ObserverTestSupport.requireSilentChannel(ObserverTestSupport.readNotificationSnapshot(context))

        var pendingWindow: ForegroundReportWindow? = null
        SUBJECT_PACKAGES.forEach { (subjectKey, packageName) ->
            val before = fixtureState(apiBaseUrl, statePath, runId)
            assertEquals(
                "A prior fixture request was still in flight at the $subjectKey foreground transition: $before",
                0,
                before.getJSONArray("inFlightRequests").length(),
            )
            pendingWindow?.let { assertForegroundReportWindow(it, before, automation) }

            val beforeSequence = before.getLong("sequence")
            val path = SUBJECT_REPORT_PATHS.getValue(subjectKey)
            val window = ForegroundReportWindow(
                subjectKey = subjectKey,
                packageName = packageName,
                path = path,
                expectedCount = 2,
                minimumReports = if (subjectKey == "hangeul") 2 else 1,
                startSequence = beforeSequence,
                startMonotonicNs = before.getLong("serverMonotonicNs"),
            )
            launchPackage(context, packageName)
            waitForForegroundPackage(automation, packageName)

            ObserverTestSupport.awaitFixtureState(apiBaseUrl, statePath, runId, POLL_TIMEOUT_MS) { state ->
                ObserverTestSupport.requests(state).count {
                    it.optLong("sequence") > beforeSequence &&
                        it.optString("path") == path &&
                        it.optInt("responseStatus", -1) == 200
                } >= window.minimumReports && state.getJSONArray("inFlightRequests").length() == 0
            }
            pendingWindow = window
        }

        val beforeCompletionChange = fixtureState(apiBaseUrl, statePath, runId)
        assertEquals(
            "A fixture request was still in flight before changing the completion count: $beforeCompletionChange",
            0,
            beforeCompletionChange.getJSONArray("inFlightRequests").length(),
        )
        val completionChange = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket03/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", TARGET_HANGEUL)),
        )
        assertEquals(runId, completionChange.getString("runId"))
        val sequenceAtCompletionChange = completionChange.getLong("afterRequestSequence")
        assertTrue(sequenceAtCompletionChange >= beforeCompletionChange.getLong("sequence"))
        val beforeCompletionState = ObserverTestSupport.awaitFixtureState(apiBaseUrl, statePath, runId, TIMEOUT_MS) { state ->
            state.getJSONArray("inFlightRequests").length() == 0
        }
        pendingWindow?.let { assertForegroundReportWindow(it, beforeCompletionState, automation) }
        val beforeCompletion = beforeCompletionState.getLong("sequence")
        val completionWindow = ForegroundReportWindow(
            subjectKey = "hangeul completion",
            packageName = HANGEUL_APP_PACKAGE,
            path = HANGEUL_REPORT_PATH,
            expectedCount = TARGET_HANGEUL,
            startSequence = beforeCompletion,
            startMonotonicNs = beforeCompletionState.getLong("serverMonotonicNs"),
        )
        launchPackage(context, HANGEUL_APP_PACKAGE)
        waitForForegroundPackage(automation, HANGEUL_APP_PACKAGE)
        ObserverTestSupport.awaitFixtureState(apiBaseUrl, statePath, runId, POLL_TIMEOUT_MS) { state ->
            ObserverTestSupport.requests(state).any {
                it.optLong("sequence") > beforeCompletion &&
                    it.optString("path") == HANGEUL_REPORT_PATH &&
                    it.optInt("responseStatus", -1) == 200 &&
                    it.optInt("servedReportCount", -1) == TARGET_HANGEUL
            } && state.getJSONArray("inFlightRequests").length() == 0
        }
        val completionNotification = ObserverTestSupport.waitForNotification(context, POLL_TIMEOUT_MS) {
            it.optString("title") == CHILD_NAME + " · " + HANGEUL_SUBJECT + " 완료"
        }
        assertEquals(baselineId, completionNotification.getInt("id"))
        val completionBody = completionNotification.getString("bigText")
        assertTrue(completionBody.contains("✔ 한글 완료 6/6"))
        assertTrue(completionBody.contains("수학 미달성 2/5"))
        assertTrue(completionBody.contains("영어 미달성 2/8"))

        val activeAfterCompletion = ObserverTestSupport.activeNotifications(ObserverTestSupport.readNotificationSnapshot(context))
            .filter { it.optInt("id", -1) == baselineId }
        assertEquals(1, activeAfterCompletion.size)
        assertTrue(activeAfterCompletion.single().optString("bigText").contains("✔ 한글 완료 6/6"))

        val completedState = fixtureState(apiBaseUrl, statePath, runId)
        assertForegroundReportWindow(completionWindow, completedState, automation)
        val completedSequence = completedState.getLong("sequence")
        ObserverTestSupport.awaitNoNewRequests(
            apiBaseUrl,
            statePath,
            runId,
            completedSequence,
            NO_POLL_WINDOW_MS,
        )

        launchPackage(context, SETTINGS_PACKAGE)
        waitForForegroundPackage(automation, SETTINGS_PACKAGE)
        val settingsStart = fixtureState(apiBaseUrl, statePath, runId).getLong("sequence")
        ObserverTestSupport.awaitNoNewRequests(apiBaseUrl, statePath, runId, settingsStart, NO_POLL_WINDOW_MS)

        ObserverProductUi.openProductApp(instrumentation)
        ObserverProductUi.waitForChildScreen(automation, accessibility, TIMEOUT_MS)
        ObserverProductUi.ensureUnregistered(automation, accessibility, context, TIMEOUT_MS)
        assertTrue(
            "Unregistering the child should remove its active status notification",
            ObserverTestSupport.activeNotifications(ObserverTestSupport.readNotificationSnapshot(context))
                .none { it.optString("title").contains(CHILD_NAME) },
        )
        val unregisteredState = fixtureState(apiBaseUrl, statePath, runId)
        val unregisteredSequence = unregisteredState.getLong("sequence")
        launchPackage(context, ENGLISH_APP_PACKAGE)
        waitForForegroundPackage(automation, ENGLISH_APP_PACKAGE)
        val finalState = ObserverTestSupport.awaitNoNewRequests(
            apiBaseUrl,
            statePath,
            runId,
            unregisteredSequence,
            NO_POLL_WINDOW_MS,
        )
        ObserverTestSupport.assertFixtureRequestContract(ObserverTestSupport.requests(finalState))
        assertEquals(startDate, ObserverTestSupport.seoulDateNumber())
        assertTrue("The device display went to sleep during foreground polling", isInteractive(context))

        emitResult(runId)
    }

    private fun enterProductUi(automation: UiAutomation) {
        ObserverProductUi.enterFakeCredentialsIfNeeded(automation, accessibility, LOGIN_FORM_TIMEOUT_MS)
        if (accessibility.findNodeOrNull(automation, LOGIN_FORM_TIMEOUT_MS) {
                it.text?.toString() == "다시 로그인" && it.isClickable
            } != null
        ) {
            accessibility.clickText(automation, "다시 로그인", TIMEOUT_MS, "Could not open the login screen")
            ObserverProductUi.submitFakeCredentials(automation, accessibility, TIMEOUT_MS)
        }
        ObserverProductUi.waitForChildScreen(automation, accessibility, TIMEOUT_MS)
        ObserverProductUi.assertScreenCounts(automation, accessibility, 2, 2, 2, TIMEOUT_MS)
    }

    private fun assertBaselineVisible(automation: UiAutomation) {
        ObserverProductUi.assertScreenCounts(automation, accessibility, 2, 2, 2, TIMEOUT_MS)
    }

    private fun assertSuccessfulCount(requests: List<JSONObject>, path: String, count: Int) {
        val request = requests.lastOrNull {
            it.optString("path") == path && it.optInt("responseStatus", -1) == 200
        } ?: throw AssertionError("No successful fixture request for " + path)
        ObserverTestSupport.assertReportResponse(request, count)
    }

    private fun assertForegroundReportWindow(
        window: ForegroundReportWindow,
        state: JSONObject,
        automation: UiAutomation,
    ) {
        assertEquals(
            "The active window no longer matches the ${window.subjectKey} foreground window",
            window.packageName,
            automation.rootInActiveWindow?.packageName?.toString(),
        )
        assertEquals(
            "The ${window.subjectKey} foreground window ended with an in-flight fixture request: $state",
            0,
            state.getJSONArray("inFlightRequests").length(),
        )
        val endSequence = state.getLong("sequence")
        val endMonotonicNs = state.getLong("serverMonotonicNs")
        assertTrue(endSequence >= window.startSequence)
        assertTrue(endMonotonicNs >= window.startMonotonicNs)

        val requests = ObserverTestSupport.requests(state).filter {
            it.optLong("sequence") > window.startSequence && it.optLong("sequence") <= endSequence
        }
        ObserverTestSupport.assertFixtureRequestContract(requests)
        val reports = requests.filter { it.optString("path") in SUBJECT_REPORT_PATHS.values }
        assertTrue("The ${window.subjectKey} window contained no subject report", reports.isNotEmpty())
        assertTrue(
            "The ${window.subjectKey} window should contain at least ${window.minimumReports} successful subject reports: $reports",
            reports.size >= window.minimumReports,
        )
        assertEquals(
            "A foreground window polled another subject: " + reports,
            listOf(window.path),
            reports.map { it.getString("path") }.distinct(),
        )

        reports.forEachIndexed { index, report ->
            assertTrue(report.getLong("sequence") > window.startSequence)
            assertTrue(report.getLong("serverReceivedMonotonicNs") > window.startMonotonicNs)
            ObserverTestSupport.assertReportResponse(report, window.expectedCount)
            if (index > 0) {
                val previousReport = reports[index - 1]
                val intervalNs = report.getLong("serverReceivedMonotonicNs") -
                    previousReport.getLong("serverReceivedMonotonicNs")
                val intervalMs = intervalNs / NANOS_PER_MILLISECOND
                assertTrue(
                    "${window.subjectKey} reports were less than 58 seconds apart: $intervalMs ms",
                    intervalNs >= MIN_CADENCE_MS * NANOS_PER_MILLISECOND,
                )
                assertTrue(
                    "${window.subjectKey} reports exceeded the 180-second cadence bound: $intervalMs ms",
                    intervalNs <= MAX_CADENCE_MS * NANOS_PER_MILLISECOND,
                )
            }
        }

        val firstReportTimeNs = reports.first().getLong("serverReceivedMonotonicNs")
        val elapsedAfterFirstReportNs = endMonotonicNs - firstReportTimeNs
        assertTrue(elapsedAfterFirstReportNs >= 0L)
        val maximumReportsAllowedByCadence =
            1L + elapsedAfterFirstReportNs / (MIN_CADENCE_MS * NANOS_PER_MILLISECOND)
        assertTrue(
            "The ${window.subjectKey} window contained more reports than its duration and cadence allow: " + reports,
            reports.size.toLong() <= maximumReportsAllowedByCadence,
        )
        if (elapsedAfterFirstReportNs < MIN_CADENCE_MS * NANOS_PER_MILLISECOND) {
            assertEquals("A short foreground window should contain one report", 1, reports.size)
        }
    }

    private fun fixtureState(apiBaseUrl: String, statePath: String, runId: String): JSONObject {
        val state = fixtureRequest(apiBaseUrl, "GET", statePath)
        assertEquals(runId, state.getString("runId"))
        return state
    }

    private fun launchPackage(context: android.content.Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw AssertionError("App is not installed: " + packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }

    private fun waitForForegroundPackage(automation: UiAutomation, expectedPackage: String) {
        ObserverTestSupport.waitForValue(TIMEOUT_MS, POLL_INTERVAL_MS, "foreground package " + expectedPackage) {
            automation.rootInActiveWindow?.packageName?.toString()?.takeIf { it == expectedPackage }
        }
        SystemClock.sleep(FOREGROUND_SETTLE_MS)
        assertEquals(expectedPackage, automation.rootInActiveWindow?.packageName?.toString())
    }

    private fun isInteractive(context: android.content.Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun fixtureRequest(
        apiBaseUrl: String,
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject = ObserverTestSupport.fixtureRequest(apiBaseUrl, method, path, SERVER_TIMEOUT_MS, body)

    private fun emitResult(runId: String) {
        val json = JSONObject().put("runId", runId).put("scenario", "ticket03")
        val encoded = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        Log.i(RESULT_LOG_TAG, "$RESULT_CHUNK_MARKER$runId:1/1:$encoded")
    }

    private companion object {
        const val ARG_API_BASE_URL = "apiBaseUrl"
        const val ARG_RUN_ID = "runId"
        const val OBSERVER_PACKAGE = "com.example.todoschooltime.e2eobserver"
        const val CHILD_NAME = ObserverTestSupport.CHILD_NAME
        const val HANGEUL_REPORT_PATH = ObserverTestSupport.HANGEUL_REPORT_PATH
        const val HANGEUL_SUBJECT = ObserverTestSupport.HANGEUL_SUBJECT
        const val ENGLISH_SUBJECT = ObserverTestSupport.ENGLISH_SUBJECT
        const val TARGET_HANGEUL = ObserverProductUi.TARGET_HANGEUL
        const val TARGET_MATH = ObserverProductUi.TARGET_MATH
        const val TARGET_ENGLISH = ObserverProductUi.TARGET_ENGLISH
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val HANGEUL_APP_PACKAGE = "com.enuma.todohangeul"
        const val ENGLISH_APP_PACKAGE = "com.enuma.todoenglish"
        const val TIMEOUT_MS = 30_000L
        const val BASELINE_TIMEOUT_MS = 120_000L
        const val POLL_TIMEOUT_MS = 180_000L
        const val NO_POLL_WINDOW_MS = 65_000L
        const val MIN_CADENCE_MS = 58_000L
        const val MAX_CADENCE_MS = 180_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val SCREEN_WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L
        const val WAKE_LOCK_SETTLE_MS = 1_000L
        const val LOGIN_FORM_TIMEOUT_MS = 2_000L
        const val SERVER_TIMEOUT_MS = 5_000
        const val POLL_INTERVAL_MS = 200L
        const val FOREGROUND_SETTLE_MS = 400L
        const val RESULT_LOG_TAG = "Ticket03Result"
        const val RESULT_CHUNK_MARKER = "TICKET03_RESULT_CHUNK:"
        val SUBJECT_PACKAGES = linkedMapOf(
            "hangeul" to "com.enuma.todohangeul",
            "math" to "com.enuma.todomath",
            "english" to "com.enuma.todoenglish",
        )
        val SUBJECT_REPORT_PATHS = mapOf(
            "hangeul" to ObserverTestSupport.HANGEUL_REPORT_PATH,
            "math" to ObserverTestSupport.MATH_REPORT_PATH,
            "english" to ObserverTestSupport.ENGLISH_REPORT_PATH,
        )
    }

    private data class ForegroundReportWindow(
        val subjectKey: String,
        val packageName: String,
        val path: String,
        val expectedCount: Int,
        val minimumReports: Int = 1,
        val startSequence: Long,
        val startMonotonicNs: Long,
    )
}
