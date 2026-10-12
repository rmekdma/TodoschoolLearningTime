package com.example.todoschooltime

import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only read surface used by an independently running E2E observer package. */
class LearningMonitorDiagnosticsProvider : ContentProvider() {
    override fun onCreate(): Boolean = context != null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != METHOD_SNAPSHOT) throw IllegalArgumentException("Unsupported diagnostics method")
        val appContext = requireNotNull(context)
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.myUid() &&
            appContext.packageManager.checkSignatures(Process.myUid(), callingUid) != PackageManager.SIGNATURE_MATCH
        ) {
            throw SecurityException("Diagnostics require the debug signing certificate")
        }
        val application = appContext.applicationContext as TodoSchoolApplication
        return Bundle().apply { putString(EXTRA_SNAPSHOT_JSON, snapshotJson(application).toString()) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun snapshotJson(application: TodoSchoolApplication): JSONObject {
        val snapshot = application.learningMonitor.snapshot()
        val children = JSONObject()
        snapshot.children.forEach { (childName, child) ->
            val subjects = JSONObject()
            child.subjects.forEach { (subjectName, subject) ->
                subjects.put(
                    subjectName,
                    JSONObject()
                        .put("status", subject.status.name)
                        .put("activityCount", subject.activityCount ?: JSONObject.NULL)
                        .put("targetCount", subject.targetCount),
                )
            }
            children.put(childName, JSONObject().put("subjects", subjects))
        }
        val diagnostics = application.learningMonitorDiagnostics
        val notifications = JSONArray()
        diagnostics.notificationEventsAfter(0L).forEach { event ->
            notifications.put(
                JSONObject()
                    .put("sequence", event.sequence)
                    .put("accountId", event.accountId)
                    .put("childName", event.childName)
                    .put("completionRequested", event.completionRequested)
                    .put("completionSubjectName", event.completionSubjectName ?: JSONObject.NULL)
                    .put("notificationId", event.notificationId)
                    .put("notificationManagerCallReturned", event.notificationManagerCallReturned ?: JSONObject.NULL),
            )
        }
        val foreground = JSONArray()
        diagnostics.foregroundEventsAfter(0L).forEach { event ->
            foreground.put(
                JSONObject()
                    .put("sequence", event.sequence)
                    .put("packageName", event.packageName ?: JSONObject.NULL)
                    .put("elapsedRealtimeMs", event.elapsedRealtimeMs),
            )
        }
        val httpRequestDispatches = JSONArray()
        diagnostics.httpRequestDispatchEventsAfter(0L).forEach { event ->
            httpRequestDispatches.put(
                JSONObject()
                    .put("sequence", event.sequence)
                    .put("path", event.path)
                    .put("elapsedRealtimeMs", event.elapsedRealtimeMs)
                    .put("foregroundEventSequence", event.foregroundEventSequence)
                    .put("foregroundPackage", event.foregroundPackage ?: JSONObject.NULL)
                    .put("foregroundEventElapsedRealtimeMs", event.foregroundEventElapsedRealtimeMs)
                    .put("serviceLeaseAttached", event.serviceLeaseAttached)
                    .put("serviceLeaseObservedAtElapsedRealtimeMs", event.serviceLeaseObservedAtElapsedRealtimeMs),
            )
        }
        val notificationManager = appContextOrThrow().getSystemService(NotificationManager::class.java)
        val activeNotifications = JSONArray()
        notificationManager.activeNotifications
            .filter { it.packageName == BuildConfig.APPLICATION_ID }
            .forEach { notification ->
                activeNotifications.put(
                    JSONObject()
                        .put("id", notification.id)
                        .put("title", notification.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "")
                        .put("bigText", notification.notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: ""),
                )
            }
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.getNotificationChannel(CHANNEL_ID)
        } else {
            null
        }
        val channelJson = JSONObject()
            .put("exists", channel != null)
            .put("importance", channel?.importance ?: JSONObject.NULL)
            .put("soundUri", channel?.sound?.toString() ?: JSONObject.NULL)
            .put("vibrationEnabled", channel?.shouldVibrate() ?: JSONObject.NULL)

        return JSONObject()
            .put("applicationId", BuildConfig.APPLICATION_ID)
            .put("accountId", snapshot.accountId ?: JSONObject.NULL)
            .put("serviceLeaseAttached", snapshot.serviceLeaseAttached)
            .put("accessibilityEnabled", snapshot.accessibilityEnabled)
            .put("notificationsEnabled", snapshot.notificationsEnabled)
            .put(
                "postNotificationsPermissionGranted",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    appContextOrThrow().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
            .put("registeredChildren", JSONArray(snapshot.registeredChildren.toList()))
            .put("children", children)
            .put("notificationEvents", notifications)
            .put("foregroundEvents", foreground)
            .put("httpRequestDispatchCursor", diagnostics.httpRequestDispatchCursor())
            .put("httpRequestDispatchEvents", httpRequestDispatches)
            .put("activeNotifications", activeNotifications)
            .put("channel", channelJson)
    }

    private fun appContextOrThrow() = requireNotNull(context).applicationContext

    private companion object {
        const val METHOD_SNAPSHOT = "snapshot"
        const val EXTRA_SNAPSHOT_JSON = "snapshotJson"
        const val CHANNEL_ID = "learning-monitor-status"
    }
}
