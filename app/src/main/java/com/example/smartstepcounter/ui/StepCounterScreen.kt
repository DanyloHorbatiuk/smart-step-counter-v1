package com.example.smartstepcounter.ui

import android.hardware.SensorManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartstepcounter.HISTORY_SIZE
import com.example.smartstepcounter.StepCounterUiState
import com.example.smartstepcounter.cloud.CloudStatus
import com.example.smartstepcounter.cloud.CloudSyncState
import com.example.smartstepcounter.ml.AiUiState
import com.example.smartstepcounter.sensor.MotionState
import com.example.smartstepcounter.sensor.Thresholds
import com.example.smartstepcounter.ui.theme.AxisXColor
import com.example.smartstepcounter.ui.theme.AxisYColor
import com.example.smartstepcounter.ui.theme.AxisZColor
import com.example.smartstepcounter.ui.theme.RunningColor
import com.example.smartstepcounter.ui.theme.StillColor
import com.example.smartstepcounter.ui.theme.WalkingColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Range of the X/Y/Z bars: ±2g. */
private const val AXIS_RANGE = 2 * Thresholds.GRAVITY

/** Upper bound of the intensity scale, m/s². */
private const val INTENSITY_MAX = 7f

/** Range of the signal chart, m/s². */
private const val CHART_RANGE = 6f

private fun Float.fmt(digits: Int = 2) = String.format(Locale.US, "%.${digits}f", this)

private fun MotionState.label() = when (this) {
    MotionState.STILL -> "Спокій"
    MotionState.WALKING -> "Ходьба"
    MotionState.RUNNING -> "Біг"
}

private fun MotionState.color() = when (this) {
    MotionState.STILL -> StillColor
    MotionState.WALKING -> WalkingColor
    MotionState.RUNNING -> RunningColor
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepCounterScreen(state: StepCounterUiState, cloud: CloudSyncState, ai: AiUiState, onReset: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Smart Step Counter") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AiInsightsCard(ai)
            CloudSyncCard(cloud)
            if (!state.sensorAvailable) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Акселерометр недоступний на цьому пристрої.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@Column
            }
            StepsCard(state, onReset)
            IntensityCard(state)
            AxesCard(state)
            TiltCard(state)
            SignalCard(state)
            ThresholdsCard()
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun CloudStatus.label() = when (this) {
    CloudStatus.NOT_CONFIGURED -> "Хмара не налаштована"
    CloudStatus.IDLE -> "Очікування даних"
    CloudStatus.SYNCING -> "Синхронізація…"
    CloudStatus.SUCCESS -> "Синхронізовано"
    CloudStatus.ERROR -> "Помилка відправки"
    CloudStatus.PENDING -> "Офлайн — дані в черзі"
}

private fun CloudStatus.color() = when (this) {
    CloudStatus.SUCCESS -> Color(0xFF2E7D32)
    CloudStatus.ERROR -> Color(0xFFC62828)
    CloudStatus.PENDING -> Color(0xFFF9A825)
    CloudStatus.SYNCING -> Color(0xFF1565C0)
    CloudStatus.IDLE, CloudStatus.NOT_CONFIGURED -> Color(0xFF9E9E9E)
}

@Composable
private fun CloudSyncCard(cloud: CloudSyncState) {
    val dotColor by animateColorAsState(cloud.status.color(), label = "cloudDot")
    SectionCard("Хмарна синхронізація") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(dotColor, RoundedCornerShape(50)),
            )
            Spacer(Modifier.width(8.dp))
            Text(cloud.status.label(), fontWeight = FontWeight.SemiBold)
        }
        if (cloud.status == CloudStatus.NOT_CONFIGURED) {
            Text(
                "Додайте FIREBASE_DB_URL у local.properties і перезберіть застосунок",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val last = cloud.lastSuccessAt?.let {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
        } ?: "—"
        Text("Остання відправка: $last", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Відправлено: ${cloud.sentCount} · У черзі: ${cloud.pendingCount}",
            style = MaterialTheme.typography.bodyMedium,
        )
        cloud.lastError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StepsCard(state: StepCounterUiState, onReset: () -> Unit) {
    val s = state.snapshot
    val stateColor by animateColorAsState(s.state.color(), label = "stateColor")
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Кроки", style = MaterialTheme.typography.titleMedium)
            Text(
                s.steps.toString(),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
            )
            Box(
                Modifier
                    .background(stateColor, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(s.state.label(), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text("Темп: ${s.cadence.fmt(0)} кроків/хв")
            Text(
                if (state.listening) "Сенсор активний (≈50 Гц)" else "Сенсор зупинено (onPause)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE) {
                Text(
                    "Дані сенсора ненадійні",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onReset) { Text("Скинути") }
        }
    }
}

/** Horizontal scale with coloured STILL/WALKING/RUNNING zones and a marker at the current RMS. */
@Composable
private fun IntensityCard(state: StepCounterUiState) {
    val rms = state.snapshot.rms
    val animated by animateFloatAsState(
        targetValue = (rms / INTENSITY_MAX).coerceIn(0f, 1f),
        animationSpec = tween(150),
        label = "intensity",
    )
    val markerColor = MaterialTheme.colorScheme.onSurface
    SectionCard("Інтенсивність руху (RMS за 2 с): ${rms.fmt()} м/с²") {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            val walkX = size.width * Thresholds.WALK_RMS / INTENSITY_MAX
            val runX = size.width * Thresholds.RUN_RMS / INTENSITY_MAX
            val r = CornerRadius(6.dp.toPx())
            drawRoundRect(RunningColor.copy(alpha = 0.35f), cornerRadius = r)
            drawRect(StillColor.copy(alpha = 0.35f), size = Size(walkX, size.height))
            drawRect(
                WalkingColor.copy(alpha = 0.35f),
                topLeft = Offset(walkX, 0f),
                size = Size(runX - walkX, size.height),
            )
            val x = animated * size.width
            drawRect(
                markerColor,
                topLeft = Offset(x - 2.dp.toPx(), 0f),
                size = Size(4.dp.toPx(), size.height),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Спокій", color = StillColor, style = MaterialTheme.typography.labelSmall)
            Text("${Thresholds.WALK_RMS} → Ходьба", color = WalkingColor, style = MaterialTheme.typography.labelSmall)
            Text("${Thresholds.RUN_RMS} → Біг", color = RunningColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AxesCard(state: StepCounterUiState) {
    val s = state.snapshot
    SectionCard("Координати акселерометра, м/с²") {
        AxisRow("X", s.x, AxisXColor)
        AxisRow("Y", s.y, AxisYColor)
        AxisRow("Z", s.z, AxisZColor)
        Text(
            "|a| = ${s.magnitude.fmt()}    динамічне = ${s.dynamic.fmt()}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Value with a bar that grows left (negative) or right (positive) from the centre. */
@Composable
private fun AxisRow(name: String, value: Float, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val centerLine = MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$name: ${value.fmt().padStart(6)}",
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(110.dp),
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(14.dp),
        ) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val cx = size.width / 2
            val w = (value / AXIS_RANGE).coerceIn(-1f, 1f) * cx
            drawRect(
                color,
                topLeft = Offset(if (w < 0) cx + w else cx, 0f),
                size = Size(abs(w), size.height),
            )
            drawLine(centerLine, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f)
        }
    }
}

/**
 * "Bubble level": the dot moves with the device tilt.
 * With the phone flat on a table X ≈ Y ≈ 0 and the dot is in the centre.
 */
@Composable
private fun TiltCard(state: StepCounterUiState) {
    val s = state.snapshot
    val norm = sqrt(s.x * s.x + s.y * s.y + s.z * s.z).coerceAtLeast(0.1f)
    // Tilt as a fraction of gravity. The dot rolls to the lower edge like a ball:
    // right edge down -> X < 0 -> dot moves right, top edge down -> Y < 0 -> dot moves up.
    val tx by animateFloatAsState((-s.x / norm).coerceIn(-1f, 1f), tween(80), label = "tx")
    val ty by animateFloatAsState((s.y / norm).coerceIn(-1f, 1f), tween(80), label = "ty")
    val outline = MaterialTheme.colorScheme.outline
    val dotColor = s.state.color()
    SectionCard("Індикатор нахилу") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(200.dp)) {
                val radius = min(size.width, size.height) / 2
                val c = center
                drawCircle(outline, radius, c, style = Stroke(2.dp.toPx()))
                drawCircle(outline, radius / 2, c, style = Stroke(1.dp.toPx()))
                drawLine(outline, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y))
                drawLine(outline, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius))
                val dotR = 16.dp.toPx()
                val reach = radius - dotR
                drawCircle(dotColor, dotR, Offset(c.x + tx * reach, c.y + ty * reach))
            }
        }
        Text(
            "Нахиліть пристрій (або змініть положення у Virtual Sensors емулятора)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Live chart of dynamic acceleration with the step thresholds drawn as dashed lines. */
@Composable
private fun SignalCard(state: StepCounterUiState) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val history = state.history
    SectionCard("Сигнал (|a| − g), останні ~3 с") {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            fun yOf(v: Float) = size.height / 2 - (v / CHART_RANGE).coerceIn(-1f, 1f) * size.height / 2
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            drawLine(grid, Offset(0f, yOf(0f)), Offset(size.width, yOf(0f)))
            drawLine(
                RunningColor, Offset(0f, yOf(Thresholds.STEP_PEAK)),
                Offset(size.width, yOf(Thresholds.STEP_PEAK)), strokeWidth = 2f, pathEffect = dash,
            )
            drawLine(
                StillColor, Offset(0f, yOf(Thresholds.STEP_RESET)),
                Offset(size.width, yOf(Thresholds.STEP_RESET)), strokeWidth = 2f, pathEffect = dash,
            )
            if (history.size >= 2) {
                val step = size.width / (HISTORY_SIZE - 1)
                val startX = size.width - step * (history.size - 1)
                val path = Path()
                history.forEachIndexed { i, v ->
                    val x = startX + i * step
                    if (i == 0) path.moveTo(x, yOf(v)) else path.lineTo(x, yOf(v))
                }
                drawPath(path, line, style = Stroke(2.dp.toPx()))
            }
        }
        Text(
            "Червона лінія — поріг кроку (${Thresholds.STEP_PEAK} м/с²), сіра — скидання (${Thresholds.STEP_RESET} м/с²)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThresholdsCard() {
    SectionCard("Порогові значення (Threshold)") {
        val rows = listOf(
            "Поріг кроку" to "${Thresholds.STEP_PEAK} м/с²",
            "Повторне спрацювання після" to "< ${Thresholds.STEP_RESET} м/с²",
            "Мін. інтервал між кроками" to "${Thresholds.MIN_STEP_INTERVAL_NS / 1_000_000} мс",
            "Спокій → Ходьба" to "RMS ≥ ${Thresholds.WALK_RMS} м/с²",
            "Ходьба → Біг" to "RMS ≥ ${Thresholds.RUN_RMS} м/с²",
            "Вікно аналізу" to "${Thresholds.WINDOW_NS / 1_000_000_000} с",
        )
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, style = MaterialTheme.typography.bodyMedium)
                Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
