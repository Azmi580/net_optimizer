package com.netpulse.optimizer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

object QualityColors {
    val Weak = Color(0xFFE5484D)
    val Fair = Color(0xFFF5A524)
    val Excellent = Color(0xFF30A46C)
}

@Composable
fun NetPulseTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // minSdk 31 → Material You dynamic colour is always available (One UI 6+ wallpaper palette).
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}
