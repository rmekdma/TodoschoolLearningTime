package com.example.todoschooltime.e2eobserver

import java.net.URI
import java.net.URISyntaxException

/** Validates the loopback fixture endpoint shared by observer scenarios. */
object FixtureServerUrl {
    private const val ALLOWED_SCHEME = "http"
    private const val ALLOWED_HOST = "127.0.0.1"
    private const val MIN_ALLOWED_PORT = 1024
    private const val MAX_ALLOWED_PORT = 65535

    fun requireAllowed(value: String): String {
        val uri = try {
            URI(value)
        } catch (error: URISyntaxException) {
            throw IllegalArgumentException("Observer fixture URL must be a valid loopback URI", error)
        }

        require(
            uri.scheme == ALLOWED_SCHEME &&
                uri.host == ALLOWED_HOST &&
                uri.port in MIN_ALLOWED_PORT..MAX_ALLOWED_PORT &&
                uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null,
        ) {
            "Observer fixture URL must use http://127.0.0.1 with a port from 1024 through 65535 and no credentials, path, query, or fragment"
        }
        return value
    }
}
