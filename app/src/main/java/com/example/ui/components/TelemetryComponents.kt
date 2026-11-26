package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.CanonicalReleaseGroup
import com.example.model.IndexerSearchStatus
import com.example.model.IndexerSource
import com.example.model.ProviderTerminalState
import com.example.model.QualityTag
import com.example.model.SearchFilterState
import com.example.model.SortOption
import com.example.model.SwarmHealth
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import com.example.model.WebsiteOpenMode
import com.example.ui.theme.GlassCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle

@Composable
fun SwarmHealthBadge(health: SwarmHealth, modifier: Modifier = Modifier) {
    val telemetry = LocalTelemetryColors.current
    val (bg, fg, icon) = when (health) {
        SwarmHealth.HEALTHY -> Triple(telemetry.seederGreenContainer, telemetry.seederGreen, Icons.Filled.CheckCircle)
        SwarmHealth.MODERATE -> Triple(telemetry.leecherAmberContainer, telemetry.leecherAmber, Icons.Filled.Speed)
        SwarmHealth.WEAK -> Triple(telemetry.leecherAmberContainer, telemetry.leecherAmber, Icons.Filled.WarningAmber)
        SwarmHealth.DEAD -> Triple(telemetry.deadRoseContainer, telemetry.deadRose, Icons.Filled.Cancel)
        SwarmHealth.UNKNOWN -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.HelpOutline
        )
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.75.dp, fg.copy(alpha = 0.28f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = health.label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun SourcePill(source: IndexerSource, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)),
        modifier = modifier
    ) {
        Text(
            text = source.shortBadge,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
        )
    }
}

@Composable
fun QualityBadge(tag: QualityTag, modifier: Modifier = Modifier) {
    val telemetry = LocalTelemetryColors.current
    Surface(
        color = telemetry.qualityPurpleContainer,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.75.dp, telemetry.qualityPurple.copy(alpha = 0.28f)),
        modifier = modifier
    ) {
        Text(
            text = tag.label,
            style = MaterialTheme.typography.labelSmall,
            color = telemetry.qualityPurple,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
        )
    }
}

/**
 * Compact, high-density canonical release row for Search results:
 * shows title, size, seeders, age, and source(s) while keeping unknown values distinct from zero.
 */
@Composable
fun ReleaseGroupRow(
    group: CanonicalReleaseGroup,
    isSelected: Boolean,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    onToggleSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    val extraSources = (group.sources.size - 1).coerceAtLeast(0)
    val seedersText = group.knownSeeders?.toString() ?: "Unknown"
    val leechersText = group.knownLeechers?.toString() ?: "Unknown"
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else telemetry.cardBorder
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
    } else {
        telemetry.glassSurface
    }

    val accessibilitySummary = buildString {
        append(group.title)
        append(", source ")
        append(group.sources.joinToString(", ") { it.displayName })
        append(", size ")
        append(group.sizeLabel)
        append(", seeders ")
        append(seedersText)
        append(", age ")
        append(group.uploadDateLabel)
        append(", swarm ")
        append(group.swarmHealth.label)
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = false) {
                contentDescription = accessibilitySummary
            }
            .testTag("release_row_${group.groupKey}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Metrics line: Seeders | Size | Age | Source (+N)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SourcePill(group.primarySource)
                    if (extraSources > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "+$extraSources",
                                style = TelemetryMonoStyle.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = "Seeders",
                            tint = if (group.knownSeeders != null && group.knownSeeders!! > 0) {
                                telemetry.seederGreen
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = seedersText,
                            style = TelemetryMonoStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = if (group.knownSeeders != null && group.knownSeeders!! > 0) {
                                telemetry.seederGreen
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1
                        )
                    }

                    Text(
                        text = group.sizeLabel,
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = group.uploadDateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                if (group.qualityTags.isNotEmpty() || group.swarmHealth == SwarmHealth.DEAD || group.knownLeechers != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SwarmHealthBadge(group.swarmHealth)
                        group.qualityTags.take(3).forEach { tag ->
                            QualityBadge(tag)
                        }
                        if (group.knownLeechers != null) {
                            Text(
                                text = "L:$leechersText",
                                style = TelemetryMonoStyle,
                                color = telemetry.leecherAmber,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onToggleSave,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("row_save_${group.groupKey}")
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (isBookmarked) "Remove saved release" else "Save release",
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

@Composable
fun ProviderStatusStrip(
    statuses: List<IndexerSearchStatus>,
    onStatusClick: (IndexerSearchStatus) -> Unit = {},
    onOpenDiagnostics: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    if (statuses.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onOpenDiagnostics != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, telemetry.cardBorder),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenDiagnostics)
                    .testTag("open_sources_diagnostics_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_sources),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        statuses.forEach { st ->
            val (accentColor, statusIcon, shortValue) = when (st.terminalState) {
                ProviderTerminalState.LOADING -> Triple(MaterialTheme.colorScheme.primary, null, "…")
                ProviderTerminalState.MATCHES -> Triple(telemetry.seederGreen, Icons.Filled.CheckCircle, st.resultCount.toString())
                ProviderTerminalState.ZERO_MATCHES -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.RemoveCircleOutline, "0")
                ProviderTerminalState.TIMEOUT -> Triple(telemetry.leecherAmber, Icons.Filled.Schedule, "Timeout")
                ProviderTerminalState.RATE_LIMITED -> Triple(telemetry.leecherAmber, Icons.Filled.HourglassTop, "429")
                ProviderTerminalState.PARSER_ERROR -> Triple(telemetry.deadRose, Icons.Filled.ErrorOutline, "Parse")
                ProviderTerminalState.NETWORK_ERROR -> Triple(telemetry.deadRose, Icons.Filled.ErrorOutline, "Err")
                ProviderTerminalState.DISABLED -> Triple(telemetry.disabledText, Icons.Filled.Block, "Off")
                ProviderTerminalState.UNSUPPORTED_CATEGORY -> Triple(telemetry.disabledText, Icons.Filled.RemoveCircleOutline, "N/A")
            }

            Surface(
                color = telemetry.glassSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onStatusClick(st) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${st.source.displayName}: ${st.terminalState.label}, ${st.resultCount} results"
                    }
                    .testTag("provider_status_${st.source.name}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (st.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.6.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (statusIcon != null) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = st.source.shortBadge,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = shortValue,
                        style = TelemetryMonoStyle,
                        color = if (st.terminalState.isFailure) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSortSheet(
    currentFilters: SearchFilterState,
    onApply: (SearchFilterState) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val telemetry = LocalTelemetryColors.current

    var draftCategory by remember(currentFilters) { mutableStateOf(currentFilters.category) }
    var draftSort by remember(currentFilters) { mutableStateOf(currentFilters.sort) }
    var draftQualities by remember(currentFilters) { mutableStateOf(currentFilters.qualities) }
    var draftSources by remember(currentFilters) { mutableStateOf(currentFilters.selectedSources) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = telemetry.cardBorder) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag("search_filter_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Filter & Sort",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = {
                        onReset()
                        onDismiss()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("filter_sheet_reset_button")
                ) {
                    Text(stringResource(R.string.action_reset))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "SORT BY (LOCAL)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SortOption.entries.forEach { sort ->
                    val selected = draftSort == sort
                    FilterChip(
                        selected = selected,
                        onClick = { draftSort = sort },
                        label = { Text(sort.label) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("filter_sort_${sort.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "CATEGORY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TorrentCategory.entries.forEach { cat ->
                    val selected = draftCategory == cat
                    FilterChip(
                        selected = selected,
                        onClick = { draftCategory = cat },
                        label = { Text(cat.label) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("filter_category_${cat.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "QUALITY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QualityTag.entries.forEach { tag ->
                    val selected = tag in draftQualities
                    FilterChip(
                        selected = selected,
                        onClick = {
                            draftQualities = if (selected) draftQualities - tag else draftQualities + tag
                        },
                        label = { Text(tag.label) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = telemetry.qualityPurpleContainer,
                            selectedLabelColor = telemetry.qualityPurple
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("filter_quality_${tag.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "SOURCES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IndexerSource.entries.forEach { src ->
                    val selected = src in draftSources
                    FilterChip(
                        selected = selected,
                        onClick = {
                            draftSources = if (selected) draftSources - src else draftSources + src
                        },
                        label = { Text(src.displayName) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("filter_source_${src.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = {
                    onApply(
                        SearchFilterState(
                            category = draftCategory,
                            sort = draftSort,
                            qualities = draftQualities,
                            selectedSources = draftSources
                        )
                    )
                    onDismiss()
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("filter_sheet_apply_button")
            ) {
                Text("Apply Filters", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDiagnosticsSheet(
    statuses: List<IndexerSearchStatus>,
    openMode: WebsiteOpenMode,
    onRetrySource: (IndexerSource) -> Unit,
    onOpenSourceSite: (IndexerSearchStatus) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val telemetry = LocalTelemetryColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = telemetry.cardBorder) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag("sources_diagnostics_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Source Status & Diagnostics",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Mirrors")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                statuses.forEach { st ->
                    GlassCard(
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diagnostic_row_${st.source.name}")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SourcePill(st.source)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = st.terminalState.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = when {
                                        st.terminalState == ProviderTerminalState.MATCHES -> telemetry.seederGreen
                                        st.terminalState.isFailure -> telemetry.deadRose
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = buildString {
                                        append("${st.resultCount} items")
                                        if (st.elapsedMs != null) {
                                            append(" • ${st.elapsedMs}ms")
                                        }
                                    },
                                    style = TelemetryMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (st.mirrorUsed.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = st.mirrorUsed.removePrefix("https://").removePrefix("http://"),
                                    style = TelemetryMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (!st.errorMessage.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = st.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = telemetry.deadRose
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (st.terminalState != ProviderTerminalState.DISABLED &&
                                    st.terminalState != ProviderTerminalState.UNSUPPORTED_CATEGORY
                                ) {
                                    OutlinedButton(
                                        onClick = { onRetrySource(st.source) },
                                        enabled = !st.isLoading && !st.isRetrying,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("retry_source_${st.source.name}")
                                    ) {
                                        if (st.isLoading || st.isRetrying) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Retrying…")
                                        } else {
                                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(R.string.action_retry))
                                        }
                                    }
                                }

                                val webTarget = st.webSearchUrl.ifBlank { st.mirrorUsed }
                                if (webTarget.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            onDismiss()
                                            onOpenSourceSite(st)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("open_source_html_${st.source.name}")
                                    ) {
                                        Icon(
                                            imageVector = if (openMode == WebsiteOpenMode.IN_APP) {
                                                Icons.Outlined.Public
                                            } else {
                                                Icons.AutoMirrored.Filled.OpenInNew
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Open HTML")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchStatusMessageCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    testTag: String = "search_status_card",
    modifier: Modifier = Modifier
) {
    GlassCard(
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onPrimaryAction,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("${testTag}_primary_action")
                    ) {
                        Text(primaryActionLabel)
                    }
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        OutlinedButton(
                            onClick = onSecondaryAction,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("${testTag}_secondary_action")
                        ) {
                            Text(secondaryActionLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactFailedSourcesBanner(
    failedStatuses: List<IndexerSearchStatus>,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    if (failedStatuses.isEmpty()) return
    val failedNames = failedStatuses.joinToString(", ") { it.source.shortBadge }
    Surface(
        color = telemetry.deadRoseContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.75.dp, telemetry.deadRose.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpenDiagnostics)
            .testTag("compact_failed_sources_entry")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = "Failed sources details",
                tint = telemetry.deadRose,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${failedStatuses.size} failed: $failedNames",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Details",
                style = MaterialTheme.typography.labelSmall,
                color = telemetry.deadRose,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = telemetry.deadRose,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Retained for backward compatibility if referenced externally.
 */
@Composable
fun IndexerErrorFallbackBanner(
    failedStatuses: List<IndexerSearchStatus>,
    openMode: WebsiteOpenMode,
    onRetrySource: (IndexerSource) -> Unit,
    onOpenSite: (IndexerSearchStatus) -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompactFailedSourcesBanner(
        failedStatuses = failedStatuses,
        onOpenDiagnostics = onOpenDiagnostics,
        modifier = modifier
    )
}

/**
 * Retained for existing VaultScreen bookmarks display (Phase 2 owns Vault redesign).
 */
@Composable
fun TorrentResultCard(
    torrent: TorrentResult,
    isBookmarked: Boolean,
    isResolvingAction: Boolean = false,
    onClick: () -> Unit,
    onCopyMagnet: () -> Unit,
    onOpenSeedr: () -> Unit,
    onOpenTorBox: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    GlassCard(
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("torrent_card_${torrent.id}")
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SourcePill(torrent.source)
                SwarmHealthBadge(torrent.swarmHealth)
                torrent.qualityTags.take(3).forEach { tag ->
                    QualityBadge(tag)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = torrent.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = torrent.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(9.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "Seeders",
                    tint = telemetry.seederGreen,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = (torrent.knownSeeders ?: torrent.seeders).toString(),
                    style = TelemetryMonoStyle,
                    color = telemetry.seederGreen,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.width(12.dp))

                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = "Leechers",
                    tint = telemetry.leecherAmber,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = (torrent.knownLeechers ?: torrent.leechers).toString(),
                    style = TelemetryMonoStyle,
                    color = telemetry.leecherAmber
                )

                Spacer(modifier = Modifier.width(12.dp))

                Icon(
                    imageVector = Icons.Outlined.Storage,
                    contentDescription = "Size",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = torrent.sizeLabel,
                    style = TelemetryMonoStyle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = torrent.uploadDateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(9.dp))
            HorizontalDivider(
                thickness = 0.75.dp,
                color = telemetry.cardBorder.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !isResolvingAction, onClick = onCopyMagnet)
                        .testTag("card_copy_magnet_${torrent.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isResolvingAction) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy magnet link",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isResolvingAction) "Fetching…" else "Magnet",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.75.dp, telemetry.cardBorder),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !isResolvingAction, onClick = onOpenSeedr)
                        .testTag("card_open_seedr_${torrent.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Seedr",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.75.dp, telemetry.cardBorder),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !isResolvingAction, onClick = onOpenTorBox)
                        .testTag("card_open_torbox_${torrent.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TorBox",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark torrent",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
