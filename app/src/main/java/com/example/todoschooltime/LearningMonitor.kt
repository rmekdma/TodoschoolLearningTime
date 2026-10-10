package com.example.todoschooltime

import java.time.LocalDate
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
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
    val revision: Long = 0,
)

object MonitorNotificationText {
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
            "${subject.name} $state"
        }
        if (subjectLines.isEmpty()) add("구독 과목 확인 대기") else addAll(subjectLines)
        child.lastCheckedAt?.let(::add)
        child.error?.let { add("오류: $it") }
    }.joinToString("\n")

    fun summary(child: MonitorChildSnapshot): String = child.subjects.values.joinToString(" · ") { subject ->
        when (subject.status) {
            MonitorSubjectStatus.CONFIRMATION_PENDING -> "${subject.name} 확인 대기"
            MonitorSubjectStatus.BELOW_TARGET -> "${subject.name} ${subject.activityCount ?: "?"}/${subject.targetCount}"
            MonitorSubjectStatus.COMPLETE -> if (subject.activityCount == null) {
                "${subject.name} 완료"
            } else {
                "${subject.name} ${subject.activityCount}/${subject.targetCount}"
            }
        }
    }.ifBlank { "구독 과목 확인 대기" }
}

fun interface MonitorTaskQueue {
    fun enqueue(task: () -> Unit)
}

interface MonitorClock {
    fun today(): LocalDate
    fun checkedAt(): String
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

interface ChildNotificationSink {
    fun showStatus(accountId: String, child: MonitorChildSnapshot, completion: Boolean)
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
}

/**
 * Process-owned registration and initial-baseline coordinator.
 * Commands and repository outcomes are serialized through [commandQueue].
 */
class LearningMonitorEngine(
    private val commandQueue: MonitorTaskQueue,
    private val workerQueue: MonitorTaskQueue,
    private val callbackQueue: MonitorTaskQueue,
    private val clock: MonitorClock,
    private val store: MonitorStore,
    private val repository: MonitorRepository,
    private val targetProvider: MonitorTargetProvider,
    private val notificationSink: ChildNotificationSink,
) : LearningMonitor {
    private data class Credentials(val email: String, val password: String)

    private class MutableChild(
        val generation: Long,
        var snapshot: MonitorChildSnapshot,
        var baselineStarted: Boolean = false,
    )

    private class AccountState(
        var intervalMinutes: java.math.BigInteger,
        val registrations: LinkedHashMap<String, MutableChild> = linkedMapOf(),
        val generations: MutableMap<String, Long> = mutableMapOf(),
        var loaded: Boolean = false,
    )

    private data class PairOutcome(
        val subjectName: String,
        val targetCount: Int,
        val result: LearningRepositoryResult<SubjectReport>?,
    )

    private data class BaselineResult(
        val accountId: String,
        val childName: String,
        val generation: Long,
        val accountEpoch: Long,
        val outcomes: List<PairOutcome> = emptyList(),
        val metadataFailure: String? = null,
    )

    private class ListenerSlot(val listener: (LearningMonitorSnapshot) -> Unit) {
        val active = AtomicBoolean(true)
    }

    private val accounts = mutableMapOf<String, AccountState>()
    private val credentials = mutableMapOf<String, Credentials>()
    private val listeners = mutableListOf<ListenerSlot>()
    private var activeAccountId: String? = null
    private var accountEpoch: Long = 0
    private var serviceLeaseAttached: Boolean = false
    private var accessibilityEnabled: Boolean = false
    private var notificationsEnabled: Boolean = false
    private var foregroundPackageName: String? = null

    @Volatile
    private var currentSnapshot = LearningMonitorSnapshot()

    override fun snapshot(): LearningMonitorSnapshot = currentSnapshot

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
            if (switchingAccounts) {
                accountEpoch += 1
                activeAccountId = accountId
            }
            val firstCredentialsForAccount = credentials[accountId] == null
            credentials[accountId] = Credentials(email, password)
            val state = accountState(accountId)
            if (switchingAccounts) {
                state.registrations.keys.toList().forEach { childName -> resetChild(state, childName) }
            }
            publish()
            if (switchingAccounts || firstCredentialsForAccount) {
                startUnattemptedBaselines(accountId)
            }
        }
    }

    /** Enqueue an independent child registration and a fresh all-subject baseline. */
    override fun registerChild(accountId: String, childName: String) {
        require(accountId.isNotBlank())
        require(childName.isNotBlank())
        commandQueue.enqueue {
            val state = accountState(accountId)
            val child = resetChild(state, childName)
            store.saveRegisteredChildren(accountId, state.registrations.keys)
            notificationSink.showStatus(accountId, immutableChild(child.snapshot), completion = false)
            publish()
            startInitialBaseline(accountId, childName)
        }
    }

    /** Removing one child invalidates only that child's in-flight baseline. */
    override fun unregisterChild(accountId: String, childName: String) {
        commandQueue.enqueue {
            val state = accountState(accountId)
            val old = state.registrations.remove(childName)
            state.generations[childName] = (state.generations[childName] ?: old?.generation ?: 0L) + 1L
            store.saveRegisteredChildren(accountId, state.registrations.keys)
            notificationSink.cancel(accountId, childName)
            publish()
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
        }
    }

    /** AccessibilityService attach/detach is an execution lease, never engine ownership. */
    internal fun attachServiceLease() {
        commandQueue.enqueue {
            if (!serviceLeaseAttached) {
                serviceLeaseAttached = true
                publish()
                startUnattemptedBaselines()
            }
        }
    }

    /** The accessibility setting and notification permission are separate from service binding. */
    internal fun executionConditionsChanged(accessibilityEnabled: Boolean, notificationsEnabled: Boolean) {
        commandQueue.enqueue {
            val wasReady = executionReady()
            this.accessibilityEnabled = accessibilityEnabled
            this.notificationsEnabled = notificationsEnabled
            if (!wasReady && executionReady()) startUnattemptedBaselines()
        }
    }

    internal fun detachServiceLease() {
        commandQueue.enqueue {
            if (serviceLeaseAttached) {
                serviceLeaseAttached = false
                publish()
            }
        }
    }

    /** Private AccessibilityService runtime input for the later foreground polling slice. */
    internal fun foregroundPackageChanged(packageName: String?) {
        commandQueue.enqueue { foregroundPackageName = packageName }
    }

    private fun accountState(accountId: String): AccountState = accounts.getOrPut(accountId) {
        AccountState(intervalMinutes = store.intervalMinutes(accountId)).also { state ->
            state.loaded = true
            store.registeredChildren(accountId).forEach { childName ->
                val child = resetChild(state, childName)
                state.registrations[childName] = child
            }
        }
    }

    private fun resetChild(state: AccountState, childName: String): MutableChild {
        val generation = (state.generations[childName] ?: state.registrations[childName]?.generation ?: 0L) + 1L
        state.generations[childName] = generation
        val subjects = supportedSubjects().associateWith { name ->
            pendingSubject(childName, name)
        }
        return MutableChild(generation, MonitorChildSnapshot(childName, subjects)).also {
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
        if (activeAccountId != accountId || !executionReady()) return
        val availableCredentials = credentials[accountId] ?: return
        val state = accounts[accountId] ?: return
        val child = state.registrations[childName] ?: return
        if (child.baselineStarted) return
        child.baselineStarted = true
        val epoch = accountEpoch
        val generation = child.generation
        workerQueue.enqueue {
            val result = try {
                loadBaseline(accountId, childName, generation, epoch, availableCredentials)
            } catch (exception: Exception) {
                baselineFailure(accountId, childName, generation, epoch, exception.message)
            }
            commandQueue.enqueue { applyBaseline(result) }
        }
    }

    private fun loadBaseline(
        accountId: String,
        childName: String,
        generation: Long,
        epoch: Long,
        availableCredentials: Credentials,
    ): BaselineResult {
        val session = when (val signIn = repository.signIn(availableCredentials.email, availableCredentials.password)) {
            is LearningRepositoryResult.Success -> signIn.value
            is LearningRepositoryResult.TemporaryFailure -> return baselineFailure(accountId, childName, generation, epoch, signIn.error.message)
            is LearningRepositoryResult.AuthenticationDenied -> return baselineFailure(accountId, childName, generation, epoch, signIn.error.message)
        }
        if (session.accountId.toString() != accountId) {
            return baselineFailure(
                accountId,
                childName,
                generation,
                epoch,
                "인증 계정이 감시 계정과 일치하지 않습니다.",
            )
        }
        val catalog = when (val loaded = repository.loadCatalog(session)) {
            is LearningRepositoryResult.Success -> loaded.value
            is LearningRepositoryResult.TemporaryFailure -> return baselineFailure(accountId, childName, generation, epoch, loaded.error.message)
            is LearningRepositoryResult.AuthenticationDenied -> return baselineFailure(accountId, childName, generation, epoch, loaded.error.message)
        }
        val subscriptions = catalog.subscriptions.filter { it.childName == childName }
        val outcomes = subscriptions.map { subscription ->
            val target = targetProvider.targetCount(childName, subscription.subjectName).coerceAtLeast(0)
            PairOutcome(
                subjectName = subscription.subjectName,
                targetCount = target,
                result = if (target == 0) {
                    null
                } else {
                    when (val report = repository.loadSubjectReport(session, subscription, clock.today())) {
                        is LearningRepositoryResult.Success -> if (
                            report.value.childName == childName && report.value.subjectName == subscription.subjectName
                        ) {
                            report
                        } else {
                            LearningRepositoryResult.TemporaryFailure(
                                LearningRepositoryException("학습 보고서의 아이 또는 과목이 요청과 일치하지 않습니다."),
                            )
                        }
                        is LearningRepositoryResult.TemporaryFailure -> report
                        is LearningRepositoryResult.AuthenticationDenied -> report
                    }
                },
            )
        }
        return BaselineResult(accountId, childName, generation, epoch, outcomes)
    }

    private fun baselineFailure(
        accountId: String,
        childName: String,
        generation: Long,
        epoch: Long,
        message: String?,
    ) = BaselineResult(
        accountId = accountId,
        childName = childName,
        generation = generation,
        accountEpoch = epoch,
        metadataFailure = message ?: "학습 정보를 확인하지 못했습니다.",
    )

    private fun applyBaseline(result: BaselineResult) {
        if (result.accountEpoch != accountEpoch || result.accountId != activeAccountId) return
        val state = accounts[result.accountId] ?: return
        val child = state.registrations[result.childName] ?: return
        if (child.generation != result.generation) return

        val oldCheckedAt = child.snapshot.lastCheckedAt
        if (result.metadataFailure != null) {
            val pending = supportedSubjects().associateWith { name ->
                pendingSubject(result.childName, name)
            }
            child.snapshot = MonitorChildSnapshot(
                childName = result.childName,
                subjects = pending,
                lastCheckedAt = oldCheckedAt,
                error = result.metadataFailure,
            )
        } else {
            val checkedAt = if (result.outcomes.any { it.result is LearningRepositoryResult.Success }) clock.checkedAt() else oldCheckedAt
            val subjects = result.outcomes.associate { outcome ->
                val subject = when (val report = outcome.result) {
                    null -> MonitorSubjectSnapshot(
                        name = outcome.subjectName,
                        status = MonitorSubjectStatus.COMPLETE,
                        targetCount = 0,
                    )
                    is LearningRepositoryResult.Success -> {
                        val reportValue = report.value
                        val status = if (reportValue.activityCount >= outcome.targetCount) {
                            MonitorSubjectStatus.COMPLETE
                        } else {
                            MonitorSubjectStatus.BELOW_TARGET
                        }
                        MonitorSubjectSnapshot(
                            name = outcome.subjectName,
                            status = status,
                            targetCount = outcome.targetCount,
                            activityCount = reportValue.activityCount,
                            checkedAt = clock.checkedAt(),
                        )
                    }
                    is LearningRepositoryResult.TemporaryFailure -> MonitorSubjectSnapshot(
                        name = outcome.subjectName,
                        status = MonitorSubjectStatus.CONFIRMATION_PENDING,
                        targetCount = outcome.targetCount,
                        error = report.error.message,
                    )
                    is LearningRepositoryResult.AuthenticationDenied -> MonitorSubjectSnapshot(
                        name = outcome.subjectName,
                        status = MonitorSubjectStatus.CONFIRMATION_PENDING,
                        targetCount = outcome.targetCount,
                        error = report.error.message,
                    )
                }
                outcome.subjectName to subject
            }
            val errors = subjects.values.mapNotNull { it.error }.distinct()
            child.snapshot = MonitorChildSnapshot(
                childName = result.childName,
                subjects = subjects,
                lastCheckedAt = checkedAt,
                error = errors.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            )
        }
        notificationSink.showStatus(result.accountId, immutableChild(child.snapshot), completion = false)
        publish()
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
            revision = currentSnapshot.revision + 1L,
        ))
        currentSnapshot = next
        listeners.toList().forEach { slot -> deliver(slot, next) }
    }

    private fun startUnattemptedBaselines(accountId: String? = activeAccountId) {
        if (!executionReady()) return
        val id = accountId ?: return
        accounts[id]?.registrations?.forEach { (childName, child) ->
            if (!child.baselineStarted) startInitialBaseline(id, childName)
        }
    }

    private fun executionReady() = accessibilityEnabled && notificationsEnabled && serviceLeaseAttached

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
}
