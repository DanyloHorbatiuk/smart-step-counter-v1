package com.example.smartstepcounter.ble

import java.util.UUID

/** A device found during scanning. */
data class BleDevice(
    val name: String,
    val address: String,
    /** Received signal strength, dBm (closer to 0 = stronger). */
    val rssi: Int,
)

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,

    /** Short-lived: a packet is being written, then back to [CONNECTED]. */
    DATA_TRANSFER,
}

/** True when the GATT link is up (sending a packet does not break the link). */
val BleConnectionState.isLinked: Boolean
    get() = this == BleConnectionState.CONNECTED || this == BleConnectionState.DATA_TRANSFER

enum class BleDirection {
    /** App → hub. */
    TX,

    /** Hub → app. */
    RX,

    /** Link events and errors. */
    SYS,
}

/** One line of the BLE terminal. */
data class BleLogEntry(
    val timeMillis: Long,
    val direction: BleDirection,
    val characteristic: UUID?,
    val message: String,
    /** Payload size in bytes (UTF-8), 0 for system events. */
    val sizeBytes: Int = 0,
)
