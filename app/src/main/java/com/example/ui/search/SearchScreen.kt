package com.example.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.CanonicalReleaseGroup
import com.example.model.IndexerSource
import com.example.model.ProviderListing
import com.example.model.ProviderTerminalState
import com.example.model.SearchContentPhase
import com.example.model.TorrentCategory
import com.example.model.WebsiteOpenMode
import com.example.ui.components.CompactFailedSourcesBanner
import com.example.ui.components.ReleaseGroupRow
import com.example.ui.components.SearchFilterSortSheet
import com.example.ui.components.SearchStatusMessageCard
import com.example.ui.components.SourceDiagnosticsSheet
import com.example.ui.handoff.MagnetActionDetailContent
import com.example.ui.handoff.MagnetActionModalSheet
import com.example.ui.theme.GlassCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle
import com.example.ui.theme.ambientGlassBackdrop
import com.example.viewmodel.SearchViewModel

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    websiteOpenMode: WebsiteOpenMode,
    torrentBoxOpenMode: WebsiteOpenMode,
    onReadClipboard: () -> String,
    onOpenSettings: () -> Unit,
    onOpenLocalClient: (title: String, magnetUri: String) -> Unit,
    onCopyMagnet: (title: String, magnetUri: String) -> Unit,
    onOpenSeedr: (title: String, magnetUri: String?) -> Unit,
    onOpenTorBox: (title: String, magnetUri: String?) -> Unit,
    onShareMagnet: (title: String, magnetUri: String) -> Unit,
    onOpenSourcePage: (url: String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val groups by viewModel.displayedGroups.collectAsStateWithLifecycle()
    val trending by viewModel.trendingResults.collectAsStateWithLifecycle()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val searchHistoryEnabled by viewModel.searchHistoryEnabled.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val pastedMagnetGroup by viewModel.pastedMagnetGroup.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val telemetry = LocalTelemetryColors.current
    val listState = rememberLazyListState()

    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var showSourcesSheet by rememberSaveable { mutableStateOf(false) }
    var showSourceMenu by rememberSaveable { mutableStateOf(false) }
    var showPasteEditDialog by rememberSaveable { mutableStateOf(false) }
    var manualPasteDraft by rememberSaveable { mutableStateOf("") }

    // Back priority inside Search: dismiss modal/dialog/sheet -> dismiss selected detail pane
    BackHandler(enabled = selectedGroup != null) {
        viewModel.dismissTorrentDetail()
    }
    BackHandler(enabled = pastedMagnetGroup != null) {
        viewModel.dismissPastedMagnetSheet()
    }
    BackHandler(enabled = showSourcesSheet) {
        showSourcesSheet = false
    }
    BackHandler(enabled = showFilterSheet) {
        showFilterSheet = false
    }
    BackHandler(enabled = showPasteEditDialog) {
        showPasteEditDialog = false
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .ambientGlassBackdrop(telemetry)
    ) {
        val useTwoPane = maxWidth >= 840.dp

        Row(modifier = Modifier.fillMaxSize()) {
            // Left / Main Search Column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // Compact translucent Search Header (slim search input + left Filters + right Source dropdown)
                Surface(
                    color = telemetry.chromeTranslucent,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = telemetry.glassSurface,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, telemetry.cardBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 10.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (state.query.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.search_placeholder),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        BasicTextField(
                                            value = state.query,
                                            onValueChange = viewModel::updateQuery,
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                            keyboardActions = KeyboardActions(
                                                onSearch = {
                                                    focusManager.clearFocus()
                                                    viewModel.executeSearch()
                                                }
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("search_query_input")
                                        )
                                    }
                                    if (state.query.isNotEmpty() || state.hasSearched) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { viewModel.clearSearch() }
                                                .testTag("search_clear_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Clear search",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.executeSearch()
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .height(42.dp)
                                    .testTag("search_submit_button")
                            ) {
                                Text(
                                    text = "Go",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Compact single row: Filters (left) + Source dropdown (right)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = if (state.filters.hasNonDefaultFilters) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        telemetry.glassSurface
                                    },
                                    shape = RoundedCornerShape(9.dp),
                                    border = BorderStroke(
                                        0.75.dp,
                                        if (state.filters.hasNonDefaultFilters) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            telemetry.cardBorder
                                        }
                                    ),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .clickable { showFilterSheet = true }
                                        .testTag("open_filters_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.FilterList,
                                            contentDescription = null,
                                            tint = if (state.filters.hasNonDefaultFilters) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (state.filters.activeFilterCount > 0) {
                                                "${stringResource(R.string.action_filters)} (${state.filters.activeFilterCount})"
                                            } else {
                                                stringResource(R.string.action_filters)
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (state.filters.hasNonDefaultFilters) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                if (state.filters.hasNonDefaultFilters) {
                                    Surface(
                                        color = telemetry.glassSurface,
                                        shape = RoundedCornerShape(9.dp),
                                        border = BorderStroke(0.75.dp, telemetry.cardBorder),
                                        modifier = Modifier
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .clickable { viewModel.resetFilters() }
                                            .testTag("active_filters_reset_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Reset filters",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.action_reset),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }

                            // Right: Single Source selector dropdown
                            Box {
                                val selectedSources = state.filters.selectedSources
                                val sourceButtonLabel = buildString {
                                    when {
                                        selectedSources.size == 1 -> append(selectedSources.first().shortBadge)
                                        selectedSources.size > 1 -> append("${selectedSources.size} sources")
                                        else -> append("All sources")
                                    }
                                    if (state.hasSearched && state.enabledSourcesCount > 0) {
                                        append(" (${state.completedSourcesCount}/${state.enabledSourcesCount})")
                                    }
                                }
                                Surface(
                                    color = if (selectedSources.isNotEmpty()) {
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    } else {
                                        telemetry.glassSurface
                                    },
                                    shape = RoundedCornerShape(9.dp),
                                    border = BorderStroke(
                                        0.75.dp,
                                        if (selectedSources.isNotEmpty()) {
                                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
                                        } else {
                                            telemetry.cardBorder
                                        }
                                    ),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .clickable { showSourceMenu = true }
                                        .testTag("source_dropdown_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 10.dp, end = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = sourceButtonLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selectedSources.isNotEmpty()) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            imageVector = Icons.Filled.ArrowDropDown,
                                            contentDescription = "Select source",
                                            tint = if (selectedSources.isNotEmpty()) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showSourceMenu,
                                    onDismissRequest = { showSourceMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.testTag("source_dropdown_menu")
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("All sources", style = MaterialTheme.typography.bodyMedium) },
                                        trailingIcon = if (selectedSources.isEmpty()) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else null,
                                        onClick = {
                                            viewModel.selectSingleSourceFilter(null)
                                            showSourceMenu = false
                                        },
                                        modifier = Modifier.testTag("source_dropdown_all")
                                    )
                                    HorizontalDivider(color = telemetry.cardBorder.copy(alpha = 0.6f))
                                    val statusBySource = state.providerStatuses.associateBy { it.source }
                                    IndexerSource.entries.forEach { src ->
                                        val st = statusBySource[src]
                                        val isCurrent = selectedSources.size == 1 && src in selectedSources
                                        val statusLabel = if (state.hasSearched && st != null) {
                                            when (st.terminalState) {
                                                ProviderTerminalState.LOADING -> "…"
                                                ProviderTerminalState.MATCHES -> st.resultCount.toString()
                                                ProviderTerminalState.ZERO_MATCHES -> "0"
                                                ProviderTerminalState.DISABLED -> "Off"
                                                ProviderTerminalState.UNSUPPORTED_CATEGORY -> "N/A"
                                                else -> "Failed"
                                            }
                                        } else null
                                        val statusColor = when {
                                            st == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                            st.terminalState == ProviderTerminalState.MATCHES -> telemetry.seederGreen
                                            st.terminalState.isFailure -> telemetry.deadRose
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = src.displayName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    if (statusLabel != null) {
                                                        Text(
                                                            text = statusLabel,
                                                            style = TelemetryMonoStyle,
                                                            color = statusColor
                                                        )
                                                    }
                                                }
                                            },
                                            trailingIcon = if (isCurrent) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            } else null,
                                            onClick = {
                                                viewModel.selectSingleSourceFilter(src)
                                                showSourceMenu = false
                                            },
                                            modifier = Modifier.testTag("source_dropdown_${src.name}")
                                        )
                                    }
                                    HorizontalDivider(color = telemetry.cardBorder.copy(alpha = 0.6f))
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Source info & diagnostics",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        onClick = {
                                            showSourceMenu = false
                                            showSourcesSheet = true
                                        },
                                        modifier = Modifier.testTag("source_dropdown_diagnostics")
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(thickness = 0.75.dp, color = telemetry.cardBorder.copy(alpha = 0.7f))

                if (state.isSearching) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .testTag("search_progress_bar"),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }

                // Inline paste magnet validation feedback (NEVER auto-submits as search)
                val pasteError = state.pasteMagnetError
                if (pasteError != null) {
                    GlassCard(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("paste_magnet_error_card")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = "Invalid magnet",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = pasteError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.dismissPasteMagnetError() },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Dismiss magnet error", modifier = Modifier.size(18.dp))
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        manualPasteDraft = state.lastInvalidPasteText.orEmpty()
                                        showPasteEditDialog = true
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("paste_magnet_edit_button")
                                ) {
                                    Text("Edit magnet")
                                }
                                if (!state.lastInvalidPasteText.isNullOrBlank()) {
                                    TextButton(
                                        onClick = {
                                            val explicitText = state.lastInvalidPasteText.orEmpty()
                                            viewModel.dismissPasteMagnetError()
                                            viewModel.executeSearch(queryOverride = explicitText)
                                        },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("paste_search_anyway_button")
                                    ) {
                                        Text("Search text instead")
                                    }
                                }
                            }
                        }
                    }
                }

                // Single compact tappable failed-source entry (opens SourceDiagnosticsSheet)
                if (state.hasSearched && state.contentPhase != SearchContentPhase.ALL_SOURCES_DISABLED) {
                    val failedStatuses = state.providerStatuses.filter { it.terminalState.isFailure }
                    if (failedStatuses.isNotEmpty() && state.contentPhase != SearchContentPhase.TOTAL_FAILURE) {
                        CompactFailedSourcesBanner(
                            failedStatuses = failedStatuses,
                            onOpenDiagnostics = { showSourcesSheet = true }
                        )
                    }
                }

                // Main scrollable list (Search Home OR Search Results States)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("search_results_list"),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (state.contentPhase) {
                        SearchContentPhase.IDLE_HOME -> {
                            // 1. Recent Searches (shown ONLY when enabled & non-empty)
                            if (searchHistoryEnabled && recentSearches.isNotEmpty()) {
                                item(key = "recent_searches_section") {
                                    Column(modifier = Modifier.testTag("recent_searches_section")) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.History,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.recent_searches),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            recentSearches.take(10).forEach { entry ->
                                                Surface(
                                                    color = telemetry.glassSurface,
                                                    shape = RoundedCornerShape(10.dp),
                                                    border = BorderStroke(1.dp, telemetry.cardBorder),
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .clickable {
                                                            focusManager.clearFocus()
                                                            viewModel.runSearchFromHistory(
                                                                entry.query,
                                                                TorrentCategory.fromNameSafe(entry.category)
                                                            )
                                                        }
                                                        .testTag("recent_query_${entry.id}")
                                                ) {
                                                    Box(
                                                        contentAlignment = Alignment.Center,
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                                    ) {
                                                        Text(
                                                            text = entry.query,
                                                            style = MaterialTheme.typography.labelLarge,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 2. Explicit Trending Action Card (never auto-fetched on startup)
                            item(key = "trending_action_card") {
                                GlassCard(
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("trending_control_card")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.LocalFireDepartment,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.trending_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = if (state.trendingExpanded) {
                                                    "Live releases across enabled sources"
                                                } else {
                                                    "Fetch on demand when you want to browse popular releases"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (state.trendingExpanded) {
                                            IconButton(
                                                onClick = { viewModel.loadTrending() },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .testTag("trending_refresh_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Refresh,
                                                    contentDescription = "Refresh trending",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        FilledTonalButton(
                                            onClick = { viewModel.toggleTrending() },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("trending_toggle_button")
                                        ) {
                                            Text(
                                                text = if (state.trendingExpanded) {
                                                    stringResource(R.string.trending_hide_action)
                                                } else {
                                                    stringResource(R.string.trending_show_action)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            if (state.trendingExpanded) {
                                when {
                                    state.isLoadingTrending -> {
                                        item(key = "trending_loading") {
                                            GlassCard(
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("trending_loading_card")
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 28.dp, horizontal = 20.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    CircularProgressIndicator(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        strokeWidth = 2.5.dp,
                                                        modifier = Modifier.size(28.dp)
                                                    )
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    Text(
                                                        text = "Fetching trending feeds from enabled sources…",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    trending.isEmpty() && state.trendingError != null -> {
                                        item(key = "trending_error") {
                                            SearchStatusMessageCard(
                                                icon = Icons.Filled.ErrorOutline,
                                                title = "Could not load trending",
                                                subtitle = state.trendingError.orEmpty(),
                                                iconTint = telemetry.deadRose,
                                                primaryActionLabel = "Retry",
                                                onPrimaryAction = { viewModel.loadTrending() },
                                                secondaryActionLabel = "Settings",
                                                onSecondaryAction = onOpenSettings,
                                                testTag = "trending_error_card"
                                            )
                                        }
                                    }
                                    else -> {
                                        val trendingGroups = trending.map { item ->
                                            val listing = ProviderListing.fromTorrentResult(item)
                                            CanonicalReleaseGroup.fromListings(
                                                groupKey = listing.canonicalIdentity.storageKey,
                                                identity = listing.canonicalIdentity,
                                                listings = listOf(listing)
                                            )
                                        }
                                        items(trendingGroups, key = { "trend_${it.groupKey}" }) { group ->
                                            val isSaved = viewModel.isGroupBookmarked(group, bookmarkedIds)
                                            ReleaseGroupRow(
                                                group = group,
                                                isSelected = selectedGroup?.groupKey == group.groupKey,
                                                isBookmarked = isSaved,
                                                onClick = { viewModel.openTorrentDetail(group.toRepresentativeTorrentResult()) },
                                                onToggleSave = { viewModel.toggleGroupBookmark(group) }
                                            )
                                        }
                                    }
                                }
                            } else {
                                item(key = "search_home_ready_tip") {
                                    SearchStatusMessageCard(
                                        icon = Icons.Filled.Search,
                                        title = "Ready to search",
                                        subtitle = "Search across ${state.enabledSourcesCount} enabled sources or filter a single source from the dropdown.",
                                        testTag = "search_home_idle_card"
                                    )
                                }
                            }
                        }

                        SearchContentPhase.ALL_SOURCES_DISABLED -> {
                            item(key = "all_sources_disabled") {
                                SearchStatusMessageCard(
                                    icon = Icons.Outlined.Tune,
                                    title = "All search sources are disabled",
                                    subtitle = "Enable at least one indexer in Settings to run searches.",
                                    iconTint = telemetry.leecherAmber,
                                    primaryActionLabel = "Open Settings",
                                    onPrimaryAction = onOpenSettings,
                                    testTag = "search_state_all_disabled"
                                )
                            }
                        }

                        SearchContentPhase.SEARCHING_EMPTY -> {
                            item(key = "searching_empty") {
                                GlassCard(
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("search_state_searching_empty")
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp, horizontal = 20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier.size(30.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "Searching ${state.enabledSourcesCount} enabled sources…",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Results appear as soon as the first source responds.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        SearchContentPhase.ZERO_MATCHES -> {
                            item(key = "zero_matches") {
                                SearchStatusMessageCard(
                                    icon = Icons.Filled.SearchOff,
                                    title = "No matching releases found",
                                    subtitle = "Sources responded cleanly with no matches for \"${state.submittedQuery}\". Try broader keywords or another category.",
                                    primaryActionLabel = if (state.filters.hasNonDefaultFilters) "Reset Filters" else null,
                                    onPrimaryAction = if (state.filters.hasNonDefaultFilters) {
                                        { viewModel.resetFilters() }
                                    } else null,
                                    secondaryActionLabel = "Inspect Sources",
                                    onSecondaryAction = { showSourcesSheet = true },
                                    testTag = "search_state_zero_matches"
                                )
                            }
                        }

                        SearchContentPhase.FILTERED_OUT -> {
                            item(key = "filtered_out") {
                                SearchStatusMessageCard(
                                    icon = Icons.Filled.FilterList,
                                    title = "All results hidden by active filters",
                                    subtitle = "Matches were found, but your current category, quality, or source filters excluded them.",
                                    iconTint = MaterialTheme.colorScheme.primary,
                                    primaryActionLabel = "Reset Filters",
                                    onPrimaryAction = { viewModel.resetFilters() },
                                    secondaryActionLabel = "Adjust Filters",
                                    onSecondaryAction = { showFilterSheet = true },
                                    testTag = "search_state_filtered_out"
                                )
                            }
                        }

                        SearchContentPhase.TOTAL_FAILURE -> {
                            item(key = "total_failure") {
                                SearchStatusMessageCard(
                                    icon = Icons.Outlined.CloudOff,
                                    title = "All sources failed to respond",
                                    subtitle = "Enabled mirrors timed out, rate-limited, or returned errors. Retry, inspect source diagnostics, or open a site directly in HTML view.",
                                    iconTint = telemetry.deadRose,
                                    primaryActionLabel = "Retry All",
                                    onPrimaryAction = { viewModel.retryAllSearch() },
                                    secondaryActionLabel = "Inspect Sources",
                                    onSecondaryAction = { showSourcesSheet = true },
                                    testTag = "search_state_total_failure"
                                )
                            }
                        }

                        SearchContentPhase.STREAMING_RESULTS,
                        SearchContentPhase.PARTIAL_SUCCESS,
                        SearchContentPhase.COMPLETED_SUCCESS -> {
                            items(groups, key = { it.groupKey }) { group ->
                                val isSaved = viewModel.isGroupBookmarked(group, bookmarkedIds)
                                ReleaseGroupRow(
                                    group = group,
                                    isSelected = selectedGroup?.groupKey == group.groupKey,
                                    isBookmarked = isSaved,
                                    onClick = { viewModel.selectGroup(group) },
                                    onToggleSave = { viewModel.toggleGroupBookmark(group) }
                                )
                            }
                        }
                    }
                }
            }

            // Expanded-screen persistent side detail pane (>= 840.dp)
            val expandedSelection = selectedGroup
            if (useTwoPane && expandedSelection != null) {
                VerticalDivider(thickness = 0.75.dp, color = telemetry.cardBorder)
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .widthIn(min = 340.dp, max = 440.dp)
                        .fillMaxHeight()
                        .testTag("expanded_detail_pane")
                ) {
                    MagnetActionDetailContent(
                        group = expandedSelection,
                        isBookmarked = viewModel.isGroupBookmarked(expandedSelection, bookmarkedIds),
                        torrentBoxOpenMode = torrentBoxOpenMode,
                        showCloseButton = true,
                        onClose = { viewModel.dismissTorrentDetail() },
                        onRetryResolveMagnet = { viewModel.retryResolveSelectedMagnet() },
                        onOpenLocalClient = { magnet -> onOpenLocalClient(expandedSelection.title, magnet) },
                        onCopyMagnet = { magnet -> onCopyMagnet(expandedSelection.title, magnet) },
                        onToggleSave = { viewModel.toggleGroupBookmark(expandedSelection) },
                        onOpenSeedr = { magnet -> onOpenSeedr(expandedSelection.title, magnet) },
                        onOpenTorBox = { magnet -> onOpenTorBox(expandedSelection.title, magnet) },
                        onShareMagnet = { magnet -> onShareMagnet(expandedSelection.title, magnet) },
                        onOpenSourcePage = onOpenSourcePage
                    )
                }
            }
        }

        // Compact/medium screen modal bottom sheet for selected release
        val phoneSelection = selectedGroup
        if (!useTwoPane && phoneSelection != null) {
            MagnetActionModalSheet(
                group = phoneSelection,
                isBookmarked = viewModel.isGroupBookmarked(phoneSelection, bookmarkedIds),
                torrentBoxOpenMode = torrentBoxOpenMode,
                onDismiss = { viewModel.dismissTorrentDetail() },
                onRetryResolveMagnet = { viewModel.retryResolveSelectedMagnet() },
                onOpenLocalClient = { magnet -> onOpenLocalClient(phoneSelection.title, magnet) },
                onCopyMagnet = { magnet -> onCopyMagnet(phoneSelection.title, magnet) },
                onToggleSave = { viewModel.toggleGroupBookmark(phoneSelection) },
                onOpenSeedr = { magnet ->
                    viewModel.dismissTorrentDetail()
                    onOpenSeedr(phoneSelection.title, magnet)
                },
                onOpenTorBox = { magnet ->
                    viewModel.dismissTorrentDetail()
                    onOpenTorBox(phoneSelection.title, magnet)
                },
                onShareMagnet = { magnet -> onShareMagnet(phoneSelection.title, magnet) },
                onOpenSourcePage = { url ->
                    viewModel.dismissTorrentDetail()
                    onOpenSourcePage(url)
                }
            )
        }

        // Shared action sheet for locally parsed Pasted Magnet (all widths)
        val pasted = pastedMagnetGroup
        if (pasted != null) {
            MagnetActionModalSheet(
                group = pasted,
                isBookmarked = viewModel.isGroupBookmarked(pasted, bookmarkedIds),
                torrentBoxOpenMode = torrentBoxOpenMode,
                onDismiss = { viewModel.dismissPastedMagnetSheet() },
                onRetryResolveMagnet = {},
                onOpenLocalClient = { magnet -> onOpenLocalClient(pasted.title, magnet) },
                onCopyMagnet = { magnet -> onCopyMagnet(pasted.title, magnet) },
                onToggleSave = { viewModel.toggleGroupBookmark(pasted) },
                onOpenSeedr = { magnet ->
                    viewModel.dismissPastedMagnetSheet()
                    onOpenSeedr(pasted.title, magnet)
                },
                onOpenTorBox = { magnet ->
                    viewModel.dismissPastedMagnetSheet()
                    onOpenTorBox(pasted.title, magnet)
                },
                onShareMagnet = { magnet -> onShareMagnet(pasted.title, magnet) },
                onOpenSourcePage = { url ->
                    viewModel.dismissPastedMagnetSheet()
                    onOpenSourcePage(url)
                }
            )
        }

        if (showFilterSheet) {
            SearchFilterSortSheet(
                currentFilters = state.filters,
                onApply = { viewModel.applyFilters(it) },
                onReset = { viewModel.resetFilters() },
                onDismiss = { showFilterSheet = false }
            )
        }

        if (showSourcesSheet) {
            SourceDiagnosticsSheet(
                statuses = state.providerStatuses,
                openMode = websiteOpenMode,
                onRetrySource = { viewModel.retryProvider(it) },
                onOpenSourceSite = { st -> onOpenSourcePage(st.webSearchUrl.ifBlank { st.mirrorUsed }) },
                onOpenSettings = onOpenSettings,
                onDismiss = { showSourcesSheet = false }
            )
        }

        if (showPasteEditDialog) {
            AlertDialog(
                onDismissRequest = { showPasteEditDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("Paste or Edit Magnet Link") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Enter a valid magnet:?xt=urn:btih:... or urn:btmh:... URI.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = manualPasteDraft,
                            onValueChange = { manualPasteDraft = it },
                            placeholder = { Text("magnet:?xt=urn:btih:…") },
                            maxLines = 4,
                            textStyle = TelemetryMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_paste_magnet_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPasteEditDialog = false
                            viewModel.handlePasteMagnet(manualPasteDraft)
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("manual_paste_magnet_confirm")
                    ) {
                        Text("Inspect Magnet")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showPasteEditDialog = false },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
