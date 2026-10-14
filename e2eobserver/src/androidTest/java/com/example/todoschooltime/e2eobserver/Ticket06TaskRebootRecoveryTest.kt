package com.example.todoschooltime.e2eobserver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observes actual task removal and boot restoration from a separate, non-product process. */
@RunWith(AndroidJUnit4::class)
class Ticket06TaskRebootRecoveryTest {
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
        assertTrue("Ticket 06 accepts only a loopback fixture", apiBaseUrl.startsWith(LOOPBACK_PREFIX))
        assertTrue("Unknown Ticket 06 phase: $phase", phase in setOf(PHASE_TASK_REMOVAL, PHASE_REBOOT_PREPARATION, PHASE_POST_REBOOT))

        val context = instrumentation.targetContext
        val powerManager = context.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Ticket06:TaskRebootRecovery")
        wakeLock.acquire(MAX_WAKE_LOCK_MS)
        val result = try {
            val initial = readProductState(context)
            requireActualRuntime(initial)
            requireSilentChannel(initial)
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val afterAutomation = readProductState(context)
            requireActualRuntime(afterAutomation)
            assertTrue("UiAutomation changed the monitor lease", afterAutomation.getBoolean("serviceLeaseAttached"))
            fixtureRequest(apiBaseUrl, "GET", "/__ticket06/health").also {
                assertEquals(runId, it.getString("runId"))
                assertEquals("ticket06", it.getString("scenario"))
            }

            when (phase) {
                PHASE_TASK_REMOVAL -> runTaskRemovalCase(instrumentation, automation, apiBaseUrl, runId, afterAutomation)
                PHASE_REBOOT_PREPARATION -> runTaskRemovalCase(instrumentation, automation, apiBaseUrl, runId, afterAutomation, rebootPreparation = true)
                else -> {
                    val sequence = arguments.getString(ARG_POST_REBOOT_SEQUENCE)?.toLongOrNull() ?: -1L
                    assertTrue("Missing pre-reboot fixture request cursor", sequence >= 0L)
                    runPostRebootCase(instrumentation, automation, apiBaseUrl, runId, sequence, afterAutomation)
                }
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }

        emitStructuredResult(result, runId)
    }

    private fun runTaskRemovalCase(
        instrumentation: Instrumentation,
        automation: UiAutomation,
        apiBaseUrl: String,
        runId: String,
        initial: JSONObject,
        rebootPreparation: Boolean = false,
    ): JSONObject {
        val context = instrumentation.targetContext
        val registrations = initial.getJSONArray("registeredChildren").strings().toSet()
        assertTrue(
            "Only an empty registration or this scenario's child may already exist: $initial",
            registrations.isEmpty() || registrations == setOf(CHILD_NAME),
        )
        openProductApp(instrumentation)
        enterFakeCredentialsIfNeeded(automation)
        waitForProductScreen(automation)

        val accountState = waitForProductState(context, TIMEOUT_MS) { it.optString("accountId") == ACCOUNT_ID }
        assertTrue("The real service disconnected while opening the debug UI", accountState.getBoolean("serviceLeaseAttached"))
        if (accountState.getJSONArray("registeredChildren").strings().contains(CHILD_NAME)) {
            clickDescription(automation, "$CHILD_NAME 감시 해제")
            val unregistered = waitForProductState(context, TIMEOUT_MS) {
                it.getJSONArray("registeredChildren").length() == 0 && it.getJSONArray("activeNotifications").length() == 0
            }
            assertTrue("The real monitor lease ended while removing the prior test child", unregistered.getBoolean("serviceLeaseAttached"))
        }

        writeTargetsThroughTheApp(automation)
        saveOneMinuteIntervalExplicitly(automation)
        val beforeRegistration = readProductState(context)
        requireSilentChannel(beforeRegistration)
        assertEquals("The test child must be unregistered before each setup", 0, beforeRegistration.getJSONArray("registeredChildren").length())
        assertEquals("An unregistered test child must not leave a notification", 0, beforeRegistration.getJSONArray("activeNotifications").length())

        fixtureRequest(apiBaseUrl, "POST", "/__ticket06/reset-requests", JSONObject())
        fixtureRequest(apiBaseUrl, "POST", "/__ticket06/set-report-counts", JSONObject().put("reports", baselineCounts()))
        val baselineNotificationCursor = lastSequence(beforeRegistration.getJSONArray("notificationEvents"))
        val baselineDispatchCursor = lastSequence(beforeRegistration.getJSONArray("httpRequestDispatchEvents"))
        clickDescription(automation, "$CHILD_NAME 감시 등록")
        val baselineState = waitForProductState(context, BASELINE_TIMEOUT_MS) { hasBaselineCounts(it) }
        requireActualRuntime(baselineState)
        val baselineNotificationEvents = eventsAfter(baselineState.getJSONArray("notificationEvents"), baselineNotificationCursor)
        assertFalse("The completed baseline requested a completion notification: $baselineNotificationEvents", baselineNotificationEvents.any { it.optBoolean("completionRequested") })
        assertBaselineVisibleInUi(automation)

        val baselineFixture = awaitFixtureState(apiBaseUrl, runId, TIMEOUT_MS) {
            it.getJSONArray("requests").length() >= EXPECTED_PATHS.size
        }
        val baselineRequests = baselineFixture.getJSONArray("requests").objects()
        assertEquals("Initial registration did not fetch one whole baseline", EXPECTED_PATHS, baselineRequests.take(EXPECTED_PATHS.size).map { it.getString("path") })
        assertSuccessfulFakeFixtureRequests(baselineRequests.take(EXPECTED_PATHS.size), baselineCounts())
        val baselineSignin = firstSigninDispatchAfter(baselineState, baselineDispatchCursor)
        assertTrue("The first baseline sign-in was not dispatched with the real service lease", baselineSignin.getBoolean("serviceLeaseAttached"))
        if (rebootPreparation) {
            Log.i(STAGE_LOG_TAG, "$runId:registered-before-reboot")
            awaitPreRebootPreferenceCapture(apiBaseUrl, runId)
            val targets = JSONObject().put("hangeul", TARGET_HANGEUL).put("math", TARGET_MATH).put("english", TARGET_ENGLISH)
            return JSONObject()
                .put("runId", runId)
                .put("scenario", "ticket06")
                .put("phase", PHASE_REBOOT_PREPARATION)
                .put("serviceLeaseAttached", baselineState.getBoolean("serviceLeaseAttached"))
                .put("registration", JSONArray(listOf(CHILD_NAME)))
                .put("targets", targets)
                .put("interval", JSONObject().put("minutes", 1).put("persistedExplicitly", true).put("source", "debug-app-ui-save-and-host-filtered-pre-reboot-check"))
                .put("baseline", JSONObject()
                    .put("complete", true)
                    .put("successfulSubjects", 3)
                    .put("completionAlertRequested", false)
                    .put("fixtureRequestStartSequence", baselineRequests.first().getLong("sequence"))
                    .put("fixtureRequestEndSequence", baselineRequests.last().getLong("sequence")))
                .put("productTodoApiCalled", false)
                .put("notificationOrAccessibilityPermissionChanged", false)
        }

        Log.i(STAGE_LOG_TAG, "$runId:registered-before-task-removal")
        awaitPreRemovalPreferenceCapture(apiBaseUrl, runId)

        val expectedAppLabel = context.packageManager.getApplicationInfo(PRODUCT_PACKAGE, 0).loadLabel(context.packageManager).toString()
        val automationInfo = automation.serviceInfo ?: AccessibilityServiceInfo()
        automationInfo.flags = automationInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.setServiceInfo(automationInfo)
        assertTrue("Recents action failed", automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS))
        SystemClock.sleep(900)
        Log.i(STAGE_LOG_TAG, "$runId:after-global-recents")
        val recentsCardLabel = observedRecentsCardLabel(automation, expectedAppLabel)
        val taskIdentity = taskIdentityForProduct(automation)
        val taskId = taskIdentity.id
        val expectedTaskComponent = "$PRODUCT_PACKAGE/com.example.todoschooltime.MainActivity"
        assertEquals("The target Recent task is not the isolated debug MainActivity", expectedTaskComponent, taskIdentity.component)
        Log.i(STAGE_LOG_TAG, "$runId:target-task:${taskIdentity.id}:${taskIdentity.component}")
        val otherTasksBeforeRemoval = recentTaskIdentities(automation).filterNot { it.id == taskId }
        val removeOutput = executeShellCommand(automation, "am task remove $taskId")
        val commandReportedFailure = removeOutput.lineSequence().any {
            it.contains("error", ignoreCase = true) || it.contains("exception", ignoreCase = true) || it.contains("unknown task", ignoreCase = true)
        }
        val taskPresentAfterCommand = taskIsPresent(automation, taskId)
        var removalMechanism = "adb shell am task remove"
        var recentsDismissal = JSONObject.NULL
        var recentsSwipePerformed = false
        if (taskPresentAfterCommand) {
            assertTrue(
                "The exact debug task remains after am task remove, and no exact label was exposed by a non-product Recents window; no other card will be swiped",
                recentsCardLabel != null,
            )
            recentsDismissal = dismissOnlyObservedProductTask(automation, recentsCardLabel!!, taskId)
            removalMechanism = "actual Recents dismiss via targeted swipe"
            recentsSwipePerformed = true
        }
        waitForCondition(TIMEOUT_MS, "the removed task disappearing from Android Recents") {
            !taskIsPresent(automation, taskId)
        }
        val otherTasksAfterRemoval = recentTaskIdentities(automation).filterNot { it.id == taskId }
        assertEquals("Removing the debug task changed another recent task", otherTasksBeforeRemoval, otherTasksAfterRemoval)
        val afterRemoval = waitForProductState(context, TIMEOUT_MS) {
            it.getJSONArray("registeredChildren").strings() == setOf(CHILD_NAME) && it.optBoolean("serviceLeaseAttached")
        }
        assertTrue("Removing the activity task detached the real service", afterRemoval.getBoolean("serviceLeaseAttached"))
        assertEquals(setOf(CHILD_NAME), afterRemoval.getJSONArray("registeredChildren").strings().toSet())

        val foregroundCursor = lastSequence(afterRemoval.getJSONArray("foregroundEvents"))
        launchActualSubjectApp(context, HANGEUL_APP_PACKAGE)
        waitForActualForegroundAndConnectedService(automation, context, foregroundCursor, HANGEUL_APP_PACKAGE)
        val cycleFixture = awaitFixtureState(apiBaseUrl, runId, CADENCE_TIMEOUT_MS) {
            val requests = it.getJSONArray("requests").objects()
            requests.size >= EXPECTED_PATHS.size * 2 && requests.drop(EXPECTED_PATHS.size).take(EXPECTED_PATHS.size).map { request -> request.getString("path") } == EXPECTED_PATHS
        }
        val cycleRequests = cycleFixture.getJSONArray("requests").objects().drop(EXPECTED_PATHS.size).take(EXPECTED_PATHS.size)
        assertSuccessfulFakeFixtureRequests(cycleRequests, baselineCounts())
        val afterCycle = readProductState(context)
        val cycleSignin = firstSigninDispatchForPackageAfter(afterCycle, HANGEUL_APP_PACKAGE, baselineSignin.getLong("elapsedRealtimeMs"))
        val baselineServerSignin = baselineRequests.first { it.getString("path") == SIGN_IN_PATH }
        val cycleServerSignin = cycleRequests.first { it.getString("path") == SIGN_IN_PATH }
        val clientDeltaMs = cycleSignin.getLong("elapsedRealtimeMs") - baselineSignin.getLong("elapsedRealtimeMs")
        val serverDeltaMs = (cycleServerSignin.getLong("serverReceivedMonotonicNs") - baselineServerSignin.getLong("serverReceivedMonotonicNs")) / 1_000_000L
        assertTrue("The recorded cycle interval was shorter than the explicitly saved minute: $clientDeltaMs", clientDeltaMs >= 60_000L)
        assertTrue("The recorded cycle interval exceeded its bounded wait: $clientDeltaMs", clientDeltaMs < 180_000L)

        val targets = JSONObject().put("hangeul", TARGET_HANGEUL).put("math", TARGET_MATH).put("english", TARGET_ENGLISH)
        return JSONObject()
            .put("runId", runId)
            .put("scenario", "ticket06")
            .put("phase", PHASE_TASK_REMOVAL)
            .put("taskRemoval", JSONObject()
                .put("recentsObserved", true)
                .put("recentsAppLabel", expectedAppLabel)
                .put("recentsViewLabelObserved", recentsCardLabel != null)
                .put("recentsLabelWindowPackage", recentsCardLabel?.windowPackage ?: JSONObject.NULL)
                .put("mechanism", removalMechanism)
                .put("taskId", taskId)
                .put("taskComponent", taskIdentity.component)
                .put("commandOutputSummary", JSONArray(removeOutput.lineSequence().map(String::trim).filter(String::isNotEmpty).take(4).toList()))
                .put("commandReportedFailure", commandReportedFailure)
                .put("taskPresentAfterCommand", taskPresentAfterCommand)
                .put("taskAbsentAfterRemoval", true)
                .put("forceStopUsed", false)
                .put("recentsSwipePerformed", recentsSwipePerformed)
                .put("recentsDismissal", recentsDismissal)
                .put("otherTasksUnchanged", true)
                .put("otherTaskIdsBeforeRemoval", JSONArray(otherTasksBeforeRemoval.map { it.id }))
                .put("otherTaskIdsAfterRemoval", JSONArray(otherTasksAfterRemoval.map { it.id })))
            .put("registration", JSONArray(listOf(CHILD_NAME)))
            .put("targets", targets)
            .put("interval", JSONObject().put("minutes", 1).put("persistedExplicitly", true).put("source", "debug-app-ui-save-and-host-filtered-post-boot-check"))
            .put("baseline", JSONObject()
                .put("complete", true)
                .put("successfulSubjects", 3)
                .put("completionAlertRequested", false)
                .put("fixtureRequestStartSequence", baselineRequests.first().getLong("sequence"))
                .put("fixtureRequestEndSequence", baselineRequests.last().getLong("sequence")))
            .put("taskRemovalState", JSONObject()
                .put("serviceLeaseAttached", afterRemoval.getBoolean("serviceLeaseAttached"))
                .put("registeredChildren", afterRemoval.getJSONArray("registeredChildren")))
            .put("cadence", JSONObject()
                .put("baselineSigninElapsedRealtimeMs", baselineSignin.getLong("elapsedRealtimeMs"))
                .put("nextCycleSigninElapsedRealtimeMs", cycleSignin.getLong("elapsedRealtimeMs"))
                .put("clientDeltaMs", clientDeltaMs)
                .put("baselineServerSigninMonotonicNs", baselineServerSignin.getLong("serverReceivedMonotonicNs"))
                .put("nextCycleServerSigninMonotonicNs", cycleServerSignin.getLong("serverReceivedMonotonicNs"))
                .put("serverDeltaMs", serverDeltaMs)
                .put("foregroundPackage", cycleSignin.getString("foregroundPackage"))
                .put("serviceLeaseAttachedAtDispatch", cycleSignin.getBoolean("serviceLeaseAttached")))
            .put("postRemovalRequestPaths", JSONArray(cycleRequests.map { it.getString("path") }))
            .put("productAlertSoundOrHeadsUpTested", false)
    }

    private data class ObservedRecentsLabel(
        val node: AccessibilityNodeInfo,
        val windowPackage: String,
        val windowBounds: Rect,
    )

    private fun observedRecentsCardLabel(automation: UiAutomation, expectedAppLabel: String): ObservedRecentsLabel? {
        val windows = try {
            automation.windows
        } catch (_: SecurityException) {
            return null
        }
        for (window in windows) {
            val root = window.root ?: continue
            val windowPackage = root.packageName?.toString().orEmpty()
            if (windowPackage.isBlank() || windowPackage == PRODUCT_PACKAGE) continue
            val node = findNode(root) {
                it.text?.toString() == expectedAppLabel || it.contentDescription?.toString() == expectedAppLabel
            } ?: continue
            val windowBounds = Rect().also(window::getBoundsInScreen)
            if (windowBounds.width() <= 0 || windowBounds.height() <= 0) continue
            return ObservedRecentsLabel(node, windowPackage, windowBounds)
        }
        return null
    }

    private fun dismissOnlyObservedProductTask(automation: UiAutomation, observedLabel: ObservedRecentsLabel, taskId: Int): JSONObject {
        assertTrue("The Recents label must come from a non-product system window", observedLabel.windowPackage != PRODUCT_PACKAGE)
        val cardLabelBounds = Rect().also(observedLabel.node::getBoundsInScreen)
        val screenBounds = observedLabel.windowBounds
        assertTrue("The observed product app label has no usable screen bounds: $cardLabelBounds", cardLabelBounds.width() > 0 && cardLabelBounds.height() > 0)
        assertTrue("The observed Recents window has no usable screen bounds: $screenBounds", screenBounds.width() > 0 && screenBounds.height() > 0)
        val x = cardLabelBounds.centerX().coerceIn(screenBounds.left + 1, screenBounds.right - 1)
        val startY = cardLabelBounds.centerY()
        val endY = (startY - screenBounds.height() / 2).coerceAtLeast(screenBounds.top + 1)
        assertTrue("The bounded swipe does not move upward through the observed product card", startY > endY)
        val gestureElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val gestureEpochMillis = System.currentTimeMillis()
        val swipeOutput = executeShellCommand(automation, "input swipe $x $startY $x $endY 300")
        waitForCondition(TIMEOUT_MS, "the observed product task leaving Recents after its targeted dismiss swipe") {
            !taskIsPresent(automation, taskId)
        }
        return JSONObject()
            .put("method", "input swipe inside the observed product app label bounds")
            .put("windowPackage", observedLabel.windowPackage)
            .put("gestureElapsedRealtimeMs", gestureElapsedRealtimeMs)
            .put("gestureEpochMillis", gestureEpochMillis)
            .put("startX", x)
            .put("startY", startY)
            .put("endX", x)
            .put("endY", endY)
            .put("screenBounds", JSONObject()
                .put("left", screenBounds.left)
                .put("top", screenBounds.top)
                .put("right", screenBounds.right)
                .put("bottom", screenBounds.bottom))
            .put("swipeOutputSummary", JSONArray(swipeOutput.lineSequence().map(String::trim).filter(String::isNotEmpty).take(2).toList()))
    }

    private fun runPostRebootCase(
        instrumentation: Instrumentation,
        automation: UiAutomation,
        apiBaseUrl: String,
        runId: String,
        postRebootSequence: Long,
        initial: JSONObject,
    ): JSONObject {
        val context = instrumentation.targetContext
        assertEquals("Post-boot observer must begin with the previously registered child", setOf(CHILD_NAME), initial.getJSONArray("registeredChildren").strings().toSet())
        assertEquals("The registered fake account was not restored automatically", ACCOUNT_ID, initial.optString("accountId"))
        assertTrue("The actual AccessibilityService lease was not attached before any product UI action", initial.getBoolean("serviceLeaseAttached"))
        val rootPackageAtStart = automation.rootInActiveWindow?.packageName?.toString()
        assertFalse("The product UI was opened before automatic boot restoration was observed", rootPackageAtStart == PRODUCT_PACKAGE)
        val initialTargets = targetsFrom(initial)
        assertEquals(TARGET_HANGEUL, initialTargets.getInt("hangeul"))
        assertEquals(TARGET_MATH, initialTargets.getInt("math"))
        assertEquals(TARGET_ENGLISH, initialTargets.getInt("english"))
        var fixture = waitForFixtureStateOrNull(apiBaseUrl, runId, BOOT_BASELINE_TIMEOUT_MS) { snapshot ->
            fullCycleAfter(snapshot.getJSONArray("requests").objects(), postRebootSequence) != null
        }
        var recoveryTrigger = "automatic-service-baseline"
        var foregroundCursor = lastSequence(initial.getJSONArray("foregroundEvents"))
        if (fixture == null) {
            // A route can be restored just after the boot service's first transient attempt.
            // Keep the report values unchanged; a real Todo foreground event permits the
            // service's next eligible full cycle without launching the product Activity.
            launchActualSubjectApp(context, HANGEUL_APP_PACKAGE)
            waitForActualForegroundAndConnectedService(automation, context, foregroundCursor, HANGEUL_APP_PACKAGE)
            recoveryTrigger = "eligible-subject-foreground-after-boot"
            fixture = awaitFixtureState(apiBaseUrl, runId, CADENCE_TIMEOUT_MS) { snapshot ->
                fullCycleAfter(snapshot.getJSONArray("requests").objects(), postRebootSequence) != null
            }
        }
        val state = waitForProductState(context, TIMEOUT_MS) { hasBaselineCounts(it) }
        val postBootRequests = fixture.getJSONArray("requests").objects()
        val firstPostBootCycle = fullCycleAfter(postBootRequests, postRebootSequence)
            ?: throw AssertionError("No complete post-boot fixture cycle followed reverse restoration: $postBootRequests")
        assertSuccessfulFakeFixtureRequests(firstPostBootCycle, baselineCounts())
        assertTrue("The first successful post-boot check must include all three reports", firstPostBootCycle.count { it.getString("path") in REPORT_PATHS } == 3)
        val baselineNotificationEvents = eventsAfter(state.getJSONArray("notificationEvents"), 0L)
        assertFalse("The first already-complete post-boot state requested a completion alert: $baselineNotificationEvents", baselineNotificationEvents.any { it.optBoolean("completionRequested") })
        val mathBaseline = subject(state, MATH_SUBJECT)
        assertEquals("COMPLETE", mathBaseline.getString("status"))
        assertEquals(TARGET_MATH, mathBaseline.getInt("activityCount"))
        assertTrue("The post-boot snapshot lost the real service lease", state.getBoolean("serviceLeaseAttached"))
        requireSilentChannel(state)

        val baselineNotification = state.getJSONArray("activeNotifications").objects().firstOrNull { it.optString("title").contains(CHILD_NAME) }
            ?: throw AssertionError("The silent status notification is absent after the successful boot check")
        val baselineNotificationId = baselineNotification.getInt("id")
        val baselineSignin = firstSigninDispatchAfter(state, 0L)
        fixtureRequest(apiBaseUrl, "POST", "/__ticket06/set-report-counts", JSONObject().put("reports", JSONObject().put("hangeul", TARGET_HANGEUL)))

        if (recoveryTrigger == "automatic-service-baseline") {
            foregroundCursor = lastSequence(state.getJSONArray("foregroundEvents"))
            launchActualSubjectApp(context, HANGEUL_APP_PACKAGE)
            waitForActualForegroundAndConnectedService(automation, context, foregroundCursor, HANGEUL_APP_PACKAGE)
        }
        val completionCursor = firstPostBootCycle.last().getLong("sequence")
        val completedFixture = awaitFixtureState(apiBaseUrl, runId, CADENCE_TIMEOUT_MS) { snapshot ->
            eligibleHangeulCycleAfter(snapshot.getJSONArray("requests").objects(), completionCursor)
                ?.lastOrNull()
                ?.optInt("servedReportCount", -1) == TARGET_HANGEUL
        }
        val completed = waitForProductState(context, CADENCE_TIMEOUT_MS) {
            val notification = it.getJSONArray("notificationEvents").objects().any { event ->
                event.optBoolean("completionRequested") && event.optString("completionSubjectName") == HANGEUL_SUBJECT && event.optBoolean("notificationManagerCallReturned")
            }
            subjectOrNull(it, HANGEUL_SUBJECT)?.let { current ->
                current.optString("status") == "COMPLETE" && current.optInt("activityCount", -1) == TARGET_HANGEUL && notification
            } ?: false
        }
        val newCompletion = eventsAfter(completed.getJSONArray("notificationEvents"), lastSequence(state.getJSONArray("notificationEvents")))
            .singleOrNull { it.optBoolean("completionRequested") && it.optString("completionSubjectName") == HANGEUL_SUBJECT }
            ?: throw AssertionError("A newly achieved Hangeul target did not request exactly one completion notification")
        assertEquals("The Android notification manager did not return success", true, newCompletion.getBoolean("notificationManagerCallReturned"))
        assertEquals("The completion should update the existing child notification ID", baselineNotificationId, newCompletion.getInt("notificationId"))
        val activeCompletionNotification = completed.getJSONArray("activeNotifications").objects().firstOrNull { it.optInt("id", -1) == baselineNotificationId }
            ?: throw AssertionError("The baseline status notification was not present after the new completion")
        assertTrue("The updated notification must show the new completion", activeCompletionNotification.optString("bigText").contains("$TARGET_HANGEUL/$TARGET_HANGEUL"))

        val completedFixtureRequests = completedFixture.getJSONArray("requests").objects()
        val completionCycle = eligibleHangeulCycleAfter(completedFixtureRequests, completionCursor)
            ?: throw AssertionError("The later Hangeul achievement did not follow one exact five-request eligible cycle")
        assertSuccessfulFakeFixtureRequests(
            completionCycle,
            JSONObject().put("hangeul", TARGET_HANGEUL),
            Ticket06EligibleSubjectCycle.HANGEUL_PATHS,
        )
        val postBootBaselineSignin = firstSigninDispatchAfter(state, 0L)
        val completionSignin = firstSigninDispatchForPackageAfter(completed, HANGEUL_APP_PACKAGE, postBootBaselineSignin.getLong("elapsedRealtimeMs"))
        val allCompletionDispatches = completed.getJSONArray("httpRequestDispatchEvents").objects()
        val completionDispatchCycle = eligibleHangeulCycleAfter(
            allCompletionDispatches,
            completionSignin.getLong("sequence") - 1L,
        ) ?: throw AssertionError("The later Hangeul achievement did not dispatch one exact five-request eligible cycle")
        completionDispatchCycle.forEach { event ->
            assertEquals(HANGEUL_APP_PACKAGE, event.getString("foregroundPackage"))
            assertTrue("The actual monitor lease was absent at dispatch $event", event.getBoolean("serviceLeaseAttached"))
            assertTrue("The eligible foreground event followed dispatch $event", event.getLong("foregroundEventElapsedRealtimeMs") <= event.getLong("elapsedRealtimeMs"))
        }
        val bootDispatches = state.getJSONArray("httpRequestDispatchEvents").objects()
        val fixturePathCounts = firstPostBootCycle.groupingBy { it.getString("path") }.eachCount()
        val dispatchPathCounts = bootDispatches.groupingBy { it.getString("path") }.eachCount()
        val dispatchesWithoutFixtureReceipt = (dispatchPathCounts.keys + fixturePathCounts.keys).sumOf { path ->
            (dispatchPathCounts.getOrDefault(path, 0) - fixturePathCounts.getOrDefault(path, 0)).coerceAtLeast(0)
        }
        return JSONObject()
            .put("runId", runId)
            .put("scenario", "ticket06")
            .put("phase", PHASE_POST_REBOOT)
            .put("automaticServiceBound", true)
            .put("productActivityLaunchedByController", false)
            .put("productRootPackageAtInitialObservation", rootPackageAtStart ?: JSONObject.NULL)
            .put("serviceLeaseAttached", initial.getBoolean("serviceLeaseAttached"))
            .put("registration", initial.getJSONArray("registeredChildren"))
            .put("targets", initialTargets)
            .put("interval", JSONObject().put("minutes", 1).put("persistedExplicitly", true).put("source", "debug-app-ui-save-and-host-filtered-post-boot-check"))
            .put("baseline", JSONObject()
                .put("complete", true)
                .put("successfulSubjects", 3)
                .put("alreadyCompleteSubjects", JSONArray(listOf(MATH_SUBJECT)))
                .put("completionAlertRequested", false)
                .put("firstSuccessfulCycleTrigger", recoveryTrigger)
                .put("fixtureRequestStartSequence", firstPostBootCycle.first().getLong("sequence"))
                .put("fixtureRequestEndSequence", firstPostBootCycle.last().getLong("sequence"))
                .put("requestPaths", JSONArray(firstPostBootCycle.map { it.getString("path") })))
            .put("bootTransport", JSONObject()
                .put("serviceDispatchPaths", JSONArray(bootDispatches.map { it.getString("path") }))
                .put("dispatchesWithoutFixtureReceipt", dispatchesWithoutFixtureReceipt)
                .put("successfulFixtureCycleRequestCount", firstPostBootCycle.size))
            .put("laterCompletion", JSONObject()
                .put("subject", HANGEUL_SUBJECT)
                .put("completionAlertRequested", true)
                .put("notificationIdReused", newCompletion.getInt("notificationId") == baselineNotificationId)
                .put("notificationManagerCallReturned", newCompletion.getBoolean("notificationManagerCallReturned"))
                .put("activeNotificationId", activeCompletionNotification.getInt("id"))
                .put("completionCycleSigninElapsedRealtimeMs", completionSignin.getLong("elapsedRealtimeMs")))
            .put("foreground", JSONObject()
                .put("packageName", completionSignin.getString("foregroundPackage"))
                .put("eligibleRequests", completionCycle.size)
                .put("requestPaths", JSONArray(completionCycle.map { it.getString("path") }))
                .put("responseStatuses", JSONArray(completionCycle.map { it.getInt("responseStatus") }))
                .put("servedReportCounts", JSONArray(completionCycle.filter { it.getString("path") in REPORT_PATHS }.map { it.getInt("servedReportCount") }))
                .put("dispatchPaths", JSONArray(completionDispatchCycle.map { it.getString("path") }))
                .put("dispatchForegroundPackages", JSONArray(completionDispatchCycle.map { it.getString("foregroundPackage") }))
                .put("dispatchServiceLeases", JSONArray(completionDispatchCycle.map { it.getBoolean("serviceLeaseAttached") }))
                .put("actualLeaseAttachedAtDispatch", completionSignin.getBoolean("serviceLeaseAttached")))
            .put("postBootRequestPaths", JSONArray(firstPostBootCycle.map { it.getString("path") }))
            .put("productAlertSoundOrHeadsUpTested", false)
    }

    private fun openProductApp(instrumentation: Instrumentation) {
        val intent = instrumentation.targetContext.packageManager.getLaunchIntentForPackage(PRODUCT_PACKAGE)
            ?: throw AssertionError("The isolated debug app has no launcher activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.targetContext.startActivity(intent)
    }

    private fun enterFakeCredentialsIfNeeded(automation: UiAutomation) {
        val email = findNodeOrNull(automation, LOGIN_FORM_TIMEOUT_MS) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 이메일" || it.text?.toString() == "토도스쿨 이메일")
        } ?: return
        setField(email, TEST_EMAIL)
        val password = waitForNode(automation, TIMEOUT_MS) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 비밀번호" || it.text?.toString() == "토도스쿨 비밀번호")
        }
        setField(password, TEST_PASSWORD)
        clickText(automation, "확인")
    }

    private fun waitForProductScreen(automation: UiAutomation) {
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString()?.contains(CHILD_NAME) == true || it.text?.toString() == "로그인" }
    }

    private fun writeTargetsThroughTheApp(automation: UiAutomation) {
        clickDescription(automation, "$CHILD_NAME 목표 설정")
        val fields = findAllNodes(automation, TIMEOUT_MS) { it.className == EDIT_TEXT }
        assertEquals("The target dialog must show all subscribed subjects", 3, fields.size)
        listOf(TARGET_HANGEUL, TARGET_MATH, TARGET_ENGLISH).forEachIndexed { index, count -> setField(fields[index], count.toString()) }
        clickText(automation, "저장")
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString()?.contains("한글 (2/$TARGET_HANGEUL)") == true }
    }

    private fun saveOneMinuteIntervalExplicitly(automation: UiAutomation) {
        val intervalLabel = visibleText(automation).firstOrNull { it.startsWith("감시 간격 ") }
            ?: throw AssertionError("The actual monitor interval control was not visible")
        clickText(automation, intervalLabel)
        val field = waitForNode(automation, TIMEOUT_MS) { it.className == EDIT_TEXT }
        setField(field, "1")
        clickText(automation, "저장")
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString() == "감시 간격 1분" }
    }

    private fun requireActualRuntime(state: JSONObject) {
        assertTrue("The actual product AccessibilityService lease is not attached: $state", state.optBoolean("serviceLeaseAttached"))
        assertTrue("Accessibility access is not enabled: $state", state.optBoolean("accessibilityEnabled"))
        assertTrue("Notification delivery is not enabled: $state", state.optBoolean("notificationsEnabled"))
        assertTrue("POST_NOTIFICATIONS is not granted: $state", state.optBoolean("postNotificationsPermissionGranted"))
    }

    private fun requireSilentChannel(state: JSONObject) {
        val channel = state.getJSONObject("channel")
        val importance = channel.optInt("importance", -1)
        assertTrue("The product notification channel is missing: $channel", channel.optBoolean("exists"))
        assertFalse("The product notification channel must have vibration disabled: $channel", channel.optBoolean("vibrationEnabled"))
        assertTrue("The configured low-importance channel is not silent: $channel", importance == IMPORTANCE_MIN || importance == IMPORTANCE_LOW)
    }

    private fun assertBaselineVisibleInUi(automation: UiAutomation) {
        waitForCondition(TIMEOUT_MS, "the actual debug UI to render the registered baseline") {
            val text = visibleText(automation).joinToString("\n")
            text.contains("한글 (2/$TARGET_HANGEUL)") && text.contains("✔ 수학 ($TARGET_MATH/$TARGET_MATH)") && text.contains("영어 (7/$TARGET_ENGLISH)")
        }
    }

    private fun baselineCounts() = JSONObject().put("hangeul", 2).put("math", TARGET_MATH).put("english", 7)

    private fun assertSuccessfulFakeFixtureRequests(
        requests: List<JSONObject>,
        counts: JSONObject,
        expectedPaths: List<String> = EXPECTED_PATHS,
    ) {
        assertEquals(expectedPaths, requests.map { it.getString("path") })
        requests.forEach { request ->
            assertEquals("POST", request.getString("method"))
            assertEquals(200, request.getInt("responseStatus"))
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
                val pathSubject = when (request.getString("path")) {
                    HANGEUL_REPORT_PATH -> "hangeul"
                    MATH_REPORT_PATH -> "math"
                    else -> "english"
                }
                assertEquals(counts.getInt(pathSubject), request.getInt("servedReportCount"))
                assertEquals(seoulDateNumber(), request.getJSONObject("body").getInt("yyyymmdd"))
                assertEquals("ko", request.getJSONObject("body").getString("languageCode"))
            }
        }
    }

    private fun hasBaselineCounts(state: JSONObject): Boolean = runCatching {
        subject(state, HANGEUL_SUBJECT).let { it.optInt("activityCount", -1) == 2 && it.optInt("targetCount", -1) == TARGET_HANGEUL && it.optString("status") == "BELOW_TARGET" } &&
            subject(state, MATH_SUBJECT).let { it.optInt("activityCount", -1) == TARGET_MATH && it.optInt("targetCount", -1) == TARGET_MATH && it.optString("status") == "COMPLETE" } &&
            subject(state, ENGLISH_SUBJECT).let { it.optInt("activityCount", -1) == 7 && it.optInt("targetCount", -1) == TARGET_ENGLISH && it.optString("status") == "BELOW_TARGET" }
    }.getOrDefault(false)

    private fun targetsFrom(state: JSONObject): JSONObject {
        val subjects = state.getJSONObject("children").getJSONObject(CHILD_NAME).getJSONObject("subjects")
        return JSONObject()
            .put("hangeul", subjects.getJSONObject(HANGEUL_SUBJECT).getInt("targetCount"))
            .put("math", subjects.getJSONObject(MATH_SUBJECT).getInt("targetCount"))
            .put("english", subjects.getJSONObject(ENGLISH_SUBJECT).getInt("targetCount"))
    }

    private fun subject(state: JSONObject, subjectName: String): JSONObject =
        state.getJSONObject("children").getJSONObject(CHILD_NAME).getJSONObject("subjects").getJSONObject(subjectName)

    private fun subjectOrNull(state: JSONObject, subjectName: String): JSONObject? = runCatching { subject(state, subjectName) }.getOrNull()

    private fun eventsAfter(events: JSONArray, sequence: Long): List<JSONObject> = events.objects().filter { it.optLong("sequence") > sequence }

    private fun lastSequence(events: JSONArray): Long = events.objects().maxOfOrNull { it.optLong("sequence") } ?: 0L

    private fun firstSigninDispatchAfter(state: JSONObject, afterSequence: Long): JSONObject =
        state.getJSONArray("httpRequestDispatchEvents").objects().firstOrNull {
            it.optLong("sequence") > afterSequence && it.optString("path") == SIGN_IN_PATH
        } ?: throw AssertionError("No actual sign-in body dispatch followed the recorded monitor boundary")

    private fun firstSigninDispatchForPackageAfter(state: JSONObject, packageName: String, elapsedAfter: Long): JSONObject =
        state.getJSONArray("httpRequestDispatchEvents").objects().firstOrNull {
            it.optString("path") == SIGN_IN_PATH && it.optString("foregroundPackage") == packageName && it.optLong("elapsedRealtimeMs") > elapsedAfter
        } ?: throw AssertionError("No actual eligible $packageName sign-in dispatch followed the requested cycle")

    private fun eligibleHangeulCycleAfter(requests: List<JSONObject>, afterSequence: Long): List<JSONObject>? {
        val bySequence = requests.associateBy { it.getLong("sequence") }
        val cycle = findFirstExactTicket06RequestCycleAfter(
            requests = requests.map { request ->
                Ticket06RequestPathEvidence(
                    sequence = request.getLong("sequence"),
                    path = request.getString("path"),
                )
            },
            afterSequence = afterSequence,
            expectedPaths = Ticket06EligibleSubjectCycle.HANGEUL_PATHS,
        ) ?: return null
        if (cycle.any { it.sequence !in bySequence }) return null
        return cycle.map { bySequence.getValue(it.sequence) }
    }

    private fun fullCycleAfter(requests: List<JSONObject>, afterSequence: Long): List<JSONObject>? {
        val later = requests.filter { it.optLong("sequence") > afterSequence }.sortedBy { it.getLong("sequence") }
        for (start in later.indices) {
            val candidate = later.drop(start).take(EXPECTED_PATHS.size)
            if (candidate.size == EXPECTED_PATHS.size && candidate.map { it.getString("path") } == EXPECTED_PATHS) return candidate
        }
        return null
    }

    private fun waitForActualForegroundAndConnectedService(
        automation: UiAutomation,
        context: android.content.Context,
        afterForegroundSequence: Long,
        expectedPackage: String,
    ) {
        waitForCondition(TIMEOUT_MS, "the actual Todo app foreground and live monitor lease") {
            val activePackage = automation.rootInActiveWindow?.packageName?.toString()
            val state = readProductState(context)
            val eventObserved = state.getJSONArray("foregroundEvents").objects().any {
                it.optString("packageName") == expectedPackage && it.optLong("sequence") > afterForegroundSequence
            }
            activePackage == expectedPackage && eventObserved && state.optBoolean("serviceLeaseAttached")
        }
    }

    private fun awaitPreRemovalPreferenceCapture(apiBaseUrl: String, runId: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        do {
            val state = fixtureRequest(apiBaseUrl, "GET", "/__ticket06/state")
            assertEquals("Ticket 06 fixture run changed before task removal", runId, state.optString("runId"))
            if (state.optBoolean("ticket06PreRemovalPreferencesCaptured")) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("The PC did not capture exact registration, interval, and target rows before Recents task removal")
    }

    private fun awaitPreRebootPreferenceCapture(apiBaseUrl: String, runId: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        do {
            val state = fixtureRequest(apiBaseUrl, "GET", "/__ticket06/state")
            assertEquals("Ticket 06 fixture run changed before reboot", runId, state.optString("runId"))
            if (state.optBoolean("ticket06PreRebootPreferencesCaptured")) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("The PC did not capture exact registration, interval, and target rows before reboot")
    }

    private fun launchActualSubjectApp(context: android.content.Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw AssertionError("The actual Todo subject app is not installed: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }

    private data class TaskIdentity(val id: Int, val component: String)

    private fun taskIdentityForProduct(automation: UiAutomation): TaskIdentity {
        return recentTaskIdentities(automation).firstOrNull { it.component.startsWith("$PRODUCT_PACKAGE/") }
            ?: throw AssertionError("Could not associate the observed debug app with its actual Android Recents task ID")
    }

    private fun recentTaskIdentities(automation: UiAutomation): List<TaskIdentity> {
        val lines = recentsDump(automation).lineSequence().toList()
        val tasks = mutableListOf<TaskIdentity>()
        for (index in lines.indices) {
            val taskLine = lines[index]
            if (!taskLine.contains("Task{")) continue
            val task = Regex("Task\\{[^}]*#([0-9]+)").find(taskLine) ?: continue
            val component = lines.drop(index).take(12)
                .firstOrNull { it.trimStart().startsWith("mActivityComponent=") }
                ?.substringAfter('=')?.trim()
                .orEmpty()
            tasks += TaskIdentity(task.groupValues[1].toInt(), component)
        }
        return tasks.distinctBy { it.id }
    }

    private fun taskIsPresent(automation: UiAutomation, taskId: Int): Boolean =
        recentTaskIdentities(automation).any { it.id == taskId && it.component.startsWith("$PRODUCT_PACKAGE/") }

    private fun recentsDump(automation: UiAutomation): String = executeShellCommand(automation, "dumpsys activity recents")

    private fun executeShellCommand(automation: UiAutomation, command: String): String {
        val descriptor: ParcelFileDescriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val length = reader.read(buffer)
                    if (length < 0) break
                    if (result.length + length > MAX_SHELL_OUTPUT_CHARS) throw AssertionError("Shell evidence exceeded its bounded read size")
                    result.append(buffer, 0, length)
                }
                result.toString()
            }
        }
    }

    private fun readProductState(context: android.content.Context): JSONObject {
        val result = context.contentResolver.call(PROVIDER_AUTHORITY, PROVIDER_METHOD_SNAPSHOT, null, Bundle())
            ?: throw AssertionError("The debug read-only diagnostics provider returned no result")
        return JSONObject(result.getString(PROVIDER_EXTRA_SNAPSHOT_JSON)
            ?: throw AssertionError("The debug diagnostics provider omitted its snapshot"))
    }

    private fun waitForProductState(context: android.content.Context, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitForValue(timeoutMs, "the requested actual debug-app state") { readProductState(context).takeIf(predicate) }

    private fun awaitFixtureState(apiBaseUrl: String, runId: String, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitForValue(timeoutMs, "matching Ticket 06 fixture-server state") {
            fixtureRequest(apiBaseUrl, "GET", "/__ticket06/state").takeIf { it.optString("runId") == runId && predicate(it) }
        }

    private fun waitForFixtureStateOrNull(apiBaseUrl: String, runId: String, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            val snapshot = fixtureRequest(apiBaseUrl, "GET", "/__ticket06/state")
            assertEquals("Fixture server run ID changed while the device was rebooting", runId, snapshot.optString("runId"))
            if (predicate(snapshot)) return snapshot
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun waitForValue(timeoutMs: Long, description: String, block: () -> JSONObject?): JSONObject {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            block()?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun waitForCondition(timeoutMs: Long, description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (condition()) return
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
            if (status !in 200..299) throw AssertionError("Fixture request $path returned HTTP $status")
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun setField(field: AccessibilityNodeInfo, value: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue("Could not enter text into an actual app field", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
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
        findNodeOrNull(automation, timeoutMs, predicate) ?: throw AssertionError("Timed out waiting for an actual UI element")

    private fun findNodeOrNull(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            accessibilityRoots(automation).forEach { root -> findNode(root, predicate)?.let { return it } }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun findAllNodes(automation: UiAutomation, timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = findNodeOrNull(automation, timeoutMs) { predicate(it) }?.let { automation.rootInActiveWindow }
            ?: throw AssertionError("The actual UI did not expose the expected editable fields")
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

    private fun collectNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean, result: MutableList<AccessibilityNodeInfo>) {
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (predicate(node)) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
    }

    private fun visibleText(automation: UiAutomation): List<String> {
        val result = mutableListOf<String>()
        accessibilityRoots(automation).forEach { root ->
            val pending = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                node.text?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
                node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
                for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
            }
        }
        return result
    }

    private fun accessibilityRoots(automation: UiAutomation): List<AccessibilityNodeInfo> = buildList {
        automation.rootInActiveWindow?.let(::add)
        try {
            automation.windows.mapNotNull { it.root }.forEach(::add)
        } catch (_: SecurityException) {
            // The active root remains useful when a device does not expose interactive window roots.
        }
    }

    private fun accessibilityWindowPackages(automation: UiAutomation): List<String> = try {
        automation.windows.mapNotNull { it.root?.packageName?.toString() }.distinct().take(8)
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun seoulDateNumber(): Int = LocalDate.now(ZoneId.of("Asia/Seoul")).year * 10_000 +
        LocalDate.now(ZoneId.of("Asia/Seoul")).monthValue * 100 + LocalDate.now(ZoneId.of("Asia/Seoul")).dayOfMonth

    private fun emitStructuredResult(result: JSONObject, runId: String) {
        assertEquals(runId, result.getString("runId"))
        val encoded = android.util.Base64.encodeToString(result.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val chunks = encoded.chunked(RESULT_CHUNK_CHARS)
        chunks.forEachIndexed { index, chunk ->
            Log.i(RESULT_LOG_TAG, "$RESULT_CHUNK_MARKER$runId:${index + 1}/${chunks.size}:$chunk")
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = buildList {
        for (index in 0 until length()) add(getJSONObject(index))
    }

    private fun JSONArray.strings(): List<String> = buildList {
        for (index in 0 until length()) add(getString(index))
    }

    private companion object {
        const val ARG_API_BASE_URL = "apiBaseUrl"
        const val ARG_RUN_ID = "runId"
        const val ARG_PHASE = "phase"
        const val ARG_POST_REBOOT_SEQUENCE = "postRebootSequence"
        const val LOOPBACK_PREFIX = "http://127.0.0.1:"
        const val PRODUCT_PACKAGE = "com.example.todoschooltime.debug"
        const val PROVIDER_AUTHORITY = "com.example.todoschooltime.debug.monitor-diagnostics"
        const val PROVIDER_METHOD_SNAPSHOT = "snapshot"
        const val PROVIDER_EXTRA_SNAPSHOT_JSON = "snapshotJson"
        const val CHILD_NAME = "테스트 아이"
        const val ACCOUNT_ID = "ticket01-fake-account"
        const val ACCOUNT_KEY = "dGlja2V0MDEtZmFrZS1hY2NvdW50"
        const val TEST_EMAIL = "ticket01@example.test"
        const val TEST_PASSWORD = "not-a-real-password"
        const val AUTHORIZATION = "Bearer ticket01-fake-token"
        const val HANGEUL_APP_PACKAGE = "com.enuma.todohangeul"
        const val HANGEUL_SUBJECT = "한글"
        const val MATH_SUBJECT = "수학"
        const val ENGLISH_SUBJECT = "영어"
        const val HANGEUL_REPORT_PATH = "/v3/todoschool/report/daily/hangeul"
        const val MATH_REPORT_PATH = "/v3/todoschool/report/daily/math"
        const val ENGLISH_REPORT_PATH = "/v3/todoschool/report/daily/english"
        const val HANGEUL_CATALOG_PATH = "/v3/todohangeul/user"
        const val MATH_CATALOG_PATH = "/v3/math/user"
        const val ENGLISH_CATALOG_PATH = "/v3/english/user"
        const val SIGN_IN_PATH = "/v3/account/signin"
        const val TARGET_HANGEUL = 6
        const val TARGET_MATH = 5
        const val TARGET_ENGLISH = 8
        const val PHASE_TASK_REMOVAL = "task-removal"
        const val PHASE_REBOOT_PREPARATION = "reboot-preparation"
        const val PHASE_POST_REBOOT = "post-reboot"
        const val EDIT_TEXT = "android.widget.EditText"
        const val IMPORTANCE_MIN = 1
        const val IMPORTANCE_LOW = 2
        const val TIMEOUT_MS = 30_000L
        const val LOGIN_FORM_TIMEOUT_MS = 2_000L
        const val BASELINE_TIMEOUT_MS = 90_000L
        const val BOOT_BASELINE_TIMEOUT_MS = 45_000L
        const val CADENCE_TIMEOUT_MS = 140_000L
        const val SERVER_TIMEOUT_MS = 5_000
        const val POLL_INTERVAL_MS = 400L
        const val MAX_WAKE_LOCK_MS = 15 * 60 * 1000L
        const val MAX_SHELL_OUTPUT_CHARS = 1_000_000
        const val RESULT_CHUNK_CHARS = 3_000
        const val RESULT_LOG_TAG = "Ticket06Result"
        const val STAGE_LOG_TAG = "Ticket06Stage"
        const val RESULT_CHUNK_MARKER = "TICKET06_RESULT_CHUNK:"
        val EXPECTED_PATHS = listOf(
            SIGN_IN_PATH,
            HANGEUL_CATALOG_PATH,
            MATH_CATALOG_PATH,
            ENGLISH_CATALOG_PATH,
            HANGEUL_REPORT_PATH,
            MATH_REPORT_PATH,
            ENGLISH_REPORT_PATH,
        )
        val CATALOG_PATHS = setOf(HANGEUL_CATALOG_PATH, MATH_CATALOG_PATH, ENGLISH_CATALOG_PATH)
        val REPORT_PATHS = setOf(HANGEUL_REPORT_PATH, MATH_REPORT_PATH, ENGLISH_REPORT_PATH)
    }
}
