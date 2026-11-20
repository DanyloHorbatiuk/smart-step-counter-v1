package com.example.smartstepcounter.decision

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.actuator.VibrationPattern
import com.example.smartstepcounter.data.local.SmartRule
import com.example.smartstepcounter.sensor.StepSnapshot
import java.util.Locale

/** Values a Smart rule can compare; [unit] is shown in the UI. */
enum class SmartMetric(val title: String, val unit: String) {
    ACCEL_MAGNITUDE("|a|", "м/с²"),
    RMS("RMS", "м/с²"),
    CADENCE("Каденс", "кроків/хв"),
    AI_CONFIDENCE_SHAKE("AI: SHAKE", "0…1"),
}

enum class SmartAction(val title: String) {
    VIBRATE("вібрація"),
    TORCH_ON("ліхтарик увімк."),
    BLE_ALARM("BLE-тривога"),
}

/**
 * Lab 7: user-defined rules "metric > threshold → action". Pure Kotlin with an explicit
 * clock, like [DecisionEngine]; not thread-safe.
 *
 * Anti-jitter: a rule fires once its condition held for [streak] samples in a row, then
 * stays silent for [cooldownMs].
 */
class SmartRuleEvaluator(
    val streak: Int = 3,
    val cooldownMs: Long = 5_000,
) {
    private class RuleState(var streak: Int = 0, var lastFiredAt: Long? = null)

    private val states = mutableMapOf<Long, RuleState>()

    /** @param shakeConfidence probability of SHAKE in the latest model window, 0 without ML. */
    fun evaluate(rules: List<SmartRule>, snapshot: StepSnapshot, shakeConfidence: Float, nowMs: Long): List<Decision> {
        // Deleted rules: forget their state, so a new rule with a reused id starts clean.
        states.keys.retainAll(rules.mapTo(HashSet()) { it.id })
        return rules.mapNotNull { rule ->
            val state = states.getOrPut(rule.id) { RuleState() }
            val value = value(rule.metric, snapshot, shakeConfidence)
            if (!rule.enabled || value <= rule.threshold) {
                state.streak = 0
                return@mapNotNull null
            }
            state.streak++
            val last = state.lastFiredAt
            if (state.streak < streak || (last != null && nowMs - last < cooldownMs)) return@mapNotNull null
            state.lastFiredAt = nowMs
            state.streak = 0
            decision(rule, value)
        }
    }

    private fun decision(rule: SmartRule, value: Float): Decision {
        val condition = "${rule.metric.title}=${value.fmt()} > ${rule.threshold.fmt()}"
        val reason = "Smart #${rule.id}: $condition"
        val message = "Smart-сценарій: $condition → ${rule.action.title}"
        return when (rule.action) {
            SmartAction.VIBRATE -> Decision(
                Rule.SMART, ActuatorType.VIBRATION, DecisionAction.ACTIVATE, reason, message,
                pattern = VibrationPattern.SINGLE, source = EventSource.SMART,
            )
            SmartAction.TORCH_ON -> Decision(
                Rule.SMART, ActuatorType.TORCH, DecisionAction.ACTIVATE, reason, message, source = EventSource.SMART,
            )
            SmartAction.BLE_ALARM -> Decision(
                Rule.SMART, ActuatorType.BLE, DecisionAction.ACTIVATE, reason, message, source = EventSource.SMART,
            )
        }
    }

    private fun Float.fmt() = String.format(Locale.US, "%.2f", this)

    companion object {
        fun value(metric: SmartMetric, s: StepSnapshot, shakeConfidence: Float): Float = when (metric) {
            SmartMetric.ACCEL_MAGNITUDE -> s.magnitude
            SmartMetric.RMS -> s.rms
            SmartMetric.CADENCE -> s.cadence
            SmartMetric.AI_CONFIDENCE_SHAKE -> shakeConfidence
        }
    }
}
