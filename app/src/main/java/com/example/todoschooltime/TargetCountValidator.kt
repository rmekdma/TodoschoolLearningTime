package com.example.todoschooltime

object TargetCountValidator {
    private const val MIN_TARGET = 0
    private const val MAX_TARGET = 99

    fun parse(input: String?): Int? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()
        val num = trimmed.toIntOrNull() ?: return null
        return if (num in MIN_TARGET..MAX_TARGET) num else null
    }

    fun isValid(input: String?): Boolean = parse(input) != null
}
