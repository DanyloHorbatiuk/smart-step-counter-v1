package com.example.smartstepcounter.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.smartstepcounter.actuator.ActuatorEvent
import com.example.smartstepcounter.actuator.ActuatorState
import com.example.smartstepcounter.actuator.ActuatorType
import com.example.smartstepcounter.actuator.ControlViewModel
import com.example.smartstepcounter.actuator.EventSource
import com.example.smartstepcounter.cloud.ControlListenerState
import com.example.smartstepcounter.cloud.ControlListenerStatus
import com.example.smartstepcounter.decision.DecisionConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LampOnColor = Color(0xFFFFD54F)
private val LampOffColor = Color(0xFF616161)

data class ControlActions(
    val onTorch: (Boolean) -> Unit,
    val onVibrate: () -> Unit,
    val onAutoMode: (Boolean) -> Unit,
    val onMirror: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(viewModel: ControlViewModel, actions: ControlActions) {
    val actuator by viewModel.actuatorState.collectAsStateWithLifecycle()
    val listener by viewModel.listenerState.collectAsStateWithLifecycle()
    val autoMode by viewModel.autoMode.collectAsStateWithLifecycle()
    val mirror by viewModel.mirrorLedToTorch.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Керування") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CloudControlCard(listener, viewModel.deviceId)
            LampCard(actuator.virtualLampOn, viewModel.deviceId, mirror, actions.onMirror)
            TorchCard(actuator, actions.onTorch)
            VibrationCard(actuator, actions.onVibrate)
            AutoModeCard(autoMode, viewModel.config, actions.onAutoMode)
            EventLogCard(log)
        }
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun ControlListenerStatus.label() = when (this) {
    ControlListenerStatus.NOT_CONFIGURED -> "Не налаштовано"
    ControlListenerStatus.CONNECTING -> "Підключення…"
    ControlListenerStatus.LISTENING -> "Слухає команди (SSE)"
    ControlListenerStatus.RECONNECTING -> "Перепідключення…"
    ControlListenerStatus.ERROR -> "Помилка"
}

private fun ControlListenerStatus.color() = when (this) {
    ControlListenerStatus.LISTENING -> Color(0xFF2E7D32)
    ControlListenerStatus.CONNECTING -> Color(0xFF1565C0)
    ControlListenerStatus.RECONNECTING -> Color(0xFFF9A825)
    ControlListenerStatus.ERROR -> Color(0xFFC62828)
    ControlListenerStatus.NOT_CONFIGURED -> Color(0xFF9E9E9E)
}

private fun formatTime(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

@Composable
private fun CloudControlCard(state: ControlListenerState, deviceId: String) {
    val dotColor by animateColorAsState(state.status.color(), label = "controlDot")
    val clipboard = LocalClipboardManager.current
    ControlCard("Cloud-to-Device") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(dotColor, RoundedCornerShape(50)),
            )
            Spacer(Modifier.width(8.dp))
            Text(state.status.label(), fontWeight = FontWeight.SemiBold)
        }
        if (state.status == ControlListenerStatus.NOT_CONFIGURED) {
            Text(
                "Додайте FIREBASE_DB_URL у local.properties, щоб отримувати команди з Firebase.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Остання команда: ${state.lastCommandAt?.let(::formatTime) ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
        )
        state.lastError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "deviceId: $deviceId",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(deviceId)) }) { Text("Копіювати") }
        }
    }
}

@Composable
private fun LampCard(on: Boolean, deviceId: String, mirror: Boolean, onMirror: (Boolean) -> Unit) {
    val glow by animateFloatAsState(if (on) 1f else 0f, tween(400), label = "lampGlow")
    ControlCard("Віртуальна лампа") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(140.dp)) {
                val r = size.minDimension / 2
                if (glow > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(LampOnColor.copy(alpha = 0.6f * glow), Color.Transparent),
                            center = center,
                            radius = r,
                        ),
                        radius = r,
                    )
                }
                drawCircle(
                    color = lerp(LampOffColor, LampOnColor, glow),
                    radius = r * 0.6f,
                    center = Offset(size.width / 2, size.height / 2),
                )
            }
        }
        Text(
            if (on) "Увімкнена" else "Вимкнена",
            modifier = Modifier.fillMaxWidth(),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            "керується з Firebase: devices/$deviceId/control/led_status",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        SwitchRow("Дублювати лампу на ліхтарик", mirror, onMirror)
    }
}

@Composable
private fun TorchCard(state: ActuatorState, onTorch: (Boolean) -> Unit) {
    ControlCard("Ліхтарик") {
        SwitchRow(if (state.torchOn) "Увімкнено" else "Вимкнено", state.torchOn, onTorch)
        Text(
            if (state.torchIsVirtual) "віртуальний (спалаху немає)" else "апаратний",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.torchIsVirtual) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun VibrationCard(state: ActuatorState, onVibrate: () -> Unit) {
    ControlCard("Вібрація") {
        Button(onClick = onVibrate) { Text("Вібрація") }
        Text(
            buildString {
                append(if (state.vibratorAvailable) "Вібромотор є" else "Вібромотора немає — подія лише в журналі")
                state.lastVibrationAt?.let { append(" · остання: ${formatTime(it)}") }
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AutoModeCard(enabled: Boolean, config: DecisionConfig, onAutoMode: (Boolean) -> Unit) {
    ControlCard("Автоматика") {
        SwitchRow("Автоматичний режим (DecisionEngine)", enabled, onAutoMode)
        if (!enabled) {
            Text(
                "Рішення лише записуються в Logcat (тег Decision) і не виконуються.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()
        RuleRow("R1", "кроки ≥ ${config.stepGoal}", "подвійна вібрація, один раз до скидання")
        RuleRow("R2", "Біг ≥ ${config.runOnDelayMs / 1000} с", "ліхтарик увімк.; вимк. після ${config.runOffDelayMs / 1000} с без бігу")
        RuleRow("R3", "|a| ≥ ${config.impactThreshold.toInt()} м/с²", "вібрація-тривога, пауза ${config.impactCooldownMs / 1000} с")
    }
}

@Composable
private fun RuleRow(id: String, condition: String, action: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(id, Modifier.width(32.dp), fontWeight = FontWeight.Bold)
        Text(condition, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(action, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun ActuatorType.label() = when (this) {
    ActuatorType.TORCH -> "Ліхтарик"
    ActuatorType.VIBRATION -> "Вібрація"
    ActuatorType.LAMP -> "Лампа"
}

private fun EventSource.color() = when (this) {
    EventSource.AUTO -> Color(0xFF6A1B9A)
    EventSource.MANUAL -> Color(0xFF1565C0)
    EventSource.CLOUD -> Color(0xFFEF6C00)
    EventSource.AI -> Color(0xFF00897B)
}

@Composable
private fun EventLogCard(events: List<ActuatorEvent>) {
    ControlCard("Журнал подій (останні 30)") {
        if (events.isEmpty()) {
            Text("Подій ще немає", style = MaterialTheme.typography.bodySmall)
            return@ControlCard
        }
        events.forEach { e ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(e.timestamp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(e.actuator.label(), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (e.on) "увімк" else "вимк", fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(e.source.name, color = e.source.color(), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Text(e.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
