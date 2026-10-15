package com.example.todoschooltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMonitorCompletionTest {
    @Test
    fun aNewCrossingUpdatesTheChildNotificationWithTheExactNewestSubjectAndAllStatuses() {
        val fixture = LearningMonitorFixture(
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
    fun registeringAnotherChildPreservesExistingBelowTargetBaselineAndItsNextCompletion() {
        val fixture = LearningMonitorFixture(
            reports = listOf("한글", "수학", "영어").associateWith { subject ->
                LearningRepositoryResult.Success(SubjectReport("철수", subject, 0, 0))
            },
        )
        fixture.startAndRegister("철수")
        fixture.repository.clearReports()

        val oldBeforeRegistration = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("영어")
        assertEquals(MonitorSubjectStatus.BELOW_TARGET, oldBeforeRegistration.status)
        assertEquals(0, oldBeforeRegistration.activityCount)

        // Model the old child reaching its goal while a different child is being registered.
        fixture.repository.enqueueReportResult(
            "철수",
            "영어",
            LearningRepositoryResult.Success(SubjectReport("철수", "영어", 8, 0)),
        )
        fixture.engine.registerChild("account-A", "영희")
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(
            listOf("영희" to "한글", "영희" to "수학", "영희" to "영어"),
            fixture.repository.reportedPairs,
        )
        val oldAfterRegistration = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("영어")
        assertEquals(MonitorSubjectStatus.BELOW_TARGET, oldAfterRegistration.status)
        assertEquals(0, oldAfterRegistration.activityCount)
        assertTrue(fixture.notifications.updates.none { it.completion })

        fixture.repository.clearReports()
        fixture.notifications.updates.clear()
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todoenglish")
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals(listOf("철수" to "영어", "영희" to "영어"), fixture.repository.reportedPairs)
        val completion = fixture.notifications.updates.single { it.completion }
        assertEquals("철수", completion.child.childName)
        assertEquals("영어", completion.completionSubjectName)
        assertEquals(MonitorSubjectStatus.COMPLETE,
            completion.child.subjects.getValue("영어").status)
        assertEquals(8, completion.child.subjects.getValue("영어").activityCount)
        assertTrue(fixture.notifications.updates.filter { it.child.childName == "영희" }.all { !it.completion })
    }

    @Test
    fun changingATargetReclassifiesKnownCountsSilentlyAndOnlyLaterObservedCrossingAlerts() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 2, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 3, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
            ),
        )
        fixture.startAndRegister("철수")
        fixture.notifications.updates.clear()
        fixture.repository.clearReports()

        fixture.setTarget("수학", 0)
        fixture.engine.setTargetCounts("account-A", "철수", mapOf("수학" to 0))
        fixture.commands.drain()

        val lowered = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학")
        assertEquals(MonitorSubjectStatus.COMPLETE, lowered.status)
        assertEquals(3, lowered.activityCount)
        assertTrue(fixture.notifications.updates.none { it.completion })
        assertTrue(fixture.repository.reportedPairs.isEmpty())

        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        fixture.clock.advanceBy(120_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()
        assertTrue(fixture.repository.reportedPairs.isEmpty())

        fixture.setTarget("수학", 4)
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.Success(SubjectReport("철수", "수학", 4, 0)),
        )
        fixture.engine.setTargetCounts("account-A", "철수", mapOf("수학" to 4))
        fixture.commands.drain()
        val raised = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학")
        assertEquals(MonitorSubjectStatus.BELOW_TARGET, raised.status)
        assertEquals(3, raised.activityCount)
        assertTrue(fixture.notifications.updates.none { it.completion })
        fixture.drainAll()

        assertEquals(listOf("철수" to "수학"), fixture.repository.reportedPairs)
        assertEquals(MonitorSubjectStatus.COMPLETE,
            fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학").status)
        assertEquals("수학", fixture.notifications.updates.single { it.completion }.completionSubjectName)
    }

    @Test
    fun aFirstPositiveGoalAfterZeroUsesAnUnknownFirstSuccessAsSilentBaseline() {
        val fixture = LearningMonitorFixture(
            reports = mapOf(
                "한글" to LearningRepositoryResult.Success(SubjectReport("철수", "한글", 2, 0)),
                "수학" to LearningRepositoryResult.Success(SubjectReport("철수", "수학", 5, 0)),
                "영어" to LearningRepositoryResult.Success(SubjectReport("철수", "영어", 2, 0)),
            ),
            targets = mapOf("수학" to 0),
        )
        fixture.startAndRegister("철수")

        val zeroGoal = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학")
        assertEquals(MonitorSubjectStatus.COMPLETE, zeroGoal.status)
        assertNull(zeroGoal.activityCount)
        assertTrue(fixture.repository.reportedPairs.none { it.second == "수학" })

        fixture.repository.clearReports()
        fixture.notifications.updates.clear()
        fixture.setTarget("수학", 5)
        fixture.repository.enqueueReportResult(
            "철수",
            "수학",
            LearningRepositoryResult.Success(SubjectReport("철수", "수학", 5, 0)),
        )
        fixture.engine.setTargetCounts("account-A", "철수", mapOf("수학" to 5))
        fixture.commands.drain()
        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todomath")
        fixture.commands.drain()
        fixture.clock.advanceBy(60_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.drainAll()

        val firstPositiveSuccess = fixture.engine.snapshot().children.getValue("철수").subjects.getValue("수학")
        assertEquals(MonitorSubjectStatus.COMPLETE, firstPositiveSuccess.status)
        assertEquals(5, firstPositiveSuccess.activityCount)
        assertEquals(listOf("철수" to "수학"), fixture.repository.reportedPairs)
        assertTrue(fixture.notifications.updates.none { it.completion })
    }
}
