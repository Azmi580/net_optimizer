package com.netpulse.optimizer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.netpulse.optimizer.domain.model.LatencyMetrics
import com.netpulse.optimizer.ui.theme.MetricText
import com.netpulse.optimizer.ui.theme.QualityColors
import kotlin.math.abs
import kotlin.math.max

/** Glass card with the live latency wave and 30-second summary. */
@Composable
fun LatencyCard(latency: LatencyMetrics, capacity: Int, sampleIntervalMs: Long, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val jitterColor = MaterialTheme.colorScheme.tertiary
    GlassCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CANLI GECİKME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Son 30 saniye", style = MaterialTheme.typography.titleMedium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        latency.avgRttMs?.let { "$it ms" } ?: "—",
                        style = MetricText.large,
                        color = lineColor,
                    )
                    Text("ortalama", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LatencySparkline(
                history = latency.history,
                capacity = capacity,
                sampleIntervalMs = sampleIntervalMs,
                lineColor = lineColor,
                jitterColor = jitterColor,
                lossColor = QualityColors.Weak,
                gridColor = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(lineColor, "Ping")
                LegendDot(jitterColor, "Jitter ${latency.jitterMs?.let { "$it ms" } ?: "—"}")
                LegendDot(QualityColors.Weak, "Kayıp %${latency.lossPercent}")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.size(6.dp))
        Text(label, style = MetricText.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Live latency wave.
 *
 * - Ping is drawn as a smooth curve (midpoint quadratic Béziers) with a vertical gradient fill underneath;
 *   jitter (|Δ| between consecutive samples) as a dashed curve with its own faint fill.
 * - When a sample arrives the whole wave starts one slot to the right and glides left over the sample
 *   interval ([scroll]), so the chart flows continuously instead of jumping.
 * - The vertical scale eases towards the new maximum ([ceiling]) so spikes do not make the wave snap.
 * - Lost probes break the curve and show as red dots on the baseline.
 * Animations run only while the composable is on screen; nothing ticks in the background.
 */
@Composable
fun LatencySparkline(
    history: List<Long?>,
    capacity: Int,
    sampleIntervalMs: Long,
    lineColor: Color,
    jitterColor: Color,
    lossColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
) {
    val scroll = remember { Animatable(0f) }
    val ceiling = remember { Animatable(80f) }
    val maxValue = history.filterNotNull().maxOrNull() ?: 0L
    val targetCeiling = max(maxValue, 60L) * 1.25f

    LaunchedEffect(history) {
        if (history.size > 1) {
            scroll.snapTo(1f)
            scroll.animateTo(0f, tween(durationMillis = (sampleIntervalMs * 0.9).toInt(), easing = LinearEasing))
        }
    }
    LaunchedEffect(targetCeiling) {
        ceiling.animateTo(targetCeiling, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }

    Canvas(modifier) {
        val slots = max(capacity, 2)
        val stepX = size.width / (slots - 1)
        val slotOffset = slots - history.size
        val strokePx = 2.5.dp.toPx()
        val top = strokePx * 2
        val baseline = size.height - strokePx
        val scale = ceiling.value

        fun x(index: Int) = (slotOffset + index + scroll.value) * stepX
        fun y(value: Float) = baseline - (value / scale).coerceIn(0f, 1f) * (baseline - top)

        // Mesh grid: three faint horizontal lines.
        for (i in 1..3) {
            val gy = baseline - (baseline - top) * i / 4f
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(0f, gy),
                end = Offset(size.width, gy),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx())),
            )
        }

        val pingSegments = segments(history) { it.toFloat() }
        val jitterSamples = history.mapIndexed { i, v ->
            val prev = history.getOrNull(i - 1)
            if (v != null && prev != null) abs(v - prev).toFloat() else null
        }
        val jitterSegments = segments(jitterSamples) { it }

        clipRect {
            jitterSegments.forEach { segment ->
                drawWave(segment, ::x, ::y, baseline, jitterColor, fillAlpha = 0.10f, strokeWidth = 1.5.dp.toPx(), dashed = true)
            }
            pingSegments.forEach { segment ->
                drawWave(segment, ::x, ::y, baseline, lineColor, fillAlpha = 0.32f, strokeWidth = strokePx, dashed = false)
            }

            history.forEachIndexed { i, value ->
                if (value == null) drawCircle(lossColor, radius = 3.5.dp.toPx(), center = Offset(x(i), baseline - 3.5.dp.toPx()))
            }

            history.lastOrNull()?.let { last ->
                val head = Offset(x(history.lastIndex), y(last.toFloat()))
                drawCircle(lineColor.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = head)
                drawCircle(lineColor, radius = 4.dp.toPx(), center = head)
            }
        }
    }
}

/** Splits samples into contiguous non-null runs of (index, value). */
private fun <T> segments(values: List<T?>, toFloat: (T) -> Float): List<List<Pair<Int, Float>>> {
    val result = mutableListOf<List<Pair<Int, Float>>>()
    var current = mutableListOf<Pair<Int, Float>>()
    values.forEachIndexed { i, v ->
        if (v == null) {
            if (current.isNotEmpty()) result += current
            current = mutableListOf()
        } else {
            current += i to toFloat(v)
        }
    }
    if (current.isNotEmpty()) result += current
    return result
}

private fun DrawScope.drawWave(
    segment: List<Pair<Int, Float>>,
    x: (Int) -> Float,
    y: (Float) -> Float,
    baseline: Float,
    color: Color,
    fillAlpha: Float,
    strokeWidth: Float,
    dashed: Boolean,
) {
    val points = segment.map { (i, v) -> Offset(x(i), y(v)) }
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, radius = strokeWidth, center = points.first())
        return
    }

    val line = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (k in 1 until points.size) {
            val prev = points[k - 1]
            val cur = points[k]
            val mid = Offset((prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
            quadraticTo(prev.x, prev.y, mid.x, mid.y)
        }
        lineTo(points.last().x, points.last().y)
    }

    val fill = Path().apply {
        addPath(line)
        lineTo(points.last().x, baseline)
        lineTo(points.first().x, baseline)
        close()
    }
    val minY = points.minOf { it.y }
    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = fillAlpha), Color.Transparent),
            startY = minY,
            endY = baseline,
        ),
    )
    drawPath(
        path = line,
        color = if (dashed) color.copy(alpha = 0.75f) else color,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
        ),
    )
}
