package com.example.todoschooltime

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Activity and repository against the PC loopback server.
 * UiAutomation keeps any existing AccessibilityService enabled for later monitor scenarios.
 */
@RunWith(AndroidJUnit4::class)
class Ticket01ServerScreenRoundTripTest {
    @Test
    fun testFakeLoginAndServerReportReachTheRealScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expectedApiBaseUrl = InstrumentationRegistry.getArguments().getString(ARG_EXPECTED_API_BASE_URL)
            ?: throw AssertionError("Missing expectedApiBaseUrl instrumentation argument")
        val expectedRunId = InstrumentationRegistry.getArguments().getString(ARG_EXPECTED_RUN_ID)
            ?: throw AssertionError("Missing expectedRunId instrumentation argument")

        assertEquals("Unexpected test app package", EXPECTED_APPLICATION_ID, BuildConfig.APPLICATION_ID)
        assertEquals("APK was built for a different API server", expectedApiBaseUrl, BuildConfig.API_BASE_URL)
        assertTrue(
            "Ticket 01 must only use the local test server",
            BuildConfig.API_BASE_URL.startsWith("http://127.0.0.1:"),
        )

        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val activity = openApp(instrumentation)
        activity.runOnUiThread {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val emailField = findNodeOrNull(automation, LOGIN_FORM_TIMEOUT_MS) {
            it.className == "android.widget.EditText" &&
                (it.hintText?.toString() == "토도스쿨 이메일" || it.text?.toString() == "토도스쿨 이메일")
        }
        if (emailField != null) {
            setField(automation, "토도스쿨 이메일", TEST_EMAIL)
            setField(automation, "토도스쿨 비밀번호", TEST_PASSWORD)
            clickText(automation, "확인")
        }

        val child = findNodeOrNull(automation, TIMEOUT_MS) {
            it.text?.toString()?.contains("테스트 아이") == true
        }
        if (child == null) {
            val screenshotPath = saveScreenshot(automation, instrumentation, expectedRunId)
            throw AssertionError(
                "Timed out waiting for the test child on the real app screen. " +
                    "Visible text: ${visibleText(automation)}; screenshot=$screenshotPath",
            )
        }
        val status = waitForText(automation, "한글 (2/6)", TIMEOUT_MS)
        assertTrue("The displayed card must contain the server's math count: $status", status.contains("✔ 수학 (5/5)"))
        assertTrue("The displayed card must contain the server's English count: $status", status.contains("영어 (7/8)"))
        assertNotNull(
            "The server-backed screen should have a visible screenshot",
            saveScreenshot(automation, instrumentation, expectedRunId),
        )
    }

    private fun openApp(instrumentation: Instrumentation): MainActivity {
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return instrumentation.startActivitySync(intent) as MainActivity
    }

    private fun visibleText(automation: UiAutomation): List<String> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<String>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return result
    }

    private fun setField(automation: UiAutomation, hint: String, value: String) {
        val node = waitForNode(automation, TIMEOUT_MS) {
            it.className == "android.widget.EditText" &&
                (it.hintText?.toString() == hint || it.text?.toString() == hint)
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue("Could not enter test credentials into the $hint field", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
    }

    private fun clickText(automation: UiAutomation, text: String) {
        val node = waitForNode(automation, TIMEOUT_MS) {
            it.text?.toString() == text && it.isClickable
        }
        assertTrue("Could not tap '$text'", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun waitForText(automation: UiAutomation, text: String, timeoutMs: Long): String {
        val node = waitForNode(automation, timeoutMs) {
            it.text?.toString()?.contains(text) == true
        }
        return node.text?.toString().orEmpty()
    }

    private fun waitForNode(
        automation: UiAutomation,
        timeoutMs: Long,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        return findNodeOrNull(automation, timeoutMs, predicate)
            ?: throw AssertionError("Timed out waiting for an accessible node in the real app screen")
    }

    private fun findNodeOrNull(
        automation: UiAutomation,
        timeoutMs: Long,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            automation.rootInActiveWindow?.let { root ->
                findNode(root, predicate)?.let { return it }
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return null
    }

    private fun saveScreenshot(
        automation: UiAutomation,
        instrumentation: Instrumentation,
        expectedRunId: String,
    ): String? {
        val screenshot: Bitmap = automation.takeScreenshot() ?: return null
        val bytes = ByteArrayOutputStream().use { output ->
            assertTrue("Could not encode the actual Activity screenshot", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        val file = File(instrumentation.targetContext.filesDir, SCREENSHOT_NAME)
        FileOutputStream(file).use { it.write(bytes) }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val chunks = encoded.chunked(SCREENSHOT_LOG_CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            Log.i(SCREENSHOT_LOG_TAG, "$SCREENSHOT_LOG_MARKER:$expectedRunId:${index + 1}/${chunks.size}:$chunk")
        }
        screenshot.recycle()
        System.out.println("TICKET01_SCREENSHOT=${file.absolutePath}")
        return file.absolutePath
    }

    private companion object {
        const val ARG_EXPECTED_API_BASE_URL = "expectedApiBaseUrl"
        const val ARG_EXPECTED_RUN_ID = "expectedRunId"
        const val EXPECTED_APPLICATION_ID = "com.example.todoschooltime.debug"
        const val TEST_EMAIL = "ticket01@example.test"
        const val TEST_PASSWORD = "not-a-real-password"
        const val SCREENSHOT_NAME = "ticket01-server-screen.png"
        const val SCREENSHOT_LOG_TAG = "Ticket01Screenshot"
        const val SCREENSHOT_LOG_MARKER = "TICKET01_SCREENSHOT_CHUNK"
        const val SCREENSHOT_LOG_CHUNK_SIZE = 2_800
        const val LOGIN_FORM_TIMEOUT_MS = 3_000L
        const val TIMEOUT_MS = 25_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
