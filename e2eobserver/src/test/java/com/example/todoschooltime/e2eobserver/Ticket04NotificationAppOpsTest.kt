package com.example.todoschooltime.e2eobserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Ticket04NotificationAppOpsTest {
    @Test
    fun packageSpecificModeTakesPrecedenceOverUidMode() {
        assertEquals(
            Ticket04NotificationAppOps.Mode.IGNORED,
            Ticket04NotificationAppOps.effectiveMode(
                "Uid mode: POST_NOTIFICATION: allow\nPackage mode: POST_NOTIFICATION: ignore\n",
            ),
        )
    }

    @Test
    fun packageDefaultFallsBackToUidMode() {
        assertEquals(
            Ticket04NotificationAppOps.Mode.IGNORED,
            Ticket04NotificationAppOps.effectiveMode(
                "Uid mode: POST_NOTIFICATION: ignore\nPackage mode: POST_NOTIFICATION: default\n",
            ),
        )
    }

    @Test
    fun defaultModeLeavesRuntimePermissionAsTheFallback() {
        assertEquals(
            Ticket04NotificationAppOps.Mode.DEFAULT,
            Ticket04NotificationAppOps.effectiveMode("Uid mode: POST_NOTIFICATION: default\n"),
        )
    }

    @Test
    fun packageWithNoRecordedOverrideUsesAndroidDefaultMode() {
        assertEquals(
            Ticket04NotificationAppOps.Mode.ALLOWED,
            Ticket04NotificationAppOps.effectiveMode("No operations.\nDefault mode: allow\n"),
        )
    }

    @Test
    fun notificationAccessRequiresBothAnAllowedAppOpAndRuntimePermission() {
        assertEquals(false, Ticket04NotificationAppOps.isEnabled(Ticket04NotificationAppOps.Mode.IGNORED, true))
        assertEquals(false, Ticket04NotificationAppOps.isEnabled(Ticket04NotificationAppOps.Mode.ALLOWED, false))
        assertEquals(true, Ticket04NotificationAppOps.isEnabled(Ticket04NotificationAppOps.Mode.ALLOWED, true))
    }

    @Test
    fun unrecognizedOutputFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            Ticket04NotificationAppOps.effectiveMode("Package mode: POST_NOTIFICATION: mystery\n")
        }
    }
}
