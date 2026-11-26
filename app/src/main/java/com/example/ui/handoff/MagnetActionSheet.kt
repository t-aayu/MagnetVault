package com.example.ui.handoff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.CanonicalReleaseGroup
import com.example.model.MagnetResolutionState
import com.example.model.WebsiteOpenMode
import com.example.ui.components.QualityBadge
import com.example.ui.components.SourcePill
import com.example.ui.components.SwarmHealthBadge
import com.example.ui.theme.GlassCard
import com.example.ui.theme.HashMonoStyle
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagnetActionModalSheet(
    group: CanonicalReleaseGroup,
    isBookmarked: Boolean,
    torrentBoxOpenMode: WebsiteOpenMode,
    onDismiss: () -> Unit,
    onRetryResolveMagnet: () -> Unit,
    onOpenLocalClient: (String) -> Unit,
    onCopyMagnet: (String) -> Unit,
    onToggleSave: () -> Unit,
    onOpenSeedr: (String?) -> Unit,
    onOpenTorBox: (String?) -> Unit,
    onShareMagnet: (String) -> Unit,
    onOpenSourcePage: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val telemetry = LocalTelemetryColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = telemetry.cardBorder)
        }
    ) {
        MagnetActionDetailContent(
            group = group,
            isBookmarked = isBookmarked,
            torrentBoxOpenMode = torrentBoxOpenMode,
            showCloseButton = false,
            onClose = onDismiss,
            onRetryResolveMagnet = onRetryResolveMagnet,
            onOpenLocalClient = onOpenLocalClient,
            onCopyMagnet = onCopyMagnet,
            onToggleSave = onToggleSave,
            onOpenSeedr = onOpenSeedr,
            onOpenTorBox = onOpenTorBox,
            onShareMagnet = onShareMagnet,
            onOpenSourcePage = onOpenSourcePage,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        )
    }
}

/**
 * Shared release inspection and magnet action surface used in both the phone bottom sheet
 * and the expanded-screen side detail pane (and for pasted magnet links).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MagnetActionDetailContent(
    group: CanonicalReleaseGroup,
    isBookmarked: Boolean,
    torrentBoxOpenMode: WebsiteOpenMode,
    showCloseButton: Boolean,
    onClose: () -> Unit,
    onRetryResolveMagnet: () -> Unit,
    onOpenLocalClient: (String) -> Unit,
    onCopyMagnet: (String) -> Unit,
    onToggleSave: () -> Unit,
    onOpenSeedr: (String?) -> Unit,
    onOpenTorBox: (String?) -> Unit,
    onShareMagnet: (String) -> Unit,
    onOpenSourcePage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    val magnetState = group.magnetState
    val readyMagnet = (magnetState as? MagnetResolutionState.Available)?.magnetUri
    val isMagnetReady = !readyMagnet.isNullOrBlank()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = if (showCloseButton) 14.dp else 4.dp, bottom = 24.dp)
            .testTag("magnet_action_sheet_content")
    ) {
        // Header row with badges and optional close button for side pane
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                group.sources.forEach { src -> SourcePill(src) }
                SwarmHealthBadge(group.swarmHealth)
                group.qualityTags.forEach { tag -> QualityBadge(tag) }
            }
            if (showCloseButton) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("detail_pane_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close release details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = group.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("detail_release_title")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Magnet resolution status banner (Idle / Resolving / Available / Failed)
        MagnetResolutionBanner(
            group = group,
            onRetry = onRetryResolveMagnet
        )

        Spacer(modifier = Modifier.height(16.dp))

        // PRIMARY ACTIONS: Open with, Copy magnet, Save
        Text(
            text = "PRIMARY ACTIONS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { readyMagnet?.let(onOpenLocalClient) },
            enabled = isMagnetReady,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("action_open_with_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.action_open_with),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = { readyMagnet?.let(onCopyMagnet) },
                enabled = isMagnetReady,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("copy_magnet_button")
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.action_copy_magnet),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Save is ALWAYS enabled, even when magnet resolution is pending or failed
            OutlinedButton(
                onClick = onToggleSave,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isBookmarked) MaterialTheme.colorScheme.primary else telemetry.cardBorder
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("action_save_button")
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isBookmarked) stringResource(R.string.action_bookmarked) else stringResource(R.string.action_save),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SWARM & METADATA SUMMARY (representative values — never summed across providers)
        GlassCard(
            shape = RoundedCornerShape(14.dp),
            elevated = true,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "SWARM DETAILS (${group.primarySource.displayName.uppercase(Locale.US)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricItem(
                        label = "SEEDERS",
                        value = group.knownSeeders?.toString() ?: "Unknown",
                        valueColor = if (group.knownSeeders != null) telemetry.seederGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MetricItem(
                        label = "LEECHERS",
                        value = group.knownLeechers?.toString() ?: "Unknown",
                        valueColor = if (group.knownLeechers != null) telemetry.leecherAmber else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MetricItem(
                        label = "SIZE",
                        value = group.sizeLabel,
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                    MetricItem(
                        label = "AGE",
                        value = group.uploadDateLabel,
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SECONDARY ACTIONS: Seedr.cc, TorBox, Share
        Text(
            text = "SECONDARY ACTIONS • ${if (torrentBoxOpenMode == WebsiteOpenMode.IN_APP) "IN-APP" else "BROWSER"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { onOpenSeedr(readyMagnet) },
                enabled = isMagnetReady,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("open_seedr_browser_button")
            ) {
                Icon(Icons.Outlined.CloudQueue, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_open_seedr))
            }

            FilledTonalButton(
                onClick = { onOpenTorBox(readyMagnet) },
                enabled = isMagnetReady,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("open_torbox_browser_button")
            ) {
                Icon(Icons.Outlined.CloudQueue, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_open_torbox))
            }

            OutlinedButton(
                onClick = { readyMagnet?.let(onShareMagnet) },
                enabled = isMagnetReady,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, telemetry.cardBorder),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("action_share_button")
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_share))
            }
        }

        // SOURCE LISTINGS (Per-provider swarm counts & individual source links — never summed)
        if (group.listings.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "SOURCE LINKS (${group.listings.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            GlassCard(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    group.listings.forEachIndexed { idx, listing ->
                        val hasUrl = listing.detailPageUrl.startsWith("http", ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = hasUrl) { onOpenSourcePage(listing.detailPageUrl) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .semantics(mergeDescendants = true) {}
                                .testTag("source_listing_${listing.source.name}_${listing.listingId}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SourcePill(listing.source)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "S:${listing.knownSeeders?.toString() ?: "?"}  L:${listing.knownLeechers?.toString() ?: "?"}  •  ${listing.sizeLabel}  •  ${listing.uploadDateLabel}",
                                    style = TelemetryMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (listing.mirrorUsed.isNotBlank()) {
                                    Text(
                                        text = listing.mirrorUsed.removePrefix("https://").removePrefix("http://"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (hasUrl) {
                                Icon(
                                    imageVector = Icons.Outlined.Public,
                                    contentDescription = "Open ${listing.source.displayName} source page",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (idx < group.listings.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.75.dp,
                                color = telemetry.cardBorder.copy(alpha = 0.55f),
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MagnetResolutionBanner(
    group: CanonicalReleaseGroup,
    onRetry: () -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    when (val state = group.magnetState) {
        is MagnetResolutionState.Resolving -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("magnet_state_resolving")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Resolving magnet link from ${(state.source ?: group.primarySource).displayName}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        is MagnetResolutionState.Failed -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("magnet_state_failed")
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = "Magnet resolution error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("retry_magnet_resolve_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Retry magnet resolution",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        is MagnetResolutionState.Available -> {
            Surface(
                color = telemetry.glassSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(0.75.dp, telemetry.cardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("magnet_state_available")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Verified magnet hash",
                        tint = telemetry.seederGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${state.identity.family.name} VERIFIED HASH",
                            style = MaterialTheme.typography.labelSmall,
                            color = telemetry.seederGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = state.identity.canonicalHex.uppercase(Locale.US),
                            style = HashMonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        MagnetResolutionState.Idle -> {
            Surface(
                color = telemetry.glassSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(0.75.dp, telemetry.cardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("magnet_state_idle")
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Magnet link not resolved yet. You can still Save this listing or resolve now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("resolve_magnet_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Resolve magnet link",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = TelemetryMonoStyle.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
