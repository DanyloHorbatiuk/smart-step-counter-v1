package com.example.smartstepcounter.history

/** How much sensor history is kept. */
object RetentionPolicy {
    /** ~5.5 hours at one record per second; a few MB on disk. */
    const val MAX_ROWS = 20_000

    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    const val CLEANUP_INTERVAL_MS = 60_000L

    /** Rows kept after SQLiteFullException: frees ~90% of the table at once. */
    const val AGGRESSIVE_KEEP_ROWS = 2_000

    /** Records with a timestamp below this are deleted. */
    fun cutoff(now: Long): Long = now - MAX_AGE_MS
}
