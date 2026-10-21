package com.camscaner.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF0E9F6E)
val BrandDark = Color(0xFF087A54)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6F1DE),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF3F6656),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9EADF),
    onSecondaryContainer = Color(0xFF0E2219),
    tertiary = Color(0xFF2F6A9E),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF181C1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF181C1B),
    surfaceVariant = Color(0xFFE6ECEA),
    onSurfaceVariant = Color(0xFF414946),
    surfaceContainer = Color(0xFFF0F4F2),
    surfaceContainerHigh = Color(0xFFE9EFEC),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD8A6),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF00513A),
    onPrimaryContainer = Color(0xFF7CF5C1),
    secondary = Color(0xFFA6D0BC),
    onSecondary = Color(0xFF0F3728),
    secondaryContainer = Color(0xFF274E3E),
    onSecondaryContainer = Color(0xFFC2ECD8),
    tertiary = Color(0xFF9CCBFF),
    background = Color(0xFF101413),
    onBackground = Color(0xFFDFE4E1),
    surface = Color(0xFF151A18),
    onSurface = Color(0xFFDFE4E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceContainer = Color(0xFF1C2120),
    surfaceContainerHigh = Color(0xFF262B2A),
    error = Color(0xFFFFB4AB),
)

@Composable
fun CamScanerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
