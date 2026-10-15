package com.example.smartstepcounter.cloud

import com.example.smartstepcounter.sensor.StepSnapshot
import kotlin.math.abs

/**
 * Decides whether a snapshot differs enough from the last *sent* one to be worth uploading.
 * Comparing with the last sent value (not the previous sample) means slow drift still
 * gets sent once it accumulates to [axisDelta].
 */
class SyncChangeFilter(private val axisDelta: Float = AXIS_DELTA) {
    private var lastSent: StepSnapshot? = null

    fun shouldSend(now: StepSnapshot): Boolean = hasSignificantChange(lastSent, now, axisDelta)

    fun markSent(snapshot: StepSnapshot) {
        lastSent = snapshot
    }

    companion object {
        /**
         * Minimal change of any axis that counts as new data, m/s².
         *
         * At rest the accelerometer noise is ~0.05–0.3 m/s², so 0.5 m/s² filters it out,
         * while walking moves the axes by 1.5–4 m/s² and a tilt of ~3° already gives 0.5 m/s².
         * A phone lying on a table therefore sends nothing; a moving one is sent every tick.
         */
        const val AXIS_DELTA = 0.5f

        fun hasSignificantChange(last: StepSnapshot?, now: StepSnapshot, axisDelta: Float = AXIS_DELTA): Boolean {
            if (last == null) return true
            return now.steps != last.steps ||
                now.state != last.state ||
                abs(now.x - last.x) >= axisDelta ||
                abs(now.y - last.y) >= axisDelta ||
                abs(now.z - last.z) >= axisDelta
        }
    }
}
