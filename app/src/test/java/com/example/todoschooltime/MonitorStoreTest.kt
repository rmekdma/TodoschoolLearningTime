package com.example.todoschooltime

import android.content.SharedPreferences
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorStoreTest {
    @Test
    fun registrationsAndUnboundedPositiveIntervalRestoreByAccountWithoutSavingBaselineState() {
        val preferences = MapSharedPreferences()
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
        val preferences = MapSharedPreferences()
        val allocator = SharedPreferencesNotificationIdAllocator(preferences)
        val first = allocator.idFor("account-A", "kid|1")
        val second = allocator.idFor("account-A", "kid/1")
        val third = allocator.idFor("account-B", "kid|1")

        assertEquals(3, setOf(first, second, third).size)
        assertEquals(first, SharedPreferencesNotificationIdAllocator(preferences).idFor("account-A", "kid|1"))
    }

    private class MapSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                put(key, values?.toSet())
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor = put(key, null)
            override fun clear(): SharedPreferences.Editor { pending["__clear__"] = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (pending["__clear__"] == true) values.clear()
                pending.filterKeys { it != "__clear__" }.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                pending.clear()
            }
            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
        }
    }
}
