package com.example.todoschooltime.e2eobserver

import android.app.Notification
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises retry, authentication pause, and explicit UI re-login using public observations. */
@RunWith(AndroidJUnit4::class)
class AuthenticationRecoveryTest {
    private val accessibility = ObserverAccessibilitySupport(pollIntervalMs = POLL_INTERVAL_MS)

    @Test
    fun temporaryHttpFailureRetriesAndAuthenticationExpiryRequiresUiLogin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val apiBaseUrl = arguments.getString(ARG_API_BASE_URL)
            ?: throw AssertionError("Missing apiBaseUrl instrumentation argument")
        val runId = arguments.getString(ARG_RUN_ID)
            ?: throw AssertionError("Missing runId instrumentation argument")
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val statePath = "/__ticket05/state"
        ObserverTestSupport.requireSilentChannel(ObserverTestSupport.readNotificationSnapshot(context))
        assertEquals(runId, fixtureRequest(apiBaseUrl, "GET", "/__ticket05/health").getString("runId"))

        ObserverProductUi.openProductApp(instrumentation)
        enterProductUi(automation)
        ObserverProductUi.ensureUnregistered(automation, accessibility, context, TIMEOUT_MS)
        ObserverProductUi.writeTargets(automation, accessibility, TIMEOUT_MS)
        ObserverProductUi.ensureOneMinuteInterval(automation, accessibility, TIMEOUT_MS)
        fixtureRequest(apiBaseUrl, "POST", "/__ticket05/reset-requests", JSONObject())
        ObserverProductUi.ensureRegistered(automation, accessibility, TIMEOUT_MS)

        val baselineState = ObserverTestSupport.awaitFixtureState(apiBaseUrl, statePath, runId, BASELINE_TIMEOUT_MS) { state ->
            val reports = ObserverTestSupport.reportRequests(state)
            listOf(HANGEUL_REPORT_PATH, MATH_REPORT_PATH, ENGLISH_REPORT_PATH).all { path ->
                reports.any { it.optString("path") == path && it.optInt("responseStatus", -1) == 200 }
            }
        }
        val baselineRequests = ObserverTestSupport.requests(baselineState)
        ObserverTestSupport.assertFixtureRequestContract(baselineRequests)
        assertSuccessfulCount(baselineState, HANGEUL_REPORT_PATH, 2)
        assertSuccessfulCount(baselineState, MATH_REPORT_PATH, TARGET_MATH)
        assertSuccessfulCount(baselineState, ENGLISH_REPORT_PATH, 7)
        val baselineNotification = ObserverTestSupport.waitForNotification(context, TIMEOUT_MS) {
            it.optString("title") == CHILD_NAME + " 학습 상태" && it.optString("bigText").contains("한글 미달성 2/6")
        }
        val baselineId = baselineNotification.getInt("id")
        val checkedAtLine = baselineNotification.getString("bigText").lines().getOrNull(3)
            ?: throw AssertionError("The product notification omitted its last successful check time")
        assertTrue(checkedAtLine.isNotBlank())

        val armFailure = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket05/arm-report-response",
            JSONObject()
                .put("caseId", TEMPORARY_CASE_ID)
                .put("path", HANGEUL_REPORT_PATH)
                .put("responseStatus", 500),
        )
        assertEquals(500, armFailure.getInt("responseStatus"))
        launchSubjectApp(context, HANGEUL_APP_PACKAGE)
        waitForForegroundPackage(automation, HANGEUL_APP_PACKAGE)
        val failedState = ObserverTestSupport.awaitFixtureState(apiBaseUrl, statePath, runId, POLL_TIMEOUT_MS) { state ->
            ObserverTestSupport.requests(state).any { it.optString("caseId") == TEMPORARY_CASE_ID }
        }
        val failedRequest = ObserverTestSupport.requests(failedState).first { it.optString("caseId") == TEMPORARY_CASE_ID }
        assertEquals(HANGEUL_REPORT_PATH, failedRequest.getString("path"))
        assertEquals(500, failedRequest.getInt("responseStatus"))
        val failedSequence = failedRequest.getLong("sequence")
        ObserverTestSupport.assertFixtureRequestContract(listOf(failedRequest))
        val failureNotification = ObserverTestSupport.waitForNotification(context, TIMEOUT_MS) {
            it.optInt("id", -1) == baselineId && it.optString("bigText").contains("HTTP 500")
        }
        val failureBody = failureNotification.getString("bigText")
        assertTrue(failureBody.contains("한글 미달성 2/6"))
        assertTrue("An HTTP error discarded the last successful check time", failureBody.contains(checkedAtLine))

        val recoveredState = ObserverTestSupport.awaitFixtureState(apiBaseUrl, statePath, runId, POLL_TIMEOUT_MS) { state ->
            ObserverTestSupport.requests(state).any {
                it.optLong("sequence") > failedSequence &&
                    it.optString("path") == HANGEUL_REPORT_PATH &&
                    it.optInt("responseStatus", -1) == 200
            }
        }
        val recoveredRequest = ObserverTestSupport.requests(recoveredState).first {
            it.optLong("sequence") > failedSequence &&
                it.optString("path") == HANGEUL_REPORT_PATH &&
                it.optInt("responseStatus", -1) == 200
        }
        assertSuccessfulCount(recoveredState, HANGEUL_REPORT_PATH, 2)
        val retryInterval = recoveredRequest.getLong("serverReceivedMonotonicMs") -
            failedRequest.getLong("serverReceivedMonotonicMs")
        assertTrue("Retry interval was shorter than 58 seconds: " + retryInterval + " ms", retryInterval >= MIN_CADENCE_MS)
        assertTrue("Retry interval exceeded 180 seconds: " + retryInterval + " ms", retryInterval <= MAX_CADENCE_MS)
        val recoveredNotification = ObserverTestSupport.waitForNotification(context, TIMEOUT_MS) {
            it.optInt("id", -1) == baselineId &&
                it.optString("title") == CHILD_NAME + " 학습 상태" &&
                !it.optString("bigText").contains("HTTP 500")
        }
        assertTrue(recoveredNotification.getString("bigText").contains("한글 미달성 2/6"))

        val armExpiredAuth = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket05/arm-report-response",
            JSONObject()
                .put("caseId", AUTH_CASE_ID)
                .put("path", HANGEUL_REPORT_PATH)
                .put("responseStatus", 401),
        )
        assertEquals(401, armExpiredAuth.getInt("responseStatus"))
        val authFailureState = ObserverTestSupport.awaitFixtureState(apiBaseUrl, statePath, runId, POLL_TIMEOUT_MS) { state ->
            ObserverTestSupport.requests(state).any { it.optString("caseId") == AUTH_CASE_ID }
        }
        val authFailure = ObserverTestSupport.requests(authFailureState).first { it.optString("caseId") == AUTH_CASE_ID }
        assertEquals(HANGEUL_REPORT_PATH, authFailure.getString("path"))
        assertEquals(401, authFailure.getInt("responseStatus"))
        ObserverTestSupport.assertFixtureRequestContract(listOf(authFailure))
        val authSequence = authFailure.getLong("sequence")
        val authNotification = ObserverTestSupport.waitForNotification(context, TIMEOUT_MS) {
            it.optInt("id", -1) == baselineId && it.optString("bigText").contains("다시 로그인 필요")
        }
        assertTrue(authNotification.getString("bigText").contains("한글 미달성 2/6"))

        ObserverTestSupport.awaitNoNewRequests(
            apiBaseUrl,
            statePath,
            runId,
            authSequence,
            AUTH_PAUSE_WINDOW_MS,
        )

        val countChanged = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket05/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", TARGET_HANGEUL)),
        )
        assertEquals(TARGET_HANGEUL, countChanged.getJSONObject("reportCounts").getInt("hangeul"))
        assertEquals(authSequence, countChanged.getLong("afterRequestSequence"))

        ObserverProductUi.openProductApp(instrumentation)
        ObserverProductUi.waitForChildScreen(automation, accessibility, TIMEOUT_MS)
        assertTrue(
            "The registered child disappeared during the authentication pause",
            accessibility.findNodeOrNull(automation, TIMEOUT_MS) {
                it.contentDescription?.toString() == CHILD_NAME + " 감시 해제" && it.isClickable
            } != null,
        )
        accessibility.clickText(automation, "다시 로그인", TIMEOUT_MS, "The expired session did not offer UI re-login")
        ObserverProductUi.submitFakeCredentials(automation, accessibility, TIMEOUT_MS)
        ObserverProductUi.assertScreenCounts(automation, accessibility, TARGET_HANGEUL, TARGET_MATH, 7, TIMEOUT_MS)
        accessibility.waitForNode(automation, TIMEOUT_MS) { it.text?.toString() == "감시 설정" }

        val recoveryState = ObserverTestSupport.awaitFixtureState(apiBaseUrl, statePath, runId, POLL_TIMEOUT_MS) { state ->
            ObserverTestSupport.requests(state).any {
                it.optLong("sequence") > authSequence &&
                    it.optString("path") == HANGEUL_REPORT_PATH &&
                    it.optInt("responseStatus", -1) == 200 &&
                    it.optInt("servedReportCount", -1) == TARGET_HANGEUL
            }
        }
        ObserverTestSupport.assertFixtureRequestContract(ObserverTestSupport.requests(recoveryState))
        assertSuccessfulCount(recoveryState, HANGEUL_REPORT_PATH, TARGET_HANGEUL)
        ObserverProductUi.ensureRegistered(automation, accessibility, TIMEOUT_MS)
        val rebaselineNotification = ObserverTestSupport.waitForNotification(context, TIMEOUT_MS) {
            it.optInt("id", -1) == baselineId &&
                it.optString("title") == CHILD_NAME + " 학습 상태" &&
                it.optString("bigText").contains("✔ 한글 완료 6/6")
        }
        val rebaselineBody = rebaselineNotification.getString("bigText")
        assertTrue(rebaselineBody.contains("✔ 수학 완료 5/5"))
        assertTrue(rebaselineBody.contains("영어 미달성 7/8"))
        assertTrue(
            "A successful UI re-login should treat the refreshed complete result as the new quiet baseline",
            (rebaselineNotification.getInt("flags") and Notification.FLAG_ONLY_ALERT_ONCE) != 0,
        )

        val changedEnglish = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket05/set-report-counts",
            JSONObject().put("reports", JSONObject().put("english", TARGET_ENGLISH)),
        )
        val beforeEnglish = changedEnglish.getLong("afterRequestSequence")
        launchSubjectApp(context, ENGLISH_APP_PACKAGE)
        waitForForegroundPackage(automation, ENGLISH_APP_PACKAGE)
        val englishState = ObserverTestSupport.awaitFixtureState(apiBaseUrl, statePath, runId, POLL_TIMEOUT_MS) { state ->
            ObserverTestSupport.requests(state).any {
                it.optLong("sequence") > beforeEnglish &&
                    it.optString("path") == ENGLISH_REPORT_PATH &&
                    it.optInt("responseStatus", -1) == 200 &&
                    it.optInt("servedReportCount", -1) == TARGET_ENGLISH
            }
        }
        ObserverTestSupport.assertFixtureRequestContract(ObserverTestSupport.requests(englishState))
        assertSuccessfulCount(englishState, ENGLISH_REPORT_PATH, TARGET_ENGLISH)
        val completionNotification = ObserverTestSupport.waitForNotification(context, POLL_TIMEOUT_MS) {
            it.optInt("id", -1) == baselineId &&
                it.optString("title") == CHILD_NAME + " · " + ENGLISH_SUBJECT + " 완료"
        }
        val completionBody = completionNotification.getString("bigText")
        assertTrue(completionBody.contains("✔ 한글 완료 6/6"))
        assertTrue(completionBody.contains("✔ 수학 완료 5/5"))
        assertTrue(completionBody.contains("✔ 영어 완료 8/8"))
        val active = ObserverTestSupport.activeNotifications(ObserverTestSupport.readNotificationSnapshot(context))
        assertEquals(listOf(baselineId), active.filter { it.optInt("id", -1) == baselineId }.map { it.getInt("id") })

        Log.i(RESULT_LOG_TAG, encodeResult(runId))
    }

    private fun enterProductUi(automation: UiAutomation) {
        if (accessibility.findNodeOrNull(automation, LOGIN_FORM_TIMEOUT_MS) {
                it.text?.toString() == "다시 로그인" && it.isClickable
            } != null
        ) {
            accessibility.clickText(automation, "다시 로그인", TIMEOUT_MS, "Could not open the login screen")
            ObserverProductUi.submitFakeCredentials(automation, accessibility, TIMEOUT_MS)
        } else {
            ObserverProductUi.enterFakeCredentialsIfNeeded(automation, accessibility, LOGIN_FORM_TIMEOUT_MS)
        }
        ObserverProductUi.waitForChildScreen(automation, accessibility, TIMEOUT_MS)
        ObserverProductUi.assertScreenCounts(automation, accessibility, 2, 5, 7, TIMEOUT_MS)
    }

    private fun assertSuccessfulCount(state: JSONObject, path: String, count: Int) {
        val request = ObserverTestSupport.requests(state).lastOrNull {
            it.optString("path") == path && it.optInt("responseStatus", -1) == 200
        } ?: throw AssertionError("No successful request for " + path)
        ObserverTestSupport.assertReportResponse(request, count)
    }

    private fun launchSubjectApp(context: android.content.Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw AssertionError("Subject app is not installed: " + packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }

    private fun waitForForegroundPackage(automation: UiAutomation, expectedPackage: String) {
        ObserverTestSupport.waitForValue(TIMEOUT_MS, POLL_INTERVAL_MS, "foreground subject app " + expectedPackage) {
            automation.rootInActiveWindow?.packageName?.toString()?.takeIf { it == expectedPackage }
        }
        SystemClock.sleep(FOREGROUND_SETTLE_MS)
        assertEquals(expectedPackage, automation.rootInActiveWindow?.packageName?.toString())
    }

    private fun fixtureRequest(
        apiBaseUrl: String,
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject = ObserverTestSupport.fixtureRequest(apiBaseUrl, method, path, SERVER_TIMEOUT_MS, body)

    private fun encodeResult(runId: String): String {
        val result = JSONObject().put("runId", runId).put("scenario", "ticket05")
        val encoded = Base64.encodeToString(result.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "$RESULT_CHUNK_MARKER$runId:1/1:$encoded"
    }

    private companion object {
        const val ARG_API_BASE_URL = "apiBaseUrl"
        const val ARG_RUN_ID = "runId"
        const val CHILD_NAME = ObserverTestSupport.CHILD_NAME
        const val HANGEUL_REPORT_PATH = ObserverTestSupport.HANGEUL_REPORT_PATH
        const val MATH_REPORT_PATH = ObserverTestSupport.MATH_REPORT_PATH
        const val ENGLISH_REPORT_PATH = ObserverTestSupport.ENGLISH_REPORT_PATH
        const val HANGEUL_APP_PACKAGE = "com.enuma.todohangeul"
        const val ENGLISH_APP_PACKAGE = "com.enuma.todoenglish"
        const val HANGEUL_SUBJECT = ObserverTestSupport.HANGEUL_SUBJECT
        const val ENGLISH_SUBJECT = ObserverTestSupport.ENGLISH_SUBJECT
        const val TEMPORARY_CASE_ID = "temporary-http-500"
        const val AUTH_CASE_ID = "expired-auth-401"
        const val TARGET_HANGEUL = ObserverProductUi.TARGET_HANGEUL
        const val TARGET_MATH = ObserverProductUi.TARGET_MATH
        const val TARGET_ENGLISH = ObserverProductUi.TARGET_ENGLISH
        const val TIMEOUT_MS = 30_000L
        const val BASELINE_TIMEOUT_MS = 120_000L
        const val POLL_TIMEOUT_MS = 180_000L
        const val AUTH_PAUSE_WINDOW_MS = 65_000L
        const val MIN_CADENCE_MS = 58_000L
        const val MAX_CADENCE_MS = 180_000L
        const val LOGIN_FORM_TIMEOUT_MS = 2_000L
        const val SERVER_TIMEOUT_MS = 5_000
        const val POLL_INTERVAL_MS = 200L
        const val FOREGROUND_SETTLE_MS = 300L
        const val RESULT_LOG_TAG = "Ticket05Result"
        const val RESULT_CHUNK_MARKER = "TICKET05_RESULT_CHUNK:"
    }
}
