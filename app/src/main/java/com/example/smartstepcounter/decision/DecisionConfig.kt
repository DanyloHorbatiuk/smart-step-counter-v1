package com.example.smartstepcounter.decision

/** All thresholds of the local automation in one place. */
data class DecisionConfig(
    /**
     * R1: step goal. 50 instead of a realistic 10 000 so the rule is easy to demonstrate
     * in the emulator (a few seconds of shaking) or on a short walk.
     */
    val stepGoal: Int = 50,

    /**
     * R2: how long RUNNING must last before the signal torch turns on, ms.
     * The detector classifies by a 2 s RMS window, so a single jump can flash RUNNING for
     * a moment; 3 s of uninterrupted RUNNING means the user is really running.
     */
    val runOnDelayMs: Long = 3_000,

    /**
     * R2: how long the state must stay *not* RUNNING before the torch turns off, ms.
     * Longer than [runOnDelayMs] (time hysteresis): a short slowdown at a crossing
     * must not make the torch blink.
     */
    val runOffDelayMs: Long = 5_000,

    /**
     * R3: |a| (including gravity) that counts as a sharp impact or fall, m/s².
     * ≈ 2.5 g: walking gives |a| ≈ 10–14, running up to ~20 m/s², a drop on the floor
     * or a hard hit well above 25.
     */
    val impactThreshold: Float = 25f,

    /**
     * R3: minimal time between two impact alarms, ms. One fall produces several peaks
     * (bounces); 10 s gives one alarm per event instead of a burst.
     */
    val impactCooldownMs: Long = 10_000,
)
