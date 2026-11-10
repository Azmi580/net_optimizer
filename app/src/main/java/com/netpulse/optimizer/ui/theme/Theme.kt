package com.netpulse.optimizer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.netpulse.optimizer.domain.model.SignalQuality

/** Semantic status colours, deliberately fixed (not wallpaper-derived) so green/amber/red stay readable. */
object QualityColors {
    val Weak = Color(0xFFE5484D)
    val Fair = Color(0xFFF5A524)
    val Excellent = Color(0xFF30A46C)
}

@Composable
fun qualityColor(quality: SignalQuality): Color = when (quality) {
    SignalQuality.EXCELLENT -> QualityColors.Excellent
    SignalQuality.FAIR -> QualityColors.Fair
    SignalQuality.WEAK -> QualityColors.Weak
    SignalQuality.UNKNOWN -> MaterialTheme.colorScheme.outline
}

@Composable
fun NetPulseTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // minSdk 31 → Material You dynamic colour is always available, on every OEM.
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}
