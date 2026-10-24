package com.example.smartstepcounter.actuator

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smartstepcounter.AppContainer
import com.example.smartstepcounter.cloud.ControlListenerState
import com.example.smartstepcounter.decision.DecisionAction
import com.example.smartstepcounter.decision.DecisionConfig
import com.example.smartstepcounter.decision.DecisionEngine
import com.example.smartstepcounter.decision.DecisionExecutor
import com.example.smartstepcounter.sensor.StepSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Glue of the "Керування" tab: feeds sensor snapshots into [DecisionEngine], executes
 * decisions through [DecisionExecutor], exposes the actuator event log and handles manual
 * actions. Cloud commands are handled in the app scope (see AppContainer), not here.
 *
 * @param samples every snapshot of the step counter, see StepCounterViewModel.samples.
 * @param clock monotonic time for the rules' timers.
 */
class ControlViewModel(
    private val actuator: Actuator,
    private val settings: ControlSettings,
    samples: SharedFlow<StepSnapshot>,
    val listenerState: StateFlow<ControlListenerState>,
    val deviceId: String,
    config: DecisionConfig = DecisionConfig(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {

    private val engine = DecisionEngine(config)
    private val executor = DecisionExecutor(
        actuator = actuator,
        autoEnabled = { settings.autoMode.value },
        logger = { Log.i(TAG, it) },
    )

    val config: DecisionConfig get() = engine.config
    val actuatorState: StateFlow<ActuatorState> = actuator.state
    val autoMode: StateFlow<Boolean> = settings.autoMode.asStateFlow()
    val mirrorLedToTorch: StateFlow<Boolean> = settings.mirrorLedToTorch.asStateFlow()

    private val _log = MutableStateFlow<List<ActuatorEvent>>(emptyList())

    /** Newest first, at most [SmartActuator.EVENT_REPLAY] entries. */
    val log: StateFlow<List<ActuatorEvent>> = _log.asStateFlow()

    private val _ruleEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Snackbar texts when a rule fires. */
    val ruleEvents: SharedFlow<String> = _ruleEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            samples.collect { snapshot ->
                engine.evaluate(snapshot, clock()).forEach { decision ->
                    val executed = executor.execute(decision)
                    if (decision.action == DecisionAction.ACTIVATE) {
                        _ruleEvents.tryEmit(if (executed) decision.message else "${decision.message} (авто-режим вимкнено)")
                    }
                }
            }
        }
        viewModelScope.launch {
            actuator.events.collect { event ->
                _log.update { (listOf(event) + it).take(SmartActuator.EVENT_REPLAY) }
            }
        }
    }

    fun setAutoMode(enabled: Boolean) {
        settings.autoMode.value = enabled
        Log.i(TAG, "auto mode ${if (enabled) "on" else "off"}")
    }

    fun setMirrorLedToTorch(enabled: Boolean) {
        settings.mirrorLedToTorch.value = enabled
    }

    fun toggleTorch(on: Boolean) = actuator.toggleFlashlight(on, EventSource.MANUAL, "кнопка в застосунку")

    fun vibrate() = actuator.triggerAlarm(VibrationPattern.SINGLE, EventSource.MANUAL, "кнопка в застосунку")

    companion object {
        const val TAG = "Decision"

        fun factory(container: AppContainer, samples: SharedFlow<StepSnapshot>): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ControlViewModel(
                        actuator = container.actuator,
                        settings = container.controlSettings,
                        samples = samples,
                        listenerState = container.controlListener.state,
                        deviceId = container.deviceId,
                    )
                }
            }
    }
}
