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

/** Shared transport and polling helpers for the observer's device-level scenarios. */
internal object ObserverTestSupport {
    private const val PROVIDER_AUTHORITY = "com.example.todoschooltime.debug.monitor-diagnostics"
    private const val PROVIDER_METHOD_SNAPSHOT = "snapshot"
    private const val PROVIDER_EXTRA_SNAPSHOT_JSON = "snapshotJson"

    fun readProductState(context: Context): JSONObject {
        val result = context.contentResolver.call(PROVIDER_AUTHORITY, PROVIDER_METHOD_SNAPSHOT, null, Bundle())
            ?: throw AssertionError("The debug read-only diagnostics provider returned no result")
        val serialized = result.getString(PROVIDER_EXTRA_SNAPSHOT_JSON)
            ?: throw AssertionError("The debug diagnostics provider omitted its snapshot")
        return JSONObject(serialized)
    }

    fun fixtureRequest(
        apiBaseUrl: String,
        method: String,
        path: String,
        timeoutMs: Int,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = URL("${apiBaseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
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
            if (status !in 200..299) throw AssertionError("Fixture request $path returned HTTP $status: $response")
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
        throw AssertionError("Timed out waiting for $description")
    }
}

internal fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) add(getJSONObject(index))
}

internal fun JSONArray.strings(): List<String> = buildList {
    for (index in 0 until length()) add(getString(index))
}

internal fun eventsAfter(events: JSONArray, sequence: Long): List<JSONObject> =
    events.objects().filter { it.optLong("sequence") > sequence }

internal fun lastSequence(events: JSONArray): Long =
    events.objects().maxOfOrNull { it.optLong("sequence") } ?: 0L

internal fun seoulDateNumber(): Int =
    LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
