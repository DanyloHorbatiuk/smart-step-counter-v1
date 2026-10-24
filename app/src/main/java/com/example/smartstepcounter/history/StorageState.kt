package com.example.smartstepcounter.history

enum class StorageStatus {
    /** Writes succeed. */
    OK,

    /** SQLiteFullException: old records were trimmed aggressively. */
    FULL,

    /** Any other SQLiteException; the app keeps running without history. */
    ERROR,
}

data class StorageState(
    val status: StorageStatus = StorageStatus.OK,
    val lastError: String? = null,
)

/** One-off notifications for the UI (snackbars). */
sealed interface StorageEvent {
    /** The disk was full; [deleted] old sensor records were removed to free space. */
    data class SpaceFreed(val deleted: Int) : StorageEvent
}
