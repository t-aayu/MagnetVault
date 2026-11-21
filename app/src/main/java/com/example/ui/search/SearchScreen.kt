package com.example.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.QualityTag
import com.example.model.SortOption
import com.example.model.TorrentCategory
import com.example.ui.components.ProviderStatusStrip
import com.example.ui.components.TorrentDetailBottomSheet
import com.example.ui.components.TorrentResultCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.util.IntentActions
import com.example.viewmodel.SearchViewModel

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    isSignedIn: Boolean,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val results by viewModel.displayedResults.collectAsStateWithLifecycle()
    val trending by viewModel.trendingResults.collectAsStateWithLifecycle()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val mirrorConfigs by viewModel.mirrorConfigs.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val telemetry = LocalTelemetryColors.current
    val enabledCount = mirrorConfigs.values.count { it.enabled }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(telemetry.headerGlow, Color.Transparent),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = size.width * 0.85f
                    )
                )
            }
    ) {
        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "$enabledCount active indexers • ${if (isSignedIn) "Cloud Sync" else "Local Mode"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = "Mirror & Indexer Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("theme_toggle_button")
            ) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    contentDescription = if (isDarkTheme) "Switch to light theme" else "Switch to dark theme",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Search Input Bar
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::updateQuery,
            placeholder = {
                Text(
                    text = stringResource(R.string.search_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearSearch() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                focusManager.clearFocus()
                                viewModel.executeSearch()
                            }
                            .testTag("search_submit_button")
                    ) {
                        Text(
                            text = "Go",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    viewModel.executeSearch()
                }
            ),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = telemetry.cardBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("search_query_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Category Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TorrentCategory.entries.forEach { category ->
                val selected = state.selectedCategory == category
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectCategory(category) },
                    label = { Text(category.label, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = telemetry.cardBorder
                    )
                )
            }
        }

        // Sort + Quality Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Sort by",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            SortOption.entries.forEach { sort ->
                val selected = state.selectedSort == sort
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectSort(sort) },
                    label = { Text(sort.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            QualityTag.entries.forEach { tag ->
                val selected = tag in state.selectedQualities
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.toggleQualityTag(tag) },
                    label = { Text(tag.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = telemetry.qualityPurpleContainer,
                        selectedLabelColor = telemetry.qualityPurple
                    )
                )
            }
        }

        if (state.isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (state.hasSearched) {
            ProviderStatusStrip(
                statuses = state.providerStatuses,
                onStatusClick = { st ->
                    val mirrorHost = st.mirrorUsed.removePrefix("https://").removePrefix("http://")
                    val detail = when {
                        st.isLoading -> "${st.source.displayName}: Searching…"
                        st.errorMessage != null -> "${st.source.displayName} error: ${st.errorMessage}"
                        else -> "${st.source.displayName}: ${st.resultCount} results via $mirrorHost"
                    }
                    onShowMessage(detail)
                }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!state.hasSearched) {
                if (recentSearches.isNotEmpty()) {
                    item(key = "recent_header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
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
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(20.dp),
                                    border = BorderStroke(1.dp, telemetry.cardBorder),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .clickable {
                                            focusManager.clearFocus()
                                            viewModel.runSearchFromHistory(
                                                entry.query,
                                                TorrentCategory.fromNameSafe(entry.category)
                                            )
                                        }
                                ) {
                                    Text(
                                        text = entry.query,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "trending_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.LocalFireDepartment,
                                contentDescription = null,
                                tint = telemetry.leecherAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.trending_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(
                            onClick = { viewModel.loadTrending() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh trending",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (state.isLoadingTrending) {
                    item(key = "trending_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Fetching live feeds from 1337x, EZTV, TPB, YTS & Nyaa…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (trending.isEmpty() && state.trendingError != null) {
                    item(key = "trending_error") {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, telemetry.cardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = state.trendingError.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { viewModel.loadTrending() }) {
                                        Text("Retry")
                                    }
                                    TextButton(onClick = onOpenSettings) {
                                        Text("Check Mirrors")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val filteredTrending = trending.filter { item ->
                        val catOk = state.selectedCategory == TorrentCategory.ALL || item.category == state.selectedCategory
                        val qualOk = state.selectedQualities.isEmpty() || state.selectedQualities.any { it in item.qualityTags }
                        catOk && qualOk
                    }
                    items(filteredTrending, key = { it.id }) { torrent ->
                        val isSaved = viewModel.isBookmarked(torrent) && bookmarkedIds.isNotEmpty()
                        TorrentResultCard(
                            torrent = torrent,
                            isBookmarked = isSaved,
                            isResolvingAction = state.resolvingActionTorrentId == torrent.id,
                            onClick = { viewModel.openTorrentDetail(torrent) },
                            onCopyMagnet = { viewModel.copyMagnetAndOptionallyOpen(torrent) },
                            onOpenSeedr = {
                                viewModel.copyMagnetAndOptionallyOpen(
                                    torrent = torrent,
                                    openBrowserUrl = IntentActions.SEEDR_WEB_URL,
                                    targetLabel = "Seedr.cc"
                                )
                            },
                            onOpenTorBox = {
                                viewModel.copyMagnetAndOptionallyOpen(
                                    torrent = torrent,
                                    openBrowserUrl = IntentActions.TORBOX_WEB_URL,
                                    targetLabel = "TorBox"
                                )
                            },
                            onToggleBookmark = { viewModel.toggleBookmark(torrent) }
                        )
                    }
                }
            } else {
                if (!state.isSearching && results.isEmpty()) {
                    item(key = "search_empty") {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, telemetry.cardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "No matching torrents found",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Try clearing quality filters, changing category to All, or running Mirror Health Check in Settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                TextButton(onClick = onOpenSettings) {
                                    Text("Open Mirror Health Checker")
                                }
                            }
                        }
                    }
                } else {
                    items(results, key = { "${it.source.name}_${it.id}_${it.infoHash}" }) { torrent ->
                        val isSaved = viewModel.isBookmarked(torrent) && bookmarkedIds.isNotEmpty()
                        TorrentResultCard(
                            torrent = torrent,
                            isBookmarked = isSaved,
                            isResolvingAction = state.resolvingActionTorrentId == torrent.id,
                            onClick = { viewModel.openTorrentDetail(torrent) },
                            onCopyMagnet = { viewModel.copyMagnetAndOptionallyOpen(torrent) },
                            onOpenSeedr = {
                                viewModel.copyMagnetAndOptionallyOpen(
                                    torrent = torrent,
                                    openBrowserUrl = IntentActions.SEEDR_WEB_URL,
                                    targetLabel = "Seedr.cc"
                                )
                            },
                            onOpenTorBox = {
                                viewModel.copyMagnetAndOptionallyOpen(
                                    torrent = torrent,
                                    openBrowserUrl = IntentActions.TORBOX_WEB_URL,
                                    targetLabel = "TorBox"
                                )
                            },
                            onToggleBookmark = { viewModel.toggleBookmark(torrent) }
                        )
                    }
                }
            }
        }
    }

    val selected = state.selectedTorrent
    if (selected != null) {
        TorrentDetailBottomSheet(
            torrent = selected,
            isBookmarked = viewModel.isBookmarked(selected) && bookmarkedIds.isNotEmpty(),
            isResolvingMagnet = state.isResolvingMagnet,
            magnetResolveError = state.magnetResolveError,
            onDismiss = { viewModel.dismissTorrentDetail() },
            onRetryResolveMagnet = { viewModel.retryResolveSelectedMagnet() },
            onCopyMagnet = { magnet ->
                IntentActions.copyToClipboard(context, selected.title, magnet)
                onShowMessage("Magnet link copied to clipboard")
            },
            onOpenSeedrBrowser = { magnet ->
                if (!magnet.isNullOrBlank()) {
                    IntentActions.copyToClipboard(context, selected.title, magnet)
                    onShowMessage("Magnet copied — opening Seedr.cc in browser")
                } else {
                    onShowMessage("Opening Seedr.cc in browser")
                }
                IntentActions.openUrlInBrowser(context, IntentActions.SEEDR_WEB_URL)
            },
            onOpenTorBoxBrowser = { magnet ->
                if (!magnet.isNullOrBlank()) {
                    IntentActions.copyToClipboard(context, selected.title, magnet)
                    onShowMessage("Magnet copied — opening TorBox in browser")
                } else {
                    onShowMessage("Opening TorBox in browser")
                }
                IntentActions.openUrlInBrowser(context, IntentActions.TORBOX_WEB_URL)
            },
            onOpenLocalTorrentApp = { magnet ->
                val opened = IntentActions.openMagnetInLocalApp(context, magnet)
                if (!opened) {
                    IntentActions.copyToClipboard(context, selected.title, magnet)
                    onShowMessage("No local torrent app found—magnet copied to clipboard")
                }
            },
            onShareMagnet = { magnet ->
                IntentActions.shareText(context, selected.title, "${selected.title}\n$magnet")
            },
            onToggleBookmark = { viewModel.toggleBookmark(selected) }
        )
    }
}
