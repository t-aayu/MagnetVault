package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// --- Obsidian Telemetry Dark Palette ---
val ObsidianBackground = Color(0xFF0B0F17)
val ObsidianSurface = Color(0xFF111724)
val ObsidianSurfaceVariant = Color(0xFF172030)
val ObsidianSurfaceContainerHigh = Color(0xFF1F2A3F)
val ObsidianOutline = Color(0xFF2B3852)
val ObsidianOutlineSubtle = Color(0xFF1E293B)

val ElectricCyan = Color(0xFF22D3EE)
val ElectricCyanDark = Color(0xFF06B6D4)
val OnElectricCyan = Color(0xFF04222A)
val ElectricCyanContainer = Color(0xFF0E3A4A)
val OnElectricCyanContainer = Color(0xFFA5F3FC)

val TelemetryIndigo = Color(0xFF818CF8)
val OnTelemetryIndigo = Color(0xFF161B4D)
val TelemetryIndigoContainer = Color(0xFF272E61)
val OnTelemetryIndigoContainer = Color(0xFFE0E7FF)

val DarkTextPrimary = Color(0xFFF1F5F9)
val DarkTextSecondary = Color(0xFF94A3B8)

// --- Crisp Alabaster Light Palette ---
val AlabasterBackground = Color(0xFFF6F8FB)
val AlabasterSurface = Color(0xFFFFFFFF)
val AlabasterSurfaceVariant = Color(0xFFEEF2F7)
val AlabasterSurfaceContainerHigh = Color(0xFFE2E8F0)
val AlabasterOutline = Color(0xFFCBD5E1)

val DeepCyanPrimary = Color(0xFF0891B2)
val OnDeepCyanPrimary = Color(0xFFFFFFFF)
val DeepCyanContainer = Color(0xFFCFFAFE)
val OnDeepCyanContainer = Color(0xFF164E63)

val RoyalIndigoSecondary = Color(0xFF4F46E5)
val OnRoyalIndigo = Color(0xFFFFFFFF)
val RoyalIndigoContainer = Color(0xFFE0E7FF)
val OnRoyalIndigoContainer = Color(0xFF312E81)

val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF475569)

// --- Semantic Swarm & Ping Telemetry Colors ---
@Immutable
data class TelemetryColors(
    val seederGreen: Color,
    val seederGreenContainer: Color,
    val leecherAmber: Color,
    val leecherAmberContainer: Color,
    val deadRose: Color,
    val deadRoseContainer: Color,
    val qualityPurple: Color,
    val qualityPurpleContainer: Color,
    val cardBorder: Color,
    val headerGlow: Color
)

val DarkTelemetryColors = TelemetryColors(
    seederGreen = Color(0xFF34D399),
    seederGreenContainer = Color(0xFF0A3327),
    leecherAmber = Color(0xFFFBBF24),
    leecherAmberContainer = Color(0xFF3B2A0A),
    deadRose = Color(0xFFFB7185),
    deadRoseContainer = Color(0xFF3E1420),
    qualityPurple = Color(0xFFC084FC),
    qualityPurpleContainer = Color(0xFF2D1B4E),
    cardBorder = Color(0xFF243147),
    headerGlow = Color(0x3322D3EE)
)

val LightTelemetryColors = TelemetryColors(
    seederGreen = Color(0xFF059669),
    seederGreenContainer = Color(0xFFD1FAE5),
    leecherAmber = Color(0xFFD97706),
    leecherAmberContainer = Color(0xFFFEF3C7),
    deadRose = Color(0xFFE11D48),
    deadRoseContainer = Color(0xFFFFE4E6),
    qualityPurple = Color(0xFF7C3AED),
    qualityPurpleContainer = Color(0xFFEDE9FE),
    cardBorder = Color(0xFFE2E8F0),
    headerGlow = Color(0x1F0891B2)
)
