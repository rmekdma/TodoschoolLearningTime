package com.example.todoschooltime

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentNotificationChannelPolicyTest {
    @Test
    fun lowAndMinimumImportanceSuppressSoundEvenWhenTheConfiguredUriIsRetained() {
        assertTrue(SilentNotificationChannelPolicy.allowsSilentTest(NotificationManager.IMPORTANCE_MIN, soundPresent = true, vibrationEnabled = false))
        assertTrue(SilentNotificationChannelPolicy.allowsSilentTest(NotificationManager.IMPORTANCE_LOW, soundPresent = true, vibrationEnabled = false))
    }

    @Test
    fun defaultImportanceIsAcceptedOnlyWhenSoundAndVibrationAreDisabled() {
        assertTrue(SilentNotificationChannelPolicy.allowsSilentTest(NotificationManager.IMPORTANCE_DEFAULT, soundPresent = false, vibrationEnabled = false))
        assertFalse(SilentNotificationChannelPolicy.allowsSilentTest(NotificationManager.IMPORTANCE_DEFAULT, soundPresent = true, vibrationEnabled = false))
        assertFalse(SilentNotificationChannelPolicy.allowsSilentTest(NotificationManager.IMPORTANCE_DEFAULT, soundPresent = false, vibrationEnabled = true))
    }

    @Test
    fun vibrationAndAlertingOrBlockedImportanceAreRejected() {
        assertFalse(SilentNotificationChannelPolicy.allowsSilentTest(NotificationManager.IMPORTANCE_MIN, soundPresent = false, vibrationEnabled = true))
        assertFalse(SilentNotificationChannelPolicy.allowsSilentTest(NotificationManager.IMPORTANCE_LOW, soundPresent = false, vibrationEnabled = true))
        assertFalse(SilentNotificationChannelPolicy.allowsSilentTest(NotificationManager.IMPORTANCE_HIGH, soundPresent = false, vibrationEnabled = false))
        assertFalse(SilentNotificationChannelPolicy.allowsSilentTest(NotificationManager.IMPORTANCE_NONE, soundPresent = false, vibrationEnabled = false))
    }
}
