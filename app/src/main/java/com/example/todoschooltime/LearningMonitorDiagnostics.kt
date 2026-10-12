package com.example.todoschooltime

import android.os.SystemClock

/** Read-only debug evidence for distinguishing engine intent from Android notification state. */
internal class LearningMonitorDiagnostics(
    private val enabled: Boolean,
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    internal data class NotificationEvent(
        val sequence: Long,
        val accountId: String,
        val childName: String,
        val completionRequested: Boolean,
        val completionSubjectName: String?,
        val notificationId: Int,
        val notificationManagerCallReturned: Boolean? = null,
    )

    internal data class ForegroundEvent(
        val sequence: Long,
        val packageName: String?,
        val elapsedRealtimeMs: Long,
    )

    internal data class HttpRequestDispatchEvent(
        val sequence: Long,
        val path: String,
        val elapsedRealtimeMs: Long,
        val foregroundEventSequence: Long,
        val foregroundPackage: String?,
        val foregroundEventElapsedRealtimeMs: Long,
        val serviceLeaseAttached: Boolean,
        val serviceLeaseObservedAtElapsedRealtimeMs: Long,
    )

    private val lock = Any()
    private var nextNotificationSequence = 0L
    private var nextForegroundSequence = 0L
    private var nextHttpRequestDispatchSequence = 0L
    private var serviceLeaseAttached = false
    private var serviceLeaseObservedAtElapsedRealtimeMs = 0L
    private val notificationEvents = ArrayDeque<NotificationEvent>()
    private val foregroundEvents = ArrayDeque<ForegroundEvent>()
    private val httpRequestDispatchEvents = ArrayDeque<HttpRequestDispatchEvent>()

    fun notificationCursor(): Long = synchronized(lock) { nextNotificationSequence }

    fun foregroundCursor(): Long = synchronized(lock) { nextForegroundSequence }

    fun notificationEventsAfter(sequence: Long): List<NotificationEvent> = synchronized(lock) {
        notificationEvents.filter { it.sequence > sequence }
    }

    fun foregroundEventsAfter(sequence: Long): List<ForegroundEvent> = synchronized(lock) {
        foregroundEvents.filter { it.sequence > sequence }
    }

    fun httpRequestDispatchCursor(): Long = synchronized(lock) { nextHttpRequestDispatchSequence }

    fun httpRequestDispatchEventsAfter(sequence: Long): List<HttpRequestDispatchEvent> = synchronized(lock) {
        httpRequestDispatchEvents.filter { it.sequence > sequence }
    }

    fun serviceLeaseChanged(attached: Boolean) {
        if (!enabled) return
        synchronized(lock) {
            serviceLeaseAttached = attached
            currentElapsedRealtimeMs()?.let { serviceLeaseObservedAtElapsedRealtimeMs = it }
        }
    }

    fun notificationRequested(
        accountId: String,
        childName: String,
        completion: Boolean,
        completionSubjectName: String?,
        notificationId: Int,
    ): Long {
        if (!enabled) return 0L
        return synchronized(lock) {
            val sequence = ++nextNotificationSequence
            notificationEvents.addLast(
                NotificationEvent(
                    sequence = sequence,
                    accountId = accountId,
                    childName = childName,
                    completionRequested = completion,
                    completionSubjectName = completionSubjectName,
                    notificationId = notificationId,
                ),
            )
            trim(notificationEvents)
            sequence
        }
    }

    fun notificationManagerCallReturned(sequence: Long, returned: Boolean) {
        if (!enabled || sequence <= 0L) return
        synchronized(lock) {
            replaceNotificationEvent(sequence) { it.copy(notificationManagerCallReturned = returned) }
        }
    }

    fun foregroundPackageObserved(packageName: String?) {
        if (!enabled) return
        synchronized(lock) {
            val elapsedRealtimeMs = currentElapsedRealtimeMs() ?: return
            foregroundEvents.addLast(ForegroundEvent(++nextForegroundSequence, packageName, elapsedRealtimeMs))
            trim(foregroundEvents)
        }
    }

    /** Captures the latest foreground callback and service-lease state immediately before HTTP body dispatch. */
    fun httpRequestDispatching(path: String): Long {
        if (!enabled) return 0L
        return synchronized(lock) {
            val elapsedRealtimeMs = currentElapsedRealtimeMs() ?: return@synchronized 0L
            val foregroundEvent = foregroundEvents.lastOrNull()
            val sequence = ++nextHttpRequestDispatchSequence
            httpRequestDispatchEvents.addLast(
                HttpRequestDispatchEvent(
                    sequence = sequence,
                    path = path,
                    elapsedRealtimeMs = elapsedRealtimeMs,
                    foregroundEventSequence = foregroundEvent?.sequence ?: 0L,
                    foregroundPackage = foregroundEvent?.packageName,
                    foregroundEventElapsedRealtimeMs = foregroundEvent?.elapsedRealtimeMs ?: 0L,
                    serviceLeaseAttached = serviceLeaseAttached,
                    serviceLeaseObservedAtElapsedRealtimeMs = serviceLeaseObservedAtElapsedRealtimeMs,
                ),
            )
            trim(httpRequestDispatchEvents)
            sequence
        }
    }

    private fun currentElapsedRealtimeMs(): Long? = runCatching(monotonicMillis).getOrNull()

    private fun replaceNotificationEvent(sequence: Long, transform: (NotificationEvent) -> NotificationEvent) {
        val existing = notificationEvents.indexOfFirst { it.sequence == sequence }
        if (existing >= 0) notificationEvents[existing] = transform(notificationEvents[existing])
    }

    private fun <T> trim(events: ArrayDeque<T>) {
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    private companion object {
        const val MAX_EVENTS = 128
    }
}
