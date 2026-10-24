package com.example.smartstepcounter.decision

import com.example.smartstepcounter.actuator.Actuator
import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.actuator.VibrationPattern

/**
 * Applies [Decision]s to an [Actuator] when auto mode is on; otherwise only logs them,
 * so the rules can be observed without the phone buzzing.
 *
 * @return true if the decision was executed.
 */
class DecisionExecutor(
    private val actuator: Actuator,
    private val autoEnabled: () -> Boolean,
    private val logger: (String) -> Unit = {},
) {
    fun execute(decision: Decision): Boolean {
        if (!autoEnabled()) {
            logger("skipped (auto mode off): ${decision.action} ${decision.actuator} — ${decision.reason}")
            return false
        }
        logger("execute: ${decision.action} ${decision.actuator} — ${decision.reason}")
        val on = decision.action == DecisionAction.ACTIVATE
        when (decision.actuator) {
            ActuatorType.VIBRATION -> if (on) {
                actuator.triggerAlarm(decision.pattern ?: VibrationPattern.SINGLE, EventSource.AUTO, decision.reason)
            }
            ActuatorType.TORCH -> actuator.toggleFlashlight(on, EventSource.AUTO, decision.reason)
            ActuatorType.LAMP -> actuator.setVirtualLamp(on, EventSource.AUTO, decision.reason)
        }
        return true
    }
}
