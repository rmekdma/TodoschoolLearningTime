package com.example.todoschooltime

import android.content.SharedPreferences
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Base64

class SharedPreferencesMonitorStore(private val preferences: SharedPreferences) : MonitorStore {
    override fun registeredChildren(accountId: String): Set<String> =
        preferences.getStringSet(registrationsKey(accountId), emptySet())?.toSet().orEmpty()

    override fun saveRegisteredChildren(accountId: String, children: Set<String>) {
        preferences.edit()
            .putStringSet(registrationsKey(accountId), children.filter(String::isNotBlank).toMutableSet())
            .apply()
    }

    override fun intervalMinutes(accountId: String): BigInteger {
        val stored = preferences.getString(intervalKey(accountId), null)?.let { runCatching { BigInteger(it) }.getOrNull() }
        return stored?.takeIf { it.signum() > 0 } ?: BigInteger.ONE
    }

    override fun saveIntervalMinutes(accountId: String, minutes: BigInteger) {
        require(minutes.signum() > 0) { "조회 간격은 양의 정수여야 합니다." }
        preferences.edit().putString(intervalKey(accountId), minutes.toString()).apply()
    }

    private fun registrationsKey(accountId: String) = "registrations.${accountKey(accountId)}"
    private fun intervalKey(accountId: String) = "interval.${accountKey(accountId)}"

    private fun accountKey(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}

/** Allocates stable, collision-free Android integer IDs for the account/child notification key. */
class SharedPreferencesNotificationIdAllocator(private val preferences: SharedPreferences) {
    @Synchronized
    fun idFor(accountId: String, childName: String): Int {
        val identity = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "$accountId\u0000$childName".toByteArray(StandardCharsets.UTF_8),
        )
        val key = ID_PREFIX + identity
        preferences.getInt(key, 0).takeIf { it > 0 }?.let { return it }

        val used = preferences.all.asSequence()
            .filter { (storedKey, value) -> storedKey.startsWith(ID_PREFIX) && value is Int }
            .map { (_, value) -> value as Int }
            .toHashSet()
        var candidate = preferences.getInt(NEXT_ID_KEY, FIRST_ID).coerceAtLeast(FIRST_ID)
        while (candidate in used) {
            if (candidate == Int.MAX_VALUE) candidate = FIRST_ID else candidate += 1
            check(candidate !in used || used.size < Int.MAX_VALUE - FIRST_ID) { "Notification IDs are exhausted." }
        }
        val next = if (candidate == Int.MAX_VALUE) FIRST_ID else candidate + 1
        preferences.edit().putInt(key, candidate).putInt(NEXT_ID_KEY, next).apply()
        return candidate
    }

    private companion object {
        const val FIRST_ID = 10_000
        const val NEXT_ID_KEY = "nextId"
        const val ID_PREFIX = "notificationId."
    }
}
