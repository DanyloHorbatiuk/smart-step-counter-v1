package com.example.smartstepcounter.ble

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockBleScannerTest {

    @Test
    fun scanFindsStepHubExactlyAfterTwoSeconds() = runTest {
        val connector = MockBleConnector(backgroundScope, clock = { currentTime }, logger = {})
        val scanner = MockBleScanner(connector)

        val result = async { scanner.scan() }
        runCurrent()
        assertEquals(BleConnectionState.SCANNING, connector.state.value)

        advanceTimeBy(1_999)
        assertFalse("scan must still be running at 1999 ms", result.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(2_000L, currentTime)

        val devices = result.await()
        assertTrue(devices.any { it.name == HUB_NAME && it.address == "C0:FF:EE:12:34:56" })
        assertTrue("third-party devices are found too", devices.size >= 2)
        assertEquals(BleConnectionState.DISCONNECTED, connector.state.value)
    }
}
