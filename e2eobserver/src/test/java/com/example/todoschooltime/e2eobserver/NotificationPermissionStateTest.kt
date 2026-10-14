package com.example.todoschooltime.e2eobserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationPermissionStateTest {
    @Test
    fun packageSpecificModeTakesPrecedenceOverUidMode() {
        assertEquals(
            NotificationPermissionState.Mode.IGNORED,
            NotificationPermissionState.effectiveMode(
                "Uid mode: POST_NOTIFICATION: allow\nPackage mode: POST_NOTIFICATION: ignore\n",
            ),
        )
    }

    @Test
    fun packageDefaultFallsBackToUidMode() {
        assertEquals(
            NotificationPermissionState.Mode.IGNORED,
            NotificationPermissionState.effectiveMode(
                "Uid mode: POST_NOTIFICATION: ignore\nPackage mode: POST_NOTIFICATION: default\n",
            ),
        )
    }

    @Test
    fun defaultModeLeavesRuntimePermissionAsTheFallback() {
        assertEquals(
            NotificationPermissionState.Mode.DEFAULT,
            NotificationPermissionState.effectiveMode("Uid mode: POST_NOTIFICATION: default\n"),
        )
    }

    @Test
    fun packageWithNoRecordedOverrideUsesAndroidDefaultMode() {
        assertEquals(
            NotificationPermissionState.Mode.ALLOWED,
            NotificationPermissionState.effectiveMode("No operations.\nDefault mode: allow\n"),
        )
    }

    @Test
    fun notificationAccessRequiresBothAnAllowedAppOpAndRuntimePermission() {
        assertEquals(false, NotificationPermissionState.isEnabled(NotificationPermissionState.Mode.IGNORED, true))
        assertEquals(false, NotificationPermissionState.isEnabled(NotificationPermissionState.Mode.ALLOWED, false))
        assertEquals(true, NotificationPermissionState.isEnabled(NotificationPermissionState.Mode.ALLOWED, true))
    }

    @Test
    fun unrecognizedOutputFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationPermissionState.effectiveMode("Package mode: POST_NOTIFICATION: mystery\n")
        }
    }
}
