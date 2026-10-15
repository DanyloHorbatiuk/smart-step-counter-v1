package com.example.smartstepcounter.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.util.UUID

/**
 * Software emulation of the StepHub-01 GATT link.
 *
 * All timing is done with [delay], so nothing blocks the main thread and
 * unit tests can run on virtual time.
 *
 * @param scope long-lived scope for background work (auto-reconnect).
 * @param clock wall-clock source for log timestamps.
 * @param logger mirror of every log line (Logcat by default).
 */
class MockBleConnector(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) : IBleConnector, BleHubSimulator {

    private val _state = MutableStateFlow(BleConnectionState.DISCONNECTED)
    override val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<BleLogEntry>>(emptyList())
    override val log: StateFlow<List<BleLogEntry>> = _log.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    /** Serialises writes: one GATT operation at a time, like a real stack. */
    private val writeLock = Mutex()
    private var device: BleDevice? = null
    private var reconnectJob: Job? = null

    override suspend fun connect(device: BleDevice): Boolean {
        if (_state.value.isLinked) return this.device == device
        reconnectJob?.cancel() // a manual connect wins over auto-reconnect
        if (device.name != HUB_NAME) {
            sys("Connection refused: ${device.name} (${device.address}) has no StepHub service")
            return false
        }
        sys("Connecting to ${device.name} (${device.address}), RSSI ${device.rssi} dBm…")
        _state.value = BleConnectionState.CONNECTING
        delay(CONNECT_DELAY_MS)
        if (_state.value != BleConnectionState.CONNECTING) return false // cancelled meanwhile
        this.device = device
        onLinkUp()
        return true
    }

    override fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        if (_state.value != BleConnectionState.DISCONNECTED) {
            _state.value = BleConnectionState.DISCONNECTED
            sys("Disconnected by user")
        }
        device = null
    }

    override suspend fun sendData(data: String, characteristic: UUID): Boolean = writeLock.withLock {
        if (_state.value != BleConnectionState.CONNECTED) {
            sys("Write failed: not connected (state ${_state.value}), dropped \"$data\"")
            return false
        }
        val size = BlePacket.sizeOf(data)
        _state.value = BleConnectionState.DATA_TRANSFER
        append(BleDirection.TX, characteristic, data, size, "TX → $characteristic $data ($size B)")
        delay(TRANSFER_DELAY_MS)
        if (_state.value != BleConnectionState.DATA_TRANSFER) {
            sys("Write to ${BleUuids.shortName(characteristic)} aborted: link lost")
            return false
        }
        val reply = if (characteristic == BleUuids.COMMAND && data == HubCommands.PING) {
            HubCommands.PONG
        } else {
            HubCommands.ACK
        }
        append(BleDirection.RX, characteristic, reply, BlePacket.sizeOf(reply), "RX ← $reply")
        _state.value = BleConnectionState.CONNECTED
        true
    }

    override fun simulateIncomingCommand(command: String) {
        if (!_state.value.isLinked) {
            sys("Notify \"$command\" ignored: not connected")
            return
        }
        val size = BlePacket.sizeOf(command)
        append(BleDirection.RX, BleUuids.COMMAND, command, size, "RX ← ${BleUuids.COMMAND} $command ($size B)")
        _incoming.tryEmit(command)
    }

    override fun simulateConnectionLoss(recoverOnAttempt: Int) {
        if (!_state.value.isLinked) {
            sys("Cannot simulate link loss: not connected")
            return
        }
        reconnectJob?.cancel()
        _state.value = BleConnectionState.DISCONNECTED
        sys("Link lost (supervision timeout)")
        reconnectJob = scope.launch {
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                val backoff = RECONNECT_BASE_DELAY_MS shl (attempt - 1) // 1 s, 2 s, 4 s
                delay(backoff)
                sys("Reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS (after ${backoff} ms)")
                _state.value = BleConnectionState.CONNECTING
                delay(CONNECT_DELAY_MS)
                if (attempt == recoverOnAttempt) {
                    onLinkUp()
                    return@launch
                }
                _state.value = BleConnectionState.DISCONNECTED
                sys("Reconnect attempt $attempt failed: device not responding")
            }
            sys("Giving up after $MAX_RECONNECT_ATTEMPTS attempts")
            device = null
        }
    }

    /** Called by [MockBleScanner]: SCANNING is shown only while there is no link. */
    internal fun onScanStarted() {
        if (_state.value == BleConnectionState.DISCONNECTED) _state.value = BleConnectionState.SCANNING
        sys("Scan started")
    }

    internal fun onScanFinished(found: List<BleDevice>) {
        if (_state.value == BleConnectionState.SCANNING) _state.value = BleConnectionState.DISCONNECTED
        sys("Scan finished: ${found.size} device(s) – ${found.joinToString { it.name }}")
    }

    private fun onLinkUp() {
        _state.value = BleConnectionState.CONNECTED
        sys(
            "Connected to ${device?.name}. Service ${BleUuids.SERVICE}: " +
                "STEPS, MOTION, COMMAND discovered; CCCD ${BleUuids.CCCD} enabled (Notify)"
        )
    }

    private fun sys(message: String) = append(BleDirection.SYS, null, message, 0, "SYS $message")

    private fun append(
        direction: BleDirection,
        characteristic: UUID?,
        message: String,
        size: Int,
        logcatLine: String,
    ) {
        val entry = BleLogEntry(clock(), direction, characteristic, message, size)
        _log.update { (it + entry).takeLast(MAX_LOG_ENTRIES) }
        logger(logcatLine)
    }

    companion object {
        const val TAG = "BleMock"
        const val CONNECT_DELAY_MS = 1_000L
        const val TRANSFER_DELAY_MS = 150L
        const val RECONNECT_BASE_DELAY_MS = 1_000L
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val MAX_LOG_ENTRIES = 100
    }
}
