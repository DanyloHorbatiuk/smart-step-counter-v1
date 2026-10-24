package com.example.smartstepcounter.ml

import com.example.smartstepcounter.sensor.MotionState

/**
 * Model classes in the order of the output tensor (app/src/main/assets/labels.txt).
 *
 * @param motionState the matching Lab 1 threshold state; null for SHAKE, which the
 * threshold classifier cannot recognise.
 */
enum class AiLabel(val title: String, val motionState: MotionState?) {
    STILL("Спокій", MotionState.STILL),
    WALKING("Ходьба", MotionState.WALKING),
    RUNNING("Біг", MotionState.RUNNING),
    SHAKE("Струшування", null),
}

/**
 * One classification result.
 *
 * @param probabilities softmax output, indexed by [AiLabel.ordinal].
 * @param inferenceMs time of Interpreter.run, ms.
 */
data class Prediction(
    val label: AiLabel,
    val confidence: Float,
    val probabilities: List<Float>,
    val inferenceMs: Float,
) {
    companion object {
        fun fromProbabilities(probabilities: List<Float>, inferenceMs: Float): Prediction {
            require(probabilities.size == AiLabel.entries.size) { "expected ${AiLabel.entries.size} probabilities" }
            val best = probabilities.indices.maxBy { probabilities[it] }
            return Prediction(AiLabel.entries[best], probabilities[best], probabilities, inferenceMs)
        }
    }
}

enum class AiStatus {
    /** The model is being loaded. */
    LOADING,

    /** The model failed to load: the app works without ML. */
    UNAVAILABLE,

    /** Waiting for the first full window. */
    WARMING_UP,

    READY,
}

data class AiInsight(
    val status: AiStatus = AiStatus.LOADING,
    /** Samples in the buffer, up to [MlConfig.WINDOW]. */
    val bufferFill: Int = 0,
    /** Prediction for the latest window. */
    val latest: Prediction? = null,
    /** Majority of the last [MlConfig.SMOOTHING] predictions: what the UI shows. */
    val smoothed: Prediction? = null,
    /** StepDetector state on the last sample of the window (Lab 1 thresholds). */
    val detectorState: MotionState = MotionState.STILL,
    val windowsClassified: Long = 0,
) {
    /** Null until the first prediction. */
    val matchesThresholds: Boolean?
        get() = smoothed?.let { it.label.motionState == detectorState }
}

/** Model behind an interface, so the pipeline and the rules are tested on the JVM with a fake. */
interface Classifier : AutoCloseable {
    /** @param window [MlConfig.WINDOW] samples of dynamic acceleration, m/s², oldest first. */
    fun classify(window: FloatArray): Prediction

    override fun close()
}
