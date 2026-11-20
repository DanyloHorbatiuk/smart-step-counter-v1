package com.example.smartstepcounter

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.FakeActuator
import com.example.smartstepcounter.actuator.VibrationPattern
import com.example.smartstepcounter.ble.BleDirection
import com.example.smartstepcounter.ble.BleUuids
import com.example.smartstepcounter.ble.MockBleConnector
import com.example.smartstepcounter.ble.MockBleScanner
import com.example.smartstepcounter.cloud.SensorData
import com.example.smartstepcounter.data.local.FakeActuatorEventDao
import com.example.smartstepcounter.data.local.FakeSensorRecordDao
import com.example.smartstepcounter.data.local.SmartRule
import com.example.smartstepcounter.decision.AiRules
import com.example.smartstepcounter.decision.DecisionEngine
import com.example.smartstepcounter.decision.DecisionExecutor
import com.example.smartstepcounter.decision.SmartAction
import com.example.smartstepcounter.decision.SmartMetric
import com.example.smartstepcounter.decision.SmartRuleEvaluator
import com.example.smartstepcounter.history.SensorHistoryRepository
import com.example.smartstepcounter.ml.AiController
import com.example.smartstepcounter.ml.AiLabel
import com.example.smartstepcounter.ml.FakeClassifier
import com.example.smartstepcounter.ml.MlPipeline
import com.example.smartstepcounter.ml.prediction
import com.example.smartstepcounter.sensor.StepSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lab 7: sensor samples -> ML -> decision -> actuator + BLE command -> actuator_events,
 * assembled from the same fakes as the per-lab tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndScenarioTest {
    private var auto = true
    private var now = 0L

    private fun TestScope.connector() = MockBleConnector(backgroundScope, clock = { currentTime }, logger = {})

    /** Like SmartActuator: BLE writes go to the COMMAND characteristic in the app scope. */
    private fun actuator(connector: MockBleConnector, scope: CoroutineScope) =
        FakeActuator(onBle = { scope.launch { connector.sendData(it, BleUuids.COMMAND) } })

    private fun MockBleConnector.commandsSent() =
        log.value.filter { it.direction == BleDirection.TX && it.characteristic == BleUuids.COMMAND }.map { it.message }

    /** [seconds] of 50 Hz samples through MlPipeline; the classifier answers per window from [script]. */
    private suspend fun TestScope.driveMl(controller: AiController, script: List<Pair<AiLabel, Float>>, seconds: Int) {
        val pipeline = MlPipeline({ FakeClassifier(script) }, controller::onWindow, UnconfinedTestDispatcher(testScheduler))
        pipeline.run(flow {
            repeat(seconds * 50) {
                now = it * 20L
                emit(StepSnapshot())
            }
        })
    }

    @Test
    fun shakeIsCriticalAlarmReachesActuatorBleAndHistory() = runTest {
        val connector = connector()
        connector.connect(MockBleScanner.STEP_HUB)
        val actuator = actuator(connector, this)
        val actuatorDao = FakeActuatorEventDao()
        val history = SensorHistoryRepository(FakeSensorRecordDao(), actuatorDao, logger = {})
        val controller = AiController(AiRules(), DecisionExecutor(actuator, { auto }), history, { now })

        driveMl(controller, List(4) { AiLabel.SHAKE to 0.95f }, seconds = 3)
        advanceUntilIdle()
        actuator.recorded.forEach { history.insertActuatorEvent(it) } // what SensorHistoryRepository.start does

        assertEquals(listOf(VibrationPattern.ALARM), actuator.vibrations)
        assertTrue(actuator.state.value.torchOn)
        assertEquals(listOf("ALARM:R4_AI_ANOMALY"), connector.commandsSent())
        assertEquals(
            listOf("VIBRATION", "TORCH", "BLE"),
            actuatorDao.rows.value.map { it.actuator },
        )
    }

    @Test
    fun impactSendsBleAlarmAndReportCarriesAiLabel() = runTest {
        val connector = connector()
        connector.connect(MockBleScanner.STEP_HUB)
        val actuator = actuator(connector, this)
        val executor = DecisionExecutor(actuator, { auto })
        val impact = StepSnapshot(magnitude = 30f, steps = 7)

        DecisionEngine().evaluate(impact, 0).forEach(executor::execute)
        advanceUntilIdle()

        assertEquals(listOf(VibrationPattern.ALARM), actuator.vibrations)
        assertEquals(listOf("ALARM:R3_IMPACT"), connector.commandsSent())
        val report = SensorData.from(impact, "dev", 1L, prediction(AiLabel.SHAKE, 0.9f))
        assertEquals("SHAKE", report.aiLabel)
        assertEquals(0.9f, report.aiConfidence)
    }

    @Test
    fun disconnectedBleDoesNotCrashAndIsLogged() = runTest {
        val connector = connector() // never connected
        val actuator = actuator(connector, this)

        DecisionEngine().evaluate(StepSnapshot(magnitude = 30f), 0).forEach(DecisionExecutor(actuator, { auto })::execute)
        advanceUntilIdle()

        assertEquals(listOf(VibrationPattern.ALARM), actuator.vibrations) // the local alarm still works
        assertTrue(connector.commandsSent().isEmpty())
        assertTrue(connector.log.value.any { it.message.startsWith("Write failed: not connected") })
    }

    @Test
    fun smartRuleRespectsAutoMode() = runTest {
        val actuator = FakeActuator()
        val executor = DecisionExecutor(actuator, { auto })
        val evaluator = SmartRuleEvaluator()
        val rules = listOf(SmartRule(id = 1, metric = SmartMetric.ACCEL_MAGNITUDE, threshold = 10f, action = SmartAction.VIBRATE))
        fun feed(from: Long) = (0..2L).forEach { i ->
            evaluator.evaluate(rules, StepSnapshot(magnitude = 12f), 0f, from + i * 20).forEach(executor::execute)
        }

        feed(0)
        assertEquals(listOf(VibrationPattern.SINGLE), actuator.vibrations)

        auto = false
        feed(10_000) // after the cooldown: decided, but only logged
        assertEquals(1, actuator.vibrations.size)
        assertFalse(actuator.recorded.any { it.actuator == ActuatorType.BLE })
    }
}
