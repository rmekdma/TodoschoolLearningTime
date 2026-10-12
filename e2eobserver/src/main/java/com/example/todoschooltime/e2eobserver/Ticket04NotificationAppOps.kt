package com.example.todoschooltime.e2eobserver

/** Parses the read-only `cmd appops get <package> POST_NOTIFICATION` result. */
internal object Ticket04NotificationAppOps {
    enum class Mode {
        ALLOWED,
        IGNORED,
        DEFAULT,
    }

    private val modeLine = Regex("(?im)^\\s*(uid|package)\\s+mode:\\s*POST_NOTIFICATION:\\s*([a-z_]+)\\s*$")
    private val defaultModeLine = Regex("(?im)^\\s*Default mode:\\s*([a-z_]+)\\s*$")

    fun effectiveMode(output: String): Mode {
        val modes = modeLine.findAll(output).associate { match ->
            match.groupValues[1].lowercase() to parseMode(match.groupValues[2])
        }
        val packageMode = modes["package"]
        val uidMode = modes["uid"]
        val defaultMode = defaultModeLine.find(output)?.groupValues?.get(1)?.let(::parseMode)
        return when (packageMode) {
            Mode.ALLOWED, Mode.IGNORED -> packageMode
            Mode.DEFAULT, null -> uidMode ?: defaultMode ?: packageMode
        } ?: throw IllegalArgumentException("No POST_NOTIFICATION app-op mode was present")
    }

    fun isEnabled(mode: Mode, postNotificationsPermissionGranted: Boolean): Boolean =
        mode != Mode.IGNORED && postNotificationsPermissionGranted

    private fun parseMode(value: String): Mode = when (value.lowercase()) {
        "allow" -> Mode.ALLOWED
        "ignore", "deny", "errored" -> Mode.IGNORED
        "default" -> Mode.DEFAULT
        else -> throw IllegalArgumentException("Unrecognized POST_NOTIFICATION app-op mode: $value")
    }
}
