package com.example.todoschooltime.e2eobserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualActionTokenTest {
    @Test
    fun onlyTheCurrentRunAndActionTokenAcknowledgesTheWaiter() {
        val current = "run-123-notification-revocation-confirm-off-456"
        val stale = "run-122-notification-revocation-confirm-off-111"

        assertTrue(ManualActionToken.matches(current, current))
        assertFalse(ManualActionToken.matches(current, stale))
        assertFalse(ManualActionToken.matches(current, null))
    }
}
