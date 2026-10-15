package com.example.smartstepcounter.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockBleConnectorTest {

    private val hub = MockBleScanner.STEP_HUB
    private val band = BleDevice("Mi Band 7", "E4:5F:01:A8:3B:7C", rssi = -74)

    private fun TestScope.newConnector(scope: CoroutineScope = backgroundScope) =
        MockBleConnector(scope, clock = { currentTime }, logger = {})

    /** Background work (auto-reconnect) is not covered by advanceUntilIdle, so step the clock. */
    private fun TestScope.settle() {
        advanceTimeBy(20_000)
        runCurrent()
    }

    private suspend fun TestScope.connected(): MockBleConnector {
        val connector = newConnector()
        assertTrue(connector.connect(hub))
        return connector
    }

    @Test
    fun connectGoesThroughConnectingToConnected() = runTest {
        val connector = newConnector()
        val result = async { connector.connect(hub) }
        runCurrent()
        assertEquals(BleConnectionState.CONNECTING, connector.state.value)

        advanceTimeBy(999)
        assertEquals(BleConnectionState.CONNECTING, connector.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.await())
        assertEquals(BleConnectionState.CONNECTED, connector.state.value)
    }

    @Test
    fun connectToForeignDeviceIsRefused() = runTest {
        val connector = newConnector()
        assertFalse(connector.connect(band))
        assertEquals(BleConnectionState.DISCONNECTED, connector.state.value)
        assertTrue(connector.log.value.last().message.contains("refused"))
    }

    @Test
    fun disconnectReturnsToDisconnected() = runTest {
        val connector = connected()
        connector.disconnect()
        assertEquals(BleConnectionState.DISCONNECTED, connector.state.value)
        assertEquals(BleDirection.SYS, connector.log.value.last().direction)
    }

    @Test
    fun sendDataWithoutConnectionReturnsFalse() = runTest {
        val connector = newConnector()
        assertFalse(connector.sendData("STEPS=1;CAD=0"))
        val last = connector.log.value.last()
        assertEquals(BleDirection.SYS, last.direction)
        assertTrue(last.message.contains("not connected"))
    }

    @Test
    fun sendDataLogsTxAndAckAndPassesThroughDataTransfer() = runTest {
        val connector = connected()
        val payload = "STEPS=28;CAD=108"
        val result = async { connector.sendData(payload, BleUuids.STEPS) }
        runCurrent()
        assertEquals(BleConnectionState.DATA_TRANSFER, connector.state.value)

        advanceTimeBy(MockBleConnector.TRANSFER_DELAY_MS)
        runCurrent()
        assertTrue(result.await())
        assertEquals(BleConnectionState.CONNECTED, connector.state.value)

        val (tx, rx) = connector.log.value.takeLast(2)
        assertEquals(BleDirection.TX, tx.direction)
        assertEquals(BleUuids.STEPS, tx.characteristic)
        assertEquals(payload, tx.message)
        assertEquals(16, tx.sizeBytes)
        assertEquals(BleDirection.RX, rx.direction)
        assertEquals(HubCommands.ACK, rx.message)
    }

    @Test
    fun pingOnCommandCharacteristicIsAnsweredWithPong() = runTest {
        val connector = connected()
        assertTrue(connector.sendData(HubCommands.PING, BleUuids.COMMAND))
        assertEquals(HubCommands.PONG, connector.log.value.last().message)
    }

    @Test
    fun incomingCommandIsEmitted() = runTest {
        val connector = connected()
        val received = mutableListOf<String>()
        backgroundScope.launch { connector.incoming.collect { received += it } }
        runCurrent()

        connector.simulateIncomingCommand(HubCommands.RESET)
        runCurrent()
        assertEquals(listOf(HubCommands.RESET), received)
    }

    @Test
    fun connectionLossMakesThreeReconnectAttemptsThenGivesUp() = runTest {
        val connector = connected()
        val lossAt = currentTime

        connector.simulateConnectionLoss(recoverOnAttempt = 0)
        assertEquals(BleConnectionState.DISCONNECTED, connector.state.value)

        settle()
        val attempts = connector.log.value.filter { it.message.startsWith("Reconnect attempt") && it.message.contains("/") }
        assertEquals(3, attempts.size)
        // Backoff 1 s, 2 s, 4 s plus 1 s for every connect attempt.
        assertEquals(
            listOf(1_000L, 4_000L, 9_000L),
            attempts.map { it.timeMillis - lossAt },
        )
        assertEquals(BleConnectionState.DISCONNECTED, connector.state.value)
        assertTrue(connector.log.value.last().message.startsWith("Giving up"))
    }

    @Test
    fun connectionLossRecoversOnThirdAttemptByDefault() = runTest {
        val connector = connected()
        connector.simulateConnectionLoss()
        settle()
        assertEquals(BleConnectionState.CONNECTED, connector.state.value)
    }

    @Test
    fun disconnectCancelsAutoReconnect() = runTest {
        val connector = connected()
        connector.simulateConnectionLoss(recoverOnAttempt = 3)
        advanceTimeBy(1_500)
        connector.disconnect()
        settle()
        assertEquals(BleConnectionState.DISCONNECTED, connector.state.value)
        assertEquals(1, connector.log.value.count { it.message.startsWith("Reconnect attempt 1/") })
    }
}
