package com.example.smartstepcounter.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smartstepcounter.AppContainer
import com.example.smartstepcounter.StepCounterUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Decides *what* and *when* to upload; queueing and networking live in [ICloudRepository]
 * (app scope), so this ViewModel only mirrors the lifetime of its data source, the
 * step counter ViewModel — the same pattern as BleViewModel.
 *
 * Two triggers, both going through [SyncChangeFilter]:
 * - a ticker every [TICK_INTERVAL_MS];
 * - a motion state change, sent immediately but not more often than [STATE_MIN_INTERVAL_MS].
 *
 * @param steps state of [com.example.smartstepcounter.StepCounterViewModel]; read-only.
 */
class CloudSyncViewModel(
    private val repository: ICloudRepository,
    private val steps: StateFlow<StepCounterUiState>,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val state: StateFlow<CloudSyncState> = repository.state
    val events: SharedFlow<CloudEvent> = repository.events

    private val filter = SyncChangeFilter()

    init {
        viewModelScope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                sendIfChanged()
            }
        }
        viewModelScope.launch {
            steps.map { it.snapshot.state }
                .distinctUntilChanged()
                .drop(1) // the initial value is not a change
                .conflate()
                .collect {
                    sendIfChanged()
                    delay(STATE_MIN_INTERVAL_MS)
                }
        }
    }

    /** Both triggers run on the main thread, so check-and-mark below is not racy. */
    private suspend fun sendIfChanged() {
        val ui = steps.value
        // A paused sensor produces no new data; avoids uploading the zero initial snapshot.
        if (!ui.listening) return
        val snapshot = ui.snapshot
        if (!filter.shouldSend(snapshot)) return
        filter.markSent(snapshot)
        repository.upload(SensorData.from(snapshot, deviceId, clock()))
    }

    companion object {
        /**
         * Upload period, ms. 5 s keeps the database small (≤ 720 records/hour) while
         * the dashboard still looks live; a step count is at most 5 s stale.
         */
        const val TICK_INTERVAL_MS = 5_000L

        /** Rate limit for immediate uploads on a motion state change, ms. */
        const val STATE_MIN_INTERVAL_MS = 1_000L

        fun factory(
            container: AppContainer,
            steps: StateFlow<StepCounterUiState>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CloudSyncViewModel(
                    repository = container.cloudRepository,
                    steps = steps,
                    deviceId = container.deviceId,
                )
            }
        }
    }
}
