package com.victorious.mesh.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Charcoal = Color(0xFF121216)
val CharcoalSurface = Color(0xFF1C1C22)
val NeonCyan = Color(0xFF00F0FF)
val NeonMagenta = Color(0xFFFF00E5)
val SoftWhite = Color(0xFFE8E8F0)

private val NeonColors = darkColorScheme(
    primary = NeonCyan,
    secondary = NeonMagenta,
    background = Charcoal,
    surface = CharcoalSurface,
    onPrimary = Charcoal,
    onSecondary = Charcoal,
    onBackground = SoftWhite,
    onSurface = SoftWhite,
)

@Composable
fun VictoriousMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NeonColors, content = content)
}
