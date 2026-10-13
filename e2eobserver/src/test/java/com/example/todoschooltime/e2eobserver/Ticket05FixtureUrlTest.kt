package com.example.todoschooltime.e2eobserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Ticket05FixtureUrlTest {
    @Test
    fun acceptsOnlyTheConfiguredLoopbackFixtureBaseUrl() {
        val fixtureUrl = "http://127.0.0.1:18765"

        assertEquals(fixtureUrl, Ticket05FixtureUrl.requireAllowed(fixtureUrl))
    }

    @Test
    fun rejectsOtherAuthoritiesAndUrlComponents() {
        val invalidUrls = listOf(
            "http://127.0.0.1:18765@evil.example",
            "http://evil.example:18765",
            "https://127.0.0.1:18765",
            "http://127.0.0.1:18766",
            "http://user@127.0.0.1:18765",
            "http://127.0.0.1:18765/fixture",
            "http://127.0.0.1:18765?redirect=evil.example",
            "http://127.0.0.1:18765#fragment",
        )

        invalidUrls.forEach { url ->
            assertThrows("Expected fixture URL to be rejected: $url", IllegalArgumentException::class.java) {
                Ticket05FixtureUrl.requireAllowed(url)
            }
        }
    }
}
