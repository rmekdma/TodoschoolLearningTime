package com.example.todoschooltime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMonitorBaselineRecoveryTest {
    @Test
    fun registrationSetsSilentPerSubjectBaselinesAndKeepsPartialFailureUnknown() {
        val fixture = LearningMonitorFixture(
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
        val fixture = LearningMonitorFixture(
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
        val fixture = LearningMonitorFixture(reports = emptyMap(), catalogFailure = LearningRepositoryException("catalog unavailable"))
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
        val fixture = LearningMonitorFixture(reports = emptyMap())
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.executionStatus.set(accessibilityEnabled = true, notificationsEnabled = true)
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
    fun authenticationDeniedDuringInitialBaselineStopsRemainingPairsAndPreservesCommittedStatus() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 3, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
            ),
        )
        fixture.prepareRuntime()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.AuthenticationDenied(LearningAuthenticationDeniedException("unauthorized")),
        )
        fixture.commands.drain()
        fixture.drainAll()

        val snapshot = fixture.engine.snapshot()
        val child = snapshot.children.getValue("철수")
        assertEquals(setOf("철수"), snapshot.registeredChildren)
        assertEquals(listOf("한글", "수학"), fixture.repository.reportedSubjects)
        assertEquals(MonitorSubjectStatus.BELOW_TARGET, child.subjects.getValue("한글").status)
        assertEquals(3, child.subjects.getValue("한글").activityCount)
        assertEquals(MonitorSubjectStatus.CONFIRMATION_PENDING, child.subjects.getValue("수학").status)
        assertEquals(MonitorSubjectStatus.CONFIRMATION_PENDING, child.subjects.getValue("영어").status)
        assertTrue(snapshot.authenticationRequired)
        assertTrue(snapshot.monitoringPaused)
        assertTrue(child.error.orEmpty().contains("다시 로그인 필요"))
        assertTrue(fixture.notifications.updates.last().child.error.orEmpty().contains("다시 로그인 필요"))
        assertTrue(MonitorNotificationText.summary(fixture.notifications.updates.last().child).contains("다시 로그인 필요"))
        assertFalse(fixture.notifications.updates.last().completion)
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    @Test
    fun savedCredentialsDoNotResumeAuthenticationPauseButHumanReauthenticationResetsSilently() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 3, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 2, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 1, 0)),
            ),
        )
        fixture.prepareRuntime()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.AuthenticationDenied(LearningAuthenticationDeniedException("unauthorized")),
        )
        fixture.commands.drain()
        fixture.drainAll()
        fixture.repository.clearReports()

        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.commands.drain()
        fixture.drainAll()
        assertTrue(fixture.engine.snapshot().authenticationRequired)
        assertTrue(fixture.repository.reportedSubjects.isEmpty())

        fixture.repository.enqueueReportResult(
            "철수",
            "한글",
            LearningRepositoryResult.Success(SubjectReport("철수", "한글", 20, 0)),
        )
        fixture.engine.reauthenticate("account-A", "mail@example.test", "new-secret")
        fixture.commands.drain()
        fixture.drainAll()

        val snapshot = fixture.engine.snapshot()
        assertFalse(snapshot.authenticationRequired)
        assertFalse(snapshot.monitoringPaused)
        assertEquals(setOf("철수"), snapshot.registeredChildren)
        assertEquals(20, snapshot.children.getValue("철수").subjects.getValue("한글").activityCount)
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    @Test
    fun regularAuthenticationDeniedPausesMonitoringAndPreservesRegisteredChildStatus() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 3, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 2, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 1, 0)),
            ),
        )
        fixture.startAndRegister("철수")
        val before = fixture.engine.snapshot().children.getValue("철수")
        fixture.repository.clearReports()
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.AuthenticationDenied(LearningAuthenticationDeniedException("unauthorized")),
        )
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        fixture.clock.advanceBy(60_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        val snapshot = fixture.engine.snapshot()
        val child = snapshot.children.getValue("철수")
        assertTrue(snapshot.authenticationRequired)
        assertTrue(snapshot.monitoringPaused)
        assertEquals(setOf("철수"), snapshot.registeredChildren)
        assertEquals(listOf("수학"), fixture.repository.reportedSubjects)
        assertEquals(before.subjects.getValue("한글"), child.subjects.getValue("한글"))
        assertEquals(before.subjects.getValue("수학").activityCount, child.subjects.getValue("수학").activityCount)
        assertEquals(before.subjects.getValue("영어"), child.subjects.getValue("영어"))
        assertTrue(MonitorNotificationText.summary(fixture.notifications.updates.last().child).contains("다시 로그인 필요"))
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    @Test
    fun permissionPausePreservesStatusRepeatedProbesDoNotRestartAndRecoveryBaselineIsSilent() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 3, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 2, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 1, 0)),
            ),
        )
        fixture.startAndRegister("철수")
        val requestsBeforePause = fixture.repository.reportedSubjects.size
        val hangulBeforePause = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("한글")
        fixture.executionStatus.set(accessibilityEnabled = false, notificationsEnabled = true)
        fixture.engine.executionConditionsChanged(accessibilityEnabled = false, notificationsEnabled = true)
        fixture.commands.drain()

        val paused = fixture.engine.snapshot()
        assertTrue(paused.monitoringPaused)
        assertFalse(paused.accessibilityEnabled)
        assertTrue(paused.notificationsEnabled)
        assertEquals(setOf("철수"), paused.registeredChildren)
        assertEquals(hangulBeforePause.status, paused.children.getValue("철수").subjects.getValue("한글").status)
        assertEquals(hangulBeforePause.activityCount, paused.children.getValue("철수").subjects.getValue("한글").activityCount)
        assertTrue(fixture.notifications.updates.last().child.error.orEmpty().contains("접근성 권한 필요"))

        repeat(3) {
            fixture.engine.executionConditionsChanged(accessibilityEnabled = false, notificationsEnabled = true)
            fixture.commands.drain()
        }
        assertEquals(requestsBeforePause, fixture.repository.reportedSubjects.size)

        fixture.repository.enqueueReportResult(
            "철수",
            "한글",
            LearningRepositoryResult.Success(SubjectReport("철수", "한글", 20, 0)),
        )
        fixture.executionStatus.set(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.engine.executionConditionsChanged(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.commands.drain()
        fixture.drainAll()

        assertFalse(fixture.engine.snapshot().monitoringPaused)
        assertEquals(20, fixture.engine.snapshot().children.getValue("철수").subjects.getValue("한글").activityCount)
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    @Test
    fun goalChangeDuringPermissionRecoveryKeepsRetainedCountPendingAndQueriesFreshBaseline() {
        val recoveryReportEntered = CountDownLatch(1)
        val releaseRecoveryReport = CountDownLatch(1)
        val blockRecoveryMath = AtomicBoolean(false)
        val recoveryGateUsed = AtomicBoolean(false)
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 3, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 3, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 1, 0)),
            ),
            reportGate = { subscription ->
                if (subscription.subjectName == "수학" && blockRecoveryMath.get() &&
                    recoveryGateUsed.compareAndSet(false, true)
                ) {
                    recoveryReportEntered.countDown()
                    check(releaseRecoveryReport.await(5, TimeUnit.SECONDS)) {
                        "The recovery report was not released"
                    }
                }
            },
        )
        fixture.startAndRegister("철수")
        val oldKnownCount = fixture.engine.snapshot().children.getValue("철수")
            .subjects.getValue("수학").activityCount
        assertEquals(3, oldKnownCount)

        blockRecoveryMath.set(true)
        fixture.executionStatus.set(accessibilityEnabled = false, notificationsEnabled = true)
        fixture.engine.executionConditionsChanged(accessibilityEnabled = false, notificationsEnabled = true)
        fixture.commands.drain()
        fixture.executionStatus.set(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.engine.executionConditionsChanged(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.commands.drain()
        val oldRecoveryWorker = fixture.startWorkerUntil(recoveryReportEntered)

        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.AuthenticationDenied(
                LearningAuthenticationDeniedException("stale recovery unauthorized"),
            ),
        )
        fixture.setTarget("수학", 2)
        fixture.engine.setTargetCounts("account-A", "철수", mapOf("수학" to 2))
        fixture.commands.drain()

        val duringRecovery = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학")
        assertEquals(MonitorSubjectStatus.CONFIRMATION_PENDING, duringRecovery.status)
        assertEquals(oldKnownCount, duringRecovery.activityCount)

        releaseRecoveryReport.countDown()
        oldRecoveryWorker.join(5_000)
        assertFalse("The stale recovery worker should finish after release", oldRecoveryWorker.isAlive)
        fixture.commands.drain()
        fixture.drainAll()

        val recoveredMath = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학")
        assertEquals(MonitorSubjectStatus.COMPLETE, recoveredMath.status)
        assertEquals(3, recoveredMath.activityCount)
        assertEquals(3, fixture.repository.reportedSubjects.count { it == "수학" })
        assertFalse(fixture.engine.snapshot().authenticationRequired)
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    @Test
    fun goalCommandAfterCatalogReconciliationStillInvalidatesThePreviousRecoveryTarget() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 3, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 3, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 1, 0)),
            ),
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()
        fixture.executionStatus.set(accessibilityEnabled = false, notificationsEnabled = true)
        fixture.engine.executionConditionsChanged(accessibilityEnabled = false, notificationsEnabled = true)
        fixture.commands.drain()
        fixture.executionStatus.set(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.engine.executionConditionsChanged(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.commands.drain()

        fixture.workers.takeNext().invoke() // recovery sign-in, whose response queues catalog
        fixture.commands.drain()
        fixture.setTarget("수학", 2)
        fixture.workers.takeNext().invoke() // catalog response is queued before the goal command
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.AuthenticationDenied(
                LearningAuthenticationDeniedException("stale pre-command recovery unauthorized"),
            ),
        )
        fixture.engine.setTargetCounts("account-A", "철수", mapOf("수학" to 2))
        fixture.commands.drain()

        val pendingAfterGoalChange = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학")
        assertEquals(MonitorSubjectStatus.CONFIRMATION_PENDING, pendingAfterGoalChange.status)
        assertEquals(3, pendingAfterGoalChange.activityCount)
        fixture.workers.takeNext().invoke() // old recovery report worker must be invalidated before repository access
        fixture.commands.drain()
        assertFalse(fixture.engine.snapshot().authenticationRequired)
        assertTrue(fixture.repository.reportedSubjects.isEmpty())

        fixture.repository.clearQueuedReportResult("철수", "수학")
        fixture.drainAll()

        val confirmed = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학")
        assertEquals(MonitorSubjectStatus.COMPLETE, confirmed.status)
        assertEquals(3, confirmed.activityCount)
        assertEquals(1, fixture.repository.reportedPairs.count { it == ("철수" to "수학") })
        assertFalse(fixture.engine.snapshot().authenticationRequired)
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    @Test
    fun workerPermissionProbeBlocksQueuedRequestWhenPermissionWasRevoked() {
        val fixture = LearningMonitorFixture(reports = emptyMap())
        fixture.executionStatus.set(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.engine.executionConditionsChanged(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.engine.attachServiceLease()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.commands.drain()
        assertEquals(1, fixture.workers.size())

        fixture.executionStatus.set(accessibilityEnabled = true, notificationsEnabled = false)
        fixture.workers.drain()
        fixture.commands.drain()

        assertEquals(0, fixture.repository.signInCalls)
        assertTrue(fixture.engine.snapshot().monitoringPaused)
        assertFalse(fixture.engine.snapshot().notificationsEnabled)
        assertEquals(setOf("철수"), fixture.engine.snapshot().registeredChildren)
    }

    @Test
    fun lateBaselineSuccessFailureAndAuthenticationDenialCannotChangeRecoveredState() {
        val staleResults = listOf<LearningRepositoryResult<SubjectReport>>(
            LearningRepositoryResult.Success(SubjectReport("철수", "수학", 100, 0)),
            LearningRepositoryResult.TemporaryFailure(LearningRepositoryException("late failure")),
            LearningRepositoryResult.AuthenticationDenied(LearningAuthenticationDeniedException("late unauthorized")),
        )
        staleResults.forEach { staleResult ->
            val reportStarted = CountDownLatch(1)
            val releaseReport = CountDownLatch(1)
            val reportGateUsed = AtomicBoolean(false)
            val fixture = LearningMonitorFixture(
                reports = mapOf(
                    "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 3, 0)),
                    "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 2, 0)),
                    "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 1, 0)),
                ),
                reportGate = { subscription ->
                    if (subscription.subjectName == "수학" && reportGateUsed.compareAndSet(false, true)) {
                        reportStarted.countDown()
                        check(releaseReport.await(5, TimeUnit.SECONDS))
                    }
                },
            )
            fixture.prepareRuntime()
            fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
            fixture.engine.registerChild("account-A", "철수")
            fixture.commands.drain()

            fixture.workers.takeNext().invoke() // sign-in
            fixture.commands.drain()
            fixture.workers.takeNext().invoke() // catalog
            fixture.commands.drain()
            fixture.workers.takeNext().invoke() // commit Hangul, queue Math
            fixture.commands.drain()
            val oldReportWorker = fixture.startWorkerUntil(reportStarted)

            fixture.executionStatus.set(accessibilityEnabled = false, notificationsEnabled = true)
            fixture.engine.executionConditionsChanged(accessibilityEnabled = false, notificationsEnabled = true)
            fixture.commands.drain()
            repeat(3) {
                fixture.engine.executionConditionsChanged(accessibilityEnabled = false, notificationsEnabled = true)
                fixture.commands.drain()
            }
            fixture.executionStatus.set(accessibilityEnabled = true, notificationsEnabled = true)
            fixture.engine.executionConditionsChanged(accessibilityEnabled = true, notificationsEnabled = true)
            fixture.commands.drain()
            fixture.drainAll()
            val recovered = fixture.engine.snapshot()
            val notificationsAfterRecovery = fixture.notifications.updates.size

            fixture.repository.enqueueReportResult("철수", "수학", staleResult)
            releaseReport.countDown()
            oldReportWorker.join(5_000)
            assertFalse("A stale report should return after recovery", oldReportWorker.isAlive)
            fixture.commands.drain()

            val afterStaleResult = fixture.engine.snapshot()
            assertEquals(recovered.children, afterStaleResult.children)
            assertEquals(recovered.authenticationRequired, afterStaleResult.authenticationRequired)
            assertEquals(recovered.registeredChildren, afterStaleResult.registeredChildren)
            assertEquals(notificationsAfterRecovery, fixture.notifications.updates.size)
            assertTrue(fixture.notifications.updates.none { it.completion })
        }
    }

    @Test
    fun lateAuthenticationDenialFromOldBaselineCannotRevokeHumanReauthentication() {
        val reportStarted = CountDownLatch(1)
        val releaseReport = CountDownLatch(1)
        val reportGateUsed = AtomicBoolean(false)
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 3, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 2, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 1, 0)),
            ),
            reportGate = { subscription ->
                if (subscription.subjectName == "수학" && reportGateUsed.compareAndSet(false, true)) {
                    reportStarted.countDown()
                    check(releaseReport.await(5, TimeUnit.SECONDS))
                }
            },
        )
        fixture.prepareRuntime()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.commands.drain()
        fixture.workers.takeNext().invoke() // initial sign-in
        fixture.commands.drain()
        fixture.workers.takeNext().invoke() // initial catalog
        fixture.commands.drain()
        fixture.workers.takeNext().invoke() // commit Hangul, queue Math
        fixture.commands.drain()
        val oldReportWorker = fixture.startWorkerUntil(reportStarted)

        fixture.engine.reauthenticate("account-A", "mail@example.test", "new-secret")
        fixture.commands.drain()
        fixture.drainAll()
        val recovered = fixture.engine.snapshot()
        val notificationsAfterRecovery = fixture.notifications.updates.size

        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.AuthenticationDenied(LearningAuthenticationDeniedException("late unauthorized")),
        )
        releaseReport.countDown()
        oldReportWorker.join(5_000)
        assertFalse("The old report should return after reauthentication", oldReportWorker.isAlive)
        fixture.commands.drain()

        assertFalse(fixture.engine.snapshot().authenticationRequired)
        assertFalse(fixture.engine.snapshot().monitoringPaused)
        assertEquals(recovered.children, fixture.engine.snapshot().children)
        assertEquals(notificationsAfterRecovery, fixture.notifications.updates.size)
        assertTrue(fixture.notifications.updates.none { it.completion })
    }

    @Test
    fun enabledButDetachedServiceWaitsForConnectionWithoutResettingItsBaseline() {
        val fixture = LearningMonitorFixture(
            reports = mapOf("한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 3, 0))),
        )
        fixture.startAndRegister("철수")
        val beforeDetach = fixture.engine.snapshot().children.getValue("철수")
        fixture.repository.clearReports()

        fixture.engine.detachServiceLease()
        fixture.commands.drain()
        val disconnected = fixture.engine.snapshot()
        assertTrue(disconnected.accessibilityEnabled)
        assertTrue(disconnected.notificationsEnabled)
        assertFalse(disconnected.serviceLeaseAttached)
        assertTrue(disconnected.connectionWaiting)
        assertFalse(disconnected.monitoringPaused)
        assertEquals(beforeDetach, disconnected.children.getValue("철수"))

        fixture.engine.attachServiceLease()
        fixture.commands.drain()
        fixture.drainAll()
        assertTrue(fixture.repository.reportedSubjects.isEmpty())
        assertEquals(beforeDetach, fixture.engine.snapshot().children.getValue("철수"))
    }

    @Test
    fun permissionRevokedBeforeDetachResetsAllChildrenAndRecoveryIsSilent() {
        val fixture = LearningMonitorFixture(reports = emptyMap())
        fixture.prepareRuntime()
        fixture.engine.credentialsAvailable("account-A", "mail@example.test", "secret")
        fixture.engine.registerChild("account-A", "철수")
        fixture.engine.registerChild("account-A", "영희")
        listOf("철수", "영희").forEach { child ->
            listOf("한글", "수학", "영어").forEach { subject ->
                fixture.repository.enqueueReportResult(
                    child,
                    subject,
                    LearningRepositoryResult.Success(SubjectReport(child, subject, 2, 0)),
                )
            }
        }
        fixture.commands.drain()
        fixture.drainAll()
        fixture.repository.clearReports()

        fixture.executionStatus.set(accessibilityEnabled = false, notificationsEnabled = true)
        fixture.engine.executionConditionsChanged(accessibilityEnabled = false, notificationsEnabled = true)
        fixture.commands.drain()
        fixture.engine.detachServiceLease()
        fixture.commands.drain()
        assertTrue(fixture.engine.snapshot().monitoringPaused)
        assertEquals(setOf("철수", "영희"), fixture.engine.snapshot().registeredChildren)

        listOf("철수", "영희").forEach { child ->
            listOf("한글", "수학", "영어").forEach { subject ->
                fixture.repository.enqueueReportResult(
                    child,
                    subject,
                    LearningRepositoryResult.Success(SubjectReport(child, subject, 20, 0)),
                )
            }
        }
        fixture.executionStatus.set(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.engine.executionConditionsChanged(accessibilityEnabled = true, notificationsEnabled = true)
        fixture.commands.drain()
        fixture.engine.attachServiceLease()
        fixture.commands.drain()
        fixture.drainAll()

        val recovered = fixture.engine.snapshot()
        assertFalse(recovered.monitoringPaused)
        assertEquals(setOf("철수", "영희"), recovered.registeredChildren)
        assertTrue(recovered.children.values.all { child ->
            child.subjects.values.all { it.activityCount == 20 && it.status == MonitorSubjectStatus.COMPLETE }
        })
        assertTrue(fixture.notifications.updates.none { it.completion })
        assertEquals(6, fixture.repository.reportedPairs.size)
        assertTrue(fixture.repository.reportedPairs.groupingBy { it }.eachCount().values.all { it == 1 })
    }

    @Test
    fun restoredRegistrationGetsFreshSilentBaselineAfterCredentialsAndRuntimeAreAvailable() {
        val fixture = LearningMonitorFixture(
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
}
