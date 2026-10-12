package com.example.todoschooltime.e2eobserver

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.putJsonArray(name: String, values: Iterable<*>): JSONObject =
    put(name, JSONArray(values.toList()))
