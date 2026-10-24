package com.example.smartstepcounter.ml

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.actuator.FakeActuator
import com.example.smartstepcounter.actuator.VibrationPattern
import com.example.smartstepcounter.data.local.FakeActuatorEventDao
import com.example.smartstepcounter.data.local.FakeSensorRecordDao
import com.example.smartstepcounter.decision.AiRules
import com.example.smartstepcounter.decision.DecisionExecutor
import com.example.smartstepcounter.history.SensorHistoryRepository
import com.example.smartstepcounter.sensor.StepSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake classifier -> MlPipeline -> AiController -> repository interval + actuator. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiIntegrationTest {
    private val actuator = FakeActuator()
    private var auto = true
    private val alerts = mutableListOf<String>()
    private val history = SensorHistoryRepository(FakeSensorRecordDao(), FakeActuatorEventDao(), logger = {})

    /** Sample time at 50 Hz, ms. */
    private var now = 0L

    private fun controller() = AiController(
        rules = AiRules(),
        executor = DecisionExecutor(actuator, { auto }),
        history = history,
        clock = { now },
        onAlert = { alerts += it },
    )

    /** Runs [seconds] of samples; the classifier answers per window from [script]. */
    private suspend fun TestScope.drive(script: List<Pair<AiLabel, Float>>, seconds: Int) {
        val controller = controller()
        val pipeline = MlPipeline(
            { FakeClassifier(script) },
            onResult = controller::onWindow,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        pipeline.run(flow {
            repeat(seconds * 50) {
                now = it * 20L
                emit(StepSnapshot())
            }
        })
    }

    @Test
    fun runningSpeedsUpRecordingAndReturnsAfterward() = runTest {
        // 6 windows of running (0.5 s each), then calm.
        val script = List(6) { AiLabel.RUNNING to 0.9f } + (AiLabel.STILL to 0.95f)
        val intervals = mutableListOf<Long>()
        val controller = controller()
        val pipeline = MlPipeline(
            { FakeClassifier(script) },
            onResult = { raw, smoothed ->
                controller.onWindow(raw, smoothed)
                intervals += history.recordIntervalMs.value
            },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        pipeline.run(flow {
            repeat(10 * 50) {
                now = it * 20L
                emit(StepSnapshot())
            }
        })
        // Windows every 0.5 s, 19 in 10 s. The first STILL window still votes RUNNING, but its
        // averaged confidence (0.61) is below 0.7, so running ends with window 6; fast mode
        // then holds for 5 more windows (< 3 s) and returns to 1000 ms.
        assertEquals(List(6 + 5) { 250L } + List(8) { 1_000L }, intervals)
    }

    @Test
    fun twoShakeWindowsTriggerAlarmOnceDuringCooldown() = runTest {
        drive(listOf(AiLabel.SHAKE to 0.91f), seconds = 8)
        assertEquals(listOf(VibrationPattern.ALARM), actuator.vibrations)
        assertTrue(actuator.state.value.torchOn)
        val vibration = actuator.recorded.first { it.actuator == ActuatorType.VIBRATION }
        assertEquals(EventSource.AI, vibration.source)
        assertTrue(vibration.reason, vibration.reason.startsWith("Аномалія: SHAKE 0,91"))
        assertEquals(1, alerts.size)
    }

    @Test
    fun singleShakeWindowDoesNothing() = runTest {
        drive(listOf(AiLabel.WALKING to 0.9f, AiLabel.SHAKE to 0.95f, AiLabel.WALKING to 0.9f), seconds = 5)
        assertTrue(actuator.recorded.isEmpty())
    }

    @Test
    fun autoModeOffOnlyNotifies() = runTest {
        auto = false
        drive(listOf(AiLabel.SHAKE to 0.95f), seconds = 3)
        assertTrue(actuator.recorded.isEmpty())
        assertTrue(alerts.single().endsWith("(авто-режим вимкнено)"))
    }
}
