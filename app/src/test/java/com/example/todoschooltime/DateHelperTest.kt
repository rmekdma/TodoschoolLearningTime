package com.example.todoschooltime

import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DateHelperTest {
    @Test
    fun nextSeoulMidnightUsesSeoulCalendarDateForNonSeoulInputs() {
        val utcNow = ZonedDateTime.of(
            2026, 10, 10, 16, 0, 0, 0,
            ZoneId.of("UTC"),
        )

        assertEquals(23L * 60L * 60L * 1_000L, DateHelper.millisUntilNextSeoulMidnight(utcNow))
    }
}
