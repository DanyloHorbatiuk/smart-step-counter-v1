package com.example.smartstepcounter.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlEventParserTest {

    private fun reduce(state: ControlState, type: String, data: String) =
        ControlReducer.reduce(state, ControlEventParser.parse(type, data))

    @Test
    fun putRootWithFullObject() {
        val r = reduce(ControlState(), "put", """{"path":"/","data":{"led_status":true,"vibrate":false}}""")
        assertEquals(ControlState(ledStatus = true, vibrate = false), r.state)
        assertEquals(listOf(ControlCommand.SetLed(true)), r.commands)
    }

    @Test
    fun putRootNullMeansDeleted() {
        val r = reduce(ControlState(ledStatus = true), "put", """{"path":"/","data":null}""")
        assertEquals(ControlState(), r.state)
        assertEquals(listOf(ControlCommand.SetLed(false)), r.commands)
    }

    @Test
    fun putSingleField() {
        val r = reduce(ControlState(), "put", """{"path":"/led_status","data":true}""")
        assertEquals(ControlState(ledStatus = true), r.state)
        assertEquals(listOf(ControlCommand.SetLed(true)), r.commands)
    }

    @Test
    fun putVibrateIsOneShotCommand() {
        val on = reduce(ControlState(), "put", """{"path":"/vibrate","data":true}""")
        assertEquals(listOf(ControlCommand.Vibrate), on.commands)
        // The app writes false back: no new command.
        val off = reduce(on.state, "put", """{"path":"/vibrate","data":false}""")
        assertTrue(off.commands.isEmpty())
    }

    @Test
    fun patchMergesFields() {
        val r = reduce(ControlState(ledStatus = true), "patch", """{"path":"/","data":{"vibrate":true}}""")
        assertEquals(ControlState(ledStatus = true, vibrate = true), r.state)
        assertEquals(listOf(ControlCommand.Vibrate), r.commands)
    }

    @Test
    fun unchangedLedProducesNoCommand() {
        val r = reduce(ControlState(ledStatus = true), "put", """{"path":"/led_status","data":true}""")
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun patchOfLedDoesNotRepeatPendingVibrate() {
        val r = reduce(ControlState(vibrate = true), "patch", """{"path":"/","data":{"led_status":true}}""")
        assertEquals(listOf(ControlCommand.SetLed(true)), r.commands)
    }

    @Test
    fun keepAliveIsIgnored() {
        assertEquals(ControlUpdate.KeepAlive, ControlEventParser.parse("keep-alive", "null"))
        val r = reduce(ControlState(ledStatus = true), "keep-alive", "null")
        assertEquals(ControlState(ledStatus = true), r.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun cancelAndAuthRevokedAreFailures() {
        assertTrue(ControlEventParser.parse("cancel", "null") is ControlUpdate.Failure)
        assertTrue(ControlEventParser.parse("auth_revoked", "null") is ControlUpdate.Failure)
    }

    @Test
    fun malformedJsonIsIgnored() {
        assertTrue(ControlEventParser.parse("put", "{oops") is ControlUpdate.Ignored)
        assertTrue(ControlEventParser.parse("put", """{"data":true}""") is ControlUpdate.Ignored)
    }

    @Test
    fun handTypedValuesAreAccepted() {
        assertEquals(true, reduce(ControlState(), "put", """{"path":"/led_status","data":"true"}""").state.ledStatus)
        assertEquals(true, reduce(ControlState(), "put", """{"path":"/led_status","data":1}""").state.ledStatus)
    }

    @Test
    fun backoffDoublesUpToThirtySeconds() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map(Backoff::delayMs),
        )
    }
}
