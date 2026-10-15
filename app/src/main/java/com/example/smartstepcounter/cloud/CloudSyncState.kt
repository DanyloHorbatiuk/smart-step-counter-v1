package com.example.smartstepcounter.cloud

enum class CloudStatus {
    /** FIREBASE_DB_URL is missing or invalid: nothing is sent. */
    NOT_CONFIGURED,

    /** Configured, nothing sent yet. */
    IDLE,

    /** A request is in flight. */
    SYNCING,

    /** The last request succeeded and the queue is empty. */
    SUCCESS,

    /** The last request failed; the record stays in the queue. */
    ERROR,

    /** No network; records wait in the queue. */
    PENDING,
}

data class CloudSyncState(
    val status: CloudStatus = CloudStatus.IDLE,
    /** Time of the last successful upload, ms since epoch. */
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    val sentCount: Int = 0,
    val pendingCount: Int = 0,
)

/** One-off notifications for the UI (snackbars). */
sealed interface CloudEvent {
    data object WentOffline : CloudEvent

    /** The network is back and the queue was flushed; [sent] records went out. */
    data class Restored(val sent: Int) : CloudEvent
}
