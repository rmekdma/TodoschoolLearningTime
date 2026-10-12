package com.example.todoschooltime.e2eobserver

/** Classifies the request state at a manual permission boundary without inferring its cause. */
internal object Ticket04BoundaryEvidence {
    const val INVALID_REQUEST_AGE = "invalid-request-age"
    const val NO_CLIENT_REQUEST_AT_BOUNDARY = "no-client-request-at-permission-boundary"
    const val CLIENT_SOCKET_CLOSED_AT_BOUNDARY = "client-socket-closed-at-permission-boundary"
    const val LIVE_REQUEST_WITHIN_EVIDENCE_WINDOW = "client-request-in-flight-with-open-socket-within-evidence-window"
    const val LIVE_REQUEST_OUTSIDE_EVIDENCE_WINDOW = "client-request-in-flight-with-open-socket-outside-evidence-window"

    const val PRE_TIMEOUT_EVIDENCE_WINDOW_MS = 13_500L

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
