package com.example.todoschooltime

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorStoreTest {
    @Test
    fun registrationsAndUnboundedPositiveIntervalRestoreByAccountWithoutSavingBaselineState() {
        val preferences = InMemorySharedPreferences()
        val store = SharedPreferencesMonitorStore(preferences)
        val veryLargeInterval = BigInteger("12345678901234567890123456789012345678901234567890")
        store.saveRegisteredChildren("account/한글", setOf("철수", "영희"))
        store.saveIntervalMinutes("account/한글", veryLargeInterval)
        store.saveRegisteredChildren("account-other", setOf("민수"))
        store.saveIntervalMinutes("account-other", BigInteger.valueOf(7))

        val restored = SharedPreferencesMonitorStore(preferences)
        assertEquals(setOf("철수", "영희"), restored.registeredChildren("account/한글"))
        assertEquals(veryLargeInterval, restored.intervalMinutes("account/한글"))
        assertEquals(setOf("민수"), restored.registeredChildren("account-other"))
        assertEquals(BigInteger.valueOf(7), restored.intervalMinutes("account-other"))
        assertEquals(4, preferences.all.size)
        assertFalse(preferences.all.keys.any { key ->
            key.contains("baseline", ignoreCase = true) ||
                key.contains("count", ignoreCase = true) ||
                key.contains("completion", ignoreCase = true)
        })
    }

    @Test
    fun notificationIdsRemainStableAndDistinctAcrossAccountsAndChildren() {
        val preferences = InMemorySharedPreferences()
        val allocator = SharedPreferencesNotificationIdAllocator(preferences)
        val first = allocator.idFor("account-A", "kid|1")
        val second = allocator.idFor("account-A", "kid/1")
        val third = allocator.idFor("account-B", "kid|1")

        assertEquals(3, setOf(first, second, third).size)
        assertEquals(first, SharedPreferencesNotificationIdAllocator(preferences).idFor("account-A", "kid|1"))
    }
}
