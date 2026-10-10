package com.example.todoschooltime

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
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
    private var screenReceiver: BroadcastReceiver? = null

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
        registerScreenReceiver()
        engine.screenOnChanged(isScreenInteractive())
        credentialStore.load()?.let { credentials ->
            credentialStore.loadAccountId()?.let { accountId ->
                engine.credentialsAvailable(accountId, credentials.email, credentials.password)
            }
        }
    }

    fun serviceDisconnected() {
        unregisterScreenReceiver()
        engine.screenOnChanged(false)
        engine.detachServiceLease()
    }

    fun foregroundPackageChanged(packageName: String?) {
        engine.foregroundPackageChanged(packageName)
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> engine.screenOnChanged(true)
                    Intent.ACTION_SCREEN_OFF -> engine.screenOnChanged(false)
                }
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { receiver ->
            runCatching { appContext.unregisterReceiver(receiver) }
        }
        screenReceiver = null
    }

    private fun isScreenInteractive(): Boolean =
        appContext.getSystemService(PowerManager::class.java)?.isInteractive == true

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
