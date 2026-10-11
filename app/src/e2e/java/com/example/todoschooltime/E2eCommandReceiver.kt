package com.example.todoschooltime

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** E2E-only ADB entry point. The manifest for the regular app variants does not declare it. */
class E2eCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val request = E2ePendingRequest(pendingResult)
        request.startTimeout()
        val app = context.applicationContext as? E2eTodoSchoolApplication
        if (app == null) {
            request.complete(1, E2eCommandService.errorResponse("wrong_application", "E2E application is not active."))
            return
        }
        E2eCommandDispatcher.submit(app, intent, request)
    }
}

private class E2ePendingRequest(
    private val pendingResult: BroadcastReceiver.PendingResult,
) {
    private val completed = AtomicBoolean(false)
    private val subscription = AtomicReference<MonitorSubscription?>()
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        complete(
            1,
            E2eCommandService.errorResponse("command_timeout", "Timed out waiting for the engine command fence."),
        )
    }

    fun startTimeout() {
        timeoutHandler.postDelayed(timeout, COMMAND_TIMEOUT_MS)
    }

    fun attach(subscription: MonitorSubscription) {
        if (!this.subscription.compareAndSet(null, subscription)) subscription.close()
        if (completed.get()) this.subscription.getAndSet(null)?.close()
    }

    fun complete(resultCode: Int, json: String) {
        if (!completed.compareAndSet(false, true)) return
        timeoutHandler.removeCallbacks(timeout)
        subscription.getAndSet(null)?.close()
        try {
            pendingResult.setResultCode(resultCode)
            pendingResult.setResultData(encodeResponse(json))
        } finally {
            pendingResult.finish()
        E2eCommandDispatcher.completed(this)
        }
    }

    fun isComplete(): Boolean = completed.get()

    private fun encodeResponse(json: String): String = E2eWorldStore.RESPONSE_PREFIX +
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val COMMAND_TIMEOUT_MS = 8_000L
    }
}

/** Keeps separate ADB broadcasts ordered through their one-snapshot fence without waiting on a thread. */
private object E2eCommandDispatcher {
    private data class Work(
        val app: E2eTodoSchoolApplication,
        val intent: Intent,
        val pending: E2ePendingRequest,
    )

    private val lock = Any()
    private val queue = ArrayDeque<Work>()
    private var active: Work? = null
    private val executor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32),
        { runnable -> Thread(runnable, "e2e-command").apply { isDaemon = true } },
    )

    fun submit(app: E2eTodoSchoolApplication, intent: Intent, pending: E2ePendingRequest) {
        val accepted = synchronized(lock) {
            if (queue.size >= MAX_PENDING_COMMANDS) false else {
                queue.addLast(Work(app, intent, pending))
                true
            }
        }
        if (!accepted) {
            pending.complete(1, E2eCommandService.errorResponse("busy", "Too many E2E commands are pending."))
        } else {
            startNextIfIdle()
        }
    }

    fun completed(pending: E2ePendingRequest) {
        val releasedActive = synchronized(lock) {
            if (active?.pending === pending) {
                active = null
                true
            } else {
                queue.removeAll { it.pending === pending }
                false
            }
        }
        if (releasedActive) startNextIfIdle()
    }

    private fun startNextIfIdle() {
        val work = synchronized(lock) {
            if (active != null || queue.isEmpty()) null else {
                queue.removeFirst().also { active = it }
            }
        } ?: return
        executor.execute {
            if (work.pending.isComplete()) {
                completed(work.pending)
            } else {
                E2eCommandService(work.app, work.pending).execute(work.intent)
            }
        }
    }

    private const val MAX_PENDING_COMMANDS = 32
}

private class E2eCommandService(
    private val app: E2eTodoSchoolApplication,
    private val pending: E2ePendingRequest,
) {
    fun execute(intent: Intent) {
        try {
            val raw = decodeRequest(intent)
            dispatch(E2eCommandParser.parse(raw))
        } catch (exception: E2eCommandValidationException) {
            pending.complete(1, errorResponse("invalid_request", exception.message ?: "Invalid request."))
        } catch (_: Exception) {
            pending.complete(1, errorResponse("internal_error", "The E2E command could not be completed."))
        }
    }

    private fun dispatch(command: E2eCommand) {
        when (command) {
            E2eCommand.GetState -> afterFence(command.wireName()) { snapshot ->
                success(command.wireName(), snapshot, JSONObject().put("state", state(snapshot)))
            }
            E2eCommand.GetEvents -> afterFence(command.wireName()) { snapshot ->
                val events = app.e2eWorld.events()
                success(command.wireName(), snapshot, JSONObject().put("events", eventEnvelope(events)))
            }
            E2eCommand.ClearEvents -> afterFence(command.wireName()) { snapshot ->
                val events = app.e2eWorld.clearEvents()
                success(command.wireName(), snapshot, JSONObject().put("events", eventEnvelope(events)))
            }
            E2eCommand.Reset -> reset()
            is E2eCommand.SetReport -> {
                app.e2eWorld.setReport(
                    command.childId,
                    command.subjectCode,
                    E2eReport(command.count, command.outcome, command.delayMs),
                )
                afterFence(command.wireName()) { snapshot ->
                    success(
                        command.wireName(),
                        snapshot,
                        JSONObject()
                            .put("child", command.childId)
                            .put("subject", command.subjectCode)
                            .put("count", command.count)
                            .put("outcome", command.outcome.wireValue())
                            .put("delayMs", command.delayMs)
                            .put("takesEffect", "next_fake_report_request"),
                    )
                }
            }
            is E2eCommand.RegisterChild -> {
                app.learningMonitor.registerChild(E2eWorldStore.TEST_ACCOUNT_ID, app.e2eWorld.child(command.childId).name)
                afterFence(command.wireName()) { snapshot ->
                    success(command.wireName(), snapshot, JSONObject().put("child", command.childId))
                }
            }
            is E2eCommand.UnregisterChild -> {
                app.learningMonitor.unregisterChild(E2eWorldStore.TEST_ACCOUNT_ID, app.e2eWorld.child(command.childId).name)
                afterFence(command.wireName()) { snapshot ->
                    success(command.wireName(), snapshot, JSONObject().put("child", command.childId))
                }
            }
            is E2eCommand.SetInterval -> {
                app.learningMonitor.setIntervalMinutes(E2eWorldStore.TEST_ACCOUNT_ID, command.minutes)
                afterFence(command.wireName()) { snapshot ->
                    success(command.wireName(), snapshot, JSONObject().put("minutes", command.minutes.toString()))
                }
            }
            is E2eCommand.SetGoal -> setGoal(command)
            is E2eCommand.AdvanceDate -> {
                val date = app.e2eWorld.advanceDate(command.days)
                app.learningMonitorRuntime.reevaluateMonitoringDate()
                afterFence(command.wireName()) { snapshot ->
                    success(
                        command.wireName(),
                        snapshot,
                        JSONObject().put("date", date.toString()).put("source", "simulated_seoul_date"),
                    )
                }
            }
        }
    }

    private fun reset() {
        app.e2eWorld.resetFakeData()
        val targetStore = TargetCountStore(app)
        val childNames = app.e2eWorld.children().map(E2eChild::name)
        childNames.forEach { targetStore.saveTargetCounts(it, emptyMap()) }
        val registered = app.learningMonitor.snapshot().registeredChildren
        registered.forEach { app.learningMonitor.unregisterChild(E2eWorldStore.TEST_ACCOUNT_ID, it) }
        app.learningMonitor.setIntervalMinutes(E2eWorldStore.TEST_ACCOUNT_ID, java.math.BigInteger.ONE)
        afterFence("reset") { snapshot ->
            app.e2eWorld.clearEvents()
            success("reset", snapshot, JSONObject().put("state", state(snapshot)))
        }
    }

    private fun setGoal(command: E2eCommand.SetGoal) {
        val child = app.e2eWorld.child(command.childId)
        val store = TargetCountStore(app)
        val existing = store.getTargetCounts(child.name)
        val targetCounts = child.subjects.associate { subject ->
            subject.name to (
                command.targets[subject.code]
                    ?: existing[subject.name]
                    ?: SubjectStatusFormatter.defaultTargetCount(subject.name)
                )
        }
        store.saveTargetCounts(child.name, targetCounts)
        app.learningMonitor.setTargetCounts(E2eWorldStore.TEST_ACCOUNT_ID, child.name, targetCounts)
        afterFence(command.wireName()) { snapshot ->
            success(
                command.wireName(),
                snapshot,
                JSONObject().put("child", command.childId).put("targets", stringIntMap(targetCounts)),
            )
        }
    }

    /** LearningMonitor.observe captures its initial snapshot in the same serial command queue. */
    private fun afterFence(commandName: String, action: (LearningMonitorSnapshot) -> String) {
        val delivered = AtomicBoolean(false)
        val subscriptionRef = AtomicReference<MonitorSubscription?>()
        val subscription = app.learningMonitor.observe { snapshot ->
            if (!delivered.compareAndSet(false, true)) return@observe
            subscriptionRef.get()?.close()
            if (pending.isComplete()) return@observe
            WORKER.execute {
                try {
                    pending.complete(0, action(snapshot))
                } catch (_: Exception) {
                    pending.complete(1, errorResponse("internal_error", "The E2E command could not be completed."))
                }
            }
        }
        subscriptionRef.set(subscription)
        pending.attach(subscription)
        if (pending.isComplete()) subscription.close()
    }

    private fun success(command: String, snapshot: LearningMonitorSnapshot, details: JSONObject): String =
        JSONObject().apply {
            put("version", 1)
            put("ok", true)
            put("command", command)
            put("engineRevision", snapshot.revision)
            val keys = details.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, details.get(key))
            }
        }.toString()

    private fun state(snapshot: LearningMonitorSnapshot): JSONObject {
        val livePermissions = app.learningMonitorScreenUseCases.inspectExecutionStatus()
        val monitorChildren = JSONArray()
        snapshot.children.values.sortedBy(MonitorChildSnapshot::childName).forEach { child ->
            val subjects = JSONArray()
            child.subjects.values.forEach { subject ->
                subjects.put(
                    JSONObject()
                        .put("code", app.e2eWorld.children().flatMap(E2eChild::subjects)
                            .firstOrNull { it.name == subject.name }?.code ?: JSONObject.NULL)
                        .put("name", subject.name)
                        .put("status", subject.status.name)
                        .put("targetCount", subject.targetCount)
                        .put("activityCount", subject.activityCount ?: JSONObject.NULL)
                        .put("checkedAt", subject.checkedAt ?: JSONObject.NULL)
                        .put("error", subject.error ?: JSONObject.NULL),
                )
            }
            monitorChildren.put(
                JSONObject()
                    .put("child", app.e2eWorld.childIdForName(child.childName) ?: JSONObject.NULL)
                    .put("name", child.childName)
                    .put("subjects", subjects)
                    .put("lastCheckedAt", child.lastCheckedAt ?: JSONObject.NULL)
                    .put("error", child.error ?: JSONObject.NULL),
            )
        }

        val activeNotifications = activeNotifications()
        return JSONObject()
            .put(
                "date",
                JSONObject()
                    .put("value", app.e2eWorld.currentDate().toString())
                    .put("source", if (app.e2eWorld.isDateSimulated()) "simulated" else "Asia/Seoul_device_clock"),
            )
            .put("accountId", snapshot.accountId ?: JSONObject.NULL)
            .put(
                "monitor",
                JSONObject()
                    .put("revision", snapshot.revision)
                    .put("intervalMinutes", snapshot.intervalMinutes.toString())
                    .put("registeredChildren", JSONArray(snapshot.registeredChildren.sorted()))
                    .put("children", monitorChildren)
                    .put("serviceLeaseAttached", snapshot.serviceLeaseAttached)
                    .put("accessibilityEnabled", livePermissions.accessibilityEnabled)
                    .put("notificationsEnabled", livePermissions.notificationsEnabled)
                    .put("authenticationRequired", snapshot.authenticationRequired)
                    .put("monitoringPaused", snapshot.monitoringPaused)
                    .put("connectionWaiting", snapshot.connectionWaiting)
                    .put("foregroundObservationPending", snapshot.foregroundObservationPending),
            )
            .put("activeNotifications", activeNotifications)
            .put("fakeWorld", fakeWorld())
    }

    private fun fakeWorld(): JSONObject {
        val children = JSONArray()
        app.e2eWorld.children().forEach { child ->
            val subjects = JSONArray()
            child.subjects.forEach { subject ->
                val report = app.e2eWorld.report(child.id, subject.code)
                subjects.put(
                    JSONObject()
                        .put("code", subject.code)
                        .put("name", subject.name)
                        .put("count", report.count)
                        .put("outcome", report.outcome.wireValue())
                        .put("delayMs", report.delayMs),
                )
            }
            children.put(JSONObject().put("id", child.id).put("name", child.name).put("subjects", subjects))
        }
        return JSONObject().put("children", children).put("accountId", E2eWorldStore.TEST_ACCOUNT_ID)
    }

    private fun activeNotifications(): JSONObject {
        return try {
            val manager = app.getSystemService(NotificationManager::class.java)
            val items = manager.activeNotifications
                .asSequence()
                .filter { it.packageName == app.packageName }
                .sortedBy(StatusBarNotification::getId)
                .map(::notificationJson)
                .toList()
            JSONObject().put("available", true).put("items", JSONArray(items))
        } catch (_: Exception) {
            JSONObject().put("available", false).put("items", JSONArray())
        }
    }

    private fun notificationJson(statusBarNotification: StatusBarNotification): JSONObject {
        val notification = statusBarNotification.notification
        val extras = notification.extras
        return JSONObject()
            .put("id", statusBarNotification.id)
            .put("tag", statusBarNotification.tag ?: JSONObject.NULL)
            .put("channelId", notification.channelId ?: JSONObject.NULL)
            .put("title", extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: JSONObject.NULL)
            .put("text", extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: JSONObject.NULL)
            .put("ongoing", notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            .put("soundSet", notification.sound != null)
            .put("vibrationSet", notification.vibrate != null)
            .put("defaults", notification.defaults)
    }

    private fun eventEnvelope(events: E2eEvents): JSONObject = JSONObject()
        .put("items", events.events)
        .put("truncated", events.truncated)
        .put("lastSequence", events.lastSequence)

    private fun stringIntMap(values: Map<String, Int>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }

    private fun decodeRequest(intent: Intent): String {
        if (intent.action != E2eWorldStore.ACTION_COMMAND) throw E2eCommandValidationException("wrong command action")
        val extras = intent.extras ?: throw E2eCommandValidationException("request_b64 extra is required")
        if (extras.keySet() != setOf(REQUEST_B64_KEY)) {
            throw E2eCommandValidationException("only request_b64 is accepted")
        }
        val encoded = extras.get(REQUEST_B64_KEY) as? String
            ?: throw E2eCommandValidationException("request_b64 must be a string")
        if (encoded.length > MAX_ENCODED_REQUEST_CHARS) throw E2eCommandValidationException("request is too large")
        val bytes = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw E2eCommandValidationException("request_b64 is invalid base64url")
        }
        if (bytes.size > MAX_REQUEST_BYTES) throw E2eCommandValidationException("request is too large")
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw E2eCommandValidationException("request_b64 must decode to UTF-8")
        }
    }

    companion object {
        private const val REQUEST_B64_KEY = "request_b64"
        private const val MAX_ENCODED_REQUEST_CHARS = 87_384
        private const val MAX_REQUEST_BYTES = 65_536
        private val WORKER = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32),
            { runnable -> Thread(runnable, "e2e-command-result").apply { isDaemon = true } },
        )

        fun errorResponse(code: String, message: String): String = JSONObject()
            .put("version", 1)
            .put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()
    }
}

private fun E2eCommand.wireName(): String = when (this) {
    E2eCommand.GetState -> "getState"
    E2eCommand.GetEvents -> "getEvents"
    E2eCommand.ClearEvents -> "clearEvents"
    E2eCommand.Reset -> "reset"
    is E2eCommand.SetReport -> "setReport"
    is E2eCommand.RegisterChild -> "registerChild"
    is E2eCommand.UnregisterChild -> "unregisterChild"
    is E2eCommand.SetInterval -> "setInterval"
    is E2eCommand.SetGoal -> "setGoal"
    is E2eCommand.AdvanceDate -> "advanceDate"
}

private fun FakeReportOutcome.wireValue(): String = when (this) {
    FakeReportOutcome.SUCCESS -> "success"
    FakeReportOutcome.TEMPORARY_FAILURE -> "temporaryFailure"
    FakeReportOutcome.AUTHENTICATION_DENIED -> "authenticationDenied"
}
