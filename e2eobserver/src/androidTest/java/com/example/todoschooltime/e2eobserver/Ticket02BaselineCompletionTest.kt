package com.example.todoschooltime.e2eobserver

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs outside the observed app process so instrumentation cannot restart its AccessibilityService. */
@RunWith(AndroidJUnit4::class)
class Ticket02BaselineCompletionTest {
    @Test
    fun completedBaselineIsSilentAndLaterCompletionUpdatesTheChildNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val apiBaseUrl = arguments.getString(ARG_API_BASE_URL)
            ?: throw AssertionError("Missing apiBaseUrl instrumentation argument")
        val runId = arguments.getString(ARG_RUN_ID)
            ?: throw AssertionError("Missing runId instrumentation argument")
        assertTrue("Ticket 02 requires a loopback-only API fixture", apiBaseUrl.startsWith(LOOPBACK_PREFIX))

        val observerContext = instrumentation.targetContext
        val packageManager = observerContext.packageManager
        val appInfo = packageManager.getApplicationInfo(PRODUCT_PACKAGE, 0)
        assertEquals(DEBUG_APPLICATION_LABEL, appInfo.loadLabel(packageManager).toString())
        val serviceInfo = packageManager.getServiceInfo(
            ComponentName(PRODUCT_PACKAGE, PRODUCT_SERVICE_CLASS),
            0,
        )
        assertEquals(DEBUG_ACCESSIBILITY_LABEL, serviceInfo.loadLabel(packageManager).toString())

        // Read the live lease before creating UiAutomation. A stale enabled bit cannot pass this gate.
        val beforeAutomation = readProductState(observerContext)
        assertTrue("The real AccessibilityService lease must already be attached: $beforeAutomation", beforeAutomation.getBoolean("serviceLeaseAttached"))
        assertTrue("Accessibility permission is not enabled: $beforeAutomation", beforeAutomation.getBoolean("accessibilityEnabled"))
        assertTrue("Notification permission is not enabled: $beforeAutomation", beforeAutomation.getBoolean("notificationsEnabled"))
        assertTrue("POST_NOTIFICATIONS is not granted: $beforeAutomation", beforeAutomation.getBoolean("postNotificationsPermissionGranted"))
        val initialRegistrations = beforeAutomation.getJSONArray("registeredChildren").strings().toSet()
        assertTrue(
            "Only an empty state or this scenario's previous child registration is allowed: $beforeAutomation",
            initialRegistrations.isEmpty() || initialRegistrations == setOf(CHILD_NAME),
        )
        val initialNotifications = beforeAutomation.getJSONArray("activeNotifications").objects()
        assertTrue(
            "Only this scenario's previous child notification is allowed: $initialNotifications",
            initialNotifications.size <= 1 && initialNotifications.all { it.optString("title").contains(CHILD_NAME) },
        )
        val initialChannel = requireSilentChannel(beforeAutomation)
        println("TICKET02_LIFECYCLE beforeUiAutomation=$beforeAutomation")

        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val afterAutomation = readProductState(observerContext)
        assertTrue("UiAutomation changed the real monitor lease: $afterAutomation", afterAutomation.getBoolean("serviceLeaseAttached"))
        assertTrue("UiAutomation disabled accessibility: $afterAutomation", afterAutomation.getBoolean("accessibilityEnabled"))
        assertTrue("UiAutomation changed POST_NOTIFICATIONS: $afterAutomation", afterAutomation.getBoolean("postNotificationsPermissionGranted"))
        requireSilentChannel(afterAutomation)
        println("TICKET02_LIFECYCLE afterUiAutomation=$afterAutomation")

        val expectedApiBaseUrl = apiBaseUrl.trimEnd('/')
        val health = fixtureRequest(expectedApiBaseUrl, "GET", "/__ticket02/health")
        assertEquals(runId, health.getString("runId"))

        openProductApp(instrumentation)
        enterFakeCredentialsIfNeeded(automation)
        assertInitialScreenShowsTheServerBaseline(automation)

        val accountSnapshot = waitForProductState(observerContext, TIMEOUT_MS) { it.optString("accountId") == ACCOUNT_ID }
        assertEquals(ACCOUNT_ID, accountSnapshot.getString("accountId"))
        assertTrue("The real service disconnected during screen observation: $accountSnapshot", accountSnapshot.getBoolean("serviceLeaseAttached"))
        val existingRegistrations = accountSnapshot.getJSONArray("registeredChildren").strings().toSet()
        assertTrue(
            "Only this scenario's previous child registration may be present: $accountSnapshot",
            existingRegistrations.isEmpty() || existingRegistrations == setOf(CHILD_NAME),
        )
        if (existingRegistrations == setOf(CHILD_NAME)) {
            clickDescription(automation, "$CHILD_NAME 감시 해제")
            val resetSnapshot = waitForProductState(observerContext, TIMEOUT_MS) { state ->
                state.getJSONArray("registeredChildren").length() == 0 && state.getJSONArray("activeNotifications").length() == 0
            }
            assertTrue("The real service disconnected while resetting the prior scenario through the UI", resetSnapshot.getBoolean("serviceLeaseAttached"))
        } else {
            assertEquals("An unregistered test child must not leave an active notification", 0, accountSnapshot.getJSONArray("activeNotifications").length())
        }

        writeDefaultTargetsThroughTheApp(automation)
        requireSilentChannel(readProductState(observerContext))
        assertEquals("The debug app must start with no child notifications", 0, readProductState(observerContext).getJSONArray("activeNotifications").length())

        val reset = fixtureRequest(expectedApiBaseUrl, "POST", "/__ticket02/reset-requests", JSONObject())
        assertEquals(true, reset.getBoolean("ok"))
        assertEquals(runId, reset.getString("runId"))

        val baselineEventCursor = lastSequence(readProductState(observerContext).getJSONArray("notificationEvents"))
        clickDescription(automation, "$CHILD_NAME 감시 등록")
        val baselineSnapshot = waitForProductState(observerContext, TIMEOUT_MS) { state ->
            state.getJSONArray("registeredChildren").strings().contains(CHILD_NAME) && isExpectedBaseline(state)
        }
        assertTrue("The server-backed baseline should have one complete math subject", isExpectedBaseline(baselineSnapshot))

        val baselineNotification = waitForNotification(observerContext, TIMEOUT_MS) { notification ->
            notification.optString("title") == "$CHILD_NAME 학습 상태" && notification.optString("bigText").contains("✔ 수학 완료 5/5")
        }
        val baselineBody = baselineNotification.getString("bigText")
        assertTrue(baselineBody.contains("한글 미달성 2/6"))
        assertTrue(baselineBody.contains("✔ 수학 완료 5/5"))
        assertTrue(baselineBody.contains("영어 미달성 7/8"))

        val baselineEvents = eventsAfter(readProductState(observerContext).getJSONArray("notificationEvents"), baselineEventCursor)
            .filter { it.optString("accountId") == ACCOUNT_ID && it.optString("childName") == CHILD_NAME }
        assertTrue("No real notification sink request was observed for the baseline", baselineEvents.isNotEmpty())
        assertTrue("An already-complete baseline subject requested a completion alert: $baselineEvents", baselineEvents.none { it.optBoolean("completionRequested") })
        assertTrue("A baseline notification did not reach NotificationManager: $baselineEvents", baselineEvents.all { it.optBoolean("notificationManagerCallReturned") })

        val baselineServer = awaitFixtureState(expectedApiBaseUrl, runId, TIMEOUT_MS) { state ->
            state.getJSONArray("requests").objects().count { it.optString("path") in REPORT_PATHS } >= 3
        }
        val baselineRequests = baselineServer.getJSONArray("requests").objects()
        assertBaselineRequests(baselineRequests)
        val baselineNotificationId = baselineNotification.getInt("id")

        // The same no-sound/no-vibration rule is checked immediately before the next real completion.
        val beforeChange = readProductState(observerContext)
        requireSilentChannel(beforeChange)
        val foregroundCursor = lastSequence(beforeChange.getJSONArray("foregroundEvents"))
        val changedResponse = fixtureRequest(
            expectedApiBaseUrl,
            "POST",
            "/__ticket02/set-report-counts",
            JSONObject().put("reports", JSONObject().put("hangeul", TARGET_HANGEUL)),
        )
        assertEquals(runId, changedResponse.getString("runId"))
        assertEquals(TARGET_HANGEUL, changedResponse.getJSONObject("reportCounts").getInt("hangeul"))
        val afterRequestSequence = changedResponse.getLong("afterRequestSequence")

        launchActualSubjectApp(observerContext, HANGEUL_APP_PACKAGE)
        waitForActualForegroundAndConnectedService(
            automation = automation,
            context = observerContext,
            afterForegroundSequence = foregroundCursor,
            expectedPackage = HANGEUL_APP_PACKAGE,
        )
        val changedReportState = awaitFixtureState(expectedApiBaseUrl, runId, POLL_TIMEOUT_MS) { state ->
            state.getJSONArray("requests").objects().any { request ->
                request.optLong("sequence") > afterRequestSequence &&
                    request.optString("path") == HANGEUL_REPORT_PATH &&
                    request.optInt("servedReportCount", -1) == TARGET_HANGEUL
            }
        }
        val changedReport = changedReportState.getJSONArray("requests").objects().first { request ->
            request.optLong("sequence") > afterRequestSequence &&
                request.optString("path") == HANGEUL_REPORT_PATH &&
                request.optInt("servedReportCount", -1) == TARGET_HANGEUL
        }
        assertEquals(HANGEUL_USER_ID, changedReport.getJSONObject("body").getString("userId"))
        assertEquals(seoulDateNumber(), changedReport.getJSONObject("body").getInt("yyyymmdd"))

        val completedSnapshot = waitForProductState(observerContext, POLL_TIMEOUT_MS) { state ->
            state.getBoolean("serviceLeaseAttached") && subject(state, HANGEUL_SUBJECT).let {
                it.optString("status") == "COMPLETE" && it.optInt("activityCount", -1) == TARGET_HANGEUL
            }
        }
        assertTrue("The real service detached while the actual subject app was in front", completedSnapshot.getBoolean("serviceLeaseAttached"))
        val completionEvents = eventsAfter(completedSnapshot.getJSONArray("notificationEvents"), baselineEventCursor)
            .filter { it.optString("accountId") == ACCOUNT_ID && it.optString("childName") == CHILD_NAME && it.optBoolean("completionRequested") }
        assertEquals("The engine should request one completion update for the newly complete hangeul result", 1, completionEvents.size)
        assertEquals(HANGEUL_SUBJECT, completionEvents.single().getString("completionSubjectName"))
        assertEquals(baselineNotificationId, completionEvents.single().getInt("notificationId"))
        assertTrue(completionEvents.single().getBoolean("notificationManagerCallReturned"))

        val completedNotification = waitForNotification(observerContext, TIMEOUT_MS) { notification ->
            notification.optInt("id", -1) == baselineNotificationId &&
                notification.optString("title") == "$CHILD_NAME · $HANGEUL_SUBJECT 완료"
        }
        val completedBody = completedNotification.getString("bigText")
        assertTrue("The updated notification omitted the completed subject: $completedBody", completedBody.contains("✔ 한글 완료 6/6"))
        assertTrue("The updated notification omitted the previously complete subject: $completedBody", completedBody.contains("✔ 수학 완료 5/5"))
        assertTrue("The updated notification omitted the remaining incomplete subject: $completedBody", completedBody.contains("영어 미달성 7/8"))
        val activeNotifications = readProductState(observerContext).getJSONArray("activeNotifications").objects()
        assertEquals("Completion must update the same child's notification without adding another item", listOf(baselineNotificationId), activeNotifications.map { it.getInt("id") })
        assertEquals(setOf(CHILD_NAME), readProductState(observerContext).getJSONArray("registeredChildren").strings().toSet())

        val finalServer = fixtureRequest(expectedApiBaseUrl, "GET", "/__ticket02/state")
        assertEquals(runId, finalServer.getString("runId"))
        assertAllRequestsUseFakeAccountAndSeoulDate(finalServer.getJSONArray("requests").objects())
        val foregroundEvents = completedSnapshot.getJSONArray("foregroundEvents").objects()
        assertTrue("The connected service did not observe the actual subject app package", foregroundEvents.any { it.optString("packageName") == HANGEUL_APP_PACKAGE && it.optLong("sequence") > foregroundCursor })

        Log.i(
            RESULT_LOG_TAG,
            "TICKET02_RESULT=" + JSONObject()
                .put("runId", runId)
                .put("apiBaseUrl", apiBaseUrl)
                .put("subjectAppPackage", HANGEUL_APP_PACKAGE)
                .put("seoulDate", seoulDateNumber())
                .put("baselineNotificationId", baselineNotificationId)
                .put("completionNotificationId", completedNotification.getInt("id"))
                .put("baselineCompletionRequests", baselineEvents.count { it.optBoolean("completionRequested") })
                .put("followUpCompletionRequests", completionEvents.size)
                .put("accessibilityServiceLeaseAttached", completedSnapshot.getBoolean("serviceLeaseAttached"))
                .put("foregroundPackageObserved", HANGEUL_APP_PACKAGE)
                .put("silentChannelPrepared", true)
                .put("notificationChannelImportance", initialChannel.getInt("importance"))
                .put("notificationChannelHasSoundUri", initialChannel.optString("soundUri") != "null")
                .put("notificationChannelVibrationEnabled", initialChannel.optBoolean("vibrationEnabled"))
                .put("postedNotificationCount", activeNotifications.size)
                .put("productAlertDeliveryTested", false)
                .put("observerPackage", "com.example.todoschooltime.e2eobserver")
                .toString(),
        )
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
        val password = waitForNode(automation, LOGIN_FORM_TIMEOUT_MS) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 비밀번호" || it.text?.toString() == "토도스쿨 비밀번호")
        }
        setField(password, TEST_PASSWORD)
        clickText(automation, "확인")
    }

    private fun assertInitialScreenShowsTheServerBaseline(automation: UiAutomation) {
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString()?.contains(CHILD_NAME) == true }
        val text = visibleText(automation).joinToString("\n")
        assertTrue("The screen omitted the initial hangeul report: $text", text.contains("한글 (2/6)"))
        assertTrue("The screen omitted the already-complete math report: $text", text.contains("✔ 수학 (5/5)"))
        assertTrue("The screen omitted the initial English report: $text", text.contains("영어 (7/8)"))
    }

    private fun writeDefaultTargetsThroughTheApp(automation: UiAutomation) {
        clickDescription(automation, "$CHILD_NAME 목표 설정")
        val fields = findAllNodes(automation, TIMEOUT_MS) { it.className == EDIT_TEXT }
        assertEquals("The target dialog should show all three subscribed subjects", 3, fields.size)
        listOf(TARGET_HANGEUL, TARGET_MATH, TARGET_ENGLISH).forEachIndexed { index, target -> setField(fields[index], target.toString()) }
        clickText(automation, "저장")
        waitForNode(automation, TIMEOUT_MS) { it.text?.toString()?.contains("한글 (2/6)") == true }
        assertTrue(visibleText(automation).joinToString("\n").contains("✔ 수학 (5/5)"))
    }

    private fun assertBaselineRequests(requests: List<JSONObject>) {
        val expectedPaths = listOf(SIGN_IN_PATH, HANGEUL_CATALOG_PATH, MATH_CATALOG_PATH, ENGLISH_CATALOG_PATH, HANGEUL_REPORT_PATH, MATH_REPORT_PATH, ENGLISH_REPORT_PATH)
        assertEquals(expectedPaths, requests.map { it.getString("path") })
        val initialCounts = mapOf(HANGEUL_REPORT_PATH to 2, MATH_REPORT_PATH to TARGET_MATH, ENGLISH_REPORT_PATH to 7)
        requests.filter { it.getString("path") in REPORT_PATHS }.forEach { assertEquals(initialCounts[it.getString("path")], it.getInt("servedReportCount")) }
        assertAllRequestsUseFakeAccountAndSeoulDate(requests)
    }

    private fun assertAllRequestsUseFakeAccountAndSeoulDate(requests: List<JSONObject>) {
        requests.forEach { request ->
            assertEquals("POST", request.getString("method"))
            assertEquals(200, request.getInt("responseStatus"))
            val headers = request.getJSONObject("headers")
            assertTrue(headers.getString("content-type").startsWith("application/json"))
            if (request.getString("path") == SIGN_IN_PATH) {
                assertEquals(TEST_EMAIL, request.getJSONObject("body").getString("email"))
                assertEquals(TEST_PASSWORD, request.getJSONObject("body").getString("password"))
                assertEquals("", headers.optString("authorization"))
            } else {
                assertEquals(AUTHORIZATION, headers.getString("authorization"))
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

    private fun requireSilentChannel(state: JSONObject): JSONObject {
        assertTrue("The debug app's notifications are disabled", state.optBoolean("notificationsEnabled"))
        val channel = state.getJSONObject("channel")
        assertTrue("The product notification channel is missing", channel.optBoolean("exists"))
        val importance = channel.getInt("importance")
        val soundUriPresent = channel.optString("soundUri") != "null"
        val vibrationEnabled = channel.optBoolean("vibrationEnabled")
        val silent = !vibrationEnabled && when (importance) {
            IMPORTANCE_MIN, IMPORTANCE_LOW -> true
            IMPORTANCE_DEFAULT -> !soundUriPresent
            else -> false
        }
        assertTrue("Channel is not safe for a silent test: $channel", silent)
        return channel
    }

    private fun waitForActualForegroundAndConnectedService(
        automation: UiAutomation,
        context: android.content.Context,
        afterForegroundSequence: Long,
        expectedPackage: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var activePackage: String? = null
        var lastState = readProductState(context)
        var observedEvent = false
        do {
            activePackage = automation.rootInActiveWindow?.packageName?.toString()
            lastState = readProductState(context)
            observedEvent = lastState.getJSONArray("foregroundEvents").objects().any {
                it.optString("packageName") == expectedPackage && it.optLong("sequence") > afterForegroundSequence
            }
            if (activePackage == expectedPackage && observedEvent && lastState.optBoolean("serviceLeaseAttached")) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Subject app not observed with the real service connected: activePackage=$activePackage, state=$lastState, foregroundEventObserved=$observedEvent")
    }

    private fun launchActualSubjectApp(context: android.content.Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw AssertionError("Installed subject app has no visible launcher activity: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }

    private fun waitForNotification(context: android.content.Context, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitUntil(timeoutMs, "matching Android notification") {
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
        waitUntil(timeoutMs, "the requested product snapshot") { readProductState(context).takeIf(predicate) }

    private fun awaitFixtureState(apiBaseUrl: String, runId: String, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject =
        waitUntil(timeoutMs, "the matching fixture-server state") {
            fixtureRequest(apiBaseUrl, "GET", "/__ticket02/state").takeIf { it.optString("runId") == runId && predicate(it) }
        }

    private fun <T : Any> waitUntil(timeoutMs: Long, description: String, block: () -> T?): T {
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

    private fun subject(state: JSONObject, subjectName: String): JSONObject = state.getJSONObject("children").getJSONObject(CHILD_NAME)
        .getJSONObject("subjects").getJSONObject(subjectName)

    private fun isExpectedBaseline(state: JSONObject): Boolean = runCatching {
        subject(state, HANGEUL_SUBJECT).let { it.optString("status") == "BELOW_TARGET" && it.optInt("activityCount", -1) == 2 && it.optInt("targetCount", -1) == TARGET_HANGEUL } &&
            subject(state, MATH_SUBJECT).let { it.optString("status") == "COMPLETE" && it.optInt("activityCount", -1) == TARGET_MATH && it.optInt("targetCount", -1) == TARGET_MATH } &&
            subject(state, ENGLISH_SUBJECT).let { it.optString("status") == "BELOW_TARGET" && it.optInt("activityCount", -1) == 7 && it.optInt("targetCount", -1) == TARGET_ENGLISH }
    }.getOrDefault(false)

    private fun eventsAfter(events: JSONArray, sequence: Long): List<JSONObject> = events.objects().filter { it.optLong("sequence") > sequence }

    private fun lastSequence(events: JSONArray): Long = events.objects().maxOfOrNull { it.optLong("sequence") } ?: 0L

    private fun seoulDateNumber(): Int = LocalDate.now(ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toInt()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

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
        const val HANGEUL_USER_ID = "ticket01-hangeul"
        const val HANGEUL_REPORT_PATH = "/v3/todoschool/report/daily/hangeul"
        const val MATH_REPORT_PATH = "/v3/todoschool/report/daily/math"
        const val ENGLISH_REPORT_PATH = "/v3/todoschool/report/daily/english"
        const val HANGEUL_CATALOG_PATH = "/v3/todohangeul/user"
        const val MATH_CATALOG_PATH = "/v3/math/user"
        const val ENGLISH_CATALOG_PATH = "/v3/english/user"
        const val SIGN_IN_PATH = "/v3/account/signin"
        const val CHANNEL_ID = "learning-monitor-status"
        const val RESULT_LOG_TAG = "Ticket02E2E"
        const val TARGET_HANGEUL = 6
        const val TARGET_MATH = 5
        const val TARGET_ENGLISH = 8
        const val LOOPBACK_PREFIX = "http://127.0.0.1:"
        const val EDIT_TEXT = "android.widget.EditText"
        const val TIMEOUT_MS = 30_000L
        const val POLL_TIMEOUT_MS = 125_000L
        const val LOGIN_FORM_TIMEOUT_MS = 2_000L
        const val SERVER_TIMEOUT_MS = 3_000
        const val POLL_INTERVAL_MS = 100L
        const val IMPORTANCE_MIN = 1
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_DEFAULT = 3
        val CATALOG_PATHS = setOf(HANGEUL_CATALOG_PATH, MATH_CATALOG_PATH, ENGLISH_CATALOG_PATH)
        val REPORT_PATHS = setOf(HANGEUL_REPORT_PATH, MATH_REPORT_PATH, ENGLISH_REPORT_PATH)
    }
}
