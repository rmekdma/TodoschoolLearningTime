package com.example.todoschooltime.e2eobserver

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises both permission boundaries through real HTTP and user-operated Android Settings. */
@RunWith(AndroidJUnit4::class)
class Ticket04PermissionRecoveryTest {
    private var screenWakeLock: PowerManager.WakeLock? = null

    @Test
    fun notificationAndAccessibilityRevocationCancelInFlightRequestsAndRecoverSilently() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val apiBaseUrl = arguments.getString(ARG_API_BASE_URL)
            ?: throw AssertionError("Missing apiBaseUrl instrumentation argument")
        val runId = arguments.getString(ARG_RUN_ID)
            ?: throw AssertionError("Missing runId instrumentation argument")
        val caseMode = arguments.getString(ARG_CASE_MODE, CASE_MODE_BOTH)
        assertTrue("Unsupported Ticket 04 case mode: $caseMode", caseMode in setOf(CASE_MODE_BOTH, CASE_MODE_NOTIFICATION, CASE_MODE_ACCESSIBILITY))
        assertTrue("Ticket 04 requires its loopback-only fixture", apiBaseUrl.startsWith(LOOPBACK_PREFIX))
        val seoulDate = seoulDateNumber()
        val observerContext = instrumentation.targetContext
        assertEquals(OBSERVER_PACKAGE, observerContext.packageName)
        assertEquals(runId, fixtureRequest(apiBaseUrl, "GET", "/__ticket04/health").getString("runId"))

        val beforeUiAutomation = readProductState(observerContext)
        assertProductPermissionsReady(beforeUiAutomation)
        val initialRegistrations = beforeUiAutomation.getJSONArray("registeredChildren").strings().toSet()
        assertTrue("Only an empty state or this scenario's previous test child is allowed before setup", initialRegistrations.isEmpty() || initialRegistrations == setOf(CHILD_NAME))
        if (initialRegistrations.isEmpty()) {
            assertEquals("An unregistered test child must not leave a notification behind", 0, beforeUiAutomation.getJSONArray("activeNotifications").length())
        } else {
            onlyChildNotification(beforeUiAutomation)
        }
        requireSilentChannel(beforeUiAutomation)

        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val afterUiAutomation = readProductState(observerContext)
        assertProductPermissionsReady(afterUiAutomation)
        requireSilentChannel(afterUiAutomation)
        acquireObserverScreenWakeLock(instrumentation, afterUiAutomation)

        val productMainIntent = productMainIntent(observerContext, clearExistingTask = true)
        observerContext.startActivity(productMainIntent)
        enterFakeCredentialsIfNeeded(automation)

        prepareKnownSilentBaseline(
            automation = automation,
            context = observerContext,
            apiBaseUrl = apiBaseUrl,
            runId = runId,
            reportCounts = if (caseMode == CASE_MODE_ACCESSIBILITY) ACCESSIBILITY_INITIAL_COUNTS else INITIAL_COUNTS,
        )
        val initialState = readProductState(observerContext)
        val hangeulId = onlyChildNotification(initialState).getInt("id")
        val initialHangeulCount = if (caseMode == CASE_MODE_ACCESSIBILITY) 6 else 2
        val initialHangeulStatus = if (caseMode == CASE_MODE_ACCESSIBILITY) "COMPLETE" else "BELOW_TARGET"
        assertExpectedSubject(initialState, HANGEUL_SUBJECT, initialHangeulStatus, initialHangeulCount, 6)
        assertExpectedSubject(initialState, MATH_SUBJECT, "COMPLETE", 5, 5)
        assertExpectedSubject(initialState, ENGLISH_SUBJECT, "BELOW_TARGET", 7, 8)

        var notificationCase: JSONObject? = null
        if (caseMode != CASE_MODE_ACCESSIBILITY) {
            notificationCase = runPermissionCase(
                instrumentation = instrumentation,
                automation = automation,
                context = observerContext,
                apiBaseUrl = apiBaseUrl,
                runId = runId,
                seoulDate = seoulDate,
                caseId = "notification-revocation",
                permission = PermissionKind.NOTIFICATIONS,
                subjectName = HANGEUL_SUBJECT,
                subjectPath = HANGEUL_REPORT_PATH,
                subjectPackage = HANGEUL_APP_PACKAGE,
                expectedBeforeCount = 2,
                heldResponseStatus = 200,
                heldResponseCount = 6,
                recoveryCounts = INITIAL_COUNTS,
                followUpCount = 6,
                followUpTarget = 6,
                notificationId = hangeulId,
            )
        }

        var accessibilityCase: JSONObject? = null
        if (caseMode != CASE_MODE_NOTIFICATION) {
            accessibilityCase = runPermissionCase(
                instrumentation = instrumentation,
                automation = automation,
                context = observerContext,
                apiBaseUrl = apiBaseUrl,
                runId = runId,
                seoulDate = seoulDate,
                caseId = "accessibility-revocation",
                permission = PermissionKind.ACCESSIBILITY,
                subjectName = ENGLISH_SUBJECT,
                subjectPath = ENGLISH_REPORT_PATH,
                subjectPackage = ENGLISH_APP_PACKAGE,
                expectedBeforeCount = 7,
                heldResponseStatus = 401,
                heldResponseCount = null,
                recoveryCounts = ACCESSIBILITY_INITIAL_COUNTS,
                followUpCount = 8,
                followUpTarget = 8,
                notificationId = hangeulId,
            )
        }

        assertEquals(seoulDate, seoulDateNumber())
        val finalState = readProductState(observerContext)
        assertReadyProduct(finalState)
        assertEquals("Ticket 04 must preserve the registration", setOf(CHILD_NAME), finalState.getJSONArray("registeredChildren").strings().toSet())
        assertFalse("Ticket 04 may not leave an authentication pause", screenTexts(automation).any { it.contains("다시 로그인 필요") })

        val cases = JSONArray().apply {
            notificationCase?.let(::put)
            accessibilityCase?.let(::put)
        }
        emitResult(
            JSONObject()
                .put("runId", runId)
                .put("caseMode", caseMode)
                .put("apiBaseUrl", apiBaseUrl)
                .put("deviceApi", android.os.Build.VERSION.SDK_INT)
                .put("seoulDate", seoulDate)
                .put("notificationProcessRestartObserved", notificationCase?.optBoolean("processRestartedAfterBoundary") ?: false)
                .put("accessibilitySamePidBoundaryObserved", accessibilityCase?.optBoolean("sameProductPidAtBoundary") ?: false)
                .put("registrationPreserved", true)
                .put("silentChannelPrepared", true)
                .put("productAlertDeliveryTested", false)
                .put("actualTodoApiCalled", false)
                .put("callbackDeliveryObservation", "unobserved; HTTP cancellation or read timeout may prevent delivery, and the cause is not inferred")
                .put("engineStaleOutcomeUnitTest", "lateBaselineSuccessFailureAndAuthenticationDenialCannotChangeRecoveredState")
                .put("cases", cases)
        )
    }

    private fun prepareKnownSilentBaseline(
        automation: UiAutomation,
        context: Context,
        apiBaseUrl: String,
        runId: String,
        reportCounts: Map<String, Int>,
    ) {
        val state = waitForProductState(context, SETUP_TIMEOUT_MS) {
            it.optString("accountId") == ACCOUNT_ID && it.getBoolean("serviceLeaseAttached")
        }
        assertProductPermissionsReady(state)
        val registrations = state.getJSONArray("registeredChildren").strings().toSet()
        assertTrue("Unexpected registered children are present: $registrations", registrations.isEmpty() || registrations == setOf(CHILD_NAME))
        if (registrations == setOf(CHILD_NAME)) {
            clickDescription(automation, "$CHILD_NAME 감시 해제")
            waitForProductState(context, SETUP_TIMEOUT_MS) {
                it.getJSONArray("registeredChildren").length() == 0 && it.getJSONArray("activeNotifications").length() == 0
            }
        }

        writeDefaultTargetsThroughTheApp(automation)
        fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket04/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", reportCounts.getValue("hangeul")).put("math", reportCounts.getValue("math")).put("english", reportCounts.getValue("english"))),
        ).also { assertEquals(runId, it.getString("runId")) }
        fixtureRequest(apiBaseUrl, "POST", "/__ticket04/reset-requests", JSONObject())
        val notificationCursor = lastSequence(readProductState(context).getJSONArray("notificationEvents"))
        clickDescription(automation, "$CHILD_NAME 감시 등록")
        val expectedHangeulCount = reportCounts.getValue("hangeul")
        val expectedHangeulStatus = if (expectedHangeulCount >= TARGET_COUNTS.getValue(HANGEUL_SUBJECT)) "COMPLETE" else "BELOW_TARGET"
        val baseline = waitForProductState(context, RECOVERY_TIMEOUT_MS) { snapshot ->
            snapshot.getJSONArray("registeredChildren").strings().contains(CHILD_NAME) &&
                snapshot.getBoolean("serviceLeaseAttached") &&
                subject(snapshot, HANGEUL_SUBJECT).let { it.optString("status") == expectedHangeulStatus && it.optInt("activityCount", -1) == expectedHangeulCount && it.optInt("targetCount", -1) == TARGET_COUNTS.getValue(HANGEUL_SUBJECT) } &&
                subject(snapshot, MATH_SUBJECT).let { it.optString("status") == "COMPLETE" && it.optInt("activityCount", -1) == 5 && it.optInt("targetCount", -1) == TARGET_COUNTS.getValue(MATH_SUBJECT) } &&
                subject(snapshot, ENGLISH_SUBJECT).let { it.optString("status") == "BELOW_TARGET" && it.optInt("activityCount", -1) == 7 && it.optInt("targetCount", -1) == TARGET_COUNTS.getValue(ENGLISH_SUBJECT) }
        }
        assertReadyProduct(baseline)
        val requests = awaitFixtureState(apiBaseUrl, runId, RECOVERY_TIMEOUT_MS) { fixture ->
            fixture.getJSONArray("requests").objects().count { it.optString("path") in REPORT_PATHS } == 3 &&
                fixture.getJSONArray("inFlightRequests").length() == 0
        }.getJSONArray("requests").objects()
        assertEquals("The prepared baseline must query each subscribed report exactly once", REPORT_PATHS.toSet(), requests.filter { it.optString("path") in REPORT_PATHS }.map { it.getString("path") }.toSet())
        assertFakeRequests(requests, seoulDateNumber())
        val baselineEvents = eventsAfter(baseline.getJSONArray("notificationEvents"), notificationCursor)
        assertTrue("The prepared baseline unexpectedly requested a completion alert: $baselineEvents", baselineEvents.none { it.optBoolean("completionRequested") })
        assertEquals(setOf(CHILD_NAME), baseline.getJSONArray("registeredChildren").strings().toSet())
        assertEquals(1, baseline.getJSONArray("activeNotifications").length())
        requireSilentChannel(baseline)
        assertEquals(runId, fixtureRequest(apiBaseUrl, "GET", "/__ticket04/state").getString("runId"))
    }

    private fun runPermissionCase(
        instrumentation: Instrumentation,
        automation: UiAutomation,
        context: Context,
        apiBaseUrl: String,
        runId: String,
        seoulDate: Int,
        caseId: String,
        permission: PermissionKind,
        subjectName: String,
        subjectPath: String,
        subjectPackage: String,
        expectedBeforeCount: Int,
        heldResponseStatus: Int,
        heldResponseCount: Int?,
        recoveryCounts: Map<String, Int>,
        followUpCount: Int,
        followUpTarget: Int,
        notificationId: Int,
    ): JSONObject {
        assertEquals(seoulDate, seoulDateNumber())
        val before = readProductState(context)
        assertReadyProduct(before)
        assertEquals(setOf(CHILD_NAME), before.getJSONArray("registeredChildren").strings().toSet())
        assertEquals(expectedBeforeCount, subject(before, subjectName).optInt("activityCount", -1))
        assertEquals(notificationId, onlyChildNotification(before).getInt("id"))
        requireSilentChannel(before)
        val baselineVectors = subjectVectors(before)
        val dispatchCursorBefore = before.getLong("httpRequestDispatchCursor")
        val notificationCursorBefore = lastSequence(before.getJSONArray("notificationEvents"))
        val productPidsBefore = productPids(automation)
        assertEquals("Expected exactly one live product PID before the boundary", 1, productPidsBefore.size)

        fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket04/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", recoveryCounts.getValue("hangeul")).put("math", recoveryCounts.getValue("math")).put("english", recoveryCounts.getValue("english"))),
        ).also { assertEquals(runId, it.getString("runId")) }
        fixtureRequest(apiBaseUrl, "POST", "/__ticket04/reset-requests", JSONObject())
        val armed = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket04/arm-report-hold",
            JSONObject()
                .put("caseId", caseId)
                .put("path", subjectPath)
                .put("responseStatus", heldResponseStatus)
                .apply { if (heldResponseCount != null) put("responseCount", heldResponseCount) },
        )
        assertTrue(armed.getBoolean("ok"))

        // Put the exact Settings page on the task stack first. The user still controls
        // the start button; this keeps Settings navigation outside the 15-second window.
        assertEquals("The Seoul date changed after the silent baseline was prepared; start a fresh run", seoulDate, seoulDateNumber())
        openPermissionSettings(automation, context, permission)
        val startClickedAt = awaitUserAction(
            context = context,
            runId = runId,
            caseId = caseId,
            action = "START",
            buttonLabel = "시험 시작",
            instructions = "$caseId 시험을 시작할 준비가 되었으면 아래 버튼을 누르세요.\n\n버튼을 누르면 실제 보고 요청을 시작합니다. 뒤에 준비된 Android 설정 화면에서 ${permission.displayName} 권한을 직접 끄는 작업만 수동으로 해주세요.",
            expectedSeoulDate = seoulDate,
        )
        emitProgress(
            runId,
            "USER_STARTED",
            caseId,
            JSONObject().put("buttonClickedElapsedRealtimeMs", startClickedAt).toString(),
        )
        launchActualSubjectApp(context, subjectPackage)
        val heldFixture = awaitFixtureState(apiBaseUrl, runId, REGULAR_POLL_TIMEOUT_MS) { snapshot ->
            snapshot.getJSONArray("inFlightRequests").objects().any { it.optString("caseId") == caseId && it.optString("path") == subjectPath }
        }
        val held = heldFixture.getJSONArray("inFlightRequests").objects().single { it.optString("caseId") == caseId }
        val heldSequence = held.getLong("sequence")
        val heldRequest = heldFixture.getJSONArray("requests").objects().single { it.optLong("sequence") == heldSequence }
        assertEquals(JSONObject.NULL, heldRequest.get("responseStatus"))
        assertEquals("Bearer $AUTH_TOKEN", heldRequest.getJSONObject("headers").getString("authorization"))
        assertEquals(SUBJECT_USER_IDS.getValue(subjectName), heldRequest.getJSONObject("body").getString("userId"))
        assertEquals(seoulDate, heldRequest.getJSONObject("body").getInt("yyyymmdd"))
        assertEquals("ko", heldRequest.getJSONObject("body").getString("languageCode"))

        val dispatchState = waitForProductState(context, 2_000L) { state ->
            state.getJSONArray("httpRequestDispatchEvents").objects().any {
                it.optLong("sequence") > dispatchCursorBefore && it.optString("path") == subjectPath
            }
        }
        val dispatch = dispatchState.getJSONArray("httpRequestDispatchEvents").objects().last {
            it.optLong("sequence") > dispatchCursorBefore && it.optString("path") == subjectPath
        }
        assertEquals(subjectPackage, dispatch.getString("foregroundPackage"))
        assertTrue("The real monitor report must have dispatched with a connected AccessibilityService lease", dispatch.getBoolean("serviceLeaseAttached"))
        assertTrue("The observed foreground event must precede the report dispatch", dispatch.getLong("foregroundEventElapsedRealtimeMs") <= dispatch.getLong("elapsedRealtimeMs"))

        openPermissionSettings(automation, context, permission)
        emitProgress(runId, "ACTION_REQUIRED", caseId, permission.manualInstruction)
        val revokedState = waitForManualRevocation(
            context = context,
            automation = automation,
            permission = permission,
            dispatchElapsedRealtimeMs = dispatch.getLong("elapsedRealtimeMs"),
            apiBaseUrl = apiBaseUrl,
            runId = runId,
            caseId = caseId,
            heldSequence = heldSequence,
            expectedProductPid = productPidsBefore.singleOrNull().orEmpty(),
        )
        val pidAtBoundary = productPids(automation)
        val samePidAtBoundary = pidAtBoundary == productPidsBefore
        val requestAgeAtBoundary = revokedState.optLong(
            "requestAgeAtBoundaryMs",
            revokedState.getLong("boundaryElapsedRealtimeMs") - dispatch.getLong("elapsedRealtimeMs"),
        )
        val requestBoundaryClassification = if (permission == PermissionKind.ACCESSIBILITY) {
            revokedState.getString("requestBoundaryClassification")
        } else {
            Ticket04BoundaryEvidence.classify(
                requestAgeAtBoundaryMs = requestAgeAtBoundary,
                requestStillInFlight = revokedState.getBoolean("requestStillInFlight"),
                clientSocketOpen = revokedState.getBoolean("clientSocketOpenAtBoundary"),
            )
        }
        val boundaryDescription = when (requestBoundaryClassification) {
            Ticket04BoundaryEvidence.CANCELLATION_CONSISTENT -> "마지막 ON 관측에서 같은 PID의 요청과 연결이 살아 있었고, OFF/lease 해제 관측까지의 짧은 구간에서 서버 연결 종료가 기록됐습니다. 이 구간은 취소와 일치하지만 인과를 단정하지 않습니다."
            Ticket04BoundaryEvidence.LIVE_REQUEST_WITHIN_EVIDENCE_WINDOW -> "권한 OFF 시점에 진행 중인 보고 요청과 열린 연결을 확인했습니다."
            Ticket04BoundaryEvidence.LIVE_REQUEST_OUTSIDE_EVIDENCE_WINDOW -> "권한 OFF 시점에도 요청은 진행 중이었지만 13.5초 증거 창 밖입니다. 취소 근거로 해석하지 않습니다."
            Ticket04BoundaryEvidence.CLIENT_SOCKET_CLOSED_AT_BOUNDARY -> "권한 OFF 시점에 서버 연결은 이미 닫혀 있었습니다. 원인은 확인되지 않았습니다."
            Ticket04BoundaryEvidence.NO_CLIENT_REQUEST_AT_BOUNDARY -> "권한 OFF 시점에는 클라이언트 요청이 진행 중이지 않았습니다. 이 결과를 취소 근거로 해석하지 않습니다."
            else -> "요청 나이 또는 경계 상태를 확정할 수 없습니다."
        }
        val acknowledgementAt = awaitUserAction(
            context = context,
            runId = runId,
            caseId = "$caseId-confirm-off",
            action = "CONTINUE_AFTER_OFF",
            buttonLabel = "권한 OFF 확인 · 계속",
            instructions = "${permission.displayName} 권한 OFF를 확인했습니다.\n\n$boundaryDescription\n\n권한이 꺼진 상태인지 확인하고 결과 확인을 계속하려면 아래 버튼을 누르세요. 서버 응답은 이 버튼을 누르기 전까지 해제하지 않습니다.",
            expectedSeoulDate = seoulDate,
        )
        assertFalse("The permission must still be disabled when the user acknowledges the boundary", permission.isEnabled(context, automation))

        if (permission == PermissionKind.ACCESSIBILITY) {
            assertTrue("The AccessibilityService permission boundary changed the product process: before=$productPidsBefore atBoundary=$pidAtBoundary", samePidAtBoundary)
            val accessibilityBoundary = revokedState.getJSONObject("accessibilityBoundaryEvidence")
            assertTrue(
                "The AccessibilityService boundary is not cancellation-consistent within a bounded live-to-OFF observation interval: $accessibilityBoundary",
                accessibilityBoundary.getBoolean("qualifies"),
            )
            assertEquals(Ticket04BoundaryEvidence.CANCELLATION_CONSISTENT, requestBoundaryClassification)
        }

        openProductMainActivity(context)
        val pausedUi = waitForPauseUi(automation, PAUSE_UI_TIMEOUT_MS)
        val pauseSnapshot = readProductState(context)
        val pidsAfterReturn = productPids(automation)
        if (permission == PermissionKind.NOTIFICATIONS) {
            assertEquals("Notification revocation should restart the product process before recovery checks", 1, pidsAfterReturn.size)
            assertFalse("The notification-off boundary did not restart the product process", productPidsBefore == pidsAfterReturn)
        } else {
            assertEquals("The AccessibilityService boundary changed the product process before response release", productPidsBefore, pidsAfterReturn)
        }
        assertEquals(setOf(CHILD_NAME), pauseSnapshot.getJSONArray("registeredChildren").strings().toSet())
        var subjectStateComparison = "not-yet-compared"
        if (permission == PermissionKind.ACCESSIBILITY) {
            assertEquals("The same-process Accessibility pause changed the subject state", baselineVectors, subjectVectors(pauseSnapshot))
        }
        assertFalse("Permission revocation must not create an authentication pause: $pausedUi", pausedUi.any { it.contains("다시 로그인 필요") })
        assertTrue("The real monitoring UI did not show its permission-paused state: $pausedUi", pausedUi.any { it.contains("감시 일시 중지") })
        val completionEventsAtPause = eventsAfter(pauseSnapshot.getJSONArray("notificationEvents"), notificationCursorBefore)
            .filter { it.optBoolean("completionRequested") }
        assertTrue("The permission-paused state requested a completion alert: $completionEventsAtPause", completionEventsAtPause.isEmpty())
        val pauseLease = pauseSnapshot.getBoolean("serviceLeaseAttached")
        if (permission == PermissionKind.NOTIFICATIONS) {
            assertTrue("Notification revocation unexpectedly detached AccessibilityService", pauseLease)
        } else {
            assertFalse("The accessibility service should detach after the user disables it", pauseLease)
        }

        val beforeReleaseFixture = fixtureRequest(apiBaseUrl, "GET", "/__ticket04/state")
        val stillHeld = beforeReleaseFixture.getJSONArray("serverHeldResponses").objects().singleOrNull { it.optLong("sequence") == heldSequence }
        assertNotNull("The same server-side HTTP response must remain held until the user's acknowledgement", stillHeld)
        val clientConnectedAtAcknowledgement = stillHeld!!.getBoolean("clientConnected")
        val clientDisconnectSignal = stillHeld.optString("clientDisconnectSignal")
        val disconnectAfterReceiveMs = stillHeld.optLong("clientDisconnectAfterServerReceiveMs", -1L)
        val releaseAgeMs = SystemClock.elapsedRealtime() - dispatch.getLong("elapsedRealtimeMs")
        val connectionCloseClassification = when {
            clientConnectedAtAcknowledgement && releaseAgeMs < MAX_RELEASE_REQUEST_AGE_MS -> "client-still-connected-at-explicit-release; response-delivery-outcome-will-be-observed"
            !clientConnectedAtAcknowledgement && disconnectAfterReceiveMs >= requestAgeAtBoundary - DISCONNECT_BOUNDARY_TOLERANCE_MS && disconnectAfterReceiveMs < READ_TIMEOUT_MS -> "client-connection-closed-after-permission-boundary-within-15s-window; cancellation-consistent, cause-unconfirmed"
            !clientConnectedAtAcknowledgement && disconnectAfterReceiveMs >= READ_TIMEOUT_MS -> "late-client-connection-close; timeout-or-other-close-ambiguous"
            !clientConnectedAtAcknowledgement -> "client-connection-closed-before-permission-boundary-or-boundary-order-ambiguous"
            else -> "client-still-connected-near-or-after-read-timeout; outcome-ambiguous"
        }
        assertTrue("The user must acknowledge the permission-off boundary before release", acknowledgementAt > revokedState.getLong("boundaryElapsedRealtimeMs"))
        val responseReleaseElapsedRealtimeMs = SystemClock.elapsedRealtime()

        val release = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket04/release-report-hold",
            JSONObject().put("caseId", caseId).put("sequence", heldSequence),
        )
        assertTrue("The server must release only the matching held request", release.getBoolean("ok"))
        val responseState = awaitFixtureState(apiBaseUrl, runId, RELEASE_TIMEOUT_MS) { snapshot ->
            snapshot.getJSONArray("requests").objects().any { it.optLong("sequence") == heldSequence && it.opt("responseStatus") != JSONObject.NULL }
        }
        val heldResponse = responseState.getJSONArray("requests").objects().single { it.optLong("sequence") == heldSequence }
        assertEquals(heldResponseStatus, heldResponse.getInt("responseStatus"))
        val serverWrite = heldResponse.optString("deliveryStatus")
        assertTrue("The server must record whether the response write completed or failed", serverWrite == "written" || serverWrite == "write-failed")
        SystemClock.sleep(CALLBACK_SETTLE_MS)
        val afterRelease = readProductState(context)
        assertEquals("The permission pause must remain active after the previous response returns", pauseSnapshot.getJSONArray("registeredChildren").strings().toSet(), afterRelease.getJSONArray("registeredChildren").strings().toSet())
        if (permission == PermissionKind.NOTIFICATIONS) {
            assertEquals(
                "The old held report changed the subject state in the restarted process",
                subjectVectors(pauseSnapshot),
                subjectVectors(afterRelease),
            )
            subjectStateComparison = "unchanged-after-release-in-restarted-process"
        } else {
            assertEquals("The held report changed the child state after its same-process generation was invalidated", baselineVectors, subjectVectors(afterRelease))
            subjectStateComparison = "unchanged-after-release-in-same-process"
        }
        assertFalse("A stale HTTP response created an authentication pause", screenTexts(automation).any { it.contains("다시 로그인 필요") })
        val staleCompletionEvents = eventsAfter(afterRelease.getJSONArray("notificationEvents"), notificationCursorBefore)
            .filter { it.optBoolean("completionRequested") }
        assertTrue("The stale held response requested a completion alert: $staleCompletionEvents", staleCompletionEvents.isEmpty())

        fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket04/reset-requests",
            JSONObject(),
        )
        fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket04/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", recoveryCounts.getValue("hangeul")).put("math", recoveryCounts.getValue("math")).put("english", recoveryCounts.getValue("english"))),
        )
        val notificationCursorBeforeRecovery = lastSequence(afterRelease.getJSONArray("notificationEvents"))
        val dispatchCursorBeforeRecovery = afterRelease.getLong("httpRequestDispatchCursor")
        openPermissionSettings(automation, context, permission)
        emitProgress(runId, "ACTION_REQUIRED", "$caseId-restore", permission.restoreInstruction)
        waitForManualRestoration(context, automation, permission)
        openProductMainActivity(context)
        val recoveredLease = waitForProductState(context, RESTORE_TIMEOUT_MS) { state ->
            state.getBoolean("accessibilityEnabled") && state.getBoolean("notificationsEnabled") &&
                state.getBoolean("serviceLeaseAttached") &&
                state.getJSONArray("registeredChildren").strings().contains(CHILD_NAME)
        }
        assertReadyProduct(recoveredLease)
        assertEquals(setOf(CHILD_NAME), recoveredLease.getJSONArray("registeredChildren").strings().toSet())
        requireSilentChannel(recoveredLease)

        val baselineServer = awaitFixtureState(apiBaseUrl, runId, RECOVERY_TIMEOUT_MS) { fixture ->
            fixture.getJSONArray("requests").objects().count { it.optString("path") in REPORT_PATHS } == 3 &&
                fixture.getJSONArray("inFlightRequests").length() == 0
        }
        val baselineRequests = baselineServer.getJSONArray("requests").objects().filter { it.optString("path") in REPORT_PATHS }
        assertEquals("Permission restoration must establish every subscribed subject as a fresh baseline", REPORT_PATHS.toSet(), baselineRequests.map { it.getString("path") }.toSet())
        assertTrue("The restored baseline contains a stale date or real account", baselineRequests.all {
            it.getJSONObject("body").optInt("yyyymmdd") == seoulDate && it.getJSONObject("body").optString("languageCode") == "ko"
        })
        assertFakeRequests(baselineServer.getJSONArray("requests").objects(), seoulDate)
        val baselineState = waitForProductState(context, RECOVERY_TIMEOUT_MS) { state ->
            state.getBoolean("serviceLeaseAttached") && state.getBoolean("accessibilityEnabled") && state.getBoolean("notificationsEnabled") &&
                state.getJSONArray("registeredChildren").strings().contains(CHILD_NAME) &&
                state.getLong("httpRequestDispatchCursor") > dispatchCursorBeforeRecovery &&
                recoveryCounts.all { (key, count) -> subject(state, SUBJECT_NAMES.getValue(key)).optInt("activityCount", -1) == count }
        }
        assertReadyProduct(baselineState)
        assertEquals("The recovery baseline must not request a completion alert", emptyList<JSONObject>(), eventsAfter(baselineState.getJSONArray("notificationEvents"), notificationCursorBeforeRecovery).filter { it.optBoolean("completionRequested") })
        assertEquals(notificationId, onlyChildNotification(baselineState).getInt("id"))
        requireSilentChannel(baselineState)
        assertEquals(seoulDate, seoulDateNumber())

        val completionCursor = lastSequence(baselineState.getJSONArray("notificationEvents"))
        val completionUpdate = fixtureRequest(
            apiBaseUrl,
            "POST",
            "/__ticket04/set-report-counts",
            JSONObject().put("reports", JSONObject().put(if (subjectName == HANGEUL_SUBJECT) "hangeul" else "english", followUpCount)),
        )
        val afterRequestSequence = completionUpdate.getLong("afterRequestSequence")
        val foregroundCursor = lastSequence(baselineState.getJSONArray("foregroundEvents"))
        launchActualSubjectApp(context, subjectPackage)
        waitForProductState(context, REGULAR_POLL_TIMEOUT_MS) { state ->
            state.getJSONArray("foregroundEvents").objects().any { it.optLong("sequence") > foregroundCursor && it.optString("packageName") == subjectPackage }
        }
        val completionServer = awaitFixtureState(apiBaseUrl, runId, REGULAR_POLL_TIMEOUT_MS) { fixture ->
            fixture.getJSONArray("requests").objects().any {
                it.optLong("sequence") > afterRequestSequence && it.optString("path") == subjectPath &&
                    it.opt("responseStatus") != JSONObject.NULL && it.optInt("responseStatus") == 200 &&
                    it.optInt("servedReportCount", -1) == followUpCount
            }
        }
        val completionRequest = completionServer.getJSONArray("requests").objects().single {
            it.optLong("sequence") > afterRequestSequence && it.optString("path") == subjectPath && it.optInt("servedReportCount", -1) == followUpCount
        }
        assertEquals(seoulDate, completionRequest.getJSONObject("body").getInt("yyyymmdd"))
        assertEquals(SUBJECT_USER_IDS.getValue(subjectName), completionRequest.getJSONObject("body").getString("userId"))
        val completed = waitForProductState(context, REGULAR_POLL_TIMEOUT_MS) { state ->
            state.getBoolean("serviceLeaseAttached") && state.getJSONArray("registeredChildren").strings().contains(CHILD_NAME) &&
                subject(state, subjectName).let { it.optString("status") == "COMPLETE" && it.optInt("activityCount", -1) == followUpCount }
        }
        assertReadyProduct(completed)
        val completionEvents = eventsAfter(completed.getJSONArray("notificationEvents"), completionCursor)
            .filter { it.optBoolean("completionRequested") && it.optString("childName") == CHILD_NAME }
        assertEquals("A new post-recovery threshold crossing must produce exactly one completion update", 1, completionEvents.size)
        assertEquals(subjectName, completionEvents.single().getString("completionSubjectName"))
        assertEquals(notificationId, completionEvents.single().getInt("notificationId"))
        assertTrue(completionEvents.single().getBoolean("notificationManagerCallReturned"))
        val activeNotifications = completed.getJSONArray("activeNotifications").objects()
        assertEquals(listOf(notificationId), activeNotifications.map { it.getInt("id") })
        assertTrue(activeNotifications.single().optString("bigText").contains("✔ $subjectName 완료 $followUpCount/$followUpTarget"))

        val result = JSONObject()
            .put("runId", runId)
            .put("caseId", caseId)
            .put("buttonClickedElapsedRealtimeMs", startClickedAt)
            .put("permission", permission.name.lowercase())
            .put("subject", subjectName)
            .put("dispatchSequence", dispatch.getLong("sequence"))
            .put("dispatchElapsedRealtimeMs", dispatch.getLong("elapsedRealtimeMs"))
            .put("serverRequestSequence", heldSequence)
            .put("serverReceivedAt", held.getString("receivedAt"))
            .put("permissionBoundaryElapsedRealtimeMs", revokedState.getLong("boundaryElapsedRealtimeMs"))
            .put("actualPermissionEnabledAtBoundary", false)
            .put("requestAgeAtBoundaryMs", requestAgeAtBoundary)
            .put("requestBoundaryClassification", requestBoundaryClassification)
            .put("requestStillInFlight", revokedState.getBoolean("requestStillInFlight"))
            .put("clientSocketOpenAtPermissionBoundary", revokedState.getBoolean("clientSocketOpenAtBoundary"))
            .putOpt("accessibilityBoundaryEvidence", revokedState.optJSONObject("accessibilityBoundaryEvidence"))
            .put("sameProductPidAtBoundary", samePidAtBoundary)
            .put("processRestartExpected", permission == PermissionKind.NOTIFICATIONS)
            .put("processRestartedAfterBoundary", productPidsBefore != pidsAfterReturn)
            .put("productPidBefore", productPidsBefore.single())
            .put("productPidAtBoundary", pidAtBoundary.singleOrNull() ?: "")
            .put("productPidAfterReturn", pidsAfterReturn.single())
            .put("serviceLeaseAtDispatch", dispatch.getBoolean("serviceLeaseAttached"))
            .put("serviceLeaseAtPause", pauseLease)
            .put("permissionPauseUiObserved", true)
            .put("authenticationPauseAfterRelease", false)
            .put("staleCompletionRequests", staleCompletionEvents.size)
            .put("registeredChildrenPreserved", true)
            .put("subjectStateComparison", subjectStateComparison)
            .put("subjectVectorsAtPause", JSONArray(subjectVectors(pauseSnapshot)))
            .put("subjectVectorsAfterRelease", JSONArray(subjectVectors(afterRelease)))
            .put("heldResponseStatus", heldResponseStatus)
            .put("serverResponseWrite", serverWrite)
            .put("clientConnectedAtUserAcknowledgement", clientConnectedAtAcknowledgement)
            .put("clientDisconnectSignal", clientDisconnectSignal)
            .put("clientDisconnectAfterServerReceiveMs", disconnectAfterReceiveMs)
            .put("userAcknowledgementElapsedRealtimeMs", acknowledgementAt)
            .put("responseReleaseElapsedRealtimeMs", responseReleaseElapsedRealtimeMs)
            .put("connectionCloseClassification", connectionCloseClassification)
            .put("callbackDeliveryObserved", false)
            .put("restoredServiceLease", recoveredLease.getBoolean("serviceLeaseAttached"))
            .put("restoredProductPid", productPids(automation).firstOrNull() ?: "")
            .put("recoveryReportPaths", JSONArray(baselineRequests.map { it.getString("path") }))
            .put("recoverySilentBaseline", true)
            .put("recoveryNotificationId", onlyChildNotification(baselineState).getInt("id"))
            .put("laterCompletionSubject", subjectName)
            .put("laterCompletionCount", completionEvents.size)
            .put("laterCompletionNotificationId", completionEvents.single().getInt("notificationId"))
        emitProgress(runId, "CASE_RESULT", caseId, result.toString())
        return result
    }

    private fun awaitUserAction(
        context: Context,
        runId: String,
        caseId: String,
        action: String,
        buttonLabel: String,
        instructions: String,
        expectedSeoulDate: Int? = null,
    ): Long {
        val token = "$runId-$caseId-${SystemClock.elapsedRealtime()}"
        val preferences = context.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .remove(Ticket04ControlActivity.KEY_CLICKED_TOKEN)
            .remove(Ticket04ControlActivity.KEY_CLICKED_AT)
            .putString(Ticket04ControlActivity.KEY_ACTIVE_TOKEN, token)
            .commit()
        emitProgress(runId, "WAITING_FOR_USER_ACTION", caseId, "$action: $instructions")
        context.startActivity(
            Intent(context, Ticket04ControlActivity::class.java)
                .putExtra(Ticket04ControlActivity.EXTRA_CASE_ID, caseId)
                .putExtra(Ticket04ControlActivity.EXTRA_TOKEN, token)
                .putExtra(Ticket04ControlActivity.EXTRA_INSTRUCTIONS, instructions)
                .putExtra(Ticket04ControlActivity.EXTRA_BUTTON_LABEL, buttonLabel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val deadline = SystemClock.elapsedRealtime() + USER_ACTION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (expectedSeoulDate != null && seoulDateNumber() != expectedSeoulDate) {
                expireUserAction(preferences, token)
                emitProgress(runId, "ACTION_EXPIRED", caseId, "$action 준비 시 저장한 한국 날짜가 바뀌어 이 화면을 만료했습니다. 새 시험을 준비해야 합니다.")
                throw AssertionError("Ticket 04 $caseId preparation expired because the Asia/Seoul date changed before the action was accepted")
            }
            if (Ticket04ActionToken.matches(
                    expectedToken = token,
                    clickedToken = preferences.getString(Ticket04ControlActivity.KEY_CLICKED_TOKEN, null),
                )
            ) {
                val clickedAt = preferences.getLong(Ticket04ControlActivity.KEY_CLICKED_AT, 0L)
                assertTrue("The user-start button did not record an elapsed-realtime timestamp", clickedAt > 0L)
                return clickedAt
            }
            SystemClock.sleep(MANUAL_POLL_INTERVAL_MS)
        }
        if (expectedSeoulDate == null || seoulDateNumber() == expectedSeoulDate) {
            if (Ticket04ActionToken.matches(
                    expectedToken = token,
                    clickedToken = preferences.getString(Ticket04ControlActivity.KEY_CLICKED_TOKEN, null),
                )
            ) {
                val clickedAt = preferences.getLong(Ticket04ControlActivity.KEY_CLICKED_AT, 0L)
                assertTrue("The user-start button did not record an elapsed-realtime timestamp", clickedAt > 0L)
                return clickedAt
            }
        }
        expireUserAction(preferences, token)
        emitProgress(runId, "ACTION_EXPIRED", caseId, "$action 준비 화면이 만료됐습니다. 새 시험 준비를 요청해야 합니다.")
        throw AssertionError("Ticket 04 $caseId did not receive the user's '$buttonLabel' button press before its preparation expired")
    }

    private fun expireUserAction(preferences: android.content.SharedPreferences, token: String) {
        if (Ticket04ActionToken.matches(
                expectedToken = token,
                clickedToken = preferences.getString(Ticket04ControlActivity.KEY_ACTIVE_TOKEN, null),
            )
        ) {
            preferences.edit().remove(Ticket04ControlActivity.KEY_ACTIVE_TOKEN).commit()
        }
    }

    private fun waitForManualRevocation(
        context: Context,
        automation: UiAutomation,
        permission: PermissionKind,
        dispatchElapsedRealtimeMs: Long,
        apiBaseUrl: String,
        runId: String,
        caseId: String,
        heldSequence: Long,
        expectedProductPid: String,
    ): JSONObject {
        if (permission == PermissionKind.ACCESSIBILITY) {
            return waitForAccessibilityRevocation(
                context = context,
                automation = automation,
                dispatchElapsedRealtimeMs = dispatchElapsedRealtimeMs,
                apiBaseUrl = apiBaseUrl,
                runId = runId,
                caseId = caseId,
                heldSequence = heldSequence,
                expectedProductPid = expectedProductPid,
            )
        }
        var lastState: JSONObject? = null
        val notificationWaitDeadline = SystemClock.elapsedRealtime() + MAX_NOTIFICATION_PERMISSION_WAIT_MS
        val waitDeadline = if (permission == PermissionKind.NOTIFICATIONS) {
            notificationWaitDeadline
        } else {
            dispatchElapsedRealtimeMs + MAX_BOUNDARY_REQUEST_AGE_MS
        }
        var lastProductStateSampleAt = 0L
        while (SystemClock.elapsedRealtime() < waitDeadline) {
            if (!permission.isEnabled(context, automation)) {
                val elapsed = SystemClock.elapsedRealtime()
                val state = runCatching { readProductState(context) }.getOrNull()
                if (state != null) lastState = state
                val pids = productPids(automation)
                val fixture = fixtureRequest(apiBaseUrl, "GET", "/__ticket04/state")
                val stillInFlight = fixture.getJSONArray("inFlightRequests").objects().any { it.optLong("sequence") == heldSequence && it.optString("caseId") == caseId }
                val serverHeld = fixture.getJSONArray("serverHeldResponses").objects().singleOrNull { it.optLong("sequence") == heldSequence }
                val requestBoundaryClassification = Ticket04BoundaryEvidence.classify(
                    requestAgeAtBoundaryMs = elapsed - dispatchElapsedRealtimeMs,
                    requestStillInFlight = stillInFlight,
                    clientSocketOpen = serverHeld?.optBoolean("clientConnected") ?: false,
                )
                val boundary = JSONObject()
                    .put("runId", runId)
                    .put("caseId", caseId)
                    .put("permission", permission.name.lowercase())
                    .put("actualPermissionEnabled", false)
                    .put("boundaryElapsedRealtimeMs", elapsed)
                    .put("productPid", pids.firstOrNull() ?: "")
                    .put("serviceLeaseAttached", state?.optBoolean("serviceLeaseAttached") ?: lastState?.optBoolean("serviceLeaseAttached") ?: false)
                    .put("requestStillInFlight", stillInFlight)
                    .put("clientSocketOpenAtBoundary", serverHeld?.optBoolean("clientConnected") ?: false)
                    .put("requestBoundaryClassification", requestBoundaryClassification)
                emitProgress(runId, "BOUNDARY_CAPTURED", caseId, boundary.toString())
                return boundary
            }

            val now = SystemClock.elapsedRealtime()
            if (permission == PermissionKind.ACCESSIBILITY || now - lastProductStateSampleAt >= PRODUCT_STATE_SAMPLE_INTERVAL_MS) {
                val state = runCatching { readProductState(context) }.getOrNull()
                if (state != null) lastState = state
                lastProductStateSampleAt = now
            }
            SystemClock.sleep(if (permission == PermissionKind.NOTIFICATIONS) NOTIFICATION_PERMISSION_POLL_INTERVAL_MS else MANUAL_POLL_INTERVAL_MS)
        }

        val observationEndedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val permissionStillEnabled = permission.isEnabled(context, automation)
        val pids = productPids(automation)
        val fixture = fixtureRequest(apiBaseUrl, "GET", "/__ticket04/state")
        val stillInFlight = fixture.getJSONArray("inFlightRequests").objects().any { it.optLong("sequence") == heldSequence && it.optString("caseId") == caseId }
        val serverHeld = fixture.getJSONArray("serverHeldResponses").objects().singleOrNull { it.optLong("sequence") == heldSequence }
        val boundary = JSONObject()
            .put("runId", runId)
            .put("caseId", caseId)
            .put("permission", permission.name.lowercase())
            .put("permissionOffObserved", false)
            .put("actualPermissionEnabledAtObservationEnd", permissionStillEnabled)
            .put("permissionObservationEndedElapsedRealtimeMs", observationEndedElapsedRealtimeMs)
            .put("requestAgeAtObservationEndMs", observationEndedElapsedRealtimeMs - dispatchElapsedRealtimeMs)
            .put("productPid", pids.firstOrNull() ?: "")
            .put("serviceLeaseAttached", lastState?.optBoolean("serviceLeaseAttached") ?: false)
            .put("requestStillInFlightAtObservationEnd", stillInFlight)
            .put("clientSocketOpenAtObservationEnd", serverHeld?.optBoolean("clientConnected") ?: false)
            .put(
                "classification",
                if (permission == PermissionKind.NOTIFICATIONS) {
                    "notification-permission-off-not-observed-within-bounded-human-wait"
                } else {
                    "accessibility-permission-off-not-captured-within-pre-timeout-evidence-window"
                },
            )
        emitProgress(runId, "PERMISSION_WAIT_EXPIRED", caseId, boundary.toString())
        throw AssertionError("Ticket 04 $caseId did not observe the manual permission change within its bounded evidence window; no response was released. Evidence=$boundary")
    }

    private data class AccessibilityBoundaryProbe(
        val sample: Ticket04BoundaryEvidence.AccessibilityBoundarySample,
        val evidence: JSONObject,
    )

    private fun waitForAccessibilityRevocation(
        context: Context,
        automation: UiAutomation,
        dispatchElapsedRealtimeMs: Long,
        apiBaseUrl: String,
        runId: String,
        caseId: String,
        heldSequence: Long,
        expectedProductPid: String,
    ): JSONObject {
        val evidenceDeadlineNs = (dispatchElapsedRealtimeMs + MAX_BOUNDARY_REQUEST_AGE_MS) * 1_000_000L
        var lastLiveProbe: AccessibilityBoundaryProbe? = null
        var lastOffProbe: AccessibilityBoundaryProbe? = null
        var firstOffDetectedElapsedRealtimeNs: Long? = null

        while (true) {
            val probe = captureAccessibilityBoundaryProbe(
                context = context,
                automation = automation,
                apiBaseUrl = apiBaseUrl,
                caseId = caseId,
                heldSequence = heldSequence,
                expectedProductPid = expectedProductPid,
            )
            val sample = probe.sample
            if (sample.permissionEnabledBefore && sample.permissionEnabledAfter && sample.serviceLeaseAttached &&
                sample.requestInFlight && sample.serverResponseHeld && sample.clientConnected &&
                sample.productPid == expectedProductPid) {
                lastLiveProbe = probe
            }

            if (!sample.permissionEnabledBefore || !sample.permissionEnabledAfter) {
                if (firstOffDetectedElapsedRealtimeNs == null) {
                    firstOffDetectedElapsedRealtimeNs = sample.deviceStartElapsedRealtimeNs
                }
                lastOffProbe = probe
                val hasStableOff = !sample.permissionEnabledBefore && !sample.permissionEnabledAfter &&
                    !sample.serviceLeaseAttached
                val requestClosed = !sample.requestInFlight && !sample.clientConnected &&
                    sample.clientDisconnectMonotonicNs != null
                val transitionObservationExpired = SystemClock.elapsedRealtimeNanos() - firstOffDetectedElapsedRealtimeNs >=
                    MAX_TRANSITION_OBSERVATION_MS * 1_000_000L
                if (hasStableOff && (requestClosed || transitionObservationExpired)) {
                    val decision = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
                        lastLive = lastLiveProbe?.sample,
                        firstOff = sample,
                        expectedPid = expectedProductPid,
                    )
                    val boundaryEvidence = JSONObject()
                        .put("classification", decision.classification)
                        .put("qualifies", decision.qualifies)
                        .putOpt("requestAgeAtLastLiveMs", decision.requestAgeAtLastLiveMs)
                        .putOpt("disconnectAgeMs", decision.disconnectAgeMs)
                        .putOpt("transitionIntervalMs", decision.transitionIntervalMs)
                        .put("maximumTransitionIntervalMs", MAX_TRANSITION_OBSERVATION_MS)
                        .put("maximumFixtureRequestRttMs", Ticket04BoundaryEvidence.MAX_CLOCK_PROBE_RTT_MS)
                        .put("readTimeoutMs", READ_TIMEOUT_MS)
                        .put("lastLiveSample", lastLiveProbe?.evidence ?: JSONObject.NULL)
                        .put("firstOffSample", probe.evidence)
                    val boundary = JSONObject()
                        .put("runId", runId)
                        .put("caseId", caseId)
                        .put("permission", PermissionKind.ACCESSIBILITY.name.lowercase())
                        .put("actualPermissionEnabled", false)
                        .put("permissionOffDetectedElapsedRealtimeNs", firstOffDetectedElapsedRealtimeNs)
                        .put("boundaryElapsedRealtimeMs", sample.deviceEndElapsedRealtimeNs / 1_000_000L)
                        .put("productPid", sample.productPid)
                        .put("serviceLeaseAttached", sample.serviceLeaseAttached)
                        .put("requestStillInFlight", sample.requestInFlight)
                        .put("clientSocketOpenAtBoundary", sample.clientConnected)
                        .put("requestAgeAtBoundaryMs", decision.requestAgeAtLastLiveMs ?: -1L)
                        .put("requestBoundaryClassification", decision.classification)
                        .put("accessibilityBoundaryEvidence", boundaryEvidence)
                    emitProgress(runId, "BOUNDARY_CAPTURED", caseId, boundary.toString())
                    return boundary
                }
            }

            if (firstOffDetectedElapsedRealtimeNs == null && SystemClock.elapsedRealtimeNanos() >= evidenceDeadlineNs) {
                val boundary = JSONObject()
                    .put("runId", runId)
                    .put("caseId", caseId)
                    .put("permission", PermissionKind.ACCESSIBILITY.name.lowercase())
                    .put("permissionOffObserved", false)
                    .put("actualPermissionEnabledAtObservationEnd", sample.permissionEnabledAfter)
                    .put("permissionObservationEndedElapsedRealtimeNs", sample.deviceEndElapsedRealtimeNs)
                    .put("productPid", sample.productPid)
                    .put("serviceLeaseAttached", sample.serviceLeaseAttached)
                    .put("requestStillInFlightAtObservationEnd", sample.requestInFlight)
                    .put("clientSocketOpenAtObservationEnd", sample.clientConnected)
                    .put("classification", "accessibility-permission-off-not-captured-within-pre-timeout-evidence-window")
                emitProgress(runId, "PERMISSION_WAIT_EXPIRED", caseId, boundary.toString())
                throw AssertionError("Ticket 04 $caseId did not observe AccessibilityService OFF within the strict 13.5-second evidence window; no response was released. Evidence=$boundary")
            }

            if (firstOffDetectedElapsedRealtimeNs != null &&
                SystemClock.elapsedRealtimeNanos() - firstOffDetectedElapsedRealtimeNs >= MAX_TRANSITION_OBSERVATION_MS * 1_000_000L) {
                val finalProbe = lastOffProbe ?: probe
                val decision = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
                    lastLive = lastLiveProbe?.sample,
                    firstOff = finalProbe.sample,
                    expectedPid = expectedProductPid,
                )
                val boundaryEvidence = JSONObject()
                    .put("classification", decision.classification)
                    .put("qualifies", decision.qualifies)
                    .putOpt("requestAgeAtLastLiveMs", decision.requestAgeAtLastLiveMs)
                    .putOpt("disconnectAgeMs", decision.disconnectAgeMs)
                    .putOpt("transitionIntervalMs", decision.transitionIntervalMs)
                    .put("maximumTransitionIntervalMs", MAX_TRANSITION_OBSERVATION_MS)
                    .put("maximumFixtureRequestRttMs", Ticket04BoundaryEvidence.MAX_CLOCK_PROBE_RTT_MS)
                    .put("readTimeoutMs", READ_TIMEOUT_MS)
                    .put("lastLiveSample", lastLiveProbe?.evidence ?: JSONObject.NULL)
                    .put("firstOffSample", finalProbe.evidence)
                val boundary = JSONObject()
                    .put("runId", runId)
                    .put("caseId", caseId)
                    .put("permission", PermissionKind.ACCESSIBILITY.name.lowercase())
                    .put("actualPermissionEnabled", false)
                    .put("permissionOffDetectedElapsedRealtimeNs", firstOffDetectedElapsedRealtimeNs)
                    .put("boundaryElapsedRealtimeMs", finalProbe.sample.deviceEndElapsedRealtimeNs / 1_000_000L)
                    .put("productPid", finalProbe.sample.productPid)
                    .put("serviceLeaseAttached", finalProbe.sample.serviceLeaseAttached)
                    .put("requestStillInFlight", finalProbe.sample.requestInFlight)
                    .put("clientSocketOpenAtBoundary", finalProbe.sample.clientConnected)
                    .put("requestAgeAtBoundaryMs", decision.requestAgeAtLastLiveMs ?: -1L)
                    .put("requestBoundaryClassification", decision.classification)
                    .put("accessibilityBoundaryEvidence", boundaryEvidence)
                emitProgress(runId, "BOUNDARY_CAPTURED", caseId, boundary.toString())
                return boundary
            }
            SystemClock.sleep(MANUAL_POLL_INTERVAL_MS)
        }
    }

    private fun captureAccessibilityBoundaryProbe(
        context: Context,
        automation: UiAutomation,
        apiBaseUrl: String,
        caseId: String,
        heldSequence: Long,
        expectedProductPid: String,
    ): AccessibilityBoundaryProbe {
        val deviceSampleStartNs = SystemClock.elapsedRealtimeNanos()
        val permissionBefore = PermissionKind.ACCESSIBILITY.isEnabled(context, automation)
        val stateBefore = runCatching { readProductState(context) }.getOrNull()
        val fixtureRequestStartNs = SystemClock.elapsedRealtimeNanos()
        val fixture = fixtureRequest(apiBaseUrl, "GET", "/__ticket04/state")
        val fixtureRequestEndNs = SystemClock.elapsedRealtimeNanos()
        val permissionAfter = PermissionKind.ACCESSIBILITY.isEnabled(context, automation)
        val stateAfter = runCatching { readProductState(context) }.getOrNull()
        val pids = productPids(automation)
        val deviceSampleEndNs = SystemClock.elapsedRealtimeNanos()
        val inFlight = fixture.getJSONArray("inFlightRequests").objects().singleOrNull {
            it.optLong("sequence") == heldSequence && it.optString("caseId") == caseId
        }
        val held = fixture.getJSONArray("serverHeldResponses").objects().singleOrNull {
            it.optLong("sequence") == heldSequence && it.optString("caseId") == caseId
        }
        val receivedNs = held?.optLong("serverReceivedMonotonicNs", -1L) ?: -1L
        val disconnectNsValue = held?.opt("clientDisconnectMonotonicNs")
        val disconnectNs = if (disconnectNsValue == null || disconnectNsValue === JSONObject.NULL) {
            null
        } else {
            (disconnectNsValue as Number).toLong()
        }
        val serverSnapshotNs = fixture.optLong("serverMonotonicNs", -1L)
        val productPid = pids.singleOrNull().orEmpty()
        val leaseAttached = stateAfter?.optBoolean("serviceLeaseAttached") ?: false
        val sample = Ticket04BoundaryEvidence.AccessibilityBoundarySample(
            caseId = caseId,
            requestSequence = heldSequence,
            deviceStartElapsedRealtimeNs = deviceSampleStartNs,
            deviceEndElapsedRealtimeNs = deviceSampleEndNs,
            permissionEnabledBefore = permissionBefore,
            permissionEnabledAfter = permissionAfter,
            serviceLeaseAttached = leaseAttached,
            productPid = productPid,
            requestInFlight = inFlight != null,
            serverResponseHeld = held != null,
            clientConnected = held?.optBoolean("clientConnected") ?: false,
            fixtureRequestRttNs = fixtureRequestEndNs - fixtureRequestStartNs,
            serverSnapshotMonotonicNs = serverSnapshotNs,
            serverReceivedMonotonicNs = receivedNs,
            clientDisconnectMonotonicNs = disconnectNs,
            clientDisconnectSignal = held?.optString("clientDisconnectSignal")?.takeIf(String::isNotBlank),
        )
        val evidence = JSONObject()
            .put("caseId", caseId)
            .put("requestSequence", heldSequence)
            .put("deviceStartElapsedRealtimeNs", deviceSampleStartNs)
            .put("deviceEndElapsedRealtimeNs", deviceSampleEndNs)
            .put("fixtureRequestStartElapsedRealtimeNs", fixtureRequestStartNs)
            .put("fixtureRequestEndElapsedRealtimeNs", fixtureRequestEndNs)
            .put("fixtureRequestRttNs", fixtureRequestEndNs - fixtureRequestStartNs)
            .put("permissionEnabledBefore", permissionBefore)
            .put("permissionEnabledAfter", permissionAfter)
            .put("serviceLeaseAttachedBefore", stateBefore?.optBoolean("serviceLeaseAttached") ?: false)
            .put("serviceLeaseAttached", leaseAttached)
            .put("productPid", productPid)
            .put("expectedProductPid", expectedProductPid)
            .put("requestInFlight", inFlight != null)
            .put("serverResponseHeld", held != null)
            .put("clientConnected", held?.optBoolean("clientConnected") ?: false)
            .put("serverSnapshotMonotonicNs", serverSnapshotNs)
            .put("serverReceivedMonotonicNs", receivedNs)
            .put("clientDisconnectMonotonicNs", disconnectNs ?: JSONObject.NULL)
            .put("clientDisconnectSignal", held?.optString("clientDisconnectSignal") ?: "")
        return AccessibilityBoundaryProbe(sample, evidence)
    }

    private fun waitForManualRestoration(context: Context, automation: UiAutomation, permission: PermissionKind) {
        waitForCondition(USER_ACTION_TIMEOUT_MS, "the user's manual ${permission.name.lowercase()} restoration") {
            permission.isEnabled(context, automation)
        }
    }

    private fun openPermissionSettings(automation: UiAutomation, context: Context, permission: PermissionKind) {
        when (permission) {
            PermissionKind.NOTIFICATIONS -> {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, PRODUCT_PACKAGE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                waitForNode(automation, SETTINGS_PAGE_TIMEOUT_MS) { it.text?.toString()?.contains("토도 학습시간") == true || it.text?.toString()?.contains("TodoSchool") == true }
            }
            PermissionKind.ACCESSIBILITY -> {
                Ticket04AccessibilitySettingsNavigator.openServiceSettingsPage(
                    automation = automation,
                    context = context,
                    productPackage = PRODUCT_PACKAGE,
                    serviceClass = PRODUCT_SERVICE_CLASS,
                )
            }
        }
    }

    private fun openProductMainActivity(context: Context) {
        context.startActivity(productMainIntent(context))
    }

    private fun productMainIntent(context: Context, clearExistingTask: Boolean = false): Intent = Intent().apply {
        component = ComponentName(PRODUCT_PACKAGE, PRODUCT_ACTIVITY_CLASS)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (clearExistingTask) {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } else {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
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

    private fun writeDefaultTargetsThroughTheApp(automation: UiAutomation) {
        clickDescription(automation, "$CHILD_NAME 목표 설정")
        val fields = findAllNodes(automation, SETUP_TIMEOUT_MS) { it.className == EDIT_TEXT }
        assertEquals("The target dialog must include each subscribed subject", 3, fields.size)
        TARGET_COUNTS.values.forEachIndexed { index, target -> setField(fields[index], target.toString()) }
        clickText(automation, "저장")
    }

    private fun launchActualSubjectApp(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw AssertionError("Actual subject application is not installed: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun productPids(automation: UiAutomation): List<String> {
        val descriptor = automation.executeShellCommand("pidof $PRODUCT_PACKAGE")
        val text = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private fun readProductState(context: Context): JSONObject {
        val result = context.contentResolver.call(PROVIDER_AUTHORITY, PROVIDER_METHOD_SNAPSHOT, null, Bundle())
            ?: throw AssertionError("The debug read-only diagnostics provider returned no snapshot")
        val serialized = result.getString(PROVIDER_EXTRA_SNAPSHOT_JSON)
            ?: throw AssertionError("The debug diagnostics provider omitted its snapshot")
        return JSONObject(serialized)
    }

    private fun assertReadyProduct(state: JSONObject) {
        assertProductPermissionsReady(state)
        assertEquals(setOf(CHILD_NAME), state.getJSONArray("registeredChildren").strings().toSet())
    }

    private fun assertProductPermissionsReady(state: JSONObject) {
        assertEquals(PRODUCT_PACKAGE, state.getString("applicationId"))
        assertTrue("The real AccessibilityService lease is not attached: $state", state.getBoolean("serviceLeaseAttached"))
        assertTrue("The actual AccessibilityService setting is disabled: $state", state.getBoolean("accessibilityEnabled"))
        assertTrue("Notifications are not enabled in the product snapshot: $state", state.getBoolean("notificationsEnabled"))
        assertTrue("POST_NOTIFICATIONS was revoked: $state", state.getBoolean("postNotificationsPermissionGranted"))
    }

    private fun requireSilentChannel(state: JSONObject) {
        val channel = state.getJSONObject("channel")
        assertTrue("The status channel does not exist: $channel", channel.getBoolean("exists"))
        assertTrue("The status channel must remain LOW/MIN importance: $channel", channel.getInt("importance") <= 2)
        assertFalse("The status channel must not vibrate: $channel", channel.getBoolean("vibrationEnabled"))
    }

    private fun onlyChildNotification(state: JSONObject): JSONObject {
        val active = state.getJSONArray("activeNotifications").objects()
        assertEquals("Expected one persistent status notification for the registered test child", 1, active.size)
        assertTrue(active.single().optString("title").contains(CHILD_NAME))
        return active.single()
    }

    private fun assertExpectedSubject(state: JSONObject, name: String, status: String, count: Int, target: Int) {
        val value = subject(state, name)
        assertEquals(status, value.getString("status"))
        assertEquals(count, value.getInt("activityCount"))
        assertEquals(target, value.getInt("targetCount"))
    }

    private fun subjectVectors(state: JSONObject): List<String> = SUBJECT_NAMES.values.sorted().map { name ->
        val value = subject(state, name)
        "$name|${value.optString("status")}|${value.optInt("activityCount", -1)}|${value.optInt("targetCount", -1)}"
    }

    private fun subject(state: JSONObject, subjectName: String): JSONObject =
        state.getJSONObject("children").getJSONObject(CHILD_NAME).getJSONObject("subjects").getJSONObject(subjectName)

    private fun waitForProductState(context: Context, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitUntil<JSONObject>(timeoutMs, "the requested live product state") { runCatching { readProductState(context) }.getOrNull()?.takeIf(predicate) }

    private fun awaitFixtureState(apiBaseUrl: String, runId: String, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitUntil<JSONObject>(timeoutMs, "the matching Ticket 04 fixture state") {
            runCatching { fixtureRequest(apiBaseUrl, "GET", "/__ticket04/state") }.getOrNull()?.also {
                assertEquals(runId, it.getString("runId"))
            }?.takeIf(predicate)
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

    private fun acquireObserverScreenWakeLock(instrumentation: Instrumentation, state: JSONObject) {
        val observerContext = instrumentation.context
        val productContext = instrumentation.targetContext
        val powerManager = observerContext.getSystemService(PowerManager::class.java)
            ?: throw AssertionError("The observer could not access PowerManager")
        assertTrue("The device must already be awake and unlocked", powerManager.isInteractive)
        val beforeEvents = state.getJSONArray("foregroundEvents").objects()
        val wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "$OBSERVER_PACKAGE:ticket04-screen")
            .apply { setReferenceCounted(false) }
        screenWakeLock = wakeLock
        wakeLock.acquire(SCREEN_WAKE_LOCK_TIMEOUT_MS)
        SystemClock.sleep(WAKE_LOCK_SETTLE_MS)
        assertTrue("The observer wake lock did not preserve the already-awake display", powerManager.isInteractive)
        val after = readProductState(productContext)
        assertTrue("The product service disconnected during observer wake-lock acquisition", after.getBoolean("serviceLeaseAttached"))
        val afterEvents = after.getJSONArray("foregroundEvents").objects()
        assertEquals("Observer wake-lock acquisition changed the product foreground package", beforeEvents.lastOrNull()?.optString("packageName"), afterEvents.lastOrNull()?.optString("packageName"))
    }

    private fun waitForPauseUi(automation: UiAutomation, timeoutMs: Long): List<String> =
        waitUntil<List<String>>(timeoutMs, "the permission-paused monitoring status in the app UI") {
            screenTexts(automation).takeIf { text -> text.any { it.contains("감시 일시 중지") } }
        }

    private fun screenTexts(automation: UiAutomation): List<String> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val result = ArrayList<String>()
        collectText(root, result)
        return result
    }

    private fun collectText(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
        node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
        for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
            collectText(child, result)
            child.recycle()
        }
    }

    private fun clickDescription(automation: UiAutomation, description: String) {
        val node = waitForNode(automation, SETUP_TIMEOUT_MS) { it.contentDescription?.toString() == description && it.isClickable }
        assertTrue("Could not tap '$description'", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun clickText(automation: UiAutomation, text: String) {
        val node = waitForNode(automation, SETUP_TIMEOUT_MS) { it.text?.toString() == text && it.isClickable }
        assertTrue("Could not tap '$text'", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun setField(field: AccessibilityNodeInfo, value: String) {
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue("Could not enter a target value", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle))
    }

    private fun waitForNode(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        findNodeOrNull(automation, timeoutMs, predicate) ?: throw AssertionError("Timed out waiting for the requested Settings or app screen")

    private fun findNodeOrNull(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            automation.rootInActiveWindow?.let { root ->
                findNode(root, predicate)?.let { return it }
            }
            SystemClock.sleep(MANUAL_POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findAllNodes(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            val root = automation.rootInActiveWindow
            if (root != null) {
                val result = ArrayList<AccessibilityNodeInfo>()
                collectNodes(root, predicate, result)
                if (result.isNotEmpty()) return result
            }
            SystemClock.sleep(MANUAL_POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        return emptyList()
    }

    private fun collectNodes(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean, result: MutableList<AccessibilityNodeInfo>) {
        if (predicate(node)) result.add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
            collectNodes(child, predicate, result)
            if (child !in result) child.recycle()
        }
    }

    private fun waitUntil(timeoutMs: Long, description: String, block: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (block()) return true
            SystemClock.sleep(MANUAL_POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun waitForCondition(timeoutMs: Long, description: String, block: () -> Boolean) {
        waitUntil(timeoutMs, description, block)
    }

    private fun <T : Any> waitUntil(timeoutMs: Long, description: String, block: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            block()?.let { return it }
            SystemClock.sleep(MANUAL_POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun eventsAfter(events: JSONArray, sequence: Long): List<JSONObject> = events.objects().filter { it.optLong("sequence") > sequence }

    private fun lastSequence(events: JSONArray): Long = events.objects().maxOfOrNull { it.optLong("sequence") } ?: 0L

    private fun assertFakeRequests(requests: List<JSONObject>, expectedSeoulDate: Int) {
        val allowedPaths = setOf(SIGN_IN_PATH, HANGEUL_CATALOG_PATH, MATH_CATALOG_PATH, ENGLISH_CATALOG_PATH) + REPORT_PATHS
        requests.forEach { request ->
            assertTrue("Fixture recorded an unexpected path: $request", request.getString("path") in allowedPaths)
            assertEquals("POST", request.getString("method"))
            when (request.getString("path")) {
                SIGN_IN_PATH -> {
                    assertEquals(TEST_EMAIL, request.getJSONObject("body").getString("email"))
                    assertEquals(TEST_PASSWORD, request.getJSONObject("body").getString("password"))
                }
                HANGEUL_CATALOG_PATH, MATH_CATALOG_PATH, ENGLISH_CATALOG_PATH -> {
                    assertEquals("Bearer $AUTH_TOKEN", request.getJSONObject("headers").getString("authorization"))
                    assertEquals(ACCOUNT_ID, request.getJSONObject("body").getJSONObject("user").getString("accountId"))
                }
                else -> {
                    val body = request.getJSONObject("body")
                    assertEquals("Baseline and completion fixture requests should succeed", 200, request.getInt("responseStatus"))
                    assertTrue("The server request must use today's Seoul date", body.optInt("yyyymmdd") == expectedSeoulDate)
                    assertEquals("ko", body.optString("languageCode"))
                    assertTrue("The report must use a fake fixture child", body.optString("userId").startsWith("ticket01-"))
                    assertEquals("Bearer $AUTH_TOKEN", request.getJSONObject("headers").getString("authorization"))
                }
            }
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun seoulDateNumber(): Int = LocalDate.now(ZoneId.of("Asia/Seoul"))
        .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        .toInt()

    private fun emitProgress(runId: String, event: String, caseId: String, details: String) {
        Log.i(PROGRESS_LOG_TAG, "TICKET04_PROGRESS=" + JSONObject()
            .put("runId", runId)
            .put("event", event)
            .put("caseId", caseId)
            .put("details", details)
            .toString())
    }

    private fun emitResult(value: JSONObject) {
        val runId = value.getString("runId")
        val encoded = android.util.Base64.encodeToString(
            value.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val chunks = encoded.chunked(RESULT_CHUNK_SIZE)
        chunks.forEachIndexed { index, payload ->
            Log.i(RESULT_LOG_TAG, "TICKET04_RESULT_CHUNK:$runId:${index + 1}/${chunks.size}:$payload")
        }
    }

    private enum class PermissionKind(
        val displayName: String,
        val manualInstruction: String,
        val restoreInstruction: String,
    ) {
        NOTIFICATIONS(
            "알림",
            "열린 설정에서 제품 앱 알림을 직접 끄세요. OFF가 확인되면 '학습 감시 E2E 검사' 확인 화면이 자동으로 열립니다. ADB나 테스트가 권한을 바꾸지 않습니다.",
            "Android 설정에서 제품 앱 알림을 직접 다시 켜세요.",
        ),
        ACCESSIBILITY(
            "접근성",
            "열린 접근성 설정에서 제품 서비스를 직접 끄고 Android 경고도 승인하세요. OFF가 확인되면 '학습 감시 E2E 검사' 확인 화면이 자동으로 열립니다.",
            "Android 설정에서 제품 접근성 서비스를 직접 다시 켜고 확인 경고도 승인하세요.",
        );

        fun isEnabled(context: Context, automation: UiAutomation): Boolean = when (this) {
            NOTIFICATIONS -> {
                val descriptor = automation.executeShellCommand("cmd appops get $PRODUCT_PACKAGE POST_NOTIFICATION")
                val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val runtimePermissionGranted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                    context.packageManager.checkPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS,
                        PRODUCT_PACKAGE,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                Ticket04NotificationAppOps.isEnabled(
                    Ticket04NotificationAppOps.effectiveMode(output),
                    runtimePermissionGranted,
                )
            }
            ACCESSIBILITY -> {
                val component = ComponentName(PRODUCT_PACKAGE, PRODUCT_SERVICE_CLASS).flattenToString()
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                        .orEmpty().split(':').any { it.equals(component, ignoreCase = true) }
            }
        }
    }

    private fun releaseScreenWakeLock() {
        screenWakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        screenWakeLock = null
    }

    @org.junit.After
    fun cleanUpObserverWakeLock() {
        releaseScreenWakeLock()
    }

    private companion object {
        const val OBSERVER_PACKAGE = "com.example.todoschooltime.e2eobserver"
        const val PRODUCT_PACKAGE = "com.example.todoschooltime.debug"
        const val PRODUCT_ACTIVITY_CLASS = "com.example.todoschooltime.MainActivity"
        const val PRODUCT_SERVICE_CLASS = "com.example.todoschooltime.MonitorAccessibilityService"
        const val PROVIDER_AUTHORITY = "com.example.todoschooltime.debug.monitor-diagnostics"
        const val PROVIDER_METHOD_SNAPSHOT = "snapshot"
        const val PROVIDER_EXTRA_SNAPSHOT_JSON = "snapshotJson"
        const val LOOPBACK_PREFIX = "http://127.0.0.1:"
        const val ARG_API_BASE_URL = "apiBaseUrl"
        const val ARG_RUN_ID = "runId"
        const val ARG_CASE_MODE = "caseMode"
        const val CASE_MODE_BOTH = "both"
        const val CASE_MODE_NOTIFICATION = "notification"
        const val CASE_MODE_ACCESSIBILITY = "accessibility"
        const val RESULT_LOG_TAG = "Ticket04Result"
        const val RESULT_CHUNK_SIZE = 3000
        const val PROGRESS_LOG_TAG = "Ticket04Progress"
        const val CHILD_NAME = "테스트 아이"
        const val TEST_EMAIL = "ticket01@example.test"
        const val TEST_PASSWORD = "not-a-real-password"
        const val AUTH_TOKEN = "ticket01-fake-token"
        const val ACCOUNT_ID = "ticket01-fake-account"
        const val HANGEUL_SUBJECT = "한글"
        const val MATH_SUBJECT = "수학"
        const val ENGLISH_SUBJECT = "영어"
        const val HANGEUL_APP_PACKAGE = "com.enuma.todohangeul"
        const val ENGLISH_APP_PACKAGE = "com.enuma.todoenglish"
        const val HANGEUL_REPORT_PATH = "/v3/todoschool/report/daily/hangeul"
        const val ENGLISH_REPORT_PATH = "/v3/todoschool/report/daily/english"
        const val HANGEUL_CATALOG_PATH = "/v3/todohangeul/user"
        const val MATH_CATALOG_PATH = "/v3/math/user"
        const val ENGLISH_CATALOG_PATH = "/v3/english/user"
        const val SIGN_IN_PATH = "/v3/account/signin"
        const val EDIT_TEXT = "android.widget.EditText"
        const val SETUP_TIMEOUT_MS = 45_000L
        const val LOGIN_FORM_TIMEOUT_MS = 8_000L
        const val SETTINGS_PAGE_TIMEOUT_MS = 5_000L
        const val PAUSE_UI_TIMEOUT_MS = 1_500L
        const val REGULAR_POLL_TIMEOUT_MS = 180_000L
        const val RECOVERY_TIMEOUT_MS = 120_000L
        const val RESTORE_TIMEOUT_MS = 180_000L
        const val RELEASE_TIMEOUT_MS = 2_000L
        const val SERVER_TIMEOUT_MS = 5_000
        const val CALLBACK_SETTLE_MS = 500L
        const val MANUAL_POLL_INTERVAL_MS = 80L
        const val NOTIFICATION_PERMISSION_POLL_INTERVAL_MS = 250L
        const val PRODUCT_STATE_SAMPLE_INTERVAL_MS = 1_000L
        val INITIAL_COUNTS = mapOf("hangeul" to 2, "math" to 5, "english" to 7)
        val ACCESSIBILITY_INITIAL_COUNTS = mapOf("hangeul" to 6, "math" to 5, "english" to 7)
        const val USER_ACTION_TIMEOUT_MS = 30 * 60 * 1000L
        const val MAX_NOTIFICATION_PERMISSION_WAIT_MS = 5 * 60 * 1000L
        const val READ_TIMEOUT_MS = 15_000L
        const val DISCONNECT_BOUNDARY_TOLERANCE_MS = 500L
        const val MAX_BOUNDARY_REQUEST_AGE_MS = Ticket04BoundaryEvidence.PRE_TIMEOUT_EVIDENCE_WINDOW_MS
        const val MAX_TRANSITION_OBSERVATION_MS = Ticket04BoundaryEvidence.MAX_TRANSITION_INTERVAL_MS
        const val MAX_RELEASE_REQUEST_AGE_MS = 14_500L
        const val SCREEN_WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L
        const val WAKE_LOCK_SETTLE_MS = 300L
        val REPORT_PATHS = setOf(
            HANGEUL_REPORT_PATH,
            "/v3/todoschool/report/daily/math",
            ENGLISH_REPORT_PATH,
        )
        val SUBJECT_NAMES = mapOf(
            "hangeul" to HANGEUL_SUBJECT,
            "math" to MATH_SUBJECT,
            "english" to ENGLISH_SUBJECT,
        )
        val TARGET_COUNTS = mapOf(
            HANGEUL_SUBJECT to 6,
            MATH_SUBJECT to 5,
            ENGLISH_SUBJECT to 8,
        )
        val SUBJECT_USER_IDS = mapOf(
            HANGEUL_SUBJECT to "ticket01-hangeul",
            MATH_SUBJECT to "ticket01-math",
            ENGLISH_SUBJECT to "ticket01-english",
        )
    }
}
