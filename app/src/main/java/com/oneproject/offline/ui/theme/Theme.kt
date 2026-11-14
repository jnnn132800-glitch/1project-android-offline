package com.oneproject.offline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val OneProjectLightColorScheme = lightColorScheme(
    primary = NavyPrimary,
    onPrimary = OnNavyPrimary,
    primaryContainer = NavyContainer,
    onPrimaryContainer = OnNavyContainer,
    secondary = TealSecondary,
    onSecondary = OnTealSecondary,
    secondaryContainer = TealContainer,
    onSecondaryContainer = OnTealContainer,
    background = LightBackground,
    onBackground = InkPrimary,
    surface = LightSurface,
    onSurface = InkPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = InkSecondary,
    outline = BorderSubtle,
    error = ErrorRedText,
    errorContainer = ErrorRedBg,
    onErrorContainer = ErrorRedText
)

@Composable
fun OneProjectTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = OneProjectLightColorScheme,
        typography = OneProjectTypography,
        content = content
    )
}
