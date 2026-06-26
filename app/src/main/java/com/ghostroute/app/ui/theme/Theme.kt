package com.ghostroute.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GhostBackground = Color(0xFF0E1116)
private val GhostSurface = Color(0xFF161B22)
private val GhostAccent = Color(0xFF3DDC97)
private val GhostOnDark = Color(0xFFE6EDF3)

private val GhostDarkColors = darkColorScheme(
    primary = GhostAccent,
    onPrimary = Color(0xFF062014),
    background = GhostBackground,
    onBackground = GhostOnDark,
    surface = GhostSurface,
    onSurface = GhostOnDark,
    surfaceVariant = Color(0xFF1F2630),
    onSurfaceVariant = Color(0xFFB6C2CF),
)

private val GhostLightColors = lightColorScheme(
    primary = Color(0xFF12865A),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun GhostRouteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // GhostRoute is dark-first to match the night-driving map; light is a fallback.
    val colors = if (darkTheme) GhostDarkColors else GhostLightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}
