package com.example.smartstepcounter.actuator

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Executive devices behind one interface, so rules and cloud commands can be tested
 * on the JVM with a fake. Every action is reported in [events]; none of the methods throws.
 */
interface Actuator {
    val state: StateFlow<ActuatorState>

    /** Every executed action; replays recent ones to late subscribers. */
    val events: SharedFlow<ActuatorEvent>

    fun triggerAlarm(pattern: VibrationPattern, source: EventSource, reason: String)

    fun toggleFlashlight(on: Boolean, source: EventSource, reason: String)

    fun setVirtualLamp(on: Boolean, source: EventSource, reason: String)

    /** Lab 7: writes [command] to the BLE hub; without a link the event is logged with on = false. */
    fun sendBleCommand(command: String, source: EventSource, reason: String)
}
