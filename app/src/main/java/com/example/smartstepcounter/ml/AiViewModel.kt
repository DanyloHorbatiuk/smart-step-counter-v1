package com.example.smartstepcounter.ml

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smartstepcounter.AppContainer
import com.example.smartstepcounter.actuator.Actuator
import com.example.smartstepcounter.actuator.ControlSettings
import com.example.smartstepcounter.decision.AiRules
import com.example.smartstepcounter.decision.DecisionExecutor
import com.example.smartstepcounter.history.ISensorHistoryRepository
import com.example.smartstepcounter.sensor.StepSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AiUiState(
    val insight: AiInsight = AiInsight(),
    /** Current period of the Lab 5 sensor recorder, ms. */
    val recordIntervalMs: Long = 0,
)

/**
 * "AI Insights" on the "Сенсор" tab. Lives as long as the step counter ViewModel, whose
 * [samples] it classifies, so inference runs whichever tab is open (like the recorder).
 */
class AiViewModel(
    classifierFactory: () -> Classifier?,
    samples: SharedFlow<StepSnapshot>,
    private val history: ISensorHistoryRepository,
    actuator: Actuator,
    settings: ControlSettings,
    rules: AiRules = AiRules(),
    clock: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {

    private val _alerts = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Snackbar texts when the anomaly alarm fires. */
    val alerts: SharedFlow<String> = _alerts.asSharedFlow()

    private val normalIntervalMs = rules.config.normalIntervalMs

    private val controller = AiController(
        rules = rules,
        executor = DecisionExecutor(actuator, { settings.autoMode.value }, { Log.i(TAG, it) }),
        history = history,
        clock = clock,
        logger = { Log.i(TAG, it) },
        onAlert = { _alerts.tryEmit(it) },
    )

    private val pipeline = MlPipeline(
        classifierFactory = classifierFactory,
        onResult = controller::onWindow,
        logger = { Log.w(TAG, it) },
    )

    val uiState: StateFlow<AiUiState> = combine(pipeline.insight, history.recordIntervalMs, ::AiUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiUiState(pipeline.insight.value, history.recordIntervalMs.value))

    init {
        viewModelScope.launch { pipeline.run(samples) }
    }

    override fun onCleared() {
        // The repository is app-wide: do not leave it in fast mode without a classifier.
        history.setRecordInterval(normalIntervalMs)
        pipeline.close()
    }

    companion object {
        const val TAG = "AI"

        fun factory(
            container: AppContainer,
            context: Context,
            samples: SharedFlow<StepSnapshot>,
        ): ViewModelProvider.Factory = viewModelFactory {
            val appContext = context.applicationContext
            initializer {
                AiViewModel(
                    classifierFactory = { ActivityClassifier.tryCreate(appContext) },
                    samples = samples,
                    history = container.historyRepository,
                    actuator = container.actuator,
                    settings = container.controlSettings,
                )
            }
        }
    }
}
