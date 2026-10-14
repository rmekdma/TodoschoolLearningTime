package com.example.todoschooltime.e2eobserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubjectRequestCycleTest {
    @Test
    fun acceptsTheExactFiveRequestHangeulForegroundCycleAfterTheBaselineCursor() {
        val requests = requests(SubjectRequestCycle.HANGEUL_PATHS, firstSequence = 8L)

        val cycle = findFirstExactSubjectRequestCycleAfter(
            requests = requests,
            afterSequence = 7L,
            expectedPaths = SubjectRequestCycle.HANGEUL_PATHS,
        )

        assertEquals(SubjectRequestCycle.HANGEUL_PATHS, cycle?.map { it.path })
        assertEquals((8L..12L).toList(), cycle?.map { it.sequence })
    }

    @Test
    fun rejectsAReportForTheWrongSubject() {
        val wrongSubject = SubjectRequestCycle.HANGEUL_PATHS.dropLast(1) + "/v3/todoschool/report/daily/english"

        assertNull(
            findFirstExactSubjectRequestCycleAfter(
                requests = requests(wrongSubject, firstSequence = 8L),
                afterSequence = 7L,
                expectedPaths = SubjectRequestCycle.HANGEUL_PATHS,
            ),
        )
    }

    @Test
    fun rejectsAMissingRequest() {
        assertNull(
            findFirstExactSubjectRequestCycleAfter(
                requests = requests(SubjectRequestCycle.HANGEUL_PATHS.dropLast(1), firstSequence = 8L),
                afterSequence = 7L,
                expectedPaths = SubjectRequestCycle.HANGEUL_PATHS,
            ),
        )
    }

    @Test
    fun rejectsAnExtraRequestBeforeTheNextCycleBoundary() {
        val extraRequest = SubjectRequestCycle.HANGEUL_PATHS + "/v3/todoschool/report/daily/math"

        assertNull(
            findFirstExactSubjectRequestCycleAfter(
                requests = requests(extraRequest, firstSequence = 8L),
                afterSequence = 7L,
                expectedPaths = SubjectRequestCycle.HANGEUL_PATHS,
            ),
        )
    }

    @Test
    fun rejectsRequestsPresentedInTheWrongOrder() {
        val wrongOrder = SubjectRequestCycle.HANGEUL_PATHS.toMutableList().apply {
            val firstCatalog = this[1]
            this[1] = this[2]
            this[2] = firstCatalog
        }

        assertNull(
            findFirstExactSubjectRequestCycleAfter(
                requests = requests(wrongOrder, firstSequence = 8L),
                afterSequence = 7L,
                expectedPaths = SubjectRequestCycle.HANGEUL_PATHS,
            ),
        )
    }

    @Test
    fun rejectsASequenceGapInsideTheObservedCycle() {
        val requests = requests(SubjectRequestCycle.HANGEUL_PATHS, firstSequence = 8L)
            .map { if (it.path == "/v3/math/user") it.copy(sequence = it.sequence + 1L) else it }

        assertNull(
            findFirstExactSubjectRequestCycleAfter(
                requests = requests,
                afterSequence = 7L,
                expectedPaths = SubjectRequestCycle.HANGEUL_PATHS,
            ),
        )
    }

    private fun requests(paths: List<String>, firstSequence: Long) = paths.mapIndexed { index, path ->
        RequestPathEvidence(sequence = firstSequence + index, path = path)
    }
}
