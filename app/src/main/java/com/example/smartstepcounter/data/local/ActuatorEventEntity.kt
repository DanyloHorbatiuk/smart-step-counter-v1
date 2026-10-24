package com.example.smartstepcounter.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.smartstepcounter.actuator.ActuatorEvent

/** History of actuator actions (Lab 4), table actuator_events. Enums are stored by name. */
@Entity(tableName = "actuator_events", indices = [Index("timestamp")])
data class ActuatorEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock time, ms since epoch. */
    val timestamp: Long,
    /** ActuatorType name. */
    val actuator: String,
    val isOn: Boolean,
    /** EventSource name. */
    val source: String,
    val reason: String,
) {
    companion object {
        fun from(event: ActuatorEvent) = ActuatorEventEntity(
            timestamp = event.timestamp,
            actuator = event.actuator.name,
            isOn = event.on,
            source = event.source.name,
            reason = event.reason,
        )
    }
}
