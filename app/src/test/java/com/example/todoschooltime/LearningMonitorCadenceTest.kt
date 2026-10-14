package com.example.todoschooltime

import java.time.LocalDate
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMonitorCadenceTest {
    @Test
    fun screenRequestReturnsTheFullLearningResultThroughTheEngine() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 3, 60))
            },
        )
        val outcomes = mutableListOf<LearningReportRequestOutcome>()

        fixture.engine.loadScreenReport(
            email = "mail@example.test",
            password = "secret",
            date = LocalDate.of(2026, 10, 10),
            accountIdHint = "account-A",
            listener = outcomes::add,
        )
        fixture.commands.drain()
        fixture.drainAll()
        fixture.ui.drain()

        assertEquals(1, outcomes.size)
        val finished = outcomes.single() as LearningReportRequestOutcome.Finished
        val result = (finished.result as LearningRepositoryResult.Success).value
        assertEquals("account-A", result.accountId)
        assertEquals(listOf("철수", "영희"), result.children.map { it.name })
        assertEquals(listOf(3, 3), result.children.map { it.minutes })
        assertEquals(listOf(3, 3, 3), result.children.first().subjects.map { it.count })
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    @Test
    fun regularReportsWaitForScreenForegroundAndTheSharedStartBasedInterval() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 2, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 3, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 4, 0)),
            ),
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()

        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()
        assertTrue(fixture.repository.reportedPairs.isEmpty())
        assertEquals(listOf(30_000L), fixture.timers.scheduledDelays)

        fixture.clock.advanceBy(59_999L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()
        assertTrue(fixture.repository.reportedPairs.isEmpty())

        fixture.clock.advanceBy(1L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("철수" to "영어"), fixture.repository.reportedPairs)
    }

    @Test
    fun screenOffCancelsCadenceAndBlocksRegularReportsUntilScreenReturns() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 2, 0))
            },
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        assertEquals(listOf(30_000L), fixture.timers.scheduledDelays)

        fixture.engine.screenOnChanged(false)
        fixture.commands.drain()
        fixture.clock.advanceBy(120_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        assertTrue(fixture.repository.reportedPairs.isEmpty())
        assertEquals(listOf(30_000L), fixture.timers.scheduledDelays)

        fixture.engine.screenOnChanged(true)
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("철수" to "수학"), fixture.repository.reportedPairs)
    }

    @Test
    fun switchingForegroundSubjectsDoesNotResetTheSharedCadence() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 2, 0))
            },
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()

        fixture.clock.advanceBy(20_000L)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        fixture.clock.advanceBy(10_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()
        assertTrue(fixture.repository.reportedPairs.isEmpty())

        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("철수" to "수학"), fixture.repository.reportedPairs)
        assertEquals(listOf(30_000L, 30_000L, 30_000L), fixture.timers.scheduledDelays)
    }

    @Test
    fun aSlowRegularCycleBlocksOverlappingWorkAndRunsAtMostOneDueFollowUp() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 2, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 2, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
            ),
        )
        fixture.startAndRegister("철수")
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

        assertEquals(1, fixture.workers.size())
        fixture.clock.advanceBy(120_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        assertEquals("A running cycle must not queue another sign-in or report", 1, fixture.workers.size())
        assertTrue(fixture.repository.reportedPairs.isEmpty())

        fixture.drainAll()

        assertEquals(listOf("철수" to "수학", "철수" to "수학"), fixture.repository.reportedPairs)
        assertEquals(3, fixture.repository.signInCalls)
        assertEquals(30_000L, fixture.timers.scheduledDelays.last())
    }

    @Test
    fun foregroundChangeBeforeRegularPairDispatchReleasesTheOldRunAndPollsCurrentSubject() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 2, 0))
            },
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()
        fixture.clock.advanceBy(60_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        val staleEnglishPair = fixture.workers.takeNext()

        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        staleEnglishPair()
        fixture.commands.drain()
        fixture.clock.advanceBy(60_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("철수" to "수학"), fixture.repository.reportedPairs)
        assertEquals(3, fixture.repository.signInCalls)
    }

    @Test
    fun anUnboundedIntervalUsesBoundedTimerWakeupsWithoutOverflowOrPolling() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 2, 0))
            },
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        val hugeInterval = BigInteger.TEN.pow(100)
        fixture.engine.setIntervalMinutes("account-A", hugeInterval)
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()

        repeat(5) {
            fixture.clock.advanceBy(30_000L)
            fixture.timers.runDue()
            fixture.commands.drain()
            assertTrue(fixture.repository.reportedPairs.isEmpty())
        }

        assertEquals(hugeInterval, fixture.engine.snapshot().intervalMinutes)
        assertTrue(fixture.timers.scheduledDelays.isNotEmpty())
        assertTrue(fixture.timers.scheduledDelays.all { it in 1L..30_000L })
        assertEquals(1, fixture.repository.signInCalls)
    }

    @Test
    fun completeAndZeroTargetSubjectsDoNotStartRegularCycles() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 100, 0))
            },
            targets = mapOf("영어" to 0),
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        val signInsAfterBaseline = fixture.repository.signInCalls
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todohangeul")
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()

        fixture.clock.advanceBy(120_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        assertTrue(fixture.repository.reportedPairs.isEmpty())
        assertEquals(signInsAfterBaseline, fixture.repository.signInCalls)
        assertTrue(fixture.timers.scheduledDelays.isEmpty())
        assertEquals(MonitorSubjectStatus.COMPLETE,
            fixture.engine.snapshot().children.getValue("철수").subjects.getValue("영어").status)
    }

    @Test
    fun serviceReattachRequiresANewForegroundEventBeforeRegularPolling() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 2, 0))
            },
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()
        assertEquals(listOf(30_000L), fixture.timers.scheduledDelays)

        fixture.engine.detachServiceLease()
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()
        fixture.engine.attachServiceLease()
        fixture.engine.screenOnChanged(true)
        fixture.commands.drain()
        fixture.clock.advanceBy(120_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        assertTrue(fixture.repository.reportedPairs.isEmpty())
        assertEquals(listOf(30_000L), fixture.timers.scheduledDelays)

        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("철수" to "영어"), fixture.repository.reportedPairs)
    }

    @Test
    fun foregroundObservationWaitsForFreshWindowEventAndResetsOnReattach() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 2, 0))
            },
        )
        fixture.startAndRegister("철수")

        assertTrue(fixture.engine.snapshot().foregroundObservationPending)

        // A null package is still a fresh window observation; null alone cannot represent this state.
        fixture.engine.foregroundPackageChanged(null)
        fixture.commands.drain()
        assertFalse(fixture.engine.snapshot().foregroundObservationPending)

        fixture.engine.detachServiceLease()
        fixture.commands.drain()
        assertFalse(fixture.engine.snapshot().foregroundObservationPending)
        fixture.engine.attachServiceLease()
        fixture.commands.drain()
        assertTrue(fixture.engine.snapshot().foregroundObservationPending)

        fixture.engine.foregroundPackageChanged("com.example.launcher")
        fixture.commands.drain()
        assertFalse(fixture.engine.snapshot().foregroundObservationPending)
    }

    @Test
    fun initialMetadataFailureConsumesCadenceAndRetriesOnlyTheCurrentForegroundSubject() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, if (subject == "영어") 8 else 2, 0))
            },
        )
        fixture.repository.enqueueCatalogResult(
            LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("catalog unavailable")),
        )
        fixture.repository.enqueueCatalogResult(
            LearningRepositoryResult.Success(
                LearningCatalog(
                    childOrder = listOf("철수"),
                    subscriptions = listOf("한글", "수학", "영어").map {
                        ChildSubjectSubscription("철수", it, "철수-$it")
                    },
                ),
            ),
        )
        fixture.startAndRegister("철수")

        val initial = fixture.engine.snapshot().children.getValue("철수")
        assertTrue(fixture.repository.reportedPairs.isEmpty())
        assertTrue(initial.subjects.values.all { it.status == MonitorSubjectStatus.CONFIRMATION_PENDING })
        assertEquals("catalog unavailable", initial.error)

        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
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

        assertEquals(listOf("철수" to "영어"), fixture.repository.reportedPairs)
        val retried = fixture.engine.snapshot().children.getValue("철수")
        assertNull(retried.error)
        assertEquals(MonitorSubjectStatus.CONFIRMATION_PENDING,
            retried.subjects.getValue("한글").status)
        assertEquals(MonitorSubjectStatus.CONFIRMATION_PENDING,
            retried.subjects.getValue("수학").status)
        assertEquals(MonitorSubjectStatus.COMPLETE, retried.subjects.getValue("영어").status)
        assertTrue(fixture.notifications.updates.none { it.completion })
    }
}
