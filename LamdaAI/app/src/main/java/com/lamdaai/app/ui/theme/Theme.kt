package com.lamdaai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = LamdaPrimary,
    secondary = LamdaSecondary,
    background = LamdaBackground,
    surface = LamdaSurface,
    onPrimary = LamdaOnPrimary
)

private val LightColors = lightColorScheme(
    primary = LamdaPrimary,
    secondary = LamdaSecondary,
    onPrimary = LamdaOnPrimary
)

@Composable
fun LamdaAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        content = content
    )
}
