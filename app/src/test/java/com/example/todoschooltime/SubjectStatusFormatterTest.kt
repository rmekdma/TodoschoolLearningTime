package com.example.todoschooltime

import org.junit.Assert.assertEquals
import org.junit.Test

class SubjectStatusFormatterTest {

    @Test
    fun testFormatSubjectCompleted() {
        val formatted = SubjectStatusFormatter.formatSubject(name = "한글", count = 8, targetCount = 6)
        assertEquals("✔ 한글 (8/6)", formatted)
    }

    @Test
    fun testFormatSubjectExactTargetCompleted() {
        val formatted = SubjectStatusFormatter.formatSubject(name = "수학", count = 5, targetCount = 5)
        assertEquals("✔ 수학 (5/5)", formatted)
    }

    @Test
    fun testFormatSubjectIncomplete() {
        val formatted = SubjectStatusFormatter.formatSubject(name = "수학", count = 3, targetCount = 5)
        assertEquals("수학 (3/5)", formatted)
    }

    @Test
    fun testFormatSubjectZeroCount() {
        val formatted = SubjectStatusFormatter.formatSubject(name = "한글", count = 0, targetCount = 6)
        assertEquals("한글 (0/6)", formatted)
    }

    @Test
    fun testFormatSubjectZeroTargetAlwaysCompleted() {
        val formattedZero = SubjectStatusFormatter.formatSubject(name = "영어", count = 0, targetCount = 0)
        assertEquals("✔ 영어 (0/0)", formattedZero)

        val formattedPositive = SubjectStatusFormatter.formatSubject(name = "영어", count = 3, targetCount = 0)
        assertEquals("✔ 영어 (3/0)", formattedPositive)
    }

    @Test
    fun testFormatStatusWithDefaultTargets() {
        val subjects = listOf(
            SubjectProgress("한글", 8),
            SubjectProgress("수학", 3),
            SubjectProgress("영어", 13),
        )
        val status = SubjectStatusFormatter.formatStatus(subjects)
        assertEquals("✔ 한글 (8/6), 수학 (3/5), ✔ 영어 (13/8)", status)
    }

    @Test
    fun testFormatStatusExcludesUnsubscribedSubjects() {
        // Child subscribed only to Hangeul and English
        val subjects = listOf(
            SubjectProgress("한글", 6),
            SubjectProgress("영어", 4),
        )
        val status = SubjectStatusFormatter.formatStatus(subjects)
        assertEquals("✔ 한글 (6/6), 영어 (4/8)", status)
    }

    @Test
    fun testFormatStatusAllZeroActivities() {
        val subjects = listOf(
            SubjectProgress("한글", 0),
            SubjectProgress("수학", 0),
            SubjectProgress("영어", 0),
        )
        val status = SubjectStatusFormatter.formatStatus(subjects)
        assertEquals("한글 (0/6), 수학 (0/5), 영어 (0/8)", status)
    }

    @Test
    fun testFormatStatusEmptyList() {
        val status = SubjectStatusFormatter.formatStatus(emptyList())
        assertEquals("", status)
    }
}
