package com.example.smartstepcounter.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Bluetooth Interface Layer: everything the app needs from a BLE link.
 * A real implementation would wrap BluetoothGatt; [MockBleConnector] emulates it.
 */
interface IBleConnector {
    val state: StateFlow<BleConnectionState>
    val log: StateFlow<List<BleLogEntry>>

    /** Commands that arrived from the hub (Notify on [BleUuids.COMMAND]). */
    val incoming: SharedFlow<String>

    suspend fun connect(device: BleDevice): Boolean
    fun disconnect()
    suspend fun sendData(data: String, characteristic: UUID = BleUuids.STEPS): Boolean
}

/** Test hooks that only an emulated hub can provide (buttons on the Device screen). */
interface BleHubSimulator {
    /** Pretends the hub sent [command] via Notify. */
    fun simulateIncomingCommand(command: String)

    /**
     * Drops the link and starts auto-reconnect (up to 3 attempts, 1 s / 2 s / 4 s backoff).
     * The hub comes back on attempt [recoverOnAttempt]; 0 = never.
     */
    fun simulateConnectionLoss(recoverOnAttempt: Int = 3)
}
