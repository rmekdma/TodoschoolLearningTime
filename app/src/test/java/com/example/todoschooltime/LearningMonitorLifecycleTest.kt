package com.example.todoschooltime

import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMonitorLifecycleTest {
    @Test
    fun intervalCommandIsAsyncAndRetainsAnExactUnboundedPositiveInteger() {
        val fixture = LearningMonitorFixture(reports = emptyMap())
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
        val fixture = LearningMonitorFixture(reports = mapOf(
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
        fixture.drainAll()

        val snapshot = fixture.engine.snapshot()
        assertEquals(setOf("영희"), snapshot.registeredChildren)
        assertFalse(snapshot.children.containsKey("철수"))
        assertEquals(MonitorSubjectStatus.BELOW_TARGET,
            snapshot.children.getValue("영희").subjects.getValue("한글").status)
        assertEquals(listOf("account-A" to "철수"), fixture.notifications.cancellations)
    }

    @Test
    fun unregisteringDuringTheFirstReportStopsRemainingReportsButKeepsOtherChildRunning() {
        val firstReportStarted = CountDownLatch(1)
        val releaseFirstReport = CountDownLatch(1)
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith {
                LearningRepositoryResult.Success(SubjectReport("철수", it, 8, 0))
            },
            reportGate = { subscription ->
                if (subscription.childName == "철수" && subscription.subjectName == "한글") {
                    firstReportStarted.countDown()
                    check(releaseFirstReport.await(5, TimeUnit.SECONDS))
                }
            },
        )
        fixture.prepareRuntime()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.engine.registerChild("account-A", "영희")
        fixture.commands.drain()

        val firstReportWorker = fixture.startWorkerUntil(firstReportStarted)
        fixture.engine.unregisterChild("account-A", "철수")
        fixture.commands.drain()
        releaseFirstReport.countDown()
        firstReportWorker.join(5_000)
        assertFalse("The in-flight report should finish after best-effort release", firstReportWorker.isAlive)
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("한글"), fixture.repository.reportedPairs
            .filter { it.first == "철수" }
            .map { it.second })
        assertEquals(listOf("한글", "수학", "영어"), fixture.repository.reportedPairs
            .filter { it.first == "영희" }
            .map { it.second })
        assertEquals(setOf("영희"), fixture.engine.snapshot().registeredChildren)
    }

    @Test
    fun detachingLeaseDropsTheCurrentPairKeepsCommittedBaselineAndReattachRequestsOnlyPendingPairs() {
        val secondReportStarted = CountDownLatch(1)
        val releaseSecondReport = CountDownLatch(1)
        val gateUsed = AtomicBoolean(false)
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 8, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 7, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 9, 0)),
            ),
            reportGate = { subscription ->
                if (subscription.childName == "철수" && subscription.subjectName == "수학" &&
                    gateUsed.compareAndSet(false, true)) {
                    secondReportStarted.countDown()
                    check(releaseSecondReport.await(5, TimeUnit.SECONDS))
                }
            },
        )
        fixture.startRuntimeAndRegister("철수")
        val secondReportWorker = fixture.startWorkerUntil(secondReportStarted)

        val beforeDetach = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("한글")
        assertEquals(MonitorSubjectStatus.COMPLETE, beforeDetach.status)
        assertEquals(8, beforeDetach.activityCount)
        fixture.engine.detachServiceLease()
        fixture.commands.drain()
        releaseSecondReport.countDown()
        secondReportWorker.join(5_000)
        assertFalse("The in-flight report should finish after lease detach", secondReportWorker.isAlive)
        fixture.commands.drain()

        assertEquals(listOf("한글", "수학"), fixture.repository.reportedPairs.map { it.second })
        assertEquals(MonitorSubjectStatus.COMPLETE,
            fixture.engine.snapshot().children.getValue("철수").subjects.getValue("한글").status)
        assertEquals(MonitorSubjectStatus.CONFIRMATION_PENDING,
            fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학").status)
        assertFalse(fixture.engine.snapshot().serviceLeaseAttached)

        fixture.engine.attachServiceLease()
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(1, fixture.repository.reportedPairs.count { it.second == "한글" })
        assertEquals(2, fixture.repository.reportedPairs.count { it.second == "수학" })
        assertEquals(1, fixture.repository.reportedPairs.count { it.second == "영어" })
        assertEquals(MonitorSubjectStatus.COMPLETE,
            fixture.engine.snapshot().children.getValue("철수").subjects.getValue("한글").status)
    }

    @Test
    fun switchingAccountsDuringAReportDropsItAndStopsTheOldAccountRun() {
        val reportStarted = CountDownLatch(1)
        val releaseReport = CountDownLatch(1)
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith {
                LearningRepositoryResult.Success(SubjectReport("철수", it, 8, 0))
            },
            reportGate = { subscription ->
                if (subscription.childName == "철수" && subscription.subjectName == "한글") {
                    reportStarted.countDown()
                    check(releaseReport.await(5, TimeUnit.SECONDS))
                }
            },
        )
        fixture.startRuntimeAndRegister("철수")
        val reportWorker = fixture.startWorkerUntil(reportStarted)

        fixture.engine.credentialsAvailable("account-B", "other@example.test", "other-secret")
        fixture.commands.drain()
        releaseReport.countDown()
        reportWorker.join(5_000)
        assertFalse("The in-flight report should finish after the account switch", reportWorker.isAlive)
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("한글"), fixture.repository.reportedPairs.map { it.second })
        assertEquals("account-B", fixture.engine.snapshot().accountId)
        assertTrue(fixture.engine.snapshot().registeredChildren.isEmpty())
    }

    @Test
    fun accountMismatchCannotApplyAResultFromAnotherSession() {
        val fixture = LearningMonitorFixture(reports = emptyMap(), signedInAccount = "account-B")
        fixture.startAndRegister("철수")

        val child = fixture.engine.snapshot().children.getValue("철수")
        assertTrue(child.subjects.values.all { it.activityCount == null })
        assertEquals("인증 계정이 감시 계정과 일치하지 않습니다.", child.error)
        assertTrue(fixture.repository.reportedSubjects.isEmpty())
    }

    @Test
    fun observeCapturesInitialSnapshotInQueueOrderAndClosedListenerGetsNoQueuedCallbacks() {
        val fixture = LearningMonitorFixture(reports = emptyMap())
        fixture.prepareRuntime()
        val revisions = mutableListOf<Long>()
        val subscription = fixture.engine.observe { revisions += it.revision }
        fixture.commands.drain()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.commands.drain()
        fixture.drainAll()
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
}
