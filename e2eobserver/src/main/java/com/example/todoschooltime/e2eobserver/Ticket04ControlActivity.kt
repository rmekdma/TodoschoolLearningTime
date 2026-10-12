package com.example.todoschooltime.e2eobserver

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** User-controlled gate that starts a Ticket 04 request only after the human is ready. */
class Ticket04ControlActivity : Activity() {
    private lateinit var actionToken: String
    private lateinit var originalInstructions: String
    private lateinit var originalButtonLabel: String
    private lateinit var preferences: SharedPreferences
    private lateinit var instructionsView: TextView
    private lateinit var actionButton: Button

    private val activeTokenListener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
        if (key == KEY_ACTIVE_TOKEN) {
            runOnUiThread { renderActionState(changed) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val caseId = intent.getStringExtra(EXTRA_CASE_ID)
            ?: throw IllegalArgumentException("Missing Ticket 04 case id")
        actionToken = intent.getStringExtra(EXTRA_TOKEN)
            ?: throw IllegalArgumentException("Missing Ticket 04 start token")
        originalInstructions = intent.getStringExtra(EXTRA_INSTRUCTIONS).orEmpty()
        originalButtonLabel = intent.getStringExtra(EXTRA_BUTTON_LABEL) ?: "계속"
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@Ticket04ControlActivity).apply {
                text = "학습 감시 E2E 검사"
                textSize = 18f
            })
            instructionsView = TextView(this@Ticket04ControlActivity).apply {
                text = originalInstructions
                textSize = 20f
            }
            addView(instructionsView)
            actionButton = Button(this@Ticket04ControlActivity).apply {
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
                    finish()
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

    private fun isActionCurrent(): Boolean = Ticket04ActionToken.matches(
        expectedToken = actionToken,
        clickedToken = preferences.getString(KEY_ACTIVE_TOKEN, null),
    )

    private fun renderActionState(changed: SharedPreferences) {
        if (Ticket04ActionToken.matches(actionToken, changed.getString(KEY_ACTIVE_TOKEN, null))) {
            actionButton.isEnabled = true
            actionButton.text = originalButtonLabel
            instructionsView.text = originalInstructions
        } else {
            showExpiredAction()
        }
    }

    private fun showExpiredAction() {
        actionButton.isEnabled = false
        actionButton.text = EXPIRED_BUTTON_LABEL
        instructionsView.text = EXPIRED_INSTRUCTIONS
    }

    companion object {
        const val EXTRA_CASE_ID = "ticket04.caseId"
        const val EXTRA_TOKEN = "ticket04.token"
        const val EXTRA_INSTRUCTIONS = "ticket04.instructions"
        const val EXTRA_BUTTON_LABEL = "ticket04.buttonLabel"
        const val PREFERENCES_NAME = "ticket04-control"
        const val KEY_ACTIVE_TOKEN = "activeToken"
        const val KEY_CLICKED_TOKEN = "clickedToken"
        const val KEY_CLICKED_AT = "clickedAtElapsedRealtimeMs"
        const val EXPIRED_BUTTON_LABEL = "시험 종료 · 다시 준비 필요"
        const val EXPIRED_INSTRUCTIONS = "이 준비 화면은 종료됐습니다. 새 시험 준비를 요청하세요."
    }
}
