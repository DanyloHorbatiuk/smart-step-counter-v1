package com.example.smartstepcounter.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartstepcounter.ml.AiLabel
import com.example.smartstepcounter.ml.AiStatus
import com.example.smartstepcounter.ml.AiUiState
import com.example.smartstepcounter.ml.MlConfig
import com.example.smartstepcounter.sensor.MotionState
import com.example.smartstepcounter.ui.theme.RunningColor
import com.example.smartstepcounter.ui.theme.ShakeColor
import com.example.smartstepcounter.ui.theme.StillColor
import com.example.smartstepcounter.ui.theme.WalkingColor
import java.util.Locale
import kotlin.math.roundToInt

private val UK = Locale.forLanguageTag("uk-UA")

private fun AiLabel.color() = when (this) {
    AiLabel.STILL -> StillColor
    AiLabel.WALKING -> WalkingColor
    AiLabel.RUNNING -> RunningColor
    AiLabel.SHAKE -> ShakeColor
}

private fun MotionState.title() = AiLabel.entries.first { it.motionState == this }.title

private fun Float.percent() = "${(this * 100).roundToInt()}%"

/** Lab 6: on-device classifier output, compared with the Lab 1 thresholds. */
@Composable
fun AiInsightsCard(ai: AiUiState) {
    val insight = ai.insight
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI Insights · on-device", style = MaterialTheme.typography.titleMedium)
            when (insight.status) {
                AiStatus.LOADING -> Text("Завантаження моделі…")
                AiStatus.UNAVAILABLE -> Text(
                    "ML недоступний: модель не завантажилась. Застосунок працює на порогах ЛР1.",
                    color = MaterialTheme.colorScheme.error,
                )
                AiStatus.WARMING_UP -> Text("Збираю вікно: ${insight.bufferFill}/${MlConfig.WINDOW} відліків…")
                AiStatus.READY -> Unit
            }
            val smoothed = insight.smoothed ?: return@Column

            Text(
                "Поточна активність: ${smoothed.label.title} (${smoothed.confidence.percent()})",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = smoothed.label.color(),
            )
            AiLabel.entries.forEach { label ->
                ProbabilityBar(label, smoothed.probabilities[label.ordinal])
            }

            val latest = insight.latest
            val inference = latest?.let { String.format(UK, "%.2f мс", it.inferenceMs) } ?: "—"
            InfoRow("Час inference", inference)
            InfoRow("Інтервал запису в БД", "${ai.recordIntervalMs} мс")
            InfoRow("Вікон класифіковано", insight.windowsClassified.toString())

            val matches = insight.matchesThresholds == true
            val chipColor = if (matches) Color(0xFF2E7D32) else Color(0xFFEF6C00)
            Text(
                if (matches) "✓ збігається з порогами ЛР1 (${insight.detectorState.title()})"
                else "✗ не збігається: пороги ЛР1 — ${insight.detectorState.title()}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(chipColor, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ProbabilityBar(label: AiLabel, probability: Float) {
    val animated by animateFloatAsState(probability, label = "prob_${label.name}")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.title, Modifier.width(110.dp), fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .weight(1f)
                .height(10.dp),
            color = label.color(),
            drawStopIndicator = {},
        )
        Text(
            probability.percent(),
            Modifier.width(48.dp),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun InfoRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
