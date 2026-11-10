package com.netpulse.optimizer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.netpulse.optimizer.ui.theme.LocalGlass

private val GlassShape = RoundedCornerShape(24.dp)

/**
 * Frosted-glass card: translucent fill over the ambient background, hairline border and a top
 * highlight gradient. Real backdrop blur is not available to app windows, so depth comes from
 * translucency + layered tonal surfaces (cheap: no offscreen buffers, no RenderEffect).
 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalGlass.current
    Column(
        modifier = modifier
            .clip(GlassShape)
            .background(glass.fill)
            .background(Brush.verticalGradient(listOf(glass.highlight, Color.Transparent)))
            .border(1.dp, glass.border, GlassShape),
        content = content,
    )
}

/** Soft colour glows behind the content; drawn once per frame size, no animation, so zero ongoing cost. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val glass = LocalGlass.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glass.ambientPrimary, Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.12f),
                        radius = size.maxDimension * 0.55f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glass.ambientSecondary, Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.55f),
                        radius = size.maxDimension * 0.5f,
                    ),
                )
            },
        content = content,
    )
}
