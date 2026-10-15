package com.example.smartstepcounter.cloud

import android.util.Log
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
 * Uploads readings to Firebase with an in-memory offline queue.
 *
 * Every record goes through the queue, so records always leave in the order they were
 * produced: a new one never overtakes older pending ones. A record is removed from the
 * queue only after Firebase confirmed it, so a failed request leaves it at the head.
 *
 * @param scope app-wide scope; the network observer lives as long as the process.
 * @param io dispatcher for HTTP calls; injected so tests can use a test dispatcher.
 */
class CloudRepository(
    private val api: FirebaseApi,
    private val networkMonitor: NetworkMonitor,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) : ICloudRepository {

    private val queue = ArrayDeque<SensorData>()

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
            logger("offline, queued ts=${data.timestamp}, queue size=${pendingSize()}")
            _state.update { it.copy(status = CloudStatus.PENDING, pendingCount = pendingSize()) }
        }
    }

    /** Sends the queue head-first until it is empty, the network drops or a request fails. */
    private suspend fun flush(): Int = flushMutex.withLock {
        var sent = 0
        var lastSent: SensorData? = null
        var failed = false
        while (networkMonitor.online.value) {
            val next = synchronized(queue) { queue.firstOrNull() } ?: break
            _state.update { it.copy(status = CloudStatus.SYNCING) }
            val error = post(next)
            if (error != null) {
                logger("upload failed: $error, queue size=${pendingSize()}")
                _state.update {
                    it.copy(status = CloudStatus.ERROR, lastError = error, pendingCount = pendingSize())
                }
                failed = true
                break
            }
            synchronized(queue) {
                // The head may have been evicted by overflow while the request was in flight.
                if (queue.firstOrNull() === next) queue.removeFirst()
            }
            sent++
            lastSent = next
            val now = clock()
            _state.update {
                it.copy(
                    lastSuccessAt = now,
                    lastError = null,
                    sentCount = it.sentCount + 1,
                    pendingCount = pendingSize(),
                )
            }
        }
        if (!failed) {
            val status = when {
                pendingSize() > 0 -> CloudStatus.PENDING // the network dropped mid-flush
                sent > 0 -> CloudStatus.SUCCESS
                else -> _state.value.status.takeUnless { it == CloudStatus.SYNCING } ?: CloudStatus.IDLE
            }
            _state.update { it.copy(status = status, pendingCount = pendingSize()) }
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

    private fun enqueue(data: SensorData) = synchronized(queue) {
        queue.addLast(data)
        while (queue.size > MAX_PENDING) {
            val dropped = queue.removeFirst()
            logger("queue full, dropped oldest ts=${dropped.timestamp}")
        }
    }

    private fun pendingSize() = synchronized(queue) { queue.size }

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
