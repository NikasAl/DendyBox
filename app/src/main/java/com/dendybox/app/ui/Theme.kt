package com.dendybox.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AccentRed = Color(0xFFE53935)
val AccentBlue = Color(0xFF4A90D9)
val ControlBg = Color.White

private val DarkScheme = darkColorScheme(
    primary = AccentRed,
    onPrimary = Color.White,
    secondary = AccentBlue,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1A1A20),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF26262E),
    onSurfaceVariant = Color(0xFFC9C9D2)
)

@Composable
fun DendyBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
