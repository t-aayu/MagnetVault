package com.example.ui.vault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.ExportPreviewCounts
import com.example.data.local.ExportSelection
import com.example.data.local.db.BookmarkWithCollections
import com.example.data.sync.SyncSessionStatus
import com.example.model.TorrentCategory
import com.example.model.WebsiteOpenMode
import com.example.ui.components.QualityBadge
import com.example.ui.components.SourcePill
import com.example.ui.components.SwarmHealthBadge
import com.example.ui.handoff.MagnetActionModalSheet
import com.example.ui.theme.GlassCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle
import com.example.ui.theme.ambientGlassBackdrop
import com.example.viewmodel.UiState
import com.example.viewmodel.VaultSortOption
import com.example.viewmodel.VaultUiEvent
import com.example.viewmodel.VaultViewModel
import kotlinx.coroutines.launch

@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    currentUserUid: String?,
    torrentBoxOpenMode: WebsiteOpenMode,
    onOpenLocalClient: (title: String, magnetUri: String) -> Unit,
    onCopyMagnet: (title: String, magnetUri: String) -> Unit,
    onOpenSeedr: (title: String, magnetUri: String?) -> Unit,
    onOpenTorBox: (title: String, magnetUri: String?) -> Unit,
    onShareMagnet: (title: String, magnetUri: String) -> Unit,
    onOpenSourcePage: (url: String) -> Unit,
    onReplaySearch: (String, TorrentCategory) -> Unit,
    onShowMessage: (String) -> Unit,
    onShowUndoMessage: (message: String, actionLabel: String, onUndo: () -> Unit) -> Unit
) {
    val bookmarksState by viewModel.bookmarksWithCollectionsState.collectAsStateWithLifecycle()
    val filteredBookmarks by viewModel.filteredBookmarks.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val historyState by viewModel.historyState.collectAsStateWithLifecycle()
    val searchHistoryEnabled by viewModel.searchHistoryEnabled.collectAsStateWithLifecycle()
    val selectedSavedGroup by viewModel.selectedSavedGroup.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val migrationStatus by viewModel.migrationStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val telemetry = LocalTelemetryColors.current

    // Vault opens directly to Saved (tab 0)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showManageCollections by rememberSaveable { mutableStateOf(false) }
    var assignTargetLocalId by rememberSaveable { mutableStateOf<String?>(null) }
    var showClearHistoryConfirm by rememberSaveable { mutableStateOf(false) }
    var exportPreviewCounts by remember { mutableStateOf<ExportPreviewCounts?>(null) }
    var pendingExportSelection by remember { mutableStateOf(ExportSelection()) }
    var malformedNoticeDismissed by rememberSaveable { mutableStateOf(false) }

    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        viewModel.exportVaultToUri(
            contentResolver = context.contentResolver,
            destinationUri = uri,
            selection = pendingExportSelection
        )
    }

    // Back priority inside Vault
    BackHandler(enabled = selectedSavedGroup != null) {
        viewModel.dismissSavedDetail()
    }
    BackHandler(enabled = assignTargetLocalId != null) {
        assignTargetLocalId = null
    }
    BackHandler(enabled = showManageCollections) {
        showManageCollections = false
    }
    BackHandler(enabled = showClearHistoryConfirm) {
        showClearHistoryConfirm = false
    }
    BackHandler(enabled = exportPreviewCounts != null) {
        exportPreviewCounts = null
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VaultUiEvent.ShowMessage -> onShowMessage(event.message)
                is VaultUiEvent.BookmarkRemovedWithUndo -> {
                    onShowUndoMessage(
                        "Removed '${event.title.take(36)}'",
                        "Undo"
                    ) {
                        viewModel.undoRemoveBookmark(event.localId)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .ambientGlassBackdrop(telemetry)
    ) {
        // Header + Sync status pill + Collections / Export JSON actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.nav_vault),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                val (statusIcon, statusText, statusTint) = when (val st = syncStatus) {
                    SyncSessionStatus.LocalOnly -> Triple(
                        Icons.Outlined.PhoneAndroid,
                        "Local-only Vault on this device",
                        MaterialTheme.colorScheme.tertiary
                    )
                    is SyncSessionStatus.Synced -> Triple(
                        Icons.Filled.CloudDone,
                        "Cloud sync up to date",
                        telemetry.seederGreen
                    )
                    is SyncSessionStatus.Pending -> Triple(
                        Icons.Filled.Sync,
                        if (st.isSyncing) "Syncing ${st.pendingCount} change(s)…" else "${st.pendingCount} change(s) pending",
                        MaterialTheme.colorScheme.primary
                    )
                    is SyncSessionStatus.Offline -> Triple(
                        Icons.Filled.CloudOff,
                        "Offline • ${st.pendingCount} queued",
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is SyncSessionStatus.ActionNeeded -> Triple(
                        Icons.Filled.WarningAmber,
                        "Sync needs attention (${st.errorCategory})",
                        telemetry.deadRose
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !currentUserUid.isNullOrBlank() && syncStatus !is SyncSessionStatus.LocalOnly) {
                            viewModel.syncNow(currentUserUid)
                        }
                        .testTag("vault_sync_status_indicator")
                ) {
                    Icon(statusIcon, contentDescription = null, tint = statusTint, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { showManageCollections = true },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("vault_manage_collections_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = stringResource(R.string.vault_collections),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            exportPreviewCounts = viewModel.loadExportPreviewCounts()
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("vault_export_json_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.vault_export_json),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Legacy Migration Error or Malformed Isolation Banner (never leaks raw record contents)
        val mig = migrationStatus
        if (mig != null && !mig.legacyImportCompleted) {
            GlassCard(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, telemetry.deadRose.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("vault_migration_error_banner")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = telemetry.deadRose, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Previous Vault import did not finish (${mig.lastErrorCategory ?: "RETRY"}). Tap Retry to recover.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.retryMigration() },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("migration_retry_button")
                    ) {
                        Text("Retry")
                    }
                }
            }
        } else if (mig != null && mig.totalMalformedCount > 0 && !malformedNoticeDismissed) {
            GlassCard(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, telemetry.leecherAmber.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("vault_migration_malformed_banner")
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Skipped ${mig.totalMalformedCount} corrupted legacy entry(s) (B:${mig.malformedBookmarksCount}, H:${mig.malformedHistoryCount}, S:${mig.malformedSitesCount}, M:${mig.malformedMirrorsCount}).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { malformedNoticeDismissed = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss import notice", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        val segmentedColors = SegmentedButtonDefaults.colors(
            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            activeBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            inactiveContainerColor = telemetry.glassSurface,
            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            inactiveBorderColor = telemetry.cardBorder.copy(alpha = 0.7f)
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            listOf(
                stringResource(R.string.vault_tab_saved),
                stringResource(R.string.vault_tab_history)
            ).forEachIndexed { idx, label ->
                SegmentedButton(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    colors = segmentedColors,
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = 2),
                    modifier = Modifier.testTag("vault_tab_$idx")
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (selectedTab == 0) {
            // SAVED TAB
            Column(modifier = Modifier.fillMaxSize()) {
                // Search within Saved Vault
                OutlinedTextField(
                    value = filterState.query,
                    onValueChange = viewModel::updateSavedQuery,
                    placeholder = { Text("Filter saved releases or hash…", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (filterState.query.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.updateSavedQuery("") },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear filter", modifier = Modifier.size(17.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = telemetry.glassSurfaceElevated,
                        unfocusedContainerColor = telemetry.glassSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = telemetry.cardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("vault_saved_search_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Sort chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VaultSortOption.entries.forEach { option ->
                        val selected = filterState.sortOption == option
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.selectSortOption(option) },
                            label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("vault_sort_${option.name}")
                        )
                    }
                }

                // Local-only Collections filter bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = filterState.selectedCollectionId == null,
                        onClick = { viewModel.selectCollectionFilter(null) },
                        label = { Text("All Saved") },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("vault_collection_chip_all")
                    )
                    collections.forEach { col ->
                        val selected = filterState.selectedCollectionId == col.collectionId
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.selectCollectionFilter(col.collectionId) },
                            label = { Text("${col.name} (${col.bookmarkCount})") },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("vault_collection_chip_${col.collectionId}")
                        )
                    }
                    Surface(
                        color = telemetry.glassSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.75.dp, telemetry.cardBorder),
                        modifier = Modifier.testTag("collections_local_only_badge")
                    ) {
                        Text(
                            text = "Collections: local-only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                when (val bmState = bookmarksState) {
                    is UiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
                        }
                    }
                    is UiState.Error -> {
                        VaultEmptyOrError(title = "Storage Error", subtitle = bmState.message, isHistory = false)
                    }
                    is UiState.Success -> {
                        if (bmState.data.isEmpty()) {
                            VaultEmptyOrError(
                                title = stringResource(R.string.empty_bookmarks_title),
                                subtitle = stringResource(R.string.empty_bookmarks_subtitle),
                                isHistory = false
                            )
                        } else if (filteredBookmarks.isEmpty()) {
                            VaultEmptyOrError(
                                title = "No matching saved releases",
                                subtitle = "Clear your filter text or switch the active collection chip.",
                                isHistory = false
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("vault_saved_list"),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filteredBookmarks, key = { it.bookmark.localId }) { item ->
                                    SavedBookmarkRow(
                                        item = item,
                                        onOpen = { viewModel.openSavedBookmark(item) },
                                        onAssignCollections = { assignTargetLocalId = item.bookmark.localId },
                                        onRemove = { viewModel.removeBookmark(item.bookmark.localId, item.bookmark.title) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // HISTORY TAB
            Column(modifier = Modifier.fillMaxSize()) {
                GlassCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.pause_history_recording),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (!searchHistoryEnabled) {
                                    "Paused — existing entries remain available"
                                } else {
                                    "Submitted searches are saved automatically"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = !searchHistoryEnabled,
                            onCheckedChange = { paused -> viewModel.setSearchHistoryEnabled(!paused) },
                            modifier = Modifier.testTag("vault_pause_history_switch")
                        )
                        val hasHistory = historyState is UiState.Success && (historyState as UiState.Success).data.isNotEmpty()
                        if (hasHistory) {
                            Spacer(modifier = Modifier.width(6.dp))
                            OutlinedButton(
                                onClick = { showClearHistoryConfirm = true },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("clear_history_button")
                            ) {
                                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear All")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                when (val histState = historyState) {
                    is UiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
                        }
                    }
                    is UiState.Error -> {
                        VaultEmptyOrError(title = "Storage Error", subtitle = histState.message, isHistory = true)
                    }
                    is UiState.Success -> {
                        if (histState.data.isEmpty()) {
                            VaultEmptyOrError(
                                title = stringResource(R.string.empty_history_title),
                                subtitle = stringResource(R.string.empty_history_subtitle),
                                isHistory = true
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("vault_history_list"),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(histState.data, key = { it.id }) { entry ->
                                    val cat = TorrentCategory.fromNameSafe(entry.category)
                                    GlassCard(
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { onReplaySearch(entry.query, cat) }
                                            .testTag("history_entry_${entry.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Search,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = entry.query,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "Category: ${cat.label}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(
                                                onClick = { viewModel.deleteHistoryEntry(entry.id) },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .testTag("history_delete_${entry.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Close,
                                                    contentDescription = "Delete search entry",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(17.dp)
                                                )
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
    }

    // Shared MagnetActionModalSheet for Saved releases (supports lazy resolution + upgrade)
    val sheetGroup = selectedSavedGroup
    if (sheetGroup != null) {
        val allSaved = (bookmarksState as? UiState.Success)?.data.orEmpty()
        val matchedItem = allSaved.firstOrNull {
            it.bookmark.canonicalKey == sheetGroup.groupKey ||
                it.bookmark.legacyDocId == sheetGroup.representative.rawResult.id
        }
        MagnetActionModalSheet(
            group = sheetGroup,
            isBookmarked = true,
            torrentBoxOpenMode = torrentBoxOpenMode,
            onDismiss = { viewModel.dismissSavedDetail() },
            onRetryResolveMagnet = { viewModel.retryResolveSelectedSavedMagnet() },
            onOpenLocalClient = { magnet -> onOpenLocalClient(sheetGroup.title, magnet) },
            onCopyMagnet = { magnet -> onCopyMagnet(sheetGroup.title, magnet) },
            onToggleSave = {
                matchedItem?.let {
                    viewModel.removeBookmark(it.bookmark.localId, it.bookmark.title)
                }
                viewModel.dismissSavedDetail()
            },
            onOpenSeedr = { magnet ->
                viewModel.dismissSavedDetail()
                onOpenSeedr(sheetGroup.title, magnet)
            },
            onOpenTorBox = { magnet ->
                viewModel.dismissSavedDetail()
                onOpenTorBox(sheetGroup.title, magnet)
            },
            onShareMagnet = { magnet -> onShareMagnet(sheetGroup.title, magnet) },
            onOpenSourcePage = { url ->
                viewModel.dismissSavedDetail()
                onOpenSourcePage(url)
            }
        )
    }

    if (showManageCollections) {
        ManageCollectionsDialog(
            collections = collections,
            onCreateCollection = viewModel::createCollection,
            onRenameCollection = viewModel::renameCollection,
            onDeleteCollection = viewModel::deleteCollection,
            onDismiss = { showManageCollections = false }
        )
    }

    val targetId = assignTargetLocalId
    if (targetId != null) {
        val allSaved = (bookmarksState as? UiState.Success)?.data.orEmpty()
        val item = allSaved.firstOrNull { it.bookmark.localId == targetId }
        if (item != null) {
            AssignCollectionsDialog(
                item = item,
                allCollections = collections,
                onCreateAndAssign = { name -> viewModel.createCollection(name) },
                onSaveMembership = { ids -> viewModel.setBookmarkCollections(targetId, ids) },
                onDismiss = { assignTargetLocalId = null }
            )
        }
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.clear_history_confirm_title)) },
            text = {
                Text(
                    text = stringResource(R.string.clear_history_confirm_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("confirm_clear_history_dialog")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearHistoryConfirm = false
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clear_history_confirm_button")
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearHistoryConfirm = false },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clear_history_cancel_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val preview = exportPreviewCounts
    if (preview != null) {
        VaultExportDialog(
            counts = preview,
            onExportConfirmed = { selection ->
                pendingExportSelection = selection
                exportPreviewCounts = null
                exportDocumentLauncher.launch("magnetvault-vault-v2.json")
            },
            onDismiss = { exportPreviewCounts = null }
        )
    }
}

@Composable
private fun SavedBookmarkRow(
    item: BookmarkWithCollections,
    onOpen: () -> Unit,
    onAssignCollections: () -> Unit,
    onRemove: () -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    val b = item.bookmark
    val torrent = remember(b) { b.toTorrentResult() }
    val resolved = b.hasResolvedMagnet()

    Surface(
        color = telemetry.glassSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, telemetry.cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = false) {}
            .testTag("vault_bookmark_card_${b.localId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = b.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SourcePill(torrent.source)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = "Seeders",
                            tint = if (b.seeders > 0) telemetry.seederGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = (b.knownSeeders ?: b.seeders).toString(),
                            style = TelemetryMonoStyle,
                            color = if (b.seeders > 0) telemetry.seederGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = b.sizeLabel,
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!resolved) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.testTag("unresolved_badge_${b.localId}")
                        ) {
                            Text(
                                text = "Resolve on tap",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        SwarmHealthBadge(torrent.swarmHealth)
                    }
                }

                val tags = torrent.qualityTags.take(2)
                if (tags.isNotEmpty() || item.collections.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        tags.forEach { tag -> QualityBadge(tag) }
                        item.collections.forEach { col ->
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.testTag("bookmark_chip_${b.localId}_${col.collectionId}")
                            ) {
                                Text(
                                    text = col.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            IconButton(
                onClick = onAssignCollections,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("bookmark_collections_${b.localId}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = "Assign collections for ${b.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("vault_remove_${b.localId}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Remove ${b.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun VaultEmptyOrError(title: String, subtitle: String, isHistory: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (isHistory) Icons.Outlined.History else Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
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
            }
        }
    }
}
