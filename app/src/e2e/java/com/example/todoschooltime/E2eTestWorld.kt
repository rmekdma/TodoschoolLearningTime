package com.example.todoschooltime

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

internal data class E2eChild(val id: String, val name: String, val subjects: List<E2eSubject>)
internal data class E2eSubject(val code: String, val name: String)
internal data class E2eReport(
    val count: Int,
    val outcome: FakeReportOutcome,
    val delayMs: Int,
)
internal data class E2eEvents(val events: JSONArray, val truncated: Boolean, val lastSequence: Long)

/** Persists only fake input data and a bounded audit trail; engine baseline state stays process-owned. */
internal class E2eWorldStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        synchronized(this) {
            if (preferences.getString(CONFIG_KEY, null) == null) writeDefaultConfiguration()
        }
    }

    fun children(): List<E2eChild> = listOf(
        E2eChild(CHILD_IDS[0], "테스트 아이 A", SUBJECTS),
        E2eChild(CHILD_IDS[1], "테스트 아이 B", SUBJECTS),
    )

    fun child(id: String): E2eChild = children().first { it.id == id }

    fun childIdForName(name: String): String? = children().firstOrNull { it.name == name }?.id

    @Synchronized
    fun report(childId: String, subjectCode: String): E2eReport {
        val key = reportKey(childId, subjectCode)
        val reports = JSONObject(preferences.getString(CONFIG_KEY, null) ?: defaultConfiguration())
            .optJSONObject(REPORTS_KEY) ?: JSONObject()
        val json = reports.optJSONObject(key) ?: defaultReport()
        val count = json.optInt("count", 0).coerceAtLeast(0)
        val outcome = when (json.optString("outcome")) {
            OUTCOME_TEMPORARY_FAILURE -> FakeReportOutcome.TEMPORARY_FAILURE
            OUTCOME_AUTHENTICATION_DENIED -> FakeReportOutcome.AUTHENTICATION_DENIED
            else -> FakeReportOutcome.SUCCESS
        }
        val delayMs = json.optInt("delayMs", 0).coerceIn(0, E2eCommandParser.MAX_DELAY_MS)
        return E2eReport(count, outcome, delayMs)
    }

    @Synchronized
    fun setReport(childId: String, subjectCode: String, report: E2eReport) {
        val root = configuration()
        val reports = root.optJSONObject(REPORTS_KEY) ?: JSONObject()
        reports.put(
            reportKey(childId, subjectCode),
            JSONObject()
                .put("count", report.count)
                .put("outcome", report.outcome.toWireValue())
                .put("delayMs", report.delayMs),
        )
        root.put(REPORTS_KEY, reports)
        preferences.edit().putString(CONFIG_KEY, root.toString()).apply()
    }

    @Synchronized
    fun currentDate(): LocalDate = preferences.getString(SIMULATED_DATE_KEY, null)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: DateHelper.today()

    @Synchronized
    fun isDateSimulated(): Boolean = preferences.contains(SIMULATED_DATE_KEY)

    @Synchronized
    fun advanceDate(days: Int): LocalDate {
        val nextDate = currentDate().plusDays(days.toLong())
        preferences.edit().putString(SIMULATED_DATE_KEY, nextDate.toString()).apply()
        return nextDate
    }

    @Synchronized
    fun resetFakeData() {
        writeDefaultConfiguration()
        preferences.edit().remove(SIMULATED_DATE_KEY).apply()
        clearEvents()
    }

    @Synchronized
    fun recordReportQuery(
        operation: String,
        childId: String? = null,
        subjectCode: String? = null,
        date: LocalDate? = null,
        outcome: String,
        delayMs: Int = 0,
    ) {
        appendEvent(JSONObject().apply {
            put("kind", "fake_query")
            put("operation", operation)
            put("child", childId ?: JSONObject.NULL)
            put("subject", subjectCode ?: JSONObject.NULL)
            put("date", date?.toString() ?: JSONObject.NULL)
            put("outcome", outcome)
            put("delayMs", delayMs)
        })
    }

    @Synchronized
    fun recordNotificationRequest(
        operation: String,
        childName: String,
        completionRequested: Boolean? = null,
        completionSubjectName: String? = null,
        summary: String? = null,
    ) {
        appendEvent(JSONObject().apply {
            put("kind", "notification_request")
            put("operation", operation)
            put("child", childIdForName(childName) ?: JSONObject.NULL)
            put("completionRequested", completionRequested ?: JSONObject.NULL)
            put("completionSubject", completionSubjectName ?: JSONObject.NULL)
            put("summary", summary ?: JSONObject.NULL)
        })
    }

    @Synchronized
    fun events(): E2eEvents = E2eEvents(
        events = loadEvents(),
        truncated = preferences.getBoolean(EVENTS_TRUNCATED_KEY, false),
        lastSequence = preferences.getLong(EVENT_SEQUENCE_KEY, 0L),
    )

    @Synchronized
    fun clearEvents(): E2eEvents {
        preferences.edit()
            .putString(EVENTS_KEY, JSONArray().toString())
            .putBoolean(EVENTS_TRUNCATED_KEY, false)
            .apply()
        return events()
    }

    private fun appendEvent(event: JSONObject) {
        val currentSequence = preferences.getLong(EVENT_SEQUENCE_KEY, 0L)
        val nextSequence = if (currentSequence == Long.MAX_VALUE) 1L else currentSequence + 1L
        event.put("sequence", nextSequence)
        val oldEvents = loadEvents()
        val keepFrom = (oldEvents.length() - MAX_EVENTS + 1).coerceAtLeast(0)
        val nextEvents = JSONArray()
        for (index in keepFrom until oldEvents.length()) nextEvents.put(oldEvents.getJSONObject(index))
        nextEvents.put(event)
        val wasTruncated = preferences.getBoolean(EVENTS_TRUNCATED_KEY, false) ||
            oldEvents.length() >= MAX_EVENTS
        preferences.edit()
            .putLong(EVENT_SEQUENCE_KEY, nextSequence)
            .putString(EVENTS_KEY, nextEvents.toString())
            .putBoolean(EVENTS_TRUNCATED_KEY, wasTruncated)
            .apply()
    }

    private fun loadEvents(): JSONArray = try {
        JSONArray(preferences.getString(EVENTS_KEY, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun configuration(): JSONObject = try {
        JSONObject(preferences.getString(CONFIG_KEY, null) ?: defaultConfiguration())
    } catch (_: Exception) {
        JSONObject(defaultConfiguration())
    }

    private fun writeDefaultConfiguration() {
        preferences.edit().putString(CONFIG_KEY, defaultConfiguration()).apply()
    }

    private fun defaultConfiguration(): String {
        val reports = JSONObject()
        CHILD_IDS.forEach { childId ->
            SUBJECT_CODES.forEach { subjectCode -> reports.put(reportKey(childId, subjectCode), defaultReport()) }
        }
        return JSONObject().put(REPORTS_KEY, reports).toString()
    }

    private fun defaultReport() = JSONObject()
        .put("count", 0)
        .put("outcome", OUTCOME_SUCCESS)
        .put("delayMs", 0)

    private fun reportKey(childId: String, subjectCode: String) = "$childId|$subjectCode"

    private fun FakeReportOutcome.toWireValue(): String = when (this) {
        FakeReportOutcome.SUCCESS -> OUTCOME_SUCCESS
        FakeReportOutcome.TEMPORARY_FAILURE -> OUTCOME_TEMPORARY_FAILURE
        FakeReportOutcome.AUTHENTICATION_DENIED -> OUTCOME_AUTHENTICATION_DENIED
    }

    companion object {
        const val PREFERENCES_NAME = "e2e_world"
        const val TEST_ACCOUNT_ID = "e2e-test-account"
        const val TEST_EMAIL = "e2e@example.invalid"
        const val TEST_PASSWORD = "e2e-only-password"
        const val ACTION_COMMAND = "com.example.todoschooltime.e2e.COMMAND"
        const val RESPONSE_PREFIX = "E2E1:"
        const val MAX_EVENTS = 500
        val CHILD_IDS = listOf("child-a", "child-b")
        val SUBJECT_CODES = listOf("hangeul", "math", "english")

        const val CHILD_A_NAME = "테스트 아이 A"
        const val CHILD_B_NAME = "테스트 아이 B"

        val SUBJECTS = listOf(
            E2eSubject("hangeul", "한글"),
            E2eSubject("math", "수학"),
            E2eSubject("english", "영어"),
        )

        private const val CONFIG_KEY = "config"
        private const val REPORTS_KEY = "reports"
        private const val SIMULATED_DATE_KEY = "simulatedSeoulDate"
        private const val EVENTS_KEY = "events"
        private const val EVENT_SEQUENCE_KEY = "eventSequence"
        private const val EVENTS_TRUNCATED_KEY = "eventsTruncated"
        private const val OUTCOME_SUCCESS = "success"
        private const val OUTCOME_TEMPORARY_FAILURE = "temporaryFailure"
        private const val OUTCOME_AUTHENTICATION_DENIED = "authenticationDenied"
    }
}

internal class E2eMonitorClock(private val world: E2eWorldStore) : MonitorClock {
    override fun today(): LocalDate = world.currentDate()

    override fun checkedAt(): String {
        val checkedAt = if (world.isDateSimulated()) {
            world.currentDate().atTime(12, 0).atZone(ZoneId.of("Asia/Seoul"))
        } else {
            DateHelper.nowZoned()
        }
        return DateHelper.formatCheckedAt(checkedAt)
    }

    override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()

    override fun millisUntilNextSeoulMidnight(): Long = if (world.isDateSimulated()) {
        24L * 60L * 60L * 1_000L
    } else {
        DateHelper.millisUntilNextSeoulMidnight()
    }
}
