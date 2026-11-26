package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.model.AppAppearanceMode

val LocalTelemetryColors = staticCompositionLocalOf { ObsidianTelemetryColors }

private val ObsidianColorScheme = darkColorScheme(
    primary = ObsidianVioletPrimary,
    onPrimary = ObsidianOnVioletPrimary,
    primaryContainer = ObsidianVioletContainer,
    onPrimaryContainer = ObsidianOnVioletContainer,
    secondary = ObsidianPeriwinkleSecondary,
    onSecondary = ObsidianOnPeriwinkle,
    secondaryContainer = ObsidianPeriwinkleContainer,
    onSecondaryContainer = ObsidianOnPeriwinkleContainer,
    tertiary = ObsidianIcyTertiary,
    onTertiary = ObsidianOnIcy,
    tertiaryContainer = ObsidianIcyContainer,
    onTertiaryContainer = ObsidianOnIcyContainer,
    background = ObsidianBackground,
    onBackground = ObsidianTextPrimary,
    surface = ObsidianSurface,
    onSurface = ObsidianTextPrimary,
    surfaceVariant = ObsidianSurfaceContainer,
    onSurfaceVariant = ObsidianTextSecondary,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = ObsidianSurfaceContainerLow,
    surfaceContainer = ObsidianSurfaceContainer,
    surfaceContainerHigh = ObsidianSurfaceContainerHigh,
    surfaceContainerHighest = ObsidianSurfaceHighest,
    outline = ObsidianDivider,
    outlineVariant = ObsidianBorderSubtle,
    error = ObsidianTelemetryColors.deadRose,
    onError = ObsidianOnVioletPrimary,
    errorContainer = ObsidianTelemetryColors.deadRoseContainer,
    onErrorContainer = ObsidianTelemetryColors.deadRose
)

private val AlabasterColorScheme = lightColorScheme(
    primary = AlabasterVioletPrimary,
    onPrimary = AlabasterOnVioletPrimary,
    primaryContainer = AlabasterVioletContainer,
    onPrimaryContainer = AlabasterOnVioletContainer,
    secondary = AlabasterPeriwinkleSecondary,
    onSecondary = AlabasterOnPeriwinkle,
    secondaryContainer = AlabasterPeriwinkleContainer,
    onSecondaryContainer = AlabasterOnPeriwinkleContainer,
    tertiary = AlabasterIcyTertiary,
    onTertiary = AlabasterOnIcy,
    tertiaryContainer = AlabasterIcyContainer,
    onTertiaryContainer = AlabasterOnIcyContainer,
    background = AlabasterBackground,
    onBackground = AlabasterTextPrimary,
    surface = AlabasterSurface,
    onSurface = AlabasterTextPrimary,
    surfaceVariant = AlabasterSurfaceContainer,
    onSurfaceVariant = AlabasterTextSecondary,
    surfaceContainerLowest = AlabasterSurface,
    surfaceContainerLow = AlabasterSurfaceContainerLow,
    surfaceContainer = AlabasterSurfaceContainer,
    surfaceContainerHigh = AlabasterSurfaceContainerHigh,
    surfaceContainerHighest = AlabasterSurfaceHighest,
    outline = AlabasterOutline,
    outlineVariant = AlabasterOutlineSubtle,
    error = AlabasterTelemetryColors.deadRose,
    onError = AlabasterOnVioletPrimary,
    errorContainer = AlabasterTelemetryColors.deadRoseContainer,
    onErrorContainer = AlabasterTelemetryColors.deadRose
)

val MagnetVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MagnetVaultTheme(
    appearanceMode: AppAppearanceMode = AppAppearanceMode.OBSIDIAN,
    content: @Composable () -> Unit
) {
    val isDark = when (appearanceMode) {
        AppAppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppAppearanceMode.OBSIDIAN -> true
        AppAppearanceMode.ALABASTER -> false
    }
    MagnetVaultTheme(darkTheme = isDark, content = content)
}

@Composable
fun MagnetVaultTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ObsidianColorScheme else AlabasterColorScheme
    val telemetryColors = if (darkTheme) ObsidianTelemetryColors else AlabasterTelemetryColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalTelemetryColors provides telemetryColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MagnetVaultTypography,
            shapes = MagnetVaultShapes,
            content = content
        )
    }
}
