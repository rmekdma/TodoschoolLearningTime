package com.example.todoschooltime

import android.Manifest
import android.app.Notification
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
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only, read-only view of notifications posted by this app for the E2E observer. */
class LearningMonitorNotificationProvider : ContentProvider() {
    override fun onCreate(): Boolean = context != null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != METHOD_NOTIFICATION_SNAPSHOT) {
            throw IllegalArgumentException("Unsupported notification observation method")
        }
        val appContext = requireNotNull(context).applicationContext
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.myUid() &&
            appContext.packageManager.checkSignatures(Process.myUid(), callingUid) != PackageManager.SIGNATURE_MATCH
        ) {
            throw SecurityException("Notification observation requires the debug signing certificate")
        }
        return Bundle().apply {
            putString(EXTRA_NOTIFICATION_SNAPSHOT_JSON, notificationSnapshotJson(appContext).toString())
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun notificationSnapshotJson(appContext: android.content.Context): JSONObject {
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
        val activeNotifications = JSONArray()
        notificationManager.activeNotifications
            .filter { it.packageName == BuildConfig.APPLICATION_ID }
            .forEach { statusBarNotification ->
                val notification = statusBarNotification.notification
                activeNotifications.put(
                    JSONObject()
                        .put("id", statusBarNotification.id)
                        .put("title", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
                        .put("bigText", notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: "")
                        .put("postTime", statusBarNotification.postTime)
                        .put("when", notification.`when`)
                        .put("flags", notification.flags),
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
            .put("notificationsEnabled", NotificationManagerCompat.from(appContext).areNotificationsEnabled())
            .put(
                "postNotificationsPermissionGranted",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
            .put("channel", channelJson)
            .put("activeNotifications", activeNotifications)
    }

    private companion object {
        const val METHOD_NOTIFICATION_SNAPSHOT = "notificationSnapshot"
        const val EXTRA_NOTIFICATION_SNAPSHOT_JSON = "notificationSnapshotJson"
        const val CHANNEL_ID = "learning-monitor-status"
    }
}
