package com.example.todoschooltime.e2eobserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FixtureServerUrlTest {
    @Test
    fun acceptsLoopbackFixturePortsWithinTheRunnerRange() {
        val fixtureUrls = listOf(
            "http://127.0.0.1:1024",
            "http://127.0.0.1:18765",
            "http://127.0.0.1:18766",
            "http://127.0.0.1:65535",
        )

        fixtureUrls.forEach { url ->
            assertEquals(url, FixtureServerUrl.requireAllowed(url))
        }
    }

    @Test
    fun rejectsOtherAuthoritiesAndUrlComponents() {
        val invalidUrls = listOf(
            "http://127.0.0.1:18765@evil.example",
            "http://evil.example:18765",
            "https://127.0.0.1:18765",
            "http://127.0.0.1",
            "http://127.0.0.1:-1",
            "http://127.0.0.1:0",
            "http://127.0.0.1:1023",
            "http://127.0.0.1:65536",
            "http://user@127.0.0.1:18765",
            "http://127.0.0.1:18765/fixture",
            "http://127.0.0.1:18765?redirect=evil.example",
            "http://127.0.0.1:18765#fragment",
        )

        invalidUrls.forEach { url ->
            assertThrows("Expected fixture URL to be rejected: $url", IllegalArgumentException::class.java) {
                FixtureServerUrl.requireAllowed(url)
            }
        }
    }
}
