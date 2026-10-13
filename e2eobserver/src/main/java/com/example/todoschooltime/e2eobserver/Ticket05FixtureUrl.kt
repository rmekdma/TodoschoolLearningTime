package com.example.todoschooltime.e2eobserver

import java.net.URI
import java.net.URISyntaxException

/** Validates the one local fixture endpoint the Ticket 05 observer is allowed to contact. */
object Ticket05FixtureUrl {
    private const val ALLOWED_SCHEME = "http"
    private const val ALLOWED_HOST = "127.0.0.1"
    private const val ALLOWED_PORT = 18765
    private const val ALLOWED_AUTHORITY = "$ALLOWED_HOST:$ALLOWED_PORT"

    fun requireAllowed(value: String): String {
        val uri = try {
            URI(value)
        } catch (error: URISyntaxException) {
            throw IllegalArgumentException("Ticket 05 fixture URL must be a valid loopback URI", error)
        }

        require(
            uri.scheme == ALLOWED_SCHEME &&
                uri.host == ALLOWED_HOST &&
                uri.port == ALLOWED_PORT &&
                uri.rawAuthority == ALLOWED_AUTHORITY &&
                uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null,
        ) {
            "Ticket 05 fixture URL must be exactly http://127.0.0.1:18765 without credentials, path, query, or fragment"
        }
        return value
    }
}
