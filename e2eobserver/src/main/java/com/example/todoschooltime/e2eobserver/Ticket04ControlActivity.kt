package com.example.todoschooltime.e2eobserver

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** User-controlled gate that starts a Ticket 04 request only after the human is ready. */
class Ticket04ControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val caseId = intent.getStringExtra(EXTRA_CASE_ID)
            ?: throw IllegalArgumentException("Missing Ticket 04 case id")
        val token = intent.getStringExtra(EXTRA_TOKEN)
            ?: throw IllegalArgumentException("Missing Ticket 04 start token")
        val instructions = intent.getStringExtra(EXTRA_INSTRUCTIONS).orEmpty()
        val buttonLabel = intent.getStringExtra(EXTRA_BUTTON_LABEL) ?: "계속"

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@Ticket04ControlActivity).apply {
                text = "학습 감시 E2E 검사"
                textSize = 18f
            })
            addView(TextView(this@Ticket04ControlActivity).apply {
                text = instructions
                textSize = 20f
            })
            addView(Button(this@Ticket04ControlActivity).apply {
                text = buttonLabel
                setOnClickListener {
                    getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_CLICKED_TOKEN, token)
                        .putLong(KEY_CLICKED_AT, SystemClock.elapsedRealtime())
                        .commit()
                    finish()
                }
            })
        }
        setContentView(content)
    }

    companion object {
        const val EXTRA_CASE_ID = "ticket04.caseId"
        const val EXTRA_TOKEN = "ticket04.token"
        const val EXTRA_INSTRUCTIONS = "ticket04.instructions"
        const val EXTRA_BUTTON_LABEL = "ticket04.buttonLabel"
        const val PREFERENCES_NAME = "ticket04-control"
        const val KEY_CLICKED_TOKEN = "clickedToken"
        const val KEY_CLICKED_AT = "clickedAtElapsedRealtimeMs"
    }
}
