package com.example.todoschooltime.e2eobserver

/** Classifies the request state at a manual permission boundary without inferring its cause. */
internal object PermissionBoundaryEvidence {
    const val INVALID_REQUEST_AGE = "invalid-request-age"
    const val NO_CLIENT_REQUEST_AT_BOUNDARY = "no-client-request-at-permission-boundary"
    const val CLIENT_SOCKET_CLOSED_AT_BOUNDARY = "client-socket-closed-at-permission-boundary"
    const val LIVE_REQUEST_WITHIN_EVIDENCE_WINDOW = "client-request-in-flight-with-open-socket-within-evidence-window"
    const val LIVE_REQUEST_OUTSIDE_EVIDENCE_WINDOW = "client-request-in-flight-with-open-socket-outside-evidence-window"
    const val CANCELLATION_CONSISTENT = "cancellation-consistent-within-observed-transition-interval"
    const val NO_LIVE_REQUEST_BEFORE_OFF = "no-stable-live-request-before-permission-off"
    const val PRODUCT_PID_CHANGED = "product-pid-changed-across-accessibility-boundary"
    const val INVALID_CLOCK_SAMPLE = "invalid-monotonic-clock-sample"
    const val CLOCK_PROBE_TOO_SLOW = "clock-probe-round-trip-too-slow"
    const val TRANSITION_INTERVAL_TOO_WIDE = "permission-transition-observation-interval-too-wide"
    const val REQUEST_OUTSIDE_EVIDENCE_WINDOW = "last-live-request-sample-outside-13.5-second-evidence-window"
    const val DISCONNECT_AT_OR_AFTER_TIMEOUT = "client-disconnect-at-or-after-15-second-read-timeout"
    const val DISCONNECT_OUTSIDE_TRANSITION = "client-disconnect-outside-observed-permission-transition-interval"
    const val INVALID_DISCONNECT = "client-disconnect-event-missing-or-unrecognized"
    const val NO_STABLE_OFF_SAMPLE = "permission-off-or-service-lease-off-not-stable"
    const val CLIENT_STILL_OPEN_AFTER_OFF = "client-request-still-open-after-permission-off"

    const val PRE_TIMEOUT_EVIDENCE_WINDOW_MS = 13_500L
    const val CLIENT_READ_TIMEOUT_MS = 15_000L
    const val MAX_CLOCK_PROBE_RTT_MS = 500L
    const val MAX_TRANSITION_INTERVAL_MS = 1_500L

    data class AccessibilityBoundarySample(
        val caseId: String,
        val requestSequence: Long,
        val deviceStartElapsedRealtimeNs: Long,
        val deviceEndElapsedRealtimeNs: Long,
        val permissionEnabledBefore: Boolean,
        val permissionEnabledAfter: Boolean,
        val serviceLeaseAttached: Boolean,
        val productPid: String,
        val requestInFlight: Boolean,
        val serverResponseHeld: Boolean,
        val clientConnected: Boolean,
        val fixtureRequestRttNs: Long,
        val serverSnapshotMonotonicNs: Long,
        val serverReceivedMonotonicNs: Long,
        val clientDisconnectMonotonicNs: Long?,
        val clientDisconnectSignal: String?,
    )

    data class AccessibilityBoundaryDecision(
        val classification: String,
        val qualifies: Boolean,
        val requestAgeAtLastLiveMs: Long?,
        val disconnectAgeMs: Long?,
        val transitionIntervalMs: Long?,
    )

    /**
     * Accepts only a stable ON/live sample followed by a stable OFF/closed sample
     * with EOF inside the fixture's monotonic interval. The interval supports a
     * cancellation-consistent observation; it does not prove the permission
     * change caused the close.
     */
    fun classifyAccessibilityTransition(
        lastLive: AccessibilityBoundarySample?,
        firstOff: AccessibilityBoundarySample,
        expectedPid: String,
    ): AccessibilityBoundaryDecision {
        val live = lastLive ?: return decision(NO_LIVE_REQUEST_BEFORE_OFF)
        if (!live.permissionEnabledBefore || !live.permissionEnabledAfter ||
            !live.serviceLeaseAttached || !live.requestInFlight || !live.serverResponseHeld ||
            !live.clientConnected) {
            return decision(NO_LIVE_REQUEST_BEFORE_OFF)
        }
        if (firstOff.permissionEnabledBefore || firstOff.permissionEnabledAfter || firstOff.serviceLeaseAttached) {
            return decision(NO_STABLE_OFF_SAMPLE)
        }
        if (!firstOff.serverResponseHeld) return decision(INVALID_DISCONNECT)
        if (firstOff.requestInFlight || firstOff.clientConnected) return decision(CLIENT_STILL_OPEN_AFTER_OFF)
        if (live.caseId.isBlank() || live.caseId != firstOff.caseId || live.requestSequence <= 0L ||
            live.requestSequence != firstOff.requestSequence) {
            return decision(INVALID_CLOCK_SAMPLE)
        }
        if (expectedPid.isBlank() || live.productPid != expectedPid || firstOff.productPid != expectedPid) {
            return decision(PRODUCT_PID_CHANGED)
        }

        val liveRttNs = lastLive.fixtureRequestRttNs
        val offRttNs = firstOff.fixtureRequestRttNs
        val transitionNs = firstOff.deviceStartElapsedRealtimeNs - live.deviceEndElapsedRealtimeNs
        if (live.deviceStartElapsedRealtimeNs < 0L ||
            live.deviceEndElapsedRealtimeNs < live.deviceStartElapsedRealtimeNs ||
            firstOff.deviceStartElapsedRealtimeNs < 0L ||
            firstOff.deviceEndElapsedRealtimeNs < firstOff.deviceStartElapsedRealtimeNs ||
            liveRttNs < 0L || offRttNs < 0L ||
            live.serverSnapshotMonotonicNs < 0L ||
            firstOff.serverSnapshotMonotonicNs < 0L ||
            live.serverReceivedMonotonicNs < 0L ||
            live.serverSnapshotMonotonicNs < live.serverReceivedMonotonicNs ||
            firstOff.serverReceivedMonotonicNs != live.serverReceivedMonotonicNs ||
            firstOff.serverSnapshotMonotonicNs < live.serverSnapshotMonotonicNs ||
            firstOff.serverSnapshotMonotonicNs < firstOff.serverReceivedMonotonicNs) {
            return decision(INVALID_CLOCK_SAMPLE)
        }
        if (liveRttNs > MAX_CLOCK_PROBE_RTT_MS * 1_000_000L ||
            offRttNs > MAX_CLOCK_PROBE_RTT_MS * 1_000_000L) {
            return decision(CLOCK_PROBE_TOO_SLOW)
        }
        if (transitionNs < 0L || transitionNs > MAX_TRANSITION_INTERVAL_MS * 1_000_000L) {
            return decision(TRANSITION_INTERVAL_TOO_WIDE)
        }

        val requestAgeAtLastLiveMs =
            (live.serverSnapshotMonotonicNs - live.serverReceivedMonotonicNs) / 1_000_000L
        if (requestAgeAtLastLiveMs < 0L || requestAgeAtLastLiveMs >= PRE_TIMEOUT_EVIDENCE_WINDOW_MS) {
            return decision(REQUEST_OUTSIDE_EVIDENCE_WINDOW, requestAgeAtLastLiveMs)
        }

        val disconnectNs = firstOff.clientDisconnectMonotonicNs
        val disconnectSignal = firstOff.clientDisconnectSignal
        if (disconnectNs == null || disconnectSignal !in setOf("eof", "socket-exception", "socket-error") ||
            disconnectNs <= live.serverSnapshotMonotonicNs ||
            disconnectNs > firstOff.serverSnapshotMonotonicNs) {
            return decision(
                classification = DISCONNECT_OUTSIDE_TRANSITION,
                requestAgeAtLastLiveMs = requestAgeAtLastLiveMs,
                transitionIntervalMs = transitionNs / 1_000_000L,
            )
        }
        val disconnectAgeMs = (disconnectNs - firstOff.serverReceivedMonotonicNs) / 1_000_000L
        if (disconnectAgeMs < 0L || disconnectAgeMs >= CLIENT_READ_TIMEOUT_MS) {
            return decision(
                classification = DISCONNECT_AT_OR_AFTER_TIMEOUT,
                requestAgeAtLastLiveMs = requestAgeAtLastLiveMs,
                disconnectAgeMs = disconnectAgeMs,
                transitionIntervalMs = transitionNs / 1_000_000L,
            )
        }
        return AccessibilityBoundaryDecision(
            classification = CANCELLATION_CONSISTENT,
            qualifies = true,
            requestAgeAtLastLiveMs = requestAgeAtLastLiveMs,
            disconnectAgeMs = disconnectAgeMs,
            transitionIntervalMs = transitionNs / 1_000_000L,
        )
    }

    private fun decision(
        classification: String,
        requestAgeAtLastLiveMs: Long? = null,
        disconnectAgeMs: Long? = null,
        transitionIntervalMs: Long? = null,
    ) = AccessibilityBoundaryDecision(
        classification = classification,
        qualifies = false,
        requestAgeAtLastLiveMs = requestAgeAtLastLiveMs,
        disconnectAgeMs = disconnectAgeMs,
        transitionIntervalMs = transitionIntervalMs,
    )

    fun classify(
        requestAgeAtBoundaryMs: Long,
        requestStillInFlight: Boolean,
        clientSocketOpen: Boolean,
    ): String {
        if (requestAgeAtBoundaryMs < 0L) return INVALID_REQUEST_AGE
        if (!requestStillInFlight) return NO_CLIENT_REQUEST_AT_BOUNDARY
        if (!clientSocketOpen) return CLIENT_SOCKET_CLOSED_AT_BOUNDARY
        return if (requestAgeAtBoundaryMs < PRE_TIMEOUT_EVIDENCE_WINDOW_MS) {
            LIVE_REQUEST_WITHIN_EVIDENCE_WINDOW
        } else {
            LIVE_REQUEST_OUTSIDE_EVIDENCE_WINDOW
        }
    }

    fun isLivePreTimeoutBoundary(classification: String): Boolean =
        classification == LIVE_REQUEST_WITHIN_EVIDENCE_WINDOW
}
