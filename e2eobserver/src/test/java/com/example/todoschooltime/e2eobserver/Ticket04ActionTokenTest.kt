package com.example.todoschooltime.e2eobserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ticket04ActionTokenTest {
    @Test
    fun onlyTheCurrentRunAndActionTokenAcknowledgesTheWaiter() {
        val current = "run-123-notification-revocation-confirm-off-456"
        val stale = "run-122-notification-revocation-confirm-off-111"

        assertTrue(Ticket04ActionToken.matches(current, current))
        assertFalse(Ticket04ActionToken.matches(current, stale))
        assertFalse(Ticket04ActionToken.matches(current, null))
    }
}
