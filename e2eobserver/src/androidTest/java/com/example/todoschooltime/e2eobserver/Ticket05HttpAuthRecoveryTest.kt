package com.example.todoschooltime.e2eobserver

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real repository HTTP outcomes from a separate process while the product service remains bound. */
@RunWith(AndroidJUnit4::class)
class Ticket05HttpAuthRecoveryTest {
    @Test
    fun temporaryHttpFailureRetriesAndAuthenticationExpiryRequiresUiLogin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val apiBaseUrl = arguments.getString(ARG_API_BASE_URL)
            ?: throw AssertionError("Missing apiBaseUrl instrumentation argument")
        val runId = arguments.getString(ARG_RUN_ID)
            ?: throw AssertionError("Missing runId instrumentation argument")
        assertEquals(apiBaseUrl, Ticket05FixtureUrl.requireAllowed(apiBaseUrl))

        val powerManager = instrumentation.targetContext.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Ticket05:HttpAuthRecovery")
        wakeLock.acquire(MAX_WAKE_LOCK_MS)
        try {
            val result = runScenario(instrumentation, apiBaseUrl, runId)
            emitStructuredResult(runId, result)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun runScenario(instrumentation: Instrumentation, apiBaseUrl: String, runId: String): JSONObject {
        val observerContext = instrumentation.targetContext
        val packageManager = observerContext.packageManager
        val appInfo = packageManager.getApplicationInfo(PRODUCT_PACKAGE, 0)
        assertEquals(DEBUG_APPLICATION_LABEL, appInfo.loadLabel(packageManager).toString())
        val serviceInfo = packageManager.getServiceInfo(ComponentName(PRODUCT_PACKAGE, PRODUCT_SERVICE_CLASS), 0)
        assertEquals(DEBUG_ACCESSIBILITY_LABEL, serviceInfo.loadLabel(packageManager).toString())

        val beforeAutomation = readProductState(observerContext)
        requireConnectedMonitor(beforeAutomation)
        val initialRegistrations = beforeAutomation.getJSONArray("registeredChildren").strings().toSet()
        assertTrue(
            "Only an empty state or this fixture child may exist at test start: $beforeAutomation",
            initialRegistrations.isEmpty() || initialRegistrations == setOf(CHILD_NAME),
        )
        val initialNotifications = beforeAutomation.getJSONArray("activeNotifications").objects()
        assertTrue(
            "Only this fixture child's notification may exist at test start: $initialNotifications",
            initialNotifications.size <= 1 && initialNotifications.all { it.optString("title").contains(CHILD_NAME) },
        )
        val channel = requireSilentChannel(beforeAutomation)

        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val afterAutomation = readProductState(observerContext)
        requireConnectedMonitor(afterAutomation)
        requireSilentChannel(afterAutomation)
        val screenStartDispatchCursor = afterAutomation.getLong("httpRequestDispatchCursor")

        val expectedApiBaseUrl = apiBaseUrl.trimEnd('/')
        val health = fixtureRequest(expectedApiBaseUrl, "GET", "/__ticket05/health")
        assertEquals(runId, health.getString("runId"))

        openProductApp(instrumentation)
        enterFakeCredentialsIfNeeded(automation)
        assertScreenBaseline(automation, 2, 5, 7)
        val initialScreenUiPackage = assertProductScreenVisible(automation, "initial screen startup")
        var productState = waitForProductState(observerContext, TIMEOUT_MS) {
            it.optString("accountId") == ACCOUNT_ID
        }
        requireConnectedMonitor(productState)
        val initialScreenDispatches = dispatchesAfter(productState, screenStartDispatchCursor)
        assertTrue("The initial screen did not dispatch its full real HTTP query", initialScreenDispatches.size >= BASELINE_PATHS.size)
        val screenStartupDispatches = initialScreenDispatches.take(BASELINE_PATHS.size)
        val preflightMonitorDispatches = initialScreenDispatches.drop(BASELINE_PATHS.size)
        assertScreenDispatchCycle(screenStartupDispatches, BASELINE_PATHS, "initial screen startup")

        // A prior interrupted run can leave the fixture account paused. Recover it through the visible login UI.
        if (visibleText(automation).contains("다시 로그인")) {
            clickText(automation, "다시 로그인")
            submitFakeCredentials(automation)
            assertScreenBaseline(automation, 2, 5, 7)
            waitForNode(automation, TIMEOUT_MS) { it.text?.toString() == "감시 설정" }
            productState = waitForProductState(observerContext, TIMEOUT_MS) {
                it.optString("accountId") == ACCOUNT_ID && it.getBoolean("serviceLeaseAttached")
            }
        }

        val registrations = productState.getJSONArray("registeredChildren").strings().toSet()
        assertTrue("Unexpected child registrations: $registrations", registrations.isEmpty() || registrations == setOf(CHILD_NAME))
        if (registrations == setOf(CHILD_NAME)) {
            clickDescription(automation, "$CHILD_NAME 감시 해제")
            productState = waitForProductState(observerContext, TIMEOUT_MS) {
                it.getJSONArray("registeredChildren").length() == 0 && it.getJSONArray("activeNotifications").length() == 0
            }
            requireConnectedMonitor(productState)
        }

        writeDefaultTargetsThroughTheApp(automation)
        ensureOneMinuteInterval(automation)
        productState = readProductState(observerContext)
        requireConnectedMonitor(productState)
        assertEquals(0, productState.getJSONArray("activeNotifications").length())
        val baselineDispatchCursor = productState.getLong("httpRequestDispatchCursor")

        val reset = fixtureRequest(expectedApiBaseUrl, "POST", "/__ticket05/reset-requests", JSONObject())
        assertEquals(runId, reset.getString("runId"))
        assertTrue(reset.getBoolean("ok"))

        val notificationCursor = lastSequence(productState.getJSONArray("notificationEvents"))
        clickDescription(automation, "$CHILD_NAME 감시 등록")
        val baseline = waitForProductState(observerContext, TIMEOUT_MS) { state ->
            state.getJSONArray("registeredChildren").strings().contains(CHILD_NAME) && isExpectedBaseline(state)
        }
        requireConnectedMonitor(baseline)
        assertTrue("The initial monitor baseline did not include completed math", isExpectedBaseline(baseline))
        val baselineDispatches = dispatchesAfter(baseline, baselineDispatchCursor)
        assertDispatchCycle(baselineDispatches, BASELINE_PATHS, PRODUCT_PACKAGE, "initial monitor baseline")
        val baselineNotification = waitForNotification(observerContext, TIMEOUT_MS) { notification ->
            notification.optString("title") == "$CHILD_NAME 학습 상태" &&
                notification.optString("bigText").contains("✔ 수학 완료 5/5")
        }
        val baselineNotificationId = baselineNotification.getInt("id")
        val baselineBody = baselineNotification.getString("bigText")
        val checkedAtLine = baselineBody.lines().getOrNull(3)
            ?: throw AssertionError("The baseline notification omitted its last checked time: $baselineBody")
        assertTrue("The baseline notification has no last checked time: $baselineBody", checkedAtLine.isNotBlank())
        val baselineEvents = eventsAfter(baseline.getJSONArray("notificationEvents"), notificationCursor)
            .filter { it.optString("accountId") == ACCOUNT_ID && it.optString("childName") == CHILD_NAME }
        assertTrue("No actual notification request was observed for the monitor baseline", baselineEvents.isNotEmpty())
        assertTrue("The completed baseline requested a completion alert: $baselineEvents", baselineEvents.none { it.optBoolean("completionRequested") })
        assertTrue("The baseline notification did not reach NotificationManager: $baselineEvents", baselineEvents.all { it.optBoolean("notificationManagerCallReturned") })

        val baselineServer = awaitFixtureState(expectedApiBaseUrl, runId, TIMEOUT_MS) { state ->
            state.getJSONArray("requests").length() == 7
        }
        val baselineRequests = baselineServer.getJSONArray("requests").objects()
        assertRequestPaths(baselineRequests, 1L, 7L, BASELINE_PATHS, "initial monitor baseline")
        assertAllRequestsUseFakeAccountAndSeoulDate(baselineRequests)
        assertEquals(2, baselineRequests[4].getInt("servedReportCount"))
        assertEquals(5, baselineRequests[5].getInt("servedReportCount"))
        assertEquals(7, baselineRequests[6].getInt("servedReportCount"))

        val dispatchCursorBeforeFailure = baseline.getLong("httpRequestDispatchCursor")
        val beforeTemporaryFailure = lastSequence(baselineServer.getJSONArray("requests"))
        val armFailure = fixtureRequest(
            expectedApiBaseUrl,
            "POST",
            "/__ticket05/arm-report-response",
            JSONObject()
                .put("caseId", TEMPORARY_CASE_ID)
                .put("path", HANGEUL_REPORT_PATH)
                .put("responseStatus", 500),
        )
        assertEquals(runId, armFailure.getString("runId"))
        assertEquals(500, armFailure.getInt("responseStatus"))

        val foregroundCursorBeforeFailure = lastSequence(baseline.getJSONArray("foregroundEvents"))
        launchActualSubjectApp(observerContext, HANGEUL_APP_PACKAGE)
        waitForActualForegroundAndConnectedService(automation, observerContext, foregroundCursorBeforeFailure, HANGEUL_APP_PACKAGE)
        val failedServer = awaitFixtureState(expectedApiBaseUrl, runId, POLL_TIMEOUT_MS) { state ->
            state.getJSONArray("requests").objects().any { it.optString("caseId") == TEMPORARY_CASE_ID }
        }
        val failedRequest = failedServer.getJSONArray("requests").objects().single { it.optString("caseId") == TEMPORARY_CASE_ID }
        assertEquals(HANGEUL_REPORT_PATH, failedRequest.getString("path"))
        assertEquals(500, failedRequest.getInt("responseStatus"))
        assertEquals("written", failedRequest.getString("deliveryStatus"))
        assertEquals(AUTHORIZATION, failedRequest.getJSONObject("headers").getString("authorization"))
        assertEquals("ticket01-hangeul", failedRequest.getJSONObject("body").getString("userId"))
        assertEquals(seoulDateNumber(), failedRequest.getJSONObject("body").getInt("yyyymmdd"))

        val failedSnapshot = waitForProductState(observerContext, TIMEOUT_MS) { state ->
            state.getBoolean("serviceLeaseAttached") && subject(state, HANGEUL_SUBJECT).let {
                it.optString("status") == "BELOW_TARGET" && it.optInt("activityCount", -1) == 2
            } && state.getJSONArray("activeNotifications").objects().any {
                it.optInt("id", -1) == baselineNotificationId && it.optString("bigText").contains("HTTP 500")
            }
        }
        requireConnectedMonitor(failedSnapshot)
        val failedNotification = failedSnapshot.getJSONArray("activeNotifications").objects().single { it.optInt("id", -1) == baselineNotificationId }
        val failedBody = failedNotification.getString("bigText")
        assertTrue("Temporary failure erased the last successful checked time: $failedBody", failedBody.contains(checkedAtLine))
        assertTrue("Temporary failure changed the last successful activity count: $failedBody", failedBody.contains("한글 미달성 2/6"))
        val failedEvents = eventsAfter(failedSnapshot.getJSONArray("notificationEvents"), notificationCursor)
            .filter { it.optString("accountId") == ACCOUNT_ID && it.optString("childName") == CHILD_NAME }
        assertTrue("The HTTP 500 failure requested a completion alert: $failedEvents", failedEvents.none { it.optBoolean("completionRequested") })
        val failureDispatches = dispatchesAfter(failedSnapshot, dispatchCursorBeforeFailure)
        assertDispatchCycle(failureDispatches, HANGEUL_CYCLE_PATHS, HANGEUL_APP_PACKAGE, "temporary failure")
        val failedSequence = failedRequest.getLong("sequence")
        assertRequestPaths(
            failedServer.getJSONArray("requests").objects(),
            beforeTemporaryFailure + 1,
            failedSequence,
            REGULAR_HANGEUL_PATHS,
            "HTTP 500 cycle",
        )

        val recoveredServer = awaitFixtureState(expectedApiBaseUrl, runId, POLL_TIMEOUT_MS) { state ->
            state.getJSONArray("requests").objects().any {
                it.optString("path") == HANGEUL_REPORT_PATH &&
                    it.optLong("sequence") > failedSequence && it.optInt("responseStatus", -1) == 200
            }
        }
        val recoveredRequest = recoveredServer.getJSONArray("requests").objects().first {
            it.optString("path") == HANGEUL_REPORT_PATH &&
                it.optLong("sequence") > failedSequence && it.optInt("responseStatus", -1) == 200
        }
        val failureCycleStartRequest = failedServer.getJSONArray("requests").objects().single {
            it.optLong("sequence") == beforeTemporaryFailure + 1
        }
        val retryCycleStartSequence = failedSequence + 1
        val retryCycleStartRequest = recoveredServer.getJSONArray("requests").objects().single {
            it.optLong("sequence") == retryCycleStartSequence
        }
        assertEquals(SIGN_IN_PATH, failureCycleStartRequest.getString("path"))
        assertEquals(SIGN_IN_PATH, retryCycleStartRequest.getString("path"))
        val serverCycleStartIntervalNs = retryCycleStartRequest.getLong("serverReceivedMonotonicNs") -
            failureCycleStartRequest.getLong("serverReceivedMonotonicNs")
        val failedCycleStartDispatch = failureDispatches.first { it.optString("path") == SIGN_IN_PATH }
        assertTrue("Fixture sign-in cycle starts are not ordered within the bounded retry window: ${serverCycleStartIntervalNs / 1_000_000} ms", serverCycleStartIntervalNs > 0 && serverCycleStartIntervalNs < MAX_POLL_DELAY_NS)
        assertEquals(2, recoveredRequest.getInt("servedReportCount"))
        assertEquals(AUTHORIZATION, recoveredRequest.getJSONObject("headers").getString("authorization"))
        val recoveryStartSequence = retryCycleStartSequence
        assertRequestPaths(
            recoveredServer.getJSONArray("requests").objects(),
            recoveryStartSequence,
            recoveredRequest.getLong("sequence"),
            REGULAR_HANGEUL_PATHS,
            "HTTP 500 recovery cycle",
        )
        val recoveredSnapshot = waitForProductState(observerContext, TIMEOUT_MS) { state ->
            state.getBoolean("serviceLeaseAttached") && subject(state, HANGEUL_SUBJECT).let {
                it.optString("status") == "BELOW_TARGET" && it.optInt("activityCount", -1) == 2
            } && state.getJSONArray("activeNotifications").objects().any {
                it.optInt("id", -1) == baselineNotificationId && !it.optString("bigText").contains("HTTP 500")
            }
        }
        val recoveredNotification = recoveredSnapshot.getJSONArray("activeNotifications").objects().single { it.optInt("id", -1) == baselineNotificationId }
        val recoveredBody = recoveredNotification.getString("bigText")
        assertTrue("Recovery did not refresh the last successful checked time: $recoveredBody", checkedAtLine !in recoveredBody)
        val recoveryDispatches = dispatchesAfter(recoveredSnapshot, lastDispatchSequence(failureDispatches))
        assertDispatchCycle(recoveryDispatches, HANGEUL_CYCLE_PATHS, HANGEUL_APP_PACKAGE, "temporary failure retry")
        val retryCycleStartDispatch = recoveryDispatches.first { it.optString("path") == SIGN_IN_PATH }
        val dispatchCycleStartIntervalMs = retryCycleStartDispatch.getLong("elapsedRealtimeMs") -
            failedCycleStartDispatch.getLong("elapsedRealtimeMs")
        assertTrue(
            "The one-minute monitor interval was shorter than 60 seconds between actual sign-in body dispatches: ${dispatchCycleStartIntervalMs} ms",
            dispatchCycleStartIntervalMs >= POLL_INTERVAL_NS / 1_000_000L,
        )
        assertTrue("The one-minute monitor retry exceeded its bounded window at actual sign-in body dispatch: ${dispatchCycleStartIntervalMs} ms", dispatchCycleStartIntervalMs < MAX_POLL_DELAY_NS / 1_000_000L)
        val recoveredEvents = eventsAfter(recoveredSnapshot.getJSONArray("notificationEvents"), notificationCursor)
            .filter { it.optString("accountId") == ACCOUNT_ID && it.optString("childName") == CHILD_NAME }
        assertTrue("The retry requested a completion alert for an unchanged incomplete result: $recoveredEvents", recoveredEvents.none { it.optBoolean("completionRequested") })

        val dispatchCursorBeforeAuthFailure = recoveredSnapshot.getLong("httpRequestDispatchCursor")
        val beforeAuthFailure = lastSequence(recoveredServer.getJSONArray("requests"))
        val armExpiredAuth = fixtureRequest(
            expectedApiBaseUrl,
            "POST",
            "/__ticket05/arm-report-response",
            JSONObject()
                .put("caseId", AUTH_CASE_ID)
                .put("path", HANGEUL_REPORT_PATH)
                .put("responseStatus", 401),
        )
        assertEquals(runId, armExpiredAuth.getString("runId"))
        assertEquals(401, armExpiredAuth.getInt("responseStatus"))

        val authFailureServer = awaitFixtureState(expectedApiBaseUrl, runId, POLL_TIMEOUT_MS) { state ->
            state.getJSONArray("requests").objects().any { it.optString("caseId") == AUTH_CASE_ID }
        }
        val authFailureRequest = authFailureServer.getJSONArray("requests").objects().single { it.optString("caseId") == AUTH_CASE_ID }
        assertEquals(HANGEUL_REPORT_PATH, authFailureRequest.getString("path"))
        assertEquals(401, authFailureRequest.getInt("responseStatus"))
        assertEquals("written", authFailureRequest.getString("deliveryStatus"))
        assertEquals(AUTHORIZATION, authFailureRequest.getJSONObject("headers").getString("authorization"))
        val authFailureSnapshot = waitForProductState(observerContext, TIMEOUT_MS) { state ->
            state.getJSONArray("registeredChildren").strings().toSet() == setOf(CHILD_NAME) &&
                subject(state, HANGEUL_SUBJECT).let {
                    it.optString("status") == "BELOW_TARGET" && it.optInt("activityCount", -1) == 2
                } && state.getJSONArray("activeNotifications").objects().any {
                    it.optInt("id", -1) == baselineNotificationId && it.optString("bigText").contains("다시 로그인 필요")
                }
        }
        requireConnectedMonitor(authFailureSnapshot)
        val authFailureDispatches = dispatchesAfter(authFailureSnapshot, dispatchCursorBeforeAuthFailure)
        assertDispatchCycle(authFailureDispatches, HANGEUL_CYCLE_PATHS, HANGEUL_APP_PACKAGE, "authentication failure")
        val authFailureSequence = authFailureRequest.getLong("sequence")
        assertRequestPaths(
            authFailureServer.getJSONArray("requests").objects(),
            beforeAuthFailure + 1,
            authFailureSequence,
            REGULAR_HANGEUL_PATHS,
            "HTTP 401 authentication cycle",
        )
        val authFailureBody = authFailureSnapshot.getJSONArray("activeNotifications").objects()
            .single { it.optInt("id", -1) == baselineNotificationId }.getString("bigText")
        assertTrue("Authentication failure discarded the last successful checked time: $authFailureBody", authFailureBody.contains(recoveredBody.lines().getOrNull(3).orEmpty()))
        val beforePausedWait = fixtureRequest(expectedApiBaseUrl, "GET", "/__ticket05/state")
        val pausedDispatchCursor = authFailureSnapshot.getLong("httpRequestDispatchCursor")
        SystemClock.sleep(AUTH_PAUSE_WINDOW_MS)
        val afterPausedWait = fixtureRequest(expectedApiBaseUrl, "GET", "/__ticket05/state")
        assertEquals("Periodic HTTP requests continued while authentication was required", authFailureSequence, afterPausedWait.getLong("sequence"))
        assertEquals("A product HTTP request was dispatched during the paused interval", pausedDispatchCursor, readProductState(observerContext).getLong("httpRequestDispatchCursor"))
        assertEquals(beforePausedWait.getLong("sequence"), afterPausedWait.getLong("sequence"))
        val pauseStartNs = beforePausedWait.getLong("serverMonotonicNs")
        val pauseEndNs = afterPausedWait.getLong("serverMonotonicNs")
        val requestsDuringPause = afterPausedWait.getJSONArray("requests").objects().filter {
            it.optLong("serverReceivedMonotonicNs") in (pauseStartNs + 1)..pauseEndNs
        }
        assertTrue("The fixture received a request during the authentication pause: $requestsDuringPause", requestsDuringPause.isEmpty())

        val countChanged = fixtureRequest(
            expectedApiBaseUrl,
            "POST",
            "/__ticket05/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", TARGET_HANGEUL)),
        )
        assertEquals(runId, countChanged.getString("runId"))
        assertEquals(TARGET_HANGEUL, countChanged.getJSONObject("reportCounts").getInt("hangeul"))
        assertEquals(authFailureSequence, countChanged.getLong("afterRequestSequence"))

        openProductApp(instrumentation)
        assertScreenBaseline(automation, TARGET_HANGEUL, TARGET_MATH, 7)
        val screenStartupAfterPauseUiPackage = assertProductScreenVisible(automation, "screen startup while authentication remains paused")
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString() == "다시 로그인" && it.isClickable }
        val stateBeforeHumanLogin = readProductState(observerContext)
        assertEquals(setOf(CHILD_NAME), stateBeforeHumanLogin.getJSONArray("registeredChildren").strings().toSet())
        val startupAfterPauseDispatches = dispatchesAfter(stateBeforeHumanLogin, pausedDispatchCursor)
        assertScreenDispatchCycle(startupAfterPauseDispatches, BASELINE_PATHS, "screen startup while authentication remains paused")
        val startupAfterPauseServer = awaitFixtureState(expectedApiBaseUrl, runId, TIMEOUT_MS) { state ->
            state.getJSONArray("requests").objects().any { it.optLong("sequence") == authFailureSequence + BASELINE_PATHS.size }
        }
        assertRequestPaths(
            startupAfterPauseServer.getJSONArray("requests").objects(),
            authFailureSequence + 1,
            authFailureSequence + BASELINE_PATHS.size,
            BASELINE_PATHS,
            "screen startup while authentication remains paused",
        )
        assertAllRequestsUseFakeAccountAndSeoulDate(startupAfterPauseServer.getJSONArray("requests").objects())
        val beforeHumanLoginServerSequence = authFailureSequence + BASELINE_PATHS.size
        val loginDispatchCursor = stateBeforeHumanLogin.getLong("httpRequestDispatchCursor")
        clickText(automation, "다시 로그인")
        submitFakeCredentials(automation)
        assertScreenBaseline(automation, TARGET_HANGEUL, TARGET_MATH, 7)
        val uiReauthenticationScreenPackage = assertProductScreenVisible(automation, "explicit UI re-login screen query")
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString() == "감시 설정" }

        val reauthenticatedSnapshot = waitForProductState(observerContext, POLL_TIMEOUT_MS) { state ->
            state.getJSONArray("registeredChildren").strings().toSet() == setOf(CHILD_NAME) &&
                isRecoveryBaseline(state)
        }
        requireConnectedMonitor(reauthenticatedSnapshot)
        val authRecoveryUiDispatches = dispatchesAfter(reauthenticatedSnapshot, loginDispatchCursor)
        assertEquals("The explicit UI re-login should run a screen request and then the monitor's recovery baseline", BASELINE_PATHS.size * 2, authRecoveryUiDispatches.size)
        val uiReauthenticationScreenDispatches = authRecoveryUiDispatches.take(BASELINE_PATHS.size)
        val monitorRecoveryBaselineDispatches = authRecoveryUiDispatches.drop(BASELINE_PATHS.size)
        assertScreenDispatchCycle(uiReauthenticationScreenDispatches, BASELINE_PATHS, "explicit UI re-login screen request")
        assertDispatchCycle(monitorRecoveryBaselineDispatches, BASELINE_PATHS, PRODUCT_PACKAGE, "automatic monitor recovery baseline")
        val reauthenticatedNotification = waitForNotification(observerContext, TIMEOUT_MS) { notification ->
            notification.optInt("id", -1) == baselineNotificationId &&
                notification.optString("title") == "$CHILD_NAME 학습 상태" &&
                notification.optString("bigText").contains("✔ 한글 완료 6/6")
        }
        val reauthenticatedBody = reauthenticatedNotification.getString("bigText")
        assertTrue("Recovery baseline omitted the previously complete math subject: $reauthenticatedBody", reauthenticatedBody.contains("✔ 수학 완료 5/5"))
        assertTrue("Recovery baseline omitted the still-incomplete English subject: $reauthenticatedBody", reauthenticatedBody.contains("영어 미달성 7/8"))
        val afterReauthenticationEvents = eventsAfter(reauthenticatedSnapshot.getJSONArray("notificationEvents"), notificationCursor)
            .filter { it.optString("accountId") == ACCOUNT_ID && it.optString("childName") == CHILD_NAME }
        assertTrue("The disconnected completion generated a completion alert during login recovery: $afterReauthenticationEvents", afterReauthenticationEvents.none { it.optBoolean("completionRequested") })
        assertEquals(setOf(CHILD_NAME), reauthenticatedSnapshot.getJSONArray("registeredChildren").strings().toSet())

        val reauthenticationServer = awaitFixtureState(expectedApiBaseUrl, runId, TIMEOUT_MS) { state ->
            state.getJSONArray("requests").objects().any { it.optLong("sequence") == beforeHumanLoginServerSequence + BASELINE_PATHS.size * 2 }
        }
        assertRequestPaths(
            reauthenticationServer.getJSONArray("requests").objects(),
            beforeHumanLoginServerSequence + 1,
            beforeHumanLoginServerSequence + BASELINE_PATHS.size,
            BASELINE_PATHS,
            "explicit UI re-login screen request",
        )
        assertRequestPaths(
            reauthenticationServer.getJSONArray("requests").objects(),
            beforeHumanLoginServerSequence + BASELINE_PATHS.size + 1,
            beforeHumanLoginServerSequence + BASELINE_PATHS.size * 2,
            BASELINE_PATHS,
            "automatic monitor recovery baseline",
        )
        val reauthenticationRequest = reauthenticationServer.getJSONArray("requests").objects().single {
            it.optLong("sequence") in (beforeHumanLoginServerSequence + BASELINE_PATHS.size + 1)..(beforeHumanLoginServerSequence + BASELINE_PATHS.size * 2) &&
                it.optString("path") == HANGEUL_REPORT_PATH && it.optInt("servedReportCount", -1) == TARGET_HANGEUL &&
                it.optInt("responseStatus", -1) == 200
        }
        assertAllRequestsUseFakeAccountAndSeoulDate(reauthenticationServer.getJSONArray("requests").objects())
        assertTrue("No successful report after UI login confirmed the disconnected completion", reauthenticationRequest.getLong("sequence") > authFailureSequence)

        val dispatchCursorBeforeNewCompletion = reauthenticatedSnapshot.getLong("httpRequestDispatchCursor")
        val foregroundCursorBeforeEnglish = lastSequence(reauthenticatedSnapshot.getJSONArray("foregroundEvents"))
        val beforeEnglishCompletion = lastSequence(reauthenticationServer.getJSONArray("requests"))
        val changedEnglish = fixtureRequest(
            expectedApiBaseUrl,
            "POST",
            "/__ticket05/set-report-counts",
            JSONObject().put("reports", JSONObject().put("english", TARGET_ENGLISH)),
        )
        assertEquals(TARGET_ENGLISH, changedEnglish.getJSONObject("reportCounts").getInt("english"))
        launchActualSubjectApp(observerContext, ENGLISH_APP_PACKAGE)
        waitForActualForegroundAndConnectedService(automation, observerContext, foregroundCursorBeforeEnglish, ENGLISH_APP_PACKAGE)
        val completedServer = awaitFixtureState(expectedApiBaseUrl, runId, POLL_TIMEOUT_MS) { state ->
            state.getJSONArray("requests").objects().any {
                it.optLong("sequence") > beforeEnglishCompletion && it.optString("path") == ENGLISH_REPORT_PATH &&
                    it.optInt("responseStatus", -1) == 200 && it.optInt("servedReportCount", -1) == TARGET_ENGLISH
            }
        }
        val englishRequest = completedServer.getJSONArray("requests").objects().first {
            it.optLong("sequence") > beforeEnglishCompletion && it.optString("path") == ENGLISH_REPORT_PATH &&
                it.optInt("responseStatus", -1) == 200 && it.optInt("servedReportCount", -1) == TARGET_ENGLISH
        }
        assertRequestPaths(
            completedServer.getJSONArray("requests").objects(),
            beforeEnglishCompletion + 1,
            englishRequest.getLong("sequence"),
            REGULAR_ENGLISH_PATHS,
            "post-relogin English completion cycle",
        )
        val completionSnapshot = waitForProductState(observerContext, POLL_TIMEOUT_MS) { state ->
            state.getBoolean("serviceLeaseAttached") && subject(state, ENGLISH_SUBJECT).let {
                it.optString("status") == "COMPLETE" && it.optInt("activityCount", -1) == TARGET_ENGLISH
            }
        }
        val allCompletionEvents = eventsAfter(completionSnapshot.getJSONArray("notificationEvents"), notificationCursor)
            .filter { it.optString("accountId") == ACCOUNT_ID && it.optString("childName") == CHILD_NAME && it.optBoolean("completionRequested") }
        assertEquals("Only the new post-relogin completion should request a completion alert", 1, allCompletionEvents.size)
        val completionEvent = allCompletionEvents.single()
        assertEquals(ENGLISH_SUBJECT, completionEvent.getString("completionSubjectName"))
        assertEquals(baselineNotificationId, completionEvent.getInt("notificationId"))
        assertTrue(completionEvent.getBoolean("notificationManagerCallReturned"))
        val completionNotification = waitForNotification(observerContext, TIMEOUT_MS) { notification ->
            notification.optInt("id", -1) == baselineNotificationId &&
                notification.optString("title") == "$CHILD_NAME · $ENGLISH_SUBJECT 완료"
        }
        val completedBody = completionNotification.getString("bigText")
        assertTrue("The completion notification omitted the offline hangeul completion: $completedBody", completedBody.contains("✔ 한글 완료 6/6"))
        assertTrue("The completion notification omitted the baseline math completion: $completedBody", completedBody.contains("✔ 수학 완료 5/5"))
        assertTrue("The completion notification omitted newly complete English: $completedBody", completedBody.contains("✔ 영어 완료 8/8"))
        val finalProductState = readProductState(observerContext)
        val activeNotifications = finalProductState.getJSONArray("activeNotifications").objects()
        assertEquals(listOf(baselineNotificationId), activeNotifications.map { it.getInt("id") })
        assertEquals(setOf(CHILD_NAME), finalProductState.getJSONArray("registeredChildren").strings().toSet())
        requireConnectedMonitor(finalProductState)
        requireSilentChannel(finalProductState)

        val completionDispatches = dispatchesAfter(completionSnapshot, dispatchCursorBeforeNewCompletion)
        assertDispatchCycle(completionDispatches, ENGLISH_CYCLE_PATHS, ENGLISH_APP_PACKAGE, "new English completion")
        assertAllRequestsUseFakeAccountAndSeoulDate(completedServer.getJSONArray("requests").objects())

        return JSONObject()
            .put("scenario", "ticket05")
            .put("runId", runId)
            .put("apiBaseUrl", apiBaseUrl)
            .put("deviceApiLevel", android.os.Build.VERSION.SDK_INT)
            .put("productPackage", PRODUCT_PACKAGE)
            .put("observerPackage", observerContext.packageName)
            .put("fixtureAccountId", ACCOUNT_ID)
            .put("seoulDateYyyymmdd", seoulDateNumber())
            .put("fakeCredentialsUsed", true)
            .put("serviceLeaseAttached", finalProductState.getBoolean("serviceLeaseAttached"))
            .put("notificationChannelImportance", channel.getInt("importance"))
            .put("notificationChannelSoundUriPresent", channel.optString("soundUri") != "null")
            .put("notificationChannelVibrationEnabled", channel.optBoolean("vibrationEnabled"))
            .put("screenStartupDispatches", jsonObjects(screenStartupDispatches))
            .put("screenStartupUiPackage", initialScreenUiPackage)
            .put("preflightMonitorDispatches", jsonObjects(preflightMonitorDispatches))
            .put("initialMonitorBaselineDispatches", jsonObjects(baselineDispatches))
            .put("screenStartupAfterAuthPauseDispatches", jsonObjects(startupAfterPauseDispatches))
            .put("screenStartupAfterAuthPauseUiPackage", screenStartupAfterPauseUiPackage)
            .put("explicitUiReauthenticationScreenDispatches", jsonObjects(uiReauthenticationScreenDispatches))
            .put("explicitUiReauthenticationScreenPackage", uiReauthenticationScreenPackage)
            .put("monitorRecoveryBaselineDispatches", jsonObjects(monitorRecoveryBaselineDispatches))
            .put("interactiveUiDispatchesAfterAuthPause", jsonObjects(authRecoveryUiDispatches))
            .put("initialBaselineServerRequestSequences", longArrayJson(baselineRequests.map { it.getLong("sequence") }))
            .put("temporaryFailure", JSONObject()
                .put("caseId", TEMPORARY_CASE_ID)
                .put("responseStatus", failedRequest.getInt("responseStatus"))
                .put("serverRequestSequence", failedSequence)
                .put("previousActivityCount", 2)
                .put("activityCountAfterFailure", subject(failedSnapshot, HANGEUL_SUBJECT).getInt("activityCount"))
                .put("lastCheckedAtPreserved", true)
                .put("errorVisibleInPostedNotification", failedBody.contains("HTTP 500"))
                .put("completionAlertRequested", failedEvents.any { it.optBoolean("completionRequested") })
                .put("retryRequestSequence", recoveredRequest.getLong("sequence"))
                .put("failedCycleStartServerSequence", failureCycleStartRequest.getLong("sequence"))
                .put("retryCycleStartServerSequence", retryCycleStartRequest.getLong("sequence"))
                .put("failedCycleStartServerReceivedMonotonicNs", failureCycleStartRequest.getLong("serverReceivedMonotonicNs"))
                .put("retryCycleStartServerReceivedMonotonicNs", retryCycleStartRequest.getLong("serverReceivedMonotonicNs"))
                .put("serverCycleStartIntervalMs", serverCycleStartIntervalNs / 1_000_000L)
                .put("failedCycleStartDispatchSequence", failedCycleStartDispatch.getLong("sequence"))
                .put("retryCycleStartDispatchSequence", retryCycleStartDispatch.getLong("sequence"))
                .put("failedCycleStartDispatchElapsedRealtimeMs", failedCycleStartDispatch.getLong("elapsedRealtimeMs"))
                .put("retryCycleStartDispatchElapsedRealtimeMs", retryCycleStartDispatch.getLong("elapsedRealtimeMs"))
                .put("dispatchCycleStartIntervalMs", dispatchCycleStartIntervalMs)
                .put("reportToReportReceiveIntervalMs", (recoveredRequest.getLong("serverReceivedMonotonicNs") - failedRequest.getLong("serverReceivedMonotonicNs")) / 1_000_000L)
                .put("cadenceMeasuredFromActualSignInBodyDispatch", true)
                .put("recovered", !recoveredBody.contains("HTTP 500"))
                .put("cyclePaths", stringArrayJson(REGULAR_HANGEUL_PATHS)))
            .put("authenticationRecovery", JSONObject()
                .put("caseId", AUTH_CASE_ID)
                .put("responseStatus", authFailureRequest.getInt("responseStatus"))
                .put("serverRequestSequence", authFailureSequence)
                .put("registrationPreserved", authFailureSnapshot.getJSONArray("registeredChildren").strings().toSet() == setOf(CHILD_NAME))
                .put("reloginRequiredVisible", authFailureBody.contains("다시 로그인 필요"))
                .put("periodicRequestsDuringPause", 0)
                .put("pauseWindowMs", (pauseEndNs - pauseStartNs) / 1_000_000L)
                .put("pauseServerSequenceAtStart", beforePausedWait.getLong("sequence"))
                .put("pauseServerSequenceAtEnd", afterPausedWait.getLong("sequence"))
                .put("pauseServerMonotonicNsAtStart", pauseStartNs)
                .put("pauseServerMonotonicNsAtEnd", pauseEndNs)
                .put("humanLoginViaUi", true)
                .put("recoveryBaselineHangeulCount", subject(reauthenticatedSnapshot, HANGEUL_SUBJECT).getInt("activityCount"))
                .put("recoveryBaselineCompletionAlerts", afterReauthenticationEvents.count { it.optBoolean("completionRequested") })
                .put("registrationPreservedAfterLogin", reauthenticatedSnapshot.getJSONArray("registeredChildren").strings().toSet() == setOf(CHILD_NAME)))
            .put("screenStartupServerRequestSummary", requestSummaries(startupAfterPauseServer.getJSONArray("requests").objects().filter {
                it.optLong("sequence") in (authFailureSequence + 1)..beforeHumanLoginServerSequence
            }))
            .put("uiReauthenticationServerRequestSummary", requestSummaries(reauthenticationServer.getJSONArray("requests").objects().filter {
                it.optLong("sequence") > beforeHumanLoginServerSequence
            }))
            .put("newCompletion", JSONObject()
                .put("subject", ENGLISH_SUBJECT)
                .put("activityCount", TARGET_ENGLISH)
                .put("completionAlertRequests", allCompletionEvents.size)
                .put("notificationManagerCallReturned", completionEvent.getBoolean("notificationManagerCallReturned"))
                .put("notificationIdReused", completionEvent.getInt("notificationId") == baselineNotificationId)
                .put("foregroundPackage", ENGLISH_APP_PACKAGE)
                .put("dispatches", jsonObjects(completionDispatches)))
            .put("failureCycleDispatches", jsonObjects(failureDispatches))
            .put("recoveryCycleDispatches", jsonObjects(recoveryDispatches))
            .put("authenticationFailureCycleDispatches", jsonObjects(authFailureDispatches))
            .put("initialBaselineRequestSummary", requestSummaries(baselineRequests))
            .put("fullFixtureRequestSummary", requestSummaries(completedServer.getJSONArray("requests").objects()))
            .put("finalActiveNotificationCount", activeNotifications.size)
            .put("productAlertSoundOrHeadsUpTested", false)
    }

    private fun openProductApp(instrumentation: Instrumentation) {
        val intent = instrumentation.targetContext.packageManager.getLaunchIntentForPackage(PRODUCT_PACKAGE)
            ?: throw AssertionError("Debug app has no visible launcher activity: $PRODUCT_PACKAGE")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.targetContext.startActivity(intent)
    }

    private fun enterFakeCredentialsIfNeeded(automation: UiAutomation) {
        val email = findNodeOrNull(automation, LOGIN_FORM_TIMEOUT_MS) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 이메일" || it.text?.toString() == "토도스쿨 이메일")
        } ?: return
        setField(email, TEST_EMAIL)
        submitFakeCredentials(automation)
    }

    private fun submitFakeCredentials(automation: UiAutomation) {
        val password = waitForNode(automation, TIMEOUT_MS) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 비밀번호" || it.text?.toString() == "토도스쿨 비밀번호")
        }
        val email = findNodeOrNull(automation, TIMEOUT_MS) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 이메일" || it.text?.toString() == "토도스쿨 이메일")
        } ?: throw AssertionError("The actual login dialog omitted its fake account email field")
        setField(email, TEST_EMAIL)
        setField(password, TEST_PASSWORD)
        clickText(automation, "확인")
    }

    private fun assertScreenBaseline(automation: UiAutomation, hangeul: Int, math: Int, english: Int) {
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString()?.contains(CHILD_NAME) == true }
        waitForCondition(TIMEOUT_MS, "the requested screen report baseline") {
            val text = visibleText(automation).joinToString("\n")
            text.contains("${if (hangeul >= TARGET_HANGEUL) "✔ " else ""}한글 ($hangeul/$TARGET_HANGEUL)") &&
                text.contains("${if (math >= TARGET_MATH) "✔ " else ""}수학 ($math/$TARGET_MATH)") &&
                text.contains("${if (english >= TARGET_ENGLISH) "✔ " else ""}영어 ($english/$TARGET_ENGLISH)")
        }
    }

    private fun writeDefaultTargetsThroughTheApp(automation: UiAutomation) {
        clickDescription(automation, "$CHILD_NAME 목표 설정")
        val fields = findAllNodes(automation, TIMEOUT_MS) { it.className == EDIT_TEXT }
        assertEquals("The target dialog should show all three subscribed subjects", 3, fields.size)
        listOf(TARGET_HANGEUL, TARGET_MATH, TARGET_ENGLISH).forEachIndexed { index, target -> setField(fields[index], target.toString()) }
        clickText(automation, "저장")
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString()?.contains("한글 (2/6)") == true }
    }

    private fun ensureOneMinuteInterval(automation: UiAutomation) {
        val intervalLabel = visibleText(automation).firstOrNull { it.startsWith("감시 간격 ") }
            ?: throw AssertionError("The monitor interval control was not visible")
        if (intervalLabel == "감시 간격 1분") return
        clickText(automation, intervalLabel)
        val field = waitForNode(automation, TIMEOUT_MS) { it.className == EDIT_TEXT }
        setField(field, "1")
        clickText(automation, "저장")
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString() == "감시 간격 1분" }
    }

    private fun assertAllRequestsUseFakeAccountAndSeoulDate(requests: List<JSONObject>) {
        requests.forEach { request ->
            assertEquals("POST", request.getString("method"))
            assertTrue("Request ${request.optLong("sequence")} is incomplete", request.optInt("responseStatus", -1) >= 0)
            assertTrue(request.getJSONObject("headers").getString("content-type").startsWith("application/json"))
            if (request.getString("path") == SIGN_IN_PATH) {
                assertEquals(TEST_EMAIL, request.getJSONObject("body").getString("email"))
                assertEquals(TEST_PASSWORD, request.getJSONObject("body").getString("password"))
                assertEquals("", request.getJSONObject("headers").optString("authorization"))
            } else {
                assertEquals(AUTHORIZATION, request.getJSONObject("headers").getString("authorization"))
            }
            if (request.getString("path") in CATALOG_PATHS) {
                assertEquals(ACCOUNT_ID, request.getJSONObject("body").getJSONObject("user").getString("accountId"))
            }
            if (request.getString("path") in REPORT_PATHS) {
                assertEquals(seoulDateNumber(), request.getJSONObject("body").getInt("yyyymmdd"))
                assertEquals("ko", request.getJSONObject("body").getString("languageCode"))
            }
        }
    }

    private fun requireConnectedMonitor(state: JSONObject) {
        assertTrue("The real AccessibilityService lease is not attached: $state", state.optBoolean("serviceLeaseAttached"))
        assertTrue("Accessibility is not enabled: $state", state.optBoolean("accessibilityEnabled"))
        assertTrue("Notifications are not enabled: $state", state.optBoolean("notificationsEnabled"))
        assertTrue("POST_NOTIFICATIONS is not granted: $state", state.optBoolean("postNotificationsPermissionGranted"))
    }

    private fun requireSilentChannel(state: JSONObject): JSONObject {
        val channel = state.getJSONObject("channel")
        assertTrue("The product notification channel is missing", channel.optBoolean("exists"))
        val importance = channel.getInt("importance")
        val soundPresent = channel.optString("soundUri") != "null"
        assertTrue("Channel is not prepared for silent test observations: $channel", !channel.optBoolean("vibrationEnabled") && when (importance) {
            IMPORTANCE_MIN, IMPORTANCE_LOW -> true
            IMPORTANCE_DEFAULT -> !soundPresent
            else -> false
        })
        return channel
    }

    private fun waitForActualForegroundAndConnectedService(
        automation: UiAutomation,
        context: android.content.Context,
        afterForegroundSequence: Long,
        expectedPackage: String,
    ) {
        waitForValue(TIMEOUT_MS, "the actual foreground subject app and connected service") {
            val activePackage = automation.rootInActiveWindow?.packageName?.toString()
            val state = readProductState(context)
            val observed = state.getJSONArray("foregroundEvents").objects().any {
                it.optString("packageName") == expectedPackage && it.optLong("sequence") > afterForegroundSequence
            }
            if (activePackage == expectedPackage && observed && state.optBoolean("serviceLeaseAttached")) state else null
        }
    }

    private fun launchActualSubjectApp(context: android.content.Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw AssertionError("Installed subject app has no visible launcher activity: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }

    private fun waitForNotification(context: android.content.Context, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitForValue(timeoutMs, "the matching actual Android notification") {
            readProductState(context).getJSONArray("activeNotifications").objects().firstOrNull(predicate)
        }

    private fun readProductState(context: android.content.Context): JSONObject {
        val result = context.contentResolver.call(PROVIDER_AUTHORITY, PROVIDER_METHOD_SNAPSHOT, null, Bundle())
            ?: throw AssertionError("The debug read-only diagnostics provider returned no result")
        val serialized = result.getString(PROVIDER_EXTRA_SNAPSHOT_JSON)
            ?: throw AssertionError("The debug read-only diagnostics provider omitted its snapshot")
        return JSONObject(serialized)
    }

    private fun waitForProductState(context: android.content.Context, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitForValue(timeoutMs, "the requested actual product snapshot") { readProductState(context).takeIf(predicate) }

    private fun awaitFixtureState(apiBaseUrl: String, runId: String, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitForValue(timeoutMs, "the matching Ticket 05 fixture-server state") {
            fixtureRequest(apiBaseUrl, "GET", "/__ticket05/state").takeIf { it.optString("runId") == runId && predicate(it) }
        }

    private fun waitForCondition(timeoutMs: Long, description: String, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (condition()) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun <T : Any> waitForValue(timeoutMs: Long, description: String, block: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            block()?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun fixtureRequest(apiBaseUrl: String, method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = URL("${apiBaseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = SERVER_TIMEOUT_MS
        connection.readTimeout = SERVER_TIMEOUT_MS
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw AssertionError("Fixture request $path returned $status: $response")
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun setField(field: AccessibilityNodeInfo, value: String) {
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue("Could not enter text into an app field", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
    }

    private fun clickDescription(automation: UiAutomation, description: String) {
        val node = waitForNode(automation, TIMEOUT_MS) { it.contentDescription?.toString() == description && it.isClickable }
        assertTrue("Could not tap '$description'", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun clickText(automation: UiAutomation, text: String) {
        val node = waitForNode(automation, TIMEOUT_MS) { it.text?.toString() == text && it.isClickable }
        assertTrue("Could not tap '$text'", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun waitForNode(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        findNodeOrNull(automation, timeoutMs, predicate) ?: throw AssertionError("Timed out waiting for an app view")

    private fun findNodeOrNull(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            automation.rootInActiveWindow?.let { root -> findNode(root, predicate)?.let { return it } }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun findAllNodes(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = findNodeOrNull(automation, timeoutMs) { predicate(it) }?.let { automation.rootInActiveWindow }
            ?: throw AssertionError("No active window contains the requested app fields")
        val result = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, predicate, result)
        return result
    }

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return null
    }

    private fun collectNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean, results: MutableList<AccessibilityNodeInfo>) {
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (predicate(node)) results += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
    }

    private fun visibleText(automation: UiAutomation): List<String> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<String>()
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return result
    }

    private fun subject(state: JSONObject, subjectName: String): JSONObject =
        state.getJSONObject("children").getJSONObject(CHILD_NAME).getJSONObject("subjects").getJSONObject(subjectName)

    private fun isExpectedBaseline(state: JSONObject): Boolean = runCatching {
        subject(state, HANGEUL_SUBJECT).let { it.optString("status") == "BELOW_TARGET" && it.optInt("activityCount", -1) == 2 && it.optInt("targetCount", -1) == TARGET_HANGEUL } &&
            subject(state, MATH_SUBJECT).let { it.optString("status") == "COMPLETE" && it.optInt("activityCount", -1) == TARGET_MATH && it.optInt("targetCount", -1) == TARGET_MATH } &&
            subject(state, ENGLISH_SUBJECT).let { it.optString("status") == "BELOW_TARGET" && it.optInt("activityCount", -1) == 7 && it.optInt("targetCount", -1) == TARGET_ENGLISH }
    }.getOrDefault(false)

    private fun isRecoveryBaseline(state: JSONObject): Boolean = runCatching {
        subject(state, HANGEUL_SUBJECT).let { it.optString("status") == "COMPLETE" && it.optInt("activityCount", -1) == TARGET_HANGEUL && it.optInt("targetCount", -1) == TARGET_HANGEUL } &&
            subject(state, MATH_SUBJECT).let { it.optString("status") == "COMPLETE" && it.optInt("activityCount", -1) == TARGET_MATH && it.optInt("targetCount", -1) == TARGET_MATH } &&
            subject(state, ENGLISH_SUBJECT).let { it.optString("status") == "BELOW_TARGET" && it.optInt("activityCount", -1) == 7 && it.optInt("targetCount", -1) == TARGET_ENGLISH }
    }.getOrDefault(false)

    private fun assertRequestPaths(
        requests: List<JSONObject>,
        firstSequence: Long,
        lastSequence: Long,
        expectedPaths: List<String>,
        context: String,
    ) {
        val actual = requests.filter { it.optLong("sequence") in firstSequence..lastSequence }
        assertEquals("$context did not make exactly the expected API calls: $actual", expectedPaths, actual.map { it.getString("path") })
    }

    private fun assertDispatchCycle(events: List<JSONObject>, expectedPaths: List<String>, expectedPackage: String, context: String) {
        assertEquals("$context did not dispatch the full request cycle: $events", expectedPaths, events.map { it.getString("path") })
        events.forEach { event ->
            assertEquals(expectedPackage, event.getString("foregroundPackage"))
            assertTrue("The real service lease was absent at HTTP dispatch $event", event.getBoolean("serviceLeaseAttached"))
            assertTrue("Foreground callback timestamp followed HTTP dispatch $event", event.getLong("foregroundEventElapsedRealtimeMs") <= event.getLong("elapsedRealtimeMs"))
            assertTrue("Service lease observation followed HTTP dispatch $event", event.getLong("serviceLeaseObservedAtElapsedRealtimeMs") <= event.getLong("elapsedRealtimeMs"))
        }
    }

    private fun assertScreenDispatchCycle(events: List<JSONObject>, expectedPaths: List<String>, context: String) {
        assertEquals("$context did not dispatch the full screen request cycle: $events", expectedPaths, events.map { it.getString("path") })
        events.forEach { event ->
            assertTrue("The real service lease was absent at screen HTTP dispatch $event", event.getBoolean("serviceLeaseAttached"))
            assertTrue("The accessibility foreground callback timestamp followed screen HTTP dispatch $event", event.getLong("foregroundEventElapsedRealtimeMs") <= event.getLong("elapsedRealtimeMs"))
            assertTrue("The service lease observation followed screen HTTP dispatch $event", event.getLong("serviceLeaseObservedAtElapsedRealtimeMs") <= event.getLong("elapsedRealtimeMs"))
        }
    }

    private fun assertProductScreenVisible(automation: UiAutomation, context: String): String {
        val packageName = automation.rootInActiveWindow?.packageName?.toString()
            ?: throw AssertionError("$context had no accessible active product window")
        assertEquals("$context did not finish with the actual product Activity visible", PRODUCT_PACKAGE, packageName)
        return packageName
    }

    private fun dispatchesAfter(state: JSONObject, cursor: Long): List<JSONObject> =
        state.getJSONArray("httpRequestDispatchEvents").objects().filter { it.optLong("sequence") > cursor }

    private fun lastDispatchSequence(events: List<JSONObject>): Long = events.maxOfOrNull { it.optLong("sequence") } ?: 0L

    private fun requestSummaries(requests: List<JSONObject>): JSONArray = JSONArray().apply {
        requests.forEach { request ->
            put(JSONObject()
                .put("sequence", request.getLong("sequence"))
                .put("path", request.getString("path"))
                .put("responseStatus", request.getInt("responseStatus"))
                .put("caseId", if (request.isNull("caseId")) JSONObject.NULL else request.getString("caseId")))
        }
    }

    private fun eventsAfter(events: JSONArray, sequence: Long): List<JSONObject> = events.objects().filter { it.optLong("sequence") > sequence }

    private fun lastSequence(events: JSONArray): Long = events.objects().maxOfOrNull { it.optLong("sequence") } ?: 0L

    private fun seoulDateNumber(): Int = LocalDate.now(ZoneId.of("Asia/Seoul"))
        .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toInt()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun jsonObjects(values: List<JSONObject>): JSONArray = JSONArray().apply { values.forEach(::put) }

    private fun stringArrayJson(values: List<String>): JSONArray = JSONArray().apply { values.forEach(::put) }

    private fun longArrayJson(values: List<Long>): JSONArray = JSONArray().apply { values.forEach(::put) }

    private fun emitStructuredResult(runId: String, result: JSONObject) {
        val payload = JSONObject(result.toString()).put("runId", runId).toString()
        val encoded = android.util.Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val chunks = encoded.chunked(RESULT_CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            Log.i(RESULT_LOG_TAG, "TICKET05_RESULT_CHUNK:$runId:${index + 1}/${chunks.size}:$chunk")
        }
    }

    private companion object {
        const val ARG_API_BASE_URL = "apiBaseUrl"
        const val ARG_RUN_ID = "runId"
        const val PRODUCT_PACKAGE = "com.example.todoschooltime.debug"
        const val PRODUCT_SERVICE_CLASS = "com.example.todoschooltime.MonitorAccessibilityService"
        const val DEBUG_APPLICATION_LABEL = "토도 학습시간 · 로컬 테스트"
        const val DEBUG_ACCESSIBILITY_LABEL = "토도 학습 감시 · 로컬 테스트"
        const val PROVIDER_AUTHORITY = "com.example.todoschooltime.debug.monitor-diagnostics"
        const val PROVIDER_METHOD_SNAPSHOT = "snapshot"
        const val PROVIDER_EXTRA_SNAPSHOT_JSON = "snapshotJson"
        const val TEST_EMAIL = "ticket01@example.test"
        const val TEST_PASSWORD = "not-a-real-password"
        const val AUTHORIZATION = "Bearer ticket01-fake-token"
        const val ACCOUNT_ID = "ticket01-fake-account"
        const val CHILD_NAME = "테스트 아이"
        const val HANGEUL_SUBJECT = "한글"
        const val MATH_SUBJECT = "수학"
        const val ENGLISH_SUBJECT = "영어"
        const val HANGEUL_APP_PACKAGE = "com.enuma.todohangeul"
        const val ENGLISH_APP_PACKAGE = "com.enuma.todoenglish"
        const val HANGEUL_REPORT_PATH = "/v3/todoschool/report/daily/hangeul"
        const val MATH_REPORT_PATH = "/v3/todoschool/report/daily/math"
        const val ENGLISH_REPORT_PATH = "/v3/todoschool/report/daily/english"
        const val HANGEUL_CATALOG_PATH = "/v3/todohangeul/user"
        const val MATH_CATALOG_PATH = "/v3/math/user"
        const val ENGLISH_CATALOG_PATH = "/v3/english/user"
        const val SIGN_IN_PATH = "/v3/account/signin"
        const val TEMPORARY_CASE_ID = "temporary-http-500"
        const val AUTH_CASE_ID = "expired-auth-401"
        const val RESULT_LOG_TAG = "Ticket05Result"
        const val TARGET_HANGEUL = 6
        const val TARGET_MATH = 5
        const val TARGET_ENGLISH = 8
        const val EDIT_TEXT = "android.widget.EditText"
        const val TIMEOUT_MS = 30_000L
        const val POLL_TIMEOUT_MS = 125_000L
        const val AUTH_PAUSE_WINDOW_MS = 75_000L
        const val LOGIN_FORM_TIMEOUT_MS = 2_000L
        const val SERVER_TIMEOUT_MS = 3_000
        const val POLL_INTERVAL_MS = 100L
        const val POLL_INTERVAL_NS = 60_000_000_000L
        const val MAX_POLL_DELAY_NS = 140_000_000_000L
        const val MAX_WAKE_LOCK_MS = 15 * 60 * 1000L
        const val RESULT_CHUNK_SIZE = 3_000
        const val IMPORTANCE_MIN = 1
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_DEFAULT = 3
        val REPORT_PATHS = setOf(HANGEUL_REPORT_PATH, MATH_REPORT_PATH, ENGLISH_REPORT_PATH)
        val CATALOG_PATHS = setOf(HANGEUL_CATALOG_PATH, MATH_CATALOG_PATH, ENGLISH_CATALOG_PATH)
        val BASELINE_PATHS = listOf(
            SIGN_IN_PATH,
            HANGEUL_CATALOG_PATH,
            MATH_CATALOG_PATH,
            ENGLISH_CATALOG_PATH,
            HANGEUL_REPORT_PATH,
            MATH_REPORT_PATH,
            ENGLISH_REPORT_PATH,
        )
        val REGULAR_HANGEUL_PATHS = listOf(
            SIGN_IN_PATH,
            HANGEUL_CATALOG_PATH,
            MATH_CATALOG_PATH,
            ENGLISH_CATALOG_PATH,
            HANGEUL_REPORT_PATH,
        )
        val REGULAR_ENGLISH_PATHS = listOf(
            SIGN_IN_PATH,
            HANGEUL_CATALOG_PATH,
            MATH_CATALOG_PATH,
            ENGLISH_CATALOG_PATH,
            ENGLISH_REPORT_PATH,
        )
        val HANGEUL_CYCLE_PATHS = REGULAR_HANGEUL_PATHS
        val ENGLISH_CYCLE_PATHS = REGULAR_ENGLISH_PATHS
    }
}
