package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.CloudSyncRepository
import com.example.data.indexers.IndexerAggregatorRepository
import com.example.model.IndexerSearchStatus
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.QualityAndHealthParser
import com.example.model.QualityTag
import com.example.model.SearchHistoryRecord
import com.example.model.SortOption
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchScreenState(
    val query: String = "",
    val hasSearched: Boolean = false,
    val selectedCategory: TorrentCategory = TorrentCategory.ALL,
    val selectedSort: SortOption = SortOption.SEEDERS,
    val selectedQualities: Set<QualityTag> = emptySet(),
    val isSearching: Boolean = false,
    val isLoadingTrending: Boolean = true,
    val trendingError: String? = null,
    val providerStatuses: List<IndexerSearchStatus> = emptyList(),
    val selectedTorrent: TorrentResult? = null,
    val isResolvingMagnet: Boolean = false,
    val magnetResolveError: String? = null,
    val resolvingActionTorrentId: String? = null
)

sealed class SearchUiEvent {
    data class ShowMessage(val message: String) : SearchUiEvent()
    data class MagnetReady(
        val magnetUri: String,
        val title: String,
        val openBrowserUrl: String? = null,
        val targetLabel: String? = null
    ) : SearchUiEvent()
}

class SearchViewModel(
    private val indexerRepo: IndexerAggregatorRepository,
    private val cloudSyncRepo: CloudSyncRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchScreenState())
    val uiState: StateFlow<SearchScreenState> = _uiState.asStateFlow()

    private val _rawResults = MutableStateFlow<List<TorrentResult>>(emptyList())
    private val _trendingResults = MutableStateFlow<List<TorrentResult>>(emptyList())
    val trendingResults: StateFlow<List<TorrentResult>> = _trendingResults.asStateFlow()

    private val _events = MutableSharedFlow<SearchUiEvent>()
    val events: SharedFlow<SearchUiEvent> = _events.asSharedFlow()

    private var activeSearchJob: Job? = null

    val mirrorConfigs: StateFlow<Map<IndexerSource, MirrorConfig>> =
        cloudSyncRepo.observeMirrorConfigs()
            .catch { error ->
                val fsErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && error is CancellationException) throw error
                emit(IndexerSource.entries.associateWith { MirrorConfig(provider = it) })
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = IndexerSource.entries.associateWith { MirrorConfig(provider = it) }
            )

    val bookmarkedIds: StateFlow<Set<String>> =
        cloudSyncRepo.observeBookmarks()
            .catch { error ->
                val fsErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && error is CancellationException) throw error
                emit(emptyList())
            }
            .combine(MutableStateFlow(Unit)) { list, _ -> list.map { it.id }.toSet() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptySet()
            )

    val recentSearches: StateFlow<List<SearchHistoryRecord>> =
        cloudSyncRepo.observeSearchHistory()
            .catch { error ->
                val fsErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && error is CancellationException) throw error
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList()
            )

    val displayedResults: StateFlow<List<TorrentResult>> = combine(
        _rawResults,
        _uiState
    ) { raw, state ->
        val filtered = raw.filter { item ->
            val categoryMatches = state.selectedCategory == TorrentCategory.ALL ||
                item.category == state.selectedCategory ||
                item.category == TorrentCategory.OTHER
            val qualityMatches = state.selectedQualities.isEmpty() ||
                state.selectedQualities.any { it in item.qualityTags }
            categoryMatches && qualityMatches
        }
        when (state.selectedSort) {
            SortOption.SEEDERS -> filtered.sortedByDescending { it.seeders }
            SortOption.LEECHERS -> filtered.sortedByDescending { it.leechers }
            SortOption.UPLOAD_DATE -> filtered.sortedByDescending { it.uploadEpochSeconds }
            SortOption.SIZE -> filtered.sortedByDescending { it.sizeBytes }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        loadTrending()
    }

    fun loadTrending() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTrending = true, trendingError = null) }
            try {
                val items = indexerRepo.fetchTrending(mirrorConfigs.value)
                _trendingResults.value = items
                _uiState.update {
                    it.copy(
                        isLoadingTrending = false,
                        trendingError = if (items.isEmpty()) "Could not reach trending mirrors right now. Check Mirror Health in Settings." else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingTrending = false,
                        trendingError = e.localizedMessage ?: "Failed to load trending torrents"
                    )
                }
            }
        }
    }

    fun updateQuery(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
        if (newQuery.isBlank() && _uiState.value.hasSearched) {
            clearSearch()
        }
    }

    fun clearSearch() {
        activeSearchJob?.cancel()
        _rawResults.value = emptyList()
        _uiState.update {
            it.copy(
                query = "",
                hasSearched = false,
                isSearching = false,
                providerStatuses = emptyList()
            )
        }
    }

    fun selectCategory(category: TorrentCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
        if (_uiState.value.hasSearched && _uiState.value.query.isNotBlank()) {
            executeSearch()
        }
    }

    fun selectSort(sort: SortOption) {
        _uiState.update { it.copy(selectedSort = sort) }
    }

    fun toggleQualityTag(tag: QualityTag) {
        _uiState.update { state ->
            val updated = if (tag in state.selectedQualities) {
                state.selectedQualities - tag
            } else {
                state.selectedQualities + tag
            }
            state.copy(selectedQualities = updated)
        }
    }

    fun runSearchFromHistory(query: String, category: TorrentCategory) {
        _uiState.update { it.copy(query = query, selectedCategory = category) }
        executeSearch()
    }

    fun executeSearch() {
        val currentQuery = _uiState.value.query.trim()
        if (currentQuery.isBlank()) return
        val category = _uiState.value.selectedCategory
        val sort = _uiState.value.selectedSort

        activeSearchJob?.cancel()
        activeSearchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    hasSearched = true,
                    isSearching = true,
                    providerStatuses = emptyList()
                )
            }
            launch { cloudSyncRepo.recordSearchQuery(currentQuery, category) }

            val statusMap = LinkedHashMap<IndexerSource, IndexerSearchStatus>()
            val aggregated = indexerRepo.searchAll(
                query = currentQuery,
                category = category,
                sortOption = sort,
                mirrorConfigs = mirrorConfigs.value,
                onProviderProgress = { status ->
                    synchronized(statusMap) {
                        statusMap[status.source] = status
                        _uiState.update { it.copy(providerStatuses = statusMap.values.toList()) }
                    }
                }
            )
            _rawResults.value = aggregated.results
            _uiState.update {
                it.copy(
                    isSearching = false,
                    providerStatuses = aggregated.statuses
                )
            }
        }
    }

    fun openTorrentDetail(torrent: TorrentResult) {
        _uiState.update {
            it.copy(
                selectedTorrent = torrent,
                isResolvingMagnet = torrent.magnetUri.isNullOrBlank(),
                magnetResolveError = null
            )
        }
        if (torrent.magnetUri.isNullOrBlank()) {
            resolveSelectedMagnet(torrent)
        }
    }

    fun retryResolveSelectedMagnet() {
        val current = _uiState.value.selectedTorrent ?: return
        resolveSelectedMagnet(current)
    }

    private fun resolveSelectedMagnet(torrent: TorrentResult) {
        viewModelScope.launch {
            _uiState.update { it.copy(isResolvingMagnet = true, magnetResolveError = null) }
            val result = indexerRepo.resolveMagnet(torrent, mirrorConfigs.value)
            result.onSuccess { magnet ->
                val updated = cacheResolvedMagnet(torrent, magnet)
                _uiState.update { state ->
                    state.copy(
                        selectedTorrent = if (state.selectedTorrent?.id == torrent.id) updated else state.selectedTorrent,
                        isResolvingMagnet = false,
                        magnetResolveError = null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isResolvingMagnet = false,
                        magnetResolveError = err.localizedMessage ?: "Failed to extract magnet URI"
                    )
                }
            }
        }
    }

    fun dismissTorrentDetail() {
        _uiState.update {
            it.copy(
                selectedTorrent = null,
                isResolvingMagnet = false,
                magnetResolveError = null
            )
        }
    }

    /**
     * Resolves magnet link (if lazy, e.g. 1337x), copies it via event, and optionally launches
     * Seedr.cc or TorBox in the default browser so the user can paste right away.
     */
    fun copyMagnetAndOptionallyOpen(
        torrent: TorrentResult,
        openBrowserUrl: String? = null,
        targetLabel: String? = null
    ) {
        viewModelScope.launch {
            val readyMagnet = torrent.magnetUri
            if (!readyMagnet.isNullOrBlank()) {
                _events.emit(
                    SearchUiEvent.MagnetReady(
                        magnetUri = readyMagnet,
                        title = torrent.title,
                        openBrowserUrl = openBrowserUrl,
                        targetLabel = targetLabel
                    )
                )
                return@launch
            }
            _uiState.update { it.copy(resolvingActionTorrentId = torrent.id) }
            val resolved = indexerRepo.resolveMagnet(torrent, mirrorConfigs.value)
            _uiState.update { it.copy(resolvingActionTorrentId = null) }
            resolved.onSuccess { magnet ->
                cacheResolvedMagnet(torrent, magnet)
                _events.emit(
                    SearchUiEvent.MagnetReady(
                        magnetUri = magnet,
                        title = torrent.title,
                        openBrowserUrl = openBrowserUrl,
                        targetLabel = targetLabel
                    )
                )
            }.onFailure { err ->
                _events.emit(
                    SearchUiEvent.ShowMessage(
                        err.localizedMessage ?: "Could not extract magnet link from ${torrent.source.displayName}"
                    )
                )
            }
        }
    }

    private fun cacheResolvedMagnet(torrent: TorrentResult, magnet: String): TorrentResult {
        val hash = torrent.infoHash.ifBlank { QualityAndHealthParser.extractHashFromMagnet(magnet) }
        val updated = torrent.copy(magnetUri = magnet, infoHash = hash)
        _rawResults.update { list -> list.map { if (it.id == torrent.id) updated else it } }
        _trendingResults.update { list -> list.map { if (it.id == torrent.id) updated else it } }
        return updated
    }

    fun isBookmarked(torrent: TorrentResult): Boolean =
        cloudSyncRepo.bookmarkDocId(torrent) in bookmarkedIds.value

    fun toggleBookmark(torrent: TorrentResult) {
        viewModelScope.launch {
            val docId = cloudSyncRepo.bookmarkDocId(torrent)
            val alreadySaved = docId in bookmarkedIds.value
            val res = if (alreadySaved) {
                cloudSyncRepo.removeBookmark(docId)
            } else {
                cloudSyncRepo.addBookmark(torrent)
            }
            val scopeLabel = if (cloudSyncRepo.isCloudMode) "synced Vault" else "local Vault"
            res.onSuccess {
                _events.emit(
                    SearchUiEvent.ShowMessage(
                        if (alreadySaved) "Removed from $scopeLabel" else "Saved to $scopeLabel"
                    )
                )
            }.onFailure { err ->
                _events.emit(SearchUiEvent.ShowMessage(err.localizedMessage ?: "Storage error"))
            }
        }
    }

    class Factory(
        private val indexerRepo: IndexerAggregatorRepository,
        private val cloudSyncRepo: CloudSyncRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(indexerRepo, cloudSyncRepo) as T
    }
}
