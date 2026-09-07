package com.example.todoschooltime

object SubjectStatusFormatter {
    val DEFAULT_TARGET_COUNTS: Map<String, Int> = mapOf(
        "한글" to 6,
        "수학" to 5,
        "영어" to 8,
    )

    fun defaultTargetCount(subjectName: String): Int =
        DEFAULT_TARGET_COUNTS[subjectName] ?: 5

    fun formatSubject(name: String, count: Int, targetCount: Int): String {
        val completed = count >= targetCount
        val prefix = if (completed) "✔ " else ""
        return "$prefix$name ($count/$targetCount)"
    }

    fun formatStatus(
        subjects: List<SubjectProgress>,
        targetCounts: (subjectName: String) -> Int = { defaultTargetCount(it) },
    ): String {
        return subjects.joinToString(", ") { subject ->
            formatSubject(subject.name, subject.count, targetCounts(subject.name))
        }
    }
}
