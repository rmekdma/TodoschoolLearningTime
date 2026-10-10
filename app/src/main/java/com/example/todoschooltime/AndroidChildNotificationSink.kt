package com.example.todoschooltime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class AndroidChildNotificationSink(
    context: Context,
    private val idAllocator: SharedPreferencesNotificationIdAllocator,
) : ChildNotificationSink {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "학습 상태", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "감시 등록한 아이의 오늘 과목 상태"
                },
            )
        }
    }

    override fun showStatus(accountId: String, child: MonitorChildSnapshot, completion: Boolean) {
        val body = MonitorNotificationText.body(child)
        val title = if (completion) {
            val latest = child.subjects.values.firstOrNull { it.status == MonitorSubjectStatus.COMPLETE }?.name
            if (latest == null) "${child.childName} 학습 상태" else "${child.childName} · $latest 완료"
        } else {
            "${child.childName} 학습 상태"
        }
        val notification: Notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(MonitorNotificationText.summary(child))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(!completion)
            .setSilent(!completion)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            notificationManager.notify(idAllocator.idFor(accountId, child.childName), notification)
        } catch (_: SecurityException) {
            // The app UI surfaces notification permission and settings separately.
        }
    }

    override fun cancel(accountId: String, childName: String) {
        notificationManager.cancel(idAllocator.idFor(accountId, childName))
    }

    private companion object {
        const val CHANNEL_ID = "learning-monitor-status"
    }
}
