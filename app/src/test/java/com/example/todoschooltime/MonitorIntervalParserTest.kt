package com.example.todoschooltime

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorIntervalParserTest {
    @Test
    fun acceptsUnboundedPositiveIntegerAndRejectsEmptyZeroNegativeOrFractionalValues() {
        val large = BigInteger("1234567890123456789012345678901234567890")
        assertEquals(large, MonitorIntervalParser.parse("  $large  "))
        assertNull(MonitorIntervalParser.parse(""))
        assertNull(MonitorIntervalParser.parse("0"))
        assertNull(MonitorIntervalParser.parse("-1"))
        assertNull(MonitorIntervalParser.parse("1.5"))
    }
}
