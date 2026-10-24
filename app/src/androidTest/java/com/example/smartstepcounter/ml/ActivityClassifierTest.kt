package com.example.smartstepcounter.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartstepcounter.sensor.StepDetector
import com.example.smartstepcounter.sensor.Thresholds
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/** The real .tflite from assets on a device: loading, tensor contract, sanity of predictions. */
@RunWith(AndroidJUnit4::class)
class ActivityClassifierTest {
    private lateinit var classifier: ActivityClassifier

    @Before
    fun setUp() {
        classifier = ActivityClassifier(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() = classifier.close()

    /**
     * 3 s of |a| = g + amplitude·sin(2πft) at 50 Hz through the real StepDetector,
     * last second of its dynamic output: the same path as in the app.
     */
    private fun syntheticWindow(freqHz: Double, amplitude: Double): FloatArray {
        val detector = StepDetector()
        val dynamics = (0 until 150).map { i ->
            val t = i / 50.0
            val z = (Thresholds.GRAVITY + amplitude * sin(2 * PI * freqHz * t)).toFloat()
            detector.process(0f, 0f, z, (t * 1e9).toLong()).dynamic
        }
        return dynamics.takeLast(MlConfig.WINDOW).toFloatArray()
    }

    @Test
    fun modelLoadsFromAssets() {
        assertNotNull(ActivityClassifier.tryCreate(InstrumentationRegistry.getInstrumentation().targetContext)?.also { it.close() })
    }

    @Test
    fun tensorShapes() {
        assertArrayEquals(intArrayOf(1, MlConfig.WINDOW), classifier.inputShape)
        assertArrayEquals(intArrayOf(1, AiLabel.entries.size), classifier.outputShape)
    }

    @Test
    fun probabilitiesSumToOne() {
        val p = classifier.classify(syntheticWindow(1.8, 2.5))
        assertEquals(AiLabel.entries.size, p.probabilities.size)
        assertEquals(1f, p.probabilities.sum(), 1e-3f)
        assertTrue(p.inferenceMs >= 0f)
    }

    @Test
    fun walkingWindowIsWalking() {
        val p = classifier.classify(syntheticWindow(1.8, 2.5))
        assertEquals(AiLabel.WALKING, p.label)
    }

    @Test
    fun runningWindowIsRunning() {
        assertEquals(AiLabel.RUNNING, classifier.classify(syntheticWindow(2.9, 8.0)).label)
    }

    @Test
    fun flatWindowIsStill() {
        assertEquals(AiLabel.STILL, classifier.classify(FloatArray(MlConfig.WINDOW)).label)
    }
}
