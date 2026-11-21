package com.example.ui.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import com.example.ui.components.TorrentResultCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.viewmodel.UiState
import com.example.viewmodel.VaultViewModel

@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    isSignedIn: Boolean,
    resolvingActionTorrentId: String?,
    onInspectTorrent: (TorrentResult) -> Unit,
    onCopyMagnet: (TorrentResult) -> Unit,
    onOpenSeedr: (TorrentResult) -> Unit,
    onOpenTorBox: (TorrentResult) -> Unit,
    onReplaySearch: (String, TorrentCategory) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val bookmarksState by viewModel.bookmarksState.collectAsStateWithLifecycle()
    val historyState by viewModel.historyState.collectAsStateWithLifecycle()
    val telemetry = LocalTelemetryColors.current

    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { onShowMessage(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.nav_vault),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isSignedIn) Icons.Filled.CloudDone else Icons.Filled.PhoneAndroid,
                        contentDescription = if (isSignedIn) "Cloud synced" else "Stored locally",
                        tint = if (isSignedIn) telemetry.seederGreen else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isSignedIn) {
                            "Synced across devices via Google Sign-In"
                        } else {
                            "Saved locally on this device (Local Mode)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSignedIn) telemetry.seederGreen else MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (selectedTab == 1 && historyState is UiState.Success && (historyState as UiState.Success).data.isNotEmpty()) {
                TextButton(
                    onClick = { viewModel.clearAllHistory() },
                    modifier = Modifier.testTag("clear_history_button")
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All")
                }
            }
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            listOf("Bookmarks", "Search History").forEachIndexed { idx, label ->
                SegmentedButton(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = 2),
                    modifier = Modifier.testTag("vault_tab_$idx")
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (selectedTab == 0) {
            when (val bmState = bookmarksState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                is UiState.Error -> {
                    VaultEmptyOrError(
                        title = "Storage Error",
                        subtitle = bmState.message,
                        isHistory = false
                    )
                }
                is UiState.Success -> {
                    if (bmState.data.isEmpty()) {
                        VaultEmptyOrError(
                            title = stringResource(R.string.empty_bookmarks_title),
                            subtitle = stringResource(R.string.empty_bookmarks_subtitle),
                            isHistory = false
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(bmState.data, key = { it.id }) { record ->
                                val torrent = remember(record) { record.toTorrentResult() }
                                TorrentResultCard(
                                    torrent = torrent,
                                    isBookmarked = true,
                                    isResolvingAction = resolvingActionTorrentId == torrent.id,
                                    onClick = { onInspectTorrent(torrent) },
                                    onCopyMagnet = { onCopyMagnet(torrent) },
                                    onOpenSeedr = { onOpenSeedr(torrent) },
                                    onOpenTorBox = { onOpenTorBox(torrent) },
                                    onToggleBookmark = { viewModel.removeBookmark(record.id) }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            when (val histState = historyState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                is UiState.Error -> {
                    VaultEmptyOrError(
                        title = "Storage Error",
                        subtitle = histState.message,
                        isHistory = true
                    )
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
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(histState.data, key = { it.id }) { entry ->
                                val cat = TorrentCategory.fromNameSafe(entry.category)
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, telemetry.cardBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onReplaySearch(entry.query, cat) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Search,
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
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Delete search entry",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
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

@Composable
private fun VaultEmptyOrError(title: String, subtitle: String, isHistory: Boolean) {
    val telemetry = LocalTelemetryColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, telemetry.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (isHistory) Icons.Filled.History else Icons.Filled.BookmarkBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
