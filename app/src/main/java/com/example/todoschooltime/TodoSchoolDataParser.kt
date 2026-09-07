package com.example.todoschooltime

import org.json.JSONObject

object TodoSchoolDataParser {

    fun isSubscribed(userJson: JSONObject?): Boolean {
        if (userJson == null) return false
        val userId = userJson.opt("userId")?.toString()?.trim() ?: return false
        return userId.isNotBlank() && userId != "null"
    }

    fun parseSubjectProgress(
        subjectName: String,
        userJson: JSONObject?,
        reportJson: JSONObject?,
    ): SubjectProgress? {
        if (!isSubscribed(userJson)) return null
        val count = ReportParser.extractActivityCount(subjectName, reportJson)
        return SubjectProgress(name = subjectName, count = count)
    }
}
