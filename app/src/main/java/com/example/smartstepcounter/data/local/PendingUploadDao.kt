package com.example.smartstepcounter.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingUploadDao {
    @Insert
    suspend fun insert(upload: PendingUpload): Long

    /** The head of the queue (smallest id), or null when it is empty. */
    @Query("SELECT * FROM pending_uploads ORDER BY id ASC LIMIT 1")
    suspend fun peekOldest(): PendingUpload?

    @Query("DELETE FROM pending_uploads WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM pending_uploads")
    fun count(): Flow<Int>

    /** One-shot variant of [count] for the upload loop. */
    @Query("SELECT COUNT(*) FROM pending_uploads")
    suspend fun size(): Int

    /** Drops the oldest rows so that at most [max] remain. @return the number of deleted rows. */
    @Query(
        """
        DELETE FROM pending_uploads WHERE id NOT IN (
            SELECT id FROM pending_uploads ORDER BY id DESC LIMIT :max
        )
        """
    )
    suspend fun trimToMax(max: Int): Int
}
