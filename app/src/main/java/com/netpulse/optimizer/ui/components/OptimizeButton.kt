package com.netpulse.optimizer.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val PULSE_EXPAND_DP = 22f

/**
 * Pill-shaped hero action with a primary→tertiary gradient and coloured glow.
 *
 * - Tap: haptic + a one-shot pulse wave expanding from the pill outline.
 * - Running: button fades to a disabled state, a progress ring shows, and soft pulse rings repeat
 *   (infinite transition lives only while running and on screen).
 */
@Composable
fun OptimizeButton(
    running: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val tapPulse = remember { Animatable(1f) }
    val start = MaterialTheme.colorScheme.primary
    val end = MaterialTheme.colorScheme.tertiary
    val onColor = MaterialTheme.colorScheme.onPrimary

    val contentAlpha by animateFloatAsState(if (running) 0.72f else 1f, tween(300), label = "optAlpha")
    val glow by animateColorAsState(if (running) start.copy(alpha = 0.25f) else start.copy(alpha = 0.6f), tween(300), label = "optGlow")

    val loopPulse = if (running) {
        val transition = rememberInfiniteTransition(label = "optPulse")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "optPulseValue",
        ).value
    } else {
        null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .drawBehind {
                fun ring(progress: Float) {
                    val grow = PULSE_EXPAND_DP.dp.toPx() * progress
                    val alpha = (1f - progress) * 0.55f
                    if (alpha <= 0f) return
                    drawRoundRect(
                        color = start.copy(alpha = alpha),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(size.height / 2f + grow),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                if (tapPulse.value < 1f) ring(tapPulse.value)
                loopPulse?.let { ring(it) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(elevation = 18.dp, shape = CircleShape, ambientColor = glow, spotColor = glow)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(start, end)))
                .clickable(
                    enabled = !running,
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        tapPulse.snapTo(0f)
                        tapPulse.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
                    }
                    onClick()
                }
                .alpha(contentAlpha),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = running,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "optContent",
            ) { isRunning ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = onColor,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Optimize ediliyor…", color = onColor, style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(
                            "Ağı Optimize Et",
                            color = onColor,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
