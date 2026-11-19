package com.cleansweep.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF375A9E),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF526080),
    secondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFF75536C)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E6C),
    primaryContainer = Color(0xFF1D477F),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBAC7E6),
    tertiary = Color(0xFFE2BAD6)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CleanSweepTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
}
