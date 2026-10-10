package com.example.todoschooltime

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

internal data class MonitorExecutionStatus(
    val accessibilityEnabled: Boolean,
    val notificationsEnabled: Boolean,
) {
    val permissionsReady: Boolean get() = accessibilityEnabled && notificationsEnabled
}

internal interface LearningMonitorScreenUseCases {
    fun refreshExecutionConditions(): MonitorExecutionStatus
    fun inspectExecutionStatus(): MonitorExecutionStatus
    fun reevaluateMonitoringDate()
    fun credentialsAccepted(accountId: String, email: String, password: String)
    fun credentialsReauthenticated(accountId: String, email: String, password: String)
}

/** Adapts app, settings, and AccessibilityService lifecycle inputs to the private engine runtime seam. */
internal class LearningMonitorRuntimeAdapter(
    context: Context,
    private val engine: LearningMonitorEngine,
) : LearningMonitorScreenUseCases {
    private val appContext = context.applicationContext
    private val credentialStore = CredentialStore(appContext)
    private val probeHandler = Handler(Looper.getMainLooper())
    private var screenReceiver: BroadcastReceiver? = null
    private var permissionProbeRunnable: Runnable? = null

    override fun refreshExecutionConditions(): MonitorExecutionStatus = inspectExecutionStatus().also { status ->
        engine.executionConditionsChanged(status.accessibilityEnabled, status.notificationsEnabled)
    }

    override fun inspectExecutionStatus(): MonitorExecutionStatus = MonitorExecutionStatusInspector.inspect(appContext)

    override fun reevaluateMonitoringDate() {
        engine.reevaluateMonitoringDate()
    }

    override fun credentialsAccepted(accountId: String, email: String, password: String) {
        credentialStore.saveAccountId(accountId)
        engine.credentialsAvailable(accountId, email, password)
    }

    override fun credentialsReauthenticated(accountId: String, email: String, password: String) {
        credentialStore.saveAccountId(accountId)
        engine.reauthenticate(accountId, email, password)
    }

    fun serviceConnected() {
        refreshExecutionConditions()
        engine.attachServiceLease()
        registerScreenReceiver()
        engine.screenOnChanged(isScreenInteractive())
        startPermissionProbe()
        credentialStore.load()?.let { credentials ->
            credentialStore.loadAccountId()?.let { accountId ->
                engine.credentialsAvailable(accountId, credentials.email, credentials.password)
            }
        }
    }

    fun serviceDisconnected() {
        stopPermissionProbe()
        unregisterScreenReceiver()
        engine.screenOnChanged(false)
        // Permission revocation can dispatch this callback before the next periodic probe.
        // Reading settings here keeps enabled-but-unbound distinct from a revoked setting.
        refreshExecutionConditions()
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
                    Intent.ACTION_DATE_CHANGED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> engine.reevaluateMonitoringDate()
                }
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
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

    private fun startPermissionProbe() {
        if (permissionProbeRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                if (permissionProbeRunnable !== this) return
                refreshExecutionConditions()
                probeHandler.postDelayed(this, PERMISSION_PROBE_INTERVAL_MILLIS)
            }
        }
        permissionProbeRunnable = runnable
        probeHandler.postDelayed(runnable, PERMISSION_PROBE_INTERVAL_MILLIS)
    }

    private fun stopPermissionProbe() {
        permissionProbeRunnable?.let(probeHandler::removeCallbacks)
        permissionProbeRunnable = null
    }

    private companion object {
        const val PERMISSION_PROBE_INTERVAL_MILLIS = 60_000L
    }
}

internal object MonitorExecutionStatusInspector {
    fun inspect(context: Context): MonitorExecutionStatus {
        val appContext = context.applicationContext
        val accessibilityEnabled = Settings.Secure.getInt(
            appContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1 && run {
            val componentName = ComponentName(appContext, MonitorAccessibilityService::class.java).flattenToString()
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty()
                .split(':')
                .any { it.equals(componentName, ignoreCase = true) }
        }
        return MonitorExecutionStatus(
            accessibilityEnabled = accessibilityEnabled,
            notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
        )
    }
}
