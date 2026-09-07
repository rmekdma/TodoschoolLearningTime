package com.example.todoschooltime

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateHelper {
    val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    private val DISPLAY_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

    private val CHECKED_AT_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN)

    private val YYYYMMDD_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.KOREAN)

    fun today(zoneId: ZoneId = SEOUL_ZONE): LocalDate {
        return LocalDate.now(zoneId)
    }

    fun nowZoned(zoneId: ZoneId = SEOUL_ZONE): ZonedDateTime {
        return ZonedDateTime.now(zoneId)
    }

    fun formatDisplayDate(date: LocalDate): String {
        return date.format(DISPLAY_FORMATTER)
    }

    fun toYyyyMmDd(date: LocalDate): Int {
        return date.format(YYYYMMDD_FORMATTER).toInt()
    }

    fun formatCheckedAt(dateTime: ZonedDateTime): String {
        return "마지막 확인 ${dateTime.format(CHECKED_AT_FORMATTER)}"
    }

    fun maxDateEpochMillis(today: LocalDate = today(), zoneId: ZoneId = SEOUL_ZONE): Long {
        return today.atTime(LocalTime.MAX).atZone(zoneId).toInstant().toEpochMilli()
    }

    fun canGoNext(currentDate: LocalDate, today: LocalDate = today()): Boolean {
        return currentDate.isBefore(today)
    }

    fun isToday(currentDate: LocalDate, today: LocalDate = today()): Boolean {
        return currentDate == today
    }
}
