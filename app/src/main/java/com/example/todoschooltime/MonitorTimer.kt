package com.example.todoschooltime

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ScheduledMonitorTimerFactory(
    private val executor: ScheduledExecutorService,
) : MonitorTimerFactory {
    override fun schedule(delayMillis: Long, callback: () -> Unit): MonitorTimer {
        val future = executor.schedule(callback, delayMillis, TimeUnit.MILLISECONDS)
        return MonitorTimer { future.cancel(false) }
    }
}
