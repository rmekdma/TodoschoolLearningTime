package com.example.todoschooltime

import java.time.LocalDate
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class LearningMonitorFixture(
    reports: Map<String, LearningRepositoryResult<SubjectReport>>,
    targets: Map<String, Int> = emptyMap(),
    catalogFailure: Exception? = null,
    signedInAccount: String = "account-A",
    storedChildren: Set<String> = emptySet(),
    reportGate: (ChildSubjectSubscription) -> Unit = {},
) {
    private val configuredTargets = targets.toMutableMap()
    val commands = ManualQueue()
    val workers = ManualQueue()
    val ui = ManualQueue()
    val clock = FakeClock()
    val timers = ManualTimerFactory(clock)
    val dateTimers = ManualTimerFactory(clock)
    val repository = FakeRepository(reports, catalogFailure, signedInAccount, reportGate)
    val notifications = FakeNotifications()
    val store = FakeMonitorStore(storedChildren)
    val executionStatus = FakeExecutionStatus()
    val engine = LearningMonitorEngine(
        commandQueue = commands,
        workerQueue = workers,
        callbackQueue = ui,
        clock = clock,
        timerFactory = timers,
        store = store,
        repository = repository,
        targetProvider = MonitorTargetProvider { child, subject ->
            if (subject == "한글" && child == "철수" && configuredTargets[subject] == null) {
                TargetDefaults.defaultCount(subject)
            } else {
                configuredTargets[subject] ?: TargetDefaults.defaultCount(subject)
            }
        },
        notificationSink = notifications,
        executionStatusProbe = MonitorExecutionStatusProbe { executionStatus.inspect() },
        dateTimerFactory = dateTimers,
    )

    fun startAndRegister(child: String) {
        prepareRuntime()
        engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        engine.registerChild("account-A", child)
        commands.drain()
        drainAll()
    }

    fun startRuntimeAndRegister(child: String) {
        prepareRuntime()
        engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        engine.registerChild("account-A", child)
        commands.drain()
    }

    fun prepareRuntime() {
        executionStatus.set(accessibilityEnabled = true, notificationsEnabled = true)
        engine.executionConditionsChanged(accessibilityEnabled = true, notificationsEnabled = true)
        engine.attachServiceLease()
        commands.drain()
    }

    fun setTarget(subjectName: String, targetCount: Int) {
        configuredTargets[subjectName] = targetCount
    }

    fun startWorkerUntil(signal: CountDownLatch): Thread {
        while (!workers.isEmpty()) {
            val thread = Thread(workers.takeNext(), "monitor-test-worker")
            thread.start()
            if (signal.await(100, TimeUnit.MILLISECONDS)) return thread
            thread.join(5_000)
            check(!thread.isAlive) { "A worker did not reach a controllable checkpoint" }
            commands.drain()
        }
        throw AssertionError("No worker reached the requested checkpoint")
    }

    fun runWorkerAndCommands() {
        workers.takeNext().invoke()
        commands.drain()
    }

    fun drainAll() {
        while (!commands.isEmpty() || !workers.isEmpty()) {
            commands.drain()
            workers.drain()
            commands.drain()
        }
    }
}

internal class FakeExecutionStatus {
    @Volatile private var value = MonitorExecutionStatus(accessibilityEnabled = false, notificationsEnabled = false)

    fun set(accessibilityEnabled: Boolean, notificationsEnabled: Boolean) {
        value = MonitorExecutionStatus(accessibilityEnabled, notificationsEnabled)
    }

    fun inspect(): MonitorExecutionStatus = value
}

internal class ManualQueue : MonitorTaskQueue {
    private val tasks = ArrayDeque<() -> Unit>()
    override fun enqueue(task: () -> Unit) { tasks.addLast(task) }
    fun size(): Int = tasks.size
    fun isEmpty(): Boolean = tasks.isEmpty()
    fun takeNext(): () -> Unit = tasks.removeFirst()
    fun drain() {
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
    }
}

internal class FakeClock : MonitorClock {
    private var nowMillis = 0L
    var todayValue: LocalDate = LocalDate.of(2026, 10, 10)
    var dateChangeDelayMillis: Long = 86_400_000L
    override fun today(): LocalDate = todayValue
    var checkedAtValue = "마지막 확인 10:15"
    override fun checkedAt(): String = checkedAtValue
    override fun monotonicMillis(): Long = nowMillis
    override fun millisUntilNextSeoulMidnight(): Long = dateChangeDelayMillis
    fun advanceBy(millis: Long) { nowMillis += millis }
}

internal class ManualTimerFactory(private val clock: FakeClock) : MonitorTimerFactory {
    private data class PendingTimer(
        val dueAtMillis: Long,
        val callback: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val timers = mutableListOf<PendingTimer>()
    val scheduledDelays = mutableListOf<Long>()

    override fun schedule(delayMillis: Long, callback: () -> Unit): MonitorTimer {
        scheduledDelays += delayMillis
        val pending = PendingTimer(clock.monotonicMillis() + delayMillis, callback)
        timers += pending
        return MonitorTimer { pending.cancelled = true }
    }

    fun activeCount(): Int = timers.count { !it.cancelled }

    fun runDue() {
        while (true) {
            val next = timers.firstOrNull { !it.cancelled && it.dueAtMillis <= clock.monotonicMillis() } ?: return
            timers.remove(next)
            next.callback()
        }
    }
}

internal class FakeRepository(
    private val reports: Map<String, LearningRepositoryResult<SubjectReport>>,
    private val catalogFailure: Exception?,
    private val signedInAccount: String,
    private val reportGate: (ChildSubjectSubscription) -> Unit,
) : MonitorRepository {
    val reportedSubjects = mutableListOf<String>()
    val reportedPairs = mutableListOf<Pair<String, String>>()
    val requestedDates = mutableListOf<LocalDate>()
    var signInCalls = 0
    private val catalogResults = mutableListOf<LearningRepositoryResult<LearningCatalog>>()
    private val reportResults = mutableMapOf<Pair<String, String>, MutableList<LearningRepositoryResult<SubjectReport>>>()

    fun clearReports() {
        reportedSubjects.clear()
        reportedPairs.clear()
        requestedDates.clear()
    }

    fun enqueueCatalogResult(result: LearningRepositoryResult<LearningCatalog>) {
        catalogResults += result
    }

    fun enqueueReportResult(
        childName: String,
        subjectName: String,
        result: LearningRepositoryResult<SubjectReport>,
    ) {
        reportResults.getOrPut(childName to subjectName, ::mutableListOf).add(result)
    }

    fun clearQueuedReportResult(childName: String, subjectName: String) {
        reportResults.remove(childName to subjectName)
    }

    override fun signIn(email: String, password: String): LearningRepositoryResult<LearningSession> {
        signInCalls += 1
        return LearningRepositoryResult.Success(LearningSession(signedInAccount, "token"))
    }

    override fun loadCatalog(session: LearningSession): LearningRepositoryResult<LearningCatalog> {
        if (catalogResults.isNotEmpty()) return catalogResults.removeAt(0)
        return if (catalogFailure == null) LearningRepositoryResult.Success(defaultCatalog())
        else LearningRepositoryResult.TemporaryFailure(
            catalogFailure.let { LearningRepositoryException(it.message.orEmpty(), it) },
        )
    }

    override fun loadSubjectReport(
        session: LearningSession,
        subscription: ChildSubjectSubscription,
        date: LocalDate,
    ): LearningRepositoryResult<SubjectReport> {
        reportedSubjects += subscription.subjectName
        reportedPairs += subscription.childName to subscription.subjectName
        requestedDates += date
        reportGate(subscription)
        val queued = reportResults[subscription.childName to subscription.subjectName]
        val nextQueued = queued?.takeIf { it.isNotEmpty() }?.removeAt(0)
        return nextQueued ?: reports[subscription.subjectName]?.let { result ->
            when (result) {
                is LearningRepositoryResult.Success -> LearningRepositoryResult.Success(
                    result.value.copy(
                        childName = subscription.childName,
                        subjectName = subscription.subjectName,
                    ),
                )
                is LearningRepositoryResult.TemporaryFailure -> result
                is LearningRepositoryResult.AuthenticationDenied -> result
            }
        } ?: LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("report unavailable"))
    }

    private fun defaultCatalog() = LearningCatalog(
        childOrder = listOf("철수", "영희"),
        subscriptions = listOf("한글", "수학", "영어").map {
            ChildSubjectSubscription("철수", it, "철수-$it")
        } + listOf("한글", "수학", "영어").map {
            ChildSubjectSubscription("영희", it, "영희-$it")
        },
    )
}

internal class FakeNotifications : ChildNotificationSink {
    data class Update(
        val accountId: String,
        val child: MonitorChildSnapshot,
        val completion: Boolean,
        val completionSubjectName: String?,
    )
    val updates = mutableListOf<Update>()
    val cancellations = mutableListOf<Pair<String, String>>()
    override fun showStatus(
        accountId: String,
        child: MonitorChildSnapshot,
        completion: Boolean,
        completionSubjectName: String?,
    ) {
        updates += Update(accountId, child, completion, completionSubjectName)
    }
    override fun cancel(accountId: String, childName: String) { cancellations += accountId to childName }
}

internal class FakeMonitorStore(storedChildren: Set<String> = emptySet()) : MonitorStore {
    private val children = mutableMapOf<String, Set<String>>()
    private val intervals = mutableMapOf<String, BigInteger>()
    init { children["account-A"] = storedChildren.toSet() }
    override fun registeredChildren(accountId: String): Set<String> = children[accountId].orEmpty().toSet()
    override fun saveRegisteredChildren(accountId: String, children: Set<String>) {
        this.children[accountId] = children.toSet()
    }
    override fun intervalMinutes(accountId: String): BigInteger = intervals[accountId] ?: BigInteger.ONE
    override fun saveIntervalMinutes(accountId: String, minutes: BigInteger) {
        intervals[accountId] = minutes
    }
}

internal fun assertUnmodifiable(value: Any) {
    try {
        when (value) {
            is MutableMap<*, *> -> (value as MutableMap<Any?, Any?>).clear()
            is MutableSet<*> -> (value as MutableSet<Any?>).clear()
            else -> throw AssertionError("Expected an unmodifiable collection")
        }
        throw AssertionError("Expected an unmodifiable collection")
    } catch (_: UnsupportedOperationException) {
        // Snapshot collections must remain read-only even when cast through the JVM interface.
    }
}
