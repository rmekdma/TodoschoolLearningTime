package com.example.todoschooltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetCountValidatorTest {

    @Test
    fun testValidNumbers() {
        assertTrue(TargetCountValidator.isValid("0"))
        assertTrue(TargetCountValidator.isValid("1"))
        assertTrue(TargetCountValidator.isValid("50"))
        assertTrue(TargetCountValidator.isValid("99"))
        assertTrue(TargetCountValidator.isValid(" 6 "))

        assertEquals(0, TargetCountValidator.parse("0"))
        assertEquals(99, TargetCountValidator.parse("99"))
        assertEquals(6, TargetCountValidator.parse(" 6 "))
    }

    @Test
    fun testBlankIsInvalid() {
        assertFalse(TargetCountValidator.isValid(""))
        assertFalse(TargetCountValidator.isValid("   "))
        assertEquals(null, TargetCountValidator.parse(""))
    }

    @Test
    fun testNonNumericIsInvalid() {
        assertFalse(TargetCountValidator.isValid("abc"))
        assertFalse(TargetCountValidator.isValid("12a"))
        assertFalse(TargetCountValidator.isValid("3.5"))
        assertEquals(null, TargetCountValidator.parse("abc"))
    }

    @Test
    fun testOutOfRangeIsInvalid() {
        assertFalse(TargetCountValidator.isValid("-1"))
        assertFalse(TargetCountValidator.isValid("100"))
        assertFalse(TargetCountValidator.isValid("999"))
        assertEquals(null, TargetCountValidator.parse("-1"))
        assertEquals(null, TargetCountValidator.parse("100"))
    }
}
