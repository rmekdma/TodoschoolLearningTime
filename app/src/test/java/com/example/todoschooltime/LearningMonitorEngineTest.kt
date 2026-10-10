package com.example.todoschooltime

import java.time.LocalDate
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMonitorEngineTest {
    @Test
    fun registrationSetsSilentPerSubjectBaselinesAndKeepsPartialFailureUnknown() {
        val fixture = Fixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 6, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
                "수학" to LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("잠시 후 다시 확인")),
            ),
            targets = mapOf("한글" to 6, "수학" to 5, "영어" to 8),
        )
        fixture.startAndRegister("철수")

        val child = fixture.engine.snapshot().children.getValue("철수")
        assertEquals(MonitorSubjectStatus.COMPLETE, child.subjects.getValue("한글").status)
        assertEquals(6, child.subjects.getValue("한글").activityCount)
        assertEquals(MonitorSubjectStatus.CONFIRMATION_PENDING, child.subjects.getValue("수학").status)
        assertNull(child.subjects.getValue("수학").activityCount)
        assertEquals("잠시 후 다시 확인", child.subjects.getValue("수학").error)
        assertEquals(MonitorSubjectStatus.BELOW_TARGET, child.subjects.getValue("영어").status)
        assertTrue(fixture.notifications.updates.all { !it.completion })
        assertEquals(setOf("한글", "수학", "영어"), fixture.repository.reportedSubjects.toSet())
        assertTrue(fixture.repository.requestedDates.all { it.toString() == "2026-10-10" })
        assertUnmodifiable(child.subjects)
        assertUnmodifiable(fixture.engine.snapshot().children)
        assertUnmodifiable(fixture.engine.snapshot().registeredChildren)
    }

    @Test
    fun targetZeroIsLocallyCompleteWithoutInventingAReportCount() {
        val fixture = Fixture(
            reports = emptyMap(),
            targets = mapOf("한글" to 0),
        )
        fixture.startAndRegister("철수")

        val hangul = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("한글")
        assertEquals(MonitorSubjectStatus.COMPLETE, hangul.status)
        assertNull(hangul.activityCount)
        assertFalse(fixture.repository.reportedSubjects.contains("한글"))
    }

    @Test
    fun metadataFailureLeavesSubscriptionsUnknownInsteadOfTreatingThemAsAbsent() {
        val fixture = Fixture(reports = emptyMap(), catalogFailure = LearningRepositoryException("catalog unavailable"))
        fixture.startAndRegister("철수")

        val child = fixture.engine.snapshot().children.getValue("철수")
        assertEquals(setOf("한글", "수학", "영어"), child.subjects.keys)
        assertTrue(child.subjects.values.all { it.status == MonitorSubjectStatus.CONFIRMATION_PENDING })
        assertTrue(child.subjects.values.all { it.activityCount == null })
        assertEquals("catalog unavailable", child.error)
        assertTrue(fixture.repository.reportedSubjects.isEmpty())
    }

    @Test
    fun initialScanWaitsForAccessibilityPermissionNotificationPermissionAndBoundLease() {
        val fixture = Fixture(reports = emptyMap())
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.engine.executionConditionsChanged(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.commands.drain()

        assertTrue(fixture.workers.isEmpty())
        fixture.engine.attachServiceLease()
        fixture.commands.drain()
        assertEquals(1, fixture.workers.size())
        fixture.workers.drain()
        fixture.commands.drain()
        assertEquals("account-A", fixture.engine.snapshot().accountId)
    }

    @Test
    fun restoredRegistrationGetsFreshSilentBaselineAfterCredentialsAndRuntimeAreAvailable() {
        val fixture = Fixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 9, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 7, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 12, 0)),
            ),
            storedChildren = setOf("철수"),
        )
        fixture.prepareRuntime()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        assertNull(fixture.engine.snapshot().accountId)
        fixture.commands.drain()
        assertEquals(1, fixture.workers.size())
        fixture.workers.drain()
        fixture.commands.drain()

        val restored = fixture.engine.snapshot()
        assertEquals(setOf("철수"), restored.registeredChildren)
        assertEquals(3, fixture.repository.reportedSubjects.size)
        assertTrue(fixture.notifications.updates.all { !it.completion })
        assertTrue(restored.children.getValue("철수").subjects.values.all {
            it.status == MonitorSubjectStatus.COMPLETE
        })
    }

    @Test
    fun intervalCommandIsAsyncAndRetainsAnExactUnboundedPositiveInteger() {
        val fixture = Fixture(reports = emptyMap())
        fixture.prepareRuntime()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.commands.drain()
        val interval = BigInteger("98765432109876543210987654321098765432109876543210")
        fixture.engine.setIntervalMinutes("account-A", interval)
        assertEquals(BigInteger.ONE, fixture.engine.snapshot().intervalMinutes)
        fixture.commands.drain()

        assertEquals(interval, fixture.engine.snapshot().intervalMinutes)
        assertEquals(interval, fixture.store.intervalMinutes("account-A"))
    }

    @Test
    fun unregisteringOneChildDiscardsItsInFlightResultsAndPreservesOthers() {
        val fixture = Fixture(reports = mapOf(
            "한글" to LearningRepositoryResult.Success(SubjectReport("영희", "한글", 1, 0)),
            "수학" to LearningRepositoryResult.Success(SubjectReport("영희", "수학", 1, 0)),
            "영어" to LearningRepositoryResult.Success(SubjectReport("영희", "영어", 1, 0)),
        ))
        fixture.prepareRuntime()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.engine.registerChild("account-A", "영희")
        fixture.commands.drain()
        fixture.engine.unregisterChild("account-A", "철수")
        fixture.commands.drain()
        fixture.workers.drain()
        fixture.commands.drain()

        val snapshot = fixture.engine.snapshot()
        assertEquals(setOf("영희"), snapshot.registeredChildren)
        assertFalse(snapshot.children.containsKey("철수"))
        assertEquals(MonitorSubjectStatus.BELOW_TARGET,
            snapshot.children.getValue("영희").subjects.getValue("한글").status)
        assertEquals(listOf("account-A" to "철수"), fixture.notifications.cancellations)
    }

    @Test
    fun accountMismatchCannotApplyAResultFromAnotherSession() {
        val fixture = Fixture(reports = emptyMap(), signedInAccount = "account-B")
        fixture.startAndRegister("철수")

        val child = fixture.engine.snapshot().children.getValue("철수")
        assertTrue(child.subjects.values.all { it.activityCount == null })
        assertEquals("인증 계정이 감시 계정과 일치하지 않습니다.", child.error)
        assertTrue(fixture.repository.reportedSubjects.isEmpty())
    }

    @Test
    fun observeCapturesInitialSnapshotInQueueOrderAndClosedListenerGetsNoQueuedCallbacks() {
        val fixture = Fixture(reports = emptyMap())
        fixture.prepareRuntime()
        val revisions = mutableListOf<Long>()
        val subscription = fixture.engine.observe { revisions += it.revision }
        fixture.commands.drain()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.commands.drain()
        fixture.workers.drain()
        fixture.commands.drain()
        fixture.ui.drain()
        assertTrue(revisions.zipWithNext().all { (older, newer) -> newer > older })

        val closedCalls = mutableListOf<LearningMonitorSnapshot>()
        val closed = fixture.engine.observe { closedCalls += it }
        fixture.commands.drain()
        closed.close()
        closed.close()
        fixture.commands.drain()
        fixture.ui.drain()
        assertTrue(closedCalls.isEmpty())
    }

    private class Fixture(
        reports: Map<String, LearningRepositoryResult<SubjectReport>>,
        targets: Map<String, Int> = emptyMap(),
        catalogFailure: Exception? = null,
        signedInAccount: String = "account-A",
        storedChildren: Set<String> = emptySet(),
    ) {
        val commands = ManualQueue()
        val workers = ManualQueue()
        val ui = ManualQueue()
        val repository = FakeRepository(reports, catalogFailure, signedInAccount)
        val notifications = FakeNotifications()
        val store = FakeMonitorStore(storedChildren)
        val engine = LearningMonitorEngine(
            commandQueue = commands,
            workerQueue = workers,
            callbackQueue = ui,
            clock = object : MonitorClock {
                override fun today(): LocalDate = LocalDate.of(2026, 10, 10)
                override fun checkedAt(): String = "마지막 확인 10:15"
            },
            store = store,
            repository = repository,
            targetProvider = MonitorTargetProvider { child, subject ->
                if (subject == "한글" && child == "철수" && targets[subject] == null) {
                    TargetDefaults.defaultCount(subject)
                } else {
                    targets[subject] ?: TargetDefaults.defaultCount(subject)
                }
            },
            notificationSink = notifications,
        )

        fun startAndRegister(child: String) {
            prepareRuntime()
            engine.credentialsAvailable("account-A", "mail@example.test", "secret")
            engine.registerChild("account-A", child)
            commands.drain()
            workers.drain()
            commands.drain()
        }

        fun prepareRuntime() {
            engine.executionConditionsChanged(accessibilityEnabled = true, notificationsEnabled = true)
            engine.attachServiceLease()
            commands.drain()
        }
    }

    private class ManualQueue : MonitorTaskQueue {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun enqueue(task: () -> Unit) { tasks.addLast(task) }
        fun size(): Int = tasks.size
        fun isEmpty(): Boolean = tasks.isEmpty()
        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        }
    }

    private class FakeRepository(
        private val reports: Map<String, LearningRepositoryResult<SubjectReport>>,
        private val catalogFailure: Exception?,
        private val signedInAccount: String,
    ) : MonitorRepository {
        val reportedSubjects = mutableListOf<String>()
        val requestedDates = mutableListOf<LocalDate>()

        override fun signIn(email: String, password: String): LearningRepositoryResult<LearningSession> =
            LearningRepositoryResult.Success(LearningSession(signedInAccount, "token"))

        override fun loadCatalog(session: LearningSession): LearningRepositoryResult<LearningCatalog> =
            if (catalogFailure == null) LearningRepositoryResult.Success(
                LearningCatalog(
                    childOrder = listOf("철수", "영희"),
                    subscriptions = listOf("한글", "수학", "영어").map {
                        ChildSubjectSubscription("철수", it, "철수-$it")
                    } + listOf("한글", "수학", "영어").map {
                        ChildSubjectSubscription("영희", it, "영희-$it")
                    },
                ),
            ) else LearningRepositoryResult.TemporaryFailure(
                catalogFailure.let { LearningRepositoryException(it.message.orEmpty(), it) },
            )

        override fun loadSubjectReport(
            session: LearningSession,
            subscription: ChildSubjectSubscription,
            date: LocalDate,
        ): LearningRepositoryResult<SubjectReport> {
            reportedSubjects += subscription.subjectName
            requestedDates += date
            return reports[subscription.subjectName]
                ?: LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("report unavailable"))
        }
    }

    private class FakeNotifications : ChildNotificationSink {
        data class Update(val accountId: String, val child: MonitorChildSnapshot, val completion: Boolean)
        val updates = mutableListOf<Update>()
        val cancellations = mutableListOf<Pair<String, String>>()
        override fun showStatus(accountId: String, child: MonitorChildSnapshot, completion: Boolean) {
            updates += Update(accountId, child, completion)
        }
        override fun cancel(accountId: String, childName: String) { cancellations += accountId to childName }
    }

    private class FakeMonitorStore(storedChildren: Set<String> = emptySet()) : MonitorStore {
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

    private fun assertUnmodifiable(value: Any) {
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
}
