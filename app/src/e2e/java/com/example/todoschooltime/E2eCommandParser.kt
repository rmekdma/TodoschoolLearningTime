package com.example.todoschooltime

import org.json.JSONObject
import java.math.BigInteger

internal class E2eCommandValidationException(message: String) : Exception(message)

internal enum class FakeReportOutcome {
    SUCCESS,
    TEMPORARY_FAILURE,
    AUTHENTICATION_DENIED,
}

internal sealed interface E2eCommand {
    data object GetState : E2eCommand
    data object GetEvents : E2eCommand
    data object ClearEvents : E2eCommand
    data object Reset : E2eCommand
    data class SetReport(
        val childId: String,
        val subjectCode: String,
        val count: Int,
        val outcome: FakeReportOutcome,
        val delayMs: Int,
    ) : E2eCommand
    data class RegisterChild(val childId: String) : E2eCommand
    data class UnregisterChild(val childId: String) : E2eCommand
    data class SetInterval(val minutes: BigInteger) : E2eCommand
    data class SetGoal(val childId: String, val targets: Map<String, Int>) : E2eCommand
    data class AdvanceDate(val days: Int) : E2eCommand
}

/** Parses a small, closed command vocabulary before any app or engine state is touched. */
internal object E2eCommandParser {
    private const val VERSION = 1
    const val MAX_DELAY_MS = 30_000

    fun parse(raw: String): E2eCommand {
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            reject("request must be a JSON object")
        }
        val command = string(root, "command")
        return when (command) {
            "getState" -> root.exactKeys("version", "command").validateVersion().let { E2eCommand.GetState }
            "getEvents" -> root.exactKeys("version", "command").validateVersion().let { E2eCommand.GetEvents }
            "clearEvents" -> root.exactKeys("version", "command").validateVersion().let { E2eCommand.ClearEvents }
            "reset" -> root.exactKeys("version", "command").validateVersion().let { E2eCommand.Reset }
            "setReport" -> {
                root.exactKeys("version", "command", "child", "subject", "count", "outcome", "delayMs")
                    .validateVersion()
                val childId = childId(root)
                val subjectCode = subjectCode(root, "subject")
                val count = integer(root, "count").also { requireValue(it >= 0) { "count must be non-negative" } }
                val outcome = when (string(root, "outcome")) {
                    "success" -> FakeReportOutcome.SUCCESS
                    "temporaryFailure" -> FakeReportOutcome.TEMPORARY_FAILURE
                    "authenticationDenied" -> FakeReportOutcome.AUTHENTICATION_DENIED
                    else -> reject("outcome must be success, temporaryFailure, or authenticationDenied")
                }
                val delayMs = integer(root, "delayMs")
                requireValue(delayMs in 0..MAX_DELAY_MS) { "delayMs must be between 0 and $MAX_DELAY_MS" }
                E2eCommand.SetReport(childId, subjectCode, count, outcome, delayMs)
            }
            "registerChild" -> {
                root.exactKeys("version", "command", "child").validateVersion()
                E2eCommand.RegisterChild(childId(root))
            }
            "unregisterChild" -> {
                root.exactKeys("version", "command", "child").validateVersion()
                E2eCommand.UnregisterChild(childId(root))
            }
            "setInterval" -> {
                root.exactKeys("version", "command", "minutes").validateVersion()
                val value = numberText(root, "minutes")
                requireValue(value.length <= 4_096) { "minutes has too many digits" }
                val minutes = value.toBigIntegerOrNull() ?: reject("minutes must be a JSON integer")
                requireValue(minutes.signum() > 0) { "minutes must be positive" }
                E2eCommand.SetInterval(minutes)
            }
            "setGoal" -> {
                root.exactKeys("version", "command", "child", "targets").validateVersion()
                val targetsJson = value(root, "targets") as? JSONObject ?: reject("targets must be an object")
                val targets = linkedMapOf<String, Int>()
                targetsJson.keys().asSequence().forEach { code ->
                    val subject = subjectCode(code, code)
                    val count = integer(targetsJson, code)
                    requireValue(count in 0..99) { "targets.$code must be between 0 and 99" }
                    targets[subject] = count
                }
                requireValue(targets.isNotEmpty()) { "targets must contain at least one subject" }
                E2eCommand.SetGoal(childId(root), targets)
            }
            "advanceDate" -> {
                root.exactKeys("version", "command", "days").validateVersion()
                val days = integer(root, "days")
                requireValue(days in 1..3_650) { "days must be between 1 and 3650" }
                E2eCommand.AdvanceDate(days)
            }
            else -> reject("unknown command")
        }
    }

    private fun JSONObject.validateVersion() {
        val version = integer(this, "version")
        requireValue(version == VERSION) { "unsupported version" }
    }

    private fun JSONObject.exactKeys(vararg expected: String): JSONObject {
        val actual = keys().asSequence().toSet()
        requireValue(actual == expected.toSet()) { "unexpected or missing command fields" }
        return this
    }

    private fun childId(json: JSONObject): String = string(json, "child").also {
        requireValue(it in E2eWorldStore.CHILD_IDS) { "child must be child-a or child-b" }
    }

    private fun subjectCode(json: JSONObject, key: String): String = subjectCode(string(json, key), key)

    private fun subjectCode(code: String, field: String): String = code.also {
        requireValue(it in E2eWorldStore.SUBJECT_CODES) { "$field must be hangeul, math, or english" }
    }

    private fun string(json: JSONObject, key: String): String = value(json, key) as? String
        ?: reject("$key must be a string")

    private fun integer(json: JSONObject, key: String): Int {
        val text = numberText(json, key)
        requireValue(INTEGER_PATTERN.matches(text)) { "$key must be a JSON integer" }
        return text.toIntOrNull() ?: reject("$key must fit a signed 32-bit integer")
    }

    private fun numberText(json: JSONObject, key: String): String {
        val raw = value(json, key)
        requireValue(raw is Number) { "$key must be a JSON number" }
        return raw.toString()
    }

    private fun value(json: JSONObject, key: String): Any = try {
        json.get(key)
    } catch (_: Exception) {
        reject("missing field $key")
    }

    private inline fun requireValue(condition: Boolean, message: () -> String) {
        if (!condition) reject(message())
    }

    private fun reject(message: String): Nothing = throw E2eCommandValidationException(message)

    private val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
}
