package com.example.smartstepcounter.ble

import java.util.UUID

/**
 * GATT profile of the emulated "StepHub-01" smart hub.
 *
 * Custom 128-bit UUIDs share one base and differ in the 2nd group (0001…0004),
 * the CCCD is the standard Bluetooth SIG descriptor that enables Notify.
 */
object BleUuids {
    /** Primary service of the hub. */
    val SERVICE: UUID = UUID.fromString("a3f10001-6c2e-4d8b-9e57-1b0c4f2a7d90")

    /** Read, Notify: step count and cadence, e.g. "STEPS=28;CAD=108". */
    val STEPS: UUID = UUID.fromString("a3f10002-6c2e-4d8b-9e57-1b0c4f2a7d90")

    /** Read, Notify: motion state and RMS, e.g. "STATE=WALKING;RMS=1.42". */
    val MOTION: UUID = UUID.fromString("a3f10003-6c2e-4d8b-9e57-1b0c4f2a7d90")

    /** Write: commands to / from the hub ("RESET", "PING", "PONG"). */
    val COMMAND: UUID = UUID.fromString("a3f10004-6c2e-4d8b-9e57-1b0c4f2a7d90")

    /** Client Characteristic Configuration Descriptor (standard, enables Notify). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Human-readable name of a characteristic for the terminal. */
    fun shortName(uuid: UUID?): String = when (uuid) {
        null -> "-"
        SERVICE -> "SERVICE"
        STEPS -> "STEPS"
        MOTION -> "MOTION"
        COMMAND -> "COMMAND"
        CCCD -> "CCCD"
        else -> uuid.toString().substring(0, 8)
    }
}

/** Text commands understood by the hub. */
object HubCommands {
    const val RESET = "RESET"
    const val PING = "PING"
    const val PONG = "PONG"
    const val ACK = "ACK"
}

/** Advertised name of the only device the app is allowed to connect to. */
const val HUB_NAME = "StepHub-01"
