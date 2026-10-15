package com.example.todoschooltime.e2eobserver

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Reusable accessibility actions against the product's visible login, child, target, and interval controls. */
internal object ObserverProductUi {
    const val PRODUCT_PACKAGE = "com.example.todoschooltime.debug"
    const val CHILD_NAME = ObserverTestSupport.CHILD_NAME
    const val TARGET_HANGEUL = 6
    const val TARGET_MATH = 5
    const val TARGET_ENGLISH = 8
    const val EDIT_TEXT = "android.widget.EditText"

    fun openProductApp(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(PRODUCT_PACKAGE)
            ?: throw AssertionError("The debug app has no visible launcher activity: " + PRODUCT_PACKAGE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }

    fun enterFakeCredentialsIfNeeded(
        automation: UiAutomation,
        support: ObserverAccessibilitySupport,
        timeoutMs: Long,
    ) {
        val email = support.findNodeOrNull(automation, timeoutMs) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 이메일" || it.text?.toString() == "토도스쿨 이메일")
        } ?: return
        support.setField(email, ObserverTestSupport.TEST_EMAIL, "Could not enter the fixture email")
        val password = support.waitForNode(automation, timeoutMs) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 비밀번호" || it.text?.toString() == "토도스쿨 비밀번호")
        }
        support.setField(password, ObserverTestSupport.TEST_PASSWORD, "Could not enter the fixture password")
        support.clickText(automation, "확인", timeoutMs, "Could not submit fixture credentials")
    }

    fun submitFakeCredentials(
        automation: UiAutomation,
        support: ObserverAccessibilitySupport,
        timeoutMs: Long,
    ) {
        val email = support.findNodeOrNull(automation, timeoutMs) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 이메일" || it.text?.toString() == "토도스쿨 이메일")
        } ?: throw AssertionError("The login UI omitted the fixture email field")
        val password = support.waitForNode(automation, timeoutMs) {
            it.className == EDIT_TEXT && (it.hintText?.toString() == "토도스쿨 비밀번호" || it.text?.toString() == "토도스쿨 비밀번호")
        }
        support.setField(email, ObserverTestSupport.TEST_EMAIL, "Could not enter the fixture email")
        support.setField(password, ObserverTestSupport.TEST_PASSWORD, "Could not enter the fixture password")
        support.clickText(automation, "확인", timeoutMs, "Could not submit fixture credentials")
    }

    fun waitForChildScreen(
        automation: UiAutomation,
        support: ObserverAccessibilitySupport,
        timeoutMs: Long,
    ) {
        support.waitForNode(automation, timeoutMs) { it.text?.toString()?.contains(CHILD_NAME) == true }
    }

    fun visibleText(automation: UiAutomation, support: ObserverAccessibilitySupport): List<String> =
        support.visibleText(automation)

    fun assertScreenCounts(
        automation: UiAutomation,
        support: ObserverAccessibilitySupport,
        hangeul: Int,
        math: Int,
        english: Int,
        timeoutMs: Long,
    ) {
        ObserverTestSupport.waitForValue(timeoutMs, 250L, "subject report counts in the product screen") {
            val text = visibleText(automation, support).joinToString("\n")
            val hasHangeul = text.contains((if (hangeul >= TARGET_HANGEUL) "✔ " else "") + "한글 (" + hangeul + "/" + TARGET_HANGEUL + ")")
            val hasMath = text.contains((if (math >= TARGET_MATH) "✔ " else "") + "수학 (" + math + "/" + TARGET_MATH + ")")
            val hasEnglish = text.contains((if (english >= TARGET_ENGLISH) "✔ " else "") + "영어 (" + english + "/" + TARGET_ENGLISH + ")")
            if (hasHangeul && hasMath && hasEnglish) true else null
        }
    }

    fun ensureUnregistered(
        automation: UiAutomation,
        support: ObserverAccessibilitySupport,
        context: android.content.Context,
        timeoutMs: Long,
    ) {
        val unregister = support.findNodeOrNull(automation, 1_000L) {
            it.contentDescription?.toString() == CHILD_NAME + " 감시 해제" && it.isClickable
        }
        if (unregister != null) {
            support.clickDescription(automation, CHILD_NAME + " 감시 해제", timeoutMs, "Could not unregister the fixture child")
            support.waitForNode(automation, timeoutMs) {
                it.contentDescription?.toString() == CHILD_NAME + " 감시 등록" && it.isClickable
            }
            ObserverTestSupport.waitForValue(timeoutMs, 250L, "the child's active notification to be removed") {
                ObserverTestSupport.activeNotifications(ObserverTestSupport.readNotificationSnapshot(context))
                    .none { it.optString("title").contains(CHILD_NAME) }
            }
        }
    }

    fun ensureRegistered(
        automation: UiAutomation,
        support: ObserverAccessibilitySupport,
        timeoutMs: Long,
    ) {
        val register = support.findNodeOrNull(automation, 1_000L) {
            it.contentDescription?.toString() == CHILD_NAME + " 감시 등록" && it.isClickable
        }
        if (register != null) {
            support.clickDescription(automation, CHILD_NAME + " 감시 등록", timeoutMs, "Could not register the fixture child")
        }
        support.waitForNode(automation, timeoutMs) {
            it.contentDescription?.toString() == CHILD_NAME + " 감시 해제" && it.isClickable
        }
    }

    fun writeTargets(
        automation: UiAutomation,
        support: ObserverAccessibilitySupport,
        timeoutMs: Long,
    ) {
        support.clickDescription(automation, CHILD_NAME + " 목표 설정", timeoutMs, "Could not open target settings")
        val fields = support.findAllNodes(automation, timeoutMs, { it.className == EDIT_TEXT }, "Target settings omitted subject fields")
        assertEquals("Target settings should include the three subscribed subjects", 3, fields.size)
        listOf(TARGET_HANGEUL, TARGET_MATH, TARGET_ENGLISH).forEachIndexed { index, target ->
            support.setField(fields[index], target.toString(), "Could not enter a subject target")
        }
        support.clickText(automation, "저장", timeoutMs, "Could not save subject targets")
        support.waitForNode(automation, timeoutMs) {
            it.text?.toString()?.contains("한글 (2/" + TARGET_HANGEUL + ")") == true
        }
    }

    fun ensureOneMinuteInterval(
        automation: UiAutomation,
        support: ObserverAccessibilitySupport,
        timeoutMs: Long,
    ) {
        val interval = support.waitForNode(automation, timeoutMs) {
            it.text?.toString()?.startsWith("감시 간격 ") == true && it.isClickable
        }
        if (interval.text?.toString() != "감시 간격 1분") {
            assertTrue("Could not open the monitor interval setting", interval.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val field = support.waitForNode(automation, timeoutMs) { it.className == EDIT_TEXT }
            support.setField(field, "1", "Could not enter the one-minute interval")
            support.clickText(automation, "저장", timeoutMs, "Could not save the one-minute interval")
        }
        support.waitForNode(automation, timeoutMs) { it.text?.toString() == "감시 간격 1분" }
    }

    fun assertRegistrationAndTargets(
        automation: UiAutomation,
        support: ObserverAccessibilitySupport,
        timeoutMs: Long,
    ) {
        support.waitForNode(automation, timeoutMs) {
            it.contentDescription?.toString() == CHILD_NAME + " 감시 해제" && it.isClickable
        }
        support.clickDescription(automation, CHILD_NAME + " 목표 설정", timeoutMs, "Could not open restored target settings")
        val fields = support.findAllNodes(automation, timeoutMs, { it.className == EDIT_TEXT }, "Restored target settings omitted subject fields")
        val actualTargets = fields.map { it.text?.toString() }
        assertEquals(listOf("6", "5", "8"), actualTargets)
        support.clickText(automation, "저장", timeoutMs, "Could not close restored target settings")
        waitForChildScreen(automation, support, timeoutMs)
    }

}
