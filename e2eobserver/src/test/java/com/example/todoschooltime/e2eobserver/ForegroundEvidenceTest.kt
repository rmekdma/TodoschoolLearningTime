package com.example.todoschooltime.e2eobserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ForegroundEvidenceTest {
    @Test
    fun recognizesSettledForegroundOnlyWhenTheLatestTransitionEventMatchesTheActivePackage() {
        val homeAfterTransientSubject = listOf(
            ForegroundEventEvidence(360, "com.android.launcher3"),
            ForegroundEventEvidence(361, "com.enuma.todomath"),
            ForegroundEventEvidence(362, "com.android.launcher3"),
            ForegroundEventEvidence(363, "com.android.launcher3"),
        )

        assertEquals(
            true,
            latestForegroundEventMatches(homeAfterTransientSubject, "com.android.launcher3", afterSequence = 359),
        )
        assertEquals(
            false,
            latestForegroundEventMatches(homeAfterTransientSubject, "com.enuma.todomath", afterSequence = 359),
        )
        assertEquals(
            false,
            latestForegroundEventMatches(homeAfterTransientSubject, "com.android.launcher3", afterSequence = 363),
        )
        assertEquals(
            false,
            foregroundEventsMatchPackageAfterCursor(homeAfterTransientSubject, "com.android.launcher3", afterSequence = 359),
        )
        assertEquals(
            true,
            foregroundEventsMatchPackageAfterCursor(homeAfterTransientSubject, "com.android.launcher3", afterSequence = 363),
        )
    }

    @Test
    fun ignoresPackagesSeenBeforeTheStableForegroundWindowAndAllowsDuplicateEvents() {
        val events = listOf(
            ForegroundEventEvidence(181, "com.example.todoschooltime.debug"),
            ForegroundEventEvidence(182, "com.enuma.todohangeul"),
            ForegroundEventEvidence(183, "com.enuma.todohangeul"),
            ForegroundEventEvidence(184, "com.enuma.todohangeul"),
        )

        val observed = foregroundEventsAfterCursor(
            events = events,
            expectedPackage = "com.enuma.todohangeul",
            afterSequence = 181,
        )

        assertEquals(events.drop(1), observed)
    }

    @Test
    fun ignoresForegroundChangesBeforeTheStableWindowButRejectsChangesAfterIt() {
        val events = listOf(
            ForegroundEventEvidence(329, "com.enuma.todoenglish"),
            ForegroundEventEvidence(330, "com.android.launcher3"),
            ForegroundEventEvidence(331, "com.enuma.todoenglish"),
            ForegroundEventEvidence(332, "com.enuma.todoenglish"),
        )

        assertEquals(
            events.drop(2),
            foregroundEventsAfterCursor(
                events = events,
                expectedPackage = "com.enuma.todoenglish",
                afterSequence = 330,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            foregroundEventsAfterCursor(
                events = events + ForegroundEventEvidence(333, "com.android.launcher3"),
                expectedPackage = "com.enuma.todoenglish",
                afterSequence = 330,
            )
        }
    }

    @Test
    fun rejectsLeavingTheExpectedForegroundPackageAfterTheWindowStarts() {
        val events = listOf(
            ForegroundEventEvidence(182, "com.enuma.todohangeul"),
            ForegroundEventEvidence(183, "com.example.todoschooltime.debug"),
            ForegroundEventEvidence(184, "com.enuma.todohangeul"),
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            foregroundEventsAfterCursor(
                events = events,
                expectedPackage = "com.enuma.todohangeul",
                afterSequence = 181,
            )
        }

        assertEquals("The actual foreground event stream left com.enuma.todohangeul", failure.message)
    }

    @Test
    fun permitsNoNewEventsOnlyWhenTheExpectedPackageWasAlreadyForeground() {
        val stable = listOf(ForegroundEventEvidence(182, "com.enuma.todohangeul"))
        assertEquals(
            emptyList<ForegroundEventEvidence>(),
            foregroundEventsAfterCursor(stable, "com.enuma.todohangeul", 182, allowNoNewEvents = true),
        )

        val left = listOf(ForegroundEventEvidence(182, "com.example.todoschooltime.debug"))
        assertThrows(IllegalArgumentException::class.java) {
            foregroundEventsAfterCursor(left, "com.enuma.todohangeul", 182, allowNoNewEvents = true)
        }
    }
}
