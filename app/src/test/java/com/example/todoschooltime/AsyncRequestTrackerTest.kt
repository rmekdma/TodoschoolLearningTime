package com.example.todoschooltime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncRequestTrackerTest {

    @Test
    fun startingANewerRequestMakesTheEarlierRequestStale() {
        val tracker = AsyncRequestTracker()
        val previousRequest = tracker.nextRequestId()
        assertTrue(tracker.isLatest(previousRequest))

        val latestRequest = tracker.nextRequestId()

        assertFalse(tracker.isLatest(previousRequest))
        assertTrue(tracker.isLatest(latestRequest))
    }
}
