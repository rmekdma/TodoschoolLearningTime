package com.example.todoschooltime

import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMonitorDateAndFreshnessTest {
    @Test
    fun targetRevisionInvalidatesAnInFlightUnauthorizedResultAcrossAnAbaChange() {
        val reportEntered = CountDownLatch(1)
        val releaseReport = CountDownLatch(1)
        val shouldBlockMath = AtomicBoolean(false)
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 2, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 3, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
            ),
            reportGate = { subscription ->
                if (subscription.subjectName == "수학" && shouldBlockMath.get()) {
                    reportEntered.countDown()
                    check(releaseReport.await(5, TimeUnit.SECONDS)) { "The test did not release the old report" }
                }
            },
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        shouldBlockMath.set(true)
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.AuthenticationDenied(LearningAuthenticationDeniedException("old unauthorized")),
        )
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        fixture.clock.advanceBy(60_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        val oldReportWorker = fixture.startWorkerUntil(reportEntered)

        fixture.setTarget("수학", 4)
        fixture.engine.setTargetCounts("account-A", "철수", mapOf("수학" to 4))
        fixture.commands.drain()
        fixture.setTarget("수학", 5)
        fixture.engine.setTargetCounts("account-A", "철수", mapOf("수학" to 5))
        fixture.commands.drain()

        releaseReport.countDown()
        oldReportWorker.join(5_000)
        assertFalse("The old report worker should finish after its response is released", oldReportWorker.isAlive)
        fixture.commands.drain()

        assertFalse(fixture.engine.snapshot().authenticationRequired)
        assertEquals(MonitorSubjectStatus.BELOW_TARGET,
            fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학").status)
        assertEquals(3, fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학").activityCount)
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    @Test
    fun aSeoulDateChangeClearsYesterdayAndDropsItsLateUnauthorizedBaselineResult() {
        val reportEntered = CountDownLatch(1)
        val releaseReport = CountDownLatch(1)
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 2, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 3, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
            ),
            reportGate = { subscription ->
                if (subscription.subjectName == "수학") {
                    reportEntered.countDown()
                    check(releaseReport.await(5, TimeUnit.SECONDS)) { "The test did not release the yesterday report" }
                }
            },
        )
        fixture.startRuntimeAndRegister("철수")
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.AuthenticationDenied(LearningAuthenticationDeniedException("yesterday unauthorized")),
        )
        val oldReportWorker = fixture.startWorkerUntil(reportEntered)

        fixture.clock.todayValue = LocalDate.of(2026, 10, 11)
        fixture.engine.reevaluateMonitoringDate()
        fixture.commands.drain()
        val awaitingToday = fixture.engine.snapshot().children.getValue("철수")
        assertNull(awaitingToday.lastCheckedAt)
        assertEquals(MonitorSubjectStatus.CONFIRMATION_PENDING,
            awaitingToday.subjects.getValue("한글").status)
        assertNull(awaitingToday.subjects.getValue("한글").activityCount)
        assertFalse(fixture.engine.snapshot().authenticationRequired)

        releaseReport.countDown()
        oldReportWorker.join(5_000)
        assertFalse("The yesterday report worker should finish when released", oldReportWorker.isAlive)
        fixture.commands.drain()
        assertFalse(fixture.engine.snapshot().authenticationRequired)
        assertNull(fixture.engine.snapshot().children.getValue("철수").lastCheckedAt)

        fixture.drainAll()
        assertTrue(fixture.repository.requestedDates.contains(LocalDate.of(2026, 10, 10)))
        assertTrue(fixture.repository.requestedDates.contains(LocalDate.of(2026, 10, 11)))
        assertTrue(fixture.repository.requestedDates.filter { it != LocalDate.of(2026, 10, 10) }
            .all { it == LocalDate.of(2026, 10, 11) })
        assertFalse(fixture.engine.snapshot().authenticationRequired)
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    @Test
    fun nextSeoulDateEvaluationRemainsScheduledWhenEverySubjectIsLocallyComplete() {
        val fixture = LearningMonitorFixture(
            reports = emptyMap(),
            targets = mapOf("한글" to 0, "수학" to 0, "영어" to 0),
        )
        fixture.clock.dateChangeDelayMillis = 1_000L
        fixture.startAndRegister("철수")
        assertTrue(fixture.repository.reportedPairs.isEmpty())
        assertTrue(fixture.dateTimers.scheduledDelays.isNotEmpty())

        fixture.clock.advanceBy(1_000L)
        fixture.clock.todayValue = LocalDate.of(2026, 10, 11)
        fixture.dateTimers.runDue()
        fixture.commands.drain()

        assertTrue(fixture.engine.snapshot().children.getValue("철수").subjects.values.all {
            it.status == MonitorSubjectStatus.COMPLETE && it.activityCount == null
        })
        assertTrue(fixture.dateTimers.scheduledDelays.size >= 2)
    }

    @Test
    fun sameDayResumeAndServiceReattachRecalculateTheSeoulMidnightDelay() {
        val fixture = LearningMonitorFixture(
            reports = emptyMap(),
            targets = mapOf("한글" to 0, "수학" to 0, "영어" to 0),
        )
        fixture.clock.dateChangeDelayMillis = 60_000L
        fixture.startAndRegister("철수")
        val initialSchedules = fixture.dateTimers.scheduledDelays.size

        fixture.clock.dateChangeDelayMillis = 15_000L
        fixture.engine.reevaluateMonitoringDate()
        fixture.commands.drain()
        assertEquals(initialSchedules + 1, fixture.dateTimers.scheduledDelays.size)
        assertEquals(15_000L, fixture.dateTimers.scheduledDelays.last())

        fixture.engine.detachServiceLease()
        fixture.commands.drain()
        fixture.engine.attachServiceLease()
        fixture.commands.drain()
        assertEquals(initialSchedules + 2, fixture.dateTimers.scheduledDelays.size)
        assertEquals(15_000L, fixture.dateTimers.scheduledDelays.last())
    }

    @Test
    fun dateEvaluationTimerFollowsAttachedActiveAccountLeaseAndRebaselinesAfterDetachedDateChange() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 2, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 3, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 4, 0)),
            ),
        )
        fixture.startAndRegister("철수")
        assertEquals(1, fixture.dateTimers.activeCount())
        fixture.repository.clearReports()

        fixture.engine.detachServiceLease()
        fixture.commands.drain()
        assertEquals(0, fixture.dateTimers.activeCount())

        fixture.clock.todayValue = LocalDate.of(2026, 10, 11)
        fixture.engine.reevaluateMonitoringDate()
        fixture.commands.drain()
        val awaitingNewDate = fixture.engine.snapshot().children.getValue("철수")
        assertTrue(awaitingNewDate.subjects.values.all {
            it.status == MonitorSubjectStatus.CONFIRMATION_PENDING && it.activityCount == null
        })
        assertTrue(fixture.repository.reportedPairs.isEmpty())
        assertEquals(0, fixture.dateTimers.activeCount())

        fixture.engine.attachServiceLease()
        fixture.commands.drain()
        fixture.drainAll()
        assertEquals(1, fixture.dateTimers.activeCount())
        assertTrue(fixture.repository.requestedDates.isNotEmpty())
        assertTrue(fixture.repository.requestedDates.all { it == LocalDate.of(2026, 10, 11) })
        assertEquals(2, fixture.engine.snapshot().children.getValue("철수").subjects.getValue("한글").activityCount)
        assertTrue(fixture.notifications.updates.none { it.completion })

        fixture.engine.credentialsAvailable("account-B", "other@example.test", "other-secret")
        fixture.commands.drain()
        assertEquals(0, fixture.dateTimers.activeCount())
    }

    @Test
    fun baselineResponsesThatReturnAfterSeoulMidnightAreDroppedBeforeTheirOutcomeIsApplied() {
        staleDateOutcomes().forEach(::assertLateBaselineResponseDropped)
    }

    @Test
    fun regularResponsesThatReturnAfterSeoulMidnightAreDroppedBeforeTheirOutcomeIsApplied() {
        staleDateOutcomes().forEach(::assertLateRegularResponseDropped)
    }

    @Test
    fun regularReportFailurePreservesLastCountAndCheckedTimeWithoutCompletingThePair() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 3, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 3, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 3, 0)),
            ),
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("temporary error")),
        )
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        val child = fixture.engine.snapshot().children.getValue("철수")
        val math = child.subjects.getValue("수학")
        assertEquals(MonitorSubjectStatus.BELOW_TARGET, math.status)
        assertEquals(3, math.activityCount)
        assertEquals("마지막 확인 10:15", math.checkedAt)
        assertEquals("마지막 확인 10:15", child.lastCheckedAt)
        assertEquals("temporary error", math.error)
        assertFalse(fixture.notifications.updates.last().completion)
    }

    @Test
    fun mismatchedRegularReportPreservesLastValidCountAndCheckedTime() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 3, 0))
            },
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        fixture.clock.checkedAtValue = "마지막 확인 10:16"
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.Success(SubjectReport("다른 아이", "수학", 5, 0)),
        )
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        val child = fixture.engine.snapshot().children.getValue("철수")
        val math = child.subjects.getValue("수학")
        assertEquals(MonitorSubjectStatus.BELOW_TARGET, math.status)
        assertEquals(3, math.activityCount)
        assertEquals("마지막 확인 10:15", math.checkedAt)
        assertEquals("마지막 확인 10:15", child.lastCheckedAt)
        assertEquals("학습 보고서의 아이 또는 과목이 요청과 일치하지 않습니다.", math.error)
        assertFalse(fixture.notifications.updates.last().completion)
    }

    @Test
    fun anInitialPairFailureConsumesTheCommonCadenceBeforeItsFirstRetry() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 2, 0)),
                "수학" to LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("initial temporary error")),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
            ),
        )
        fixture.startAndRegister("철수")
        assertEquals(MonitorSubjectStatus.CONFIRMATION_PENDING,
            fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학").status)
        fixture.repository.clearReports()
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()

        assertTrue(fixture.repository.reportedPairs.isEmpty())
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        assertTrue(fixture.repository.reportedPairs.isEmpty())
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("철수" to "수학"), fixture.repository.reportedPairs)
    }

    @Test
    fun unregisteringAnotherChildDuringAnInFlightRegularPairPreventsItsQueuedPairRequest() {
        val firstReportStarted = CountDownLatch(1)
        val releaseFirstReport = CountDownLatch(1)
        val gateEnabled = AtomicBoolean(false)
        val gateUsed = AtomicBoolean(false)
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 2, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 2, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
            ),
            reportGate = { subscription ->
                if (gateEnabled.get() && subscription.childName == "철수" && subscription.subjectName == "수학" &&
                    gateUsed.compareAndSet(false, true)
                ) {
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
        fixture.drainAll()
        gateEnabled.set(true)
        fixture.repository.clearReports()
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.Success(SubjectReport("철수", "수학", 3, 0)),
        )
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()

        val firstReportWorker = fixture.startWorkerUntil(firstReportStarted)
        fixture.engine.unregisterChild("account-A", "영희")
        fixture.commands.drain()
        releaseFirstReport.countDown()
        firstReportWorker.join(5_000)
        assertFalse("The in-flight report should finish after the unregister", firstReportWorker.isAlive)
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("철수" to "수학"), fixture.repository.reportedPairs)
        assertEquals(setOf("철수"), fixture.engine.snapshot().registeredChildren)
        assertEquals(3, fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학").activityCount)
    }

    @Test
    fun switchingForegroundDuringAnInFlightPairPreventsTheOldSubjectFromReleasingAnotherChild() {
        val firstReportStarted = CountDownLatch(1)
        val releaseFirstReport = CountDownLatch(1)
        val gateEnabled = AtomicBoolean(false)
        val gateUsed = AtomicBoolean(false)
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 2, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 2, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
            ),
            reportGate = { subscription ->
                if (gateEnabled.get() && subscription.childName == "철수" && subscription.subjectName == "수학" &&
                    gateUsed.compareAndSet(false, true)
                ) {
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
        fixture.drainAll()
        gateEnabled.set(true)
        fixture.repository.clearReports()
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()

        val firstReportWorker = fixture.startWorkerUntil(firstReportStarted)
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()
        releaseFirstReport.countDown()
        firstReportWorker.join(5_000)
        assertFalse("The in-flight report should finish after the foreground switch", firstReportWorker.isAlive)
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("철수" to "수학"), fixture.repository.reportedPairs)
    }

    private fun staleDateOutcomes(): List<LearningRepositoryResult<SubjectReport>> = listOf(
        LearningRepositoryResult.Success(SubjectReport("철수", "한글", 99, 0)),
        LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("yesterday report failure")),
        LearningRepositoryResult.AuthenticationDenied(
            LearningAuthenticationDeniedException("yesterday report unauthorized"),
        ),
    )

    private fun assertLateBaselineResponseDropped(result: LearningRepositoryResult<SubjectReport>) {
        val reportStarted = CountDownLatch(1)
        val releaseReport = CountDownLatch(1)
        val gateUsed = AtomicBoolean(false)
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 2, 0))
            },
            reportGate = { subscription ->
                if (subscription.subjectName == "한글" && gateUsed.compareAndSet(false, true)) {
                    reportStarted.countDown()
                    check(releaseReport.await(5, TimeUnit.SECONDS))
                }
            },
        )
        fixture.startRuntimeAndRegister("철수")
        fixture.repository.enqueueReportResult("철수", "한글", result)
        val oldWorker = fixture.startWorkerUntil(reportStarted)

        fixture.clock.todayValue = LocalDate.of(2026, 10, 11)
        releaseReport.countDown()
        oldWorker.join(5_000)
        assertFalse("The previous-date baseline worker should finish when released", oldWorker.isAlive)
        fixture.commands.drain()

        val awaitingToday = fixture.engine.snapshot()
        assertFalse(awaitingToday.authenticationRequired)
        assertTrue(awaitingToday.children.getValue("철수").subjects.values.all {
            it.status == MonitorSubjectStatus.CONFIRMATION_PENDING && it.activityCount == null && it.error == null
        })
        assertTrue(fixture.notifications.updates.none { update ->
            update.child.subjects.values.any { it.activityCount == 99 || it.error == "yesterday report failure" }
        })

        fixture.drainAll()
        val today = fixture.engine.snapshot()
        assertFalse(today.authenticationRequired)
        assertEquals(2, today.children.getValue("철수").subjects.getValue("한글").activityCount)
        assertTrue(fixture.repository.requestedDates.contains(LocalDate.of(2026, 10, 10)))
        assertTrue(fixture.repository.requestedDates.any { it == LocalDate.of(2026, 10, 11) })
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    private fun assertLateRegularResponseDropped(result: LearningRepositoryResult<SubjectReport>) {
        val reportStarted = CountDownLatch(1)
        val releaseReport = CountDownLatch(1)
        val gateNextReport = AtomicBoolean(false)
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 2, 0))
            },
            reportGate = { subscription ->
                if (subscription.subjectName == "한글" && gateNextReport.compareAndSet(true, false)) {
                    reportStarted.countDown()
                    check(releaseReport.await(5, TimeUnit.SECONDS))
                }
            },
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        fixture.repository.enqueueReportResult("철수", "한글", result)
        gateNextReport.set(true)
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todohangeul")
        fixture.commands.drain()
        fixture.clock.advanceBy(60_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        val oldWorker = fixture.startWorkerUntil(reportStarted)

        fixture.clock.todayValue = LocalDate.of(2026, 10, 11)
        releaseReport.countDown()
        oldWorker.join(5_000)
        assertFalse("The previous-date regular worker should finish when released", oldWorker.isAlive)
        fixture.commands.drain()

        val awaitingToday = fixture.engine.snapshot()
        assertFalse(awaitingToday.authenticationRequired)
        assertTrue(awaitingToday.children.getValue("철수").subjects.values.all {
            it.status == MonitorSubjectStatus.CONFIRMATION_PENDING && it.activityCount == null && it.error == null
        })
        assertTrue(fixture.notifications.updates.none { update ->
            update.completion || update.child.subjects.values.any {
                it.activityCount == 99 || it.error == "yesterday report failure"
            }
        })

        fixture.drainAll()
        val today = fixture.engine.snapshot()
        assertFalse(today.authenticationRequired)
        assertEquals(2, today.children.getValue("철수").subjects.getValue("한글").activityCount)
        assertTrue(fixture.repository.requestedDates.contains(LocalDate.of(2026, 10, 10)))
        assertTrue(fixture.repository.requestedDates.any { it == LocalDate.of(2026, 10, 11) })
        assertTrue(fixture.notifications.updates.none { it.completion })
    }
}
