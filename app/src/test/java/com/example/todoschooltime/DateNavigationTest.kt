package com.example.todoschooltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
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
    fun testMaxDateEpochMillisIsEndOfDay() {
        val today = LocalDate.of(2026, 9, 7)
        val maxMillis = DateHelper.maxDateEpochMillis(today, seoulZone)

        val expected = today.atTime(LocalTime.MAX).atZone(seoulZone).toInstant().toEpochMilli()
        assertEquals(expected, maxMillis)

        // Verify that today 23:59:59.999999999 is included and next day start is strictly after maxMillis
        val nextDayStart = today.plusDays(1).atStartOfDay(seoulZone).toInstant().toEpochMilli()
        assertTrue(maxMillis < nextDayStart)
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
        val endOfDayZonedDateTime = ZonedDateTime.of(today, LocalTime.MAX, seoulZone)
        assertEquals(endOfDayZonedDateTime.toInstant().toEpochMilli(), maxMillis)

        val nextYearFirstMilli = ZonedDateTime.of(LocalDate.of(2027, 1, 1), LocalTime.MIN, seoulZone).toInstant().toEpochMilli()
        assertEquals(nextYearFirstMilli - 1, maxMillis / 1_000_000 * 1_000_000 + (maxMillis % 1_000_000))
        assertTrue(maxMillis < nextYearFirstMilli)
    }
}
