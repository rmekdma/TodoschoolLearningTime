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

    @Test
    fun immediateSocketCloseWithinObservedPermissionTransitionIsCancellationConsistent() {
        val evidence = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(
                deviceStartMs = 10_000,
                deviceEndMs = 10_010,
                serverSnapshotMs = 10_005,
            ),
            firstOff = sample(
                deviceStartMs = 10_100,
                deviceEndMs = 10_110,
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                inFlight = false,
                clientConnected = false,
                serverSnapshotMs = 10_105,
                disconnectMs = 10_050,
            ),
            expectedPid = "20478",
        )

        assertEquals(Ticket04BoundaryEvidence.CANCELLATION_CONSISTENT, evidence.classification)
        assertTrue(evidence.qualifies)
        assertEquals(10_000L, evidence.requestAgeAtLastLiveMs)
        assertEquals(10_045L, evidence.disconnectAgeMs)
    }

    @Test
    fun rejectsDisconnectOutsideObservedTransitionInterval() {
        val evidence = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(serverSnapshotMs = 10_005, deviceStartMs = 10_000, deviceEndMs = 10_010),
            firstOff = sample(
                serverSnapshotMs = 10_105,
                deviceStartMs = 10_100,
                deviceEndMs = 10_110,
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                inFlight = false,
                clientConnected = false,
                disconnectMs = 10_004,
            ),
            expectedPid = "20478",
        )
        assertEquals(Ticket04BoundaryEvidence.DISCONNECT_OUTSIDE_TRANSITION, evidence.classification)
        assertFalse(evidence.qualifies)
    }

    @Test
    fun rejectsEofBeforeLastPermissionOnLiveObservationAndMismatchedRequest() {
        val eofBeforeLive = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(serverSnapshotMs = 10_005, deviceStartMs = 10_000, deviceEndMs = 10_010),
            firstOff = sample(
                serverSnapshotMs = 10_105,
                deviceStartMs = 10_100,
                deviceEndMs = 10_110,
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                inFlight = false,
                clientConnected = false,
                disconnectMs = 10_004,
            ),
            expectedPid = "20478",
        )
        assertEquals(Ticket04BoundaryEvidence.DISCONNECT_OUTSIDE_TRANSITION, eofBeforeLive.classification)

        val mismatchedRequest = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(sequence = 5),
            firstOff = sample(
                sequence = 6,
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                inFlight = false,
                clientConnected = false,
                disconnectMs = 10_050,
            ),
            expectedPid = "20478",
        )
        assertEquals(Ticket04BoundaryEvidence.INVALID_CLOCK_SAMPLE, mismatchedRequest.classification)
    }

    @Test
    fun rejectsExpiredRequestAtLastLiveSample() {
        val evidence = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(
                serverSnapshotMs = 25_000,
                receivedMs = 11_000,
                deviceStartMs = 10_000,
                deviceEndMs = 10_010,
            ),
            firstOff = sample(
                serverSnapshotMs = 25_100,
                receivedMs = 11_000,
                deviceStartMs = 10_100,
                deviceEndMs = 10_110,
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                inFlight = false,
                clientConnected = false,
                disconnectMs = 25_050,
            ),
            expectedPid = "20478",
        )
        assertEquals(Ticket04BoundaryEvidence.REQUEST_OUTSIDE_EVIDENCE_WINDOW, evidence.classification)
        assertFalse(evidence.qualifies)
    }

    @Test
    fun rejectsReadTimeoutOrLateDisconnect() {
        val evidence = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(serverSnapshotMs = 10_005, deviceStartMs = 10_000, deviceEndMs = 10_010),
            firstOff = sample(
                serverSnapshotMs = 26_000,
                deviceStartMs = 10_100,
                deviceEndMs = 10_110,
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                inFlight = false,
                clientConnected = false,
                disconnectMs = 25_100,
            ),
            expectedPid = "20478",
        )
        assertEquals(Ticket04BoundaryEvidence.DISCONNECT_AT_OR_AFTER_TIMEOUT, evidence.classification)
        assertFalse(evidence.qualifies)
    }

    @Test
    fun rejectsRequestAlreadyClosedBeforeAnyLiveProbe() {
        val evidence = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(inFlight = false, clientConnected = false),
            firstOff = sample(
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                inFlight = false,
                clientConnected = false,
            ),
            expectedPid = "20478",
        )
        assertEquals(Ticket04BoundaryEvidence.NO_LIVE_REQUEST_BEFORE_OFF, evidence.classification)
    }

    @Test
    fun rejectsAbsenceOfStablePermissionOnLiveSample() {
        val evidence = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(permissionAfter = false),
            firstOff = sample(
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                inFlight = false,
                clientConnected = false,
            ),
            expectedPid = "20478",
        )
        assertEquals(Ticket04BoundaryEvidence.NO_LIVE_REQUEST_BEFORE_OFF, evidence.classification)
    }

    @Test
    fun rejectsPidRestartAcrossAccessibilityBoundary() {
        val evidence = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(),
            firstOff = sample(
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                pid = "20479",
                inFlight = false,
                clientConnected = false,
                disconnectMs = 10_050,
            ),
            expectedPid = "20478",
        )
        assertEquals(Ticket04BoundaryEvidence.PRODUCT_PID_CHANGED, evidence.classification)
    }

    @Test
    fun rejectsUnboundedObservationIntervalAndHighRoundTrip() {
        val wide = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(),
            firstOff = sample(
                deviceStartMs = 12_000,
                deviceEndMs = 12_010,
                serverSnapshotMs = 10_105,
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                inFlight = false,
                clientConnected = false,
                disconnectMs = 10_050,
            ),
            expectedPid = "20478",
        )
        assertEquals(Ticket04BoundaryEvidence.TRANSITION_INTERVAL_TOO_WIDE, wide.classification)

        val highRtt = Ticket04BoundaryEvidence.classifyAccessibilityTransition(
            lastLive = sample(deviceStartMs = 10_000, deviceEndMs = 10_600, fixtureRttMs = 600),
            firstOff = sample(
                permissionBefore = false,
                permissionAfter = false,
                lease = false,
                inFlight = false,
                clientConnected = false,
                disconnectMs = 10_050,
            ),
            expectedPid = "20478",
        )
        assertEquals(Ticket04BoundaryEvidence.CLOCK_PROBE_TOO_SLOW, highRtt.classification)
    }

    private fun sample(
        caseId: String = "accessibility-revocation",
        sequence: Long = 5L,
        deviceStartMs: Long = 10_000,
        deviceEndMs: Long = 10_010,
        fixtureRttMs: Long = 10,
        serverSnapshotMs: Long = 10_005,
        receivedMs: Long = 5,
        permissionBefore: Boolean = true,
        permissionAfter: Boolean = true,
        lease: Boolean = true,
        pid: String = "20478",
        inFlight: Boolean = true,
        clientConnected: Boolean = true,
        disconnectMs: Long? = null,
        disconnectSignal: String? = null,
    ) = Ticket04BoundaryEvidence.AccessibilityBoundarySample(
        caseId = caseId,
        requestSequence = sequence,
        deviceStartElapsedRealtimeNs = deviceStartMs * 1_000_000,
        deviceEndElapsedRealtimeNs = deviceEndMs * 1_000_000,
        permissionEnabledBefore = permissionBefore,
        permissionEnabledAfter = permissionAfter,
        serviceLeaseAttached = lease,
        productPid = pid,
        requestInFlight = inFlight,
        serverResponseHeld = true,
        clientConnected = clientConnected,
        fixtureRequestRttNs = fixtureRttMs * 1_000_000,
        serverSnapshotMonotonicNs = serverSnapshotMs * 1_000_000,
        serverReceivedMonotonicNs = receivedMs * 1_000_000,
        clientDisconnectMonotonicNs = disconnectMs?.times(1_000_000),
        clientDisconnectSignal = disconnectSignal ?: if (disconnectMs == null) null else "eof",
    )
}
