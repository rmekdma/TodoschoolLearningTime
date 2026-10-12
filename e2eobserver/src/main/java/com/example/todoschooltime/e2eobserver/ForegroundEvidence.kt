package com.example.todoschooltime.e2eobserver

internal data class ForegroundEventEvidence(
    val sequence: Long,
    val packageName: String,
)

/** Returns whether the latest observed event after a transition cursor matches the active window. */
internal fun latestForegroundEventMatches(
    events: List<ForegroundEventEvidence>,
    expectedPackage: String,
    afterSequence: Long,
): Boolean = events.asSequence()
    .filter { it.sequence > afterSequence }
    .maxByOrNull { it.sequence }
    ?.packageName == expectedPackage

/** Reports whether every event observed since a snapshot still belongs to the expected package. */
internal fun foregroundEventsMatchPackageAfterCursor(
    events: List<ForegroundEventEvidence>,
    expectedPackage: String,
    afterSequence: Long,
): Boolean = events.none { it.sequence > afterSequence && it.packageName != expectedPackage }

/** Returns events after a cursor, rejecting any foreground package change inside the observed window. */
internal fun foregroundEventsAfterCursor(
    events: List<ForegroundEventEvidence>,
    expectedPackage: String,
    afterSequence: Long,
    allowNoNewEvents: Boolean = false,
): List<ForegroundEventEvidence> {
    val startingEvent = events.lastOrNull { it.sequence <= afterSequence }
    val observed = events.filter { it.sequence > afterSequence }
    require(observed.isNotEmpty() || (allowNoNewEvents && startingEvent?.packageName == expectedPackage)) {
        "No actual foreground event was observed for $expectedPackage after sequence $afterSequence"
    }
    require(observed.all { it.packageName == expectedPackage }) {
        "The actual foreground event stream left $expectedPackage"
    }
    return observed
}
