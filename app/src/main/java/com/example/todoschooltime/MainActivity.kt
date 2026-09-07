package com.example.todoschooltime

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private var selectedDate: LocalDate = DateHelper.today()
    private val requestTracker = AsyncRequestTracker()

    private lateinit var rootLayout: LinearLayout
    private lateinit var topNavLayout: LinearLayout
    private lateinit var prevDateButton: TextView
    private lateinit var datePickerButton: TextView
    private lateinit var nextDateButton: TextView
    private lateinit var checkedAtView: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var childrenContainer: LinearLayout
    private lateinit var errorView: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupSystemBars()
        updateDateNavUi()

        val savedCredentials = CredentialStore(this).load()
        if (savedCredentials == null) {
            askCredentials()
        } else {
            loadLearningTime(savedCredentials, selectedDate)
        }
    }

    override fun onResume() {
        super.onResume()
        updateDateNavUi()
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        topNavLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        prevDateButton = TextView(this).apply {
            text = "<"
            textSize = 22f
            setTextColor(Color.rgb(30, 30, 30))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                selectedDate = selectedDate.minusDays(1)
                updateDateNavUi()
                loadLearningTimeForSelectedDate()
            }
        }

        datePickerButton = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(17, 17, 17))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                showDatePicker()
            }
        }

        nextDateButton = TextView(this).apply {
            text = ">"
            textSize = 22f
            setTextColor(Color.rgb(30, 30, 30))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                if (DateHelper.canGoNext(selectedDate, DateHelper.today())) {
                    selectedDate = selectedDate.plusDays(1)
                    updateDateNavUi()
                    loadLearningTimeForSelectedDate()
                }
            }
        }

        topNavLayout.addView(prevDateButton)
        topNavLayout.addView(datePickerButton)
        topNavLayout.addView(nextDateButton)

        checkedAtView = TextView(this).apply {
            text = "확인 중..."
            textSize = 13f
            setTextColor(Color.rgb(140, 140, 140))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }

        swipeRefreshLayout = SwipeRefreshLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setOnRefreshListener {
                loadLearningTimeForSelectedDate()
            }
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.START
                bottomMargin = dp(8)
            }
        }

        childrenContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        errorView = TextView(this).apply {
            visibility = View.GONE
            textSize = 15f
            setTextColor(Color.rgb(176, 0, 32))
            setPadding(0, dp(16), 0, 0)
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom,
            )
            insets
        }

        contentLayout.addView(progressBar)
        contentLayout.addView(childrenContainer)
        contentLayout.addView(errorView)

        scrollView.addView(contentLayout)
        swipeRefreshLayout.addView(scrollView)

        rootLayout.addView(topNavLayout)
        rootLayout.addView(checkedAtView)
        rootLayout.addView(swipeRefreshLayout)

        setContentView(rootLayout)
    }

    private fun updateDateNavUi() {
        val today = DateHelper.today()
        datePickerButton.text = DateHelper.formatDisplayDate(selectedDate)
        val canNext = DateHelper.canGoNext(selectedDate, today)
        nextDateButton.visibility = if (canNext) View.VISIBLE else View.INVISIBLE
        nextDateButton.isEnabled = canNext
        swipeRefreshLayout.isEnabled = DateHelper.isToday(selectedDate, today)
    }

    private fun setNavButtonsEnabled(enabled: Boolean) {
        prevDateButton.isEnabled = enabled
        prevDateButton.alpha = if (enabled) 1.0f else 0.3f

        datePickerButton.isEnabled = enabled
        datePickerButton.alpha = if (enabled) 1.0f else 0.3f

        val canNext = DateHelper.canGoNext(selectedDate, DateHelper.today())
        nextDateButton.isEnabled = enabled && canNext
        nextDateButton.alpha = if (enabled && canNext) 1.0f else 0.3f
    }

    private fun showDatePicker() {
        val today = DateHelper.today()
        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val rawPickedDate = LocalDate.of(year, month + 1, dayOfMonth)
                val pickedDate = if (rawPickedDate.isAfter(today)) today else rawPickedDate
                if (pickedDate != selectedDate) {
                    selectedDate = pickedDate
                    updateDateNavUi()
                    loadLearningTimeForSelectedDate()
                }
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth,
        )
        dialog.datePicker.maxDate = DateHelper.maxDateEpochMillis(today)
        dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "오늘") { _, _ ->
            selectedDate = DateHelper.today()
            updateDateNavUi()
            loadLearningTimeForSelectedDate()
        }
        dialog.show()
    }

    private fun loadLearningTimeForSelectedDate() {
        val savedCredentials = CredentialStore(this).load()
        if (savedCredentials == null) {
            if (swipeRefreshLayout.isRefreshing) {
                swipeRefreshLayout.isRefreshing = false
            }
            askCredentials()
        } else {
            loadLearningTime(savedCredentials, selectedDate)
        }
    }

    private fun askCredentials(message: String? = null) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(0))
        }

        val emailInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            hint = "토도스쿨 이메일"
            setSingleLine(true)
        }

        val passwordInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "토도스쿨 비밀번호"
            setSingleLine(true)
        }

        container.addView(emailInput)
        container.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle("토도스쿨 로그인")
            .setMessage(message ?: "이메일과 비밀번호를 한 번 입력하면 이 기기에 암호화해 저장합니다.")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("확인") { _, _ ->
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (email.isBlank() || password.isBlank()) {
                    checkedAtView.text = "확인 취소"
                    progressBar.visibility = View.GONE
                    swipeRefreshLayout.isRefreshing = false
                    setNavButtonsEnabled(true)
                } else {
                    val credentials = Credentials(email, password)
                    CredentialStore(this).save(credentials)
                    loadLearningTime(credentials, selectedDate)
                }
            }
            .setNegativeButton("취소") { _, _ ->
                checkedAtView.text = "확인 취소"
                progressBar.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
                setNavButtonsEnabled(true)
            }
            .show()
    }

    private fun loadLearningTime(credentials: Credentials, targetDate: LocalDate) {
        val requestId = requestTracker.nextRequestId()

        checkedAtView.text = "확인 중..."
        setNavButtonsEnabled(false)
        progressBar.visibility = if (swipeRefreshLayout.isRefreshing) View.GONE else View.VISIBLE
        childrenContainer.removeAllViews()
        errorView.visibility = View.GONE
        errorView.text = ""

        Thread {
            try {
                val result = TodoSchoolClient().load(credentials.email, credentials.password, targetDate)
                runOnUiThread {
                    if (!requestTracker.isLatest(requestId) || isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    setNavButtonsEnabled(true)
                    progressBar.visibility = View.GONE
                    swipeRefreshLayout.isRefreshing = false
                    checkedAtView.text = result.checkedAt
                    childrenContainer.removeAllViews()

                    result.minutesByChild.forEach { (name, minutes) ->
                        childrenContainer.addView(TextView(this).apply {
                            text = "$name: ${minutes}분"
                            textSize = 26f
                            setTextColor(Color.rgb(17, 17, 17))
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        })
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!requestTracker.isLatest(requestId) || isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    setNavButtonsEnabled(true)
                    progressBar.visibility = View.GONE
                    swipeRefreshLayout.isRefreshing = false
                    childrenContainer.removeAllViews()
                    checkedAtView.text = "확인 실패"
                    errorView.visibility = View.VISIBLE
                    errorView.text = "학습시간을 불러오지 못했습니다.\n${e.message ?: e.javaClass.simpleName}"

                    if (e is AuthenticationException) {
                        CredentialStore(this).clear()
                        askCredentials("로그인에 실패했습니다. 이메일과 비밀번호를 다시 입력하세요.")
                    }
                }
            }
        }.start()
    }
}

private data class Credentials(
    val email: String,
    val password: String,
)

private data class LearningResult(
    val checkedAt: String,
    val minutesByChild: LinkedHashMap<String, Int>,
)

private class TodoSchoolClient {
    private data class Subject(
        val name: String,
        val usersPath: String,
        val reportPath: String,
    )

    private val subjects = listOf(
        Subject("한글", "/v3/todohangeul/user", "/v3/todoschool/report/daily/hangeul"),
        Subject("영어", "/v3/english/user", "/v3/todoschool/report/daily/english"),
        Subject("수학", "/v3/math/user", "/v3/todoschool/report/daily/math"),
    )

    fun load(email: String, password: String, date: LocalDate = DateHelper.today()): LearningResult {
        val login = signIn(email, password)
        val token = login.optString("authToken").ifBlank { login.optString("sessionId") }
        if (token.isBlank()) throw AuthenticationException("로그인 토큰을 받지 못했습니다.")

        val accountId = login.opt("accountId")
            ?: throw IllegalStateException("accountId를 받지 못했습니다.")
        val yyyymmdd = DateHelper.toYyyyMmDd(date)
        val checkedAt = DateHelper.formatCheckedAt(DateHelper.nowZoned())

        val totals = LinkedHashMap<String, Long>()
        val childOrder = mutableListOf<String>()

        subjects.forEach { subject ->
            val users = post(
                path = subject.usersPath,
                body = JSONObject().put("user", JSONObject().put("accountId", accountId)),
                token = token,
            ) as? JSONArray ?: throw IllegalStateException("${subject.name}: 사용자 목록 형식이 예상과 다릅니다.")

            if (childOrder.isEmpty()) {
                for (i in 0 until users.length()) {
                    val name = users.getJSONObject(i).optString("name")
                    if (name.isNotBlank()) childOrder.add(name)
                }
            }

            for (i in 0 until users.length()) {
                val user = users.getJSONObject(i)
                val userId = user.opt("userId")
                    ?: throw IllegalStateException("${subject.name}: userId가 없습니다.")
                val userName = user.optString("name")

                val report = post(
                    path = subject.reportPath,
                    body = JSONObject()
                        .put("userId", userId)
                        .put("yyyymmdd", yyyymmdd)
                        .put("languageCode", "ko"),
                    token = token,
                ) as? JSONObject ?: continue

                val name = report.optString("name").ifBlank { userName }
                val seconds = learningSecondsWithoutEtc(report)
                totals[name] = (totals[name] ?: 0L) + seconds
                if (name.isNotBlank() && name !in childOrder) childOrder.add(name)
            }
        }

        val names = childOrder.distinct().filter { it.isNotBlank() }
        if (names.isEmpty()) throw IllegalStateException("아이 정보를 찾지 못했습니다.")

        val minutes = LinkedHashMap<String, Int>()
        names.forEach { name ->
            minutes[name] = ceil((totals[name] ?: 0L) / 60.0).toInt()
        }

        return LearningResult(checkedAt, minutes)
    }

    private fun signIn(email: String, password: String): JSONObject {
        val body = JSONObject()
            .put("accountType", "personal")
            .put("getSessionId", true)
            .put("product", "todoschool")
            .put("packageName", "com.enuma.todoschool")
            .put("fromWeb", true)
            .put("countryCode", "ko")
            .put("email", email)
            .put("password", password)

        try {
            return post("/v3/account/signin", body, null) as? JSONObject
                ?: throw AuthenticationException("로그인 응답 형식이 예상과 다릅니다.")
        } catch (e: HttpException) {
            if (e.statusCode == 401 || e.statusCode == 403) {
                throw AuthenticationException("이메일 또는 비밀번호가 맞지 않습니다.")
            }
            throw e
        }
    }

    private fun learningSecondsWithoutEtc(report: JSONObject): Long {
        val categories = report.optJSONObject("learningSecondsByCategory") ?: return 0L
        var total = 0L
        val keys = categories.keys()
        while (keys.hasNext()) {
            val category = keys.next()
            if (category == "etc") continue
            total += when (val value = categories.opt(category)) {
                is Number -> value.toLong()
                is String -> value.toDoubleOrNull()?.toLong() ?: 0L
                else -> 0L
            }
        }
        return total
    }

    private fun post(path: String, body: JSONObject, token: String?): Any? {
        val connection = (URL(API_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Content-Type", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) throw HttpException(code, "$path: HTTP $code")

            val json = JSONObject(responseText)
            if (!json.optBoolean("result", false)) {
                if (path == "/v3/account/signin") throw AuthenticationException("로그인에 실패했습니다.")
                return null
            }
            return json.opt("data")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val API_BASE = "https://api.todomath.com"
    }
}

private class HttpException(
    val statusCode: Int,
    message: String,
) : Exception(message)

private class AuthenticationException(message: String) : Exception(message)

private class CredentialStore(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)

    fun save(credentials: Credentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val json = JSONObject().apply {
            put("email", credentials.email)
            put("password", credentials.password)
        }.toString()
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_IV, android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
            .apply()
    }

    fun load(): Credentials? {
        val ivText = prefs.getString(KEY_IV, null) ?: return null
        val encryptedText = prefs.getString(KEY_CIPHERTEXT, null) ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = android.util.Base64.decode(ivText, android.util.Base64.NO_WRAP)
            val encrypted = android.util.Base64.decode(encryptedText, android.util.Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val jsonString = String(cipher.doFinal(encrypted), Charsets.UTF_8)
            val json = JSONObject(jsonString)
            val email = json.optString("email")
            val password = json.optString("password")
            if (email.isNotBlank() && password.isNotBlank()) {
                Credentials(email, password)
            } else {
                null
            }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "credentials"
        private const val KEY_ALIAS = "todoschool_learning_time_credentials"
        private const val KEY_IV = "credentials_iv"
        private const val KEY_CIPHERTEXT = "credentials_ciphertext"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
