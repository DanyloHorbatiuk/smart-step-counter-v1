package com.example.smartstepcounter.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorRecordDao {
    @Insert
    suspend fun insert(record: SensorRecord): Long

    /** The newest [limit] records in chronological order (oldest first), ready for a chart. */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM sensor_records ORDER BY timestamp DESC, id DESC LIMIT :limit
        ) ORDER BY timestamp ASC, id ASC
        """
    )
    fun observeLatest(limit: Int): Flow<List<SensorRecord>>

    @Query("SELECT COUNT(*) FROM sensor_records")
    fun count(): Flow<Int>

    /** @return the number of deleted rows. */
    @Query("DELETE FROM sensor_records WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int

    /** Keeps the newest [max] records. @return the number of deleted rows. */
    @Query(
        """
        DELETE FROM sensor_records WHERE id NOT IN (
            SELECT id FROM sensor_records ORDER BY timestamp DESC, id DESC LIMIT :max
        )
        """
    )
    suspend fun trimToMaxRows(max: Int): Int

    @Query("DELETE FROM sensor_records")
    suspend fun clear()
}
