package com.example.todoschooltime.e2eobserver

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Shared observations at the app UI, fixture HTTP boundary, and Android notification manager. */
internal object ObserverTestSupport {
    private const val PROVIDER_AUTHORITY = "com.example.todoschooltime.debug.notification-observer"
    private const val PROVIDER_METHOD = "notificationSnapshot"
    private const val PROVIDER_EXTRA_JSON = "notificationSnapshotJson"
    private const val IMPORTANCE_LOW = 2

    const val CHILD_NAME = "테스트 아이"
    const val TEST_EMAIL = "ticket01@example.test"
    const val TEST_PASSWORD = "not-a-real-password"
    const val ACCOUNT_ID = "ticket01-fake-account"
    const val AUTHORIZATION = "Bearer ticket01-fake-token"
    const val HANGEUL_SUBJECT = "한글"
    const val MATH_SUBJECT = "수학"
    const val ENGLISH_SUBJECT = "영어"
    const val HANGEUL_USER_ID = "ticket01-hangeul"
    const val MATH_USER_ID = "ticket01-math"
    const val ENGLISH_USER_ID = "ticket01-english"
    const val SIGN_IN_PATH = "/v3/account/signin"
    const val HANGEUL_CATALOG_PATH = "/v3/todohangeul/user"
    const val MATH_CATALOG_PATH = "/v3/math/user"
    const val ENGLISH_CATALOG_PATH = "/v3/english/user"
    const val HANGEUL_REPORT_PATH = "/v3/todoschool/report/daily/hangeul"
    const val MATH_REPORT_PATH = "/v3/todoschool/report/daily/math"
    const val ENGLISH_REPORT_PATH = "/v3/todoschool/report/daily/english"

    val catalogPaths = setOf(HANGEUL_CATALOG_PATH, MATH_CATALOG_PATH, ENGLISH_CATALOG_PATH)
    val reportPaths = setOf(HANGEUL_REPORT_PATH, MATH_REPORT_PATH, ENGLISH_REPORT_PATH)
    val baselinePaths = setOf(SIGN_IN_PATH) + catalogPaths + reportPaths

    fun readNotificationSnapshot(context: Context): JSONObject {
        val result = context.contentResolver.call(PROVIDER_AUTHORITY, PROVIDER_METHOD, null, Bundle())
            ?: throw AssertionError("The debug notification-observation provider returned no result")
        val serialized = result.getString(PROVIDER_EXTRA_JSON)
            ?: throw AssertionError("The notification provider omitted notificationSnapshotJson")
        return JSONObject(serialized)
    }

    fun fixtureRequest(
        apiBaseUrl: String,
        method: String,
        path: String,
        timeoutMs: Int,
        body: JSONObject? = null,
    ): JSONObject {
        val allowedBaseUrl = FixtureServerUrl.requireAllowed(apiBaseUrl)
        require(path.startsWith("/") && !path.startsWith("//")) { "Fixture path must be an absolute local path" }
        val connection = URL(allowedBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw AssertionError("Fixture request " + path + " returned HTTP " + status + ": " + response)
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    fun <T : Any> waitForValue(
        timeoutMs: Long,
        pollIntervalMs: Long,
        description: String,
        block: () -> T?,
    ): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            block()?.let { return it }
            SystemClock.sleep(pollIntervalMs)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for " + description)
    }

    fun awaitFixtureState(
        apiBaseUrl: String,
        statePath: String,
        runId: String,
        timeoutMs: Long,
        pollIntervalMs: Long = 250L,
        predicate: (JSONObject) -> Boolean,
    ): JSONObject =
        waitForValue(timeoutMs, pollIntervalMs, "fixture state matching run " + runId) {
            fixtureRequest(apiBaseUrl, "GET", statePath, timeoutMs = 5_000)
                .takeIf { it.optString("runId") == runId && predicate(it) }
        }

    fun activeNotifications(snapshot: JSONObject): List<JSONObject> =
        snapshot.getJSONArray("activeNotifications").objects()

    fun waitForNotification(
        context: Context,
        timeoutMs: Long,
        pollIntervalMs: Long = 250L,
        predicate: (JSONObject) -> Boolean,
    ): JSONObject =
        waitForValue(timeoutMs, pollIntervalMs, "matching active Android notification") {
            activeNotifications(readNotificationSnapshot(context)).firstOrNull(predicate)
        }

    fun requireNotificationReady(snapshot: JSONObject) {
        assertCondition(
            snapshot.optBoolean("notificationsEnabled"),
            "Observer device is not prepared: enable Android notifications before running the automated E2E scenario",
        )
        assertCondition(
            snapshot.optBoolean("postNotificationsPermissionGranted"),
            "Observer device is not prepared: grant POST_NOTIFICATIONS before running the automated E2E scenario",
        )
        val channel = snapshot.optJSONObject("channel")
            ?: throw AssertionError("Observer device is not prepared: notification channel state is unavailable: " + snapshot)
        assertCondition(channel.optBoolean("exists"), "Observer device is not prepared: create the learning-status notification channel")
        assertCondition(channel.optInt("importance", -1) > 0, "Observer device is not prepared: enable the learning-status notification channel")
    }

    /**
     * Checks a quiet-channel device precondition so automated E2E runs do not make sound or heads-up popups.
     * This is not an assertion of the product's notification-channel default or policy.
     */
    fun requireSilentChannel(snapshot: JSONObject): JSONObject {
        requireNotificationReady(snapshot)
        val channel = snapshot.getJSONObject("channel")
        val quiet = !channel.optBoolean("vibrationEnabled") &&
            channel.optInt("importance", -1) <= IMPORTANCE_LOW
        assertCondition(
            quiet,
            "Observer device is not prepared: set the learning-status channel to Low (pop-up off) and vibration off before running the automated E2E scenario: " + channel,
        )
        return channel
    }

    fun requests(state: JSONObject): List<JSONObject> = state.getJSONArray("requests").objects()

    fun reportRequests(state: JSONObject): List<JSONObject> =
        requests(state).filter { it.optString("path") in reportPaths }

    fun assertFixtureRequestContract(requests: List<JSONObject>) {
        requests.forEach { request ->
            val path = request.getString("path")
            assertCondition(path in baselinePaths, "Unexpected product HTTP request path: " + path)
            assertCondition(request.optString("method") == "POST", "Expected POST request for " + path)
            assertCondition(request.optInt("responseStatus", -1) >= 0, "Fixture has not completed request " + request)
            val headers = request.getJSONObject("headers")
            assertCondition(headers.optString("content-type").startsWith("application/json"), "Missing JSON request content type for " + path)
            val body = request.getJSONObject("body")
            when (path) {
                SIGN_IN_PATH -> {
                    assertCondition(body.optString("email") == TEST_EMAIL, "Unexpected fixture sign-in email")
                    assertCondition(body.optString("password") == TEST_PASSWORD, "Unexpected fixture sign-in password")
                    assertCondition(headers.optString("authorization").isEmpty(), "Sign-in unexpectedly used a bearer token")
                }
                in catalogPaths -> {
                    assertCondition(headers.optString("authorization") == AUTHORIZATION, "Catalog request used the wrong fixture account")
                    assertCondition(body.optJSONObject("user")?.optString("accountId") == ACCOUNT_ID, "Catalog request omitted the fake account")
                }
                in reportPaths -> {
                    val expectedUserId = when (path) {
                        HANGEUL_REPORT_PATH -> HANGEUL_USER_ID
                        MATH_REPORT_PATH -> MATH_USER_ID
                        else -> ENGLISH_USER_ID
                    }
                    assertCondition(headers.optString("authorization") == AUTHORIZATION, "Report request used the wrong fixture account")
                    assertCondition(body.optString("userId") == expectedUserId, "Report request used the wrong subject account for " + path)
                    assertCondition(body.optInt("yyyymmdd", -1) == seoulDateNumber(), "Report request did not use today's Asia/Seoul date")
                    assertCondition(body.optString("languageCode") == "ko", "Report request used the wrong language")
                }
            }
        }
    }

    fun assertReportResponse(request: JSONObject, expectedCount: Int) {
        assertCondition(request.optInt("responseStatus", -1) == 200, "Expected successful report response: " + request)
        assertCondition(request.optInt("servedReportCount", -1) == expectedCount, "Unexpected report count: " + request)
    }

    fun awaitNoNewRequests(
        apiBaseUrl: String,
        statePath: String,
        runId: String,
        previousSequence: Long,
        durationMs: Long,
        pollIntervalMs: Long = 250L,
    ): JSONObject {
        val deadline = SystemClock.uptimeMillis() + durationMs
        var state = fixtureRequest(apiBaseUrl, "GET", statePath, timeoutMs = 5_000)
        assertCondition(state.optString("runId") == runId, "Fixture run ID changed")
        assertCondition(state.optLong("sequence") == previousSequence, "Fixture received another product HTTP request: " + requests(state).lastOrNull())
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(minOf(pollIntervalMs, deadline - SystemClock.uptimeMillis()))
            state = fixtureRequest(apiBaseUrl, "GET", statePath, timeoutMs = 5_000)
            assertCondition(state.optString("runId") == runId, "Fixture run ID changed")
            assertCondition(
                state.optLong("sequence") == previousSequence,
                "Fixture received another product HTTP request during a quiet interval: " + requests(state).lastOrNull(),
            )
        }
        return state
    }

    fun seoulDateNumber(): Int =
        LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE).toInt()

    private fun assertCondition(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

}

internal fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) add(getJSONObject(index))
}
