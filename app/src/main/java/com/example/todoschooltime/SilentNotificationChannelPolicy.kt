package com.example.todoschooltime

import android.app.NotificationManager

/** Defines channel states that cannot sound or interrupt while Ticket 02 runs its silent test. */
internal object SilentNotificationChannelPolicy {
    fun allowsSilentTest(importance: Int, soundPresent: Boolean, vibrationEnabled: Boolean): Boolean {
        if (vibrationEnabled) return false
        return when (importance) {
            NotificationManager.IMPORTANCE_MIN,
            NotificationManager.IMPORTANCE_LOW -> true
            NotificationManager.IMPORTANCE_DEFAULT -> !soundPresent
            else -> false
        }
    }
}
