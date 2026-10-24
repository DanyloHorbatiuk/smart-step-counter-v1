package com.example.smartstepcounter.history

import com.example.smartstepcounter.actuator.ActuatorEvent
import com.example.smartstepcounter.data.local.ActuatorEventEntity
import com.example.smartstepcounter.data.local.SensorRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Local sensor and actuator history. Never throws on database errors: see [storageState]. */
interface ISensorHistoryRepository {
    /** Period of sensor recording, ms. Changed at runtime (Lab 6: faster while running). */
    val recordIntervalMs: StateFlow<Long>

    val storageState: StateFlow<StorageState>

    /** Not replayed to late subscribers. */
    val events: SharedFlow<StorageEvent>

    fun setRecordInterval(ms: Long)

    suspend fun insertRecord(record: SensorRecord)

    suspend fun insertActuatorEvent(event: ActuatorEvent)

    /** The newest [limit] records, oldest first. */
    fun observeLatest(limit: Int): Flow<List<SensorRecord>>

    fun observeCount(): Flow<Int>

    /** The newest [limit] actuator events, newest first. */
    fun observeActuatorEvents(limit: Int): Flow<List<ActuatorEventEntity>>

    /** Deletes sensor records and actuator events (not the cloud queue). */
    suspend fun clearHistory()

    /** Applies [RetentionPolicy]: age and row limits. */
    suspend fun applyRetention()
}
