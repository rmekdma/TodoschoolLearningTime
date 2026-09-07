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
