package com.propaint.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4A90D9),
    onPrimary = Color.White,
    secondary = Color(0xFF6AB0FF),
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    background = Color(0xFF1A1A1A),
    onBackground = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    outline = Color(0xFF444444),
)

@Composable
fun ProPaintTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
