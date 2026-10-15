package com.example.todoschooltime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LearningMonitorScreenCoordinatorTest {
    @Test
    fun postBoundaryScreenPairIsSharedWithInitialBaselineThroughProductionRepository() {
        val fixture = Fixture()
        val request = fixture.startScreenAndHoldCatalog()
        val screenCatalog = fixture.workers.takeNext()

        fixture.engine.registerChild("41", "민아")
        fixture.commands.drain()
        fixture.runWorkerAndCommands()
        val baselineCatalog = fixture.workers.takeNext()

        screenCatalog()
        fixture.commands.drain()
        val screenPair = fixture.workers.takeNext()

        baselineCatalog()
        fixture.commands.drain()
        assertTrue("The initial baseline should join the in-flight SCREEN pair", fixture.workers.isEmpty())

        screenPair()
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals(1, fixture.transport.reportCalls)
        val finished = request.outcomes.single() as LearningReportRequestOutcome.Finished
        val screenResult = (finished.result as LearningRepositoryResult.Success).value
        assertEquals("41", screenResult.accountId)
        assertEquals(listOf("민아"), screenResult.children.map { it.name })
        assertEquals(listOf(2), screenResult.children.single().subjects.map { it.count })
        val baseline = fixture.engine.snapshot().children.getValue("민아").subjects.getValue("한글")
        assertEquals(2, baseline.activityCount)
        assertEquals(MonitorSubjectStatus.BELOW_TARGET, baseline.status)
        assertTrue(fixture.notifications.completions.isEmpty())
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun screenCancellationLeavesTheJoinedBaselineConsumerAndPhysicalRequestAlive() {
        val fixture = Fixture()
        val request = fixture.startScreenAndHoldCatalog()
        val screenCatalog = fixture.workers.takeNext()

        fixture.engine.registerChild("41", "민아")
        fixture.commands.drain()
        fixture.runWorkerAndCommands()
        val baselineCatalog = fixture.workers.takeNext()

        screenCatalog()
        fixture.commands.drain()
        val screenPair = fixture.workers.takeNext()
        baselineCatalog()
        fixture.commands.drain()

        request.handle.cancel()
        fixture.commands.drain()
        fixture.ui.drain()
        assertEquals(listOf(LearningReportRequestOutcome.Cancelled), request.outcomes)

        screenPair()
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals(1, fixture.transport.reportCalls)
        assertEquals(2, fixture.engine.snapshot().children.getValue("민아")
            .subjects.getValue("한글").activityCount)
        assertEquals(listOf(LearningReportRequestOutcome.Cancelled), request.outcomes)
        assertTrue(fixture.notifications.completions.isEmpty())
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun registrationResetDetachesOnlyTheBaselineConsumerFromSharedScreenWork() {
        val fixture = Fixture()
        val request = fixture.startSharedScreenPair()
        fixture.transport.enqueueReportCounts(3, 1)
        val screenPair = fixture.workers.takeNext()

        fixture.engine.registerChild("41", "민아")
        fixture.commands.drain()
        fixture.ui.drain()

        screenPair()
        fixture.commands.drain()
        fixture.ui.drain()
        assertEquals(1, fixture.transport.reportCalls)
        assertEquals(1, request.outcomes.size)

        fixture.drainAll()
        assertEquals(2, fixture.transport.reportCalls)
        assertEquals(1, fixture.engine.snapshot().children.getValue("민아")
            .subjects.getValue("한글").activityCount)
        assertTrue(fixture.notifications.completions.isEmpty())
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun permissionPausePreservesSharedScreenAndRecoveryDoesNotReusePauseTimePair() {
        val fixture = Fixture()
        val request = fixture.startSharedScreenPair()
        fixture.transport.enqueueReportCounts(3, 1)
        val screenPair = fixture.workers.takeNext()

        fixture.engine.executionConditionsChanged(false, true)
        fixture.commands.drain()
        screenPair()
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals(1, fixture.transport.reportCalls)
        assertTrue(request.outcomes.single() is LearningReportRequestOutcome.Finished)
        assertTrue(fixture.notifications.completions.isEmpty())

        fixture.engine.executionConditionsChanged(true, true)
        fixture.commands.drain()
        fixture.drainAll()

        assertEquals("Recovery must dispatch a fresh pair instead of reusing pause-time SCREEN work",
            2, fixture.transport.reportCalls)
        assertEquals(1, fixture.engine.snapshot().children.getValue("민아")
            .subjects.getValue("한글").activityCount)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun sharedUnauthorizedPairDeliversScreenFailureBeforePausingItsMonitorConsumer() {
        val fixture = Fixture()
        val request = fixture.startSharedScreenPair()
        fixture.transport.enqueueReportStatus(401)
        fixture.workers.takeNext().invoke()
        fixture.commands.drain()
        fixture.ui.drain()

        assertTrue("The shared current SCREEN request should receive its 401 result",
            (request.outcomes.single() as LearningReportRequestOutcome.Finished)
                .result is LearningRepositoryResult.AuthenticationDenied)
        assertTrue("The same current 401 should pause monitoring", fixture.engine.snapshot().authenticationRequired)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun cancellingLastConsumerBeforeDispatchSuppressesQueuedHttpAndNotifiesOnce() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        val request = fixture.beginScreenRequest()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        val queuedPair = fixture.workers.takeNext()

        request.handle.cancel()
        fixture.commands.drain()
        fixture.ui.drain()
        request.handle.cancel()
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals(listOf(LearningReportRequestOutcome.Cancelled), request.outcomes)
        queuedPair()
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals("A cancelled queued pair must not start HTTP", 0, fixture.transport.reportCalls)
        assertEquals(listOf(LearningReportRequestOutcome.Cancelled), request.outcomes)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun supersedingScreenRequestTerminatesOldConsumerOnceAndSuppressesItsPair() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        val oldRequest = fixture.beginScreenRequest()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        val oldPair = fixture.workers.takeNext()

        val currentRequest = fixture.beginScreenRequest()
        fixture.ui.drain()
        assertEquals(listOf(LearningReportRequestOutcome.Superseded), oldRequest.outcomes)

        oldPair()
        fixture.commands.drain()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        fixture.ui.drain()

        assertEquals(listOf(LearningReportRequestOutcome.Superseded), oldRequest.outcomes)
        assertEquals(1, currentRequest.outcomes.size)
        assertTrue(currentRequest.outcomes.single() is LearningReportRequestOutcome.Finished)
        assertEquals(1, fixture.transport.reportCalls)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun lateScreenUnauthorizedResultAfterReauthenticationCannotPauseTheNewAuthEpoch() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        val request = fixture.beginScreenRequest()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        val reportGate = fixture.transport.blockNextReportWithStatus(401)
        val reportWorker = fixture.workers.takeNext()
        var workerFailure: Throwable? = null
        val thread = Thread {
            try {
                reportWorker()
            } catch (failure: Throwable) {
                workerFailure = failure
            }
        }
        thread.start()
        assertTrue("The fake report request should reach the transport", reportGate.started.await(5, TimeUnit.SECONDS))

        fixture.engine.reauthenticate("41", "parent@example.test", "rotated-secret")
        fixture.commands.drain()
        fixture.ui.drain()
        assertEquals(listOf(LearningReportRequestOutcome.Invalidated), request.outcomes)

        reportGate.release.countDown()
        thread.join(5_000L)
        assertTrue("The fake report worker should finish", !thread.isAlive)
        assertEquals(null, workerFailure)
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals(1, fixture.transport.reportCalls)
        assertEquals("The old auth epoch must not pause the current account", false,
            fixture.engine.snapshot().authenticationRequired)
        assertEquals(listOf(LearningReportRequestOutcome.Invalidated), request.outcomes)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun lateScreenUnauthorizedResultAfterAccountSwitchCannotPauseTheNewAccount() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        val request = fixture.beginScreenRequest()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        val reportGate = fixture.transport.blockNextReportWithStatus(401)
        val reportWorker = fixture.workers.takeNext()
        var workerFailure: Throwable? = null
        val thread = Thread {
            try {
                reportWorker()
            } catch (failure: Throwable) {
                workerFailure = failure
            }
        }
        thread.start()
        assertTrue("The fake report request should reach the transport", reportGate.started.await(5, TimeUnit.SECONDS))

        fixture.engine.credentialsAvailable("42", "other@example.test", "other-secret")
        fixture.commands.drain()
        fixture.ui.drain()
        assertEquals(listOf(LearningReportRequestOutcome.Invalidated), request.outcomes)

        reportGate.release.countDown()
        thread.join(5_000L)
        assertTrue("The fake report worker should finish", !thread.isAlive)
        assertEquals(null, workerFailure)
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals(1, fixture.transport.reportCalls)
        assertEquals("The new account must not inherit the old account's denial", false,
            fixture.engine.snapshot().authenticationRequired)
        assertEquals(listOf(LearningReportRequestOutcome.Invalidated), request.outcomes)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun latestExplicitScreenUnauthorizedResultRemainsAccountEvidenceAcrossSeoulDateRollover() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        val request = fixture.beginScreenRequest()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        fixture.transport.enqueueReportStatus(401)
        val reportWorker = fixture.workers.takeNext()

        fixture.clock.today = LocalDate.of(2026, 10, 11)
        reportWorker()
        fixture.commands.drain()
        fixture.ui.drain()

        assertTrue("The latest SCREEN request is still current account auth evidence",
            fixture.engine.snapshot().authenticationRequired)
        assertTrue((request.outcomes.single() as LearningReportRequestOutcome.Finished)
            .result is LearningRepositoryResult.AuthenticationDenied)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun accountSwitchInvalidatesAnOldScreenConsumerBeforeItCanReturnAResult() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        val request = fixture.beginScreenRequest()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        val oldAccountScreenPair = fixture.workers.takeNext()

        fixture.engine.credentialsAvailable("42", "other@example.test", "new-secret")
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals(listOf(LearningReportRequestOutcome.Invalidated), request.outcomes)
        oldAccountScreenPair()
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals(0, fixture.transport.reportCalls)
        assertEquals(listOf(LearningReportRequestOutcome.Invalidated), request.outcomes)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun sameAccountReauthenticationInvalidatesAScreenRequestWithoutAnAccountHint() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        val request = fixture.beginScreenRequest(accountIdHint = null)
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        val oldAuthScreenPair = fixture.workers.takeNext()

        fixture.engine.reauthenticate("41", "parent@example.test", "rotated-secret")
        fixture.commands.drain()
        fixture.ui.drain()
        assertEquals(listOf(LearningReportRequestOutcome.Invalidated), request.outcomes)

        oldAuthScreenPair()
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals(0, fixture.transport.reportCalls)
        assertEquals(listOf(LearningReportRequestOutcome.Invalidated), request.outcomes)
        assertEquals(false, fixture.engine.snapshot().authenticationRequired)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun signInDenialOnlyPausesTheAccountWhoseKnownCredentialsWereRejected() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        fixture.transport.signInStatus = 401

        val unrelatedCredentials = fixture.beginScreenRequest("new@example.test", "wrong-password")
        fixture.runWorkerAndCommands()
        fixture.ui.drain()

        assertEquals(false, fixture.engine.snapshot().authenticationRequired)
        assertTrue((unrelatedCredentials.outcomes.single() as LearningReportRequestOutcome.Finished)
            .result is LearningRepositoryResult.AuthenticationDenied)

        val knownCredentials = fixture.beginScreenRequest("parent@example.test", "secret")
        fixture.runWorkerAndCommands()
        fixture.ui.drain()

        assertEquals(true, fixture.engine.snapshot().authenticationRequired)
        assertTrue((knownCredentials.outcomes.single() as LearningReportRequestOutcome.Finished)
            .result is LearningRepositoryResult.AuthenticationDenied)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun reportDenialUsesTheSuccessfulSessionAccountEvenWhenEnteredCredentialsDiffer() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        fixture.transport.enqueueReportStatus(401)
        val request = fixture.beginScreenRequest("alternate@example.test", "alternate-secret")
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        fixture.ui.drain()

        assertTrue("A successful session for the active account attributes a later report denial",
            fixture.engine.snapshot().authenticationRequired)
        assertTrue((request.outcomes.single() as LearningReportRequestOutcome.Finished)
            .result is LearningRepositoryResult.AuthenticationDenied)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun screenPairDispatchedBeforeRegistrationBoundaryIsNotReusedOrCached() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        val request = fixture.beginScreenRequest()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        val screenPair = fixture.workers.takeNext()

        fixture.engine.registerChild("41", "민아")
        fixture.commands.drain()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()

        assertEquals("A pre-boundary SCREEN pair must not satisfy the baseline", 1, fixture.workers.size())
        val baselinePair = fixture.workers.takeNext()

        screenPair()
        fixture.commands.drain()
        fixture.ui.drain()
        baselinePair()
        fixture.commands.drain()

        assertEquals(2, fixture.transport.reportCalls)
        assertEquals(2, fixture.engine.snapshot().children.getValue("민아")
            .subjects.getValue("한글").activityCount)
        assertEquals(1, request.outcomes.size)
        fixture.transport.assertOnlyFakeRequests()
    }

    @Test
    fun screenReportDoesNotJoinOrAdvanceTheRegularMonitorConsumer() {
        val fixture = Fixture()
        fixture.prepareMonitor()
        fixture.engine.registerChild("41", "민아")
        fixture.commands.drain()
        fixture.drainAll()
        fixture.transport.clearReportCalls()

        fixture.engine.screenOnChanged(true)
        fixture.engine.foregroundPackageChanged("com.enuma.todohangeul")
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.clock.advanceBy(30_000L)
        fixture.timers.runDue()
        fixture.commands.drain()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        val regularPair = fixture.workers.takeNext()
        fixture.transport.enqueueReportCounts(3, 1)

        val screenRequest = fixture.beginScreenRequest()
        fixture.runWorkerAndCommands()
        fixture.runWorkerAndCommands()
        val screenPair = fixture.workers.takeNext()
        screenPair()
        fixture.commands.drain()
        fixture.ui.drain()

        val screenResult = ((screenRequest.outcomes.single() as LearningReportRequestOutcome.Finished)
            .result as LearningRepositoryResult.Success).value
        assertEquals(3, screenResult.children.single().subjects.single().count)
        assertEquals("The independent SCREEN result must not advance the monitor watermark", 2,
            fixture.engine.snapshot().children.getValue("민아").subjects.getValue("한글").activityCount)

        regularPair()
        fixture.commands.drain()
        fixture.ui.drain()

        assertEquals(2, fixture.transport.reportCalls)
        assertEquals(1, fixture.engine.snapshot().children.getValue("민아").subjects.getValue("한글").activityCount)
        assertTrue(fixture.notifications.completions.isEmpty())
        fixture.transport.assertOnlyFakeRequests()
    }

    private class Fixture {
        val commands = ManualQueue()
        val workers = ManualQueue()
        val ui = ManualQueue()
        val transport = FakeTransport()
        val notifications = FakeNotifications()
        private val execution = MonitorExecutionStatus(true, true)
        val clock = FakeClock()
        val timers = ManualTimerFactory(clock)
        val engine = LearningMonitorEngine(
            commandQueue = commands,
            workerQueue = workers,
            callbackQueue = ui,
            clock = object : MonitorClock {
                override fun today() = clock.today
                override fun checkedAt() = "checked"
                override fun monotonicMillis() = clock.monotonicMillis
                override fun millisUntilNextSeoulMidnight() = 86_400_000L
            },
            timerFactory = timers,
            store = object : MonitorStore {
                override fun registeredChildren(accountId: String) = emptySet<String>()
                override fun saveRegisteredChildren(accountId: String, children: Set<String>) = Unit
                override fun intervalMinutes(accountId: String) = BigInteger.ONE
                override fun saveIntervalMinutes(accountId: String, minutes: BigInteger) = Unit
            },
            repository = LearningReportRepository(transport),
            targetProvider = MonitorTargetProvider { _, _ -> 3 },
            notificationSink = notifications,
            executionStatusProbe = MonitorExecutionStatusProbe { execution },
        )

        fun prepareMonitor() {
            engine.executionConditionsChanged(true, true)
            engine.attachServiceLease()
            engine.credentialsAvailable("41", "parent@example.test", "secret")
            commands.drain()
        }

        fun beginScreenRequest(
            email: String = "parent@example.test",
            password: String = "secret",
            accountIdHint: String? = "41",
        ): Request {
            val outcomes = mutableListOf<LearningReportRequestOutcome>()
            val handle = engine.loadScreenReport(
                email = email,
                password = password,
                date = LocalDate.of(2026, 10, 10),
                accountIdHint = accountIdHint,
                listener = outcomes::add,
            )
            commands.drain()
            return Request(handle, outcomes)
        }

        fun startScreenAndHoldCatalog(): Request {
            prepareMonitor()
            val request = beginScreenRequest()
            runWorkerAndCommands()
            return request
        }

        fun startSharedScreenPair(): Request {
            val request = startScreenAndHoldCatalog()
            val screenCatalog = workers.takeNext()

            engine.registerChild("41", "민아")
            commands.drain()
            runWorkerAndCommands()
            val baselineCatalog = workers.takeNext()

            screenCatalog()
            commands.drain()
            val screenPair = workers.takeNext()
            baselineCatalog()
            commands.drain()
            assertTrue("The baseline should have joined the shared SCREEN pair", workers.isEmpty())
            workers.enqueue(screenPair)
            return request
        }

        fun runWorkerAndCommands() {
            workers.takeNext().invoke()
            commands.drain()
        }

        fun drainAll() {
            while (!commands.isEmpty() || !workers.isEmpty()) {
                commands.drain()
                workers.drain()
                commands.drain()
            }
        }
    }

    private data class Request(
        val handle: LearningReportRequestHandle,
        val outcomes: MutableList<LearningReportRequestOutcome>,
    )

    private class ManualQueue : MonitorTaskQueue {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun enqueue(task: () -> Unit) { tasks.addLast(task) }
        fun takeNext(): () -> Unit = tasks.removeFirst()
        fun size(): Int = tasks.size
        fun isEmpty(): Boolean = tasks.isEmpty()
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().invoke() }
    }

    private class FakeClock : MonitorClock {
        var today = LocalDate.of(2026, 10, 10)
        var monotonicMillis = 0L
        override fun today() = today
        override fun checkedAt() = "checked"
        override fun monotonicMillis() = monotonicMillis
        override fun millisUntilNextSeoulMidnight() = 86_400_000L
        fun advanceBy(millis: Long) { monotonicMillis += millis }
    }

    private class ManualTimerFactory(private val clock: FakeClock) : MonitorTimerFactory {
        private data class Timer(
            val dueAt: Long,
            val callback: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val timers = mutableListOf<Timer>()
        override fun schedule(delayMillis: Long, callback: () -> Unit): MonitorTimer {
            val timer = Timer(clock.monotonicMillis + delayMillis, callback)
            timers += timer
            return MonitorTimer { timer.cancelled = true }
        }

        fun runDue() {
            while (true) {
                val timer = timers.firstOrNull { !it.cancelled && it.dueAt <= clock.monotonicMillis } ?: return
                timers.remove(timer)
                timer.callback()
            }
        }
    }

    private class FakeTransport : LearningHttpTransport {
        val calls = mutableListOf<String>()
        var signInStatus = 200
        var reportCalls = 0
            private set
        private val queuedReportCounts = ArrayDeque<Int>()
        private val queuedReportStatuses = ArrayDeque<Int>()
        @Volatile private var blockedReport: BlockedReport? = null

        data class BlockedReport(
            val statusCode: Int,
            val started: CountDownLatch = CountDownLatch(1),
            val release: CountDownLatch = CountDownLatch(1),
        )

        override fun post(path: String, body: JSONObject, token: String?): LearningHttpResponse {
            calls += path
            return when (path) {
                "/v3/account/signin" -> if (signInStatus == 200) {
                    success(JSONObject().put("authToken", "fake-token").put("accountId", 41))
                } else {
                    LearningHttpResponse(signInStatus, "unauthorized")
                }
                "/v3/todohangeul/user" -> success(users("민아", "hangul-user"))
                "/v3/math/user", "/v3/english/user" -> success(JSONArray())
                "/v3/todoschool/report/daily/hangeul" -> {
                    reportCalls += 1
                    reportResponse()
                }
                else -> throw AssertionError("Unexpected fake HTTP path: $path")
            }
        }

        override fun post(
            path: String,
            body: JSONObject,
            token: String?,
            cancellation: LearningRequestCancellation,
        ): LearningHttpResponse {
            if (path == "/v3/todoschool/report/daily/hangeul") {
                cancellation.throwIfCancelled()
                val blocked = blockedReport
                if (blocked != null) {
                    calls += path
                    reportCalls += 1
                    blockedReport = null
                    blocked.started.countDown()
                    check(blocked.release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release fake report response" }
                    // Model a response already in flight: best-effort cancellation can lose this race.
                    return if (blocked.statusCode == 401 || blocked.statusCode == 403) {
                        LearningHttpResponse(blocked.statusCode, "unauthorized")
                    } else {
                        success(report(queuedReportCounts.removeFirstOrNull() ?: 2))
                    }
                }
            }
            cancellation.throwIfCancelled()
            val response = post(path, body, token)
            cancellation.throwIfCancelled()
            return response
        }

        fun assertOnlyFakeRequests() {
            assertTrue(calls.isNotEmpty())
            assertTrue(calls.all { it.startsWith("/v3/") })
        }

        fun clearReportCalls() { reportCalls = 0 }

        fun enqueueReportCounts(vararg counts: Int) { queuedReportCounts.addAll(counts.toList()) }

        fun enqueueReportStatus(status: Int) { queuedReportStatuses.addLast(status) }

        fun blockNextReportWithStatus(status: Int): BlockedReport = BlockedReport(status).also {
            blockedReport = it
        }

        private fun reportResponse(): LearningHttpResponse {
            val status = queuedReportStatuses.removeFirstOrNull()
            if (status != null) return LearningHttpResponse(status, "unauthorized")
            return success(report(queuedReportCounts.removeFirstOrNull() ?: 2))
        }

        private fun users(name: String, userId: String) = JSONArray().put(
            JSONObject().put("name", name).put("userId", userId),
        )

        private fun report(activityCount: Int) = JSONObject()
            .put("contentsHistory", JSONObject().put("dailyCourse", JSONArray().apply {
                repeat(activityCount) { put(JSONObject().put("id", it)) }
            }))
            .put("learningSecondsByCategory", JSONObject().put("course", 60).put("etc", 0))

        private fun success(data: Any) = LearningHttpResponse(
            200,
            JSONObject().put("result", true).put("data", data).toString(),
        )
    }

    private class FakeNotifications : ChildNotificationSink {
        val completions = mutableListOf<Pair<String, String>>()
        override fun showStatus(
            accountId: String,
            child: MonitorChildSnapshot,
            completion: Boolean,
            completionSubjectName: String?,
        ) {
            if (completion) completions += accountId to child.childName
        }
        override fun cancel(accountId: String, childName: String) = Unit
    }
}
