package com.mundymorning.touchreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

private val Teal = Color(0xFF1F4E5A)
private val Amber = Color(0xFFF2B84B)

private val Light = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE6EA), onPrimaryContainer = Color(0xFF0B2A31),
    secondary = Color(0xFF8A5A00), tertiary = Amber,
    background = Color(0xFFFBF7F0), onBackground = Color(0xFF1C1B17),
    surface = Color(0xFFFBF7F0), onSurface = Color(0xFF1C1B17),
    surfaceVariant = Color(0xFFEFE8DA), onSurfaceVariant = Color(0xFF4A473E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FCBD6), onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF244C56), onPrimaryContainer = Color(0xFFCFE6EA),
    secondary = Color(0xFFF2B84B), tertiary = Amber,
    background = Color(0xFF12171A), onBackground = Color(0xFFE6E2D9),
    surface = Color(0xFF12171A), onSurface = Color(0xFFE6E2D9),
    surfaceVariant = Color(0xFF232A2E), onSurfaceVariant = Color(0xFFC6C2B8),
)

val ReaderFont = FontFamily.Serif

@Composable
fun TouchReaderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

@Composable
fun highlightColor(): Color =
    if (MaterialTheme.colorScheme.background.red < 0.5f) Color(0xFF3F5B33) else Color(0xFFFFE08A)
