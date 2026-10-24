package com.example.smartstepcounter.decision

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.actuator.FakeActuator
import com.example.smartstepcounter.actuator.VibrationPattern
import com.example.smartstepcounter.sensor.MotionState
import com.example.smartstepcounter.sensor.StepSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Engine + executor + fake actuator: the whole local automation path. */
class DecisionExecutorTest {
    private val actuator = FakeActuator()
    private var auto = true
    private val logs = mutableListOf<String>()
    private val engine = DecisionEngine()
    private val executor = DecisionExecutor(actuator, { auto }, { logs += it })

    private fun run(snapshot: StepSnapshot, t: Long) = engine.evaluate(snapshot, t).forEach(executor::execute)

    @Test
    fun autoModeExecutesDecisions() {
        run(StepSnapshot(steps = 50), 0)
        run(StepSnapshot(magnitude = 30f), 100)
        assertEquals(listOf(VibrationPattern.DOUBLE_PULSE, VibrationPattern.ALARM), actuator.vibrations)
        assertTrue(actuator.recorded.all { it.source == EventSource.AUTO })
    }

    @Test
    fun runningTurnsTorchOnAndOff() {
        (0..3_000L step 100).forEach { run(StepSnapshot(state = MotionState.RUNNING), it) }
        assertTrue(actuator.state.value.torchOn)
        (3_100..8_100L step 100).forEach { run(StepSnapshot(state = MotionState.STILL), it) }
        assertFalse(actuator.state.value.torchOn)
        assertEquals(listOf(true, false), actuator.recorded.filter { it.actuator == ActuatorType.TORCH }.map { it.on })
    }

    @Test
    fun disabledAutoModeOnlyLogs() {
        auto = false
        run(StepSnapshot(steps = 50), 0)
        run(StepSnapshot(magnitude = 30f), 100)
        assertTrue(actuator.recorded.isEmpty())
        assertEquals(2, logs.count { it.startsWith("skipped") })
    }
}
