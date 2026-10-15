package com.example.smartstepcounter.sensor

import kotlin.math.sqrt

/**
 * Motion intensity states the detector moves between.
 *
 * Transitions are driven by the RMS of the *dynamic* acceleration
 * (gravity removed) over the last [Thresholds.WINDOW_NS] nanoseconds.
 */
enum class MotionState {
    /** Device is lying still or held steadily: RMS < [Thresholds.WALK_RMS]. */
    STILL,

    /** Regular walking: [Thresholds.WALK_RMS] <= RMS < [Thresholds.RUN_RMS]. */
    WALKING,

    /** Running / jumping: RMS >= [Thresholds.RUN_RMS]. */
    RUNNING,
}

/**
 * Threshold values (m/s², ns) at which the system changes state.
 *
 * Chosen from typical accelerometer data for a phone in a pocket or hand:
 * - at rest the dynamic acceleration is sensor noise (~0.05–0.3 m/s²);
 * - walking produces peaks of ~1.5–4 m/s² at 1.5–2.2 Hz (90–130 steps/min);
 * - running produces peaks of 5–15 m/s² at 2.5–3.3 Hz (150–200 steps/min).
 */
object Thresholds {
    /** Earth gravity, m/s². */
    const val GRAVITY = 9.80665f

    /** A step is registered when dynamic acceleration rises above this value, m/s². */
    const val STEP_PEAK = 1.2f

    /**
     * Hysteresis: the detector re-arms only after the signal falls below this value,
     * so one noisy peak is not counted as several steps, m/s².
     */
    const val STEP_RESET = 0.4f

    /** Minimum time between two steps (max 4 steps/s = 240 steps/min), ns. */
    const val MIN_STEP_INTERVAL_NS = 250_000_000L

    /** RMS of dynamic acceleration that separates STILL from WALKING, m/s². */
    const val WALK_RMS = 0.6f

    /** RMS of dynamic acceleration that separates WALKING from RUNNING, m/s². */
    const val RUN_RMS = 3.5f

    /** Sliding window for RMS and cadence, ns. */
    const val WINDOW_NS = 2_000_000_000L

    /** Time constant of the slow low-pass filter that tracks gravity, s. */
    const val GRAVITY_TAU_S = 0.8f

    /** Time constant of the fast low-pass filter that removes jitter, s. */
    const val SMOOTH_TAU_S = 0.04f
}

/** A snapshot of everything the detector knows after processing one sample. */
data class StepSnapshot(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    /** |a| including gravity, m/s². */
    val magnitude: Float = 0f,
    /** Smoothed |a| minus gravity, m/s². Positive and negative around 0. */
    val dynamic: Float = 0f,
    /** RMS of [dynamic] over the sliding window, m/s². */
    val rms: Float = 0f,
    val steps: Int = 0,
    /** Steps per minute over the sliding window. */
    val cadence: Float = 0f,
    val state: MotionState = MotionState.STILL,
    /** True only for the sample on which a new step was counted. */
    val stepDetected: Boolean = false,
)

/**
 * Pure Kotlin step detector and intensity classifier.
 * It has no Android dependencies, so it can be unit-tested on the JVM.
 *
 * Algorithm:
 * 1. |a| = sqrt(x² + y² + z²) – independent of phone orientation.
 * 2. Fast low-pass filter removes high-frequency jitter.
 * 3. Slow low-pass filter tracks gravity; dynamic = smoothed − gravity.
 * 4. Step = dynamic crosses [Thresholds.STEP_PEAK] upward, detector is armed
 *    (signal went below [Thresholds.STEP_RESET] since the last step) and at least
 *    [Thresholds.MIN_STEP_INTERVAL_NS] passed since the previous step.
 * 5. Intensity = RMS(dynamic) over the last [Thresholds.WINDOW_NS].
 */
class StepDetector {
    private var lastTimestampNs = -1L
    private var smoothed = Thresholds.GRAVITY
    private var gravity = Thresholds.GRAVITY
    private var armed = true
    private var lastStepNs = Long.MIN_VALUE / 2
    private var steps = 0

    private val window = ArrayDeque<Pair<Long, Float>>() // timestamp to dynamic²
    private var windowSumSq = 0.0
    private val stepTimes = ArrayDeque<Long>()

    fun reset() {
        lastTimestampNs = -1L
        smoothed = Thresholds.GRAVITY
        gravity = Thresholds.GRAVITY
        armed = true
        lastStepNs = Long.MIN_VALUE / 2
        steps = 0
        window.clear()
        windowSumSq = 0.0
        stepTimes.clear()
    }

    /**
     * Resets filter history but keeps the step count, e.g. after the app returns
     * from the background: the time gap would otherwise distort the filters.
     */
    fun resume() {
        val kept = steps
        reset()
        steps = kept
    }

    fun process(x: Float, y: Float, z: Float, timestampNs: Long): StepSnapshot {
        val magnitude = sqrt(x * x + y * y + z * z)

        if (lastTimestampNs < 0) {
            // First sample: initialise the filters with the current value.
            smoothed = magnitude
            gravity = magnitude
        } else {
            val dt = ((timestampNs - lastTimestampNs) / 1e9f).coerceIn(0f, 0.5f)
            smoothed += alpha(dt, Thresholds.SMOOTH_TAU_S) * (magnitude - smoothed)
            gravity += alpha(dt, Thresholds.GRAVITY_TAU_S) * (magnitude - gravity)
        }
        lastTimestampNs = timestampNs

        val dynamic = smoothed - gravity

        // Step detection with hysteresis.
        var stepDetected = false
        if (dynamic < Thresholds.STEP_RESET) {
            armed = true
        }
        if (armed &&
            dynamic > Thresholds.STEP_PEAK &&
            timestampNs - lastStepNs >= Thresholds.MIN_STEP_INTERVAL_NS
        ) {
            steps++
            lastStepNs = timestampNs
            armed = false
            stepDetected = true
            stepTimes.addLast(timestampNs)
        }

        // Sliding window for RMS.
        val sq = dynamic * dynamic
        window.addLast(timestampNs to sq)
        windowSumSq += sq
        while (window.isNotEmpty() && timestampNs - window.first().first > Thresholds.WINDOW_NS) {
            windowSumSq -= window.removeFirst().second
        }
        val rms = if (window.isEmpty()) 0f else sqrt((windowSumSq / window.size).coerceAtLeast(0.0)).toFloat()

        while (stepTimes.isNotEmpty() && timestampNs - stepTimes.first() > Thresholds.WINDOW_NS) {
            stepTimes.removeFirst()
        }

        return StepSnapshot(
            x = x,
            y = y,
            z = z,
            magnitude = magnitude,
            dynamic = dynamic,
            rms = rms,
            steps = steps,
            cadence = cadence(),
            state = classify(rms),
            stepDetected = stepDetected,
        )
    }

    private fun cadence(): Float {
        if (stepTimes.size < 2) return 0f
        val spanS = (stepTimes.last() - stepTimes.first()) / 1e9f
        return if (spanS <= 0f) 0f else (stepTimes.size - 1) / spanS * 60f
    }

    companion object {
        /** Exponential smoothing factor for a first-order low-pass filter. */
        fun alpha(dt: Float, tau: Float): Float = dt / (tau + dt)

        fun classify(rms: Float): MotionState = when {
            rms >= Thresholds.RUN_RMS -> MotionState.RUNNING
            rms >= Thresholds.WALK_RMS -> MotionState.WALKING
            else -> MotionState.STILL
        }
    }
}
