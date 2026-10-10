package com.example.todoschooltime

import java.time.LocalDate
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMonitorEngineTest {
    @Test
    fun regularReportsWaitForScreenForegroundAndTheSharedStartBasedInterval() {
        val fixture = Fixture(
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
        val fixture = Fixture(
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
        val fixture = Fixture(
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
        val fixture = Fixture(
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
    fun anUnboundedIntervalUsesBoundedTimerWakeupsWithoutOverflowOrPolling() {
        val fixture = Fixture(
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
        val fixture = Fixture(
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
        val fixture = Fixture(
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
    fun initialMetadataFailureConsumesCadenceAndRetriesOnlyTheCurrentForegroundSubject() {
        val fixture = Fixture(
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

    @Test
    fun aNewCrossingUpdatesTheChildNotificationWithTheExactNewestSubjectAndAllStatuses() {
        val fixture = Fixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 6, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 4, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
            ),
        )
        fixture.startAndRegister("철수")
        fixture.notifications.updates.clear()
        fixture.repository.clearReports()
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.Success(SubjectReport("철수", "수학", 5, 0)),
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

        assertEquals(listOf("철수" to "수학"), fixture.repository.reportedPairs)
        val completion = fixture.notifications.updates.single { it.completion }
        assertEquals("수학", completion.completionSubjectName)
        assertEquals("철수 · 수학 완료", MonitorNotificationText.title(completion.child, completion.completionSubjectName))
        assertEquals(MonitorSubjectStatus.COMPLETE, completion.child.subjects.getValue("한글").status)
        assertEquals(MonitorSubjectStatus.COMPLETE, completion.child.subjects.getValue("수학").status)
        assertEquals(MonitorSubjectStatus.BELOW_TARGET, completion.child.subjects.getValue("영어").status)
        assertTrue(MonitorNotificationText.body(completion.child).contains("✔ 한글"))
        assertTrue(MonitorNotificationText.body(completion.child).contains("✔ 수학"))
        assertTrue(MonitorNotificationText.body(completion.child).contains("영어 미달성 2/8"))

        fixture.repository.enqueueReportResult(
            "철수",
            "영어",
            LearningRepositoryResult.Success(SubjectReport("철수", "영어", 3, 0)),
        )
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()
        val silentProgress = fixture.notifications.updates.last()
        assertFalse(silentProgress.completion)
        assertEquals("수학", silentProgress.completionSubjectName)
        assertEquals("철수 · 수학 완료", MonitorNotificationText.title(
            silentProgress.child,
            silentProgress.completionSubjectName,
        ))
    }

    @Test
    fun regularReportFailurePreservesLastCountAndCheckedTimeWithoutCompletingThePair() {
        val fixture = Fixture(
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
        val fixture = Fixture(
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
        val fixture = Fixture(
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
        val fixture = Fixture(
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
        fixture.engine.unregisterChild("account-A", "영희")
        fixture.commands.drain()
        releaseFirstReport.countDown()
        firstReportWorker.join(5_000)
        assertFalse("The in-flight report should finish after the unregister", firstReportWorker.isAlive)
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("철수" to "수학"), fixture.repository.reportedPairs)
        assertEquals(setOf("철수"), fixture.engine.snapshot().registeredChildren)
    }

    @Test
    fun switchingForegroundDuringAnInFlightPairPreventsTheOldSubjectFromReleasingAnotherChild() {
        val firstReportStarted = CountDownLatch(1)
        val releaseFirstReport = CountDownLatch(1)
        val gateEnabled = AtomicBoolean(false)
        val gateUsed = AtomicBoolean(false)
        val fixture = Fixture(
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
        fixture.drainAll()
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
        fixture.drainAll()

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
        val fixture = Fixture(
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
        val fixture = Fixture(
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
        val fixture = Fixture(
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

    private class Fixture(
        reports: Map<String, LearningRepositoryResult<SubjectReport>>,
        targets: Map<String, Int> = emptyMap(),
        catalogFailure: Exception? = null,
        signedInAccount: String = "account-A",
        storedChildren: Set<String> = emptySet(),
        reportGate: (ChildSubjectSubscription) -> Unit = {},
    ) {
        val commands = ManualQueue()
        val workers = ManualQueue()
        val ui = ManualQueue()
        val clock = FakeClock()
        val timers = ManualTimerFactory(clock)
        val repository = FakeRepository(reports, catalogFailure, signedInAccount, reportGate)
        val notifications = FakeNotifications()
        val store = FakeMonitorStore(storedChildren)
        val engine = LearningMonitorEngine(
            commandQueue = commands,
            workerQueue = workers,
            callbackQueue = ui,
            clock = clock,
            timerFactory = timers,
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
            drainAll()
        }

        fun startRuntimeAndRegister(child: String) {
            prepareRuntime()
            engine.credentialsAvailable("account-A", "mail@example.test", "secret")
            engine.registerChild("account-A", child)
            commands.drain()
        }

        fun prepareRuntime() {
            engine.executionConditionsChanged(accessibilityEnabled = true, notificationsEnabled = true)
            engine.attachServiceLease()
            commands.drain()
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

        fun drainAll() {
            while (!commands.isEmpty() || !workers.isEmpty()) {
                commands.drain()
                workers.drain()
                commands.drain()
            }
        }
    }

    private class ManualQueue : MonitorTaskQueue {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun enqueue(task: () -> Unit) { tasks.addLast(task) }
        fun size(): Int = tasks.size
        fun isEmpty(): Boolean = tasks.isEmpty()
        fun takeNext(): () -> Unit = tasks.removeFirst()
        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        }
    }

    private class FakeClock : MonitorClock {
        private var nowMillis = 0L
        override fun today(): LocalDate = LocalDate.of(2026, 10, 10)
        var checkedAtValue = "마지막 확인 10:15"
        override fun checkedAt(): String = checkedAtValue
        override fun monotonicMillis(): Long = nowMillis
        fun advanceBy(millis: Long) { nowMillis += millis }
    }

    private class ManualTimerFactory(private val clock: FakeClock) : MonitorTimerFactory {
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

        fun runDue() {
            while (true) {
                val next = timers.firstOrNull { !it.cancelled && it.dueAtMillis <= clock.monotonicMillis() } ?: return
                timers.remove(next)
                next.callback()
            }
        }
    }

    private class FakeRepository(
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
            return nextQueued ?: reports[subscription.subjectName]
                ?: LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("report unavailable"))
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

    private class FakeNotifications : ChildNotificationSink {
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
