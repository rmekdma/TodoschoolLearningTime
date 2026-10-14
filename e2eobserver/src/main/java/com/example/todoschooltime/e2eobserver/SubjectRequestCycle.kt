package com.example.todoschooltime.e2eobserver

internal object SubjectRequestCycle {
    val HANGEUL_PATHS = listOf(
        "/v3/account/signin",
        "/v3/todohangeul/user",
        "/v3/math/user",
        "/v3/english/user",
        "/v3/todoschool/report/daily/hangeul",
    )
}

internal data class RequestPathEvidence(
    val sequence: Long,
    val path: String,
)

/** Finds the first whole cycle after a cursor, stopping at the next sign-in boundary. */
internal fun findFirstExactSubjectRequestCycleAfter(
    requests: List<RequestPathEvidence>,
    afterSequence: Long,
    expectedPaths: List<String>,
): List<RequestPathEvidence>? {
    require(expectedPaths.isNotEmpty()) { "An expected request cycle must contain at least one path" }

    val later = requests.asSequence()
        .filter { it.sequence > afterSequence }
        .sortedBy { it.sequence }
        .toList()
    if (later.firstOrNull()?.path != expectedPaths.first()) return null

    val nextCycleStart = later.indices.drop(1).firstOrNull { later[it].path == expectedPaths.first() } ?: later.size
    val candidate = later.subList(0, nextCycleStart)
    if (candidate.map { it.path } != expectedPaths) return null
    if (candidate.zipWithNext().any { (before, after) -> after.sequence != before.sequence + 1L }) return null
    return candidate
}
