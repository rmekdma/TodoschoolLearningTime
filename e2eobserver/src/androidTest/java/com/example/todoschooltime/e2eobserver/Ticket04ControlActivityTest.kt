package com.example.todoschooltime.e2eobserver

import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ticket04ControlActivityTest {
    @Test
    fun notificationOffAcknowledgementNamesObserverAndDoesNotSubmitWithoutTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = "control-screen-preview-token"
        val staleToken = "previous-run-notification-revocation-confirm-off"
        val instructions = "알림 권한 OFF 상태와 요청 경계 증거를 확인하세요. 서버 응답은 이 버튼 전까지 보류됩니다."
        val preferences = context.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(Ticket04ControlActivity.KEY_ACTIVE_TOKEN, token)
            .putString(Ticket04ControlActivity.KEY_CLICKED_TOKEN, staleToken)
            .putLong(Ticket04ControlActivity.KEY_CLICKED_AT, 123L)
            .commit()
        assertTrue("The test should seed a prior run token", preferences)
        val intent = Intent(context, Ticket04ControlActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Ticket04ControlActivity.EXTRA_CASE_ID, "notification-revocation-confirm-off")
            .putExtra(Ticket04ControlActivity.EXTRA_TOKEN, token)
            .putExtra(Ticket04ControlActivity.EXTRA_INSTRUCTIONS, instructions)
            .putExtra(Ticket04ControlActivity.EXTRA_BUTTON_LABEL, "권한 OFF 확인 · 계속")
        val activity = instrumentation.startActivitySync(intent) as Ticket04ControlActivity

        try {
            instrumentation.runOnMainSync {
                val container = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                assertEquals("The observer screen should show its name, instructions, and one explicit action", 3, container.childCount)
                assertEquals("학습 감시 E2E 검사", (container.getChildAt(0) as TextView).text)
                assertEquals(instructions, (container.getChildAt(1) as TextView).text)
                assertEquals("권한 OFF 확인 · 계속", (container.getChildAt(2) as Button).text)
                val savedToken = activity.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
                    .getString(Ticket04ControlActivity.KEY_CLICKED_TOKEN, null)
                assertEquals("Showing the current control screen must not replace the prior user's action", staleToken, savedToken)
                assertTrue("A previous run/action token must not acknowledge this screen", !Ticket04ActionToken.matches(token, savedToken))
                assertTrue("The control page must be visible to the human", activity.window.decorView.isShown)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            context.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .remove(Ticket04ControlActivity.KEY_ACTIVE_TOKEN)
                .remove(Ticket04ControlActivity.KEY_CLICKED_TOKEN)
                .remove(Ticket04ControlActivity.KEY_CLICKED_AT)
                .commit()
        }
    }

    @Test
    fun anExpiredPreparationDisablesItsActionInsteadOfAcknowledgingAnOldRun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = "expired-control-screen-token"
        val preferences = context.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit()
            .putString(Ticket04ControlActivity.KEY_ACTIVE_TOKEN, token)
            .remove(Ticket04ControlActivity.KEY_CLICKED_TOKEN)
            .remove(Ticket04ControlActivity.KEY_CLICKED_AT)
            .commit()
        val intent = Intent(context, Ticket04ControlActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Ticket04ControlActivity.EXTRA_CASE_ID, "accessibility-revocation")
            .putExtra(Ticket04ControlActivity.EXTRA_TOKEN, token)
            .putExtra(Ticket04ControlActivity.EXTRA_INSTRUCTIONS, "준비된 권한 설정을 진행하세요.")
            .putExtra(Ticket04ControlActivity.EXTRA_BUTTON_LABEL, "시험 시작")
        val activity = instrumentation.startActivitySync(intent) as Ticket04ControlActivity

        try {
            instrumentation.runOnMainSync {
                val container = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                assertTrue("A current preparation should expose its action", (container.getChildAt(2) as Button).isEnabled)
            }
            preferences.edit().putString(Ticket04ControlActivity.KEY_ACTIVE_TOKEN, "newer-run-token").commit()
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val container = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                assertEquals("A stale preparation must explain that it ended", Ticket04ControlActivity.EXPIRED_INSTRUCTIONS, (container.getChildAt(1) as TextView).text)
                assertEquals("The stale action should be visibly disabled", Ticket04ControlActivity.EXPIRED_BUTTON_LABEL, (container.getChildAt(2) as Button).text)
                assertFalse("An expired screen must not acknowledge its old token", (container.getChildAt(2) as Button).isEnabled)
                (container.getChildAt(2) as Button).performClick()
            }
            assertNull("A stale button press must not acknowledge the expired token", preferences.getString(Ticket04ControlActivity.KEY_CLICKED_TOKEN, null))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            preferences.edit().remove(Ticket04ControlActivity.KEY_ACTIVE_TOKEN).remove(Ticket04ControlActivity.KEY_CLICKED_TOKEN)
                .remove(Ticket04ControlActivity.KEY_CLICKED_AT).commit()
        }
    }
}
