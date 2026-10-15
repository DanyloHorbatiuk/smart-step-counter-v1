package com.example.smartstepcounter.ble

import com.example.smartstepcounter.sensor.StepSnapshot
import java.util.Locale
import kotlin.math.roundToInt

/** Builds the short UTF-8 text packets sent to the hub. */
object BlePacket {
    /** "STEPS=28;CAD=108" – step count and cadence (steps/min, rounded). */
    fun steps(snapshot: StepSnapshot): String =
        "STEPS=${snapshot.steps};CAD=${snapshot.cadence.roundToInt()}"

    /** "STATE=WALKING;RMS=1.42" – motion state and RMS of dynamic acceleration, m/s². */
    fun motion(snapshot: StepSnapshot): String =
        "STATE=${snapshot.state.name};RMS=${String.format(Locale.US, "%.2f", snapshot.rms)}"

    fun sizeOf(payload: String): Int = payload.toByteArray(Charsets.UTF_8).size
}
