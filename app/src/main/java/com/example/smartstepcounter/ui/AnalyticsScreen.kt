package com.example.smartstepcounter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartstepcounter.analytics.AnalyticsUiState
import com.example.smartstepcounter.analytics.Series
import com.example.smartstepcounter.data.local.ActuatorEventEntity
import com.example.smartstepcounter.data.local.SensorRecord
import com.example.smartstepcounter.history.StorageStatus
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AnalyticsActions(
    val onToggleSeries: (Series) -> Unit,
    val onClearHistory: () -> Unit,
)

private val ActionMarkerColor = Color(0xFFD32F2F)

private fun Series.color() = when (this) {
    Series.X -> Color(0xFFE53935)
    Series.Y -> Color(0xFF43A047)
    Series.Z -> Color(0xFF1E88E5)
    Series.MAGNITUDE -> Color(0xFF6A1B9A)
    Series.RMS -> Color(0xFFEF6C00)
}

/** Wall-clock time of every chart point; x of a point is its index in this list. */
private val TimestampsKey = ExtraStore.Key<List<Long>>()

/** Indices of the points recorded at an actuator action. */
private val ActionIndicesKey = ExtraStore.Key<Set<Int>>()

private fun formatTime(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(state: AnalyticsUiState, actions: AnalyticsActions) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Аналітика") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChartCard(state.records, state.series, actions.onToggleSeries)
            StatsCard(state)
            HistoryEventsCard(state.actuatorEvents)
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Очистити історію")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистити історію?") },
            text = { Text("Буде видалено всі записи сенсора й журнал актуаторів. Черга хмарної синхронізації не зміниться.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    actions.onClearHistory()
                }) { Text("Видалити") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun AnalyticsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ChartCard(records: List<SensorRecord>, active: Set<Series>, onToggle: (Series) -> Unit) {
    AnalyticsCard("Останні ${records.size} записів") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Series.entries.forEach { s ->
                FilterChip(
                    selected = s in active,
                    onClick = { onToggle(s) },
                    label = { Text(s.label) },
                    leadingIcon = { Dot(s.color()) },
                )
            }
        }
        // Keep the enum order, so a series keeps its colour when others are toggled.
        val shown = Series.entries.filter { it in active }
        when {
            shown.isEmpty() -> ChartPlaceholder("Увімкніть хоча б одну серію")
            records.size < 2 -> ChartPlaceholder("Даних ще немає: рухайте телефон, запис іде раз на інтервал")
            else -> HistoryChart(records, shown)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(ActionMarkerColor)
            Spacer(Modifier.width(6.dp))
            Text("спрацювання актуатора", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChartPlaceholder(text: String) {
    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

/** Draws a marker only on points recorded at an actuator action. */
private class ActionPointProvider(private val point: LineCartesianLayer.Point) : LineCartesianLayer.PointProvider {
    override fun getPoint(
        entry: LineCartesianLayerModel.Entry,
        seriesIndex: Int,
        extraStore: ExtraStore,
    ): LineCartesianLayer.Point? =
        point.takeIf { entry.x.toInt() in extraStore.getOrNull(ActionIndicesKey).orEmpty() }

    override fun getLargestPoint(extraStore: ExtraStore): LineCartesianLayer.Point = point
}

/**
 * Vico line chart. X is the point index (records come at a roughly constant interval)
 * and its labels show the record time; this keeps the axis readable when the change
 * filter leaves gaps.
 */
@Composable
private fun HistoryChart(records: List<SensorRecord>, shown: List<Series>) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(records, shown) {
        producer.runTransaction {
            lineSeries {
                shown.forEach { s -> series(records.indices.toList(), records.map { s.value(it) }) }
            }
            extras {
                it[TimestampsKey] = records.map(SensorRecord::timestamp)
                it[ActionIndicesKey] = records.indices.filter { i -> records[i].actionTriggered }.toSet()
            }
        }
    }

    val marker = rememberShapeComponent(fill(ActionMarkerColor), CorneredShape.Pill)
    val pointProvider = remember(marker) { ActionPointProvider(LineCartesianLayer.Point(marker, 9f)) }
    // One line per series in enum order: a fixed number of remember calls.
    val lines = Series.entries.associateWith { s ->
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(fill(s.color())),
            pointProvider = pointProvider,
        )
    }
    val timeFormatter = remember {
        CartesianValueFormatter { context, x, _ ->
            context.model.extraStore.getOrNull(TimestampsKey)?.getOrNull(x.toInt())?.let(::formatTime) ?: " "
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(LineCartesianLayer.LineProvider.series(shown.map { lines.getValue(it) })),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = timeFormatter,
                itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned(spacing = { LABEL_SPACING }) },
            ),
        ),
        modelProducer = producer,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        modifier = Modifier.fillMaxWidth().height(240.dp),
    )
}

/** A time label every N points: HH:mm:ss labels are wide. */
private const val LABEL_SPACING = 12

private fun StorageStatus.label() = when (this) {
    StorageStatus.OK -> "OK"
    StorageStatus.FULL -> "Мало місця"
    StorageStatus.ERROR -> "Помилка"
}

private fun StorageStatus.color() = when (this) {
    StorageStatus.OK -> Color(0xFF2E7D32)
    StorageStatus.FULL -> Color(0xFFEF6C00)
    StorageStatus.ERROR -> Color(0xFFC62828)
}

@Composable
private fun StatsCard(state: AnalyticsUiState) {
    AnalyticsCard("Локальна база") {
        StatRow("Записів у БД", "${state.totalCount}")
        StatRow("Інтервал запису", "${state.recordIntervalMs} мс")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Стан сховища", Modifier.weight(1f))
            Dot(state.storage.status.color())
            Spacer(Modifier.width(6.dp))
            Text(state.storage.status.label(), fontWeight = FontWeight.SemiBold)
        }
        state.storage.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun actuatorLabel(name: String) = when (name) {
    "TORCH" -> "Ліхтарик"
    "VIBRATION" -> "Вібрація"
    "LAMP" -> "Лампа"
    else -> name
}

@Composable
private fun HistoryEventsCard(events: List<ActuatorEventEntity>) {
    AnalyticsCard("Історія актуаторів (останні ${events.size})") {
        if (events.isEmpty()) {
            Text("Подій у базі ще немає", style = MaterialTheme.typography.bodySmall)
            return@AnalyticsCard
        }
        events.forEach { e ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(e.timestamp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(actuatorLabel(e.actuator), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (e.isOn) "увімк" else "вимк", fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(e.source, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Text(e.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
