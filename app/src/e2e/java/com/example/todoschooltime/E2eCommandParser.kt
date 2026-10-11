package com.example.todoschooltime

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
        val root = E2eStrictJsonParser(raw).parseObjectRoot()
        val command = string(root, "command")
        return when (command) {
            "getState" -> root.exactKeys("version", "command").also(::validateVersion).let { E2eCommand.GetState }
            "getEvents" -> root.exactKeys("version", "command").also(::validateVersion).let { E2eCommand.GetEvents }
            "clearEvents" -> root.exactKeys("version", "command").also(::validateVersion).let { E2eCommand.ClearEvents }
            "reset" -> root.exactKeys("version", "command").also(::validateVersion).let { E2eCommand.Reset }
            "setReport" -> {
                root.exactKeys("version", "command", "child", "subject", "count", "outcome", "delayMs")
                    .also(::validateVersion)
                val childId = childId(root)
                val subjectCode = subjectCode(root, "subject")
                val count = integer(root, "count").also {
                    requireValue(it >= 0) { "count must be non-negative" }
                }
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
                root.exactKeys("version", "command", "child").also(::validateVersion)
                E2eCommand.RegisterChild(childId(root))
            }
            "unregisterChild" -> {
                root.exactKeys("version", "command", "child").also(::validateVersion)
                E2eCommand.UnregisterChild(childId(root))
            }
            "setInterval" -> {
                root.exactKeys("version", "command", "minutes").also(::validateVersion)
                val minutes = number(root, "minutes").raw.toBigIntegerOrNull()
                    ?: reject("minutes must be a JSON integer")
                requireValue(minutes.signum() > 0) { "minutes must be positive" }
                E2eCommand.SetInterval(minutes)
            }
            "setGoal" -> {
                root.exactKeys("version", "command", "child", "targets").also(::validateVersion)
                val targetFields = objectValue(value(root, "targets"), "targets")
                val targets = linkedMapOf<String, Int>()
                targetFields.forEach { (code, targetValue) ->
                    val subject = subjectCode(code, code)
                    val count = integer(targetValue, "targets.$code")
                    requireValue(count in 0..99) { "targets.$code must be between 0 and 99" }
                    targets[subject] = count
                }
                requireValue(targets.isNotEmpty()) { "targets must contain at least one subject" }
                E2eCommand.SetGoal(childId(root), targets)
            }
            "advanceDate" -> {
                root.exactKeys("version", "command", "days").also(::validateVersion)
                val days = integer(root, "days")
                requireValue(days in 1..3_650) { "days must be between 1 and 3650" }
                E2eCommand.AdvanceDate(days)
            }
            else -> reject("unknown command")
        }
    }

    private fun validateVersion(fields: Map<String, E2eJsonValue>) {
        requireValue(integer(fields, "version") == VERSION) { "unsupported version" }
    }

    private fun Map<String, E2eJsonValue>.exactKeys(vararg expected: String): Map<String, E2eJsonValue> {
        requireValue(keys == expected.toSet()) { "unexpected or missing command fields" }
        return this
    }

    private fun childId(fields: Map<String, E2eJsonValue>): String = string(fields, "child").also {
        requireValue(it in E2eWorldStore.CHILD_IDS) { "child must be child-a or child-b" }
    }

    private fun subjectCode(fields: Map<String, E2eJsonValue>, key: String): String =
        subjectCode(string(fields, key), key)

    private fun subjectCode(code: String, field: String): String = code.also {
        requireValue(it in E2eWorldStore.SUBJECT_CODES) { "$field must be hangeul, math, or english" }
    }

    private fun string(fields: Map<String, E2eJsonValue>, key: String): String =
        string(value(fields, key), key)

    private fun string(value: E2eJsonValue, label: String): String =
        (value as? E2eJsonValue.StringValue)?.value ?: reject("$label must be a string")

    private fun integer(fields: Map<String, E2eJsonValue>, key: String): Int =
        integer(value(fields, key), key)

    private fun integer(value: E2eJsonValue, label: String): Int {
        val raw = number(value, label).raw
        requireValue(INTEGER_PATTERN.matches(raw)) { "$label must be a JSON integer" }
        return raw.toIntOrNull() ?: reject("$label must fit a signed 32-bit integer")
    }

    private fun number(fields: Map<String, E2eJsonValue>, key: String): E2eJsonValue.NumberValue =
        number(value(fields, key), key)

    private fun number(value: E2eJsonValue, label: String): E2eJsonValue.NumberValue =
        value as? E2eJsonValue.NumberValue ?: reject("$label must be a JSON number")

    private fun objectValue(value: E2eJsonValue, label: String): Map<String, E2eJsonValue> =
        (value as? E2eJsonValue.ObjectValue)?.fields ?: reject("$label must be an object")

    private fun value(fields: Map<String, E2eJsonValue>, key: String): E2eJsonValue =
        fields[key] ?: reject("missing field $key")

    private inline fun requireValue(condition: Boolean, message: () -> String) {
        if (!condition) reject(message())
    }

    private fun reject(message: String): Nothing = throw E2eCommandValidationException(message)

    private val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
}

private sealed interface E2eJsonValue {
    data class ObjectValue(val fields: Map<String, E2eJsonValue>) : E2eJsonValue
    data class ArrayValue(val values: List<E2eJsonValue>) : E2eJsonValue
    data class StringValue(val value: String) : E2eJsonValue
    data class NumberValue(val raw: String) : E2eJsonValue
    data class BooleanValue(val value: Boolean) : E2eJsonValue
    data object NullValue : E2eJsonValue
}

/** Small strict JSON reader that preserves number lexemes and rejects lenient JSONTokener syntax. */
private class E2eStrictJsonParser(private val source: String) {
    private var index = 0

    fun parseObjectRoot(): Map<String, E2eJsonValue> {
        skipWhitespace()
        val root = readObject(depth = 0)
        skipWhitespace()
        if (index != source.length) invalid()
        return root.fields
    }

    private fun readValue(depth: Int): E2eJsonValue = when (peek()) {
        '{' -> readObject(depth)
        '[' -> readArray(depth)
        '"' -> E2eJsonValue.StringValue(readString())
        't' -> readLiteral("true", E2eJsonValue.BooleanValue(true))
        'f' -> readLiteral("false", E2eJsonValue.BooleanValue(false))
        'n' -> readLiteral("null", E2eJsonValue.NullValue)
        '-', in '0'..'9' -> readNumber()
        else -> invalid()
    }

    private fun readObject(depth: Int): E2eJsonValue.ObjectValue {
        requireDepth(depth)
        expect('{')
        skipWhitespace()
        if (consume('}')) return E2eJsonValue.ObjectValue(emptyMap())
        val fields = linkedMapOf<String, E2eJsonValue>()
        while (true) {
            skipWhitespace()
            if (peek() != '"') invalid()
            val name = readString()
            if (fields.containsKey(name)) invalid()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            fields[name] = readValue(depth + 1)
            skipWhitespace()
            if (consume('}')) return E2eJsonValue.ObjectValue(fields)
            expect(',')
        }
    }

    private fun readArray(depth: Int): E2eJsonValue.ArrayValue {
        requireDepth(depth)
        expect('[')
        skipWhitespace()
        if (consume(']')) return E2eJsonValue.ArrayValue(emptyList())
        val values = mutableListOf<E2eJsonValue>()
        while (true) {
            skipWhitespace()
            values += readValue(depth + 1)
            skipWhitespace()
            if (consume(']')) return E2eJsonValue.ArrayValue(values)
            expect(',')
        }
    }

    private fun readString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> readEscape(result)
                character.code < 0x20 -> invalid()
                else -> result.append(character)
            }
        }
        invalid()
    }

    private fun readEscape(result: StringBuilder) {
        if (index >= source.length) invalid()
        when (val escaped = source[index++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                if (index + 4 > source.length) invalid()
                val codeUnit = source.substring(index, index + 4).toIntOrNull(16) ?: invalid()
                result.append(codeUnit.toChar())
                index += 4
            }
            else -> invalid()
        }
    }

    private fun readNumber(): E2eJsonValue.NumberValue {
        val start = index
        consume('-')
        if (consume('0')) {
            if (peek() in '0'..'9') invalid()
        } else {
            if (peek() !in '1'..'9') invalid()
            while (peek() in '0'..'9') index += 1
        }
        if (consume('.')) {
            if (peek() !in '0'..'9') invalid()
            while (peek() in '0'..'9') index += 1
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) consume('-')
            if (peek() !in '0'..'9') invalid()
            while (peek() in '0'..'9') index += 1
        }
        return E2eJsonValue.NumberValue(source.substring(start, index))
    }

    private fun readLiteral(literal: String, value: E2eJsonValue): E2eJsonValue {
        if (!source.startsWith(literal, index)) invalid()
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') index += 1
    }

    private fun expect(character: Char) {
        if (!consume(character)) invalid()
    }

    private fun consume(character: Char): Boolean {
        if (peek() != character) return false
        index += 1
        return true
    }

    private fun peek(): Char? = source.getOrNull(index)

    private fun requireDepth(depth: Int) {
        if (depth > MAX_NESTING_DEPTH) invalid()
    }

    private fun invalid(): Nothing = throw E2eCommandValidationException("request must be strict JSON")

    private companion object {
        const val MAX_NESTING_DEPTH = 32
    }
}
