package com.example.smartstepcounter

import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import com.example.smartstepcounter.sensor.StepDetector
import com.example.smartstepcounter.sensor.StepSnapshot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StepCounterUiState(
    val sensorAvailable: Boolean = true,
    val listening: Boolean = false,
    val accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
    val snapshot: StepSnapshot = StepSnapshot(),
    /** Last [HISTORY_SIZE] values of dynamic acceleration for the chart, m/s². */
    val history: List<Float> = emptyList(),
)

const val HISTORY_SIZE = 150 // ~3 s at SENSOR_DELAY_GAME (~50 Hz)

/**
 * Keeps the detector and UI state across configuration changes (screen rotation).
 * The Activity feeds raw sensor samples into [onSample].
 */
class StepCounterViewModel : ViewModel() {
    private val detector = StepDetector()

    private val _uiState = MutableStateFlow(StepCounterUiState())
    val uiState: StateFlow<StepCounterUiState> = _uiState.asStateFlow()

    // Lab 4: every snapshot for DecisionEngine. A StateFlow would conflate values and
    // could hide a single-sample impact peak (R3), so this is a buffered SharedFlow.
    private val _samples = MutableSharedFlow<StepSnapshot>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val samples: SharedFlow<StepSnapshot> = _samples.asSharedFlow()

    fun setSensorAvailable(available: Boolean) {
        _uiState.update { it.copy(sensorAvailable = available) }
    }

    fun onListeningChanged(listening: Boolean) {
        // Coming back from onPause: time gap would distort the filters.
        if (listening) detector.resume()
        _uiState.update { it.copy(listening = listening) }
    }

    fun onAccuracyChanged(accuracy: Int) {
        _uiState.update { it.copy(accuracy = accuracy) }
    }

    fun onSample(x: Float, y: Float, z: Float, timestampNs: Long) {
        val snapshot = detector.process(x, y, z, timestampNs)
        _uiState.update {
            it.copy(
                snapshot = snapshot,
                history = (it.history + snapshot.dynamic).takeLast(HISTORY_SIZE),
            )
        }
        _samples.tryEmit(snapshot)
    }

    fun reset() {
        detector.reset()
        _uiState.update { it.copy(snapshot = StepSnapshot(), history = emptyList()) }
    }
}
