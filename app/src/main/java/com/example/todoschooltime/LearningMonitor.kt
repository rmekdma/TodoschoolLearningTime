package com.example.todoschooltime

import java.time.LocalDate
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.math.BigInteger

object MonitorIntervalParser {
    fun parse(input: String): BigInteger? {
        val parsed = input.trim().takeIf(String::isNotEmpty)?.let { runCatching { BigInteger(it) }.getOrNull() }
        return parsed?.takeIf { it.signum() > 0 }
    }
}

enum class MonitorSubjectStatus {
    CONFIRMATION_PENDING,
    BELOW_TARGET,
    COMPLETE,
}

data class MonitorSubjectSnapshot(
    val name: String,
    val status: MonitorSubjectStatus,
    val targetCount: Int,
    val activityCount: Int? = null,
    val checkedAt: String? = null,
    val error: String? = null,
)

data class MonitorChildSnapshot(
    val childName: String,
    val subjects: Map<String, MonitorSubjectSnapshot>,
    val lastCheckedAt: String? = null,
    val error: String? = null,
)

data class LearningMonitorSnapshot(
    val accountId: String? = null,
    val intervalMinutes: java.math.BigInteger = java.math.BigInteger.ONE,
    val registeredChildren: Set<String> = emptySet(),
    val children: Map<String, MonitorChildSnapshot> = emptyMap(),
    val serviceLeaseAttached: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val authenticationRequired: Boolean = false,
    val monitoringPaused: Boolean = false,
    val connectionWaiting: Boolean = false,
    val foregroundObservationPending: Boolean = false,
    val revision: Long = 0,
)

object MonitorNotificationText {
    fun title(child: MonitorChildSnapshot, completionSubjectName: String? = null): String =
        completionSubjectName?.let { "${child.childName} · $it 완료" } ?: "${child.childName} 학습 상태"

    fun body(child: MonitorChildSnapshot): String = buildList {
        val subjectLines = child.subjects.values.map { subject ->
            val state = when (subject.status) {
                MonitorSubjectStatus.CONFIRMATION_PENDING -> "확인 대기"
                MonitorSubjectStatus.BELOW_TARGET -> "미달성 ${subject.activityCount ?: "?"}/${subject.targetCount}"
                MonitorSubjectStatus.COMPLETE -> if (subject.activityCount == null) {
                    "완료 · 목표 0"
                } else {
                    "완료 ${subject.activityCount}/${subject.targetCount}"
                }
            }
            if (subject.status == MonitorSubjectStatus.COMPLETE) {
                "✔ ${subject.name} $state"
            } else {
                "${subject.name} $state"
            }
        }
        if (subjectLines.isEmpty()) add("구독 중인 과목이 없습니다.") else addAll(subjectLines)
        child.lastCheckedAt?.let(::add)
        child.error?.let { add("오류: $it") }
    }.joinToString("\n")

    fun summary(child: MonitorChildSnapshot): String {
        val subjects = child.subjects.values.joinToString(" · ") { subject ->
            when (subject.status) {
                MonitorSubjectStatus.CONFIRMATION_PENDING -> "${subject.name} 확인 대기"
                MonitorSubjectStatus.BELOW_TARGET -> "${subject.name} ${subject.activityCount ?: "?"}/${subject.targetCount}"
                MonitorSubjectStatus.COMPLETE -> if (subject.activityCount == null) {
                    "✔ ${subject.name} 완료"
                } else {
                    "✔ ${subject.name} ${subject.activityCount}/${subject.targetCount}"
                }
            }
        }.ifBlank { "구독 중인 과목이 없습니다." }
        val pauseMessage = child.error?.split(" · ")?.filter { it in PAUSE_MESSAGES }?.distinct()
            ?.joinToString(" · ")?.ifBlank { null }
        return listOfNotNull(pauseMessage, subjects).joinToString(" · ")
    }

    private val PAUSE_MESSAGES = setOf("다시 로그인 필요", "접근성 권한 필요", "알림 권한 필요")
}

fun interface MonitorTaskQueue {
    fun enqueue(task: () -> Unit)
}

interface MonitorClock {
    fun today(): LocalDate
    fun checkedAt(): String
    fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    fun millisUntilNextSeoulMidnight(): Long = DateHelper.millisUntilNextSeoulMidnight()
}

fun interface MonitorTimer {
    fun cancel()
}

fun interface MonitorTimerFactory {
    fun schedule(delayMillis: Long, callback: () -> Unit): MonitorTimer
}

fun interface MonitorTargetProvider {
    fun targetCount(childName: String, subjectName: String): Int
}

interface MonitorRepository {
    fun signIn(email: String, password: String): LearningRepositoryResult<LearningSession>
    fun loadCatalog(session: LearningSession): LearningRepositoryResult<LearningCatalog>
    fun loadSubjectReport(
        session: LearningSession,
        subscription: ChildSubjectSubscription,
        date: LocalDate,
    ): LearningRepositoryResult<SubjectReport>
}

internal interface CancellableMonitorRepository {
    fun signInCancellable(
        email: String,
        password: String,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<LearningSession>

    fun loadCatalogCancellable(
        session: LearningSession,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<LearningCatalog>

    fun loadSubjectReportCancellable(
        session: LearningSession,
        subscription: ChildSubjectSubscription,
        date: LocalDate,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<SubjectReport>
}

internal sealed interface LearningReportRequestOutcome {
    data class Finished(val result: LearningRepositoryResult<LearningResult>) : LearningReportRequestOutcome
    data object Superseded : LearningReportRequestOutcome
    data object Invalidated : LearningReportRequestOutcome
    data object Cancelled : LearningReportRequestOutcome
}

internal fun interface LearningReportRequestHandle {
    fun cancel()
}

interface ChildNotificationSink {
    fun showStatus(
        accountId: String,
        child: MonitorChildSnapshot,
        completion: Boolean,
        completionSubjectName: String? = null,
    )
    fun cancel(accountId: String, childName: String)
}

interface MonitorStore {
    fun registeredChildren(accountId: String): Set<String>
    fun saveRegisteredChildren(accountId: String, children: Set<String>)
    fun intervalMinutes(accountId: String): java.math.BigInteger
    fun saveIntervalMinutes(accountId: String, minutes: java.math.BigInteger)
}

interface MonitorSubscription : AutoCloseable {
    override fun close()
}

interface LearningMonitor {
    fun snapshot(): LearningMonitorSnapshot
    fun observe(listener: (LearningMonitorSnapshot) -> Unit): MonitorSubscription
    fun registerChild(accountId: String, childName: String)
    fun unregisterChild(accountId: String, childName: String)
    fun setIntervalMinutes(accountId: String, minutes: BigInteger)
    fun setTargetCounts(accountId: String, childName: String, targets: Map<String, Int>)
    fun reauthenticate(accountId: String, email: String, password: String)
}

internal fun interface MonitorExecutionStatusProbe {
    fun inspect(): MonitorExecutionStatus
}

/**
 * Process-owned registration and initial-baseline coordinator.
 * Commands and repository outcomes are serialized through [commandQueue].
 */
class LearningMonitorEngine internal constructor(
    private val commandQueue: MonitorTaskQueue,
    private val workerQueue: MonitorTaskQueue,
    private val callbackQueue: MonitorTaskQueue,
    private val clock: MonitorClock,
    private val timerFactory: MonitorTimerFactory,
    private val store: MonitorStore,
    private val repository: MonitorRepository,
    private val targetProvider: MonitorTargetProvider,
    private val notificationSink: ChildNotificationSink,
    private val executionStatusProbe: MonitorExecutionStatusProbe? = null,
    private val dateTimerFactory: MonitorTimerFactory = timerFactory,
) : LearningMonitor {
    private data class Credentials(val email: String, val password: String)

    private class MutableChild(
        var generation: Long,
        var snapshot: MonitorChildSnapshot,
        var baselineStarted: Boolean = false,
        var latestCompletionSubjectName: String? = null,
        var baselineBoundarySequence: Long = 0L,
        var targetRevision: Long = 0L,
        val validatedSubjects: MutableSet<String> = mutableSetOf(),
        val commandedTargets: MutableMap<String, Int> = snapshot.subjects.mapValues { it.value.targetCount }.toMutableMap(),
    )

    private class AccountState(
        var intervalMinutes: java.math.BigInteger,
        val registrations: LinkedHashMap<String, MutableChild> = linkedMapOf(),
        val generations: MutableMap<String, Long> = mutableMapOf(),
        var catalog: LearningCatalog? = null,
        var metadataRetryNeeded: Boolean = true,
        var metadataError: String? = null,
        var authenticationRequired: Boolean = false,
        var authEpoch: Long = 0L,
        var recoveryPending: Boolean = false,
    )

    private data class BaselineKey(val accountId: String, val childName: String)

    private class BaselineRun(
        val accountId: String,
        val childName: String,
        val generation: Long,
        val targetRevision: Long,
        val accountEpoch: Long,
        val authEpoch: Long,
        val dateRevision: Long,
        val reportDate: LocalDate,
        val baselineBoundarySequence: Long,
        val availableCredentials: Credentials,
    ) {
        var session: LearningSession? = null
        var subscriptions: List<ChildSubjectSubscription> = emptyList()
        var nextSubscriptionIndex: Int = 0
        var lastDispatchSequence: Long = baselineBoundarySequence
        var activePairConsumer: BaselinePairConsumer? = null
        var activePairWork: ReportPairWork? = null
        @Volatile var invalidated: Boolean = false
    }

    private data class RegularPair(
        val subscription: ChildSubjectSubscription,
        val registrationGeneration: Long,
        val targetRevision: Long,
        val targetCount: Int,
    )

    private class RegularRun(
        val generation: Long,
        val accountId: String,
        val accountEpoch: Long,
        val authEpoch: Long,
        val startedAtMillis: Long,
        val foregroundSubject: String,
        val credentials: Credentials,
        val registrationGenerations: Map<String, Long>,
        val dateRevision: Long,
        val reportDate: LocalDate,
    ) {
        var session: LearningSession? = null
        var pairs: List<RegularPair> = emptyList()
        var nextPairIndex: Int = 0
        var activePairConsumer: RegularPairConsumer? = null
        var activePairWork: ReportPairWork? = null
        var invalidated: Boolean = false
        var lastDispatchSequence: Long = 0L
    }

    private enum class ReportPurpose { SCREEN, MONITOR_INITIAL, MONITOR_REGULAR }

    private enum class ConsumerTerminal { FINISHED, SUPERSEDED, INVALIDATED, CANCELLED }

    private class ScreenPairConsumer(
        val run: ScreenRun,
        val subscription: ChildSubjectSubscription,
    ) {
        @Volatile var terminal: ConsumerTerminal? = null
    }

    private class BaselinePairConsumer(
        val run: BaselineRun,
        val subscription: ChildSubjectSubscription,
        val targetRevision: Long,
        val targetCount: Int,
    ) {
        @Volatile var terminal: ConsumerTerminal? = null
    }

    private class RegularPairConsumer(
        val run: RegularRun,
        val pair: RegularPair,
    ) {
        @Volatile var terminal: ConsumerTerminal? = null
    }

    private data class ScreenPairIdentity(
        val accountId: String,
        val accountEpoch: Long,
        val authEpoch: Long,
        val dateRevision: Long,
        val reportDate: LocalDate,
        val childName: String,
        val subjectName: String,
        val generation: Long,
        val dispatchSequence: Long,
    )

    private class ReportPairWork(
        val purpose: ReportPurpose,
        val accountId: String,
        val accountEpoch: Long,
        val authEpoch: Long,
        val dateRevision: Long,
        val reportDate: LocalDate,
        val childName: String,
        val subjectName: String,
        val generation: Long,
        val targetRevision: Long?,
        val dispatchSequence: Long,
        val eligibilityRevisionAtDispatch: Long,
        val permissionRevisionAtDispatch: Long,
        val session: LearningSession,
        val subscription: ChildSubjectSubscription,
    ) {
        @Volatile var screenConsumer: ScreenPairConsumer? = null
        @Volatile var baselineConsumer: BaselinePairConsumer? = null
        @Volatile var regularConsumer: RegularPairConsumer? = null
        val cancellation = LearningRequestCancellation()
        @Volatile var physicalCompleted = false
    }

    private class ScreenRun(
        val generation: Long,
        val email: String,
        val password: String,
        val reportDate: LocalDate,
        val accountIdHint: String?,
        val activeAccountIdAtStart: String?,
        val accountEpochAtStart: Long,
        val authEpochAtStart: Long?,
        val dateRevisionAtStart: Long,
        val listener: (LearningReportRequestOutcome) -> Unit,
    ) {
        var session: LearningSession? = null
        var catalog: LearningCatalog? = null
        var checkedAt: String? = null
        var nextSubscriptionIndex: Int = 0
        val totals = LinkedHashMap<String, Long>()
        val subjectsByChild = LinkedHashMap<String, MutableList<SubjectProgress>>()
        var activePairWork: ReportPairWork? = null
        var activeCancellation: LearningRequestCancellation? = null
        @Volatile var terminal = false
    }

    private class ListenerSlot(val listener: (LearningMonitorSnapshot) -> Unit) {
        val active = AtomicBoolean(true)
    }

    private val accounts = mutableMapOf<String, AccountState>()
    private val credentials = mutableMapOf<String, Credentials>()
    private val baselineRuns = mutableMapOf<BaselineKey, BaselineRun>()
    private val screenRequestIds = AtomicLong(0L)
    private val regularRunIds = AtomicLong(0L)
    private val screenPairWork = LinkedHashMap<ScreenPairIdentity, ReportPairWork>()
    private val listeners = mutableListOf<ListenerSlot>()
    private var activeAccountId: String? = null
    private var accountEpoch: Long = 0
    private var serviceLeaseAttached: Boolean = false
    private var accessibilityEnabled: Boolean = false
    private var notificationsEnabled: Boolean = false
    private var foregroundPackageName: String? = null
    private var foregroundObservationPending: Boolean = false
    private var screenOn: Boolean = false
    private var lastCycleStartedAtMillis: Long? = null
    private var activeRegularRun: RegularRun? = null
    private var activeScreenRun: ScreenRun? = null
    private var cadenceTimer: MonitorTimer? = null
    private var cadenceTimerGeneration: Long = 0L
    private var scheduledTimerCycleStart: Long? = null
    private var monitoringDate: LocalDate = clock.today()
    private var monitoringDateRevision: Long = 0L
    private var dateTimer: MonitorTimer? = null
    private var dateTimerGeneration: Long = 0L
    @Volatile
    private var runtimeEligibilityRevision: Long = 0L

    @Volatile
    private var runtimePermissionRevision: Long = 0L

    private var lastCommittedDispatchSequence: Long = 0L

    @Volatile
    private var currentSnapshot = LearningMonitorSnapshot()

    override fun snapshot(): LearningMonitorSnapshot = currentSnapshot

    /**
     * Runs the interactive report through the same serialized command and pair-release boundary as monitoring.
     * The callback receives one terminal outcome even when a newer screen request supersedes this one.
     */
    internal fun loadScreenReport(
        email: String,
        password: String,
        date: LocalDate,
        accountIdHint: String? = null,
        listener: (LearningReportRequestOutcome) -> Unit,
    ): LearningReportRequestHandle {
        val generation = screenRequestIds.incrementAndGet()
        commandQueue.enqueue {
            activeScreenRun?.let { finishScreenRequest(it, LearningReportRequestOutcome.Superseded) }
            val currentAccountAtStart = activeAccountId
            val currentAccountHint = accountIdHint?.takeIf { it == currentAccountAtStart }
            val authEpoch = currentAccountAtStart?.let { accounts[it]?.authEpoch }
            val run = ScreenRun(
                generation = generation,
                email = email,
                password = password,
                reportDate = date,
                accountIdHint = currentAccountHint,
                activeAccountIdAtStart = currentAccountAtStart,
                accountEpochAtStart = accountEpoch,
                authEpochAtStart = authEpoch,
                dateRevisionAtStart = monitoringDateRevision,
                listener = listener,
            )
            activeScreenRun = run
            loadScreenSession(run)
        }
        return LearningReportRequestHandle {
            commandQueue.enqueue {
                activeScreenRun?.takeIf { it.generation == generation }?.let {
                    finishScreenRequest(it, LearningReportRequestOutcome.Cancelled)
                }
            }
        }
    }

    private fun loadScreenSession(run: ScreenRun) = releaseScreenIo(
        run,
        operation = { cancellation -> signInCancellable(run.email, run.password, cancellation) },
        onSuccess = { result ->
            when (result) {
                is LearningRepositoryResult.Success -> {
                    run.session = result.value
                    run.checkedAt = clock.checkedAt()
                    loadScreenCatalog(run)
                }
                is LearningRepositoryResult.TemporaryFailure -> finishScreenRequest(
                    run,
                    LearningReportRequestOutcome.Finished(result),
                )
                is LearningRepositoryResult.AuthenticationDenied -> {
                    pauseForScreenAuthenticationDenied(run)
                    finishScreenRequest(run, LearningReportRequestOutcome.Finished(result))
                }
            }
        },
        onFailure = { exception ->
            finishScreenRequest(run, LearningReportRequestOutcome.Finished(screenFailure(exception)))
        },
    )

    private fun loadScreenCatalog(run: ScreenRun) {
        val session = run.session ?: return finishScreenRequest(
            run,
            LearningReportRequestOutcome.Finished(
                LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("로그인 상태를 확인하지 못했습니다.")),
            ),
        )
        releaseScreenIo(
            run,
            operation = { cancellation -> loadCatalogCancellable(session, cancellation) },
            onSuccess = { result ->
                when (result) {
                    is LearningRepositoryResult.Success -> {
                        if (result.value.childOrder.isEmpty()) {
                            finishScreenRequest(
                                run,
                                LearningReportRequestOutcome.Finished(
                                    LearningRepositoryResult.TemporaryFailure(
                                        LearningRepositoryException("아이 정보를 찾지 못했습니다."),
                                    ),
                                ),
                            )
                        } else {
                            run.catalog = result.value
                            loadNextScreenPair(run)
                        }
                    }
                    is LearningRepositoryResult.TemporaryFailure -> finishScreenRequest(
                        run,
                        LearningReportRequestOutcome.Finished(result),
                    )
                    is LearningRepositoryResult.AuthenticationDenied -> {
                        pauseForScreenAuthenticationDenied(run)
                        finishScreenRequest(run, LearningReportRequestOutcome.Finished(result))
                    }
                }
            },
            onFailure = { exception ->
                finishScreenRequest(run, LearningReportRequestOutcome.Finished(screenFailure(exception)))
            },
        )
    }

    private fun loadNextScreenPair(run: ScreenRun) {
        if (!screenCanContinue(run)) return
        val catalog = run.catalog ?: return finishScreenRequest(
            run,
            LearningReportRequestOutcome.Finished(
                LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("과목 구독 정보를 확인하지 못했습니다.")),
            ),
        )
        while (run.nextSubscriptionIndex < catalog.subscriptions.size) {
            val subscription = catalog.subscriptions[run.nextSubscriptionIndex++]
            val session = run.session ?: return finishScreenRequest(
                run,
                LearningReportRequestOutcome.Finished(
                    LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("로그인 상태를 확인하지 못했습니다.")),
                ),
            )
            val dispatchSequence = nextDispatchSequence()
            val work = ReportPairWork(
                purpose = ReportPurpose.SCREEN,
                accountId = session.accountId.toString(),
                accountEpoch = run.accountEpochAtStart,
                authEpoch = run.authEpochAtStart ?: Long.MIN_VALUE,
                dateRevision = run.dateRevisionAtStart,
                reportDate = run.reportDate,
                childName = subscription.childName,
                subjectName = subscription.subjectName,
                generation = run.generation,
                targetRevision = null,
                dispatchSequence = dispatchSequence,
                eligibilityRevisionAtDispatch = runtimeEligibilityRevision,
                permissionRevisionAtDispatch = runtimePermissionRevision,
                session = session,
                subscription = subscription,
            )
            work.screenConsumer = ScreenPairConsumer(run, subscription)
            run.activePairWork = work
            screenPairWork[screenPairIdentity(work)] = work
            dispatchReportPair(work)
            return
        }

        val session = run.session ?: return finishScreenRequest(
            run,
            LearningReportRequestOutcome.Finished(
                LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("로그인 상태를 확인하지 못했습니다.")),
            ),
        )
        val checkedAt = run.checkedAt ?: clock.checkedAt()
        val children = catalog.childOrder.distinct().map { name ->
            val minutes = kotlin.math.ceil((run.totals[name] ?: 0L) / 60.0).toInt()
            ChildLearningInfo(name, minutes, run.subjectsByChild[name] ?: emptyList())
        }
        finishScreenRequest(
            run,
            LearningReportRequestOutcome.Finished(
                LearningRepositoryResult.Success(LearningResult(checkedAt, children, session.accountId.toString())),
            ),
        )
    }

    private fun <T> releaseScreenIo(
        run: ScreenRun,
        operation: (LearningRequestCancellation) -> LearningRepositoryResult<T>,
        onSuccess: (LearningRepositoryResult<T>) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        if (!screenCanContinue(run)) return
        val cancellation = LearningRequestCancellation()
        run.activeCancellation = cancellation
        workerQueue.enqueue {
            if (run.terminal || cancellation.isCancelled()) return@enqueue
            val outcome = try {
                Result.success(operation(cancellation))
            } catch (exception: Exception) {
                Result.failure(exception)
            }
            val authenticationDenied = outcome.getOrNull() is LearningRepositoryResult.AuthenticationDenied ||
                outcome.exceptionOrNull() is LearningAuthenticationDeniedException
            val afterRequest = if (authenticationDenied) inspectRuntimeStatus() else null
            commandQueue.enqueue response@{
                if (run.activeCancellation === cancellation) run.activeCancellation = null
                if (!screenCanContinue(run)) return@response
                if (authenticationDenied) {
                    afterRequest?.let {
                        applyExecutionConditions(it.accessibilityEnabled, it.notificationsEnabled)
                    }
                    refreshMonitoringDate()
                    if (!screenCanContinue(run)) return@response
                }
                outcome.fold(onSuccess) { error -> onFailure(error as? Exception ?: RuntimeException(error)) }
            }
        }
    }

    private fun signInCancellable(
        email: String,
        password: String,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<LearningSession> {
        cancellation.throwIfCancelled()
        val cancellable = repository as? CancellableMonitorRepository
        return if (cancellable == null) repository.signIn(email, password)
        else cancellable.signInCancellable(email, password, cancellation)
    }

    private fun loadCatalogCancellable(
        session: LearningSession,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<LearningCatalog> {
        cancellation.throwIfCancelled()
        val cancellable = repository as? CancellableMonitorRepository
        return if (cancellable == null) repository.loadCatalog(session)
        else cancellable.loadCatalogCancellable(session, cancellation)
    }

    private fun loadSubjectReportCancellable(
        session: LearningSession,
        subscription: ChildSubjectSubscription,
        date: LocalDate,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<SubjectReport> {
        cancellation.throwIfCancelled()
        val cancellable = repository as? CancellableMonitorRepository
        val result = if (cancellable == null) repository.loadSubjectReport(session, subscription, date)
        else cancellable.loadSubjectReportCancellable(session, subscription, date, cancellation)
        cancellation.throwIfCancelled()
        return result
    }

    private fun dispatchReportPair(work: ReportPairWork) {
        workerQueue.enqueue {
            if (work.cancellation.isCancelled() || !hasActivePairConsumer(work)) {
                work.physicalCompleted = true
                return@enqueue
            }
            val hasMonitorConsumer = hasActiveMonitorConsumer(work)
            val beforeRequest = if (hasMonitorConsumer) inspectRuntimeStatus() else null
            val monitorDispatchStillValid = hasMonitorConsumer &&
                work.permissionRevisionAtDispatch == runtimePermissionRevision &&
                (work.purpose != ReportPurpose.MONITOR_REGULAR ||
                    work.eligibilityRevisionAtDispatch == runtimeEligibilityRevision) &&
                (beforeRequest == null || beforeRequest.permissionsReady)
            if (hasMonitorConsumer && !monitorDispatchStillValid) {
                commandQueue.enqueue {
                    beforeRequest?.let {
                        applyExecutionConditions(it.accessibilityEnabled, it.notificationsEnabled)
                    }
                    invalidateMonitorConsumers(work)
                }
                if (work.screenConsumer == null || work.screenConsumer?.terminal != null) {
                    work.physicalCompleted = true
                    return@enqueue
                }
            }
            val result = try {
                loadSubjectReportCancellable(work.session, work.subscription, work.reportDate, work.cancellation)
            } catch (exception: Exception) {
                screenFailure(exception)
            }
            val afterRequest = inspectRuntimeStatus()
            work.physicalCompleted = true
            commandQueue.enqueue {
                val screenAuthenticationEvidence = result is LearningRepositoryResult.AuthenticationDenied &&
                    work.screenConsumer?.terminal == null && work.screenConsumer != null
                if (hasActiveMonitorConsumer(work) || screenAuthenticationEvidence) {
                    afterRequest?.let {
                        applyExecutionConditions(it.accessibilityEnabled, it.notificationsEnabled)
                    }
                    refreshMonitoringDate()
                }
                finishReportPair(work, result)
            }
        }
    }

    private fun finishReportPair(work: ReportPairWork, result: LearningRepositoryResult<SubjectReport>) {
        screenPairWork.remove(screenPairIdentity(work))
        // A current shared SCREEN 401 must reach the Activity before its auth pause advances authEpoch.
        // That pause then invalidates the joined monitor consumer through its own freshness checks.
        work.screenConsumer?.let { consumer ->
            if (consumer.terminal == null) {
                val run = consumer.run
                if (run.activePairWork === work) run.activePairWork = null
                if (screenCanContinue(run)) {
                    consumer.terminal = ConsumerTerminal.FINISHED
                    when (result) {
                        is LearningRepositoryResult.Success -> {
                            val report = result.value
                            run.totals[report.childName] = (run.totals[report.childName] ?: 0L) + report.learningSeconds
                            run.subjectsByChild.getOrPut(report.childName) { mutableListOf() }
                                .add(SubjectProgress(report.subjectName, report.activityCount))
                            loadNextScreenPair(run)
                        }
                        is LearningRepositoryResult.TemporaryFailure -> finishScreenRequest(
                            run,
                            LearningReportRequestOutcome.Finished(result),
                        )
                        is LearningRepositoryResult.AuthenticationDenied -> {
                            pauseForScreenAuthenticationDenied(run)
                            finishScreenRequest(run, LearningReportRequestOutcome.Finished(result))
                        }
                    }
                } else {
                    consumer.terminal = ConsumerTerminal.INVALIDATED
                }
                work.screenConsumer = null
            }
        }
        work.baselineConsumer?.let { consumer ->
            if (consumer.terminal == null) {
                val run = consumer.run
                if (run.activePairConsumer === consumer) run.activePairConsumer = null
                if (run.activePairWork === work) run.activePairWork = null
                if (baselineCanContinue(run)) {
                    consumer.terminal = ConsumerTerminal.FINISHED
                    val validated = when (result) {
                        is LearningRepositoryResult.Success -> if (
                            result.value.childName == consumer.subscription.childName &&
                                result.value.subjectName == consumer.subscription.subjectName
                        ) result else LearningRepositoryResult.TemporaryFailure(
                            LearningRepositoryException("학습 보고서의 아이 또는 과목이 요청과 일치하지 않습니다."),
                        )
                        is LearningRepositoryResult.TemporaryFailure -> result
                        is LearningRepositoryResult.AuthenticationDenied -> result
                    }
                    applyBaselinePair(run, consumer.subscription, consumer.targetRevision, consumer.targetCount, validated)
                    loadNextBaselinePair(run)
                } else {
                    consumer.terminal = ConsumerTerminal.INVALIDATED
                }
                work.baselineConsumer = null
            }
        }
        work.regularConsumer?.let { consumer ->
            if (consumer.terminal == null) {
                val run = consumer.run
                if (run.activePairConsumer === consumer) run.activePairConsumer = null
                if (run.activePairWork === work) run.activePairWork = null
                if (regularCanApply(run)) {
                    consumer.terminal = ConsumerTerminal.FINISHED
                    val pairResult = when (result) {
                        is LearningRepositoryResult.Success -> if (
                            result.value.childName == consumer.pair.subscription.childName &&
                                result.value.subjectName == consumer.pair.subscription.subjectName
                        ) result else LearningRepositoryResult.TemporaryFailure(
                            LearningRepositoryException("학습 보고서의 아이 또는 과목이 요청과 일치하지 않습니다."),
                        )
                        is LearningRepositoryResult.TemporaryFailure -> result
                        is LearningRepositoryResult.AuthenticationDenied -> result
                    }
                    applyRegularPair(run, consumer.pair, pairResult)
                    loadNextRegularPair(run)
                } else {
                    consumer.terminal = ConsumerTerminal.INVALIDATED
                }
                work.regularConsumer = null
            }
        }
        cancelPairWorkIfUnconsumed(work)
    }

    private fun hasActiveMonitorConsumer(work: ReportPairWork): Boolean =
        (work.baselineConsumer?.terminal == null && work.baselineConsumer != null) ||
            (work.regularConsumer?.terminal == null && work.regularConsumer != null)

    private fun invalidateMonitorConsumers(work: ReportPairWork) {
        work.baselineConsumer?.let { consumer ->
            if (consumer.terminal == null) {
                consumer.terminal = ConsumerTerminal.INVALIDATED
                consumer.run.activePairConsumer = null
                consumer.run.activePairWork = null
                work.baselineConsumer = null
            }
        }
        work.regularConsumer?.let { consumer ->
            if (consumer.terminal == null) {
                val run = consumer.run
                consumer.terminal = ConsumerTerminal.INVALIDATED
                run.activePairConsumer = null
                run.activePairWork = null
                work.regularConsumer = null
                finishRegularRun(run)
            }
        }
        cancelPairWorkIfUnconsumed(work)
    }

    private fun screenPairIdentity(work: ReportPairWork): ScreenPairIdentity = ScreenPairIdentity(
        accountId = work.accountId,
        accountEpoch = work.accountEpoch,
        authEpoch = work.authEpoch,
        dateRevision = work.dateRevision,
        reportDate = work.reportDate,
        childName = work.childName,
        subjectName = work.subjectName,
        generation = work.generation,
        dispatchSequence = work.dispatchSequence,
    )

    private fun hasActivePairConsumer(work: ReportPairWork): Boolean =
        work.screenConsumer?.terminal == null && work.screenConsumer != null ||
            work.baselineConsumer?.terminal == null && work.baselineConsumer != null ||
            work.regularConsumer?.terminal == null && work.regularConsumer != null

    private fun cancelPairWorkIfUnconsumed(work: ReportPairWork) {
        if (!hasActivePairConsumer(work)) {
            screenPairWork.remove(screenPairIdentity(work))
            if (!work.physicalCompleted) work.cancellation.cancel()
        }
    }

    private fun detachBaselineConsumer(run: BaselineRun, terminal: ConsumerTerminal) {
        val work = run.activePairWork ?: return
        val consumer = work.baselineConsumer?.takeIf { it.run === run } ?: return
        if (consumer.terminal == null) consumer.terminal = terminal
        work.baselineConsumer = null
        run.activePairConsumer = null
        run.activePairWork = null
        cancelPairWorkIfUnconsumed(work)
    }

    private fun detachRegularConsumer(run: RegularRun, terminal: ConsumerTerminal) {
        val work = run.activePairWork ?: return
        val consumer = work.regularConsumer?.takeIf { it.run === run } ?: return
        if (consumer.terminal == null) consumer.terminal = terminal
        work.regularConsumer = null
        run.activePairConsumer = null
        run.activePairWork = null
        cancelPairWorkIfUnconsumed(work)
    }

    private fun invalidateRegularRun(run: RegularRun) {
        run.invalidated = true
        detachRegularConsumer(run, ConsumerTerminal.INVALIDATED)
        if (activeRegularRun === run) activeRegularRun = null
    }

    private fun invalidateActiveRegularRun() {
        activeRegularRun?.let(::invalidateRegularRun)
    }

    private fun screenFailure(exception: Exception): LearningRepositoryResult<Nothing> =
        if (exception is LearningAuthenticationDeniedException) {
            LearningRepositoryResult.AuthenticationDenied(exception)
        } else {
            LearningRepositoryResult.TemporaryFailure(
                if (exception is LearningRepositoryException) exception
                else LearningRepositoryException(exception.message ?: exception.javaClass.simpleName, exception),
            )
        }

    private fun pauseForScreenAuthenticationDenied(run: ScreenRun) {
        val accountId = run.accountIdHint ?: return
        if (!screenCanAffectMonitor(run) || accountId != activeAccountId) return
        pauseForAuthenticationDenied(accountId)
    }

    private fun screenCanAffectMonitor(run: ScreenRun): Boolean {
        val accountId = run.accountIdHint ?: return false
        val state = accounts[accountId] ?: return false
        val authEvidenceMatches = run.session?.let { session ->
            session.accountId.toString() == accountId
        } ?: (credentials[accountId] == Credentials(run.email, run.password))
        return run.accountEpochAtStart == accountEpoch && accountId == activeAccountId &&
            run.authEpochAtStart != null && state.authEpoch == run.authEpochAtStart &&
            !state.authenticationRequired && executionReady(accountId) && authEvidenceMatches
    }

    private fun screenCanContinue(run: ScreenRun): Boolean {
        if (activeScreenRun !== run || run.terminal) return false
        val hintedAccount = run.accountIdHint
        val capturedAccount = run.activeAccountIdAtStart
        val identityChanged = run.accountEpochAtStart != accountEpoch ||
            capturedAccount != activeAccountId ||
            (capturedAccount != null && run.authEpochAtStart != accounts[capturedAccount]?.authEpoch) ||
            (hintedAccount != null && hintedAccount != activeAccountId)
        if (identityChanged) {
            finishScreenRequest(run, LearningReportRequestOutcome.Invalidated)
            return false
        }
        return true
    }

    private fun finishScreenRequest(run: ScreenRun, outcome: LearningReportRequestOutcome) {
        if (run.terminal) return
        run.terminal = true
        run.activeCancellation?.cancel()
        run.activeCancellation = null
        run.activePairWork?.let { work ->
            work.screenConsumer?.takeIf { it.run === run }?.let { consumer ->
                consumer.terminal = when (outcome) {
                    LearningReportRequestOutcome.Superseded -> ConsumerTerminal.SUPERSEDED
                    LearningReportRequestOutcome.Invalidated -> ConsumerTerminal.INVALIDATED
                    LearningReportRequestOutcome.Cancelled -> ConsumerTerminal.CANCELLED
                    is LearningReportRequestOutcome.Finished -> ConsumerTerminal.FINISHED
                }
                work.screenConsumer = null
                screenPairWork.remove(screenPairIdentity(work))
                cancelPairWorkIfUnconsumed(work)
            }
        }
        run.activePairWork = null
        if (activeScreenRun === run) activeScreenRun = null
        callbackQueue.enqueue { run.listener(outcome) }
    }

    /** Listener registration and initial snapshot capture happen in one serial command. */
    override fun observe(listener: (LearningMonitorSnapshot) -> Unit): MonitorSubscription {
        val slot = ListenerSlot(listener)
        commandQueue.enqueue {
            if (slot.active.get()) {
                listeners += slot
                deliver(slot, currentSnapshot)
            }
        }
        return object : MonitorSubscription {
            override fun close() {
                if (!slot.active.compareAndSet(true, false)) return
                commandQueue.enqueue { listeners.remove(slot) }
            }
        }
    }

    /** Supplies already-encrypted app credentials as a private runtime input. */
    internal fun credentialsAvailable(accountId: String, email: String, password: String) {
        require(accountId.isNotBlank())
        commandQueue.enqueue {
            val switchingAccounts = activeAccountId != accountId
            val updatedCredentials = credentials[accountId] != Credentials(email, password)
            if (switchingAccounts || updatedCredentials) {
                activeScreenRun?.let {
                    finishScreenRequest(it, LearningReportRequestOutcome.Invalidated)
                }
            }
            if (switchingAccounts) {
                runtimeEligibilityRevision += 1L
                invalidateActiveRegularRun()
                cancelCadenceTimer()
                cancelUnreleasedBaselines()
                accountEpoch += 1
                activeAccountId = accountId
            }
            val firstCredentialsForAccount = credentials[accountId] == null
            credentials[accountId] = Credentials(email, password)
            val state = accountState(accountId)
            if (switchingAccounts) {
                state.registrations.keys.toList().forEach { childName -> resetChild(state, childName) }
                state.recoveryPending = true
            }
            if (!permissionsAvailable()) state.recoveryPending = true
            publish()
            if (switchingAccounts || firstCredentialsForAccount) {
                startUnattemptedBaselines(accountId)
            }
            maybeStartRegularCycle()
        }
    }

    /** Accepts credentials only after a person completed the app's login flow. */
    override fun reauthenticate(accountId: String, email: String, password: String) {
        require(accountId.isNotBlank())
        commandQueue.enqueue {
            val switchingAccounts = activeAccountId != accountId
            activeScreenRun?.let { finishScreenRequest(it, LearningReportRequestOutcome.Invalidated) }
            if (switchingAccounts) {
                runtimeEligibilityRevision += 1L
                invalidateActiveRegularRun()
                cancelCadenceTimer()
                cancelUnreleasedBaselines()
                accountEpoch += 1L
                activeAccountId = accountId
            }
            credentials[accountId] = Credentials(email, password)
            val state = accountState(accountId)
            if (switchingAccounts) {
                state.registrations.keys.toList().forEach { childName -> resetChild(state, childName) }
            }
            state.authEpoch += 1L
            state.authenticationRequired = false
            runtimeEligibilityRevision += 1L
            beginBaselinePause(state)
            publish()
            startUnattemptedBaselines(accountId)
            maybeStartRegularCycle()
        }
    }

    /** Enqueue an independent child registration and a fresh all-subject baseline. */
    override fun registerChild(accountId: String, childName: String) {
        require(accountId.isNotBlank())
        require(childName.isNotBlank())
        commandQueue.enqueue {
            val state = accountState(accountId)
            runtimeEligibilityRevision += 1L
            val child = resetChild(state, childName)
            if (!permissionsAvailable() || state.authenticationRequired) state.recoveryPending = true
            store.saveRegisteredChildren(accountId, state.registrations.keys)
            showStatus(accountId, child)
            publish()
            startInitialBaseline(accountId, childName)
            maybeStartRegularCycle()
        }
    }

    /** Removing one child invalidates only that child's in-flight baseline. */
    override fun unregisterChild(accountId: String, childName: String) {
        commandQueue.enqueue {
            val state = accountState(accountId)
            runtimeEligibilityRevision += 1L
            val old = state.registrations.remove(childName)
            baselineRuns.remove(BaselineKey(accountId, childName))?.let { run ->
                run.invalidated = true
                detachBaselineConsumer(run, ConsumerTerminal.CANCELLED)
            }
            state.generations[childName] = (state.generations[childName] ?: old?.generation ?: 0L) + 1L
            store.saveRegisteredChildren(accountId, state.registrations.keys)
            notificationSink.cancel(accountId, childName)
            publish()
            maybeStartRegularCycle()
        }
    }

    /** The value is kept as an exact decimal integer and has no product maximum. */
    override fun setIntervalMinutes(accountId: String, minutes: BigInteger) {
        require(accountId.isNotBlank())
        require(minutes.signum() > 0) { "조회 간격은 양의 정수여야 합니다." }
        commandQueue.enqueue {
            val state = accountState(accountId)
            state.intervalMinutes = minutes
            store.saveIntervalMinutes(accountId, minutes)
            publish()
            cancelCadenceTimer()
            maybeStartRegularCycle()
        }
    }

    override fun setTargetCounts(accountId: String, childName: String, targets: Map<String, Int>) {
        require(accountId.isNotBlank())
        require(childName.isNotBlank())
        require(targets.values.all { it >= 0 }) { "목표 개수는 0 이상이어야 합니다." }
        val requestedTargets = targets.toMap()
        commandQueue.enqueue {
            if (accountId != activeAccountId) return@enqueue
            refreshMonitoringDate()
            var changedAnyTarget = false
            var restartBaseline = false
            val state = accounts[accountId]
            if (state == null) return@enqueue
            run {
                val child = state.registrations[childName] ?: return@enqueue
                val subjects = LinkedHashMap<String, MonitorSubjectSnapshot>()
                var changedThisChild = false
                child.snapshot.subjects.forEach { (subjectName, previous) ->
                    val target = requestedTargets[subjectName]
                        ?: targetProvider.targetCount(childName, subjectName).coerceAtLeast(0)
                    val previousCommandedTarget = child.commandedTargets[subjectName] ?: previous.targetCount
                    if (target == previousCommandedTarget && target == previous.targetCount) {
                        subjects[subjectName] = previous
                    } else {
                        changedThisChild = true
                        child.commandedTargets[subjectName] = target
                        subjects[subjectName] = when {
                            target == 0 -> previous.copy(
                                status = MonitorSubjectStatus.COMPLETE,
                                targetCount = 0,
                            )
                            previous.activityCount != null && subjectName in child.validatedSubjects -> previous.copy(
                                status = if (previous.activityCount >= target) {
                                    MonitorSubjectStatus.COMPLETE
                                } else {
                                    MonitorSubjectStatus.BELOW_TARGET
                                },
                                targetCount = target,
                            )
                            else -> previous.copy(
                                status = MonitorSubjectStatus.CONFIRMATION_PENDING,
                                targetCount = target,
                            )
                        }
                    }
                }
                if (!changedThisChild) return@run

                changedAnyTarget = true
                child.targetRevision += 1L
                val baseline = baselineRuns.remove(BaselineKey(accountId, childName))
                if (baseline != null) {
                    baseline.invalidated = true
                    detachBaselineConsumer(baseline, ConsumerTerminal.SUPERSEDED)
                    child.baselineStarted = false
                    restartBaseline = true
                }
                child.snapshot = child.snapshot.copy(
                    subjects = subjects,
                    error = aggregateChildError(subjects, state.metadataError),
                )
                showStatus(accountId, child)
                if (accountId == activeAccountId) publish()
            }

            if (changedAnyTarget) {
                syncDateEvaluationTimer()
                if (restartBaseline) startUnattemptedBaselines()
                maybeStartRegularCycle()
            }
        }
    }

    /** Rechecks the Seoul monitoring date from Activity resume and runtime date-change signals. */
    internal fun reevaluateMonitoringDate() {
        commandQueue.enqueue {
            val changed = refreshMonitoringDate()
            if (!changed) {
                rescheduleDateEvaluationTimer()
                maybeStartRegularCycle()
            }
        }
    }

    /** AccessibilityService attach/detach is an execution lease, never engine ownership. */
    internal fun attachServiceLease() {
        commandQueue.enqueue {
            if (!refreshMonitoringDate()) rescheduleDateEvaluationTimer()
            if (!serviceLeaseAttached) {
                serviceLeaseAttached = true
                foregroundPackageName = null
                foregroundObservationPending = true
                screenOn = false
                runtimeEligibilityRevision += 1L
                publish()
                startUnattemptedBaselines()
                maybeStartRegularCycle()
            }
        }
    }

    /** The accessibility setting and notification permission are separate from service binding. */
    internal fun executionConditionsChanged(accessibilityEnabled: Boolean, notificationsEnabled: Boolean) {
        commandQueue.enqueue {
            applyExecutionConditions(accessibilityEnabled, notificationsEnabled)
        }
    }

    private fun applyExecutionConditions(accessibilityEnabled: Boolean, notificationsEnabled: Boolean) {
        if (this.accessibilityEnabled == accessibilityEnabled && this.notificationsEnabled == notificationsEnabled) return
        this.accessibilityEnabled = accessibilityEnabled
        this.notificationsEnabled = notificationsEnabled
        runtimePermissionRevision += 1L
        runtimeEligibilityRevision += 1L
        if (!permissionsAvailable()) {
            val accountId = activeAccountId
            val state = accountId?.let(accounts::get)
            if (state != null) {
                beginBaselinePause(state)
                val missing = buildList {
                    if (!accessibilityEnabled) add("접근성 권한 필요")
                    if (!notificationsEnabled) add("알림 권한 필요")
                }.joinToString(" · ")
                state.registrations.values.forEach { child ->
                    child.latestCompletionSubjectName = null
                    child.snapshot = child.snapshot.copy(error = appendPauseMessage(child.snapshot.error, missing))
                    if (notificationsEnabled) showStatus(accountId, child)
                }
            }
            cancelUnreleasedBaselines()
            invalidateActiveRegularRun()
            cancelCadenceTimer()
        }
        publish()
        if (executionReady()) {
            startUnattemptedBaselines()
            maybeStartRegularCycle()
        }
    }

    internal fun detachServiceLease() {
        commandQueue.enqueue {
            if (serviceLeaseAttached) {
                serviceLeaseAttached = false
                foregroundPackageName = null
                foregroundObservationPending = false
                screenOn = false
                runtimeEligibilityRevision += 1L
                cancelUnreleasedBaselines()
                invalidateActiveRegularRun()
                cancelCadenceTimer()
                publish()
            }
        }
    }

    /** Private runtime input; foreground eligibility is refreshed by a new window event after every lease. */
    internal fun foregroundPackageChanged(packageName: String?) {
        commandQueue.enqueue {
            if (!serviceLeaseAttached) {
                foregroundPackageName = null
                return@enqueue
            }
            val wasObservationPending = foregroundObservationPending
            if (wasObservationPending || foregroundPackageName != packageName) {
                foregroundPackageName = packageName
                foregroundObservationPending = false
                runtimeEligibilityRevision += 1L
                maybeStartRegularCycle()
                if (wasObservationPending) publish()
            }
        }
    }

    /** Private screen-state input from the runtime adapter. */
    internal fun screenOnChanged(screenOn: Boolean) {
        commandQueue.enqueue {
            if (this.screenOn == screenOn) return@enqueue
            this.screenOn = screenOn
            runtimeEligibilityRevision += 1L
            if (screenOn) {
                maybeStartRegularCycle()
            } else {
                cancelCadenceTimer()
            }
        }
    }

    private fun accountState(accountId: String): AccountState = accounts.getOrPut(accountId) {
        AccountState(intervalMinutes = store.intervalMinutes(accountId)).also { state ->
            if (!permissionsAvailable()) state.recoveryPending = true
            store.registeredChildren(accountId).forEach { childName ->
                val child = resetChild(state, childName)
                state.registrations[childName] = child
            }
        }
    }

    private fun beginBaselinePause(state: AccountState) {
        if (!state.recoveryPending) {
            state.recoveryPending = true
            runtimeEligibilityRevision += 1L
            state.registrations.forEach { (childName, child) ->
                val generation = maxOf(state.generations[childName] ?: child.generation, child.generation) + 1L
                state.generations[childName] = generation
                child.generation = generation
                child.baselineStarted = false
                child.baselineBoundarySequence = lastCommittedDispatchSequence
                child.validatedSubjects.clear()
            }
        }
        cancelUnreleasedBaselines()
        invalidateActiveRegularRun()
        cancelCadenceTimer()
    }

    private fun pauseForAuthenticationDenied(accountId: String) {
        val state = accounts[accountId] ?: return
        if (state.authenticationRequired) return
        state.authenticationRequired = true
        state.authEpoch += 1L
        beginBaselinePause(state)
        state.registrations.values.forEach { child ->
            child.latestCompletionSubjectName = null
            child.snapshot = child.snapshot.copy(error = appendPauseMessage(child.snapshot.error, "다시 로그인 필요"))
            if (notificationsEnabled) showStatus(accountId, child)
        }
        publish()
    }

    private fun appendPauseMessage(previous: String?, message: String): String =
        (previous?.split(" · ").orEmpty() + message.split(" · "))
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" · ")

    private fun prepareRecoveryBaseline(state: AccountState) {
        val boundarySequence = lastCommittedDispatchSequence
        state.recoveryPending = false
        state.registrations.forEach { (childName, child) ->
            // Ticket 07 can share only SCREEN pair work dispatched after this boundary.
            // SCREEN dispatches must obtain their sequence from this engine's serialized coordinator.
            child.baselineBoundarySequence = boundarySequence
            child.baselineStarted = false
            child.latestCompletionSubjectName = null
            child.validatedSubjects.clear()
            val subjects = LinkedHashMap<String, MonitorSubjectSnapshot>()
            child.snapshot.subjects.forEach { (subjectName, previous) ->
                val target = targetProvider.targetCount(childName, subjectName).coerceAtLeast(0)
                subjects[subjectName] = if (target == 0) {
                    previous.copy(status = MonitorSubjectStatus.COMPLETE, targetCount = 0, error = null)
                } else {
                    previous.copy(
                        status = MonitorSubjectStatus.CONFIRMATION_PENDING,
                        targetCount = target,
                        error = null,
                    )
                }
            }
            child.snapshot = child.snapshot.copy(
                subjects = subjects,
                error = aggregateChildError(subjects, state.metadataError),
            )
            showStatus(activeAccountId ?: return@forEach, child)
        }
        publish()
    }

    private fun nextDispatchSequence(): Long = ++lastCommittedDispatchSequence

    private fun resetChild(state: AccountState, childName: String): MutableChild {
        val generation = (state.generations[childName] ?: state.registrations[childName]?.generation ?: 0L) + 1L
        state.generations[childName] = generation
        val subjects = supportedSubjects().associateWith { name ->
            pendingSubject(childName, name)
        }
        return MutableChild(
            generation = generation,
            snapshot = MonitorChildSnapshot(childName, subjects),
            baselineBoundarySequence = lastCommittedDispatchSequence,
        ).also {
            state.registrations[childName] = it
        }
    }

    private fun pendingSubject(childName: String, subjectName: String): MonitorSubjectSnapshot {
        val target = targetProvider.targetCount(childName, subjectName).coerceAtLeast(0)
        return MonitorSubjectSnapshot(
            name = subjectName,
            status = if (target == 0) MonitorSubjectStatus.COMPLETE else MonitorSubjectStatus.CONFIRMATION_PENDING,
            targetCount = target,
        )
    }

    private fun startInitialBaseline(accountId: String, childName: String) {
        if (refreshMonitoringDate()) return
        if (activeAccountId != accountId || !executionReady(accountId) || activeRegularRun != null) return
        val availableCredentials = credentials[accountId] ?: return
        val state = accounts[accountId] ?: return
        val child = state.registrations[childName] ?: return
        if (child.baselineStarted) return
        baselineRuns.remove(BaselineKey(accountId, childName))?.let { old ->
            old.invalidated = true
            detachBaselineConsumer(old, ConsumerTerminal.SUPERSEDED)
        }
        child.baselineStarted = true
        lastCycleStartedAtMillis = clock.monotonicMillis()
        cancelCadenceTimer()
        val run = BaselineRun(
            accountId = accountId,
            childName = childName,
            generation = child.generation,
            targetRevision = child.targetRevision,
            accountEpoch = accountEpoch,
            authEpoch = state.authEpoch,
            dateRevision = monitoringDateRevision,
            reportDate = monitoringDate,
            baselineBoundarySequence = child.baselineBoundarySequence,
            availableCredentials = availableCredentials,
        )
        baselineRuns[BaselineKey(accountId, childName)] = run
        loadBaselineSession(run)
    }

    private fun loadBaselineSession(run: BaselineRun) = releaseBaselineIo(
        run,
        operation = {
            repository.signIn(run.availableCredentials.email, run.availableCredentials.password)
        },
        onSuccess = { result ->
            when (result) {
                is LearningRepositoryResult.Success -> {
                    if (result.value.accountId.toString() != run.accountId) {
                        finishBaselineFailure(run, "인증 계정이 감시 계정과 일치하지 않습니다.")
                    } else {
                        run.session = result.value
                        loadBaselineCatalog(run)
                    }
                }
                is LearningRepositoryResult.TemporaryFailure -> finishBaselineFailure(run, result.error.message)
                is LearningRepositoryResult.AuthenticationDenied -> pauseForAuthenticationDenied(run.accountId)
            }
        },
        onFailure = { finishBaselineFailure(run, it.message) },
    )

    private fun loadBaselineCatalog(run: BaselineRun) {
        val session = run.session ?: return finishBaselineFailure(run, "로그인 상태를 확인하지 못했습니다.")
        releaseBaselineIo(
            run,
            operation = { repository.loadCatalog(session) },
            onSuccess = { result ->
                when (result) {
                    is LearningRepositoryResult.Success -> {
                        val state = accounts[run.accountId] ?: return@releaseBaselineIo
                        state.catalog = normalizedCatalog(result.value)
                        state.metadataRetryNeeded = false
                        state.metadataError = null
                        applyCatalogToChildren(run.accountId)
                        run.subscriptions = subscriptionsFor(run.accountId, run.childName)
                        run.nextSubscriptionIndex = 0
                        publishCatalogState(run.accountId)
                        loadNextBaselinePair(run)
                    }
                    is LearningRepositoryResult.TemporaryFailure -> retainCatalogAfterBaselineFailure(run, result.error.message)
                    is LearningRepositoryResult.AuthenticationDenied -> pauseForAuthenticationDenied(run.accountId)
                }
            },
            onFailure = { retainCatalogAfterBaselineFailure(run, it.message) },
        )
    }

    private fun retainCatalogAfterBaselineFailure(run: BaselineRun, message: String?) {
        val state = accounts[run.accountId] ?: return
        state.metadataRetryNeeded = true
        state.metadataError = message ?: "과목 구독 정보를 확인하지 못했습니다."
        val child = state.registrations[run.childName] ?: return
        if (state.catalog == null) {
            child.snapshot = child.snapshot.copy(error = state.metadataError)
            showStatus(run.accountId, child)
            publish()
            finishBaselineRun(run)
            return
        }
        applyCatalogToChildren(run.accountId)
        run.subscriptions = subscriptionsFor(run.accountId, run.childName)
        run.nextSubscriptionIndex = 0
        publishCatalogState(run.accountId)
        loadNextBaselinePair(run)
    }

    private fun loadNextBaselinePair(run: BaselineRun) {
        if (!baselineCanContinue(run)) return
        val child = accounts[run.accountId]?.registrations?.get(run.childName) ?: return
        while (run.nextSubscriptionIndex < run.subscriptions.size) {
            val subscription = run.subscriptions[run.nextSubscriptionIndex++]
            val target = targetProvider.targetCount(run.childName, subscription.subjectName).coerceAtLeast(0)
            val targetRevision = child.targetRevision
            val existing = child.snapshot.subjects[subscription.subjectName]
            if (target == 0 || (existing != null && existing.targetCount == target &&
                    existing.status != MonitorSubjectStatus.CONFIRMATION_PENDING)) {
                continue
            }
            val session = run.session ?: return finishBaselineFailure(run, "로그인 상태를 확인하지 못했습니다.")
            val consumer = BaselinePairConsumer(run, subscription, targetRevision, target)
            val joinableScreenWork = findJoinableScreenPair(run, subscription)
            if (joinableScreenWork != null) {
                joinableScreenWork.baselineConsumer = consumer
                run.activePairConsumer = consumer
                run.activePairWork = joinableScreenWork
                run.lastDispatchSequence = joinableScreenWork.dispatchSequence
            } else {
                val dispatchSequence = nextDispatchSequence()
                val work = ReportPairWork(
                    purpose = ReportPurpose.MONITOR_INITIAL,
                    accountId = run.accountId,
                    accountEpoch = run.accountEpoch,
                    authEpoch = run.authEpoch,
                    dateRevision = run.dateRevision,
                    reportDate = run.reportDate,
                    childName = subscription.childName,
                    subjectName = subscription.subjectName,
                    generation = run.generation,
                    targetRevision = targetRevision,
                    dispatchSequence = dispatchSequence,
                    eligibilityRevisionAtDispatch = runtimeEligibilityRevision,
                    permissionRevisionAtDispatch = runtimePermissionRevision,
                    session = session,
                    subscription = subscription,
                )
                work.baselineConsumer = consumer
                run.lastDispatchSequence = dispatchSequence
                run.activePairConsumer = consumer
                run.activePairWork = work
                dispatchReportPair(work)
            }
            return
        }
        finishBaselineRun(run)
    }

    private fun findJoinableScreenPair(
        run: BaselineRun,
        subscription: ChildSubjectSubscription,
    ): ReportPairWork? {
        if (!baselineCanContinue(run) || !executionReady(run.accountId) ||
            run.dateRevision != monitoringDateRevision || run.reportDate != monitoringDate
        ) return null
        return screenPairWork.values.firstOrNull { work ->
            val screenConsumer = work.screenConsumer
            val screenRun = screenConsumer?.run
            work.purpose == ReportPurpose.SCREEN && !work.physicalCompleted &&
                screenConsumer != null && screenConsumer.terminal == null &&
                screenRun != null && screenCanContinue(screenRun) && screenCanAffectMonitor(screenRun) &&
                screenRun.generation == work.generation &&
                screenRun.accountIdHint == run.accountId &&
                screenRun.accountEpochAtStart == run.accountEpoch &&
                screenRun.authEpochAtStart == run.authEpoch &&
                screenRun.dateRevisionAtStart == run.dateRevision &&
                screenRun.reportDate == run.reportDate &&
                work.accountId == run.accountId && work.accountEpoch == run.accountEpoch &&
                work.authEpoch == run.authEpoch && work.dateRevision == run.dateRevision &&
                work.reportDate == run.reportDate && work.childName == subscription.childName &&
                work.subjectName == subscription.subjectName &&
                work.dispatchSequence > run.baselineBoundarySequence
        }
    }

    private fun applyBaselinePair(
        run: BaselineRun,
        subscription: ChildSubjectSubscription,
        targetRevision: Long,
        target: Int,
        result: LearningRepositoryResult<SubjectReport>,
    ) {
        if (!baselineCanContinue(run)) return
        val child = accounts[run.accountId]?.registrations?.get(run.childName) ?: return
        if (child.targetRevision != targetRevision ||
            targetProvider.targetCount(run.childName, subscription.subjectName).coerceAtLeast(0) != target
        ) return
        if (result is LearningRepositoryResult.AuthenticationDenied) {
            pauseForAuthenticationDenied(run.accountId)
            return
        }
        if (child.baselineBoundarySequence != run.baselineBoundarySequence ||
            run.lastDispatchSequence <= run.baselineBoundarySequence
        ) return
        val existing = child.snapshot.subjects[subscription.subjectName]
        val checkedAt = if (result is LearningRepositoryResult.Success) clock.checkedAt() else child.snapshot.lastCheckedAt
        val subject = when (result) {
            is LearningRepositoryResult.Success -> {
                child.validatedSubjects += subscription.subjectName
                val report = result.value
                MonitorSubjectSnapshot(
                    name = subscription.subjectName,
                    status = if (report.activityCount >= target) MonitorSubjectStatus.COMPLETE else MonitorSubjectStatus.BELOW_TARGET,
                    targetCount = target,
                    activityCount = report.activityCount,
                    checkedAt = checkedAt,
                )
            }
            is LearningRepositoryResult.TemporaryFailure -> (existing ?: pendingSubject(run.childName, subscription.subjectName))
                .copy(error = result.error.message)
            is LearningRepositoryResult.AuthenticationDenied -> (existing ?: pendingSubject(run.childName, subscription.subjectName))
                .copy(error = result.error.message)
        }
        val subjects = LinkedHashMap(child.snapshot.subjects).apply { put(subscription.subjectName, subject) }
        child.snapshot = child.snapshot.copy(
            subjects = subjects,
            lastCheckedAt = checkedAt,
            error = aggregateChildError(subjects, accounts[run.accountId]?.metadataError),
        )
        showStatus(run.accountId, child)
        publish()
    }

    private fun finishBaselineFailure(run: BaselineRun, message: String?) {
        if (!baselineCanContinue(run)) return
        val child = accounts[run.accountId]?.registrations?.get(run.childName) ?: return
        child.snapshot = child.snapshot.copy(
            error = listOfNotNull(child.snapshot.error, message ?: "학습 정보를 확인하지 못했습니다.")
                .distinct().joinToString(" · "),
        )
        showStatus(run.accountId, child)
        publish()
        finishBaselineRun(run)
    }

    private fun <T> releaseBaselineIo(
        run: BaselineRun,
        operation: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Exception) -> Unit,
        subjectPair: Boolean = false,
    ) {
        if (refreshMonitoringDate()) return
        if (!baselineCanContinue(run)) return
        if (subjectPair) run.lastDispatchSequence = nextDispatchSequence()
        val permissionRevision = runtimePermissionRevision
        workerQueue.enqueue {
            if (run.invalidated) return@enqueue
            val beforeRequest = inspectRuntimeStatus()
            if (beforeRequest != null && !beforeRequest.permissionsReady) {
                commandQueue.enqueue { applyExecutionConditions(beforeRequest.accessibilityEnabled, beforeRequest.notificationsEnabled) }
                return@enqueue
            }
            if (run.invalidated || runtimePermissionRevision != permissionRevision) return@enqueue
            val outcome = try {
                Result.success(operation())
            } catch (exception: Exception) {
                Result.failure(exception)
            }
            val afterRequest = inspectRuntimeStatus()
            commandQueue.enqueue response@{
                afterRequest?.let {
                    applyExecutionConditions(it.accessibilityEnabled, it.notificationsEnabled)
                }
                val dateChanged = refreshMonitoringDate()
                if (dateChanged) return@response
                if (!baselineCanContinue(run)) return@response
                if (runtimePermissionRevision != permissionRevision) return@response
                outcome.fold(onSuccess) { error -> onFailure(error as? Exception ?: RuntimeException(error)) }
            }
        }
    }

    private fun finishBaselineRun(run: BaselineRun) {
        if (!baselineRuns.remove(BaselineKey(run.accountId, run.childName), run)) return
        detachBaselineConsumer(run, ConsumerTerminal.FINISHED)
        startUnattemptedBaselines()
        maybeStartRegularCycle()
    }

    private fun maybeStartRegularCycle() {
        if (refreshMonitoringDate()) return
        if (activeRegularRun != null) {
            cancelCadenceTimer()
            return
        }
        val subjectName = foregroundSubjectName()
        val accountId = activeAccountId
        if (!executionReady(accountId) || !screenOn || subjectName == null || accountId == null) {
            cancelCadenceTimer()
            return
        }
        val state = accounts[accountId]
        val availableCredentials = credentials[accountId]
        if (state == null || state.registrations.isEmpty() || availableCredentials == null) {
            cancelCadenceTimer()
            return
        }
        if (baselineRuns.isNotEmpty() || state.registrations.values.any { !it.baselineStarted }) {
            cancelCadenceTimer()
            startUnattemptedBaselines(accountId)
            return
        }
        if (!hasPollingCandidate(state, subjectName)) {
            cancelCadenceTimer()
            return
        }

        val cycleStart = lastCycleStartedAtMillis ?: clock.monotonicMillis().also {
            lastCycleStartedAtMillis = it
        }
        val elapsedMillis = BigInteger.valueOf(clock.monotonicMillis() - cycleStart).max(BigInteger.ZERO)
        val intervalMillis = state.intervalMinutes.multiply(MILLIS_PER_MINUTE)
        val remainingMillis = intervalMillis.subtract(elapsedMillis)
        if (remainingMillis.signum() > 0) {
            ensureCadenceTimer(cycleStart, remainingMillis)
            return
        }

        cancelCadenceTimer()
        val run = RegularRun(
            generation = regularRunIds.incrementAndGet(),
            accountId = accountId,
            accountEpoch = accountEpoch,
            authEpoch = state.authEpoch,
            startedAtMillis = clock.monotonicMillis(),
            foregroundSubject = subjectName,
            credentials = availableCredentials,
            registrationGenerations = state.registrations.mapValues { it.value.generation },
            dateRevision = monitoringDateRevision,
            reportDate = monitoringDate,
        )
        lastCycleStartedAtMillis = run.startedAtMillis
        activeRegularRun = run
        loadRegularSession(run)
    }

    private fun hasPollingCandidate(state: AccountState, subjectName: String): Boolean {
        if (state.catalog == null || state.metadataRetryNeeded) return true
        return state.catalog?.subscriptions.orEmpty().any { subscription ->
            if (subscription.subjectName != subjectName) return@any false
            val child = state.registrations[subscription.childName] ?: return@any false
            val target = targetProvider.targetCount(subscription.childName, subjectName).coerceAtLeast(0)
            val subject = child.snapshot.subjects[subjectName]
            target > 0 && subject?.status in setOf(
                MonitorSubjectStatus.CONFIRMATION_PENDING,
                MonitorSubjectStatus.BELOW_TARGET,
            )
        }
    }

    private fun ensureCadenceTimer(cycleStart: Long, remainingMillis: BigInteger) {
        if (cadenceTimer != null && scheduledTimerCycleStart == cycleStart) return
        cancelCadenceTimer()
        cadenceTimerGeneration += 1L
        val generation = cadenceTimerGeneration
        val delayMillis = remainingMillis.min(BigInteger.valueOf(MAX_TIMER_WAKE_MILLIS))
            .coerceAtLeast(BigInteger.ONE)
            .toLong()
        scheduledTimerCycleStart = cycleStart
        cadenceTimer = timerFactory.schedule(delayMillis) {
            commandQueue.enqueue {
                if (generation != cadenceTimerGeneration) return@enqueue
                cadenceTimer = null
                scheduledTimerCycleStart = null
                maybeStartRegularCycle()
            }
        }
    }

    private fun cancelCadenceTimer() {
        cadenceTimerGeneration += 1L
        cadenceTimer?.cancel()
        cadenceTimer = null
        scheduledTimerCycleStart = null
    }

    private fun refreshMonitoringDate(): Boolean {
        val today = clock.today()
        if (today == monitoringDate) {
            syncDateEvaluationTimer()
            return false
        }

        monitoringDate = today
        monitoringDateRevision += 1L
        cancelUnreleasedBaselines()
        invalidateActiveRegularRun()
        cancelCadenceTimer()
        accounts.forEach { (accountId, state) ->
            state.metadataRetryNeeded = true
            state.metadataError = null
            state.registrations.forEach { (childName, child) ->
                child.baselineStarted = false
                child.baselineBoundarySequence = lastCommittedDispatchSequence
                child.latestCompletionSubjectName = null
                child.validatedSubjects.clear()
                val subjectNames = state.catalog?.subscriptions
                    ?.filter { it.childName == childName }
                    ?.map { it.subjectName }
                    ?.distinct()
                    ?: child.snapshot.subjects.keys.toList()
                val subjects = LinkedHashMap<String, MonitorSubjectSnapshot>()
                subjectNames.forEach { subjectName ->
                    val target = targetProvider.targetCount(childName, subjectName).coerceAtLeast(0)
                    subjects[subjectName] = MonitorSubjectSnapshot(
                        name = subjectName,
                        status = if (target == 0) MonitorSubjectStatus.COMPLETE
                        else MonitorSubjectStatus.CONFIRMATION_PENDING,
                        targetCount = target,
                    )
                }
                child.snapshot = MonitorChildSnapshot(
                    childName = childName,
                    subjects = subjects,
                    error = pauseError(state),
                )
                if (accountId == activeAccountId && notificationsEnabled) showStatus(accountId, child)
            }
        }
        publish()
        rescheduleDateEvaluationTimer()
        startUnattemptedBaselines()
        return true
    }

    private fun pauseError(state: AccountState): String? = buildList {
        if (state.authenticationRequired) add("다시 로그인 필요")
        if (!accessibilityEnabled) add("접근성 권한 필요")
        if (!notificationsEnabled) add("알림 권한 필요")
    }.distinct().joinToString(" · ").ifBlank { null }

    private fun syncDateEvaluationTimer() {
        val hasActiveRegistrations = serviceLeaseAttached &&
            activeAccountId?.let(accounts::get)?.registrations?.isNotEmpty() == true
        if (!hasActiveRegistrations) {
            cancelDateEvaluationTimer()
            return
        }
        if (dateTimer != null) return

        dateTimerGeneration += 1L
        val generation = dateTimerGeneration
        val delayMillis = clock.millisUntilNextSeoulMidnight().coerceAtLeast(1L)
        dateTimer = dateTimerFactory.schedule(delayMillis) {
            commandQueue.enqueue {
                if (generation != dateTimerGeneration) return@enqueue
                dateTimer = null
                refreshMonitoringDate()
                syncDateEvaluationTimer()
                maybeStartRegularCycle()
            }
        }
    }

    private fun rescheduleDateEvaluationTimer() {
        cancelDateEvaluationTimer()
        syncDateEvaluationTimer()
    }

    private fun cancelDateEvaluationTimer() {
        dateTimerGeneration += 1L
        dateTimer?.cancel()
        dateTimer = null
    }

    private fun loadRegularSession(run: RegularRun) {
        if (!regularCanRelease(run)) return finishRegularRun(run)
        releaseRegularIo(
            run,
            operation = { repository.signIn(run.credentials.email, run.credentials.password) },
            onSuccess = { result ->
                when (result) {
                    is LearningRepositoryResult.Success -> {
                        if (result.value.accountId.toString() != run.accountId) {
                            failRegularRun(run, "인증 계정이 감시 계정과 일치하지 않습니다.")
                        } else {
                            run.session = result.value
                            loadRegularCatalog(run)
                        }
                    }
                    is LearningRepositoryResult.TemporaryFailure -> failRegularRun(run, result.error.message)
                    is LearningRepositoryResult.AuthenticationDenied -> pauseForAuthenticationDenied(run.accountId)
                }
            },
            onFailure = { failRegularRun(run, it.message) },
        )
    }

    private fun loadRegularCatalog(run: RegularRun) {
        if (!regularCanRelease(run)) return finishRegularRun(run)
        val session = run.session ?: return failRegularRun(run, "로그인 상태를 확인하지 못했습니다.")
        releaseRegularIo(
            run,
            operation = { repository.loadCatalog(session) },
            onSuccess = { result ->
                val state = accounts[run.accountId] ?: return@releaseRegularIo finishRegularRun(run)
                when (result) {
                    is LearningRepositoryResult.Success -> {
                        state.catalog = normalizedCatalog(result.value)
                        state.metadataRetryNeeded = false
                        state.metadataError = null
                        applyCatalogToChildren(run.accountId)
                        publishCatalogState(run.accountId)
                        loadRegularPairs(run)
                    }
                    is LearningRepositoryResult.TemporaryFailure -> handleRegularCatalogFailure(run, result.error.message)
                    is LearningRepositoryResult.AuthenticationDenied -> pauseForAuthenticationDenied(run.accountId)
                }
            },
            onFailure = { handleRegularCatalogFailure(run, it.message) },
        )
    }

    private fun handleRegularCatalogFailure(run: RegularRun, message: String?) {
        val state = accounts[run.accountId] ?: return finishRegularRun(run)
        state.metadataRetryNeeded = true
        state.metadataError = message ?: "과목 구독 정보를 확인하지 못했습니다."
        publishCatalogState(run.accountId)
        if (state.catalog == null) {
            finishRegularRun(run)
        } else {
            loadRegularPairs(run)
        }
    }

    private fun loadRegularPairs(run: RegularRun) {
        if (!regularCanRelease(run)) return finishRegularRun(run)
        val state = accounts[run.accountId] ?: return finishRegularRun(run)
        val catalog = state.catalog ?: return finishRegularRun(run)
        run.pairs = catalog.subscriptions.asSequence()
            .filter { it.subjectName == run.foregroundSubject }
            .filter { run.registrationGenerations.containsKey(it.childName) }
            .distinctBy { it.childName to it.subjectName }
            .mapNotNull { subscription ->
                val child = state.registrations[subscription.childName] ?: return@mapNotNull null
                val generation = run.registrationGenerations[subscription.childName] ?: return@mapNotNull null
                if (child.generation != generation) return@mapNotNull null
                val target = targetProvider.targetCount(subscription.childName, subscription.subjectName).coerceAtLeast(0)
                val current = child.snapshot.subjects[subscription.subjectName]
                if (target == 0 || current?.status !in setOf(
                        MonitorSubjectStatus.CONFIRMATION_PENDING,
                        MonitorSubjectStatus.BELOW_TARGET,
                    )
                ) {
                    null
                } else {
                    RegularPair(subscription, generation, child.targetRevision, target)
                }
            }
            .toList()
        run.nextPairIndex = 0
        loadNextRegularPair(run)
    }

    private fun loadNextRegularPair(run: RegularRun) {
        if (!regularCanRelease(run)) return finishRegularRun(run)
        while (run.nextPairIndex < run.pairs.size) {
            val pair = run.pairs[run.nextPairIndex++]
            val state = accounts[run.accountId] ?: return finishRegularRun(run)
            val child = state.registrations[pair.subscription.childName] ?: continue
            if (child.generation != pair.registrationGeneration) continue
            if (child.targetRevision != pair.targetRevision) continue
            val currentTarget = targetProvider.targetCount(pair.subscription.childName, pair.subscription.subjectName)
                .coerceAtLeast(0)
            val currentSubject = child.snapshot.subjects[pair.subscription.subjectName]
            if (currentTarget != pair.targetCount || currentTarget == 0 || currentSubject?.status !in setOf(
                    MonitorSubjectStatus.CONFIRMATION_PENDING,
                    MonitorSubjectStatus.BELOW_TARGET,
                )
            ) {
                continue
            }
            val session = run.session ?: return failRegularRun(run, "로그인 상태를 확인하지 못했습니다.")
            val dispatchSequence = nextDispatchSequence()
            val work = ReportPairWork(
                purpose = ReportPurpose.MONITOR_REGULAR,
                accountId = run.accountId,
                accountEpoch = run.accountEpoch,
                authEpoch = run.authEpoch,
                dateRevision = run.dateRevision,
                reportDate = run.reportDate,
                childName = pair.subscription.childName,
                subjectName = pair.subscription.subjectName,
                generation = run.generation,
                targetRevision = pair.targetRevision,
                dispatchSequence = dispatchSequence,
                eligibilityRevisionAtDispatch = runtimeEligibilityRevision,
                permissionRevisionAtDispatch = runtimePermissionRevision,
                session = session,
                subscription = pair.subscription,
            )
            val consumer = RegularPairConsumer(run, pair)
            work.regularConsumer = consumer
            run.activePairConsumer = consumer
            run.activePairWork = work
            run.lastDispatchSequence = dispatchSequence
            dispatchReportPair(work)
            return
        }
        finishRegularRun(run)
    }

    private fun applyRegularPair(
        run: RegularRun,
        pair: RegularPair,
        result: LearningRepositoryResult<SubjectReport>,
    ) {
        if (!regularCanApply(run)) return
        if (run.lastDispatchSequence == 0L) return
        val child = accounts[run.accountId]?.registrations?.get(pair.subscription.childName) ?: return
        if (child.generation != pair.registrationGeneration) return
        if (child.targetRevision != pair.targetRevision) return
        if (targetProvider.targetCount(pair.subscription.childName, pair.subscription.subjectName).coerceAtLeast(0) !=
            pair.targetCount
        ) return

        val previous = child.snapshot.subjects[pair.subscription.subjectName]
            ?: pendingSubject(pair.subscription.childName, pair.subscription.subjectName)
        val validated = when (result) {
            is LearningRepositoryResult.Success -> if (
                result.value.childName == pair.subscription.childName &&
                result.value.subjectName == pair.subscription.subjectName
            ) result else LearningRepositoryResult.TemporaryFailure(
                LearningRepositoryException("학습 보고서의 아이 또는 과목이 요청과 일치하지 않습니다."),
            )
            is LearningRepositoryResult.TemporaryFailure -> result
            is LearningRepositoryResult.AuthenticationDenied -> result
        }
        if (validated is LearningRepositoryResult.AuthenticationDenied) {
            pauseForAuthenticationDenied(run.accountId)
            return
        }
        val checkedAt = when (validated) {
            is LearningRepositoryResult.Success -> clock.checkedAt()
            else -> child.snapshot.lastCheckedAt
        }
        var completion = false
        val updatedSubject = when (validated) {
            is LearningRepositoryResult.Success -> {
                child.validatedSubjects += pair.subscription.subjectName
                val report = validated.value
                val status = if (report.activityCount >= pair.targetCount) {
                    MonitorSubjectStatus.COMPLETE
                } else {
                    MonitorSubjectStatus.BELOW_TARGET
                }
                completion = previous.status == MonitorSubjectStatus.BELOW_TARGET &&
                    status == MonitorSubjectStatus.COMPLETE
                if (completion) child.latestCompletionSubjectName = pair.subscription.subjectName
                MonitorSubjectSnapshot(
                    name = pair.subscription.subjectName,
                    status = status,
                    targetCount = pair.targetCount,
                    activityCount = report.activityCount,
                    checkedAt = checkedAt,
                )
            }
            is LearningRepositoryResult.TemporaryFailure -> previous.copy(error = validated.error.message)
            is LearningRepositoryResult.AuthenticationDenied -> previous.copy(error = validated.error.message)
        }
        val subjects = LinkedHashMap(child.snapshot.subjects).apply {
            put(pair.subscription.subjectName, updatedSubject)
        }
        child.snapshot = child.snapshot.copy(
            subjects = subjects,
            lastCheckedAt = checkedAt,
            error = aggregateChildError(subjects, accounts[run.accountId]?.metadataError),
        )
        showStatus(run.accountId, child, completion)
        publish()
    }

    private fun failRegularRun(run: RegularRun, message: String?) {
        if (!regularCanApply(run)) return finishRegularRun(run)
        val state = accounts[run.accountId]
        val error = message ?: "학습 정보를 확인하지 못했습니다."
        run.registrationGenerations.forEach { (childName, generation) ->
            val child = state?.registrations?.get(childName) ?: return@forEach
            if (child.generation != generation) return@forEach
            child.snapshot = child.snapshot.copy(
                error = listOfNotNull(aggregateChildError(child.snapshot.subjects, state.metadataError), error)
                    .distinct().joinToString(" · "),
            )
            showStatus(run.accountId, child)
        }
        publish()
        finishRegularRun(run)
    }

    private fun finishRegularRun(run: RegularRun) {
        if (activeRegularRun !== run) return
        activeRegularRun = null
        if (executionReady()) startUnattemptedBaselines()
        maybeStartRegularCycle()
    }

    private fun <T> releaseRegularIo(
        run: RegularRun,
        operation: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Exception) -> Unit,
        subjectPair: Boolean = false,
    ) {
        if (refreshMonitoringDate()) return
        if (!regularCanRelease(run)) return finishRegularRun(run)
        if (subjectPair) run.lastDispatchSequence = nextDispatchSequence()
        val eligibilityRevision = runtimeEligibilityRevision
        val permissionRevision = runtimePermissionRevision
        workerQueue.enqueue {
            val beforeRequest = inspectRuntimeStatus()
            if (beforeRequest != null && !beforeRequest.permissionsReady) {
                commandQueue.enqueue { applyExecutionConditions(beforeRequest.accessibilityEnabled, beforeRequest.notificationsEnabled) }
                return@enqueue
            }
            val outcome: Result<T>? = if (runtimeEligibilityRevision != eligibilityRevision ||
                runtimePermissionRevision != permissionRevision
            ) {
                null
            } else {
                try {
                    Result.success(operation())
                } catch (exception: Exception) {
                    Result.failure(exception)
                }
            }
            val afterRequest = inspectRuntimeStatus()
            commandQueue.enqueue response@{
                afterRequest?.let {
                    applyExecutionConditions(it.accessibilityEnabled, it.notificationsEnabled)
                }
                val dateChanged = refreshMonitoringDate()
                if (dateChanged) return@response
                if (activeRegularRun !== run) return@response
                if (!regularCanApply(run) || runtimePermissionRevision != permissionRevision || outcome == null) {
                    return@response finishRegularRun(run)
                }
                outcome.fold(onSuccess) { error -> onFailure(error as? Exception ?: RuntimeException(error)) }
            }
        }
    }

    private fun inspectRuntimeStatus(): MonitorExecutionStatus? = executionStatusProbe?.let { probe ->
        runCatching { probe.inspect() }
            .getOrElse { MonitorExecutionStatus(accessibilityEnabled = false, notificationsEnabled = false) }
    }

    private fun regularCanApply(run: RegularRun): Boolean =
        activeRegularRun === run && !run.invalidated && run.accountEpoch == accountEpoch &&
            run.accountId == activeAccountId &&
            accounts[run.accountId]?.authEpoch == run.authEpoch && executionReady(run.accountId) &&
            run.dateRevision == monitoringDateRevision && run.reportDate == monitoringDate

    private fun regularCanRelease(run: RegularRun): Boolean =
        regularCanApply(run) && screenOn && foregroundSubjectName() == run.foregroundSubject

    private fun foregroundSubjectName(): String? = when (foregroundPackageName) {
        "com.enuma.todohangeul" -> "한글"
        "com.enuma.todoenglish" -> "영어"
        "com.enuma.todomath" -> "수학"
        else -> null
    }

    private fun normalizedCatalog(catalog: LearningCatalog): LearningCatalog {
        val supported = supportedSubjects().toSet()
        val subscriptions = catalog.subscriptions
            .filter { it.childName.isNotBlank() && it.subjectName in supported }
            .distinctBy { it.childName to it.subjectName }
        return LearningCatalog(catalog.childOrder.distinct(), subscriptions)
    }

    private fun subscriptionsFor(accountId: String, childName: String): List<ChildSubjectSubscription> =
        accounts[accountId]?.catalog?.subscriptions.orEmpty().filter { it.childName == childName }

    private fun applyCatalogToChildren(accountId: String) {
        val state = accounts[accountId] ?: return
        val subscriptionsByChild = state.catalog?.subscriptions.orEmpty().groupBy { it.childName }
        state.registrations.forEach { (childName, child) ->
            val subjects = LinkedHashMap<String, MonitorSubjectSnapshot>()
            subscriptionsByChild[childName].orEmpty().forEach { subscription ->
                val target = targetProvider.targetCount(childName, subscription.subjectName).coerceAtLeast(0)
                val previous = child.snapshot.subjects[subscription.subjectName]
                val current = when {
                    target == 0 -> MonitorSubjectSnapshot(
                        name = subscription.subjectName,
                        status = MonitorSubjectStatus.COMPLETE,
                        targetCount = 0,
                        activityCount = previous?.activityCount,
                        checkedAt = previous?.checkedAt,
                        error = previous?.error,
                    )
                    previous?.targetCount == target -> previous
                    previous?.activityCount != null && subscription.subjectName in child.validatedSubjects -> previous.copy(
                        targetCount = target,
                        status = if (previous.activityCount >= target) {
                            MonitorSubjectStatus.COMPLETE
                        } else {
                            MonitorSubjectStatus.BELOW_TARGET
                        },
                    )
                    previous != null -> previous.copy(
                        targetCount = target,
                        status = MonitorSubjectStatus.CONFIRMATION_PENDING,
                    )
                    else -> pendingSubject(childName, subscription.subjectName).copy(error = previous?.error)
                }
                subjects[subscription.subjectName] = current
            }
            child.snapshot = child.snapshot.copy(
                subjects = subjects,
                error = aggregateChildError(subjects, state.metadataError),
            )
        }
    }

    private fun publishCatalogState(accountId: String) {
        val state = accounts[accountId] ?: return
        state.registrations.values.forEach { child ->
            child.snapshot = child.snapshot.copy(
                error = aggregateChildError(child.snapshot.subjects, state.metadataError),
            )
            showStatus(accountId, child)
        }
        if (activeAccountId == accountId) publish()
    }

    private fun aggregateChildError(
        subjects: Map<String, MonitorSubjectSnapshot>,
        metadataError: String?,
    ): String? = (subjects.values.mapNotNull { it.error } + listOfNotNull(metadataError))
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

    private fun showStatus(accountId: String, child: MutableChild, completion: Boolean = false) {
        notificationSink.showStatus(
            accountId = accountId,
            child = immutableChild(child.snapshot),
            completion = completion,
            completionSubjectName = child.latestCompletionSubjectName,
        )
    }

    private fun baselineCanContinue(run: BaselineRun): Boolean {
        if (baselineRuns[BaselineKey(run.accountId, run.childName)] !== run) return false
        if (run.accountEpoch != accountEpoch || run.accountId != activeAccountId || !executionReady(run.accountId)) return false
        val state = accounts[run.accountId] ?: return false
        if (state.authEpoch != run.authEpoch || run.dateRevision != monitoringDateRevision ||
            run.reportDate != monitoringDate
        ) return false
        val child = state.registrations[run.childName] ?: return false
        return child.generation == run.generation && child.targetRevision == run.targetRevision && child.baselineStarted
    }

    private fun cancelUnreleasedBaselines() {
        val activeRuns = baselineRuns.values.toList()
        baselineRuns.clear()
        activeRuns.forEach { run ->
            run.invalidated = true
            detachBaselineConsumer(run, ConsumerTerminal.INVALIDATED)
            accounts[run.accountId]?.registrations?.get(run.childName)
                ?.takeIf { it.generation == run.generation }
                ?.baselineStarted = false
        }
    }

    private fun publish() {
        val accountId = activeAccountId
        val state = accountId?.let(accounts::get)
        val next = immutableSnapshot(LearningMonitorSnapshot(
            accountId = accountId,
            intervalMinutes = state?.intervalMinutes ?: java.math.BigInteger.ONE,
            registeredChildren = state?.registrations?.keys?.toSet().orEmpty(),
            children = state?.registrations?.mapValues { (_, child) -> child.snapshot }?.toMap().orEmpty(),
            serviceLeaseAttached = serviceLeaseAttached,
            accessibilityEnabled = accessibilityEnabled,
            notificationsEnabled = notificationsEnabled,
            authenticationRequired = state?.authenticationRequired == true,
            monitoringPaused = !permissionsAvailable() || state?.authenticationRequired == true,
            connectionWaiting = accessibilityEnabled && notificationsEnabled && !serviceLeaseAttached &&
                state?.authenticationRequired != true,
            foregroundObservationPending = serviceLeaseAttached && this.foregroundObservationPending,
            revision = currentSnapshot.revision + 1L,
        ))
        currentSnapshot = next
        listeners.toList().forEach { slot -> deliver(slot, next) }
        syncDateEvaluationTimer()
    }

    private fun startUnattemptedBaselines(accountId: String? = activeAccountId) {
        val id = accountId ?: return
        if (id != activeAccountId || !executionReady(id)) return
        val state = accounts[id] ?: return
        if (state.recoveryPending) prepareRecoveryBaseline(state)
        state.registrations.forEach { (childName, child) ->
            if (!child.baselineStarted) startInitialBaseline(id, childName)
        }
    }

    private fun permissionsAvailable() = accessibilityEnabled && notificationsEnabled

    private fun executionReady(accountId: String? = activeAccountId): Boolean =
        permissionsAvailable() && serviceLeaseAttached && accountId != null && accountId == activeAccountId &&
            accounts[accountId]?.authenticationRequired != true

    private fun immutableChild(child: MonitorChildSnapshot) = child.copy(
        subjects = Collections.unmodifiableMap(LinkedHashMap(child.subjects)),
    )

    private fun deliver(slot: ListenerSlot, snapshot: LearningMonitorSnapshot) {
        val captured = immutableSnapshot(snapshot)
        callbackQueue.enqueue {
            if (slot.active.get()) slot.listener(captured)
        }
    }

    private fun immutableSnapshot(snapshot: LearningMonitorSnapshot) = snapshot.copy(
        registeredChildren = Collections.unmodifiableSet(LinkedHashSet(snapshot.registeredChildren)),
        children = Collections.unmodifiableMap(
            snapshot.children.entries.associateTo(LinkedHashMap()) { (name, child) -> name to immutableChild(child) },
        ),
    )

    private fun supportedSubjects(): List<String> = listOf("한글", "수학", "영어")

    private companion object {
        val MILLIS_PER_MINUTE: BigInteger = BigInteger.valueOf(60_000L)
        const val MAX_TIMER_WAKE_MILLIS = 30_000L
    }
}
