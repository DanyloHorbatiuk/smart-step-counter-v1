package com.example.smartstepcounter.cloud

import com.example.smartstepcounter.sensor.StepSnapshot

/** One accelerometer reading, stored at devices/{deviceId}/readings/{pushId}. */
data class SensorData(
    /** Stable per-install id, see [DeviceIdProvider]. */
    val deviceId: String,
    val type: String = "ACCELEROMETER",
    /** Wall-clock time, ms since epoch. */
    val timestamp: Long,
    /** |a| including gravity, m/s². */
    val value: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val steps: Int,
    val cadence: Float,
    val state: String,
    val rms: Float,
) {
    companion object {
        fun from(snapshot: StepSnapshot, deviceId: String, timestamp: Long) = SensorData(
            deviceId = deviceId,
            timestamp = timestamp,
            value = snapshot.magnitude,
            x = snapshot.x,
            y = snapshot.y,
            z = snapshot.z,
            steps = snapshot.steps,
            cadence = snapshot.cadence,
            state = snapshot.state.name,
            rms = snapshot.rms,
        )
    }
}

/** Latest device state, overwritten at devices/{deviceId}/status. */
data class DeviceStatus(
    val online: Boolean,
    /** Time of the last successful upload, ms since epoch. */
    val lastSync: Long,
    val steps: Int,
)

/** Firebase answer to a POST: the generated push id, e.g. {"name": "-NxAbC..."}. */
data class PushResponse(val name: String)
