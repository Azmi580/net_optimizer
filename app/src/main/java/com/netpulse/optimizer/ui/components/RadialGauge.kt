package com.netpulse.optimizer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netpulse.optimizer.domain.model.SignalQuality
import com.netpulse.optimizer.ui.theme.qualityColor

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f

/** 270° radial gauge for the 0–100 network score; arc and number tint green / amber / red by quality. */
@Composable
fun RadialGauge(
    score: Int,
    quality: SignalQuality,
    modifier: Modifier = Modifier,
    diameter: Dp = 196.dp,
    strokeWidth: Dp = 16.dp,
) {
    val known = quality != SignalQuality.UNKNOWN
    val progress by animateFloatAsState(
        targetValue = if (known) score.coerceIn(0, 100) / 100f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "gaugeProgress",
    )
    val color by animateColorAsState(qualityColor(quality), animationSpec = tween(500), label = "gaugeColor")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = modifier
            .size(diameter)
            .semantics { contentDescription = "Ağ skoru ${if (known) score else "ölçülüyor"} / 100, ${quality.label}" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = color,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (known) score.toString() else "—",
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                color = color,
            )
            Text(
                text = quality.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            Text(
                text = "/ 100",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
