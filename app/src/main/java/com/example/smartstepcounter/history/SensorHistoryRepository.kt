package com.example.smartstepcounter.history

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.util.Log
import com.example.smartstepcounter.actuator.ActuatorEvent
import com.example.smartstepcounter.data.local.ActuatorEventDao
import com.example.smartstepcounter.data.local.ActuatorEventEntity
import com.example.smartstepcounter.data.local.SensorRecord
import com.example.smartstepcounter.data.local.SensorRecordDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Room-backed history. App-wide singleton (AppContainer): the actuator log and the
 * retention job outlive screens. Every database call runs on [io].
 *
 * @param io dispatcher for database calls; injected so tests can use a test dispatcher.
 */
class SensorHistoryRepository(
    private val sensorDao: SensorRecordDao,
    private val actuatorDao: ActuatorEventDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) : ISensorHistoryRepository {

    private val _recordIntervalMs = MutableStateFlow(DEFAULT_RECORD_INTERVAL_MS)
    override val recordIntervalMs: StateFlow<Long> = _recordIntervalMs.asStateFlow()

    private val _storageState = MutableStateFlow(StorageState())
    override val storageState: StateFlow<StorageState> = _storageState.asStateFlow()

    private val _events = MutableSharedFlow<StorageEvent>(extraBufferCapacity = 4)
    override val events: SharedFlow<StorageEvent> = _events.asSharedFlow()

    /**
     * Starts the background jobs: logging every actuator event and the periodic cleanup.
     * Subscribe before any event is emitted, otherwise the replay cache is stored twice.
     */
    fun start(scope: CoroutineScope, actuatorEvents: Flow<ActuatorEvent>) {
        scope.launch { actuatorEvents.collect { insertActuatorEvent(it) } }
        scope.launch {
            while (true) {
                applyRetention()
                delay(RetentionPolicy.CLEANUP_INTERVAL_MS)
            }
        }
    }

    override fun setRecordInterval(ms: Long) {
        val value = ms.coerceAtLeast(MIN_RECORD_INTERVAL_MS)
        if (_recordIntervalMs.value != value) logger("record interval -> $value ms")
        _recordIntervalMs.value = value
    }

    override suspend fun insertRecord(record: SensorRecord) {
        safeWrite("insert record") { sensorDao.insert(record) }
    }

    override suspend fun insertActuatorEvent(event: ActuatorEvent) {
        safeWrite("insert actuator event") { actuatorDao.insert(ActuatorEventEntity.from(event)) }
    }

    override fun observeLatest(limit: Int): Flow<List<SensorRecord>> =
        sensorDao.observeLatest(limit).guard(emptyList())

    override fun observeCount(): Flow<Int> = sensorDao.count().guard(0)

    override fun observeActuatorEvents(limit: Int): Flow<List<ActuatorEventEntity>> =
        actuatorDao.observeLatest(limit).guard(emptyList())

    override suspend fun clearHistory() {
        safeWrite("clear") {
            sensorDao.clear()
            actuatorDao.clear()
        }
        logger("history cleared")
    }

    override suspend fun applyRetention() {
        val ok = safeWrite("retention") {
            val cutoff = RetentionPolicy.cutoff(clock())
            val old = sensorDao.deleteOlderThan(cutoff)
            val extra = sensorDao.trimToMaxRows(RetentionPolicy.MAX_ROWS)
            actuatorDao.deleteOlderThan(cutoff)
            if (old + extra > 0) logger("retention: deleted $old old + $extra over limit")
        }
        // A clean pass means the database is healthy again after FULL/ERROR.
        if (ok && _storageState.value.status != StorageStatus.OK) _storageState.value = StorageState()
    }

    /** @return true when [block] succeeded. */
    private suspend fun safeWrite(what: String, block: suspend () -> Unit): Boolean = withContext(io) {
        try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteFullException) {
            onDiskFull(what, e)
            false
        } catch (e: SQLiteException) {
            logger("$what failed: ${e.javaClass.simpleName}: ${e.message}")
            _storageState.value = StorageState(StorageStatus.ERROR, "${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Runs on [io]. Deleting usually still works on a full disk: SQLite frees pages. */
    private suspend fun onDiskFull(what: String, e: SQLiteFullException) {
        logger("$what failed: disk full (${e.message}), trimming to ${RetentionPolicy.AGGRESSIVE_KEEP_ROWS}")
        _storageState.value = StorageState(StorageStatus.FULL, "SQLiteFullException: ${e.message}")
        val deleted = try {
            sensorDao.trimToMaxRows(RetentionPolicy.AGGRESSIVE_KEEP_ROWS)
        } catch (trimError: SQLiteException) {
            logger("aggressive trim failed: ${trimError.message}")
            0
        }
        _events.emit(StorageEvent.SpaceFreed(deleted))
    }

    /** A failing query must not crash the collector (the UI): report and emit [fallback]. */
    private fun <T> Flow<T>.guard(fallback: T): Flow<T> = flowOn(io).catch { e ->
        if (e !is SQLiteException) throw e
        logger("query failed: ${e.message}")
        _storageState.value = StorageState(StorageStatus.ERROR, "${e.javaClass.simpleName}: ${e.message}")
        emit(fallback)
    }

    companion object {
        const val TAG = "SensorHistory"

        const val DEFAULT_RECORD_INTERVAL_MS = 1_000L

        /** Lower bound for [setRecordInterval]: 10 records/s is plenty for a phone. */
        const val MIN_RECORD_INTERVAL_MS = 100L
    }
}
