package com.example.smartstepcounter.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smartstepcounter.AppContainer
import com.example.smartstepcounter.StepCounterUiState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class BleUiState(
    val scanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val permissionDenied: Boolean = false,
    val connection: BleConnectionState = BleConnectionState.DISCONNECTED,
    val log: List<BleLogEntry> = emptyList(),
)

/**
 * Glue between the step counter (lab 1) and the BLE layer.
 *
 * @param steps state of [com.example.smartstepcounter.StepCounterViewModel]; read-only.
 * @param onHubReset the same reset the "Скинути" button calls, triggered by a hub RESET.
 */
class BleViewModel(
    private val scanner: MockBleScanner,
    private val connector: IBleConnector,
    private val simulator: BleHubSimulator,
    private val steps: StateFlow<StepCounterUiState>,
    private val onHubReset: () -> Unit,
) : ViewModel() {

    private val local = MutableStateFlow(BleUiState())

    val uiState: StateFlow<BleUiState> =
        combine(local, connector.state, connector.log) { ui, connection, log ->
            ui.copy(connection = connection, log = log)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BleUiState())

    init {
        viewModelScope.launch { streamWhileConnected() }
        viewModelScope.launch {
            connector.incoming.collect { command -> handleHubCommand(command) }
        }
    }

    fun onPermissionsResult(granted: Boolean) {
        local.update { it.copy(permissionDenied = !granted) }
        if (granted) scan()
    }

    fun scan() {
        if (local.value.scanning) return
        viewModelScope.launch {
            local.update { it.copy(scanning = true, permissionDenied = false) }
            val found = scanner.scan()
            local.update { it.copy(scanning = false, devices = found) }
        }
    }

    fun connect(device: BleDevice) {
        viewModelScope.launch { connector.connect(device) }
    }

    fun disconnect() = connector.disconnect()

    fun sendStepsNow() {
        val snapshot = steps.value.snapshot
        viewModelScope.launch {
            connector.sendData(BlePacket.steps(snapshot), BleUuids.STEPS)
            connector.sendData(BlePacket.motion(snapshot), BleUuids.MOTION)
        }
    }

    fun hubReset() = simulator.simulateIncomingCommand(HubCommands.RESET)

    fun hubPing() = simulator.simulateIncomingCommand(HubCommands.PING)

    fun simulateLoss() = simulator.simulateConnectionLoss()

    private fun handleHubCommand(command: String) {
        when (command) {
            HubCommands.RESET -> onHubReset()
            HubCommands.PING -> viewModelScope.launch {
                connector.sendData(HubCommands.PONG, BleUuids.COMMAND)
            }
        }
    }

    /** STEPS on every step-count change, MOTION on every state change, each ≤ 1 packet/s. */
    private suspend fun streamWhileConnected() {
        connector.state
            .map { it.isLinked }
            .distinctUntilChanged()
            .collectLatest { linked ->
                if (!linked) return@collectLatest
                coroutineScope {
                    launch {
                        steps.map { it.snapshot }
                            .distinctUntilChanged { a, b -> a.steps == b.steps }
                            .map(BlePacket::steps)
                            .throttledSend(BleUuids.STEPS)
                    }
                    launch {
                        steps.map { it.snapshot }
                            .distinctUntilChanged { a, b -> a.state == b.state }
                            .map(BlePacket::motion)
                            .throttledSend(BleUuids.MOTION)
                    }
                }
            }
    }

    /**
     * Throttle-latest: the first packet goes out immediately, then at most one per
     * [SEND_INTERVAL_MS]; intermediate values are dropped by [conflate], the last one is kept.
     */
    private suspend fun Flow<String>.throttledSend(characteristic: UUID) {
        conflate().collect { payload ->
            connector.sendData(payload, characteristic)
            delay(SEND_INTERVAL_MS)
        }
    }

    companion object {
        const val SEND_INTERVAL_MS = 1_000L

        fun factory(
            container: AppContainer,
            steps: StateFlow<StepCounterUiState>,
            onHubReset: () -> Unit,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BleViewModel(
                    scanner = container.scanner,
                    connector = container.connector,
                    simulator = container.connector,
                    steps = steps,
                    onHubReset = onHubReset,
                )
            }
        }
    }
}
