package com.example.smartstepcounter.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActuatorEventDao {
    @Insert
    suspend fun insert(event: ActuatorEventEntity): Long

    /** The newest [limit] events, newest first (a log). */
    @Query("SELECT * FROM actuator_events ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<ActuatorEventEntity>>

    @Query("DELETE FROM actuator_events WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int

    @Query("DELETE FROM actuator_events")
    suspend fun clear()
}
