package com.example.smartstepcounter.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class StepDetectorTest {

    private val sampleRateHz = 50
    private val dtNs = 1_000_000_000L / sampleRateHz

    /**
     * Feeds [seconds] of synthetic data: gravity on Z plus a vertical oscillation
     * of [amplitude] m/s² at [freqHz] (one oscillation = one step) and random noise.
     */
    private fun simulate(
        detector: StepDetector,
        seconds: Int,
        freqHz: Float,
        amplitude: Float,
        noise: Float = 0.1f,
        startNs: Long = 0L,
    ): StepSnapshot {
        val random = Random(42)
        var last = StepSnapshot()
        for (i in 0 until seconds * sampleRateHz) {
            val t = i.toFloat() / sampleRateHz
            val wave = amplitude * sin(2 * PI * freqHz * t).toFloat()
            val n = { (random.nextFloat() * 2 - 1) * noise }
            last = detector.process(
                x = n(),
                y = n(),
                z = Thresholds.GRAVITY + wave + n(),
                timestampNs = startNs + i * dtNs,
            )
        }
        return last
    }

    @Test
    fun stillDevice_noSteps_stateStill() {
        val result = simulate(StepDetector(), seconds = 10, freqHz = 0f, amplitude = 0f)
        assertEquals(0, result.steps)
        assertEquals(MotionState.STILL, result.state)
    }

    @Test
    fun walking_countsOneStepPerOscillation() {
        val result = simulate(StepDetector(), seconds = 20, freqHz = 1.8f, amplitude = 2.5f)
        // 20 s * 1.8 Hz = 36 steps; allow a small error for filter warm-up.
        assertTrue("steps=${result.steps}", result.steps in 33..37)
        assertEquals(MotionState.WALKING, result.state)
        assertTrue("cadence=${result.cadence}", result.cadence in 95f..120f)
    }

    @Test
    fun running_isClassifiedAsRunning() {
        val result = simulate(StepDetector(), seconds = 10, freqHz = 2.8f, amplitude = 8f)
        assertTrue("steps=${result.steps}", result.steps in 25..29)
        assertEquals(MotionState.RUNNING, result.state)
        assertTrue("cadence=${result.cadence}", result.cadence > 150f)
    }

    @Test
    fun smallTremor_belowThreshold_isNotCountedAsSteps() {
        val result = simulate(StepDetector(), seconds = 10, freqHz = 2f, amplitude = 0.8f)
        assertEquals(0, result.steps)
    }

    @Test
    fun highFrequencyVibration_isLimitedByMinStepInterval() {
        // 10 Hz shaking would be 100 "steps"; the interval limit caps it at 4 per second.
        val result = simulate(StepDetector(), seconds = 10, freqHz = 10f, amplitude = 15f)
        assertTrue("steps=${result.steps}", result.steps <= 40)
    }

    @Test
    fun resume_keepsStepCount_reset_clearsIt() {
        val detector = StepDetector()
        val walked = simulate(detector, seconds = 10, freqHz = 1.8f, amplitude = 2.5f)
        detector.resume()
        val afterResume = simulate(detector, seconds = 2, freqHz = 0f, amplitude = 0f, startNs = 60_000_000_000L)
        assertEquals(walked.steps, afterResume.steps)
        detector.reset()
        val afterReset = simulate(detector, seconds = 2, freqHz = 0f, amplitude = 0f)
        assertEquals(0, afterReset.steps)
    }

    @Test
    fun classify_usesThresholdBoundaries() {
        assertEquals(MotionState.STILL, StepDetector.classify(Thresholds.WALK_RMS - 0.01f))
        assertEquals(MotionState.WALKING, StepDetector.classify(Thresholds.WALK_RMS))
        assertEquals(MotionState.WALKING, StepDetector.classify(Thresholds.RUN_RMS - 0.01f))
        assertEquals(MotionState.RUNNING, StepDetector.classify(Thresholds.RUN_RMS))
    }
}
