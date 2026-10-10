package com.example.todoschooltime

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private var learningDate: LocalDate = DateHelper.today()
    private val requestTracker = AsyncRequestTracker()
    private val targetCountStore by lazy { TargetCountStore(this) }
    private val learningMonitor by lazy { (application as TodoSchoolApplication).learningMonitor }
    private val learningMonitorScreenUseCases by lazy { (application as TodoSchoolApplication).learningMonitorScreenUseCases }
    private var currentResult: LearningResult? = null
    private var monitorSnapshot: LearningMonitorSnapshot = LearningMonitorSnapshot()
    private var monitorSubscription: MonitorSubscription? = null

    private lateinit var rootLayout: LinearLayout
    private lateinit var topNavLayout: LinearLayout
    private lateinit var prevDateButton: TextView
    private lateinit var datePickerButton: TextView
    private lateinit var nextDateButton: TextView
    private lateinit var checkedAtView: TextView
    private lateinit var intervalButton: TextView
    private lateinit var monitorStatusView: TextView
    private lateinit var monitorSettingsButton: TextView
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
            loadLearningTime(savedCredentials, learningDate)
        }
    }

    override fun onResume() {
        super.onResume()
        updateDateNavUi()
        monitorSubscription?.close()
        monitorSubscription = learningMonitor.observe { snapshot ->
            if (isFinishing || isDestroyed || snapshot.revision < monitorSnapshot.revision) return@observe
            monitorSnapshot = snapshot
            updateMonitorControls()
            currentResult?.let(::renderChildren)
        }
        updateMonitorControls()
    }

    override fun onPause() {
        monitorSubscription?.close()
        monitorSubscription = null
        super.onPause()
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
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

        prevDateButton = createNavButton("<") {
            changeLearningDate(learningDate.minusDays(1))
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

        nextDateButton = createNavButton(">") {
            if (DateHelper.canGoNext(learningDate, DateHelper.today())) {
                changeLearningDate(learningDate.plusDays(1))
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
                loadLearningTimeForLearningDate()
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
        buildMonitorControls()
        rootLayout.addView(swipeRefreshLayout)

        setContentView(rootLayout)
    }

    private fun buildMonitorControls() {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        intervalButton = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(30, 30, 30))
            minimumHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(8), 0, dp(16), 0)
            setOnClickListener { showMonitorIntervalDialog() }
        }
        monitorSettingsButton = TextView(this).apply {
            text = "감시 설정"
            textSize = 14f
            setTextColor(Color.rgb(30, 30, 30))
            minimumHeight = dp(44)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                if (monitorSnapshot.authenticationRequired) {
                    askCredentials("로그인이 만료되어 아이별 감시를 일시 중지했습니다. 다시 로그인해주세요.")
                } else {
                    showMonitorSetupDialog()
                }
            }
        }
        buttons.addView(intervalButton)
        buttons.addView(monitorSettingsButton)
        monitorStatusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(110, 110, 110))
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        controls.addView(buttons)
        controls.addView(monitorStatusView)
        rootLayout.addView(controls)
    }

    private fun updateMonitorControls() {
        if (!::intervalButton.isInitialized) return
        intervalButton.text = "감시 간격 ${monitorSnapshot.intervalMinutes}분"
        val executionStatus = learningMonitorScreenUseCases.refreshExecutionConditions()
        monitorSettingsButton.text = if (monitorSnapshot.authenticationRequired) "다시 로그인" else "감시 설정"
        val accessibilityText = when {
            !executionStatus.accessibilityEnabled -> "접근성 설정 필요"
            monitorSnapshot.serviceLeaseAttached -> "접근성 연결됨"
            else -> "접근성 연결 대기"
        }
        val notificationText = if (executionStatus.notificationsEnabled) "알림 사용 가능" else "알림 권한 필요"
        val stateText = when {
            monitorSnapshot.authenticationRequired -> "다시 로그인 필요"
            monitorSnapshot.monitoringPaused -> "감시 일시 중지"
            monitorSnapshot.connectionWaiting -> "연결 대기 중"
            else -> null
        }
        monitorStatusView.text = listOfNotNull(accessibilityText, notificationText, stateText).joinToString(" · ")
    }

    private fun showMonitorSetupDialog() {
        val executionStatus = learningMonitorScreenUseCases.inspectExecutionStatus()
        val message = buildString {
            append("아이별 감시를 계속 실행하려면 접근성 서비스와 알림을 허용하세요.\n\n")
            append("접근성: ")
            append(if (executionStatus.accessibilityEnabled) "설정됨" else "설정 필요")
            append(if (executionStatus.accessibilityEnabled && monitorSnapshot.serviceLeaseAttached) " · 연결됨" else if (executionStatus.accessibilityEnabled) " · 연결 대기" else "")
            append("\n알림: ")
            append(if (executionStatus.notificationsEnabled) "사용 가능" else "설정 필요")
            if (monitorSnapshot.authenticationRequired) {
                append("\n감시 상태: 로그인 다시 필요")
            } else if (monitorSnapshot.monitoringPaused) {
                append("\n감시 상태: 권한을 허용하면 자동으로 다시 시작합니다.")
            } else if (monitorSnapshot.connectionWaiting) {
                append("\n감시 상태: 접근성 서비스 연결 대기 중")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("감시 설정")
            .setMessage(message)
            .setPositiveButton("접근성 설정") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("앱 알림 설정") { _, _ ->
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showMonitorIntervalDialog() {
        val accountId = currentResult?.accountId.orEmpty()
        if (accountId.isBlank()) {
            Toast.makeText(this, "로그인 후 감시 간격을 설정할 수 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(monitorSnapshot.intervalMinutes.toString())
            setSelection(text.length)
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("공통 감시 간격 (분)")
            .setMessage("1 이상의 정수를 입력하세요.")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val minutes = MonitorIntervalParser.parse(input.text.toString())
                if (minutes == null) {
                    Toast.makeText(this, "1 이상의 정수를 입력해주세요.", Toast.LENGTH_SHORT).show()
                } else {
                    learningMonitor.setIntervalMinutes(accountId, minutes)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun updateDateNavUi() {
        val today = DateHelper.today()
        datePickerButton.text = DateHelper.formatDisplayDate(learningDate)
        val canNext = DateHelper.canGoNext(learningDate, today)
        nextDateButton.visibility = if (canNext) View.VISIBLE else View.INVISIBLE
        nextDateButton.isEnabled = canNext
        swipeRefreshLayout.isEnabled = DateHelper.isToday(learningDate, today)
    }

    private fun createNavButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 22f
        setTextColor(Color.rgb(30, 30, 30))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        isClickable = true
        isFocusable = true
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

    private fun changeLearningDate(newDate: LocalDate) {
        learningDate = newDate
        updateDateNavUi()
        loadLearningTimeForLearningDate()
    }

    private fun setNavButtonsEnabled(enabled: Boolean) {
        prevDateButton.isEnabled = enabled
        prevDateButton.alpha = if (enabled) 1.0f else 0.3f

        datePickerButton.isEnabled = enabled
        datePickerButton.alpha = if (enabled) 1.0f else 0.3f

        val canNext = DateHelper.canGoNext(learningDate, DateHelper.today())
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
                if (pickedDate != learningDate) {
                    changeLearningDate(pickedDate)
                }
            },
            learningDate.year,
            learningDate.monthValue - 1,
            learningDate.dayOfMonth,
        )
        dialog.datePicker.maxDate = DateHelper.maxDateEpochMillis(today)
        dialog.setButton(DialogInterface.BUTTON_NEUTRAL, "오늘") { d, _ ->
            d.dismiss()
            changeLearningDate(DateHelper.today())
        }
        dialog.show()
    }

    private fun loadLearningTimeForLearningDate() {
        val savedCredentials = CredentialStore(this).load()
        if (savedCredentials == null) {
            if (swipeRefreshLayout.isRefreshing) {
                swipeRefreshLayout.isRefreshing = false
            }
            askCredentials()
        } else {
            loadLearningTime(savedCredentials, learningDate)
        }
    }

    private fun handleAuthCancelled() {
        checkedAtView.text = "확인 취소"
        progressBar.visibility = View.GONE
        swipeRefreshLayout.isRefreshing = false
        setNavButtonsEnabled(true)
    }

    private fun askCredentials(message: String? = null) {
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
                    handleAuthCancelled()
                } else {
                    val credentials = Credentials(email, password)
                    CredentialStore(this).save(credentials)
                    loadLearningTime(credentials, learningDate, humanReauthentication = true)
                }
            }
            .setNegativeButton("취소") { _, _ ->
                handleAuthCancelled()
            }
            .show()
    }

    private fun loadLearningTime(
        credentials: Credentials,
        targetDate: LocalDate,
        humanReauthentication: Boolean = false,
    ) {
        val requestId = requestTracker.nextRequestId()

        checkedAtView.text = "확인 중..."
        setNavButtonsEnabled(false)
        progressBar.visibility = if (swipeRefreshLayout.isRefreshing) View.GONE else View.VISIBLE
        childrenContainer.removeAllViews()
        errorView.visibility = View.GONE
        errorView.text = ""

        Thread {
            try {
                val result = LearningReportRepository()
                    .load(credentials.email, credentials.password, targetDate)
                    .getOrThrow()
                runOnUiThread {
                    if (!requestTracker.isLatest(requestId) || isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    currentResult = result
                    if (result.accountId.isNotBlank()) {
                        if (humanReauthentication) {
                            learningMonitorScreenUseCases.credentialsReauthenticated(
                                result.accountId,
                                credentials.email,
                                credentials.password,
                            )
                        } else {
                            learningMonitorScreenUseCases.credentialsAccepted(
                                result.accountId,
                                credentials.email,
                                credentials.password,
                            )
                        }
                    }
                    setNavButtonsEnabled(true)
                    progressBar.visibility = View.GONE
                    swipeRefreshLayout.isRefreshing = false
                    checkedAtView.text = result.checkedAt
                    renderChildren(result)
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

                    if (e is LearningAuthenticationDeniedException) {
                        CredentialStore(this).clear()
                        askCredentials("로그인에 실패했습니다. 이메일과 비밀번호를 다시 입력하세요.")
                    }
                }
            }
        }.start()
    }

    private fun renderChildren(result: LearningResult) {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)

        childrenContainer.removeAllViews()
        result.children.forEachIndexed { index, child ->
            val cardLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                if (index > 0) {
                    setPadding(0, dp(24), 0, 0)
                }
            }

            // 1행: 아이 이름 + 아이별 감시 + 목표 설정
            val headerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            val nameView = TextView(this).apply {
                text = child.name
                textSize = 22f
                setTextColor(Color.rgb(17, 17, 17))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(190)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            val registered = currentResult?.accountId == monitorSnapshot.accountId &&
                child.name in monitorSnapshot.registeredChildren
            val monitoringButton = TextView(this).apply {
                text = if (registered) "🔔" else "🔕"
                textSize = 16f
                contentDescription = if (registered) "${child.name} 감시 해제" else "${child.name} 감시 등록"
                if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
                gravity = Gravity.CENTER
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val accountId = currentResult?.accountId.orEmpty()
                    if (accountId.isBlank()) {
                        Toast.makeText(this@MainActivity, "로그인 후 감시를 등록할 수 있습니다.", Toast.LENGTH_SHORT).show()
                    } else if (registered) {
                        learningMonitor.unregisterChild(accountId, child.name)
                    } else {
                        learningMonitor.registerChild(accountId, child.name)
                    }
                }
            }

            val settingsButton = TextView(this).apply {
                text = "⚙️"
                textSize = 16f
                contentDescription = "${child.name} 목표 설정"
                if (outValue.resourceId != 0) {
                    setBackgroundResource(outValue.resourceId)
                }
                gravity = Gravity.CENTER
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(4)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    showTargetCountDialog(child)
                }
            }

            headerLayout.addView(nameView)
            headerLayout.addView(monitoringButton)
            headerLayout.addView(settingsButton)

            // 2행: ${minutes}분
            val minutesView = TextView(this).apply {
                text = "${child.minutes}분"
                textSize = 26f
                setTextColor(Color.rgb(17, 17, 17))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
            }

            // 3행: 과목 현황 (아이별 1회 목표 개수 조회로 파싱 오버헤드 최적화)
            val childTargets = targetCountStore.getTargetCounts(child.name)
            val statusText = SubjectStatusFormatter.formatStatus(child.subjects) { subjectName ->
                childTargets[subjectName] ?: SubjectStatusFormatter.defaultTargetCount(subjectName)
            }
            val statusView = TextView(this).apply {
                text = colorizeCheckmarks(statusText)
                textSize = 15f
                setTextColor(Color.rgb(80, 80, 80))
                setPadding(0, dp(4), 0, 0)
            }

            cardLayout.addView(headerLayout)
            cardLayout.addView(minutesView)
            if (statusText.isNotEmpty()) {
                cardLayout.addView(statusView)
            }

            childrenContainer.addView(cardLayout)
        }
    }

    private fun colorizeCheckmarks(text: String): CharSequence {
        val checkmark = SubjectStatusFormatter.CHECKMARK
        if (!text.contains(checkmark)) return text
        val spannable = SpannableStringBuilder(text)
        val greenColor = Color.rgb(46, 125, 50)
        var index = text.indexOf(checkmark)
        while (index >= 0) {
            spannable.setSpan(
                ForegroundColorSpan(greenColor),
                index,
                index + checkmark.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                index,
                index + checkmark.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            index = text.indexOf(checkmark, index + checkmark.length)
        }
        return spannable
    }

    private fun showTargetCountDialog(child: ChildLearningInfo) {
        val savedCounts = targetCountStore.getTargetCounts(child.name)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val inputFields = mutableMapOf<String, EditText>()

        if (child.subjects.isEmpty()) {
            val emptyNotice = TextView(this).apply {
                text = "구독 중인 과목이 없습니다."
                textSize = 15f
                setTextColor(Color.rgb(100, 100, 100))
                setPadding(0, dp(8), 0, dp(8))
            }
            container.addView(emptyNotice)
        } else {
            child.subjects.forEachIndexed { index, subject ->
                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }

                val labelView = TextView(this).apply {
                    text = subject.name
                    textSize = 16f
                    setTextColor(Color.rgb(30, 30, 30))
                    layoutParams = LinearLayout.LayoutParams(
                        dp(56),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }

                val defaultTarget = SubjectStatusFormatter.defaultTargetCount(subject.name)
                val savedTarget = savedCounts[subject.name]

                val isLast = index == child.subjects.size - 1
                val editInput = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    filters = arrayOf(InputFilter.LengthFilter(2))
                    imeOptions = if (isLast) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                    hint = defaultTarget.toString()
                    if (savedTarget != null) {
                        setText(savedTarget.toString())
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                }

                inputFields[subject.name] = editInput
                rowLayout.addView(labelView)
                rowLayout.addView(editInput)
                container.addView(rowLayout)
            }
        }

        val dialogScrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(container)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("${child.name} 목표 설정")
            .setView(dialogScrollView)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newTargets = mutableMapOf<String, Int>()
            for ((subjectName, editText) in inputFields) {
                val text = editText.text.toString()
                val parsed = TargetCountValidator.parse(text)
                if (parsed == null) {
                    Toast.makeText(this, "0부터 99까지의 숫자를 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                newTargets[subjectName] = parsed
            }

            targetCountStore.saveTargetCounts(child.name, newTargets)
            currentResult?.let { renderChildren(it) }
            dialog.dismiss()
        }
    }
}
