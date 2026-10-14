package com.example.todoschooltime.e2eobserver

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** User-controlled gate that starts a Ticket 04 request only after the human is ready. */
class PermissionControlActivity : Activity() {
    private lateinit var actionToken: String
    private lateinit var originalInstructions: String
    private lateinit var originalButtonLabel: String
    private lateinit var preferences: SharedPreferences
    private lateinit var instructionsView: TextView
    private lateinit var actionButton: Button
    private var keepVisibleAfterClick = false
    private var pendingInstructions = ""
    private var requestPreparationStarted = false

    private val activeTokenListener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
        if (key == KEY_ACTIVE_TOKEN) {
            runOnUiThread { renderActionState(changed) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        actionToken = preferences.getString(KEY_ACTIVE_TOKEN, null).orEmpty()
        originalInstructions = preferences.getString(KEY_ACTIVE_INSTRUCTIONS, "").orEmpty()
        originalButtonLabel = preferences.getString(KEY_ACTIVE_BUTTON_LABEL, EXPIRED_BUTTON_LABEL).orEmpty()
        keepVisibleAfterClick = preferences.getBoolean(KEY_ACTIVE_KEEP_VISIBLE_AFTER_CLICK, false)
        pendingInstructions = preferences.getString(KEY_ACTIVE_PENDING_INSTRUCTIONS, "").orEmpty()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@PermissionControlActivity).apply {
                text = "학습 감시 E2E 검사"
                textSize = 18f
            })
            instructionsView = TextView(this@PermissionControlActivity).apply {
                text = originalInstructions
                textSize = 20f
            }
            addView(instructionsView)
            actionButton = Button(this@PermissionControlActivity).apply {
                text = originalButtonLabel
                setOnClickListener {
                    if (!isActionCurrent()) {
                        showExpiredAction()
                        return@setOnClickListener
                    }
                    preferences.edit()
                        .putString(KEY_CLICKED_TOKEN, actionToken)
                        .putLong(KEY_CLICKED_AT, SystemClock.elapsedRealtime())
                        .commit()
                    if (keepVisibleAfterClick) {
                        requestPreparationStarted = true
                        showRequestPreparing()
                    } else {
                        finish()
                    }
                }
            }
            addView(actionButton)
        }
        setContentView(content)
        preferences.registerOnSharedPreferenceChangeListener(activeTokenListener)
        renderActionState(preferences)
    }

    override fun onDestroy() {
        if (::preferences.isInitialized) {
            preferences.unregisterOnSharedPreferenceChangeListener(activeTokenListener)
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!::preferences.isInitialized || !::actionButton.isInitialized) return
        if (hasWindowFocus) {
            window.decorView.post { recordVisibleActionIfReady() }
        } else if (preferences.getString(KEY_DISPLAYED_TOKEN, null) == actionToken) {
            preferences.edit()
                .remove(KEY_DISPLAYED_TOKEN)
                .remove(KEY_DISPLAYED_BUTTON_LABEL)
                .remove(KEY_DISPLAYED_AT)
                .commit()
        }
    }

    private fun isActionCurrent(): Boolean =
        ManualActionToken.matches(actionToken, preferences.getString(KEY_ACTIVE_TOKEN, null)) &&
            !ManualActionToken.matches(actionToken, preferences.getString(KEY_CLICKED_TOKEN, null))

    private fun renderActionState(changed: SharedPreferences) {
        if (!ManualActionToken.matches(actionToken, changed.getString(KEY_ACTIVE_TOKEN, null)) ||
            ManualActionToken.matches(actionToken, changed.getString(KEY_CLICKED_TOKEN, null))
        ) {
            showExpiredAction()
        } else if (requestPreparationStarted) {
            showRequestPreparing()
        } else {
            actionButton.isEnabled = true
            actionButton.text = originalButtonLabel
            instructionsView.text = originalInstructions
        }
    }

    private fun showRequestPreparing() {
        actionButton.isEnabled = false
        actionButton.text = REQUEST_PREPARING_BUTTON_LABEL
        instructionsView.text = pendingInstructions.ifBlank { REQUEST_PREPARING_INSTRUCTIONS }
    }

    private fun showExpiredAction() {
        actionButton.isEnabled = false
        actionButton.text = EXPIRED_BUTTON_LABEL
        instructionsView.text = EXPIRED_INSTRUCTIONS
    }

    private fun recordVisibleActionIfReady() {
        val visible = !isFinishing && !isDestroyed && isActionCurrent() &&
            window.decorView.isShown && window.decorView.hasWindowFocus() &&
            actionButton.isShown && actionButton.isEnabled && actionButton.text.toString() == originalButtonLabel
        if (!visible) return
        preferences.edit()
            .putString(KEY_DISPLAYED_TOKEN, actionToken)
            .putString(KEY_DISPLAYED_BUTTON_LABEL, originalButtonLabel)
            .putLong(KEY_DISPLAYED_AT, SystemClock.elapsedRealtime())
            .commit()
    }

    companion object {
        const val EXTRA_CASE_ID = "ticket04.caseId"
        const val EXTRA_TOKEN = "ticket04.token"
        const val EXTRA_INSTRUCTIONS = "ticket04.instructions"
        const val EXTRA_BUTTON_LABEL = "ticket04.buttonLabel"
        const val EXTRA_KEEP_VISIBLE_AFTER_CLICK = "ticket04.keepVisibleAfterClick"
        const val EXTRA_PENDING_INSTRUCTIONS = "ticket04.pendingInstructions"
        const val PREFERENCES_NAME = "ticket04-control"
        const val KEY_ACTIVE_TOKEN = "activeToken"
        const val KEY_ACTIVE_INSTRUCTIONS = "activeInstructions"
        const val KEY_ACTIVE_BUTTON_LABEL = "activeButtonLabel"
        const val KEY_ACTIVE_KEEP_VISIBLE_AFTER_CLICK = "activeKeepVisibleAfterClick"
        const val KEY_ACTIVE_PENDING_INSTRUCTIONS = "activePendingInstructions"
        const val KEY_CLICKED_TOKEN = "clickedToken"
        const val KEY_CLICKED_AT = "clickedAtElapsedRealtimeMs"
        const val KEY_DISPLAYED_TOKEN = "displayedToken"
        const val KEY_DISPLAYED_BUTTON_LABEL = "displayedButtonLabel"
        const val KEY_DISPLAYED_AT = "displayedAtElapsedRealtimeMs"
        const val REQUEST_PREPARING_BUTTON_LABEL = "조회 준비 중…"
        const val REQUEST_PREPARING_INSTRUCTIONS = "실제 보고 요청을 확인 중입니다. 자동으로 열린 권한 설정 화면이 나올 때까지 기다려 주세요."
        const val EXPIRED_BUTTON_LABEL = "시험 종료 · 다시 준비 필요"
        const val EXPIRED_INSTRUCTIONS = "이 준비 화면은 종료됐습니다. 새 시험 준비를 요청하세요."

        fun launchVisibleUserAction(
            instrumentation: Instrumentation,
            expectedToken: String,
            expectedButtonLabel: String,
        ) {
            val preferences = instrumentation.targetContext.getSharedPreferences(PREFERENCES_NAME, Activity.MODE_PRIVATE)
            check(preferences.getString(KEY_ACTIVE_TOKEN, null) == expectedToken) { "The current action token is not stored in observer state" }
            check(preferences.getString(KEY_ACTIVE_BUTTON_LABEL, null) == expectedButtonLabel) { "The current action label is not stored in observer state" }
            preferences.edit()
                .remove(KEY_DISPLAYED_TOKEN)
                .remove(KEY_DISPLAYED_BUTTON_LABEL)
                .remove(KEY_DISPLAYED_AT)
                .commit()
            val launchRequestedAt = SystemClock.elapsedRealtime()
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, PermissionControlActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            val deadline = SystemClock.elapsedRealtime() + USER_ACTION_SCREEN_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (preferences.getString(KEY_DISPLAYED_TOKEN, null) == expectedToken &&
                    preferences.getString(KEY_DISPLAYED_BUTTON_LABEL, null) == expectedButtonLabel &&
                    preferences.getLong(KEY_DISPLAYED_AT, 0L) >= launchRequestedAt
                ) return
                SystemClock.sleep(USER_ACTION_SCREEN_POLL_MS)
            }
            throw AssertionError("Ticket 04 action screen did not display the current token and enabled button in the foreground")
        }
    }
}

private const val USER_ACTION_SCREEN_TIMEOUT_MS = 5_000L
private const val USER_ACTION_SCREEN_POLL_MS = 50L
