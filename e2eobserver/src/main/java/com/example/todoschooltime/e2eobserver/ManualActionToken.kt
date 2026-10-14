package com.example.todoschooltime.e2eobserver

/** A control action is acknowledged only by the exact run/action token expected by its waiter. */
internal object ManualActionToken {
    fun matches(expectedToken: String, clickedToken: String?): Boolean =
        expectedToken.isNotBlank() && clickedToken == expectedToken
}
