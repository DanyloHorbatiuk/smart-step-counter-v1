package com.example.smartstepcounter.history

import com.example.smartstepcounter.StepCounterUiState
import com.example.smartstepcounter.actuator.ActuatorEvent
import com.example.smartstepcounter.cloud.SyncChangeFilter
import com.example.smartstepcounter.data.local.SensorRecord
import com.example.smartstepcounter.sensor.StepSnapshot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Decides *what* and *when* to store; the database lives behind [repository].
 *
 * Two triggers:
 * - a ticker every [ISensorHistoryRepository.recordIntervalMs], going through [SyncChangeFilter]
 *   (the same "significant change" rule as the cloud upload);
 * - every actuator event: the current snapshot is stored at once with actionTriggered = true.
 *
 * Both run in the caller's scope; with the main dispatcher check-and-mark is not racy.
 *
 * @param steps state of [com.example.smartstepcounter.StepCounterViewModel]; read-only.
 */
class SensorRecorder(
    private val repository: ISensorHistoryRepository,
    private val steps: StateFlow<StepCounterUiState>,
    private val actuatorEvents: Flow<ActuatorEvent>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val filter = SyncChangeFilter()

    /** Records until cancelled. */
    suspend fun run(): Unit = coroutineScope {
        // The actuator flow replays recent events; only new ones mark the chart.
        val startedAt = clock()
        launch {
            actuatorEvents
                .filter { it.timestamp >= startedAt }
                .collect { recordForced(it.timestamp) }
        }
        // collectLatest restarts the ticker at once when the interval changes.
        repository.recordIntervalMs.collectLatest { interval ->
            while (true) {
                delay(interval)
                recordIfChanged()
            }
        }
    }

    private suspend fun recordIfChanged() {
        val ui = steps.value
        // A paused sensor produces no new data; avoids storing the zero initial snapshot.
        if (!ui.listening) return
        val snapshot = ui.snapshot
        if (!filter.shouldSend(snapshot)) return
        filter.markSent(snapshot)
        repository.insertRecord(snapshot.toRecord(clock(), actionTriggered = false))
    }

    private suspend fun recordForced(timestamp: Long) {
        val ui = steps.value
        if (!ui.listening) return
        filter.markSent(ui.snapshot)
        repository.insertRecord(ui.snapshot.toRecord(timestamp, actionTriggered = true))
    }

    private fun StepSnapshot.toRecord(timestamp: Long, actionTriggered: Boolean) = SensorRecord(
        timestamp = timestamp,
        sensorValue = magnitude,
        x = x,
        y = y,
        z = z,
        rms = rms,
        steps = steps,
        state = state.name,
        actionTriggered = actionTriggered,
    )
}
