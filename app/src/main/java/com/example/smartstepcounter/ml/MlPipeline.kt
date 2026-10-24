package com.example.smartstepcounter.ml

import com.example.smartstepcounter.sensor.StepSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Sliding-window inference over the step counter's samples:
 * dynamic acceleration -> [CircularBuffer] of [MlConfig.WINDOW] -> every [MlConfig.HOP]
 * samples one [Classifier.classify] -> [MajorityVoter] -> [insight].
 *
 * The model is loaded and run on [dispatcher], never on the UI thread. The default is a
 * single-thread view of Dispatchers.Default, because the Interpreter is not thread-safe.
 *
 * @param classifierFactory returns null when the model cannot be loaded.
 * @param onResult called on the collector's thread for every classified window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MlPipeline(
    private val classifierFactory: () -> Classifier?,
    private val onResult: (raw: Prediction, smoothed: Prediction) -> Unit = { _, _ -> },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val logger: (String) -> Unit = {},
) : AutoCloseable {

    @Volatile
    private var classifier: Classifier? = null
    private val buffer = CircularBuffer(MlConfig.WINDOW)
    private val voter = MajorityVoter()
    private var sinceLastWindow = 0

    private val _insight = MutableStateFlow(AiInsight())
    val insight: StateFlow<AiInsight> = _insight.asStateFlow()

    /** Collects [samples] until cancelled; returns at once if the model is unavailable. */
    suspend fun run(samples: Flow<StepSnapshot>) {
        val c = withContext(dispatcher) { classifierFactory() }
        if (c == null) {
            _insight.update { it.copy(status = AiStatus.UNAVAILABLE) }
            return
        }
        classifier = c
        _insight.update { it.copy(status = AiStatus.WARMING_UP) }
        samples.collect { onSample(c, it) }
    }

    private suspend fun onSample(c: Classifier, snapshot: StepSnapshot) {
        buffer.add(snapshot.dynamic)
        sinceLastWindow++
        if (!buffer.isFull) {
            _insight.update { it.copy(bufferFill = buffer.size) }
            return
        }
        if (sinceLastWindow < MlConfig.HOP) return
        sinceLastWindow = 0

        val window = buffer.snapshot()
        val raw = try {
            withContext(dispatcher) { c.classify(window) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("inference failed: ${e.message}")
            return
        }
        val smoothed = voter.add(raw)
        _insight.update {
            it.copy(
                status = AiStatus.READY,
                bufferFill = buffer.size,
                latest = raw,
                smoothed = smoothed,
                detectorState = snapshot.state,
                windowsClassified = it.windowsClassified + 1,
            )
        }
        onResult(raw, smoothed)
    }

    override fun close() {
        classifier?.close()
    }
}
