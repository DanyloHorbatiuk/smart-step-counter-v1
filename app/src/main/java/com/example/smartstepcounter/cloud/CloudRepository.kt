package com.example.smartstepcounter.cloud

import android.database.sqlite.SQLiteException
import android.util.Log
import com.example.smartstepcounter.data.local.PendingUpload
import com.example.smartstepcounter.data.local.PendingUploadDao
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Uploads readings to Firebase with a persistent offline queue (Room table pending_uploads).
 *
 * Every record goes through the queue, so records always leave in the order they were
 * produced (by row id): a new one never overtakes older pending ones. A row is deleted
 * only after Firebase confirmed it, so a failed request leaves it at the head, and rows
 * collected offline survive an app restart and are sent on the next start.
 *
 * @param queue DAO of the pending_uploads table.
 * @param scope app-wide scope; the network observer lives as long as the process.
 * @param io dispatcher for HTTP and database calls; injected so tests can use a test dispatcher.
 */
class CloudRepository(
    private val api: FirebaseApi,
    private val networkMonitor: NetworkMonitor,
    private val queue: PendingUploadDao,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) : ICloudRepository {

    private val gson = Gson()

    /** Serialises flushes so two of them never send the same head record. */
    private val flushMutex = Mutex()

    private val _state = MutableStateFlow(CloudSyncState())
    override val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CloudEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<CloudEvent> = _events.asSharedFlow()

    init {
        // Remember the state at creation so the initial value does not produce a snackbar.
        var wasOnline = networkMonitor.online.value
        if (!wasOnline) _state.update { it.copy(status = CloudStatus.PENDING) }
        scope.launch {
            // Rows left by a previous run (the app was closed while offline).
            val restored = pendingSize()
            if (restored == 0) return@launch
            logger("restored $restored pending from the database")
            _state.update { it.copy(pendingCount = restored) }
            if (networkMonitor.online.value) flush()
        }
        scope.launch {
            networkMonitor.online.collect { online ->
                if (online == wasOnline) return@collect
                wasOnline = online
                if (online) {
                    logger("network restored, flushing ${pendingSize()} pending")
                    val sent = flush()
                    _events.emit(CloudEvent.Restored(sent))
                } else {
                    logger("network lost, queue size=${pendingSize()}")
                    _state.update { it.copy(status = CloudStatus.PENDING) }
                    _events.emit(CloudEvent.WentOffline)
                }
            }
        }
    }

    override suspend fun upload(data: SensorData) {
        enqueue(data)
        if (networkMonitor.online.value) {
            flush()
        } else {
            val pending = pendingSize()
            logger("offline, queued ts=${data.timestamp}, queue size=$pending")
            _state.update { it.copy(status = CloudStatus.PENDING, pendingCount = pending) }
        }
    }

    /** Sends the queue head-first until it is empty, the network drops or a request fails. */
    private suspend fun flush(): Int = flushMutex.withLock {
        var sent = 0
        var lastSent: SensorData? = null
        var failed = false
        while (networkMonitor.online.value) {
            val head = db(null) { peekOldest() } ?: break
            val next = decode(head)
            if (next == null) {
                db(0) { deleteById(head.id) } // unreadable row: drop it, otherwise it blocks the queue
                continue
            }
            _state.update { it.copy(status = CloudStatus.SYNCING) }
            val error = post(next)
            if (error != null) {
                val pending = pendingSize()
                logger("upload failed: $error, queue size=$pending")
                _state.update {
                    it.copy(status = CloudStatus.ERROR, lastError = error, pendingCount = pending)
                }
                failed = true
                break
            }
            // The row may have been evicted by overflow while the request was in flight: then a no-op.
            db(0) { deleteById(head.id) }
            sent++
            lastSent = next
            val now = clock()
            val pending = pendingSize()
            _state.update {
                it.copy(
                    lastSuccessAt = now,
                    lastError = null,
                    sentCount = it.sentCount + 1,
                    pendingCount = pending,
                )
            }
        }
        if (!failed) {
            val pending = pendingSize()
            val status = when {
                pending > 0 -> CloudStatus.PENDING // the network dropped mid-flush
                sent > 0 -> CloudStatus.SUCCESS
                else -> _state.value.status.takeUnless { it == CloudStatus.SYNCING } ?: CloudStatus.IDLE
            }
            _state.update { it.copy(status = status, pendingCount = pending) }
        }
        lastSent?.let { putStatus(it) }
        sent
    }

    /** @return null on success, otherwise a short error description. */
    private suspend fun post(data: SensorData): String? = withContext(io) {
        try {
            val response = api.postReading(data.deviceId, data)
            if (response.isSuccessful) {
                logger("sent ts=${data.timestamp} steps=${data.steps} state=${data.state} -> ${response.body()?.name}")
                null
            } else {
                "HTTP ${response.code()}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException, SocketTimeoutException, malformed JSON…
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Best effort: a failed status update does not affect the queue or the status. */
    private suspend fun putStatus(last: SensorData) {
        withContext(io) {
            try {
                val status = DeviceStatus(online = true, lastSync = clock(), steps = last.steps)
                val response = api.putStatus(last.deviceId, status)
                logger("status ${if (response.isSuccessful) "updated" else "failed: HTTP ${response.code()}"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger("status failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun enqueue(data: SensorData) {
        db(Unit) {
            insert(PendingUpload(createdAt = clock(), payloadJson = gson.toJson(data)))
            val dropped = trimToMax(MAX_PENDING)
            if (dropped > 0) logger("queue full, dropped $dropped oldest")
        }
    }

    private suspend fun pendingSize(): Int = db(0) { size() }

    private fun decode(row: PendingUpload): SensorData? = try {
        gson.fromJson(row.payloadJson, SensorData::class.java)
    } catch (e: JsonParseException) {
        logger("dropping unreadable row ${row.id}: ${e.message}")
        null
    }

    /** Runs a queue query on [io]; a database error is logged and [fallback] returned, upload() never throws. */
    private suspend fun <T> db(fallback: T, block: suspend PendingUploadDao.() -> T): T = withContext(io) {
        try {
            queue.block()
        } catch (e: SQLiteException) {
            logger("queue database error: ${e.message}")
            _state.update { it.copy(status = CloudStatus.ERROR, lastError = "SQLiteException: ${e.message}") }
            fallback
        }
    }

    companion object {
        const val TAG = "CloudSync"

        /** Queue cap: at one record per 5 s this is ~17 minutes offline. */
        const val MAX_PENDING = 200
    }
}

/** Used when FIREBASE_DB_URL is missing: the app keeps working, nothing is sent. */
class NotConfiguredCloudRepository : ICloudRepository {
    override val state: StateFlow<CloudSyncState> =
        MutableStateFlow(CloudSyncState(status = CloudStatus.NOT_CONFIGURED)).asStateFlow()
    override val events: SharedFlow<CloudEvent> = MutableSharedFlow<CloudEvent>().asSharedFlow()

    override suspend fun upload(data: SensorData) = Unit
}
