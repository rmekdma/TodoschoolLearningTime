package com.example.todoschooltime

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class TargetCountStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun getTargetCounts(childName: String): Map<String, Int> {
        val jsonString = prefs.getString(childName, null) ?: return emptyMap()
        return try {
            val json = JSONObject(jsonString)
            val result = mutableMapOf<String, Int>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = json.getInt(key)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getTargetCount(childName: String, subjectName: String): Int {
        val childTargets = getTargetCounts(childName)
        return childTargets[subjectName] ?: TargetDefaults.defaultCount(subjectName)
    }

    fun saveTargetCounts(childName: String, targets: Map<String, Int>) {
        val json = JSONObject()
        targets.forEach { (subject, count) ->
            json.put(subject, count)
        }
        prefs.edit().putString(childName, json.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "target_counts"
    }
}
