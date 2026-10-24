package com.example.smartstepcounter.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smartstepcounter.AppContainer
import com.example.smartstepcounter.StepCounterUiState
import com.example.smartstepcounter.actuator.ActuatorEvent
import com.example.smartstepcounter.data.local.ActuatorEventEntity
import com.example.smartstepcounter.data.local.SensorRecord
import com.example.smartstepcounter.history.ISensorHistoryRepository
import com.example.smartstepcounter.history.SensorRecorder
import com.example.smartstepcounter.history.StorageEvent
import com.example.smartstepcounter.history.StorageState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Chart series; [label] is shown on the filter chips. */
enum class Series(val label: String, val value: (SensorRecord) -> Float) {
    X("X", { it.x }),
    Y("Y", { it.y }),
    Z("Z", { it.z }),
    MAGNITUDE("|a|", { it.sensorValue }),
    RMS("RMS", { it.rms }),
}

data class AnalyticsUiState(
    /** The newest records, oldest first. */
    val records: List<SensorRecord> = emptyList(),
    val totalCount: Int = 0,
    val recordIntervalMs: Long = 0,
    val storage: StorageState = StorageState(),
    /** Newest first. */
    val actuatorEvents: List<ActuatorEventEntity> = emptyList(),
    /** X/Y/Z by default: rotating the phone gives clear waves. */
    val series: Set<Series> = setOf(Series.X, Series.Y, Series.Z),
)

/**
 * "Аналітика" tab: View → this ViewModel → [ISensorHistoryRepository] → Room DAO.
 *
 * Also owns the [SensorRecorder]: like CloudSyncViewModel it lives as long as its data
 * source, the step counter ViewModel, so recording runs whichever tab is open.
 */
class AnalyticsViewModel(
    private val repository: ISensorHistoryRepository,
    steps: StateFlow<StepCounterUiState>,
    actuatorEvents: Flow<ActuatorEvent>,
) : ViewModel() {

    private val series = MutableStateFlow(AnalyticsUiState().series)

    val uiState: StateFlow<AnalyticsUiState> = combine(
        repository.observeLatest(CHART_POINTS),
        repository.observeCount(),
        repository.recordIntervalMs,
        repository.storageState,
        repository.observeActuatorEvents(EVENT_LIST_SIZE),
    ) { records, count, interval, storage, events ->
        AnalyticsUiState(records, count, interval, storage, events)
    }.combine(series) { state, series -> state.copy(series = series) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    val events: SharedFlow<StorageEvent> = repository.events

    init {
        val recorder = SensorRecorder(repository, steps, actuatorEvents)
        viewModelScope.launch { recorder.run() }
    }

    fun toggleSeries(s: Series) {
        series.update { if (s in it) it - s else it + s }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    companion object {
        /** More points slow down drawing and make the lines unreadable on a phone. */
        const val CHART_POINTS = 50

        const val EVENT_LIST_SIZE = 20

        fun factory(
            container: AppContainer,
            steps: StateFlow<StepCounterUiState>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AnalyticsViewModel(
                    repository = container.historyRepository,
                    steps = steps,
                    actuatorEvents = container.actuator.events,
                )
            }
        }
    }
}
