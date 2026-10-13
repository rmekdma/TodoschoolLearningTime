package com.example.todoschooltime.e2eobserver

import android.app.KeyguardManager
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ticket04ControlActivityTest {
    private var screenWakeLock: PowerManager.WakeLock? = null

    @Before
    fun keepTransitionDisplayAwake() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val powerManager = instrumentation.context.getSystemService(PowerManager::class.java)
            ?: throw AssertionError("The observer could not access PowerManager")
        val keyguardManager = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
            ?: throw AssertionError("The observer could not access KeyguardManager")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_WAKEUP)
        val wakeDeadline = SystemClock.elapsedRealtime() + CONTROL_UI_WAKE_LOCK_SETTLE_TIMEOUT_MS
        while (!powerManager.isInteractive && SystemClock.elapsedRealtime() < wakeDeadline) {
            SystemClock.sleep(CONTROL_UI_WAKE_LOCK_POLL_MS)
        }
        assertTrue("The device must already be awake before the observer UI test starts", powerManager.isInteractive)
        assertFalse("The device must already be unlocked before the observer UI test starts", keyguardManager.isKeyguardLocked)
        screenWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "${instrumentation.targetContext.packageName}:ticket04-control-ui",
        ).apply { setReferenceCounted(false) }
        screenWakeLock!!.acquire(CONTROL_UI_WAKE_LOCK_TIMEOUT_MS)
        SystemClock.sleep(CONTROL_UI_WAKE_LOCK_SETTLE_MS)
        assertTrue("The bounded observer wake lock must preserve the already-awake display", powerManager.isInteractive)
    }

    @After
    fun releaseTransitionDisplayWakeLock() {
        screenWakeLock?.let { if (it.isHeld) it.release() }
        screenWakeLock = null
    }

    @Test
    fun confirmationScreenReplacesTheStartScreenAfterSettingsReturnsToTheObserver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        val startToken = "settings-return-start-token"
        val acknowledgementToken = "settings-return-ack-token"
        setActiveAction(preferences, startToken, "시작 안내", "시험 시작", keepVisibleAfterClick = true)
        val startIntent = controlIntent(startToken, "accessibility-revocation", "시험 시작", "시작 안내")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(Ticket04ControlActivity.EXTRA_KEEP_VISIBLE_AFTER_CLICK, true)
            .putExtra(Ticket04ControlActivity.EXTRA_PENDING_INSTRUCTIONS, "조회 준비 중입니다. 자동으로 열린 권한 설정을 기다리세요.")
        val startActivity = instrumentation.startActivitySync(startIntent)
        instrumentation.runOnMainSync { startActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

        try {
            instrumentation.runOnMainSync {
                val button = controlButton(startActivity)
                assertEquals("시험 시작", button.text)
                button.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse("The start screen must stay alive while preparing the real request", startActivity.isFinishing)
                assertEquals("조회 준비 중…", controlButton(startActivity).text)
                assertFalse("The preparation button must not allow another start", controlButton(startActivity).isEnabled)
            }

            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val settingsDeadline = SystemClock.elapsedRealtime() + 3_000L
            var startScreenStillFocused = true
            while (SystemClock.elapsedRealtime() < settingsDeadline && startScreenStillFocused) {
                instrumentation.runOnMainSync { startScreenStillFocused = startActivity.window.decorView.hasWindowFocus() }
                if (startScreenStillFocused) SystemClock.sleep(100L)
            }
            assertFalse("Opening Android Accessibility settings must move focus away from the observer start screen", startScreenStillFocused)
            assertEquals("Android Accessibility settings must be the foreground window before the confirmation action", "com.android.settings", instrumentation.uiAutomation.rootInActiveWindow?.packageName)

            setActiveAction(
                preferences,
                acknowledgementToken,
                "권한 OFF 상태를 확인하고 다음 단계로 진행하세요.",
                "권한 OFF 확인 · 계속",
            )
            val launchRequestedAt = SystemClock.elapsedRealtime()
            val acknowledgementActivity = instrumentation.startActivitySync(
                controlIntent(
                    acknowledgementToken,
                    "accessibility-revocation-confirm-off",
                    "권한 OFF 확인 · 계속",
                    "권한 OFF 상태를 확인하고 다음 단계로 진행하세요.",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ) as Ticket04ControlActivity
            instrumentation.runOnMainSync {
                acknowledgementActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            assertTrue(
                "The display must remain interactive while the observer replaces its Settings task",
                instrumentation.targetContext.getSystemService(PowerManager::class.java)?.isInteractive == true,
            )
            awaitDisplayedAction(
                instrumentation = instrumentation,
                preferences = preferences,
                expectedToken = acknowledgementToken,
                expectedButtonLabel = "권한 OFF 확인 · 계속",
                launchRequestedAt = launchRequestedAt,
            )
            assertEquals(acknowledgementToken, preferences.getString(Ticket04ControlActivity.KEY_DISPLAYED_TOKEN, null))
            assertEquals("권한 OFF 확인 · 계속", preferences.getString(Ticket04ControlActivity.KEY_DISPLAYED_BUTTON_LABEL, null))
            assertTrue("The visible-action record must be newer than the launch request", preferences.getLong(Ticket04ControlActivity.KEY_DISPLAYED_AT, 0L) > 0L)
            val actionRoot: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow
            assertNotNull("The observer action must be the accessibility root of the foreground window", actionRoot)
            assertEquals(context.packageName, actionRoot!!.packageName)
            val acknowledgementNodes = actionRoot.findAccessibilityNodeInfosByText("권한 OFF 확인 · 계속")
            assertTrue("The current acknowledgement button must be visible and enabled in the foreground window", acknowledgementNodes.any { it.isVisibleToUser && it.isClickable && it.isEnabled })
        } finally {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            clearActiveAction(preferences.edit())
        }
    }

    @Test
    fun startingAReportKeepsTheControlScreenInFrontWhileTheRequestIsPrepared() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = "start-control-screen-token"
        val instructions = "조회 중입니다. 자동으로 열린 설정 화면이 나올 때까지 이 화면에서 기다려 주세요."
        val preferences = context.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        setActiveAction(preferences, token, "시작 버튼 설명", "시험 시작", keepVisibleAfterClick = true, pendingInstructions = instructions)
        val intent = Intent(context, Ticket04ControlActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Ticket04ControlActivity.EXTRA_CASE_ID, "accessibility-revocation")
            .putExtra(Ticket04ControlActivity.EXTRA_TOKEN, token)
            .putExtra(Ticket04ControlActivity.EXTRA_INSTRUCTIONS, "시작 버튼 설명")
            .putExtra(Ticket04ControlActivity.EXTRA_BUTTON_LABEL, "시험 시작")
            .putExtra(Ticket04ControlActivity.EXTRA_KEEP_VISIBLE_AFTER_CLICK, true)
            .putExtra(Ticket04ControlActivity.EXTRA_PENDING_INSTRUCTIONS, instructions)
        val activity = startControlActivity(instrumentation, intent)

        try {
            instrumentation.runOnMainSync {
                val container = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                (container.getChildAt(2) as Button).performClick()
            }

            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val container = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                val actionButton = container.getChildAt(2) as Button
                assertFalse("Tapping start must not finish the control screen", activity.isFinishing)
                assertTrue("The waiting screen must remain visible while the real request is prepared", activity.window.decorView.isShown)
                assertTrue("The waiting screen must remain in front", activity.window.decorView.hasWindowFocus())
                assertFalse("The start action must be disabled after one tap", actionButton.isEnabled)
                assertEquals("조회 준비 중…", actionButton.text)
                assertEquals(instructions, (container.getChildAt(1) as TextView).text)
            }
            assertEquals("The exact active run token must acknowledge the start click", token, preferences.getString(Ticket04ControlActivity.KEY_CLICKED_TOKEN, null))
            assertTrue("The start click must retain its elapsed-realtime timestamp", preferences.getLong(Ticket04ControlActivity.KEY_CLICKED_AT, 0L) > 0L)

            preferences.edit().putString(Ticket04ControlActivity.KEY_ACTIVE_TOKEN, "newer-run-token").commit()
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val container = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                assertEquals("An expired in-progress screen must explain that it ended", Ticket04ControlActivity.EXPIRED_INSTRUCTIONS, (container.getChildAt(1) as TextView).text)
                assertEquals("An expired in-progress action must be visibly disabled", Ticket04ControlActivity.EXPIRED_BUTTON_LABEL, (container.getChildAt(2) as Button).text)
                assertFalse("An expired in-progress action must stay disabled", (container.getChildAt(2) as Button).isEnabled)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            clearActiveAction(preferences.edit())
        }
    }

    @Test
    fun notificationOffAcknowledgementIgnoresIntentExtrasAndDoesNotSubmitWithoutTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = "control-screen-preview-token"
        val staleToken = "previous-run-notification-revocation-confirm-off"
        val instructions = "알림 권한 OFF 상태와 요청 경계 증거를 확인하세요. 서버 응답은 이 버튼 전까지 보류됩니다."
        val preferences = context.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        setActiveAction(preferences, token, instructions, "권한 OFF 확인 · 계속")
        preferences.edit().putString(Ticket04ControlActivity.KEY_CLICKED_TOKEN, staleToken)
            .putLong(Ticket04ControlActivity.KEY_CLICKED_AT, 123L).commit()
        val intent = controlIntent("forged-token", "forged-case", "Injected action", "Injected instructions")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = startControlActivity(instrumentation, intent)

        try {
            instrumentation.runOnMainSync {
                val container = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                assertEquals("The observer screen should show its name, instructions, and one explicit action", 3, container.childCount)
                assertEquals("학습 감시 E2E 검사", (container.getChildAt(0) as TextView).text)
                assertEquals(instructions, (container.getChildAt(1) as TextView).text)
                assertEquals("권한 OFF 확인 · 계속", (container.getChildAt(2) as Button).text)
                assertEquals(token, activity.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
                    .getString(Ticket04ControlActivity.KEY_ACTIVE_TOKEN, null))
                val savedToken = activity.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
                    .getString(Ticket04ControlActivity.KEY_CLICKED_TOKEN, null)
                assertEquals("Showing the current control screen must not replace the prior user's action", staleToken, savedToken)
                assertTrue("A previous run/action token must not acknowledge this screen", !Ticket04ActionToken.matches(token, savedToken))
                assertTrue("The control page must be visible to the human", activity.window.decorView.isShown)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            clearActiveAction(context.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE).edit())
        }
    }

    @Test
    fun anExpiredPreparationDisablesItsActionInsteadOfAcknowledgingAnOldRun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = "expired-control-screen-token"
        val preferences = context.getSharedPreferences(Ticket04ControlActivity.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        setActiveAction(preferences, token, "준비된 권한 설정을 진행하세요.", "시험 시작")
        val intent = Intent(context, Ticket04ControlActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Ticket04ControlActivity.EXTRA_CASE_ID, "accessibility-revocation")
            .putExtra(Ticket04ControlActivity.EXTRA_TOKEN, token)
            .putExtra(Ticket04ControlActivity.EXTRA_INSTRUCTIONS, "준비된 권한 설정을 진행하세요.")
            .putExtra(Ticket04ControlActivity.EXTRA_BUTTON_LABEL, "시험 시작")
        val activity = startControlActivity(instrumentation, intent)

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
            clearActiveAction(preferences.edit())
        }
    }

    private fun controlIntent(token: String, caseId: String, buttonLabel: String, instructions: String): Intent =
        Intent(InstrumentationRegistry.getInstrumentation().targetContext, Ticket04ControlActivity::class.java)
            .putExtra(Ticket04ControlActivity.EXTRA_CASE_ID, caseId)
            .putExtra(Ticket04ControlActivity.EXTRA_TOKEN, token)
            .putExtra(Ticket04ControlActivity.EXTRA_INSTRUCTIONS, instructions)
            .putExtra(Ticket04ControlActivity.EXTRA_BUTTON_LABEL, buttonLabel)

    private fun controlButton(activity: android.app.Activity): Button =
        ((activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout).getChildAt(2) as Button)

    private fun startControlActivity(instrumentation: android.app.Instrumentation, intent: Intent): Ticket04ControlActivity {
        val activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as Ticket04ControlActivity
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        return activity
    }

    private fun setActiveAction(
        preferences: android.content.SharedPreferences,
        token: String,
        instructions: String,
        buttonLabel: String,
        keepVisibleAfterClick: Boolean = false,
        pendingInstructions: String = "",
    ) {
        preferences.edit()
            .putString(Ticket04ControlActivity.KEY_ACTIVE_TOKEN, token)
            .putString(Ticket04ControlActivity.KEY_ACTIVE_INSTRUCTIONS, instructions)
            .putString(Ticket04ControlActivity.KEY_ACTIVE_BUTTON_LABEL, buttonLabel)
            .putBoolean(Ticket04ControlActivity.KEY_ACTIVE_KEEP_VISIBLE_AFTER_CLICK, keepVisibleAfterClick)
            .putString(Ticket04ControlActivity.KEY_ACTIVE_PENDING_INSTRUCTIONS, pendingInstructions)
            .remove(Ticket04ControlActivity.KEY_CLICKED_TOKEN)
            .remove(Ticket04ControlActivity.KEY_CLICKED_AT)
            .remove(Ticket04ControlActivity.KEY_DISPLAYED_TOKEN)
            .remove(Ticket04ControlActivity.KEY_DISPLAYED_BUTTON_LABEL)
            .remove(Ticket04ControlActivity.KEY_DISPLAYED_AT)
            .commit()
    }

    private fun awaitDisplayedAction(
        instrumentation: android.app.Instrumentation,
        preferences: android.content.SharedPreferences,
        expectedToken: String,
        expectedButtonLabel: String,
        launchRequestedAt: Long,
    ) {
        val deadline = SystemClock.elapsedRealtime() + DISPLAYED_ACTION_TIMEOUT_MS
        fun isDisplayed(): Boolean =
            preferences.getString(Ticket04ControlActivity.KEY_DISPLAYED_TOKEN, null) == expectedToken &&
                preferences.getString(Ticket04ControlActivity.KEY_DISPLAYED_BUTTON_LABEL, null) == expectedButtonLabel &&
                preferences.getLong(Ticket04ControlActivity.KEY_DISPLAYED_AT, 0L) >= launchRequestedAt

        while (!isDisplayed() && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (!isDisplayed()) SystemClock.sleep(DISPLAYED_ACTION_POLL_MS)
        }
        assertTrue(
            "The observer did not record this action token and enabled button while its window had focus",
            isDisplayed(),
        )
    }

    private fun clearActiveAction(editor: android.content.SharedPreferences.Editor) {
        editor.remove(Ticket04ControlActivity.KEY_ACTIVE_TOKEN)
            .remove(Ticket04ControlActivity.KEY_ACTIVE_INSTRUCTIONS)
            .remove(Ticket04ControlActivity.KEY_ACTIVE_BUTTON_LABEL)
            .remove(Ticket04ControlActivity.KEY_ACTIVE_KEEP_VISIBLE_AFTER_CLICK)
            .remove(Ticket04ControlActivity.KEY_ACTIVE_PENDING_INSTRUCTIONS)
            .remove(Ticket04ControlActivity.KEY_CLICKED_TOKEN)
            .remove(Ticket04ControlActivity.KEY_CLICKED_AT)
            .remove(Ticket04ControlActivity.KEY_DISPLAYED_TOKEN)
            .remove(Ticket04ControlActivity.KEY_DISPLAYED_BUTTON_LABEL)
            .remove(Ticket04ControlActivity.KEY_DISPLAYED_AT)
            .commit()
    }

}

private const val CONTROL_UI_WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L
private const val CONTROL_UI_WAKE_LOCK_SETTLE_MS = 300L
private const val CONTROL_UI_WAKE_LOCK_SETTLE_TIMEOUT_MS = 3_000L
private const val CONTROL_UI_WAKE_LOCK_POLL_MS = 50L
private const val DISPLAYED_ACTION_TIMEOUT_MS = 5_000L
private const val DISPLAYED_ACTION_POLL_MS = 50L
