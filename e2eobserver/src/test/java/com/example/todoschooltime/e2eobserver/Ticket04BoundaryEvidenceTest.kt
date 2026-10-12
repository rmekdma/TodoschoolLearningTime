package com.example.todoschooltime.e2eobserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ticket04BoundaryEvidenceTest {
    @Test
    fun notificationPermissionOffAfterClientTimedOutIsRecordedWithoutClaimingCancellation() {
        val classification = Ticket04BoundaryEvidence.classify(
            requestAgeAtBoundaryMs = 15_200L,
            requestStillInFlight = false,
            clientSocketOpen = false,
        )

        assertEquals(Ticket04BoundaryEvidence.NO_CLIENT_REQUEST_AT_BOUNDARY, classification)
        assertFalse(Ticket04BoundaryEvidence.isLivePreTimeoutBoundary(classification))
    }

    @Test
    fun liveRequestAfterEvidenceCutoffIsRecordedButDoesNotQualifyAsStrictBoundary() {
        val classification = Ticket04BoundaryEvidence.classify(
            requestAgeAtBoundaryMs = 14_000L,
            requestStillInFlight = true,
            clientSocketOpen = true,
        )

        assertEquals(Ticket04BoundaryEvidence.LIVE_REQUEST_OUTSIDE_EVIDENCE_WINDOW, classification)
        assertFalse(Ticket04BoundaryEvidence.isLivePreTimeoutBoundary(classification))
    }

    @Test
    fun liveRequestInsideEvidenceCutoffQualifiesForAccessibilityBoundary() {
        val classification = Ticket04BoundaryEvidence.classify(
            requestAgeAtBoundaryMs = 12_000L,
            requestStillInFlight = true,
            clientSocketOpen = true,
        )

        assertEquals(Ticket04BoundaryEvidence.LIVE_REQUEST_WITHIN_EVIDENCE_WINDOW, classification)
        assertTrue(Ticket04BoundaryEvidence.isLivePreTimeoutBoundary(classification))
    }

    @Test
    fun negativeRequestAgeIsNotClassifiedAsValidEvidence() {
        val classification = Ticket04BoundaryEvidence.classify(
            requestAgeAtBoundaryMs = -1L,
            requestStillInFlight = true,
            clientSocketOpen = true,
        )

        assertEquals(Ticket04BoundaryEvidence.INVALID_REQUEST_AGE, classification)
        assertFalse(Ticket04BoundaryEvidence.isLivePreTimeoutBoundary(classification))
    }
}
