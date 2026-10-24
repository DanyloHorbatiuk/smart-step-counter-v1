package com.example.smartstepcounter.decision

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.VibrationPattern

enum class Rule(val title: String) {
    R1_GOAL("R1 Ціль досягнута"),
    R2_RUN_TORCH("R2 Біг — сигнальний ліхтар"),
    R3_IMPACT("R3 Різкий удар"),
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
)
