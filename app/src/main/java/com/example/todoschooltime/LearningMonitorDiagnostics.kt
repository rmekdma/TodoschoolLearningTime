package com.example.todoschooltime

/** Read-only debug evidence for distinguishing engine intent from Android notification state. */
internal class LearningMonitorDiagnostics(private val enabled: Boolean) {
    internal data class NotificationEvent(
        val sequence: Long,
        val accountId: String,
        val childName: String,
        val completionRequested: Boolean,
        val completionSubjectName: String?,
        val notificationId: Int,
        val notificationManagerCallReturned: Boolean? = null,
    )

    internal data class ForegroundEvent(val sequence: Long, val packageName: String?)

    private val lock = Any()
    private var nextNotificationSequence = 0L
    private var nextForegroundSequence = 0L
    private val notificationEvents = ArrayDeque<NotificationEvent>()
    private val foregroundEvents = ArrayDeque<ForegroundEvent>()

    fun notificationCursor(): Long = synchronized(lock) { nextNotificationSequence }

    fun foregroundCursor(): Long = synchronized(lock) { nextForegroundSequence }

    fun notificationEventsAfter(sequence: Long): List<NotificationEvent> = synchronized(lock) {
        notificationEvents.filter { it.sequence > sequence }
    }

    fun foregroundEventsAfter(sequence: Long): List<ForegroundEvent> = synchronized(lock) {
        foregroundEvents.filter { it.sequence > sequence }
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
            foregroundEvents.addLast(ForegroundEvent(++nextForegroundSequence, packageName))
            trim(foregroundEvents)
        }
    }

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
