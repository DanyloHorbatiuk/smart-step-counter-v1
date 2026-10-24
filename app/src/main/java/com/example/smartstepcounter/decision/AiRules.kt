package com.example.smartstepcounter.decision

import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.actuator.VibrationPattern
import com.example.smartstepcounter.ml.AiLabel
import com.example.smartstepcounter.ml.Prediction
import java.util.Locale

/** Thresholds of the model-driven rules (Lab 6). */
data class AiRulesConfig(
    /** Smoothed RUNNING at least this confident switches to fast recording. */
    val runConfidence: Float = 0.7f,
    /** Record interval while running, ms: 4 points/s capture the faster signal. */
    val fastIntervalMs: Long = 250,
    /** Normal record interval, ms (Lab 5 default). */
    val normalIntervalMs: Long = 1_000,
    /** Fast recording continues this long after RUNNING ends, ms: a short pause does not flip it. */
    val runExitDelayMs: Long = 3_000,
    /** A window counts as SHAKE only with at least this confidence. */
    val shakeConfidence: Float = 0.8f,
    /** Consecutive SHAKE windows needed for the alarm: 2 windows = 1 s of shaking. */
    val shakeWindows: Int = 2,
    /** Minimal time between two anomaly alarms, ms; the torch goes off when it expires. */
    val shakeCooldownMs: Long = 10_000,
)

/**
 * Rules driven by the on-device classifier. Pure Kotlin with an explicit clock, like
 * [DecisionEngine]; not thread-safe.
 */
class AiRules(val config: AiRulesConfig = AiRulesConfig()) {
    private var lastRunningAt: Long? = null

    private var shakeStreak = 0
    private var lastAlarmAt: Long? = null
    private var torchOn = false

    /**
     * Record interval for [ISensorHistoryRepository.setRecordInterval][com.example.smartstepcounter.history.ISensorHistoryRepository.setRecordInterval].
     * @param smoothed majority-voted prediction.
     */
    fun recordInterval(smoothed: Prediction, nowMs: Long): Long {
        if (smoothed.label == AiLabel.RUNNING && smoothed.confidence >= config.runConfidence) {
            lastRunningAt = nowMs
            return config.fastIntervalMs
        }
        val last = lastRunningAt ?: return config.normalIntervalMs
        if (nowMs - last < config.runExitDelayMs) return config.fastIntervalMs
        lastRunningAt = null
        return config.normalIntervalMs
    }

    /**
     * R4: anomaly alarm. Uses raw per-window predictions: the majority vote would delay
     * the reaction by a whole window.
     */
    fun shakeRule(raw: Prediction, nowMs: Long): List<Decision> {
        val isShake = raw.label == AiLabel.SHAKE && raw.confidence >= config.shakeConfidence
        shakeStreak = if (isShake) shakeStreak + 1 else 0
        val last = lastAlarmAt
        val coolingDown = last != null && nowMs - last < config.shakeCooldownMs

        if (shakeStreak >= config.shakeWindows && !coolingDown) {
            lastAlarmAt = nowMs
            torchOn = true
            val reason = "Аномалія: SHAKE ${String.format(UK, "%.2f", raw.confidence)}"
            return listOf(
                Decision(
                    rule = Rule.R4_AI_ANOMALY,
                    actuator = ActuatorType.VIBRATION,
                    action = DecisionAction.ACTIVATE,
                    reason = reason,
                    message = "AI: струшування / можлива спроба злому — тривога!",
                    pattern = VibrationPattern.ALARM,
                    source = EventSource.AI,
                ),
                Decision(
                    rule = Rule.R4_AI_ANOMALY,
                    actuator = ActuatorType.TORCH,
                    action = DecisionAction.ACTIVATE,
                    reason = reason,
                    message = "AI: ліхтарик-сигнал увімкнено",
                    source = EventSource.AI,
                ),
            )
        }
        if (torchOn && !coolingDown && !isShake) {
            torchOn = false
            return listOf(
                Decision(
                    rule = Rule.R4_AI_ANOMALY,
                    actuator = ActuatorType.TORCH,
                    action = DecisionAction.DEACTIVATE,
                    reason = "Аномалія минула: ${raw.label}",
                    message = "AI: тривогу знято",
                    source = EventSource.AI,
                )
            )
        }
        return emptyList()
    }

    private companion object {
        /** Ukrainian decimal comma: "0,91". */
        val UK: Locale = Locale.forLanguageTag("uk-UA")
    }
}
