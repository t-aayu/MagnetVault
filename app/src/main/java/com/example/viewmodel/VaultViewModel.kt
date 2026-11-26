package com.example.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.CloudSyncRepository
import com.example.data.indexers.IndexerAggregatorRepository
import com.example.data.local.ExportPreviewCounts
import com.example.data.local.ExportSelection
import com.example.data.local.LocalPrefsStore
import com.example.data.local.VaultJsonExporter
import com.example.data.local.db.BookmarkEntity
import com.example.data.local.db.BookmarkWithCollections
import com.example.data.local.db.CollectionWithCount
import com.example.data.local.db.MigrationStatusEntity
import com.example.data.sync.SyncSessionStatus
import com.example.domain.magnet.MagnetParser
import com.example.model.CanonicalReleaseGroup
import com.example.model.MagnetResolutionState
import com.example.model.MirrorConfig
import com.example.model.ProviderListing
import com.example.model.SearchHistoryRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VaultSortOption(val label: String) {
    RECENTLY_SAVED("Recently Saved"),
    SEEDERS("Seeders"),
    SIZE("Size"),
    TITLE("Title")
}

sealed class VaultUiEvent {
    data class ShowMessage(val message: String) : VaultUiEvent()
    data class BookmarkRemovedWithUndo(val localId: String, val title: String) : VaultUiEvent()
}

data class VaultFilterState(
    val query: String = "",
    val sortOption: VaultSortOption = VaultSortOption.RECENTLY_SAVED,
    val selectedCollectionId: String? = null
)

class VaultViewModel(
    private val cloudSyncRepo: CloudSyncRepository,
    private val prefsStore: LocalPrefsStore? = null,
    private val indexerRepo: IndexerAggregatorRepository? = null
) : ViewModel() {

    private val _events = MutableSharedFlow<VaultUiEvent>()
    val events: SharedFlow<VaultUiEvent> = _events.asSharedFlow()

    // Backwards-compatible text message stream
    val messages: SharedFlow<String> = MutableSharedFlow<String>().apply {
        viewModelScope.launch {
            _events.collect { ev ->
                if (ev is VaultUiEvent.ShowMessage) emit(ev.message)
            }
        }
    }

    private val _filterState = MutableStateFlow(VaultFilterState())
    val filterState: StateFlow<VaultFilterState> = _filterState.asStateFlow()

    private val _selectedSavedBookmarkLocalId = MutableStateFlow<String?>(null)
    private val _selectedSavedGroup = MutableStateFlow<CanonicalReleaseGroup?>(null)
    val selectedSavedGroup: StateFlow<CanonicalReleaseGroup?> = _selectedSavedGroup.asStateFlow()

    private var resolveJob: Job? = null

    val syncStatus: StateFlow<SyncSessionStatus> = cloudSyncRepo.syncStatus

    val migrationStatus: StateFlow<MigrationStatusEntity?> =
        cloudSyncRepo.observeMigrationStatus()
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val searchHistoryEnabled: StateFlow<Boolean> =
        cloudSyncRepo.observeSearchHistoryEnabled()
            .catch { emit(true) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    val mirrorConfigs: StateFlow<Map<com.example.model.IndexerSource, MirrorConfig>> =
        cloudSyncRepo.observeMirrorConfigs()
            .catch { emit(com.example.model.IndexerSource.entries.associateWith { MirrorConfig(provider = it) }) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = com.example.model.IndexerSource.entries.associateWith { MirrorConfig(provider = it) }
            )

    val bookmarksWithCollectionsState: StateFlow<UiState<List<BookmarkWithCollections>>> =
        cloudSyncRepo.observeBookmarksWithCollections()
            .map<List<BookmarkWithCollections>, UiState<List<BookmarkWithCollections>>> { UiState.Success(it) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(UiState.Error("Unable to load saved Vault items"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    val bookmarksState: StateFlow<UiState<List<com.example.model.BookmarkRecord>>> =
        cloudSyncRepo.observeBookmarks()
            .map<List<com.example.model.BookmarkRecord>, UiState<List<com.example.model.BookmarkRecord>>> {
                UiState.Success(it)
            }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(UiState.Error("Unable to load saved Vault items"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    val collections: StateFlow<List<CollectionWithCount>> =
        cloudSyncRepo.observeCollections()
            .catch { emit(emptyList()) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList()
            )

    val filteredBookmarks: StateFlow<List<BookmarkWithCollections>> = combine(
        bookmarksWithCollectionsState,
        _filterState
    ) { state, filter ->
        val all = (state as? UiState.Success)?.data ?: emptyList()
        val q = filter.query.trim()
        val filtered = all.filter { item ->
            val matchesQuery = q.isBlank() ||
                item.bookmark.title.contains(q, ignoreCase = true) ||
                item.bookmark.source.contains(q, ignoreCase = true) ||
                item.bookmark.canonicalHashHex.contains(q, ignoreCase = true)
            val matchesCollection = filter.selectedCollectionId == null ||
                item.collections.any { it.collectionId == filter.selectedCollectionId }
            matchesQuery && matchesCollection
        }
        when (filter.sortOption) {
            VaultSortOption.RECENTLY_SAVED -> filtered.sortedByDescending { it.bookmark.updatedAtEpochMs }
            VaultSortOption.SEEDERS -> filtered.sortedByDescending { it.bookmark.seeders }
            VaultSortOption.SIZE -> filtered.sortedByDescending { it.bookmark.sizeBytes }
            VaultSortOption.TITLE -> filtered.sortedBy { it.bookmark.title.lowercase() }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    val historyState: StateFlow<UiState<List<SearchHistoryRecord>>> =
        cloudSyncRepo.observeSearchHistory()
            .map<List<SearchHistoryRecord>, UiState<List<SearchHistoryRecord>>> { UiState.Success(it) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(UiState.Error("Unable to load search history"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    // --- FILTERS ---
    fun updateSavedQuery(query: String) {
        _filterState.update { it.copy(query = query) }
    }

    fun selectSortOption(option: VaultSortOption) {
        _filterState.update { it.copy(sortOption = option) }
    }

    fun selectCollectionFilter(collectionId: String?) {
        _filterState.update {
            it.copy(selectedCollectionId = if (it.selectedCollectionId == collectionId) null else collectionId)
        }
    }

    // --- SAVED ITEM SELECTION & LAZY MAGNET RESOLUTION ---
    fun openSavedBookmark(item: BookmarkWithCollections) {
        val torrent = item.bookmark.toTorrentResult()
        val listing = ProviderListing.fromTorrentResult(torrent)
        val group = CanonicalReleaseGroup.fromListings(
            groupKey = listing.canonicalIdentity.storageKey,
            identity = listing.canonicalIdentity,
            listings = listOf(listing)
        )
        _selectedSavedBookmarkLocalId.value = item.bookmark.localId
        _selectedSavedGroup.value = group
        if (group.magnetState !is MagnetResolutionState.Available) {
            resolveSavedGroupMagnet(item.bookmark, group)
        }
    }

    fun retryResolveSelectedSavedMagnet() {
        val group = _selectedSavedGroup.value ?: return
        val localId = _selectedSavedBookmarkLocalId.value ?: return
        viewModelScope.launch {
            val currentItems = (bookmarksWithCollectionsState.value as? UiState.Success)?.data.orEmpty()
            val entity = currentItems.firstOrNull { it.bookmark.localId == localId }?.bookmark
                ?: return@launch
            resolveSavedGroupMagnet(entity, group)
        }
    }

    private fun resolveSavedGroupMagnet(entity: BookmarkEntity, group: CanonicalReleaseGroup) {
        val repo = indexerRepo ?: return
        resolveJob?.cancel()
        val selectionKey = group.groupKey
        _selectedSavedGroup.value = group.copy(
            magnetState = MagnetResolutionState.Resolving(selectionKey, group.primarySource)
        )
        resolveJob = viewModelScope.launch {
            val res = repo.coordinator.resolveStandaloneMagnet(
                listing = group.representative,
                mirrorConfigs = mirrorConfigs.value
            )
            if (_selectedSavedBookmarkLocalId.value != entity.localId) return@launch
            res.onSuccess { magnet ->
                val verified = MagnetParser.extractVerifiedIdentities(magnet).firstOrNull()
                if (verified != null) {
                    val upgradedListing = group.representative.copy(
                        magnetUri = magnet,
                        canonicalIdentity = verified,
                        rawResult = group.representative.rawResult.copy(
                            magnetUri = magnet,
                            infoHash = verified.canonicalHex,
                            canonicalHashHex = verified.canonicalHex,
                            canonicalHashFamily = verified.family
                        )
                    )
                    val upgradedGroup = CanonicalReleaseGroup.fromListings(
                        groupKey = verified.storageKey,
                        identity = verified,
                        listings = listOf(upgradedListing),
                        overrideMagnetState = MagnetResolutionState.Available(
                            magnetUri = magnet,
                            identity = verified,
                            resolvedBySource = group.primarySource
                        )
                    )
                    _selectedSavedGroup.value = upgradedGroup
                    // Upgrades existing Room bookmark in place, preserving localId and collection memberships
                    cloudSyncRepo.saveOrUpgradeBookmark(
                        torrent = upgradedGroup.toRepresentativeTorrentResult(),
                        existingLocalIdHint = entity.localId
                    )
                } else {
                    _selectedSavedGroup.value = group.copy(
                        magnetState = MagnetResolutionState.Failed(
                            selectionKey,
                            "Resolved magnet did not contain a valid info hash"
                        )
                    )
                }
            }.onFailure { err ->
                _selectedSavedGroup.value = group.copy(
                    magnetState = MagnetResolutionState.Failed(
                        selectionKey,
                        err.localizedMessage ?: "Failed to resolve magnet link"
                    )
                )
            }
        }
    }

    fun dismissSavedDetail() {
        resolveJob?.cancel()
        resolveJob = null
        _selectedSavedBookmarkLocalId.value = null
        _selectedSavedGroup.value = null
    }

    // --- BOOKMARK REMOVAL & UNDO ---
    fun removeBookmark(localIdOrDocId: String, titleHint: String? = null) {
        viewModelScope.launch {
            val currentItems = (bookmarksWithCollectionsState.value as? UiState.Success)?.data.orEmpty()
            val matched = currentItems.firstOrNull {
                it.bookmark.localId == localIdOrDocId || it.bookmark.legacyDocId == localIdOrDocId
            }
            val displayTitle = titleHint ?: matched?.bookmark?.title ?: "Bookmark"
            cloudSyncRepo.removeBookmarkByLocalIdOrDocId(localIdOrDocId).onSuccess { removedLocalId ->
                if (_selectedSavedBookmarkLocalId.value == removedLocalId) {
                    dismissSavedDetail()
                }
                if (removedLocalId != null) {
                    _events.emit(VaultUiEvent.BookmarkRemovedWithUndo(removedLocalId, displayTitle))
                } else {
                    _events.emit(VaultUiEvent.ShowMessage("Bookmark removed"))
                }
            }.onFailure { err ->
                _events.emit(VaultUiEvent.ShowMessage(err.localizedMessage ?: "Failed to remove bookmark"))
            }
        }
    }

    fun undoRemoveBookmark(localId: String) {
        viewModelScope.launch {
            cloudSyncRepo.undoRemoveBookmark(localId).onSuccess { restored ->
                if (restored) {
                    _events.emit(VaultUiEvent.ShowMessage("Bookmark restored"))
                }
            }.onFailure { err ->
                _events.emit(VaultUiEvent.ShowMessage(err.localizedMessage ?: "Failed to restore bookmark"))
            }
        }
    }

    // --- COLLECTIONS (LOCAL ONLY) ---
    fun createCollection(name: String) {
        viewModelScope.launch {
            cloudSyncRepo.createCollection(name).onSuccess {
                _events.emit(VaultUiEvent.ShowMessage("Collection '${it.name}' created"))
            }.onFailure { err ->
                _events.emit(VaultUiEvent.ShowMessage(err.localizedMessage ?: "Could not create collection"))
            }
        }
    }

    fun renameCollection(collectionId: String, newName: String) {
        viewModelScope.launch {
            cloudSyncRepo.renameCollection(collectionId, newName).onSuccess {
                _events.emit(VaultUiEvent.ShowMessage("Collection renamed"))
            }.onFailure { err ->
                _events.emit(VaultUiEvent.ShowMessage(err.localizedMessage ?: "Could not rename collection"))
            }
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            if (_filterState.value.selectedCollectionId == collectionId) {
                _filterState.update { it.copy(selectedCollectionId = null) }
            }
            cloudSyncRepo.deleteCollection(collectionId).onSuccess {
                _events.emit(VaultUiEvent.ShowMessage("Collection deleted (bookmarks kept)"))
            }.onFailure { err ->
                _events.emit(VaultUiEvent.ShowMessage(err.localizedMessage ?: "Could not delete collection"))
            }
        }
    }

    fun setBookmarkCollections(bookmarkLocalId: String, collectionIds: Set<String>) {
        viewModelScope.launch {
            cloudSyncRepo.setBookmarkCollections(bookmarkLocalId, collectionIds).onFailure { err ->
                _events.emit(VaultUiEvent.ShowMessage(err.localizedMessage ?: "Could not update collections"))
            }
        }
    }

    // --- SEARCH HISTORY ---
    fun setSearchHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsStore?.setSearchHistoryEnabled(enabled)
            _events.emit(
                VaultUiEvent.ShowMessage(
                    if (enabled) "Search history recording enabled" else "Search history paused (existing entries kept)"
                )
            )
        }
    }

    fun deleteHistoryEntry(entryId: String) {
        viewModelScope.launch {
            cloudSyncRepo.deleteSearchHistoryEntry(entryId).onFailure { err ->
                _events.emit(VaultUiEvent.ShowMessage(err.localizedMessage ?: "Failed to delete history entry"))
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            cloudSyncRepo.clearAllSearchHistory().onSuccess {
                _events.emit(VaultUiEvent.ShowMessage("Search history cleared"))
            }.onFailure { err ->
                _events.emit(VaultUiEvent.ShowMessage(err.localizedMessage ?: "Failed to clear history"))
            }
        }
    }

    // --- MIGRATION RECOVERY & SYNC ---
    fun retryMigration() {
        viewModelScope.launch {
            val status = cloudSyncRepo.retryMigration()
            if (status.legacyImportCompleted) {
                _events.emit(VaultUiEvent.ShowMessage("Local Vault import recovered"))
            } else {
                _events.emit(VaultUiEvent.ShowMessage("Import recovery failed (${status.lastErrorCategory ?: "ERROR"})"))
            }
        }
    }

    fun syncNow(accountUid: String?) {
        val uid = accountUid?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val res = cloudSyncRepo.syncEngine.syncNowOrRetry(uid)
            val msg = when {
                res.actionNeededCategory != null -> "Sync needs attention (${res.actionNeededCategory})"
                res.hadTransientFailure -> "Offline or unreachable — queued ${res.remainingPendingCount} item(s)"
                else -> "Vault is up to date"
            }
            _events.emit(VaultUiEvent.ShowMessage(msg))
        }
    }

    // --- JSON EXPORT ---
    suspend fun loadExportPreviewCounts(): ExportPreviewCounts {
        val input = withContext(Dispatchers.IO) { cloudSyncRepo.buildExportInput() }
        return VaultJsonExporter.computePreviewCounts(input)
    }

    fun exportVaultToUri(
        contentResolver: ContentResolver,
        destinationUri: Uri?,
        selection: ExportSelection
    ) {
        if (destinationUri == null) {
            viewModelScope.launch { _events.emit(VaultUiEvent.ShowMessage("Export cancelled")) }
            return
        }
        viewModelScope.launch {
            val writeResult = withContext(Dispatchers.IO) {
                runCatching {
                    val input = cloudSyncRepo.buildExportInput()
                    val json = VaultJsonExporter.generateJson(input, selection)
                    val stream = contentResolver.openOutputStream(destinationUri)
                        ?: throw IllegalStateException("Unable to open selected file for writing")
                    VaultJsonExporter.writeJsonToStream(stream, json).getOrThrow()
                }
            }
            writeResult.onSuccess {
                _events.emit(VaultUiEvent.ShowMessage("Vault exported as JSON"))
            }.onFailure { err ->
                _events.emit(
                    VaultUiEvent.ShowMessage(
                        "Export failed: ${err.localizedMessage ?: "unable to write file"}"
                    )
                )
            }
        }
    }

    class Factory(
        private val cloudSyncRepo: CloudSyncRepository,
        private val prefsStore: LocalPrefsStore? = null,
        private val indexerRepo: IndexerAggregatorRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            VaultViewModel(cloudSyncRepo, prefsStore, indexerRepo) as T
    }
}
