package com.example.todoschooltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMonitorDiagnosticsTest {
    @Test
    fun httpDispatchSnapshotUsesForegroundCallbackAndDispatchOnOneElapsedRealtimeAxis() {
        var elapsedRealtimeMs = 100L
        val diagnostics = LearningMonitorDiagnostics(enabled = true, monotonicMillis = { elapsedRealtimeMs })

        diagnostics.serviceLeaseChanged(true)
        elapsedRealtimeMs = 200L
        diagnostics.foregroundPackageObserved("com.enuma.todomath")
        elapsedRealtimeMs = 240L

        val dispatchSequence = diagnostics.httpRequestDispatching("/v3/todoschool/report/daily/math")
        val event = diagnostics.httpRequestDispatchEventsAfter(0L).single()

        assertEquals(1L, dispatchSequence)
        assertEquals(1L, event.sequence)
        assertEquals("/v3/todoschool/report/daily/math", event.path)
        assertEquals(240L, event.elapsedRealtimeMs)
        assertEquals(1L, event.foregroundEventSequence)
        assertEquals("com.enuma.todomath", event.foregroundPackage)
        assertEquals(200L, event.foregroundEventElapsedRealtimeMs)
        assertTrue(event.serviceLeaseAttached)
        assertEquals(100L, event.serviceLeaseObservedAtElapsedRealtimeMs)
    }

    @Test
    fun releaseDiagnosticsRemainDisabledAndDoNotRetainEvents() {
        var elapsedRealtimeMs = 100L
        val diagnostics = LearningMonitorDiagnostics(enabled = false, monotonicMillis = { elapsedRealtimeMs })

        diagnostics.serviceLeaseChanged(true)
        diagnostics.foregroundPackageObserved("com.enuma.todomath")
        elapsedRealtimeMs = 200L

        assertEquals(0L, diagnostics.httpRequestDispatching("/v3/todoschool/report/daily/math"))
        assertTrue(diagnostics.foregroundEventsAfter(0L).isEmpty())
        assertTrue(diagnostics.httpRequestDispatchEventsAfter(0L).isEmpty())
        assertEquals(0L, diagnostics.httpRequestDispatchCursor())
    }

    @Test
    fun dispatchObserverFailureDoesNotEscapeIntoRequestPath() {
        val diagnostics = LearningMonitorDiagnostics(enabled = true, monotonicMillis = { error("clock probe failure") })

        assertEquals(0L, diagnostics.httpRequestDispatching("/v3/todoschool/report/daily/math"))
        assertTrue(diagnostics.httpRequestDispatchEventsAfter(0L).isEmpty())
    }

    @Test
    fun dispatchRingIsBoundedAndKeepsItsMonotonicCursor() {
        var elapsedRealtimeMs = 0L
        val diagnostics = LearningMonitorDiagnostics(enabled = true, monotonicMillis = { elapsedRealtimeMs++ })

        repeat(130) { diagnostics.httpRequestDispatching("/request/$it") }

        assertEquals(130L, diagnostics.httpRequestDispatchCursor())
        val events = diagnostics.httpRequestDispatchEventsAfter(0L)
        assertEquals(128, events.size)
        assertEquals(3L, events.first().sequence)
        assertEquals(130L, events.last().sequence)
        assertFalse(events.any { it.sequence < 3L })
    }
}
