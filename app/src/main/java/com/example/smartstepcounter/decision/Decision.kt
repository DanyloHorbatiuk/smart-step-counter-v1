package com.example.smartstepcounter.decision

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.actuator.VibrationPattern

enum class Rule(val title: String) {
    R1_GOAL("R1 Ціль досягнута"),
    R2_RUN_TORCH("R2 Біг — сигнальний ліхтар"),
    R3_IMPACT("R3 Різкий удар"),
    R4_AI_ANOMALY("R4 AI: аномалія руху"),
}

enum class DecisionAction { ACTIVATE, DEACTIVATE }

/**
 * What a rule wants an actuator to do. [message] is a short user-facing text (snackbar),
 * [reason] the detailed one for the event log.
 */
data class Decision(
    val rule: Rule,
    val actuator: ActuatorType,
    val action: DecisionAction,
    val reason: String,
    val message: String,
    /** Only for [ActuatorType.VIBRATION]. */
    val pattern: VibrationPattern? = null,
    /** Who is reported in the actuator log: threshold rules are AUTO, model-driven ones AI. */
    val source: EventSource = EventSource.AUTO,
)
