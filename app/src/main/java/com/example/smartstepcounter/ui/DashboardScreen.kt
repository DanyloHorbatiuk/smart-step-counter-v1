package com.example.smartstepcounter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartstepcounter.actuator.ActuatorState
import com.example.smartstepcounter.ble.BleConnectionState
import com.example.smartstepcounter.cloud.CloudSyncState
import com.example.smartstepcounter.data.local.SensorRecord
import com.example.smartstepcounter.ml.AiLabel
import com.example.smartstepcounter.ml.AiStatus
import com.example.smartstepcounter.ml.AiUiState
import kotlin.math.roundToInt

/** Lab 7: everything the "Головна" tab shows, collected from the existing ViewModels. */
data class DashboardState(
    val ai: AiUiState,
    val steps: Int,
    /** Newest first. */
    val recent: List<SensorRecord>,
    val cloud: CloudSyncState,
    val ble: BleConnectionState,
    val actuator: ActuatorState,
    val autoMode: Boolean,
)

data class DashboardActions(
    val onVibrate: () -> Unit,
    val onTorch: (Boolean) -> Unit,
    val onAutoMode: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(state: DashboardState, actions: DashboardActions) {
    Scaffold(topBar = { TopAppBar(title = { Text("Головна") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AiStateCard(state.ai, state.steps)
            LinksCard(state.cloud, state.ble)
            QuickControlsCard(state.actuator, state.autoMode, actions)
            RecentRecordsCard(state.recent)
        }
    }
}

private fun AiStatus.label() = when (this) {
    AiStatus.LOADING -> "завантаження"
    AiStatus.UNAVAILABLE -> "недоступна"
    AiStatus.WARMING_UP -> "накопичення вікна"
    AiStatus.READY -> "працює"
}

private fun aiTitle(name: String?) = AiLabel.entries.firstOrNull { it.name == name }?.title ?: "—"

@Composable
private fun AiStateCard(ai: AiUiState, steps: Int) {
    val smoothed = ai.insight.smoothed
    ControlCard("Поточний стан (AI)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                smoothed?.label?.title ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = smoothed?.label?.color() ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(smoothed?.let { "${(it.confidence * 100).roundToInt()}%" } ?: "", style = MaterialTheme.typography.titleLarge)
        }
        Text("Модель: ${ai.insight.status.label()} · кроків: $steps", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusRow(title: String, color: Color, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(8.dp))
        Text(title, Modifier.width(72.dp), fontWeight = FontWeight.SemiBold)
        Text(value)
    }
}

@Composable
private fun LinksCard(cloud: CloudSyncState, ble: BleConnectionState) {
    ControlCard("Зв'язок") {
        StatusRow("Хмара", cloud.status.color(), "${cloud.status.label()} · у черзі: ${cloud.pendingCount}")
        StatusRow("BLE", ble.color(), ble.label())
    }
}

@Composable
private fun QuickControlsCard(actuator: ActuatorState, autoMode: Boolean, actions: DashboardActions) {
    ControlCard("Швидке керування") {
        Button(onClick = actions.onVibrate) { Text("Вібрація") }
        SwitchRow("Ліхтарик", actuator.torchOn, actions.onTorch)
        SwitchRow("Авто-режим", autoMode, actions.onAutoMode)
    }
}

@Composable
private fun RecentRecordsCard(records: List<SensorRecord>) {
    ControlCard("Останні записи (Room)") {
        if (records.isEmpty()) {
            Text("Записів ще немає", style = MaterialTheme.typography.bodySmall)
            return@ControlCard
        }
        records.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(r.timestamp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text("${r.steps} кр.", Modifier.width(56.dp), fontSize = 13.sp)
                Text(r.state, Modifier.weight(1f), fontSize = 13.sp)
                Text(
                    aiTitle(r.aiLabel) + (r.aiConfidence?.let { " ${(it * 100).roundToInt()}%" } ?: ""),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
