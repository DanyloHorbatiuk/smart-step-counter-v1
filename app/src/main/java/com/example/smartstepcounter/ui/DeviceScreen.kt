package com.example.smartstepcounter.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.smartstepcounter.ble.BleConnectionState
import com.example.smartstepcounter.ble.BleDevice
import com.example.smartstepcounter.ble.BleDirection
import com.example.smartstepcounter.ble.BleLogEntry
import com.example.smartstepcounter.ble.BleUiState
import com.example.smartstepcounter.ble.BleUuids
import com.example.smartstepcounter.ble.HUB_NAME
import com.example.smartstepcounter.ble.isLinked
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runtime permissions needed before a BLE scan (Android 12+). */
private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

/** Callbacks of the Device screen, grouped to keep the signature short. */
data class DeviceActions(
    val onPermissionsResult: (Boolean) -> Unit,
    val onScan: () -> Unit,
    val onConnect: (BleDevice) -> Unit,
    val onSendNow: () -> Unit,
    val onHubReset: () -> Unit,
    val onHubPing: () -> Unit,
    val onSimulateLoss: () -> Unit,
    val onDisconnect: () -> Unit,
)

private fun BleConnectionState.label() = when (this) {
    BleConnectionState.DISCONNECTED -> "Відключено"
    BleConnectionState.SCANNING -> "Пошук…"
    BleConnectionState.CONNECTING -> "Підключення…"
    BleConnectionState.CONNECTED -> "Підключено"
    BleConnectionState.DATA_TRANSFER -> "Передача даних"
}

private fun BleConnectionState.color() = when (this) {
    BleConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
    BleConnectionState.SCANNING -> Color(0xFF42A5F5)
    BleConnectionState.CONNECTING -> Color(0xFFFFA726)
    BleConnectionState.CONNECTED -> Color(0xFF66BB6A)
    BleConnectionState.DATA_TRANSFER -> Color(0xFFAB47BC)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(state: BleUiState, actions: DeviceActions) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> actions.onPermissionsResult(result.values.all { it }) }

    val startScan = {
        val granted = BLE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) actions.onScan() else permissionLauncher.launch(BLE_PERMISSIONS)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Пристрій StepHub") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(state.connection)
            ScanCard(state, onScan = startScan, onConnect = actions.onConnect)
            ControlsCard(state.connection.isLinked, actions)
            TerminalCard(state.log)
        }
    }
}

@Composable
private fun StatusCard(connection: BleConnectionState) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(connection.color(), CircleShape)
            )
            Text(
                connection.label(),
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                connection.name,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScanCard(state: BleUiState, onScan: () -> Unit, onConnect: (BleDevice) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onScan,
                enabled = !state.scanning && state.connection == BleConnectionState.DISCONNECTED,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Пошук пристроїв") }
            if (state.scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.permissionDenied) {
                Text(
                    "Немає дозволів Bluetooth / геолокації — пошук неможливий. " +
                        "Надайте дозволи в налаштуваннях застосунку.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.devices.forEach { device ->
                DeviceRow(
                    device = device,
                    enabled = state.connection == BleConnectionState.DISCONNECTED,
                    onClick = { onConnect(device) },
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: BleDevice, enabled: Boolean, onClick: () -> Unit) {
    val ours = device.name == HUB_NAME
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = if (ours) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold)
                Text(
                    device.address,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (ours) {
                AssistChip(onClick = onClick, enabled = enabled, label = { Text("наш пристрій") })
            }
            Text(
                "${device.rssi} dBm",
                modifier = Modifier.padding(start = 8.dp),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ControlsCard(linked: Boolean, actions: DeviceActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = actions.onSendNow,
                    enabled = linked,
                    modifier = Modifier.weight(1f),
                ) { Text("Надіслати кроки зараз") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = actions.onHubReset,
                    enabled = linked,
                    modifier = Modifier.weight(1f),
                ) { Text("Хаб: RESET") }
                FilledTonalButton(
                    onClick = actions.onHubPing,
                    enabled = linked,
                    modifier = Modifier.weight(1f),
                ) { Text("Хаб: PING") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions.onSimulateLoss,
                    enabled = linked,
                    modifier = Modifier.weight(1f),
                ) { Text("Імітувати обрив зв'язку") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions.onDisconnect,
                    enabled = linked,
                    modifier = Modifier.weight(1f),
                ) { Text("Відключитися") }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun BleLogEntry.format(): String {
    val time = timeFormat.format(Date(timeMillis))
    val target = if (characteristic != null) "[${BleUuids.shortName(characteristic)}] " else ""
    val size = if (sizeBytes > 0) " ($sizeBytes B)" else ""
    return "$time ${direction.name.padEnd(3)} $target$message$size"
}

private fun BleDirection.color() = when (this) {
    BleDirection.TX -> Color(0xFF80CBC4)
    BleDirection.RX -> Color(0xFFFFCC80)
    BleDirection.SYS -> Color(0xFFB0BEC5)
}

@Composable
private fun TerminalCard(log: List<BleLogEntry>) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Термінал", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                if (log.isEmpty()) {
                    item {
                        Text(
                            "Лог порожній. Почніть пошук пристроїв.",
                            color = Color(0xFF757575),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
                items(log) { entry ->
                    Text(
                        entry.format(),
                        color = entry.direction.color(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}
