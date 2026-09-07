package com.example.todoschooltime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportParserTest {

    @Test
    fun testExtractActivityCountHangeul() {
        val json = JSONObject().apply {
            put("contentsHistory", JSONObject().apply {
                put("dailyCourse", JSONArray().apply {
                    put(JSONObject())
                    put(JSONObject())
                    put(JSONObject())
                })
            })
        }
        val count = ReportParser.extractActivityCount("한글", json)
        assertEquals(3, count)
    }

    @Test
    fun testExtractActivityCountEnglish() {
        val json = JSONObject().apply {
            put("contentsHistory", JSONObject().apply {
                put("dailyCourse", JSONArray().apply {
                    put(JSONObject())
                    put(JSONObject())
                })
            })
        }
        val count = ReportParser.extractActivityCount("영어", json)
        assertEquals(2, count)
    }

    @Test
    fun testExtractActivityCountMath() {
        val json = JSONObject().apply {
            put("contentsHistory", JSONObject().apply {
                put("daily", JSONArray().apply {
                    put(JSONObject())
                    put(JSONObject())
                    put(JSONObject())
                    put(JSONObject())
                })
            })
        }
        val count = ReportParser.extractActivityCount("수학", json)
        assertEquals(4, count)
    }

    @Test
    fun testExtractActivityCountNullOrEmptyCases() {
        // null report
        assertEquals(0, ReportParser.extractActivityCount("한글", null))
        assertEquals(0, ReportParser.extractActivityCount("수학", null))
        assertEquals(0, ReportParser.extractActivityCount("영어", null))

        // empty json
        val emptyJson = JSONObject()
        assertEquals(0, ReportParser.extractActivityCount("한글", emptyJson))
        assertEquals(0, ReportParser.extractActivityCount("수학", emptyJson))

        // contentsHistory is empty
        val emptyHistory = JSONObject().put("contentsHistory", JSONObject())
        assertEquals(0, ReportParser.extractActivityCount("한글", emptyHistory))
        assertEquals(0, ReportParser.extractActivityCount("수학", emptyHistory))

        // contentsHistory has empty array
        val emptyArrayHangeul = JSONObject().put("contentsHistory", JSONObject().put("dailyCourse", JSONArray()))
        assertEquals(0, ReportParser.extractActivityCount("한글", emptyArrayHangeul))

        val emptyArrayMath = JSONObject().put("contentsHistory", JSONObject().put("daily", JSONArray()))
        assertEquals(0, ReportParser.extractActivityCount("수학", emptyArrayMath))
    }

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
