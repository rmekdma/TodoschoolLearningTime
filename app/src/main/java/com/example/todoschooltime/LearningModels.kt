package com.example.todoschooltime

data class SubjectProgress(
    val name: String,
    val count: Int,
)

data class ChildLearningInfo(
    val name: String,
    val minutes: Int,
    val subjects: List<SubjectProgress>,
)

data class LearningResult(
    val checkedAt: String,
    val children: List<ChildLearningInfo>,
)

object TargetDefaults {
    val DEFAULT_COUNTS: Map<String, Int> = mapOf(
        "한글" to 6,
        "수학" to 5,
        "영어" to 8,
    )

    fun defaultCount(subjectName: String): Int =
        DEFAULT_COUNTS[subjectName] ?: 5
}
