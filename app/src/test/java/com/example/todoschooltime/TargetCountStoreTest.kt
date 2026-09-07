package com.example.todoschooltime

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TargetCountStoreTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearFlag) data.clear()
                temp.forEach { (k, v) ->
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var store: TargetCountStore

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        store = TargetCountStore(fakePrefs)
    }

    @Test
    fun testGetDefaultWhenNotSaved() {
        assertEquals(emptyMap<String, Int>(), store.getTargetCounts("철수"))
        assertEquals(6, store.getTargetCount("철수", "한글"))
        assertEquals(5, store.getTargetCount("철수", "수학"))
        assertEquals(8, store.getTargetCount("철수", "영어"))
        assertEquals(5, store.getTargetCount("철수", "기타과목"))
    }

    @Test
    fun testSaveAndGetTargetCounts() {
        val targets = mapOf("한글" to 10, "수학" to 0, "영어" to 15)
        store.saveTargetCounts("철수", targets)

        val retrieved = store.getTargetCounts("철수")
        assertEquals(10, retrieved["한글"])
        assertEquals(0, retrieved["수학"])
        assertEquals(15, retrieved["영어"])

        assertEquals(10, store.getTargetCount("철수", "한글"))
        assertEquals(0, store.getTargetCount("철수", "수학"))
        assertEquals(15, store.getTargetCount("철수", "영어"))
    }

    @Test
    fun testMultipleChildrenIsolated() {
        store.saveTargetCounts("철수", mapOf("수학" to 7))
        store.saveTargetCounts("영희", mapOf("수학" to 3))

        assertEquals(7, store.getTargetCount("철수", "수학"))
        assertEquals(3, store.getTargetCount("영희", "수학"))
        assertEquals(6, store.getTargetCount("철수", "한글")) // default
        assertEquals(6, store.getTargetCount("영희", "한글")) // default
    }

    @Test
    fun testCorruptedJsonFallsBackGracefully() {
        fakePrefs.edit().putString("민수", "invalid-json-string").apply()
        assertEquals(emptyMap<String, Int>(), store.getTargetCounts("민수"))
        assertEquals(6, store.getTargetCount("민수", "한글"))
    }
}
