package com.example.smartstepcounter.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One stored accelerometer reading, table sensor_records. */
@Entity(tableName = "sensor_records", indices = [Index("timestamp")])
data class SensorRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock time, ms since epoch. */
    val timestamp: Long,
    /** |a| including gravity, m/s². */
    val sensorValue: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    /** RMS of the dynamic acceleration, m/s². */
    val rms: Float,
    val steps: Int,
    /** MotionState name. */
    val state: String,
    /** True when the record was forced by an actuator action at this moment. */
    val actionTriggered: Boolean = false,
)
