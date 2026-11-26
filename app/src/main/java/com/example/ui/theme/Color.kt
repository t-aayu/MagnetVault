package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// --- Obsidian Dark Palette (Opaque content surfaces + violet primary + subtle chrome translucency) ---
val ObsidianBackground = Color(0xFF0B0D17)
val ObsidianSurface = Color(0xFF121626)
val ObsidianSurfaceContainerLow = Color(0xFF15192B)
val ObsidianSurfaceContainer = Color(0xFF191F33)
val ObsidianSurfaceContainerHigh = Color(0xFF212840)
val ObsidianSurfaceHighest = Color(0xFF29314D)
val ObsidianDivider = Color(0xFF2F374F)
val ObsidianBorderSubtle = Color(0xFF232A40)

val ObsidianVioletPrimary = Color(0xFF9E8BFF)
val ObsidianOnVioletPrimary = Color(0xFF140D33)
val ObsidianVioletContainer = Color(0xFF2C2259)
val ObsidianOnVioletContainer = Color(0xFFEBE5FF)

val ObsidianPeriwinkleSecondary = Color(0xFFA9B9FF)
val ObsidianOnPeriwinkle = Color(0xFF111A3D)
val ObsidianPeriwinkleContainer = Color(0xFF222D54)
val ObsidianOnPeriwinkleContainer = Color(0xFFE1E8FF)

val ObsidianIcyTertiary = Color(0xFF8DD9F5)
val ObsidianOnIcy = Color(0xFF082432)
val ObsidianIcyContainer = Color(0xFF163245)
val ObsidianOnIcyContainer = Color(0xFFD2F0FC)

val ObsidianTextPrimary = Color(0xFFF2F3FA)
val ObsidianTextSecondary = Color(0xFFA2A9BE)
val ObsidianTextDisabled = Color(0xFF66708A)

// --- Alabaster Light Palette (Opaque content surfaces + deep violet primary + subtle chrome translucency) ---
val AlabasterBackground = Color(0xFFF5F6FB)
val AlabasterSurface = Color(0xFFFFFFFF)
val AlabasterSurfaceContainerLow = Color(0xFFFFFFFF)
val AlabasterSurfaceContainer = Color(0xFFEEF1F8)
val AlabasterSurfaceContainerHigh = Color(0xFFE4E8F5)
val AlabasterSurfaceHighest = Color(0xFFD8DEF0)
val AlabasterOutline = Color(0xFFC5CEE3)
val AlabasterOutlineSubtle = Color(0xFFDCE2F0)

val AlabasterVioletPrimary = Color(0xFF5D3FD9)
val AlabasterOnVioletPrimary = Color(0xFFFFFFFF)
val AlabasterVioletContainer = Color(0xFFEBE5FF)
val AlabasterOnVioletContainer = Color(0xFF231166)

val AlabasterPeriwinkleSecondary = Color(0xFF4458CC)
val AlabasterOnPeriwinkle = Color(0xFFFFFFFF)
val AlabasterPeriwinkleContainer = Color(0xFFE1E6FF)
val AlabasterOnPeriwinkleContainer = Color(0xFF152061)

val AlabasterIcyTertiary = Color(0xFF16789C)
val AlabasterOnIcy = Color(0xFFFFFFFF)
val AlabasterIcyContainer = Color(0xFFD6F0FA)
val AlabasterOnIcyContainer = Color(0xFF0C384A)

val AlabasterTextPrimary = Color(0xFF111526)
val AlabasterTextSecondary = Color(0xFF4F5870)
val AlabasterTextDisabled = Color(0xFF8C94AA)

// --- Semantic Telemetry & Surface Tokens ---
@Immutable
data class TelemetryColors(
    val seederGreen: Color,
    val seederGreenContainer: Color,
    val leecherAmber: Color,
    val leecherAmberContainer: Color,
    val deadRose: Color,
    val deadRoseContainer: Color,
    val unknownNeutral: Color,
    val unknownNeutralContainer: Color,
    val qualityPurple: Color,
    val qualityPurpleContainer: Color,
    val cardBorder: Color,
    val glassSurface: Color,
    val glassSurfaceElevated: Color,
    val chromeTranslucent: Color,
    val chromeBorder: Color,
    val glassBorderHighlight: Color,
    val glassBorderSubtle: Color,
    val disabledText: Color,
    val headerGlow: Color,
    val headerGlowSecondary: Color
)

val ObsidianTelemetryColors = TelemetryColors(
    seederGreen = Color(0xFF6EE7B7),
    seederGreenContainer = Color(0xFF132D27),
    leecherAmber = Color(0xFFF2C679),
    leecherAmberContainer = Color(0xFF302615),
    deadRose = Color(0xFFF28B9C),
    deadRoseContainer = Color(0xFF381A24),
    unknownNeutral = Color(0xFF9EA6BC),
    unknownNeutralContainer = Color(0xFF20263A),
    qualityPurple = Color(0xFFB9A2FF),
    qualityPurpleContainer = Color(0xFF282147),
    cardBorder = Color(0xFF2E364E),
    // Content surfaces are intentionally opaque for crisp contrast & performance
    glassSurface = ObsidianSurfaceContainerLow,
    glassSurfaceElevated = ObsidianSurfaceContainer,
    // Subtle translucency reserved strictly for top search header & navigation bar/rail
    chromeTranslucent = Color(0xE0121626),
    chromeBorder = Color(0xFF2B3249),
    glassBorderHighlight = Color(0x3D9E8BFF),
    glassBorderSubtle = Color(0x2E364E80),
    disabledText = ObsidianTextDisabled,
    headerGlow = Color(0x1C9E8BFF),
    headerGlowSecondary = Color(0x168DD9F5)
)

val AlabasterTelemetryColors = TelemetryColors(
    seederGreen = Color(0xFF177A58),
    seederGreenContainer = Color(0xFFDDF5EB),
    leecherAmber = Color(0xFFAD6D12),
    leecherAmberContainer = Color(0xFFFCF0D7),
    deadRose = Color(0xFFC43D54),
    deadRoseContainer = Color(0xFFFDE4E9),
    unknownNeutral = Color(0xFF56607A),
    unknownNeutralContainer = Color(0xFFE7ECF6),
    qualityPurple = Color(0xFF5D3FD9),
    qualityPurpleContainer = Color(0xFFEBE5FF),
    cardBorder = Color(0xFFD3DAEC),
    glassSurface = AlabasterSurface,
    glassSurfaceElevated = AlabasterSurfaceContainer,
    chromeTranslucent = Color(0xEBFFFFFF),
    chromeBorder = Color(0xFFD3DAEC),
    glassBorderHighlight = Color(0x405D3FD9),
    glassBorderSubtle = Color(0x80C5CEE3),
    disabledText = AlabasterTextDisabled,
    headerGlow = Color(0x145D3FD9),
    headerGlowSecondary = Color(0x1216789C)
)
