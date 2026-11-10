package com.netpulse.optimizer.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.netpulse.optimizer.domain.model.SignalQuality
import com.netpulse.optimizer.ui.theme.MetricText
import com.netpulse.optimizer.ui.theme.qualityColor
import kotlin.math.cos
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f
private const val TICKS = 27

/**
 * 270° score gauge with a sweep-gradient arc, neon glow (BlurMaskFilter, hardware accelerated on API 28+),
 * a glowing head dot and 10-point tick marks. The arc animates with an [Animatable] tween, so a score
 * change always continues smoothly from the currently drawn position instead of jumping.
 */
@Composable
fun RadialGauge(
    score: Int,
    quality: SignalQuality,
    modifier: Modifier = Modifier,
    diameter: Dp = 220.dp,
    strokeWidth: Dp = 18.dp,
) {
    val known = quality != SignalQuality.UNKNOWN
    val target = if (known) score.coerceIn(0, 100) / 100f else 0f
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) {
        progress.animateTo(target, animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    val color by animateColorAsState(qualityColor(quality), animationSpec = tween(600), label = "gaugeColor")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.outlineVariant
    val glowPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    }

    Box(
        modifier = modifier
            .size(diameter)
            .semantics { contentDescription = "Ağ skoru ${if (known) score else "ölçülüyor"} / 100, ${quality.label}" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val glowRadius = stroke * 1.1f
            val inset = stroke / 2f + glowRadius
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val sweep = SWEEP_ANGLE * progress.value

            // Tick marks every 10 points, just inside the arc.
            val radiusOuter = arcSize.width / 2f - stroke * 0.9f
            val radiusInner = radiusOuter - stroke * 0.45f
            for (i in 0 until TICKS) {
                if (i % 3 != 0) continue
                val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * i / (TICKS - 1)).toDouble())
                val c = cos(angle).toFloat()
                val s = sin(angle).toFloat()
                drawLine(
                    color = tick,
                    start = Offset(center.x + c * radiusInner, center.y + s * radiusInner),
                    end = Offset(center.x + c * radiusOuter, center.y + s * radiusOuter),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            drawArc(
                color = track,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            if (sweep > 0.5f) {
                // Neon glow layer.
                glowPaint.strokeWidth = stroke
                glowPaint.color = color.copy(alpha = 0.55f).toArgb()
                glowPaint.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawArc(
                        RectF(topLeft.x, topLeft.y, topLeft.x + arcSize.width, topLeft.y + arcSize.height),
                        START_ANGLE,
                        sweep,
                        false,
                        glowPaint,
                    )
                }

                // Gradient arc: dim at the start, full colour at the head. Canvas is rotated so the
                // sweep gradient's 0° lines up with the arc start.
                val headFraction = (sweep / 360f).coerceIn(0.001f, 1f)
                rotate(START_ANGLE) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to lerp(color, track, 0.55f),
                            headFraction to color,
                            1f to color,
                            center = center,
                        ),
                        startAngle = 0f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }

                // Glowing head dot.
                val headAngle = Math.toRadians((START_ANGLE + sweep).toDouble())
                val r = arcSize.width / 2f
                val head = Offset(center.x + cos(headAngle).toFloat() * r, center.y + sin(headAngle).toFloat() * r)
                drawCircle(color.copy(alpha = 0.35f), radius = stroke * 0.95f, center = head)
                drawCircle(androidx.compose.ui.graphics.Color.White, radius = stroke * 0.28f, center = head)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (known) score.toString() else "—",
                style = MetricText.hero,
                color = color,
            )
            Text(
                text = quality.label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
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
