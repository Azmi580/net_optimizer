package com.netpulse.optimizer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.netpulse.optimizer.domain.model.LatencyMetrics
import com.netpulse.optimizer.ui.theme.QualityColors
import kotlin.math.abs
import kotlin.math.max

/** Card wrapping the sparkline with the 30-second summary numbers. */
@Composable
fun LatencyCard(latency: LatencyMetrics, capacity: Int, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val jitterColor = MaterialTheme.colorScheme.tertiary
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gecikme · son 30 sn", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "ort ${latency.avgRttMs?.let { "$it ms" } ?: "—"} · kayıp %${latency.lossPercent}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LatencySparkline(
                history = latency.history,
                capacity = capacity,
                lineColor = lineColor,
                jitterColor = jitterColor,
                lossColor = QualityColors.Weak,
                gridColor = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(lineColor, "Ping")
                LegendDot(jitterColor, "Jitter ${latency.jitterMs?.let { "$it ms" } ?: "—"}")
                LegendDot(QualityColors.Weak, "Kayıp")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Minimal sparkline: ping as a solid line, sample-to-sample variation (jitter) as a dashed line,
 * lost probes as red dots on the baseline. Newest sample is right-aligned.
 */
@Composable
fun LatencySparkline(
    history: List<Long?>,
    capacity: Int,
    lineColor: Color,
    jitterColor: Color,
    lossColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val slots = max(capacity, 2)
        val stepX = size.width / (slots - 1)
        val offset = slots - history.size
        val values = history.filterNotNull()
        val ceiling = max(values.maxOrNull() ?: 0L, 60L) * 1.2f
        val strokePx = 2.dp.toPx()
        val baseline = size.height - strokePx

        fun x(index: Int) = (offset + index) * stepX
        fun y(value: Long) = baseline - (value / ceiling) * (baseline - strokePx)

        // Mid grid line (half of ceiling) for scale reference.
        drawLine(
            color = gridColor,
            start = Offset(0f, baseline / 2f),
            end = Offset(size.width, baseline / 2f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )

        val pingPath = Path()
        val jitterPath = Path()
        var pingOpen = false
        var jitterOpen = false
        history.forEachIndexed { i, value ->
            if (value == null) {
                pingOpen = false
                jitterOpen = false
                drawCircle(lossColor, radius = 3.dp.toPx(), center = Offset(x(i), baseline))
                return@forEachIndexed
            }
            if (pingOpen) pingPath.lineTo(x(i), y(value)) else pingPath.moveTo(x(i), y(value))
            pingOpen = true

            val previous = history.getOrNull(i - 1)
            if (previous != null) {
                val jitter = abs(value - previous)
                if (jitterOpen) jitterPath.lineTo(x(i), y(jitter)) else jitterPath.moveTo(x(i), y(jitter))
                jitterOpen = true
            } else {
                jitterOpen = false
            }
        }

        drawPath(
            path = jitterPath,
            color = jitterColor.copy(alpha = 0.7f),
            style = Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            ),
        )
        drawPath(
            path = pingPath,
            color = lineColor,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Emphasise the newest sample.
        history.lastOrNull()?.let { last ->
            drawCircle(lineColor, radius = 3.5.dp.toPx(), center = Offset(x(history.lastIndex), y(last)))
        }
    }
}
