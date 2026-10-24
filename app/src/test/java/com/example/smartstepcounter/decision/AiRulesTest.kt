package com.example.smartstepcounter.decision

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.actuator.VibrationPattern
import com.example.smartstepcounter.ml.AiLabel
import com.example.smartstepcounter.ml.prediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRulesTest {
    private val rules = AiRules()

    @Test
    fun confidentRunningSwitchesToFastRecording() {
        assertEquals(1_000L, rules.recordInterval(prediction(AiLabel.WALKING, 0.9f), 0))
        assertEquals(250L, rules.recordInterval(prediction(AiLabel.RUNNING, 0.7f), 500))
    }

    @Test
    fun unsureRunningKeepsNormalInterval() {
        assertEquals(1_000L, rules.recordInterval(prediction(AiLabel.RUNNING, 0.69f), 0))
    }

    @Test
    fun fastRecordingEndsThreeSecondsAfterRunning() {
        rules.recordInterval(prediction(AiLabel.RUNNING, 0.9f), 1_000)
        assertEquals(250L, rules.recordInterval(prediction(AiLabel.WALKING, 0.9f), 1_500))
        assertEquals(250L, rules.recordInterval(prediction(AiLabel.WALKING, 0.9f), 3_999))
        assertEquals(1_000L, rules.recordInterval(prediction(AiLabel.WALKING, 0.9f), 4_000))
        assertEquals(1_000L, rules.recordInterval(prediction(AiLabel.WALKING, 0.9f), 4_500))
    }

    @Test
    fun runningAgainResetsTheExitTimer() {
        rules.recordInterval(prediction(AiLabel.RUNNING, 0.9f), 0)
        rules.recordInterval(prediction(AiLabel.STILL, 0.9f), 2_000)
        rules.recordInterval(prediction(AiLabel.RUNNING, 0.9f), 2_500)
        assertEquals(250L, rules.recordInterval(prediction(AiLabel.STILL, 0.9f), 5_000))
    }

    @Test
    fun oneShakeWindowIsNotEnough() {
        assertTrue(rules.shakeRule(prediction(AiLabel.SHAKE, 0.95f), 0).isEmpty())
        assertTrue(rules.shakeRule(prediction(AiLabel.WALKING, 0.9f), 500).isEmpty())
        assertTrue(rules.shakeRule(prediction(AiLabel.SHAKE, 0.95f), 1_000).isEmpty())
    }

    @Test
    fun lowConfidenceShakeIsIgnored() {
        rules.shakeRule(prediction(AiLabel.SHAKE, 0.79f), 0)
        assertTrue(rules.shakeRule(prediction(AiLabel.SHAKE, 0.79f), 500).isEmpty())
    }

    @Test
    fun twoShakeWindowsRaiseAlarmAndTorch() {
        rules.shakeRule(prediction(AiLabel.SHAKE, 0.85f), 0)
        val decisions = rules.shakeRule(prediction(AiLabel.SHAKE, 0.91f), 500)
        assertEquals(listOf(ActuatorType.VIBRATION, ActuatorType.TORCH), decisions.map { it.actuator })
        assertTrue(decisions.all { it.source == EventSource.AI && it.rule == Rule.R4_AI_ANOMALY })
        assertTrue(decisions.all { it.action == DecisionAction.ACTIVATE })
        assertEquals(VibrationPattern.ALARM, decisions[0].pattern)
        assertEquals("Аномалія: SHAKE 0,91", decisions[0].reason)
    }

    @Test
    fun cooldownThenTorchOff() {
        rules.shakeRule(prediction(AiLabel.SHAKE, 0.9f), 0)
        rules.shakeRule(prediction(AiLabel.SHAKE, 0.9f), 500) // alarm
        // Still shaking within 10 s: no second alarm.
        (1_000..10_000L step 500).forEach {
            assertTrue("t=$it", rules.shakeRule(prediction(AiLabel.SHAKE, 0.9f), it).isEmpty())
        }
        // Cooldown over and shaking continues: a new alarm.
        assertEquals(2, rules.shakeRule(prediction(AiLabel.SHAKE, 0.9f), 10_500).size)
        // Calm during the cooldown keeps the torch on, afterwards it goes off once.
        assertTrue(rules.shakeRule(prediction(AiLabel.STILL, 0.9f), 15_000).isEmpty())
        val off = rules.shakeRule(prediction(AiLabel.STILL, 0.9f), 20_500)
        assertEquals(DecisionAction.DEACTIVATE, off.single().action)
        assertEquals(ActuatorType.TORCH, off.single().actuator)
        assertTrue(rules.shakeRule(prediction(AiLabel.STILL, 0.9f), 21_000).isEmpty())
    }
}
