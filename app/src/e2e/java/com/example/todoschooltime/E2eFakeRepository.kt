package com.example.todoschooltime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.time.LocalDate

/** A fixed-account adapter whose delayed report result can arrive after the engine cancels it. */
internal class E2eFakeRepository(private val world: E2eWorldStore) : MonitorRepository, CancellableMonitorRepository {
    override fun signIn(email: String, password: String): LearningRepositoryResult<LearningSession> =
        signInCancellable(email, password, LearningRequestCancellation())

    override fun signInCancellable(
        email: String,
        password: String,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<LearningSession> {
        val success = email == E2eWorldStore.TEST_EMAIL && password == E2eWorldStore.TEST_PASSWORD
        world.recordReportQuery("signIn", outcome = if (success) "success" else "authenticationDenied")
        return if (success) {
            LearningRepositoryResult.Success(LearningSession(E2eWorldStore.TEST_ACCOUNT_ID, "e2e-static-token"))
        } else {
            LearningRepositoryResult.AuthenticationDenied(
                LearningAuthenticationDeniedException("E2E fake credentials were rejected."),
            )
        }
    }

    override fun loadCatalog(session: LearningSession): LearningRepositoryResult<LearningCatalog> =
        loadCatalogCancellable(session, LearningRequestCancellation())

    override fun loadCatalogCancellable(
        session: LearningSession,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<LearningCatalog> {
        val success = session.accountId.toString() == E2eWorldStore.TEST_ACCOUNT_ID
        world.recordReportQuery("loadCatalog", outcome = if (success) "success" else "authenticationDenied")
        if (!success) {
            return LearningRepositoryResult.AuthenticationDenied(
                LearningAuthenticationDeniedException("E2E fake account was rejected."),
            )
        }
        val children = world.children()
        return LearningRepositoryResult.Success(
            LearningCatalog(
                childOrder = children.map(E2eChild::name),
                subscriptions = children.flatMap { child ->
                    child.subjects.map { subject ->
                        ChildSubjectSubscription(
                            childName = child.name,
                            subjectName = subject.name,
                            userId = "fake-${child.id}",
                        )
                    }
                },
            ),
        )
    }

    override fun loadSubjectReport(
        session: LearningSession,
        subscription: ChildSubjectSubscription,
        date: LocalDate,
    ): LearningRepositoryResult<SubjectReport> = loadSubjectReportCancellable(
        session,
        subscription,
        date,
        LearningRequestCancellation(),
    )

    override fun loadSubjectReportCancellable(
        session: LearningSession,
        subscription: ChildSubjectSubscription,
        date: LocalDate,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<SubjectReport> {
        val childId = world.childIdForName(subscription.childName)
        val subject = world.children().firstOrNull { it.id == childId }
            ?.subjects?.firstOrNull { it.name == subscription.subjectName }
        if (session.accountId.toString() != E2eWorldStore.TEST_ACCOUNT_ID || childId == null || subject == null) {
            world.recordReportQuery(
                operation = "loadSubjectReport",
                childId = childId,
                date = date,
                outcome = "invalidSubscription",
            )
            return LearningRepositoryResult.TemporaryFailure(
                LearningRepositoryException("E2E fake subscription was rejected."),
            )
        }

        // Capture this request's response before waiting so later setReport commands cannot rewrite it.
        val captured = world.report(childId, subject.code)
        val outcomeName = when (captured.outcome) {
            FakeReportOutcome.SUCCESS -> "success"
            FakeReportOutcome.TEMPORARY_FAILURE -> "temporaryFailure"
            FakeReportOutcome.AUTHENTICATION_DENIED -> "authenticationDenied"
        }
        world.recordReportQuery(
            operation = "loadSubjectReport",
            childId = childId,
            subjectCode = subject.code,
            date = date,
            outcome = outcomeName,
            delayMs = captured.delayMs,
        )
        if (captured.delayMs > 0) {
            try {
                Thread.sleep(captured.delayMs.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return when (captured.outcome) {
            FakeReportOutcome.SUCCESS -> LearningRepositoryResult.Success(
                SubjectReport(
                    childName = subscription.childName,
                    subjectName = subscription.subjectName,
                    activityCount = captured.count,
                    learningSeconds = captured.count.toLong() * SECONDS_PER_ACTIVITY,
                ),
            )
            FakeReportOutcome.TEMPORARY_FAILURE -> LearningRepositoryResult.TemporaryFailure(
                LearningRepositoryException("Simulated E2E temporary report failure."),
            )
            FakeReportOutcome.AUTHENTICATION_DENIED -> LearningRepositoryResult.AuthenticationDenied(
                LearningAuthenticationDeniedException("Simulated E2E authentication denial."),
            )
        }
    }

    private companion object {
        const val SECONDS_PER_ACTIVITY = 60L
    }
}

internal class E2eAuditedNotificationSink(
    context: Context,
    private val world: E2eWorldStore,
    notificationIdPreferences: SharedPreferences,
) : ChildNotificationSink {
    private val delegate = createSilentDelegate(context, notificationIdPreferences)

    override fun showStatus(
        accountId: String,
        child: MonitorChildSnapshot,
        completion: Boolean,
        completionSubjectName: String?,
    ) {
        world.recordNotificationRequest(
            operation = "show",
            childName = child.childName,
            completionRequested = completion,
            completionSubjectName = completionSubjectName,
            summary = MonitorNotificationText.summary(child),
        )
        // Preserve actual NotificationManager posting while silencing every automated E2E post.
        delegate.showStatus(accountId, child, completion = false, completionSubjectName = completionSubjectName)
    }

    override fun cancel(accountId: String, childName: String) {
        world.recordNotificationRequest(operation = "cancel", childName = childName)
        delegate.cancel(accountId, childName)
    }

    private fun createSilentDelegate(
        context: Context,
        notificationIdPreferences: SharedPreferences,
    ): AndroidChildNotificationSink {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(PRODUCT_NOTIFICATION_CHANNEL_ID, "학습 상태", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "E2E 테스트용 무음 학습 상태"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        return AndroidChildNotificationSink(
            context,
            SharedPreferencesNotificationIdAllocator(notificationIdPreferences),
        )
    }

    private companion object {
        // Pre-create the production channel with silent settings before its sink requests HIGH importance.
        const val PRODUCT_NOTIFICATION_CHANNEL_ID = "learning-monitor-status"
    }
}
