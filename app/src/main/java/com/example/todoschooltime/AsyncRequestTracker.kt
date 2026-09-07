package com.example.todoschooltime

import java.util.concurrent.atomic.AtomicLong

class AsyncRequestTracker {
    private val lastRequestId = AtomicLong(0L)

    fun nextRequestId(): Long {
        return lastRequestId.incrementAndGet()
    }

    fun isLatest(requestId: Long): Boolean {
        return lastRequestId.get() == requestId
    }

    fun currentRequestId(): Long {
        return lastRequestId.get()
    }
}
