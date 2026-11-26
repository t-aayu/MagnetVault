package com.example.ui.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.magnet.MagnetParseResult
import com.example.model.QualityAndHealthParser
import com.example.ui.theme.HashMonoStyle
import com.example.ui.theme.LocalTelemetryColors
import java.util.Locale

@Composable
fun MultiMagnetSelectionDialog(
    magnets: List<MagnetParseResult.Valid>,
    onSelectMagnet: (MagnetParseResult.Valid) -> Unit,
    onDismiss: () -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Select Magnet Link (${magnets.size})") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("multi_magnet_dialog")
            ) {
                Text(
                    text = "Multiple magnet links were found in the shared text. Choose one to open in MagnetVault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                magnets.forEachIndexed { index, magnet ->
                    Surface(
                        color = telemetry.glassSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.75.dp, telemetry.cardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectMagnet(magnet) }
                            .testTag("multi_magnet_option_$index")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = magnet.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val meta = buildString {
                                    append(magnet.primaryIdentity.canonicalHex.take(16).uppercase(Locale.US))
                                    magnet.exactLengthBytes?.let { bytes ->
                                        append(" • ")
                                        append(QualityAndHealthParser.formatKnownBytes(bytes))
                                    }
                                    if (magnet.trackers.isNotEmpty()) {
                                        append(" • ")
                                        append("${magnet.trackers.size} tracker(s)")
                                    }
                                }
                                Text(
                                    text = meta,
                                    style = HashMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun UnsupportedShareDialog(
    reason: String,
    rawPreview: String,
    onDismiss: () -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = telemetry.leecherAmber
            )
        },
        title = { Text("No Valid Magnet Found") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("unsupported_share_dialog")
            ) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (rawPreview.isNotBlank()) {
                    Surface(
                        color = telemetry.glassSurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.75.dp, telemetry.cardBorder)
                    ) {
                        Text(
                            text = rawPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("unsupported_share_dismiss_button")
            ) {
                Text("Return to App")
            }
        }
    )
}
