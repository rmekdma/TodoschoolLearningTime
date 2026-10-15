package com.example.todoschooltime

object SubjectStatusFormatter {
    const val CHECKMARK = "✔"

    fun defaultTargetCount(subjectName: String): Int =
        TargetDefaults.defaultCount(subjectName)

    fun formatSubject(name: String, count: Int, targetCount: Int): String {
        val completed = count >= targetCount
        val prefix = if (completed) "$CHECKMARK " else ""
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
