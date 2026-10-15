package com.example.todoschooltime.e2eobserver

import android.app.UiAutomation
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertTrue

/** Small shared driver for the observer scenarios' read-only accessibility tree and UI input. */
internal class ObserverAccessibilitySupport(
    private val pollIntervalMs: Long,
    private val searchWindowRoots: Boolean = false,
) {
    fun setField(field: AccessibilityNodeInfo, value: String, failureMessage: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue(failureMessage, field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
    }

    fun clickDescription(
        automation: UiAutomation,
        description: String,
        timeoutMs: Long,
        failureMessage: String,
    ) {
        val node = waitForNode(
            automation = automation,
            timeoutMs = timeoutMs,
            predicate = { it.contentDescription?.toString() == description && it.isClickable },
        )
        assertTrue(failureMessage, node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    fun clickText(
        automation: UiAutomation,
        text: String,
        timeoutMs: Long,
        failureMessage: String,
    ) {
        val node = waitForNode(
            automation = automation,
            timeoutMs = timeoutMs,
            predicate = { it.text?.toString() == text && it.isClickable },
        )
        assertTrue(failureMessage, node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    fun waitForNode(
        automation: UiAutomation,
        timeoutMs: Long,
        failureMessage: String = "Timed out waiting for an app view",
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo =
        findNodeOrNull(automation, timeoutMs, predicate)
            ?: throw AssertionError(failureMessage)

    fun findNodeOrNull(
        automation: UiAutomation,
        timeoutMs: Long,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            accessibilityRoots(automation).forEach { root ->
                findNode(root, predicate)?.let { return it }
            }
            SystemClock.sleep(pollIntervalMs)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    fun findAllNodes(
        automation: UiAutomation,
        timeoutMs: Long,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        failureMessage: String,
    ): List<AccessibilityNodeInfo> {
        val root = findNodeOrNull(automation, timeoutMs, predicate)?.let { automation.rootInActiveWindow }
            ?: throw AssertionError(failureMessage)
        val result = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, predicate, result)
        return result
    }

    fun visibleText(automation: UiAutomation): List<String> {
        val result = mutableListOf<String>()
        accessibilityRoots(automation).forEach { root ->
            val pending = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                node.text?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
                node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
                for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
            }
        }
        return result
    }

    private fun accessibilityRoots(automation: UiAutomation): List<AccessibilityNodeInfo> {
        if (!searchWindowRoots) return listOfNotNull(automation.rootInActiveWindow)
        return buildList {
            automation.rootInActiveWindow?.let(::add)
            try {
                automation.windows.mapNotNull { it.root }.forEach(::add)
            } catch (_: SecurityException) {
                // Keep the active root when a device hides interactive-window roots.
            }
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return null
    }

    private fun collectNodes(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        result: MutableList<AccessibilityNodeInfo>,
    ) {
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (predicate(node)) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
    }
}
