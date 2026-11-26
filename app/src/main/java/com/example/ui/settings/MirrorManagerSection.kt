package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.MirrorHealthResult
import com.example.model.ParserCheckStatus
import com.example.ui.theme.GlassCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle
import java.text.DateFormat
import java.util.Date

@Composable
fun MirrorManagerSection(
    mirrorConfigs: Map<IndexerSource, MirrorConfig>,
    healthMap: Map<String, MirrorHealthResult>,
    isPingingAll: Boolean,
    onPingAll: () -> Unit,
    onPingSingle: (IndexerSource, String) -> Unit,
    onVerifyParser: (IndexerSource, String) -> Unit,
    onToggleProvider: (IndexerSource, Boolean) -> Unit,
    onAddMirror: (IndexerSource, String) -> Unit,
    onRemoveMirror: (IndexerSource, String) -> Unit,
    onSetPrimaryMirror: (IndexerSource, String) -> Unit,
    onPromoteFastest: (IndexerSource) -> Unit,
    onResetDefaults: () -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    var addDialogProvider by remember { mutableStateOf<IndexerSource?>(null) }
    var newMirrorInput by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Manage provider domains, HTTP reachability checks, and parser schema verification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onPingAll,
                enabled = !isPingingAll,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("ping_all_mirrors_button")
            ) {
                if (isPingingAll) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pinging…")
                } else {
                    Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_test_mirrors))
                }
            }
            OutlinedButton(
                onClick = onResetDefaults,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, telemetry.cardBorder),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("reset_mirrors_button")
            ) {
                Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset")
            }
        }

        IndexerSource.entries.forEach { source ->
            val cfg = mirrorConfigs[source] ?: MirrorConfig(provider = source)
            GlassCard(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mirror_source_card_${source.name}")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = source.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Primary: ${cfg.activeMirrorUrl}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = cfg.enabled,
                            onCheckedChange = { onToggleProvider(source, it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(
                        thickness = 0.75.dp,
                        color = telemetry.cardBorder.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    cfg.mirrors.forEachIndexed { mirrorIndex, url ->
                        val isPrimary = url == cfg.activeMirrorUrl
                        val health = healthMap[url]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSetPrimaryMirror(source, url) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = if (isPrimary) "Primary mirror" else "Set as primary",
                                    tint = if (isPrimary) MaterialTheme.colorScheme.primary else telemetry.disabledText,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val displayUrl = url.removePrefix("https://").removePrefix("http://")
                                Text(
                                    text = if (displayUrl.contains("knaben.org", true)) "$displayUrl (1337x Index)" else displayUrl,
                                    style = TelemetryMonoStyle,
                                    color = if (isPrimary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))

                                MirrorPingPill(
                                    health = health,
                                    onPing = { onPingSingle(source, url) },
                                    modifier = Modifier.testTag("ping_mirror_${source.name}_$mirrorIndex")
                                )

                                if (cfg.mirrors.size > 1) {
                                    IconButton(
                                        onClick = { onRemoveMirror(source, url) },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Remove mirror",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Parser verification status + checked timestamp row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 23.dp, top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val parserStatus = health?.parserStatus ?: ParserCheckStatus.NEVER_RUN
                                val parserColor = when (parserStatus) {
                                    ParserCheckStatus.NEVER_RUN -> MaterialTheme.colorScheme.onSurfaceVariant
                                    ParserCheckStatus.VERIFIED -> telemetry.seederGreen
                                    ParserCheckStatus.FAILED -> telemetry.deadRose
                                }
                                Text(
                                    text = buildString {
                                        append(parserStatus.label)
                                        health?.parserDetail?.takeIf { it.isNotBlank() }?.let {
                                            append(" (")
                                            append(it)
                                            append(")")
                                        }
                                        health?.checkedAtEpochMs?.let { ts ->
                                            append(" • ")
                                            append(formatCheckedTime(ts))
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = parserColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("mirror_parser_status_${source.name}_$mirrorIndex")
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = health?.isChecking != true) {
                                            onVerifyParser(source, url)
                                        }
                                        .testTag("verify_parser_${source.name}_$mirrorIndex")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.FactCheck,
                                            contentDescription = "Verify parser on $url",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Verify Parser",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                newMirrorInput = "https://"
                                addDialogProvider = source
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Mirror")
                        }
                        TextButton(
                            onClick = { onPromoteFastest(source) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Promote Fastest")
                        }
                    }
                }
            }
        }
    }

    val targetProvider = addDialogProvider
    if (targetProvider != null) {
        AlertDialog(
            onDismissRequest = { addDialogProvider = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(18.dp),
            title = { Text("Add ${targetProvider.displayName} Mirror") },
            text = {
                Column {
                    Text(
                        text = "Enter the latest working mirror URL for ${targetProvider.displayName}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newMirrorInput,
                        onValueChange = { newMirrorInput = it },
                        label = { Text("Mirror URL") },
                        placeholder = { Text("https://x1337x.cc") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = telemetry.glassSurfaceElevated,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = telemetry.cardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddMirror(targetProvider, newMirrorInput)
                        addDialogProvider = null
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save & Ping")
                }
            },
            dismissButton = {
                TextButton(onClick = { addDialogProvider = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatCheckedTime(epochMs: Long): String =
    runCatching { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs)) }
        .getOrDefault("Just now")

@Composable
private fun MirrorPingPill(
    health: MirrorHealthResult?,
    onPing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    val (bg, fg, label) = when {
        health == null -> Triple(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            "HTTP Ping"
        )
        health.isChecking -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primary,
            "…"
        )
        health.isReachable == true -> {
            val ms = health.latencyMs ?: 0L
            val isFast = ms < 900L
            Triple(
                if (isFast) telemetry.seederGreenContainer else telemetry.leecherAmberContainer,
                if (isFast) telemetry.seederGreen else telemetry.leecherAmber,
                "HTTP ${ms}ms"
            )
        }
        else -> Triple(
            telemetry.deadRoseContainer,
            telemetry.deadRose,
            when {
                health.error.equals("Cloudflare", ignoreCase = true) -> "CF Block"
                health.statusCode != null -> "HTTP ${health.statusCode}"
                !health.error.isNullOrBlank() -> health.error
                else -> "Down"
            }
        )
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.75.dp, fg.copy(alpha = 0.22f)),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPing)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (health?.isChecking == true) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = fg
                )
            } else if (health != null) {
                Box(
                    modifier = Modifier
                        .size(5.5.dp)
                        .clip(CircleShape)
                        .background(fg)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
