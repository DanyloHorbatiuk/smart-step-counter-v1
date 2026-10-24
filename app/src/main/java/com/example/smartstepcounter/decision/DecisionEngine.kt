package com.example.smartstepcounter.decision

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.VibrationPattern
import com.example.smartstepcounter.sensor.MotionState
import com.example.smartstepcounter.sensor.StepSnapshot
import java.util.Locale

/**
 * Local automation: turns sensor snapshots into actuator decisions. Pure Kotlin,
 * driven by an explicit clock, so every rule is unit-tested on the JVM.
 *
 * The engine only *decides*; whether a decision is executed is up to [DecisionExecutor]
 * (auto mode switch). Its state advances either way, so the log stays consistent.
 *
 * Not thread-safe: call [evaluate] from one thread.
 */
class DecisionEngine(val config: DecisionConfig = DecisionConfig()) {
    // R1
    private var goalFired = false

    // R2
    private var runningSince: Long? = null
    private var notRunningSince: Long? = null
    private var torchOn = false

    // R3
    private var lastImpactAt: Long? = null

    /** @param nowMs monotonic time, ms (e.g. SystemClock.elapsedRealtime). */
    fun evaluate(snapshot: StepSnapshot, nowMs: Long): List<Decision> =
        listOfNotNull(goalRule(snapshot), runTorchRule(snapshot, nowMs), impactRule(snapshot, nowMs))

    /** R1: once per crossing of the goal; re-armed when the counter is reset below it. */
    private fun goalRule(s: StepSnapshot): Decision? {
        if (s.steps < config.stepGoal) {
            goalFired = false
            return null
        }
        if (goalFired) return null
        goalFired = true
        return Decision(
            rule = Rule.R1_GOAL,
            actuator = ActuatorType.VIBRATION,
            action = DecisionAction.ACTIVATE,
            reason = "R1: steps=${s.steps} >= ${config.stepGoal}",
            message = "Ціль ${config.stepGoal} кроків досягнута",
            pattern = VibrationPattern.DOUBLE_PULSE,
        )
    }

    /** R2: time hysteresis — on after [DecisionConfig.runOnDelayMs], off after [DecisionConfig.runOffDelayMs]. */
    private fun runTorchRule(s: StepSnapshot, now: Long): Decision? {
        if (s.state == MotionState.RUNNING) {
            notRunningSince = null
            val since = runningSince ?: now.also { runningSince = it }
            if (!torchOn && now - since >= config.runOnDelayMs) {
                torchOn = true
                return Decision(
                    rule = Rule.R2_RUN_TORCH,
                    actuator = ActuatorType.TORCH,
                    action = DecisionAction.ACTIVATE,
                    reason = "R2: RUNNING for ${now - since} ms >= ${config.runOnDelayMs}",
                    message = "Біг ≥ ${config.runOnDelayMs / 1000} с — ліхтарик увімкнено",
                )
            }
        } else {
            runningSince = null
            val since = notRunningSince ?: now.also { notRunningSince = it }
            if (torchOn && now - since >= config.runOffDelayMs) {
                torchOn = false
                return Decision(
                    rule = Rule.R2_RUN_TORCH,
                    actuator = ActuatorType.TORCH,
                    action = DecisionAction.DEACTIVATE,
                    reason = "R2: ${s.state} for ${now - since} ms >= ${config.runOffDelayMs}",
                    message = "Біг завершено — ліхтарик вимкнено",
                )
            }
        }
        return null
    }

    /** R3: |a| above the threshold, at most once per [DecisionConfig.impactCooldownMs]. */
    private fun impactRule(s: StepSnapshot, now: Long): Decision? {
        if (s.magnitude < config.impactThreshold) return null
        val last = lastImpactAt
        if (last != null && now - last < config.impactCooldownMs) return null
        lastImpactAt = now
        val g = String.format(Locale.US, "%.1f", s.magnitude)
        return Decision(
            rule = Rule.R3_IMPACT,
            actuator = ActuatorType.VIBRATION,
            action = DecisionAction.ACTIVATE,
            reason = "R3: |a|=$g >= ${config.impactThreshold} m/s²",
            message = "Різкий удар ($g м/с²) — тривога!",
            pattern = VibrationPattern.ALARM,
        )
    }
}
