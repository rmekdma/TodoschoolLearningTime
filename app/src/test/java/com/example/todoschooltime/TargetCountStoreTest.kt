package com.example.todoschooltime

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TargetCountStoreTest {

    private lateinit var fakePrefs: InMemorySharedPreferences
    private lateinit var store: TargetCountStore

    @Before
    fun setUp() {
        fakePrefs = InMemorySharedPreferences()
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
