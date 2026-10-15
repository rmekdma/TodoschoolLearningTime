package com.example.todoschooltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DateNavigationTest {

    private val seoulZone = ZoneId.of("Asia/Seoul")

    @Test
    fun testFormatDisplayDate() {
        val date = LocalDate.of(2026, 9, 7) // 2026-09-07 is Monday
        val formatted = DateHelper.formatDisplayDate(date)
        assertEquals("9월 7일 (월)", formatted)
    }

    @Test
    fun testFormatDisplayDateSingleDigitMonthAndDay() {
        val date = LocalDate.of(2026, 1, 5) // 2026-01-05 is Monday
        val formatted = DateHelper.formatDisplayDate(date)
        assertEquals("1월 5일 (월)", formatted)
    }

    @Test
    fun testToYyyyMmDd() {
        val date = LocalDate.of(2026, 9, 7)
        assertEquals(20260907, DateHelper.toYyyyMmDd(date))
    }

    @Test
    fun testFormatCheckedAt() {
        val dateTime = ZonedDateTime.of(2026, 9, 7, 14, 5, 0, 0, seoulZone)
        val formatted = DateHelper.formatCheckedAt(dateTime)
        assertEquals("마지막 확인 14:05", formatted)
    }

    @Test
    fun nextSeoulMidnightUsesSeoulCalendarDateForNonSeoulInputs() {
        val utcNow = ZonedDateTime.of(
            2026, 10, 10, 16, 0, 0, 0,
            ZoneId.of("UTC"),
        )

        assertEquals(23L * 60L * 60L * 1_000L, DateHelper.millisUntilNextSeoulMidnight(utcNow))
    }

    @Test
    fun testMaxDateEpochMillisIsEndOfDay() {
        val today = LocalDate.of(2026, 9, 7)
        val maxMillis = DateHelper.maxDateEpochMillis(today, seoulZone)

        assertEquals(1_788_793_199_999L, maxMillis)

        // In Seoul, the next midnight is 2026-09-07 15:00 UTC.
        assertEquals(1_788_793_200_000L, maxMillis + 1L)
        val todayNoon = today.atTime(12, 0).atZone(seoulZone).toInstant().toEpochMilli()
        assertTrue(todayNoon < maxMillis)
    }

    @Test
    fun testCanGoNextOnlyWhenBeforeToday() {
        val today = LocalDate.of(2026, 9, 7)
        val past = LocalDate.of(2026, 9, 6)

        assertTrue(DateHelper.canGoNext(past, today))
        assertFalse(DateHelper.canGoNext(today, today))
        assertFalse(DateHelper.canGoNext(today.plusDays(1), today))
    }

    @Test
    fun testIsToday() {
        val today = LocalDate.of(2026, 9, 7)
        assertTrue(DateHelper.isToday(today, today))
        assertFalse(DateHelper.isToday(today.minusDays(1), today))
    }

    @Test
    fun testLeapYearFormattingAndYyyyMmDd() {
        val leapDay = LocalDate.of(2024, 2, 29) // 2024 is leap year, 2/29 is Thursday
        assertEquals("2월 29일 (목)", DateHelper.formatDisplayDate(leapDay))
        assertEquals(20240229, DateHelper.toYyyyMmDd(leapDay))
    }

    @Test
    fun testMidnightBoundaryMaxDateEpochMillis() {
        val today = LocalDate.of(2026, 12, 31)
        val maxMillis = DateHelper.maxDateEpochMillis(today, seoulZone)
        assertEquals(1_798_729_199_999L, maxMillis)

        val nextYearFirstMilli = 1_798_729_200_000L
        assertEquals(nextYearFirstMilli - 1, maxMillis)
        assertTrue(maxMillis < nextYearFirstMilli)
    }
}
