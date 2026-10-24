package com.example.smartstepcounter.actuator

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Records every call instead of touching hardware. */
class FakeActuator : Actuator {
    private val _state = MutableStateFlow(ActuatorState(torchIsVirtual = true, vibratorAvailable = true))
    override val state: StateFlow<ActuatorState> = _state.asStateFlow()
    override val events: SharedFlow<ActuatorEvent> = MutableSharedFlow(replay = 30)

    val recorded = mutableListOf<ActuatorEvent>()
    val vibrations = mutableListOf<VibrationPattern>()

    override fun triggerAlarm(pattern: VibrationPattern, source: EventSource, reason: String) {
        vibrations += pattern
        recorded += ActuatorEvent(0, ActuatorType.VIBRATION, true, source, reason)
    }

    override fun toggleFlashlight(on: Boolean, source: EventSource, reason: String) {
        _state.update { it.copy(torchOn = on) }
        recorded += ActuatorEvent(0, ActuatorType.TORCH, on, source, reason)
    }

    override fun setVirtualLamp(on: Boolean, source: EventSource, reason: String) {
        _state.update { it.copy(virtualLampOn = on) }
        recorded += ActuatorEvent(0, ActuatorType.LAMP, on, source, reason)
    }
}
