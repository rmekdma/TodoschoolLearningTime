package com.example.todoschooltime

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

internal data class MonitorExecutionStatus(
    val accessibilityEnabled: Boolean,
    val notificationsEnabled: Boolean,
)

internal interface LearningMonitorScreenUseCases {
    fun refreshExecutionConditions(): MonitorExecutionStatus
    fun inspectExecutionStatus(): MonitorExecutionStatus
    fun credentialsAccepted(accountId: String, email: String, password: String)
}

/** Adapts app, settings, and AccessibilityService lifecycle inputs to the private engine runtime seam. */
internal class LearningMonitorRuntimeAdapter(
    context: Context,
    private val engine: LearningMonitorEngine,
) : LearningMonitorScreenUseCases {
    private val appContext = context.applicationContext
    private val credentialStore = CredentialStore(appContext)

    override fun refreshExecutionConditions(): MonitorExecutionStatus = inspectExecutionStatus().also { status ->
        engine.executionConditionsChanged(status.accessibilityEnabled, status.notificationsEnabled)
    }

    override fun inspectExecutionStatus(): MonitorExecutionStatus = MonitorExecutionStatus(
        accessibilityEnabled = isAccessibilityServiceEnabled(),
        notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
    )

    override fun credentialsAccepted(accountId: String, email: String, password: String) {
        credentialStore.saveAccountId(accountId)
        engine.credentialsAvailable(accountId, email, password)
    }

    fun serviceConnected() {
        engine.executionConditionsChanged(
            accessibilityEnabled = true,
            notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
        )
        engine.attachServiceLease()
        credentialStore.load()?.let { credentials ->
            credentialStore.loadAccountId()?.let { accountId ->
                engine.credentialsAvailable(accountId, credentials.email, credentials.password)
            }
        }
    }

    fun serviceDisconnected() {
        engine.detachServiceLease()
    }

    fun foregroundPackageChanged(packageName: String?) {
        engine.foregroundPackageChanged(packageName)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(appContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!enabled) return false
        val componentName = ComponentName(appContext, MonitorAccessibilityService::class.java).flattenToString()
        return Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .any { it.equals(componentName, ignoreCase = true) }
    }
}
