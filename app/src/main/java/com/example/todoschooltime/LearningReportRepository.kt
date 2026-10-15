package com.example.todoschooltime

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

sealed class LearningRepositoryResult<out T> {
    data class Success<T>(val value: T) : LearningRepositoryResult<T>()
    data class TemporaryFailure(val error: LearningRepositoryException) : LearningRepositoryResult<Nothing>()
    data class AuthenticationDenied(val error: LearningAuthenticationDeniedException) : LearningRepositoryResult<Nothing>()
}

class LearningRepositoryException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class LearningAuthenticationDeniedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

fun <T> LearningRepositoryResult<T>.getOrThrow(): T = when (this) {
    is LearningRepositoryResult.Success -> value
    is LearningRepositoryResult.TemporaryFailure -> throw error
    is LearningRepositoryResult.AuthenticationDenied -> throw error
}

class LearningSession internal constructor(
    val accountId: Any,
    val authToken: String,
)

data class ChildSubjectSubscription(
    val childName: String,
    val subjectName: String,
    val userId: String,
)

data class LearningCatalog(
    val childOrder: List<String>,
    val subscriptions: List<ChildSubjectSubscription>,
)

data class SubjectReport(
    val childName: String,
    val subjectName: String,
    val activityCount: Int,
    val learningSeconds: Long,
)

internal data class LearningHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal interface LearningHttpTransport {
    fun post(path: String, body: JSONObject, token: String?): LearningHttpResponse

    fun post(
        path: String,
        body: JSONObject,
        token: String?,
        cancellation: LearningRequestCancellation,
    ): LearningHttpResponse {
        cancellation.throwIfCancelled()
        val response = post(path, body, token)
        cancellation.throwIfCancelled()
        return response
    }
}

/** Cancellation is best effort: the active connection is disconnected, but the HTTP stack may still finish it. */
class LearningRequestCancellation internal constructor() {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) activeConnection?.disconnect()
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun throwIfCancelled() {
        if (isCancelled()) throw IOException("Learning request cancelled")
    }

    internal fun attach(connection: HttpURLConnection) {
        activeConnection = connection
        if (isCancelled()) connection.disconnect()
    }

    internal fun detach(connection: HttpURLConnection) {
        if (activeConnection === connection) activeConnection = null
    }
}

class LearningReportRepository internal constructor(
    private val transport: LearningHttpTransport,
) : MonitorRepository, CancellableMonitorRepository {
    constructor() : this(UrlConnectionLearningHttpTransport())

    private data class SubjectEndpoint(
        val name: String,
        val usersPath: String,
        val reportPath: String,
        val reportActivityArray: String,
    )

    private val subjects = listOf(
        SubjectEndpoint("한글", "/v3/todohangeul/user", "/v3/todoschool/report/daily/hangeul", "dailyCourse"),
        SubjectEndpoint("수학", "/v3/math/user", "/v3/todoschool/report/daily/math", "daily"),
        SubjectEndpoint("영어", "/v3/english/user", "/v3/todoschool/report/daily/english", "dailyCourse"),
    )

    fun load(email: String, password: String, date: LocalDate = DateHelper.today()): LearningRepositoryResult<LearningResult> =
        capture {
            val session = signIn(email, password).getOrThrow()
            val checkedAt = DateHelper.formatCheckedAt(DateHelper.nowZoned())
            val catalog = loadCatalog(session).getOrThrow()
            if (catalog.childOrder.isEmpty()) {
                throw LearningRepositoryException("아이 정보를 찾지 못했습니다.")
            }

            val totals = LinkedHashMap<String, Long>()
            val subjectsByChild = LinkedHashMap<String, MutableList<SubjectProgress>>()
            for (subscription in catalog.subscriptions) {
                val report = loadSubjectReport(session, subscription, date).getOrThrow()
                totals[report.childName] = (totals[report.childName] ?: 0L) + report.learningSeconds
                subjectsByChild.getOrPut(report.childName) { mutableListOf() }
                    .add(SubjectProgress(report.subjectName, report.activityCount))
            }

            val children = catalog.childOrder.distinct().map { name ->
                val minutes = ceil((totals[name] ?: 0L) / 60.0).toInt()
                ChildLearningInfo(name, minutes, subjectsByChild[name] ?: emptyList())
            }
            LearningResult(checkedAt, children, session.accountId.toString())
        }

    override fun signIn(email: String, password: String): LearningRepositoryResult<LearningSession> =
        signInInternal(email, password, null)

    override fun signInCancellable(
        email: String,
        password: String,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<LearningSession> = signInInternal(email, password, cancellation)

    private fun signInInternal(
        email: String,
        password: String,
        cancellation: LearningRequestCancellation?,
    ): LearningRepositoryResult<LearningSession> = capture {
        val body = JSONObject()
            .put("accountType", "personal")
            .put("getSessionId", true)
            .put("product", "todoschool")
            .put("packageName", "com.enuma.todoschool")
            .put("fromWeb", true)
            .put("countryCode", "ko")
            .put("email", email)
            .put("password", password)

        val login = postData("/v3/account/signin", body, null, isSignIn = true, cancellation = cancellation) as? JSONObject
            ?: throw LearningRepositoryException("로그인 응답 형식이 예상과 다릅니다.")
        val token = login.optString("authToken").ifBlank { login.optString("sessionId") }
        if (token.isBlank()) throw LearningRepositoryException("로그인 응답에 유효한 토큰이 없습니다.")

        val accountId = login.opt("accountId")
        if (accountId == null || accountId == JSONObject.NULL) {
            throw LearningRepositoryException("accountId를 받지 못했습니다.")
        }
        LearningSession(accountId, token)
    }

    override fun loadCatalog(session: LearningSession): LearningRepositoryResult<LearningCatalog> =
        loadCatalogInternal(session, null)

    override fun loadCatalogCancellable(
        session: LearningSession,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<LearningCatalog> = loadCatalogInternal(session, cancellation)

    private fun loadCatalogInternal(
        session: LearningSession,
        cancellation: LearningRequestCancellation?,
    ): LearningRepositoryResult<LearningCatalog> = capture {
        val subscriptions = mutableListOf<ChildSubjectSubscription>()
        val childOrder = linkedSetOf<String>()

        subjects.forEach { subject ->
            val users = postData(
                path = subject.usersPath,
                body = JSONObject().put("user", JSONObject().put("accountId", session.accountId)),
                token = session.authToken,
                cancellation = cancellation,
            ) as? JSONArray ?: throw LearningRepositoryException("${subject.name}: 아이 목록 형식이 예상과 다릅니다.")

            for (index in 0 until users.length()) {
                val user = try {
                    users.getJSONObject(index)
                } catch (exception: Exception) {
                    throw LearningRepositoryException("${subject.name}: 아이 목록 형식이 예상과 다릅니다.", exception)
                }
                val childName = user.optString("name").trim()
                if (childName.isBlank() || !TodoSchoolDataParser.isSubscribed(user)) continue

                val userId = user.optString("userId").ifBlank { user.opt("userId")?.toString().orEmpty() }
                childOrder += childName
                subscriptions += ChildSubjectSubscription(childName, subject.name, userId)
            }
        }

        LearningCatalog(childOrder.toList(), subscriptions.toList())
    }

    override fun loadSubjectReport(
        session: LearningSession,
        subscription: ChildSubjectSubscription,
        date: LocalDate,
    ): LearningRepositoryResult<SubjectReport> = loadSubjectReportInternal(session, subscription, date, null)

    override fun loadSubjectReportCancellable(
        session: LearningSession,
        subscription: ChildSubjectSubscription,
        date: LocalDate,
        cancellation: LearningRequestCancellation,
    ): LearningRepositoryResult<SubjectReport> =
        loadSubjectReportInternal(session, subscription, date, cancellation)

    private fun loadSubjectReportInternal(
        session: LearningSession,
        subscription: ChildSubjectSubscription,
        date: LocalDate,
        cancellation: LearningRequestCancellation?,
    ): LearningRepositoryResult<SubjectReport> = capture {
        val subject = subjects.firstOrNull { it.name == subscription.subjectName }
            ?: throw LearningRepositoryException("지원하지 않는 과목입니다: ${subscription.subjectName}")
        val report = postData(
            path = subject.reportPath,
            body = JSONObject()
                .put("userId", subscription.userId)
                .put("yyyymmdd", DateHelper.toYyyyMmDd(date))
                .put("languageCode", "ko"),
            token = session.authToken,
            cancellation = cancellation,
        ) as? JSONObject ?: throw LearningRepositoryException("${subject.name}: 학습 보고서 형식이 예상과 다릅니다.")

        val contentsHistory = report.optJSONObject("contentsHistory")
            ?: throw LearningRepositoryException("${subject.name}: 학습 내역 형식이 예상과 다릅니다.")
        val activities = contentsHistory.optJSONArray(subject.reportActivityArray)
            ?: throw LearningRepositoryException("${subject.name}: 학습 활동 목록 형식이 예상과 다릅니다.")

        SubjectReport(
            childName = subscription.childName,
            subjectName = subscription.subjectName,
            activityCount = activities.length(),
            learningSeconds = ReportParser.extractLearningSeconds(report),
        )
    }

    private fun postData(
        path: String,
        body: JSONObject,
        token: String?,
        isSignIn: Boolean = false,
        cancellation: LearningRequestCancellation? = null,
    ): Any? {
        val response = if (cancellation == null) transport.post(path, body, token)
        else transport.post(path, body, token, cancellation)
        if (response.statusCode !in 200..299) {
            if (response.statusCode == 401 || response.statusCode == 403) {
                throw LearningAuthenticationDeniedException("$path: HTTP ${response.statusCode}")
            }
            throw LearningRepositoryException("$path: HTTP ${response.statusCode}")
        }

        val envelope = try {
            JSONObject(response.body)
        } catch (exception: Exception) {
            throw LearningRepositoryException("$path: JSON 응답 형식이 예상과 다릅니다.", exception)
        }
        val result = envelope.opt("result") as? Boolean
            ?: throw LearningRepositoryException("$path: API result 표식 형식이 예상과 다릅니다.")
        if (!result) {
            if (isSignIn) throw LearningAuthenticationDeniedException("로그인에 실패했습니다.")
            throw LearningRepositoryException("$path: API 요청이 실패했습니다.")
        }
        if (!envelope.has("data") || envelope.isNull("data")) {
            throw LearningRepositoryException("$path: API 데이터가 없습니다.")
        }
        return envelope.opt("data")
    }

    private inline fun <T> capture(block: () -> T): LearningRepositoryResult<T> = try {
        LearningRepositoryResult.Success(block())
    } catch (exception: LearningAuthenticationDeniedException) {
        LearningRepositoryResult.AuthenticationDenied(exception)
    } catch (exception: Exception) {
        val failure = if (exception is LearningRepositoryException) {
            exception
        } else {
            LearningRepositoryException(exception.message ?: exception.javaClass.simpleName, exception)
        }
        LearningRepositoryResult.TemporaryFailure(failure)
    }
}

private class UrlConnectionLearningHttpTransport(
    private val apiBaseUrl: String = BuildConfig.API_BASE_URL,
) : LearningHttpTransport {
    override fun post(path: String, body: JSONObject, token: String?): LearningHttpResponse {
        return post(path, body, token, LearningRequestCancellation())
    }

    override fun post(
        path: String,
        body: JSONObject,
        token: String?,
        cancellation: LearningRequestCancellation,
    ): LearningHttpResponse {
        cancellation.throwIfCancelled()
        val connection = (URL(apiBaseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Content-Type", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        cancellation.attach(connection)

        try {
            cancellation.throwIfCancelled()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            cancellation.throwIfCancelled()
            return LearningHttpResponse(statusCode, responseBody)
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }
}
