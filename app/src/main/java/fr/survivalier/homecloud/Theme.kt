package fr.survivalier.homecloud

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Même palette que le client de bureau : bleu nuit + accent bleu électrique (sombre) / gris clair (clair).
private val DarkColors = darkColorScheme(
    primary = Color(0xFF1A8CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF17355E),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF3D9FFF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1A3A66),
    onSecondaryContainer = Color.White,
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE8ECF3),
    surface = Color(0xFF141A25),
    onSurface = Color(0xFFE8ECF3),
    surfaceVariant = Color(0xFF1B2332),
    onSurfaceVariant = Color(0xFF8B97AB),
    surfaceTint = Color(0xFF1A8CFF),
    surfaceContainerLowest = Color(0xFF0E1117),
    surfaceContainerLow = Color(0xFF111722),
    surfaceContainer = Color(0xFF141A25),
    surfaceContainerHigh = Color(0xFF1B2332),
    surfaceContainerHighest = Color(0xFF222C3F),
    outline = Color(0xFF36445C),
    outlineVariant = Color(0xFF222B3B),
    error = Color(0xFFFF6B79),
    onError = Color.White,
    errorContainer = Color(0xFF2A1A21),
    onErrorContainer = Color(0xFFFF6B79),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A7DF0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E5FF),
    onPrimaryContainer = Color(0xFF0B2A55),
    secondary = Color(0xFF0B6BD6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBE9FF),
    onSecondaryContainer = Color(0xFF0B2A55),
    background = Color(0xFFF3F5F9),
    onBackground = Color(0xFF1B2330),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2330),
    surfaceVariant = Color(0xFFF1F4F9),
    onSurfaceVariant = Color(0xFF5F6B7D),
    surfaceTint = Color(0xFF1A7DF0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F8FB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF3FA),
    surfaceContainerHighest = Color(0xFFE6ECF5),
    outline = Color(0xFFB4C1D4),
    outlineVariant = Color(0xFFDDE3EC),
    error = Color(0xFFD92D3F),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEE),
    onErrorContainer = Color(0xFFD92D3F),
)

@Composable
fun HomeCloudTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
