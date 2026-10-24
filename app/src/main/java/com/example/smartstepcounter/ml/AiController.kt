package com.example.smartstepcounter.ml

import com.example.smartstepcounter.decision.AiRules
import com.example.smartstepcounter.decision.DecisionAction
import com.example.smartstepcounter.decision.DecisionExecutor
import com.example.smartstepcounter.history.ISensorHistoryRepository
import java.util.Locale

/**
 * Connects classifier output with the previous labs:
 * - Lab 5: record interval of [com.example.smartstepcounter.history.SensorRecorder];
 * - Lab 4: anomaly alarm through [DecisionExecutor] (respects the auto mode switch);
 *   the actuator event then reaches actuator_events like any other.
 *
 * @param clock monotonic time, ms.
 * @param onAlert user-facing text when an alarm fires (snackbar).
 */
class AiController(
    private val rules: AiRules,
    private val executor: DecisionExecutor,
    private val history: ISensorHistoryRepository,
    private val clock: () -> Long,
    private val logger: (String) -> Unit = {},
    private val onAlert: (String) -> Unit = {},
) {
    fun onWindow(raw: Prediction, smoothed: Prediction) {
        val now = clock()

        val interval = rules.recordInterval(smoothed, now)
        val current = history.recordIntervalMs.value
        if (interval != current) {
            logger("record interval $current -> $interval ms (${smoothed.label} ${smoothed.confidence.fmt()})")
            history.setRecordInterval(interval)
        }

        rules.shakeRule(raw, now).forEach { decision ->
            val executed = executor.execute(decision)
            if (decision.action == DecisionAction.ACTIVATE && decision.pattern != null) {
                onAlert(if (executed) decision.message else "${decision.message} (авто-режим вимкнено)")
            }
        }
    }

    private fun Float.fmt() = String.format(Locale.US, "%.2f", this)
}
