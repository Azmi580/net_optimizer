package com.netpulse.optimizer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.netpulse.optimizer.domain.model.SignalQuality

/** Semantic status colours, deliberately fixed (not wallpaper-derived) so green/amber/red stay readable. */
object QualityColors {
    val Weak = Color(0xFFFF4D5E)
    val Fair = Color(0xFFFFB020)
    val Excellent = Color(0xFF2BE38B)
}

/** Glass surface tokens; differ between light and dark so translucency reads well on both. */
@Immutable
data class GlassTokens(
    val fill: Color,
    val border: Color,
    val highlight: Color,
    val ambientPrimary: Color,
    val ambientSecondary: Color,
    val isDark: Boolean,
)

val LocalGlass = staticCompositionLocalOf {
    GlassTokens(Color.White, Color.Black, Color.White, Color.Transparent, Color.Transparent, false)
}

@Composable
fun qualityColor(quality: SignalQuality): Color = when (quality) {
    SignalQuality.EXCELLENT -> QualityColors.Excellent
    SignalQuality.FAIR -> QualityColors.Fair
    SignalQuality.WEAK -> QualityColors.Weak
    SignalQuality.UNKNOWN -> MaterialTheme.colorScheme.outline
}

/**
 * Dark mode: true-black base (#000000, ideal for OLED) with deep navy/graphite elevation layers.
 * Accent roles (primary/secondary/tertiary) stay wallpaper-derived via Material You.
 */
private fun ColorScheme.deepDark(): ColorScheme = copy(
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE6EAF2),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF1C2333),
    surfaceContainerLowest = Color(0xFF020306),
    surfaceContainerLow = Color(0xFF070A12),
    surfaceContainer = Color(0xFF0B101B),
    surfaceContainerHigh = Color(0xFF111827),
    surfaceContainerHighest = Color(0xFF182133),
    surfaceVariant = Color(0xFF151C2B),
    onSurfaceVariant = Color(0xFF9AA4B8),
    outline = Color(0xFF4A5468),
    outlineVariant = Color(0xFF232C3D),
)

@Composable
fun NetPulseTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // minSdk 31 → Material You dynamic colour is always available, on every OEM.
    val colors = if (dark) dynamicDarkColorScheme(context).deepDark() else dynamicLightColorScheme(context)

    val glass = if (dark) {
        GlassTokens(
            fill = Color(0xFF0E1524).copy(alpha = 0.62f),
            border = Color.White.copy(alpha = 0.08f),
            highlight = Color.White.copy(alpha = 0.05f),
            ambientPrimary = colors.primary.copy(alpha = 0.22f),
            ambientSecondary = colors.tertiary.copy(alpha = 0.16f),
            isDark = true,
        )
    } else {
        GlassTokens(
            fill = colors.surfaceContainerLowest.copy(alpha = 0.78f),
            border = colors.outlineVariant.copy(alpha = 0.6f),
            highlight = Color.White.copy(alpha = 0.6f),
            ambientPrimary = colors.primary.copy(alpha = 0.14f),
            ambientSecondary = colors.tertiary.copy(alpha = 0.10f),
            isDark = false,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(colorScheme = colors, typography = NetPulseTypography, content = content)
    }
}
