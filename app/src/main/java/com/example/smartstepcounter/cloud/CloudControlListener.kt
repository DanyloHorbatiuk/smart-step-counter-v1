package com.example.smartstepcounter.cloud

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

interface IControlListener {
    val state: StateFlow<ControlListenerState>
}

/** Used when FIREBASE_DB_URL is missing: no stream, the tab shows NOT_CONFIGURED. */
class NotConfiguredControlListener : IControlListener {
    override val state: StateFlow<ControlListenerState> =
        MutableStateFlow(ControlListenerState(status = ControlListenerStatus.NOT_CONFIGURED)).asStateFlow()
}

/**
 * Cloud-to-Device: keeps a Firebase REST streaming (SSE) connection to
 * devices/{deviceId}/control open and turns every change into [ControlCommand]s.
 *
 * Event-driven instead of polling: Firebase pushes a `put`/`patch` event the moment the
 * value changes, so the reaction time is network latency, not a polling period.
 *
 * Reconnects with [Backoff] after a drop, and immediately when [NetworkMonitor] reports
 * that the network is back.
 *
 * @param streamUrl full URL of devices/{deviceId}/control.json.
 * @param onCommand called sequentially, in the order of events.
 */
class CloudControlListener(
    private val streamUrl: HttpUrl,
    private val api: ControlApi,
    private val deviceId: String,
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope,
    private val onCommand: suspend (ControlCommand) -> Unit,
    client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) : IControlListener {

    private val sseFactory = EventSources.createFactory(client)

    private val _state = MutableStateFlow(ControlListenerState())
    override val state: StateFlow<ControlListenerState> = _state.asStateFlow()

    private var job: Job? = null
    private var nodeEnsured = false

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    private suspend fun loop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (!networkMonitor.online.value) {
                logger("offline, waiting for network")
                _state.update { it.copy(status = ControlListenerStatus.RECONNECTING, lastError = "немає мережі") }
                networkMonitor.online.first { it }
            }
            if (!nodeEnsured) nodeEnsured = ensureNode()

            _state.update { it.copy(status = ControlListenerStatus.CONNECTING) }
            val outcome = session(onOpen = { attempt = 0 })

            val delayMs = Backoff.delayMs(attempt++)
            val status = if (outcome.fatal) ControlListenerStatus.ERROR else ControlListenerStatus.RECONNECTING
            logger("stream ended: ${outcome.message}; retry in $delayMs ms")
            _state.update { it.copy(status = status, lastError = outcome.message) }

            // Wait for the backoff, but reconnect at once if the network comes back meanwhile.
            withTimeoutOrNull(delayMs) {
                networkMonitor.online.dropWhile { it }.first { it }
            }
        }
    }

    private class Outcome(val message: String, val fatal: Boolean)

    /** One SSE connection, from open to close. */
    private suspend fun session(onOpen: () -> Unit): Outcome {
        var result: Outcome? = null
        stream().takeWhile { result == null }.collect { signal ->
            when (signal) {
                Signal.Open -> {
                    onOpen()
                    logger("stream open: $streamUrl")
                    _state.update { it.copy(status = ControlListenerStatus.LISTENING, lastError = null) }
                }
                is Signal.Event -> result = onEvent(signal.type, signal.data)
                Signal.Closed -> result = Outcome("потік закрито сервером", fatal = false)
                is Signal.Failed -> result = signal.code?.let { code ->
                    // 401/403: database rules deny reading, 404: wrong URL — retrying won't fix it fast.
                    Outcome("HTTP $code", fatal = code in 400..499)
                } ?: Outcome(signal.message, fatal = false)
            }
        }
        return result ?: Outcome("потік завершився", fatal = false)
    }

    private suspend fun onEvent(type: String?, data: String): Outcome? {
        when (val update = ControlEventParser.parse(type, data)) {
            ControlUpdate.KeepAlive -> Unit
            is ControlUpdate.Ignored -> logger("ignored event: ${update.why}")
            is ControlUpdate.Failure -> {
                Log.w(TAG, "stream cancelled by Firebase: ${update.reason}")
                return Outcome(update.reason, fatal = true)
            }
            is ControlUpdate.Put, is ControlUpdate.Patch -> {
                logger("event $type: $data")
                val reduced = ControlReducer.reduce(_state.value.control, update)
                val now = clock()
                _state.update {
                    it.copy(
                        control = reduced.state,
                        lastCommandAt = if (reduced.commands.isEmpty()) it.lastCommandAt else now,
                    )
                }
                reduced.commands.forEach { onCommand(it) }
            }
        }
        return null
    }

    /** Creates the control node with false values so it shows up in the Firebase console. */
    private suspend fun ensureNode(): Boolean = try {
        val current = api.getControl(deviceId)
        when {
            !current.isSuccessful -> {
                logger("control GET failed: HTTP ${current.code()}")
                false
            }
            current.body() != null -> true
            else -> {
                val created = api.putControl(deviceId, ControlState())
                logger("control node created: ${created.isSuccessful}")
                created.isSuccessful
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger("control node check failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private sealed interface Signal {
        data object Open : Signal

        data class Event(val type: String?, val data: String) : Signal

        data object Closed : Signal

        data class Failed(val message: String, val code: Int?) : Signal
    }

    private fun stream(): Flow<Signal> = callbackFlow {
        val request = Request.Builder()
            .url(streamUrl)
            .header("Accept", "text/event-stream")
            .build()
        val source = sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(Signal.Open)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend(Signal.Event(type, data))
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(Signal.Closed)
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val message = t?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "HTTP ${response?.code}"
                trySend(Signal.Failed(message, response?.code?.takeIf { it >= 400 }))
                channel.close()
            }
        })
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    companion object {
        const val TAG = "CloudControl"

        /**
         * A separate client: readTimeout = 0 because the stream stays silent between
         * keep-alives, and no BODY logger — it would buffer the endless body.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
