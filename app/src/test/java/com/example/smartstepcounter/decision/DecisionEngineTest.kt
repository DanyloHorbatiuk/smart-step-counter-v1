package com.example.smartstepcounter.decision

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.VibrationPattern
import com.example.smartstepcounter.sensor.MotionState
import com.example.smartstepcounter.sensor.StepSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionEngineTest {
    private val engine = DecisionEngine()

    private fun snap(steps: Int = 0, state: MotionState = MotionState.STILL, magnitude: Float = 9.8f) =
        StepSnapshot(steps = steps, state = state, magnitude = magnitude)

    /** Feeds [state] every 100 ms from [from] to [to] inclusive and collects decisions. */
    private fun feed(state: MotionState, from: Long, to: Long): List<Decision> =
        (from..to step 100).flatMap { engine.evaluate(snap(state = state), it) }

    private fun List<Decision>.torch() = filter { it.actuator == ActuatorType.TORCH }

    // R1

    @Test
    fun goalFiresExactlyOnce() {
        val fired = (0..80).flatMap { steps -> engine.evaluate(snap(steps = steps), steps * 500L) }
            .filter { it.rule == Rule.R1_GOAL }
        assertEquals(1, fired.size)
        assertEquals(VibrationPattern.DOUBLE_PULSE, fired[0].pattern)
        assertEquals("Ціль 50 кроків досягнута", fired[0].message)
    }

    @Test
    fun goalFiresAgainOnlyAfterReset() {
        engine.evaluate(snap(steps = 50), 0)
        assertTrue(engine.evaluate(snap(steps = 51), 100).none { it.rule == Rule.R1_GOAL })
        engine.evaluate(snap(steps = 0), 200) // counter reset
        val again = engine.evaluate(snap(steps = 50), 300)
        assertEquals(1, again.count { it.rule == Rule.R1_GOAL })
    }

    // R2

    @Test
    fun shortRunningDoesNotTurnTorchOn() {
        assertTrue(feed(MotionState.RUNNING, 0, 2_900).torch().isEmpty())
    }

    @Test
    fun runningForThreeSecondsTurnsTorchOn() {
        val decisions = feed(MotionState.RUNNING, 0, 3_000).torch()
        assertEquals(1, decisions.size)
        assertEquals(DecisionAction.ACTIVATE, decisions[0].action)
    }

    @Test
    fun torchTurnsOffOnlyAfterFiveSecondsWithoutRunning() {
        feed(MotionState.RUNNING, 0, 3_000)
        assertTrue(feed(MotionState.WALKING, 3_100, 8_000).torch().isEmpty()) // 4.9 s
        val off = feed(MotionState.WALKING, 8_100, 8_100).torch()
        assertEquals(1, off.size)
        assertEquals(DecisionAction.DEACTIVATE, off[0].action)
    }

    @Test
    fun shortDipDoesNotTurnTorchOff() {
        feed(MotionState.RUNNING, 0, 3_000)
        feed(MotionState.WALKING, 3_100, 4_100) // 1 s dip
        assertTrue(feed(MotionState.RUNNING, 4_200, 6_000).torch().isEmpty())
        // The dip timer restarted: 4.9 s of walking after the dip still keeps the torch on.
        assertTrue(feed(MotionState.STILL, 6_100, 11_000).torch().isEmpty())
        assertEquals(DecisionAction.DEACTIVATE, feed(MotionState.STILL, 11_100, 11_100).torch().single().action)
    }

    @Test
    fun interruptedRunningRestartsOnTimer() {
        feed(MotionState.RUNNING, 0, 2_000)
        feed(MotionState.WALKING, 2_100, 2_100)
        assertTrue(feed(MotionState.RUNNING, 2_200, 5_100).torch().isEmpty()) // 2.9 s since restart
        assertEquals(1, feed(MotionState.RUNNING, 5_200, 5_200).torch().size)
    }

    // R3

    @Test
    fun impactAboveThresholdTriggersAlarm() {
        val decisions = engine.evaluate(snap(magnitude = 25f), 0)
        val alarm = decisions.single { it.rule == Rule.R3_IMPACT }
        assertEquals(VibrationPattern.ALARM, alarm.pattern)
    }

    @Test
    fun impactBelowThresholdIsIgnored() {
        assertTrue(engine.evaluate(snap(magnitude = 24.9f), 0).none { it.rule == Rule.R3_IMPACT })
    }

    @Test
    fun impactHasTenSecondCooldown() {
        fun impact(t: Long) = engine.evaluate(snap(magnitude = 30f), t).count { it.rule == Rule.R3_IMPACT }
        assertEquals(1, impact(0))
        assertEquals(0, impact(500))
        assertEquals(0, impact(9_999))
        assertEquals(1, impact(10_000))
    }
}
