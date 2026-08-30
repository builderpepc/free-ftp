package com.freeftp.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF00695C)
private val TealLight = Color(0xFF4DB6AC)
private val Sand = Color(0xFFF5F3EF)

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = TealLight,
    background = Sand,
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    secondary = Teal,
)

@Composable
fun FreeFtpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
