package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalTelemetryColors = staticCompositionLocalOf { DarkTelemetryColors }

private val ColorTokenHighestDark = androidx.compose.ui.graphics.Color(0xFF27344D)

private val ObsidianDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = OnElectricCyanContainer,
    secondary = TelemetryIndigo,
    onSecondary = OnTelemetryIndigo,
    secondaryContainer = TelemetryIndigoContainer,
    onSecondaryContainer = OnTelemetryIndigoContainer,
    tertiary = DarkTelemetryColors.seederGreen,
    onTertiary = OnElectricCyan,
    tertiaryContainer = DarkTelemetryColors.seederGreenContainer,
    onTertiaryContainer = DarkTelemetryColors.seederGreen,
    background = ObsidianBackground,
    onBackground = DarkTextPrimary,
    surface = ObsidianSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceVariant,
    surfaceContainerHigh = ObsidianSurfaceContainerHigh,
    surfaceContainerHighest = ColorTokenHighestDark,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineSubtle,
    error = DarkTelemetryColors.deadRose,
    onError = OnElectricCyan,
    errorContainer = DarkTelemetryColors.deadRoseContainer,
    onErrorContainer = DarkTelemetryColors.deadRose
)

private val AlabasterLightColorScheme = lightColorScheme(
    primary = DeepCyanPrimary,
    onPrimary = OnDeepCyanPrimary,
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = OnDeepCyanContainer,
    secondary = RoyalIndigoSecondary,
    onSecondary = OnRoyalIndigo,
    secondaryContainer = RoyalIndigoContainer,
    onSecondaryContainer = OnRoyalIndigoContainer,
    tertiary = LightTelemetryColors.seederGreen,
    onTertiary = OnDeepCyanPrimary,
    tertiaryContainer = LightTelemetryColors.seederGreenContainer,
    onTertiaryContainer = LightTelemetryColors.seederGreen,
    background = AlabasterBackground,
    onBackground = LightTextPrimary,
    surface = AlabasterSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = AlabasterSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainerLowest = AlabasterSurface,
    surfaceContainerLow = AlabasterBackground,
    surfaceContainer = AlabasterSurfaceVariant,
    surfaceContainerHigh = AlabasterSurfaceContainerHigh,
    outline = AlabasterOutline,
    outlineVariant = AlabasterSurfaceContainerHigh,
    error = LightTelemetryColors.deadRose,
    onError = OnDeepCyanPrimary,
    errorContainer = LightTelemetryColors.deadRoseContainer,
    onErrorContainer = LightTelemetryColors.deadRose
)

val MagnetVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MagnetVaultTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ObsidianDarkColorScheme else AlabasterLightColorScheme
    val telemetryColors = if (darkTheme) DarkTelemetryColors else LightTelemetryColors

    CompositionLocalProvider(LocalTelemetryColors provides telemetryColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MagnetVaultTypography,
            shapes = MagnetVaultShapes,
            content = content
        )
    }
}
