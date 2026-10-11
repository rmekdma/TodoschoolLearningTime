package com.example.todoschooltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class E2eCommandParserTest {
    @Test
    fun parsesVersionedReportConfigurationAndGoals() {
        val report = E2eCommandParser.parse(
            """{"version":1,"command":"setReport","child":"child-a","subject":"math","count":12,"outcome":"success","delayMs":250}""",
        ) as E2eCommand.SetReport
        assertEquals("child-a", report.childId)
        assertEquals("math", report.subjectCode)
        assertEquals(12, report.count)
        assertEquals(FakeReportOutcome.SUCCESS, report.outcome)
        assertEquals(250, report.delayMs)

        val goal = E2eCommandParser.parse(
            """{"version":1,"command":"setGoal","child":"child-b","targets":{"hangeul":4,"english":9}}""",
        ) as E2eCommand.SetGoal
        assertEquals(mapOf("hangeul" to 4, "english" to 9), goal.targets)
    }

    @Test
    fun parsesOnlyKnownSimpleCommands() {
        assertTrue(E2eCommandParser.parse("""{"version":1,"command":"getState"}""") is E2eCommand.GetState)
        assertTrue(E2eCommandParser.parse("""{"version":1,"command":"getEvents"}""") is E2eCommand.GetEvents)
        assertTrue(E2eCommandParser.parse("""{"version":1,"command":"clearEvents"}""") is E2eCommand.ClearEvents)
        assertTrue(E2eCommandParser.parse("""{"version":1,"command":"reset"}""") is E2eCommand.Reset)
        assertTrue(E2eCommandParser.parse("""{"version":1,"command":"registerChild","child":"child-a"}""") is E2eCommand.RegisterChild)
        assertTrue(E2eCommandParser.parse("""{"version":1,"command":"unregisterChild","child":"child-b"}""") is E2eCommand.UnregisterChild)
        assertTrue(E2eCommandParser.parse("""{"version":1,"command":"setInterval","minutes":1}""") is E2eCommand.SetInterval)
        assertTrue(E2eCommandParser.parse("""{"version":1,"command":"advanceDate","days":1}""") is E2eCommand.AdvanceDate)
    }

    @Test
    fun preservesLargePositiveIntervalFromItsRawDecimalJsonToken() {
        listOf(BigInteger("9223372036854775808"), BigInteger.TEN.pow(400)).forEach { expected ->
            val command = E2eCommandParser.parse(
                """{"version":1,"command":"setInterval","minutes":$expected}""",
            ) as E2eCommand.SetInterval

            assertEquals(expected, command.minutes)
        }
    }

    @Test
    fun rejectsUnknownFieldsVersionsAliasesAndNonIntegerNumbers() {
        listOf(
            """{"version":1,"command":"getState","surprise":true}""",
            """{"version":2,"command":"getState"}""",
            """{"version":"1","command":"getState"}""",
            """{"version":1,"command":"setReport","child":"child-a","subject":"math","count":1.5,"outcome":"success","delayMs":0}""",
            """{"version":1,"command":"setReport","child":"child-c","subject":"math","count":1,"outcome":"success","delayMs":0}""",
            """{"version":1,"command":"setReport","child":"child-a","subject":"science","count":1,"outcome":"success","delayMs":0}""",
            """{"version":1,"command":"setReport","child":"child-a","subject":"math","count":1,"outcome":"success","delayMs":30001}""",
            """{"version":1,"command":"setReport","child":"child-a","subject":"math","count":1,"outcome":"other","delayMs":0}""",
            """{"version":1,"command":"setGoal","child":"child-a","targets":{"math":2,"unknown":3}}""",
            """{"version":1,"command":"setInterval","minutes":0}""",
            """{"version":1,"command":"advanceDate","days":0}""",
            """{"version":1,"command":"forgetEverything"}""",
            """{"version":1,"command":"reset"}garbage""",
            """{'version':1,'command':'reset'}""",
            """{"version":1,/*comment*/"command":"reset"}""",
            """{"version":1,"command":"getState","command":"reset"}""",
            """{"version":1,"command":"setReport","child":"child-a","subject":"math","count":010,"outcome":"success","delayMs":0}""",
        ).forEach { json ->
            assertRejected(json)
        }
    }

    private fun assertRejected(json: String) {
        try {
            E2eCommandParser.parse(json)
            throw AssertionError("Expected command to be rejected: $json")
        } catch (_: E2eCommandValidationException) {
            // Expected: the parser rejects malformed or out-of-scope commands before dispatch.
        }
    }
}
