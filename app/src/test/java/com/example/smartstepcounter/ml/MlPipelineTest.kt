package com.example.smartstepcounter.ml

import com.example.smartstepcounter.sensor.MotionState
import com.example.smartstepcounter.sensor.StepSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MlPipelineTest {

    /** Sample i has dynamic = i, so a window shows exactly which samples it contains. */
    private fun samples(count: Int, state: MotionState = MotionState.STILL): Flow<StepSnapshot> =
        (0 until count).asFlow().map { StepSnapshot(dynamic = it.toFloat(), state = state) }

    private fun expectedWindow(from: Int) = FloatArray(MlConfig.WINDOW) { (from + it).toFloat() }

    @Test
    fun noInferenceUntilWindowIsFull() = runTest {
        val classifier = FakeClassifier(listOf(AiLabel.STILL to 0.9f))
        val pipeline = MlPipeline({ classifier }, dispatcher = UnconfinedTestDispatcher(testScheduler))
        pipeline.run(samples(MlConfig.WINDOW - 1))
        assertTrue(classifier.windows.isEmpty())
        assertEquals(AiStatus.WARMING_UP, pipeline.insight.value.status)
        assertEquals(MlConfig.WINDOW - 1, pipeline.insight.value.bufferFill)
        assertNull(pipeline.insight.value.smoothed)
    }

    @Test
    fun firstWindowAfter50SamplesThenEveryHop() = runTest {
        val classifier = FakeClassifier(listOf(AiLabel.STILL to 0.9f))
        val results = mutableListOf<Prediction>()
        val pipeline = MlPipeline(
            { classifier },
            onResult = { raw, _ -> results += raw },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        pipeline.run(samples(100))
        // Windows end at samples 50, 75 and 100.
        assertEquals(3, classifier.windows.size)
        assertEquals(3, results.size)
        assertArrayEquals(expectedWindow(0), classifier.windows[0], 0f)
        assertArrayEquals(expectedWindow(25), classifier.windows[1], 0f)
        assertArrayEquals(expectedWindow(50), classifier.windows[2], 0f)
        assertEquals(3L, pipeline.insight.value.windowsClassified)
        assertEquals(AiStatus.READY, pipeline.insight.value.status)
    }

    @Test
    fun smoothedAndComparedWithThresholds() = runTest {
        val classifier = FakeClassifier(
            listOf(AiLabel.WALKING to 0.9f, AiLabel.WALKING to 0.9f, AiLabel.RUNNING to 0.9f)
        )
        val pipeline = MlPipeline({ classifier }, dispatcher = UnconfinedTestDispatcher(testScheduler))
        pipeline.run(samples(100, MotionState.WALKING))
        val insight = pipeline.insight.value
        assertEquals(AiLabel.RUNNING, insight.latest?.label)
        assertEquals(AiLabel.WALKING, insight.smoothed?.label)
        assertEquals(true, insight.matchesThresholds)
    }

    @Test
    fun shakeNeverMatchesThresholds() = runTest {
        val classifier = FakeClassifier(listOf(AiLabel.SHAKE to 0.9f))
        val pipeline = MlPipeline({ classifier }, dispatcher = UnconfinedTestDispatcher(testScheduler))
        pipeline.run(samples(50, MotionState.RUNNING))
        assertEquals(false, pipeline.insight.value.matchesThresholds)
    }

    @Test
    fun missingModelMeansUnavailable() = runTest {
        val pipeline = MlPipeline({ null }, dispatcher = UnconfinedTestDispatcher(testScheduler))
        pipeline.run(samples(100))
        assertEquals(AiStatus.UNAVAILABLE, pipeline.insight.value.status)
    }

    @Test
    fun failedInferenceIsSkipped() = runTest {
        val classifier = FakeClassifier(listOf(AiLabel.STILL to 0.9f)).apply { failNext = true }
        val logs = mutableListOf<String>()
        val pipeline = MlPipeline(
            { classifier },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            logger = { logs += it },
        )
        pipeline.run(samples(75))
        assertEquals(1, classifier.windows.size)
        assertEquals(1, logs.size)
    }

    @Test
    fun closeClosesClassifier() = runTest {
        val classifier = FakeClassifier(listOf(AiLabel.STILL to 0.9f))
        val pipeline = MlPipeline({ classifier }, dispatcher = UnconfinedTestDispatcher(testScheduler))
        pipeline.run(samples(1))
        assertFalse(classifier.closed)
        pipeline.close()
        assertTrue(classifier.closed)
    }
}
