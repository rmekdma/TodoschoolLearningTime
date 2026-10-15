package com.example.todoschooltime

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Test

class SubjectSubscriptionTest {

    @Test
    fun testUnsubscribedWhenUserIdMissing() {
        val userJson = JSONObject().apply {
            put("name", "지호")
            // userId is absent
        }
        assertFalse(TodoSchoolDataParser.isSubscribed(userJson))
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
}
