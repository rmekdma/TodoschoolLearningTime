package com.example.todoschooltime

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class MonitorAccessibilityService : AccessibilityService() {
    private val monitorRuntime: LearningMonitorRuntimeAdapter
        get() = (application as TodoSchoolApplication).learningMonitorRuntime

    override fun onServiceConnected() {
        super.onServiceConnected()
        monitorRuntime.serviceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            monitorRuntime.foregroundPackageChanged(event.packageName?.toString())
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        monitorRuntime.serviceDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        monitorRuntime.serviceDisconnected()
        super.onDestroy()
    }
}
