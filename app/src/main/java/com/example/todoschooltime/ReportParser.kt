package com.example.todoschooltime

import org.json.JSONObject

object ReportParser {

    fun extractActivityCount(subjectName: String, reportJson: JSONObject?): Int {
        if (reportJson == null) return 0
        val contentsHistory = reportJson.optJSONObject("contentsHistory") ?: return 0
        val array = when (subjectName) {
            "수학" -> contentsHistory.optJSONArray("daily")
            else -> contentsHistory.optJSONArray("dailyCourse")
        }
        return array?.length() ?: 0
    }

    fun extractLearningSeconds(reportJson: JSONObject?): Long {
        if (reportJson == null) return 0L
        val categories = reportJson.optJSONObject("learningSecondsByCategory") ?: return 0L
        var total = 0L
        val keys = categories.keys()
        while (keys.hasNext()) {
            val category = keys.next()
            if (category == "etc") continue
            total += when (val value = categories.opt(category)) {
                is Number -> value.toLong()
                is String -> value.toDoubleOrNull()?.toLong() ?: 0L
                else -> 0L
            }
        }
        return total
    }
}
