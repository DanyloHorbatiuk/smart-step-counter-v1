package com.example.smartstepcounter.ble

import kotlinx.coroutines.delay

/**
 * Emulated BLE scan: after [SCAN_DURATION_MS] "finds" the StepHub and two
 * third-party devices. The link state is SCANNING while it runs.
 */
class MockBleScanner(private val connector: MockBleConnector) {

    suspend fun scan(): List<BleDevice> {
        connector.onScanStarted()
        try {
            delay(SCAN_DURATION_MS)
        } catch (e: Throwable) {
            connector.onScanFinished(emptyList())
            throw e
        }
        val found = DEVICES.sortedByDescending { it.rssi }
        connector.onScanFinished(found)
        return found
    }

    companion object {
        const val SCAN_DURATION_MS = 2_000L

        val STEP_HUB = BleDevice(HUB_NAME, "C0:FF:EE:12:34:56", rssi = -58)

        private val DEVICES = listOf(
            STEP_HUB,
            BleDevice("Mi Band 7", "E4:5F:01:A8:3B:7C", rssi = -74),
            BleDevice("Unknown", "5A:21:9C:0D:E2:47", rssi = -91),
        )
    }
}
