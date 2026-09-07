package com.example.todoschooltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncRequestTrackerTest {

    @Test
    fun testRequestIdIncrementsMonotonically() {
        val tracker = AsyncRequestTracker()
        val id1 = tracker.nextRequestId()
        val id2 = tracker.nextRequestId()
        val id3 = tracker.nextRequestId()

        assertEquals(1L, id1)
        assertEquals(2L, id2)
        assertEquals(3L, id3)
    }

    @Test
    fun testOnlyLatestRequestIdIsValid() {
        val tracker = AsyncRequestTracker()
        val id1 = tracker.nextRequestId()
        assertTrue(tracker.isLatest(id1))

        val id2 = tracker.nextRequestId()
        assertFalse(tracker.isLatest(id1))
        assertTrue(tracker.isLatest(id2))

        val id3 = tracker.nextRequestId()
        assertFalse(tracker.isLatest(id1))
        assertFalse(tracker.isLatest(id2))
        assertTrue(tracker.isLatest(id3))
    }

    @Test
    fun testConcurrentRequestIds() {
        val tracker = AsyncRequestTracker()
        val threadCount = 20
        val requestsPerThread = 50
        val threads = (1..threadCount).map {
            Thread {
                repeat(requestsPerThread) {
                    tracker.nextRequestId()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals((threadCount * requestsPerThread).toLong(), tracker.currentRequestId())
    }
}
