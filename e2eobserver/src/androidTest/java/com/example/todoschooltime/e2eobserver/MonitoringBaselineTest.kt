package com.example.todoschooltime.e2eobserver

import android.app.Notification
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks a completed baseline and later completion through visible UI, fixture HTTP, and OS notifications. */
@RunWith(AndroidJUnit4::class)
class MonitoringBaselineTest {
    private val accessibility = ObserverAccessibilitySupport(pollIntervalMs = POLL_INTERVAL_MS)

    @Test
    fun completedBaselineQuietChannelPreconditionAndLaterCompletionUpdateAreObserved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val apiBaseUrl = arguments.getString(ARG_API_BASE_URL)
            ?: throw AssertionError("Missing apiBaseUrl instrumentation argument")
        val runId = arguments.getString(ARG_RUN_ID)
            ?: throw AssertionError("Missing runId instrumentation argument")
        val context = instrumentation.targetContext
        val startDate = ObserverTestSupport.seoulDateNumber()
        val statePath = "/__ticket02/state"

        val health = fixtureRequest(apiBaseUrl, "GET", "/__ticket02/health")
        assertEquals(runId, health.getString("runId"))
        assertEquals("ticket02", health.getString("scenario"))
        ObserverTestSupport.requireSilentChannel(ObserverTestSupport.readNotificationSnapshot(context))

        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ObserverProductUi.openProductApp(instrumentation)
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
        ObserverProductUi.ensureUnregistered(automation, accessibility, context, TIMEOUT_MS)
        ObserverProductUi.writeTargets(automation, accessibility, TIMEOUT_MS)
        assertTrue(
            "An unregistered child must have no active product notification",
            ObserverTestSupport.activeNotifications(ObserverTestSupport.readNotificationSnapshot(context))
                .none { it.optString("title").contains(CHILD_NAME) },
        )

        val reset = fixtureRequest(apiBaseUrl, "POST", "/__ticket02/reset-requests", JSONObject())
        assertEquals(runId, reset.getString("runId"))
        assertTrue(reset.optBoolean("ok"))
        accessibility.clickDescription(automation, CHILD_NAME + " 감시 등록", TIMEOUT_MS, "Could not register the fixture child")
        accessibility.waitForNode(automation, TIMEOUT_MS) {
            it.contentDescription?.toString() == CHILD_NAME + " 감시 해제" && it.isClickable
        }

        val baselineState = ObserverTestSupport.awaitFixtureState(
            apiBaseUrl,
            statePath,
            runId,
            BASELINE_TIMEOUT_MS,
        ) { state ->
            val reports = ObserverTestSupport.reportRequests(state)
            listOf(HANGEUL_REPORT_PATH, MATH_REPORT_PATH, ENGLISH_REPORT_PATH).all { path ->
                reports.any { it.optString("path") == path && it.optInt("responseStatus", -1) == 200 }
            }
        }
        val baselineRequests = ObserverTestSupport.requests(baselineState)
        ObserverTestSupport.assertFixtureRequestContract(baselineRequests)
        assertBaselineReport(baselineRequests, HANGEUL_REPORT_PATH, 2)
        assertBaselineReport(baselineRequests, MATH_REPORT_PATH, TARGET_MATH)
        assertBaselineReport(baselineRequests, ENGLISH_REPORT_PATH, 7)
        assertEquals(startDate, ObserverTestSupport.seoulDateNumber())

        val baselineNotification = ObserverTestSupport.waitForNotification(context, BASELINE_TIMEOUT_MS) {
            it.optString("title") == CHILD_NAME + " 학습 상태" &&
                it.optString("bigText").contains("✔ 수학 완료 5/5")
        }
        val baselineId = baselineNotification.getInt("id")
        val baselineBody = baselineNotification.getString("bigText")
        assertTrue(baselineBody.contains("한글 미달성 2/6"))
        assertTrue(baselineBody.contains("✔ 수학 완료 5/5"))
        assertTrue(baselineBody.contains("영어 미달성 7/8"))
        assertTrue(
            "The baseline status notification should be configured not to alert on repeat updates",
            (baselineNotification.getInt("flags") and Notification.FLAG_ONLY_ALERT_ONCE) != 0,
        )
        val baselineSnapshot = ObserverTestSupport.readNotificationSnapshot(context)
        ObserverTestSupport.requireSilentChannel(baselineSnapshot)

        val changed = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket02/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", TARGET_HANGEUL)),
        )
        assertEquals(runId, changed.getString("runId"))
        assertEquals(TARGET_HANGEUL, changed.getJSONObject("reportCounts").getInt("hangeul"))
        val afterSequence = changed.getLong("afterRequestSequence")
        launchSubjectApp(context, HANGEUL_APP_PACKAGE)
        waitForForegroundPackage(automation, HANGEUL_APP_PACKAGE)

        val completionState = ObserverTestSupport.awaitFixtureState(
            apiBaseUrl,
            statePath,
            runId,
            CADENCE_TIMEOUT_MS,
        ) { state ->
            ObserverTestSupport.requests(state).any { request ->
                request.optLong("sequence") > afterSequence &&
                    request.optString("path") == HANGEUL_REPORT_PATH &&
                    request.optInt("responseStatus", -1) == 200 &&
                    request.optInt("servedReportCount", -1) == TARGET_HANGEUL
            }
        }
        val completionReport = ObserverTestSupport.requests(completionState).first { request ->
            request.optLong("sequence") > afterSequence &&
                request.optString("path") == HANGEUL_REPORT_PATH &&
                request.optInt("servedReportCount", -1) == TARGET_HANGEUL
        }
        ObserverTestSupport.assertFixtureRequestContract(listOf(completionReport))
        assertEquals(HANGEUL_USER_ID, completionReport.getJSONObject("body").getString("userId"))
        assertEquals(startDate, completionReport.getJSONObject("body").getInt("yyyymmdd"))

        val completionNotification = ObserverTestSupport.waitForNotification(context, CADENCE_TIMEOUT_MS) {
            it.optInt("id", -1) == baselineId &&
                it.optString("title") == CHILD_NAME + " · " + HANGEUL_SUBJECT + " 완료"
        }
        val completionBody = completionNotification.getString("bigText")
        assertTrue(completionBody.contains("✔ 한글 완료 6/6"))
        assertTrue(completionBody.contains("✔ 수학 완료 5/5"))
        assertTrue(completionBody.contains("영어 미달성 7/8"))
        assertTrue(
            "The completion notification must clear FLAG_ONLY_ALERT_ONCE",
            (completionNotification.getInt("flags") and Notification.FLAG_ONLY_ALERT_ONCE) == 0,
        )
        val activeChildNotifications = ObserverTestSupport.activeNotifications(
            ObserverTestSupport.readNotificationSnapshot(context),
        ).filter { it.optInt("id", -1) == baselineId }
        assertEquals("Completion should update the active child notification", 1, activeChildNotifications.size)
        assertTrue("The completion body was not retained by Android", activeChildNotifications.single().optString("bigText").contains("✔ 한글 완료 6/6"))

        val finalState = fixtureRequest(apiBaseUrl, "GET", statePath)
        assertEquals(runId, finalState.getString("runId"))
        ObserverTestSupport.assertFixtureRequestContract(ObserverTestSupport.requests(finalState))
        assertEquals(startDate, ObserverTestSupport.seoulDateNumber())

        Log.i(RESULT_LOG_TAG, "TICKET02_RESULT=" + JSONObject().put("runId", runId).put("scenario", "ticket02"))
    }

    private fun assertBaselineReport(requests: List<JSONObject>, path: String, count: Int) {
        val report = requests.lastOrNull {
            it.optString("path") == path && it.optInt("responseStatus", -1) == 200
        } ?: throw AssertionError("Fixture did not receive a successful baseline report for " + path)
        ObserverTestSupport.assertReportResponse(report, count)
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

    private companion object {
        const val ARG_API_BASE_URL = "apiBaseUrl"
        const val ARG_RUN_ID = "runId"
        const val CHILD_NAME = ObserverTestSupport.CHILD_NAME
        const val HANGEUL_SUBJECT = ObserverTestSupport.HANGEUL_SUBJECT
        const val HANGEUL_REPORT_PATH = ObserverTestSupport.HANGEUL_REPORT_PATH
        const val HANGEUL_USER_ID = ObserverTestSupport.HANGEUL_USER_ID
        const val MATH_REPORT_PATH = ObserverTestSupport.MATH_REPORT_PATH
        const val ENGLISH_REPORT_PATH = ObserverTestSupport.ENGLISH_REPORT_PATH
        const val TARGET_HANGEUL = ObserverProductUi.TARGET_HANGEUL
        const val TARGET_MATH = ObserverProductUi.TARGET_MATH
        const val HANGEUL_APP_PACKAGE = "com.enuma.todohangeul"
        const val TIMEOUT_MS = 30_000L
        const val BASELINE_TIMEOUT_MS = 120_000L
        const val CADENCE_TIMEOUT_MS = 180_000L
        const val LOGIN_FORM_TIMEOUT_MS = 2_000L
        const val SERVER_TIMEOUT_MS = 5_000
        const val POLL_INTERVAL_MS = 100L
        const val FOREGROUND_SETTLE_MS = 300L
        const val RESULT_LOG_TAG = "Ticket02E2E"
    }
}
