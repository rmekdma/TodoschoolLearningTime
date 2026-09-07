package com.example.todoschooltime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectSubscriptionTest {

    @Test
    fun testUnsubscribedWhenUserIdMissing() {
        val userJson = JSONObject().apply {
            put("name", "지호")
            // userId is absent
        }
        val isSubscribed = TodoSchoolDataParser.isSubscribed(userJson)
        assertFalse(isSubscribed)
        val progress = TodoSchoolDataParser.parseSubjectProgress("한글", userJson, null)
        assertNull(progress)
    }

    @Test
    fun testUnsubscribedWhenUserIdBlankOrNullString() {
        val userWithBlank = JSONObject().apply {
            put("name", "지호")
            put("userId", "")
        }
        assertFalse(TodoSchoolDataParser.isSubscribed(userWithBlank))

        val userWithNullString = JSONObject().apply {
            put("name", "지호")
            put("userId", "null")
        }
        assertFalse(TodoSchoolDataParser.isSubscribed(userWithNullString))
    }

    @Test
    fun testSubscribedWhenUserIdPresentEvenIfReportIsNull() {
        val userJson = JSONObject().apply {
            put("name", "지호")
            put("userId", "user-12345")
        }
        assertTrue(TodoSchoolDataParser.isSubscribed(userJson))

        // Report is null (e.g. 0 activities, empty report, or report failed)
        val result = TodoSchoolDataParser.parseSubjectProgress("한글", userJson, null)
        assertNotNull(result)
        assertEquals("한글", result!!.name)
        assertEquals(0, result.count)
    }

    @Test
    fun testSubscribedWithReportActivities() {
        val userJson = JSONObject().apply {
            put("name", "지호")
            put("userId", "user-12345")
        }
        val reportJson = JSONObject().apply {
            put("contentsHistory", JSONObject().apply {
                put("dailyCourse", JSONArray().apply {
                    put(JSONObject())
                    put(JSONObject())
                    put(JSONObject())
                    put(JSONObject())
                    put(JSONObject())
                    put(JSONObject())
                })
            })
        }
        val result = TodoSchoolDataParser.parseSubjectProgress("한글", userJson, reportJson)
        assertNotNull(result)
        assertEquals("한글", result!!.name)
        assertEquals(6, result.count)
    }

    @Test
    fun testMultiSubjectFormattingWithExclusionAndZeroCounts() {
        // Child 1: only subscribed to Hangeul and English
        val child1Subjects = listOf(
            SubjectProgress("한글", 8),
            SubjectProgress("영어", 13),
        )
        val child1Formatted = SubjectStatusFormatter.formatStatus(child1Subjects)
        assertEquals("✔ 한글 (8/6), ✔ 영어 (13/8)", child1Formatted)

        // Child 2: subscribed to all 3, but 0 activities in Hangeul, 3 in Math, 0 in English
        val child2Subjects = listOf(
            SubjectProgress("한글", 0),
            SubjectProgress("수학", 3),
            SubjectProgress("영어", 0),
        )
        val child2Formatted = SubjectStatusFormatter.formatStatus(child2Subjects)
        assertEquals("한글 (0/6), 수학 (3/5), 영어 (0/8)", child2Formatted)
    }
}
