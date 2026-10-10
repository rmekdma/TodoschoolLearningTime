package com.example.todoschooltime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LearningReportRepositoryTest {

    @Test
    fun loadPreservesChildOrderSubscriptionsActivityCountsAndLearningMinutes() {
        val transport = FixtureTransport(
            fixture("/v3/account/signin", success(loginData(accountId = 41))),
            fixture("/v3/todohangeul/user", success(users(user("민아", "han-1"), user("지수", null)))),
            fixture("/v3/math/user", success(users(user("지수", "math-1"), user("민아", "math-1")))),
            fixture("/v3/english/user", success(users(user("민아", "english-1")))),
            fixture("/v3/todoschool/report/daily/hangeul", success(report("dailyCourse", 2, 61))),
            fixture("/v3/todoschool/report/daily/math", success(report("daily", 0, 120))),
            fixture("/v3/todoschool/report/daily/math", success(report("daily", 1, 60))),
            fixture("/v3/todoschool/report/daily/english", success(report("dailyCourse", 1, 59))),
        )
        val repository = LearningReportRepository(transport)

        val result = repository.load("parent@example.com", "secret", LocalDate.of(2026, 10, 9)).getOrThrow()

        assertEquals(listOf("민아", "지수"), result.children.map { it.name })
        assertEquals(3, result.children[0].minutes)
        assertEquals(
            listOf(SubjectProgress("한글", 2), SubjectProgress("수학", 1), SubjectProgress("영어", 1)),
            result.children[0].subjects,
        )
        assertEquals(2, result.children[1].minutes)
        assertEquals(listOf(SubjectProgress("수학", 0)), result.children[1].subjects)

        val calls = transport.calls
        assertEquals("parent@example.com", calls.first().body.getString("email"))
        assertEquals("secret", calls.first().body.getString("password"))
        assertEquals(41, calls[1].body.getJSONObject("user").getInt("accountId"))
        assertTrue(calls.drop(1).all { it.token == "auth-token" })
        assertTrue(calls.drop(4).all { it.body.getInt("yyyymmdd") == 20261009 })
        assertTrue(result.checkedAt.startsWith("마지막 확인 "))
        transport.assertDrained()
    }

    @Test
    fun pairReportUsesCatalogIdentityAndReturnsValidEmptyActivityListAsZero() {
        val transport = FixtureTransport(
            fixture("/v3/account/signin", success(loginData(accountId = "account-7"))),
            fixture("/v3/todohangeul/user", success(users(user("민아", "han-1")))),
            fixture("/v3/math/user", success(JSONArray())),
            fixture("/v3/english/user", success(JSONArray())),
            fixture("/v3/todoschool/report/daily/hangeul", success(report("dailyCourse", 0, 0))),
        )
        val repository = LearningReportRepository(transport)
        val session = repository.signIn("p@example.com", "pw").getOrThrow()
        val catalog = repository.loadCatalog(session).getOrThrow()
        val subscription = catalog.subscriptions.single()

        val report = repository.loadSubjectReport(session, subscription, LocalDate.of(2026, 10, 10)).getOrThrow()

        assertEquals("민아", report.childName)
        assertEquals("한글", report.subjectName)
        assertEquals(0, report.activityCount)
        assertEquals(0L, report.learningSeconds)
        assertEquals("han-1", transport.calls.last().body.getString("userId"))
        assertEquals(20261010, transport.calls.last().body.getInt("yyyymmdd"))
        transport.assertDrained()
    }

    @Test
    fun signInRetainsSessionIdTokenFallback() {
        val transport = FixtureTransport(
            fixture(
                "/v3/account/signin",
                success(JSONObject().put("sessionId", "legacy-session").put("accountId", "account-7")),
            ),
        )
        val repository = LearningReportRepository(transport)

        val session = repository.signIn("p@example.com", "pw").getOrThrow()

        assertEquals("account-7", session.accountId)
        assertEquals("legacy-session", session.authToken)
        transport.assertDrained()
    }

    @Test
    fun reportApiAndFormatFailuresNeverBecomeZeroActivitySuccess() {
        val failedApiResponse = jsonResponse(JSONObject().put("result", false).put("message", "temporary error").toString())
        val malformedResponse = jsonResponse("not json")
        val malformedReport = success(JSONObject().put("contentsHistory", JSONObject().put("dailyCourse", "not-an-array")))

        listOf(failedApiResponse, malformedResponse, malformedReport).forEach { response ->
            val outcome = loadPairWithReportResponse(response)

            assertTrue("Expected temporary failure for $response, got $outcome", outcome is LearningRepositoryResult.TemporaryFailure)
        }
    }

    @Test
    fun metadataFailureIsTypedAndNotAnEmptySubscriptionCatalog() {
        val transport = FixtureTransport(
            fixture("/v3/account/signin", success(loginData(accountId = "account-7"))),
            fixture("/v3/todohangeul/user", LearningHttpResponse(503, "service unavailable")),
        )
        val repository = LearningReportRepository(transport)
        val session = repository.signIn("p@example.com", "pw").getOrThrow()

        val outcome = repository.loadCatalog(session)

        assertTrue(outcome is LearningRepositoryResult.TemporaryFailure)
        assertFalse(outcome is LearningRepositoryResult.Success<*>)
        transport.assertDrained()
    }

    @Test
    fun authenticationDenialIsDistinctFromTemporaryHttpFailure() {
        val denied = LearningReportRepository(
            FixtureTransport(fixture("/v3/account/signin", LearningHttpResponse(401, "unauthorized"))),
        ).signIn("p@example.com", "wrong")
        val unavailable = LearningReportRepository(
            FixtureTransport(fixture("/v3/account/signin", LearningHttpResponse(503, "unavailable"))),
        ).signIn("p@example.com", "pw")
        val reportDenied = loadPairWithReportResponse(LearningHttpResponse(403, "forbidden"))

        assertTrue(denied is LearningRepositoryResult.AuthenticationDenied)
        assertTrue(unavailable is LearningRepositoryResult.TemporaryFailure)
        assertTrue(reportDenied is LearningRepositoryResult.AuthenticationDenied)
    }

    @Test
    fun validEmptyMetadataCatalogIsDifferentFromMetadataFailure() {
        val transport = FixtureTransport(
            fixture("/v3/account/signin", success(loginData(accountId = "account-7"))),
            fixture("/v3/todohangeul/user", success(JSONArray())),
            fixture("/v3/math/user", success(JSONArray())),
            fixture("/v3/english/user", success(JSONArray())),
        )
        val repository = LearningReportRepository(transport)
        val session = repository.signIn("p@example.com", "pw").getOrThrow()

        val catalog = repository.loadCatalog(session).getOrThrow()

        assertTrue(catalog.subscriptions.isEmpty())
        assertTrue(catalog.childOrder.isEmpty())
        transport.assertDrained()
    }

    private fun loadPairWithReportResponse(reportResponse: LearningHttpResponse): LearningRepositoryResult<SubjectReport> {
        val transport = FixtureTransport(
            fixture("/v3/account/signin", success(loginData(accountId = "account-7"))),
            fixture("/v3/todohangeul/user", success(users(user("민아", "han-1")))),
            fixture("/v3/math/user", success(JSONArray())),
            fixture("/v3/english/user", success(JSONArray())),
            fixture("/v3/todoschool/report/daily/hangeul", reportResponse),
        )
        val repository = LearningReportRepository(transport)
        val session = repository.signIn("p@example.com", "pw").getOrThrow()
        val subscription = repository.loadCatalog(session).getOrThrow().subscriptions.single()
        return repository.loadSubjectReport(session, subscription, LocalDate.of(2026, 10, 10))
    }

    private fun loginData(accountId: Any): JSONObject = JSONObject()
        .put("authToken", "auth-token")
        .put("accountId", accountId)

    private fun user(name: String, userId: String?): JSONObject = JSONObject().apply {
        put("name", name)
        if (userId != null) put("userId", userId)
    }

    private fun users(vararg values: JSONObject): JSONArray = JSONArray().apply {
        values.forEach(::put)
    }

    private fun report(arrayName: String, activityCount: Int, learningSeconds: Int): JSONObject = JSONObject().apply {
        put("contentsHistory", JSONObject().put(arrayName, JSONArray().apply {
            repeat(activityCount) { put(JSONObject().put("id", it)) }
        }))
        put("learningSecondsByCategory", JSONObject()
            .put("course", learningSeconds)
            .put("etc", 50_000))
    }

    private fun success(data: Any): LearningHttpResponse = jsonResponse(
        JSONObject().put("result", true).put("data", data).toString(),
    )

    private fun jsonResponse(body: String): LearningHttpResponse = LearningHttpResponse(200, body)

    private fun fixture(path: String, response: LearningHttpResponse) = Fixture(path, response)

    private data class Fixture(val path: String, val response: LearningHttpResponse)

    private class FixtureTransport(vararg fixtures: Fixture) : LearningHttpTransport {
        private val pending = ArrayDeque(fixtures.toList())
        val calls = mutableListOf<Call>()

        override fun post(path: String, body: JSONObject, token: String?): LearningHttpResponse {
            val fixture = pending.removeFirstOrNull()
                ?: throw AssertionError("Unexpected HTTP request: $path")
            assertEquals(fixture.path, path)
            calls += Call(path, JSONObject(body.toString()), token)
            return fixture.response
        }

        fun assertDrained() {
            assertTrue("Unconsumed fake HTTP responses: ${pending.size}", pending.isEmpty())
        }
    }

    private data class Call(val path: String, val body: JSONObject, val token: String?)
}
