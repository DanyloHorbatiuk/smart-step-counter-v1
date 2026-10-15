package com.example.smartstepcounter.cloud

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface ICloudRepository {
    val state: StateFlow<CloudSyncState>

    /** Network transitions for snackbars; not replayed to late subscribers. */
    val events: SharedFlow<CloudEvent>

    /** Sends [data] now if online, otherwise keeps it in the pending queue. Never throws. */
    suspend fun upload(data: SensorData)
}
