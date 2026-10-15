package com.example.todoschooltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetCountValidatorTest {

    @Test
    fun testValidNumbers() {
        assertEquals(0, TargetCountValidator.parse("0"))
        assertEquals(1, TargetCountValidator.parse("1"))
        assertEquals(50, TargetCountValidator.parse("50"))
        assertEquals(99, TargetCountValidator.parse("99"))
        assertEquals(6, TargetCountValidator.parse(" 6 "))
    }

    @Test
    fun testBlankIsInvalid() {
        assertNull(TargetCountValidator.parse(""))
        assertNull(TargetCountValidator.parse("   "))
    }

    @Test
    fun testNonNumericIsInvalid() {
        assertNull(TargetCountValidator.parse("abc"))
        assertNull(TargetCountValidator.parse("12a"))
        assertNull(TargetCountValidator.parse("3.5"))
    }

    @Test
    fun testOutOfRangeIsInvalid() {
        assertNull(TargetCountValidator.parse("-1"))
        assertNull(TargetCountValidator.parse("100"))
        assertNull(TargetCountValidator.parse("999"))
    }
}
