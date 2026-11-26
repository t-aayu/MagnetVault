package com.example.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Restrained ambient highlight wash behind search header chrome.
 */
fun Modifier.ambientGlassBackdrop(telemetry: TelemetryColors): Modifier = this.drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(telemetry.headerGlow, Color.Transparent),
            center = Offset(size.width * 0.14f, size.height * 0.02f),
            radius = size.width * 0.72f
        )
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(telemetry.headerGlowSecondary, Color.Transparent),
            center = Offset(size.width * 0.90f, size.height * 0.06f),
            radius = size.width * 0.64f
        )
    )
}

@Composable
fun rememberGlassBorder(
    highlight: Color = LocalTelemetryColors.current.glassBorderHighlight,
    subtle: Color = LocalTelemetryColors.current.cardBorder,
    width: Dp = 1.dp
): BorderStroke = remember(highlight, subtle, width) {
    BorderStroke(
        width = width,
        brush = Brush.linearGradient(
            colors = listOf(highlight, subtle)
        )
    )
}

/**
 * Opaque Material 3 content surface card (or subtle translucent chrome when [translucent] = true).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    elevated: Boolean = false,
    translucent: Boolean = false,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    val effectiveBorder = border ?: BorderStroke(1.dp, telemetry.cardBorder)
    val fill = when {
        translucent -> telemetry.chromeTranslucent
        elevated -> telemetry.glassSurfaceElevated
        else -> telemetry.glassSurface
    }
    Surface(
        color = fill,
        shape = shape,
        border = effectiveBorder,
        modifier = modifier,
        content = content
    )
}
