package com.example.todoschooltime.e2eobserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ticket06EligibleSubjectCycleTest {
    @Test
    fun acceptsTheExactFiveRequestHangeulForegroundCycleAfterTheBaselineCursor() {
        val requests = requests(Ticket06EligibleSubjectCycle.HANGEUL_PATHS, firstSequence = 8L)

        val cycle = findFirstExactTicket06RequestCycleAfter(
            requests = requests,
            afterSequence = 7L,
            expectedPaths = Ticket06EligibleSubjectCycle.HANGEUL_PATHS,
        )

        assertEquals(Ticket06EligibleSubjectCycle.HANGEUL_PATHS, cycle?.map { it.path })
        assertEquals((8L..12L).toList(), cycle?.map { it.sequence })
    }

    @Test
    fun rejectsAReportForTheWrongSubject() {
        val wrongSubject = Ticket06EligibleSubjectCycle.HANGEUL_PATHS.dropLast(1) + "/v3/todoschool/report/daily/english"

        assertNull(
            findFirstExactTicket06RequestCycleAfter(
                requests = requests(wrongSubject, firstSequence = 8L),
                afterSequence = 7L,
                expectedPaths = Ticket06EligibleSubjectCycle.HANGEUL_PATHS,
            ),
        )
    }

    @Test
    fun rejectsAMissingRequest() {
        assertNull(
            findFirstExactTicket06RequestCycleAfter(
                requests = requests(Ticket06EligibleSubjectCycle.HANGEUL_PATHS.dropLast(1), firstSequence = 8L),
                afterSequence = 7L,
                expectedPaths = Ticket06EligibleSubjectCycle.HANGEUL_PATHS,
            ),
        )
    }

    @Test
    fun rejectsAnExtraRequestBeforeTheNextCycleBoundary() {
        val extraRequest = Ticket06EligibleSubjectCycle.HANGEUL_PATHS + "/v3/todoschool/report/daily/math"

        assertNull(
            findFirstExactTicket06RequestCycleAfter(
                requests = requests(extraRequest, firstSequence = 8L),
                afterSequence = 7L,
                expectedPaths = Ticket06EligibleSubjectCycle.HANGEUL_PATHS,
            ),
        )
    }

    @Test
    fun rejectsRequestsPresentedInTheWrongOrder() {
        val wrongOrder = Ticket06EligibleSubjectCycle.HANGEUL_PATHS.toMutableList().apply {
            val firstCatalog = this[1]
            this[1] = this[2]
            this[2] = firstCatalog
        }

        assertNull(
            findFirstExactTicket06RequestCycleAfter(
                requests = requests(wrongOrder, firstSequence = 8L),
                afterSequence = 7L,
                expectedPaths = Ticket06EligibleSubjectCycle.HANGEUL_PATHS,
            ),
        )
    }

    @Test
    fun rejectsASequenceGapInsideTheObservedCycle() {
        val requests = requests(Ticket06EligibleSubjectCycle.HANGEUL_PATHS, firstSequence = 8L)
            .map { if (it.path == "/v3/math/user") it.copy(sequence = it.sequence + 1L) else it }

        assertNull(
            findFirstExactTicket06RequestCycleAfter(
                requests = requests,
                afterSequence = 7L,
                expectedPaths = Ticket06EligibleSubjectCycle.HANGEUL_PATHS,
            ),
        )
    }

    private fun requests(paths: List<String>, firstSequence: Long) = paths.mapIndexed { index, path ->
        Ticket06RequestPathEvidence(sequence = firstSequence + index, path = path)
    }
}
