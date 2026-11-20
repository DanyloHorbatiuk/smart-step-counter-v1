package com.example.smartstepcounter.decision

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.data.local.SmartRule
import com.example.smartstepcounter.sensor.StepSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRuleEvaluatorTest {
    private val evaluator = SmartRuleEvaluator()
    private val vibrateOver10 = SmartRule(id = 1, metric = SmartMetric.ACCEL_MAGNITUDE, threshold = 10f, action = SmartAction.VIBRATE)

    private fun eval(rules: List<SmartRule>, magnitude: Float, t: Long, shake: Float = 0f) =
        evaluator.evaluate(rules, StepSnapshot(magnitude = magnitude), shake, t)

    @Test
    fun firesOnThirdConsecutiveSample() {
        val rules = listOf(vibrateOver10)
        assertTrue(eval(rules, 12f, 0).isEmpty())
        assertTrue(eval(rules, 12f, 20).isEmpty())
        val fired = eval(rules, 12f, 40).single()
        assertEquals(ActuatorType.VIBRATION, fired.actuator)
        assertEquals(EventSource.SMART, fired.source)
        assertEquals(Rule.SMART, fired.rule)
    }

    @Test
    fun conditionIsStrictlyGreater() {
        val rules = listOf(vibrateOver10)
        assertTrue((0..10L).flatMap { eval(rules, 10f, it * 20) }.isEmpty())
    }

    @Test
    fun interruptedSeriesStartsOver() {
        val rules = listOf(vibrateOver10)
        eval(rules, 12f, 0)
        eval(rules, 12f, 20)
        assertTrue(eval(rules, 9f, 40).isEmpty()) // jitter below the threshold
        assertTrue(eval(rules, 12f, 60).isEmpty())
        assertTrue(eval(rules, 12f, 80).isEmpty())
        assertEquals(1, eval(rules, 12f, 100).size)
    }

    @Test
    fun cooldownOfFiveSeconds() {
        val rules = listOf(vibrateOver10)
        val fired = (0 until 300).flatMap { eval(rules, 15f, it * 20L) } // 6 s above the threshold
        // t = 40 ms and again once 5 s passed (t = 5040 ms).
        assertEquals(2, fired.size)
    }

    @Test
    fun disabledRuleNeverFires() {
        val rules = listOf(vibrateOver10.copy(enabled = false))
        assertTrue((0 until 100).flatMap { eval(rules, 20f, it * 20L) }.isEmpty())
    }

    @Test
    fun actionsMapToActuators() {
        val rules = listOf(
            SmartRule(id = 1, metric = SmartMetric.RMS, threshold = 1f, action = SmartAction.TORCH_ON),
            SmartRule(id = 2, metric = SmartMetric.AI_CONFIDENCE_SHAKE, threshold = 0.5f, action = SmartAction.BLE_ALARM),
        )
        val snapshot = StepSnapshot(rms = 2f)
        val fired = (0..2L).flatMap { evaluator.evaluate(rules, snapshot, 0.9f, it * 20) }
        assertEquals(listOf(ActuatorType.TORCH, ActuatorType.BLE), fired.map { it.actuator })
        assertTrue(fired.all { it.action == DecisionAction.ACTIVATE })
        assertEquals("ALARM:SMART", fired[1].bleCommand)
    }

    @Test
    fun metricValues() {
        val s = StepSnapshot(magnitude = 1f, rms = 2f, cadence = 3f)
        assertEquals(1f, SmartRuleEvaluator.value(SmartMetric.ACCEL_MAGNITUDE, s, 4f))
        assertEquals(2f, SmartRuleEvaluator.value(SmartMetric.RMS, s, 4f))
        assertEquals(3f, SmartRuleEvaluator.value(SmartMetric.CADENCE, s, 4f))
        assertEquals(4f, SmartRuleEvaluator.value(SmartMetric.AI_CONFIDENCE_SHAKE, s, 4f))
    }

    @Test
    fun deletedRuleStateIsForgotten() {
        eval(listOf(vibrateOver10), 12f, 0)
        eval(listOf(vibrateOver10), 12f, 20)
        eval(emptyList(), 12f, 40) // rule deleted
        // Re-added with the same id: the series starts from zero.
        assertTrue(eval(listOf(vibrateOver10), 12f, 60).isEmpty())
    }
}
