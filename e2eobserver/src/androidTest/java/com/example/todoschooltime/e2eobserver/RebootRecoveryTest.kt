package com.example.todoschooltime.e2eobserver

import android.app.Instrumentation
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observes boot restoration through fixture requests and Android notifications before opening product UI. */
@RunWith(AndroidJUnit4::class)
class RebootRecoveryTest {
    private val accessibility = ObserverAccessibilitySupport(pollIntervalMs = POLL_INTERVAL_MS, searchWindowRoots = true)

    @Test
    fun runRequestedPhase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val apiBaseUrl = arguments.getString(ARG_API_BASE_URL)
            ?: throw AssertionError("Missing apiBaseUrl instrumentation argument")
        val runId = arguments.getString(ARG_RUN_ID)
            ?: throw AssertionError("Missing runId instrumentation argument")
        val phase = arguments.getString(ARG_PHASE)
            ?: throw AssertionError("Missing phase instrumentation argument")
        assertTrue("Unknown Ticket 06 phase: " + phase, phase == PHASE_REBOOT_PREPARATION || phase == PHASE_POST_REBOOT)

        val context = instrumentation.targetContext
        val powerManager = context.getSystemService(PowerManager::class.java)
            ?: throw AssertionError("The observer cannot access PowerManager")
        val wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Ticket06:RebootRecovery")
        wakeLock.acquire(MAX_WAKE_LOCK_MS)
        try {
            val notificationSnapshot = ObserverTestSupport.readNotificationSnapshot(context)
            ObserverTestSupport.requireSilentChannel(notificationSnapshot)
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val health = fixtureRequest(apiBaseUrl, "GET", "/__ticket06/health")
            assertEquals(runId, health.getString("runId"))
            assertEquals("ticket06", health.getString("scenario"))

            when (phase) {
                PHASE_REBOOT_PREPARATION -> prepareRebootCase(instrumentation, automation, apiBaseUrl, runId)
                PHASE_POST_REBOOT -> {
                    val sequence = arguments.getString(ARG_POST_REBOOT_SEQUENCE)?.toLongOrNull()
                        ?: throw AssertionError("Missing pre-reboot fixture request cursor")
                    assertTrue("Invalid pre-reboot fixture request cursor", sequence >= 0L)
                    runPostRebootCase(instrumentation, automation, apiBaseUrl, runId, sequence)
                }
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun prepareRebootCase(
        instrumentation: Instrumentation,
        automation: UiAutomation,
        apiBaseUrl: String,
        runId: String,
    ) {
        val context = instrumentation.targetContext
        ObserverProductUi.openProductApp(instrumentation)
        enterProductUi(automation)
        ObserverProductUi.ensureUnregistered(automation, accessibility, context, TIMEOUT_MS)
        ObserverProductUi.writeTargets(automation, accessibility, TIMEOUT_MS)
        ObserverProductUi.ensureOneMinuteInterval(automation, accessibility, TIMEOUT_MS)

        fixtureRequest(apiBaseUrl, "POST", "/__ticket06/reset-requests", JSONObject())
        fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket06/set-report-counts",
            JSONObject().put("reports", baselineCounts()),
        )
        ObserverProductUi.ensureRegistered(automation, accessibility, TIMEOUT_MS)

        val baselineState = ObserverTestSupport.awaitFixtureState(apiBaseUrl, STATE_PATH, runId, BASELINE_TIMEOUT_MS) { state ->
            val reports = ObserverTestSupport.reportRequests(state)
            REPORT_PATHS.all { path ->
                reports.any { it.optString("path") == path && it.optInt("responseStatus", -1) == 200 }
            }
        }
        val requests = ObserverTestSupport.requests(baselineState)
        ObserverTestSupport.assertFixtureRequestContract(requests)
        assertReportCount(requests, HANGEUL_REPORT_PATH, 2)
        assertReportCount(requests, MATH_REPORT_PATH, TARGET_MATH)
        assertReportCount(requests, ENGLISH_REPORT_PATH, 7)
        assertBaselineVisible(automation)
        ObserverProductUi.assertRegistrationAndTargets(automation, accessibility, TIMEOUT_MS)

        val baselineNotification = ObserverTestSupport.waitForNotification(context, BASELINE_TIMEOUT_MS) {
            it.optString("title") == CHILD_NAME + " 학습 상태" &&
                it.optString("bigText").contains("✔ 수학 완료 5/5")
        }
        assertTrue(baselineNotification.getString("bigText").contains("한글 미달성 2/6"))
        assertTrue(baselineNotification.getString("bigText").contains("영어 미달성 7/8"))
        assertTrue(
            "An already complete baseline should remain a repeat-update status notification",
            (baselineNotification.getInt("flags") and Notification.FLAG_ONLY_ALERT_ONCE) != 0,
        )
        ObserverTestSupport.requireSilentChannel(ObserverTestSupport.readNotificationSnapshot(context))

        Log.i(STAGE_LOG_TAG, runId + ":" + STAGE_REGISTERED_BEFORE_REBOOT)
        ObserverTestSupport.awaitFixtureState(apiBaseUrl, STATE_PATH, runId, TIMEOUT_MS) {
            it.optBoolean("ticket06PreRebootPreferencesCaptured")
        }
        emitResult(runId, PHASE_REBOOT_PREPARATION)
    }

    private fun runPostRebootCase(
        instrumentation: Instrumentation,
        automation: UiAutomation,
        apiBaseUrl: String,
        runId: String,
        postRebootSequence: Long,
    ) {
        val context = instrumentation.targetContext
        val productVisibleBeforeObservation =
            automation.rootInActiveWindow?.packageName?.toString() == PRODUCT_PACKAGE
        assertTrue("The product Activity was opened before checking automatic boot restoration", !productVisibleBeforeObservation)

        val bootState = ObserverTestSupport.awaitFixtureState(apiBaseUrl, STATE_PATH, runId, BOOT_BASELINE_TIMEOUT_MS) { state ->
            val postBootReports = ObserverTestSupport.requests(state).filter {
                it.optLong("sequence") > postRebootSequence &&
                    it.optString("path") in REPORT_PATHS &&
                    it.optInt("responseStatus", -1) == 200
            }
            REPORT_PATHS.all { path -> postBootReports.any { it.optString("path") == path } }
        }
        val postBootRequests = ObserverTestSupport.requests(bootState)
        ObserverTestSupport.assertFixtureRequestContract(postBootRequests)
        val postBootReports = postBootRequests.filter {
            it.optLong("sequence") > postRebootSequence && it.optString("path") in REPORT_PATHS
        }
        assertReportCount(postBootReports, HANGEUL_REPORT_PATH, 2)
        assertReportCount(postBootReports, MATH_REPORT_PATH, TARGET_MATH)
        assertReportCount(postBootReports, ENGLISH_REPORT_PATH, 7)

        val baselineNotification = ObserverTestSupport.waitForNotification(context, TIMEOUT_MS) {
            it.optString("title") == CHILD_NAME + " 학습 상태" &&
                it.optString("bigText").contains("✔ 수학 완료 5/5")
        }
        assertTrue(baselineNotification.getString("bigText").contains("한글 미달성 2/6"))
        assertTrue(baselineNotification.getString("bigText").contains("영어 미달성 7/8"))
        assertTrue(
            "The boot baseline should not change the notification from repeat-update status mode",
            (baselineNotification.getInt("flags") and Notification.FLAG_ONLY_ALERT_ONCE) != 0,
        )
        ObserverTestSupport.requireSilentChannel(ObserverTestSupport.readNotificationSnapshot(context))

        val changedCount = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket06/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", TARGET_HANGEUL)),
        )
        val beforeCompletion = changedCount.getLong("afterRequestSequence")
        launchSubjectApp(context, HANGEUL_APP_PACKAGE)
        waitForForegroundPackage(automation, HANGEUL_APP_PACKAGE)
        val completionState = ObserverTestSupport.awaitFixtureState(apiBaseUrl, STATE_PATH, runId, CADENCE_TIMEOUT_MS) { state ->
            ObserverTestSupport.requests(state).any {
                it.optLong("sequence") > beforeCompletion &&
                    it.optString("path") == HANGEUL_REPORT_PATH &&
                    it.optInt("responseStatus", -1) == 200 &&
                    it.optInt("servedReportCount", -1) == TARGET_HANGEUL
            }
        }
        ObserverTestSupport.assertFixtureRequestContract(ObserverTestSupport.requests(completionState))
        assertReportCount(ObserverTestSupport.requests(completionState), HANGEUL_REPORT_PATH, TARGET_HANGEUL)
        val completionNotification = ObserverTestSupport.waitForNotification(context, CADENCE_TIMEOUT_MS) {
            it.optString("title") == CHILD_NAME + " · " + HANGEUL_SUBJECT + " 완료"
        }
        val body = completionNotification.getString("bigText")
        assertTrue(body.contains("✔ 한글 완료 6/6"))
        assertTrue(body.contains("✔ 수학 완료 5/5"))
        assertTrue(body.contains("영어 미달성 7/8"))

        ObserverProductUi.openProductApp(instrumentation)
        enterProductUi(automation)
        ObserverProductUi.assertScreenCounts(automation, accessibility, TARGET_HANGEUL, TARGET_MATH, 7, TIMEOUT_MS)
        ObserverProductUi.assertRegistrationAndTargets(automation, accessibility, TIMEOUT_MS)
        emitResult(runId, PHASE_POST_REBOOT)
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
    }

    private fun assertBaselineVisible(automation: UiAutomation) {
        ObserverProductUi.assertScreenCounts(automation, accessibility, 2, TARGET_MATH, 7, TIMEOUT_MS)
    }

    private fun baselineCounts(): JSONObject =
        JSONObject().put("hangeul", 2).put("math", TARGET_MATH).put("english", 7)

    private fun assertReportCount(requests: List<JSONObject>, path: String, expected: Int) {
        val request = requests.lastOrNull {
            it.optString("path") == path && it.optInt("responseStatus", -1) == 200
        } ?: throw AssertionError("Fixture did not receive a successful report for " + path)
        ObserverTestSupport.assertReportResponse(request, expected)
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

    private fun emitResult(runId: String, phase: String) {
        val result = JSONObject()
            .put("runId", runId)
            .put("scenario", "ticket06")
            .put("phase", phase)
        val encoded = Base64.encodeToString(result.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        Log.i(RESULT_LOG_TAG, "$RESULT_CHUNK_MARKER$runId:1/1:$encoded")
    }

    private companion object {
        const val ARG_API_BASE_URL = "apiBaseUrl"
        const val ARG_RUN_ID = "runId"
        const val ARG_PHASE = "phase"
        const val ARG_POST_REBOOT_SEQUENCE = "postRebootSequence"
        const val PRODUCT_PACKAGE = ObserverProductUi.PRODUCT_PACKAGE
        const val CHILD_NAME = ObserverProductUi.CHILD_NAME
        const val HANGEUL_APP_PACKAGE = "com.enuma.todohangeul"
        const val HANGEUL_SUBJECT = ObserverTestSupport.HANGEUL_SUBJECT
        const val HANGEUL_REPORT_PATH = ObserverTestSupport.HANGEUL_REPORT_PATH
        const val MATH_REPORT_PATH = ObserverTestSupport.MATH_REPORT_PATH
        const val ENGLISH_REPORT_PATH = ObserverTestSupport.ENGLISH_REPORT_PATH
        const val TARGET_MATH = ObserverProductUi.TARGET_MATH
        const val TARGET_HANGEUL = ObserverProductUi.TARGET_HANGEUL
        const val TARGET_ENGLISH = ObserverProductUi.TARGET_ENGLISH
        const val PHASE_REBOOT_PREPARATION = "reboot-preparation"
        const val PHASE_POST_REBOOT = "post-reboot"
        const val STAGE_LOG_TAG = "Ticket06Stage"
        const val STAGE_REGISTERED_BEFORE_REBOOT = "registered-before-reboot"
        const val TIMEOUT_MS = 30_000L
        const val BASELINE_TIMEOUT_MS = 120_000L
        const val BOOT_BASELINE_TIMEOUT_MS = 120_000L
        const val CADENCE_TIMEOUT_MS = 180_000L
        const val LOGIN_FORM_TIMEOUT_MS = 2_000L
        const val SERVER_TIMEOUT_MS = 5_000
        const val POLL_INTERVAL_MS = 250L
        const val FOREGROUND_SETTLE_MS = 300L
        const val MAX_WAKE_LOCK_MS = 15 * 60 * 1000L
        const val STATE_PATH = "/__ticket06/state"
        const val RESULT_LOG_TAG = "Ticket06Result"
        const val RESULT_CHUNK_MARKER = "TICKET06_RESULT_CHUNK:"
        val REPORT_PATHS = setOf(HANGEUL_REPORT_PATH, MATH_REPORT_PATH, ENGLISH_REPORT_PATH)
    }
}
