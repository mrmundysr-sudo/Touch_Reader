package com.mundymorning.touchreader.ui.theme

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mundymorning.touchreader.R

/*
 * "The study": walnut wood, oxblood leather, brass and parchment, taken from the splash artwork.
 * Parchment is the light theme, Study the dark one, and High contrast a plain black and white one for readability.
 * Text and background pairs were chosen to stay above 4.5:1 (most are well over 7:1).
 */

private val Parchment = lightColorScheme(
    primary = Color(0xFF6B3A1F), onPrimary = Color(0xFFFFF8E7),
    primaryContainer = Color(0xFFEACB93), onPrimaryContainer = Color(0xFF3A2312),
    secondary = Color(0xFF7A2E26), onSecondary = Color(0xFFFFF0ED),
    secondaryContainer = Color(0xFFE6D2A8), onSecondaryContainer = Color(0xFF3A2312),
    tertiary = Color(0xFF1F6FB5), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFE4F7), onTertiaryContainer = Color(0xFF0A2A47),
    background = Color(0xFFF6ECD8), onBackground = Color(0xFF2A1B10),
    surface = Color(0xFFF6ECD8), onSurface = Color(0xFF2A1B10),
    surfaceVariant = Color(0xFFE7D7B7), onSurfaceVariant = Color(0xFF5A4633),
    surfaceDim = Color(0xFFE4D6B9), surfaceBright = Color(0xFFFBF4E4),
    surfaceContainerLowest = Color(0xFFFBF4E4), surfaceContainerLow = Color(0xFFF2E6CF),
    surfaceContainer = Color(0xFFEDDFC4), surfaceContainerHigh = Color(0xFFE7D7B7), surfaceContainerHighest = Color(0xFFE0CFAB),
    outline = Color(0xFF8A7357), outlineVariant = Color(0xFFCDBB96),
    inverseSurface = Color(0xFF3A2616), inverseOnSurface = Color(0xFFF6ECD8), inversePrimary = Color(0xFFD9A95B),
)

private val Study = darkColorScheme(
    primary = Color(0xFFD9A95B), onPrimary = Color(0xFF2B1A10),
    primaryContainer = Color(0xFF5A3A1F), onPrimaryContainer = Color(0xFFF6E3BC),
    secondary = Color(0xFFD9958A), onSecondary = Color(0xFF3A120D),
    secondaryContainer = Color(0xFF5C2B25), onSecondaryContainer = Color(0xFFF7D9D4),
    tertiary = Color(0xFF7DB8EA), onTertiary = Color(0xFF00263F),
    tertiaryContainer = Color(0xFF1D4C74), onTertiaryContainer = Color(0xFFD2E8FA),
    background = Color(0xFF1C120B), onBackground = Color(0xFFF1E6D0),
    surface = Color(0xFF1C120B), onSurface = Color(0xFFF1E6D0),
    surfaceVariant = Color(0xFF3F2C20), onSurfaceVariant = Color(0xFFD2C3A6),
    surfaceDim = Color(0xFF150D07), surfaceBright = Color(0xFF3A281A),
    surfaceContainerLowest = Color(0xFF150D07), surfaceContainerLow = Color(0xFF231710),
    surfaceContainer = Color(0xFF2A1B12), surfaceContainerHigh = Color(0xFF34231A), surfaceContainerHighest = Color(0xFF3F2C20),
    outline = Color(0xFF8C7556), outlineVariant = Color(0xFF4A3829),
    inverseSurface = Color(0xFFF1E6D0), inverseOnSurface = Color(0xFF2A1B12), inversePrimary = Color(0xFF7A4F22),
)

private val HighContrast = darkColorScheme(
    primary = Color(0xFFFFD27A), onPrimary = Color.Black,
    primaryContainer = Color(0xFF4D3A00), onPrimaryContainer = Color(0xFFFFE8B0),
    secondary = Color(0xFFFFB4A8), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF5C1A12), onSecondaryContainer = Color(0xFFFFDAD4),
    tertiary = Color(0xFF8CC8FF), onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF00406E), onTertiaryContainer = Color(0xFFD6EBFF),
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFE6E6E6),
    surfaceDim = Color.Black, surfaceBright = Color(0xFF2E2E2E),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414), surfaceContainerHigh = Color(0xFF1C1C1C), surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFFBDBDBD), outlineVariant = Color(0xFF6B6B6B),
    inverseSurface = Color.White, inverseOnSurface = Color.Black, inversePrimary = Color(0xFF6B3A1F),
)

/**
 * Playfair Display for headings and Lora for the book text, both SIL Open Font Licence and embedded unmodified (the weights are
 * chosen at run time; on Android 7 the plain weight of the same face is used). Licence texts: docs/licenses.
 */
@OptIn(ExperimentalTextApi::class)
val DisplayFont = FontFamily(
    Font(R.font.playfair_display, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.playfair_display, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.playfair_display, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@OptIn(ExperimentalTextApi::class)
val ReaderFont = FontFamily(
    Font(R.font.lora, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.lora, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.lora, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** Headings are set in a serif, like the book spines in the artwork; body text and buttons stay in the plain system face for legibility. */
private val StudyTypography = Typography().let { t ->
    fun androidx.compose.ui.text.TextStyle.serif(w: FontWeight = FontWeight.SemiBold) = copy(fontFamily = DisplayFont, fontWeight = w)
    t.copy(
        displayLarge = t.displayLarge.serif(), displayMedium = t.displayMedium.serif(), displaySmall = t.displaySmall.serif(),
        headlineLarge = t.headlineLarge.serif(), headlineMedium = t.headlineMedium.serif(), headlineSmall = t.headlineSmall.serif(),
        titleLarge = t.titleLarge.serif(), titleMedium = t.titleMedium.serif(), titleSmall = t.titleSmall.serif(FontWeight.Medium),
    )
}

private val StudyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(26.dp),
)

/** [dark] picks Study over Parchment; [contrast] overrides both with the black and white scheme. */
@Composable
fun TouchReaderTheme(dark: Boolean, contrast: Boolean = false, content: @Composable () -> Unit) {
    // With a background picture the screens must not paint over it: the real colour and the picture are drawn once, behind everything.
    val context = androidx.compose.ui.platform.LocalContext.current
    val textured = androidx.compose.runtime.remember(dark, contrast) {
        !contrast && com.mundymorning.touchreader.ui.art.Art.resId(context, if (dark) "global_bg_dark" else "global_bg_light") != 0
    }
    val scheme = when { contrast -> HighContrast; dark -> Study; else -> Parchment }
    MaterialTheme(
        colorScheme = if (textured) scheme.copy(background = Color.Transparent) else scheme,
        typography = StudyTypography, shapes = StudyShapes, content = content,
    )
}

/**
 * For text and icons that sit on the artwork's dark leather panels: always the Study colours (cream and brass), whichever theme is on,
 * so they stay readable on the leather.
 */
@Composable
fun OnLeather(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Study, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalContentColor provides Study.onSurface) { content() }
    }
}

/** The sentence being read: warm gold on parchment, deep gold on the dark themes. */
@Composable
fun highlightColor(): Color =
    if (MaterialTheme.colorScheme.onBackground.luminance() > 0.5f) Color(0xFF5A4520) else Color(0xFFF2D27A)
