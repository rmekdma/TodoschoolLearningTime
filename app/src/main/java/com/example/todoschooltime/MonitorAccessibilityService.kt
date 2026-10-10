package com.example.todoschooltime

import android.accessibilityservice.AccessibilityService
import androidx.core.app.NotificationManagerCompat
import android.view.accessibility.AccessibilityEvent

class MonitorAccessibilityService : AccessibilityService() {
    private val monitor: LearningMonitorEngine
        get() = (application as TodoSchoolApplication).learningMonitorRuntime

    override fun onServiceConnected() {
        super.onServiceConnected()
        monitor.executionConditionsChanged(
            accessibilityEnabled = true,
            notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled(),
        )
        monitor.attachServiceLease()
        val credentialStore = CredentialStore(this)
        credentialStore.load()?.let { credentials ->
            credentialStore.loadAccountId()?.let { accountId ->
                monitor.credentialsAvailable(accountId, credentials.email, credentials.password)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            monitor.foregroundPackageChanged(event.packageName?.toString())
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        monitor.detachServiceLease()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        monitor.detachServiceLease()
        super.onDestroy()
    }
}
