package com.example.smartstepcounter.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One reading waiting for upload, table pending_uploads: the persistent cloud queue.
 * The autoincrement [id] gives the FIFO order.
 */
@Entity(tableName = "pending_uploads")
data class PendingUpload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock time of enqueueing, ms since epoch. */
    val createdAt: Long,
    /** SensorData serialised with Gson. */
    val payloadJson: String,
)
