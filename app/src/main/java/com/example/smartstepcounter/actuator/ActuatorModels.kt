package com.example.smartstepcounter.actuator

/** Executive devices the app can drive. */
enum class ActuatorType { TORCH, VIBRATION, LAMP }

/** Who requested an actuator action. */
enum class EventSource {
    /** A [com.example.smartstepcounter.decision.DecisionEngine] rule. */
    AUTO,

    /** The user on the "Керування" tab. */
    MANUAL,

    /** A command from Firebase (devices/{id}/control). */
    CLOUD,

    /** The on-device ML classifier (Lab 6), see [com.example.smartstepcounter.decision.AiRules]. */
    AI,
}

/**
 * Vibration waveforms: alternating off/on durations in ms, starting with a delay
 * (the format of VibrationEffect.createWaveform).
 */
enum class VibrationPattern(val timings: LongArray) {
    /** One short buzz: manual test and cloud command. */
    SINGLE(longArrayOf(0, 300)),

    /** Two short pulses: a pleasant "goal reached" signal. */
    DOUBLE_PULSE(longArrayOf(0, 150, 120, 150)),

    /** Three long pulses: hard to miss after an impact. */
    ALARM(longArrayOf(0, 400, 150, 400, 150, 400)),
}

/**
 * One actuator action. A standalone model so Lab 5 can store it in a database as is.
 *
 * @param timestamp wall-clock time, ms since epoch.
 * @param on true = switched on / vibrated, false = switched off.
 */
data class ActuatorEvent(
    val timestamp: Long,
    val actuator: ActuatorType,
    val on: Boolean,
    val source: EventSource,
    val reason: String,
)

data class ActuatorState(
    val torchOn: Boolean = false,
    /** True when there is no hardware flash: the torch exists only in the UI. */
    val torchIsVirtual: Boolean = true,
    val vibratorAvailable: Boolean = false,
    /** Wall-clock time of the last vibration, ms since epoch. */
    val lastVibrationAt: Long? = null,
    /** Virtual lamp driven by devices/{id}/control/led_status. */
    val virtualLampOn: Boolean = false,
)
