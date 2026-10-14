package com.example.todoschooltime.e2eobserver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.app.UiAutomation

/** Opens the user-operated Accessibility service page across Android and OEM settings layouts. */
internal object PermissionSettingsNavigator {
    fun productServiceLabel(context: Context, productPackage: String, serviceClass: String): String =
        context.packageManager
            .getServiceInfo(ComponentName(productPackage, serviceClass), 0)
            .loadLabel(context.packageManager)
            .toString()

    fun findServiceRow(
        automation: UiAutomation,
        context: Context,
        productPackage: String,
        serviceClass: String,
    ): AccessibilityNodeInfo {
        val serviceLabel = productServiceLabel(context, productPackage, serviceClass)
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val findClickableServiceRow = {
            findNodeOrNull(automation, 500L) { it.text?.toString()?.contains(serviceLabel) == true }
                ?.let(::findClickableAncestor)
        }
        findClickableServiceRow()?.let { return it }

        for (attempt in 0 until MAX_SCROLL_ATTEMPTS) {
            findClickableServiceRow()?.let { return it }
            val root = automation.rootInActiveWindow ?: continue
            val scrollableNodes = mutableListOf<AccessibilityNodeInfo>()
            collectScrollableNodes(root, scrollableNodes)
            val list = scrollableNodes.lastOrNull() ?: break
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            SystemClock.sleep(SCROLL_PAUSE_MS)
        }
        return waitForNode(automation, PAGE_TIMEOUT_MS) {
            it.text?.toString()?.contains(serviceLabel) == true
        }.let { findClickableAncestor(it) ?: throw AssertionError("The product Accessibility row is not clickable") }
    }

    fun openServiceSettingsPage(
        automation: UiAutomation,
        context: Context,
        productPackage: String,
        serviceClass: String,
    ): String {
        val label = productServiceLabel(context, productPackage, serviceClass)
        val serviceRow = findServiceRow(automation, context, productPackage, serviceClass)
        check(serviceRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Could not open the product's Accessibility settings page"
        }
        waitForNode(automation, PAGE_TIMEOUT_MS) { it.text?.toString()?.contains(label) == true }
        return label
    }

    private fun collectScrollableNodes(node: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>) {
        if (node.isScrollable) output += node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectScrollableNodes(it, output) }
        }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        repeat(MAX_CLICKABLE_ANCESTORS) {
            val current = candidate ?: return null
            if (current.isClickable) return current
            candidate = current.parent
        }
        return null
    }

    private fun waitForNode(
        automation: UiAutomation,
        timeoutMs: Long,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo = findNodeOrNull(automation, timeoutMs, predicate)
        ?: throw AssertionError("Timed out waiting for the requested Accessibility settings screen")

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
            SystemClock.sleep(80L)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> findNode(child, predicate)?.let { return it } }
        }
        return null
    }

    private const val PAGE_TIMEOUT_MS = 8_000L
    private const val MAX_SCROLL_ATTEMPTS = 12
    private const val MAX_CLICKABLE_ANCESTORS = 8
    private const val SCROLL_PAUSE_MS = 250L
}
