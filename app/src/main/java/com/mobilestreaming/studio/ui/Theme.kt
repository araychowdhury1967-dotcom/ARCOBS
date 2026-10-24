package com.mobilestreaming.studio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.mobilestreaming.studio.StudioViewModel

val StudioTeal = Color(0xFF2DD4BF)
val StudioOrange = Color(0xFFF97316)
val StudioBackground = Color(0xFF0B0F14)
val StudioSurface = Color(0xFF121820)
val StudioSurfaceVariant = Color(0xFF1B2430)
val StudioMuted = Color(0xFF94A3B8)

private val DarkColors = darkColorScheme(
    primary = StudioTeal,
    onPrimary = Color(0xFF062521),
    secondary = StudioOrange,
    background = StudioBackground,
    onBackground = Color(0xFFE6EDF3),
    surface = StudioSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = StudioMuted,
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    secondary = Color(0xFFC2410C),
    background = Color(0xFFF5F7FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7EDF3),
)

@Composable
fun StudioTheme(viewModel: StudioViewModel, content: @Composable () -> Unit) {
    val settings by viewModel.appSettings.collectAsState()
    val dark = if (settings.followSystemTheme) isSystemInDarkTheme() else settings.darkTheme
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
