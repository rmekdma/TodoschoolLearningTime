package com.example.todoschooltime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportParserTest {

    @Test
    fun testExtractLearningSecondsWithoutEtc() {
        val json = JSONObject().apply {
            put("learningSecondsByCategory", JSONObject().apply {
                put("course", 300)
                put("challenge", "120.5")
                put("etc", 9999) // should be ignored
            })
        }
        val seconds = ReportParser.extractLearningSeconds(json)
        assertEquals(420L, seconds) // 300 + 120
    }

    @Test
    fun testExtractLearningSecondsNullOrEmpty() {
        assertEquals(0L, ReportParser.extractLearningSeconds(null))
        assertEquals(0L, ReportParser.extractLearningSeconds(JSONObject()))
        val emptyCategories = JSONObject().put("learningSecondsByCategory", JSONObject())
        assertEquals(0L, ReportParser.extractLearningSeconds(emptyCategories))
    }
}
