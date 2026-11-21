package com.biandan.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A7A52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0D6),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4C6358),
    secondaryContainer = Color(0xFFCEE9DA),
    tertiary = Color(0xFF3F6375),
    tertiaryContainer = Color(0xFFC2E8FD),
    error = Color(0xFFBA1A1A),
    surface = Color(0xFFF6FBF6),
    surfaceVariant = Color(0xFFDCE5DD),
    background = Color(0xFFF6FBF6),
    outline = Color(0xFF707970),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F0),
    surfaceContainer = Color(0xFFEAEFE9),
    surfaceContainerHigh = Color(0xFFE4EAE3),
    surfaceContainerHighest = Color(0xFFDFE4DE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BDAAE),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFFB7F0D6),
    secondary = Color(0xFFB3CCBF),
    secondaryContainer = Color(0xFF354B41),
    tertiary = Color(0xFFA6CCDF),
    tertiaryContainer = Color(0xFF274B5C),
    error = Color(0xFFFFB4AB),
    surface = Color(0xFF101511),
    background = Color(0xFF101511),
    outline = Color(0xFF8A9389),
)

@Composable
fun BiandanTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
