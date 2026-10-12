package com.example.todoschooltime.e2eobserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ticket03JsonArraySerializationTest {
    @Test
    fun ticket03EvidenceCollectionsRemainJsonArraysAfterAndroidSerialization() {
        val poll = JSONObject()
            .putJsonArray("foregroundPackagesObservedDuringTransition", emptyList<String>())
            .putJsonArray("foregroundEventsDuringTransition", emptyList<JSONObject>())
            .putJsonArray("requestsVisibleAtForegroundEventObservation", emptyList<Long>())
            .putJsonArray("requestsDuringTransitionSequences", emptyList<Long>())
            .putJsonArray("requestsDuringTransition", emptyList<String>())
            .putJsonArray("foregroundEventsObservedAtReport", emptyList<JSONObject>())
            .putJsonArray("foregroundPackagesObservedAtReport", listOf("com.enuma.todohangeul"))
            .putJsonArray("foregroundEventsObservedAtApply", emptyList<JSONObject>())
            .putJsonArray("foregroundPackagesObservedAtApply", emptyList<String>())
            .putJsonArray("requestCycleSequences", listOf(1L, 2L, 3L))
            .putJsonArray("requestCyclePaths", listOf("/v3/account/signin"))
        val window = JSONObject()
            .putJsonArray("foregroundPackagesObservedDuringTransition", emptyList<String>())
            .putJsonArray("foregroundEventsDuringTransition", emptyList<JSONObject>())
            .putJsonArray("requestsDuringTransition", emptyList<String>())
            .putJsonArray("requestsDuringTransitionSequences", emptyList<Long>())
            .putJsonArray("foregroundEventsObservedDuringWindow", listOf(JSONObject().put("sequence", 4)))
            .putJsonArray("foregroundPackagesObservedDuringWindow", listOf("com.enuma.todohangeul"))
        val wakeEvidence = JSONObject()
            .putJsonArray("foregroundEventsDuringAcquire", emptyList<JSONObject>())
        val wakeReleaseEvidence = JSONObject()
            .putJsonArray("foregroundEventsAfterRelease", listOf(JSONObject().put("sequence", 5)))
        val result = JSONObject()
            .putJsonArray("baselineRequests", listOf(JSONObject().put("path", "/v3/account/signin")))
            .putJsonArray("pollRequests", listOf(poll))
            .putJsonArray("observationWindows", listOf(window))
            .putJsonArray("foregroundEvents", emptyList<JSONObject>())
            .put("stayAwake", wakeEvidence)

        val serialized = JSONObject(result.toString())
        assertJsonArrays(
            serialized,
            listOf("baselineRequests", "pollRequests", "observationWindows", "foregroundEvents"),
        )
        assertJsonArrays(
            serialized.getJSONArray("pollRequests").getJSONObject(0),
            listOf(
                "foregroundPackagesObservedDuringTransition",
                "foregroundEventsDuringTransition",
                "requestsVisibleAtForegroundEventObservation",
                "requestsDuringTransitionSequences",
                "requestsDuringTransition",
                "foregroundEventsObservedAtReport",
                "foregroundPackagesObservedAtReport",
                "foregroundEventsObservedAtApply",
                "foregroundPackagesObservedAtApply",
                "requestCycleSequences",
                "requestCyclePaths",
            ),
        )
        assertJsonArrays(
            serialized.getJSONArray("observationWindows").getJSONObject(0),
            listOf(
                "foregroundPackagesObservedDuringTransition",
                "foregroundEventsDuringTransition",
                "requestsDuringTransition",
                "requestsDuringTransitionSequences",
                "foregroundEventsObservedDuringWindow",
                "foregroundPackagesObservedDuringWindow",
            ),
        )
        assertJsonArrays(
            serialized.getJSONObject("stayAwake"),
            listOf("foregroundEventsDuringAcquire"),
        )
        assertJsonArrays(JSONObject(wakeReleaseEvidence.toString()), listOf("foregroundEventsAfterRelease"))
        assertEquals(0, serialized.getJSONArray("pollRequests").getJSONObject(0).getJSONArray("requestsDuringTransitionSequences").length())
        assertEquals(3, serialized.getJSONArray("pollRequests").getJSONObject(0).getJSONArray("requestCycleSequences").length())
    }

    private fun assertJsonArrays(value: JSONObject, fields: List<String>) {
        fields.forEach { field ->
            assertTrue("$field must remain an actual JSON array", value.opt(field) is JSONArray)
        }
    }
}
