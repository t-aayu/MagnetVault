package com.example.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.firebase.CloudSyncRepository
import com.example.data.indexers.IndexerAggregatorRepository
import com.example.data.indexers.SearchSessionSnapshot
import com.example.domain.magnet.CanonicalIdentityKey
import com.example.domain.magnet.MagnetParseResult
import com.example.domain.magnet.MagnetParser
import com.example.model.CanonicalReleaseGroup
import com.example.model.IndexerSearchStatus
import com.example.model.IndexerSource
import com.example.model.MagnetResolutionState
import com.example.model.MirrorConfig
import com.example.model.ProviderListing
import com.example.model.ProviderTerminalState
import com.example.model.QualityAndHealthParser
import com.example.model.QualityTag
import com.example.model.SearchContentPhase
import com.example.model.SearchFilterState
import com.example.model.SearchHistoryRecord
import com.example.model.SortOption
import com.example.model.SwarmHealth
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchScreenState(
    val query: String = "",
    val submittedQuery: String = "",
    val hasSearched: Boolean = false,
    val filters: SearchFilterState = SearchFilterState(),
    val isSearching: Boolean = false,
    val trendingExpanded: Boolean = false,
    val isLoadingTrending: Boolean = false,
    val trendingLoadedOnce: Boolean = false,
    val trendingError: String? = null,
    val providerStatuses: List<IndexerSearchStatus> = emptyList(),
    val enabledSourcesCount: Int = IndexerSource.entries.size,
    val completedSourcesCount: Int = 0,
    val contentPhase: SearchContentPhase = SearchContentPhase.IDLE_HOME,
    val selectedGroupKey: String? = null,
    val pasteMagnetError: String? = null,
    val lastInvalidPasteText: String? = null,
    val resolvingActionTorrentId: String? = null
) {
    val selectedCategory: TorrentCategory get() = filters.category
    val selectedSort: SortOption get() = filters.sort
    val selectedQualities: Set<QualityTag> get() = filters.qualities
    val selectedSources: Set<IndexerSource> get() = filters.selectedSources
}

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
    private val cloudSyncRepo: CloudSyncRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    companion object {
        private const val KEY_QUERY_INPUT = "mv_query_input"
        private const val KEY_SUBMITTED_QUERY = "mv_submitted_query"
        private const val KEY_HAS_SEARCHED = "mv_has_searched"
        private const val KEY_CATEGORY = "mv_category"
        private const val KEY_SORT = "mv_sort"
        private const val KEY_QUALITIES = "mv_qualities"
        private const val KEY_SOURCES = "mv_sources"
        private const val KEY_SELECTED_GROUP_KEY = "mv_selected_group_key"
        private const val KEY_TRENDING_EXPANDED = "mv_trending_expanded"
    }

    private val initialFilters = SearchFilterState(
        category = TorrentCategory.fromNameSafe(savedStateHandle.get<String>(KEY_CATEGORY) ?: TorrentCategory.ALL.name),
        sort = runCatching {
            SortOption.valueOf(savedStateHandle.get<String>(KEY_SORT) ?: SortOption.SEEDERS.name)
        }.getOrDefault(SortOption.SEEDERS),
        qualities = savedStateHandle.get<ArrayList<String>>(KEY_QUALITIES)
            ?.mapNotNull { QualityTag.fromLabelOrName(it) }
            ?.toSet()
            ?: emptySet(),
        selectedSources = savedStateHandle.get<ArrayList<String>>(KEY_SOURCES)
            ?.mapNotNull { name -> IndexerSource.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: emptySet()
    )

    private val _localState = MutableStateFlow(
        SearchScreenState(
            query = savedStateHandle.get<String>(KEY_QUERY_INPUT).orEmpty(),
            submittedQuery = savedStateHandle.get<String>(KEY_SUBMITTED_QUERY).orEmpty(),
            hasSearched = savedStateHandle.get<Boolean>(KEY_HAS_SEARCHED) ?: false,
            filters = initialFilters,
            trendingExpanded = savedStateHandle.get<Boolean>(KEY_TRENDING_EXPANDED) ?: false,
            selectedGroupKey = savedStateHandle.get<String>(KEY_SELECTED_GROUP_KEY)
        )
    )

    private val _trendingResults = MutableStateFlow<List<TorrentResult>>(emptyList())
    val trendingResults: StateFlow<List<TorrentResult>> = _trendingResults.asStateFlow()

    private val _pastedMagnetGroup = MutableStateFlow<CanonicalReleaseGroup?>(null)
    val pastedMagnetGroup: StateFlow<CanonicalReleaseGroup?> = _pastedMagnetGroup.asStateFlow()

    private val _standaloneDetailGroup = MutableStateFlow<CanonicalReleaseGroup?>(null)
    val standaloneDetailGroup: StateFlow<CanonicalReleaseGroup?> = _standaloneDetailGroup.asStateFlow()

    private val _events = MutableSharedFlow<SearchUiEvent>()
    val events: SharedFlow<SearchUiEvent> = _events.asSharedFlow()

    private var trendingJob: Job? = null
    private var standaloneMagnetJob: Job? = null

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

    val searchHistoryEnabled: StateFlow<Boolean> =
        cloudSyncRepo.observeSearchHistoryEnabled()
            .catch { emit(true) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = true
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
            .map { list ->
                list.map { it.legacyDocId?.takeIf { d -> d.isNotBlank() } ?: it.id }.toSet()
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptySet()
            )

    val activeBookmarkMatchKeys: StateFlow<Set<String>> =
        cloudSyncRepo.observeActiveBookmarkMatchKeys()
            .catch { emit(emptySet()) }
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

    val displayedGroups: StateFlow<List<CanonicalReleaseGroup>> = combine(
        indexerRepo.sessionSnapshots,
        _localState
    ) { snapshot, local ->
        filterAndSortGroups(snapshot.groups, local.filters)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val uiState: StateFlow<SearchScreenState> = combine(
        _localState,
        indexerRepo.sessionSnapshots,
        displayedGroups,
        mirrorConfigs
    ) { local, snapshot, displayed, mirrors ->
        val statuses = snapshot.statuses
        val enabledCount = if (statuses.isNotEmpty()) {
            statuses.count { it.terminalState != ProviderTerminalState.DISABLED }
        } else {
            mirrors.values.count { it.enabled }
        }
        val completedCount = statuses.count {
            it.terminalState != ProviderTerminalState.DISABLED && it.terminalState.isFinished
        }
        val phase = computeContentPhase(
            hasSearched = local.hasSearched,
            enabledCount = enabledCount,
            snapshot = snapshot,
            displayedCount = displayed.size
        )
        val resolvedSelectedKey = snapshot.resolveGroupKey(local.selectedGroupKey)
        local.copy(
            isSearching = snapshot.isSearching,
            providerStatuses = statuses,
            enabledSourcesCount = enabledCount,
            completedSourcesCount = completedCount,
            contentPhase = phase,
            selectedGroupKey = resolvedSelectedKey
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _localState.value
    )

    val selectedGroup: StateFlow<CanonicalReleaseGroup?> = combine(
        indexerRepo.sessionSnapshots,
        _localState,
        _standaloneDetailGroup
    ) { snapshot, local, standalone ->
        standalone ?: snapshot.findGroup(local.selectedGroupKey)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    init {
        // Watch for resolved aliases upgrading previously saved unresolved bookmarks without duplicates
        viewModelScope.launch {
            indexerRepo.sessionSnapshots.collect { snapshot ->
                val resolvedSelected = snapshot.resolveGroupKey(_localState.value.selectedGroupKey)
                if (resolvedSelected != _localState.value.selectedGroupKey) {
                    savedStateHandle[KEY_SELECTED_GROUP_KEY] = resolvedSelected
                    _localState.update { it.copy(selectedGroupKey = resolvedSelected) }
                }
                upgradeResolvedBookmarksIfNeeded(snapshot)
            }
        }

        // Restore submitted search after process recreation if lightweight saved state exists
        val restoredQuery = _localState.value.submittedQuery.trim()
        if (_localState.value.hasSearched && restoredQuery.isNotBlank() && !indexerRepo.sessionSnapshots.value.hasStarted) {
            executeSearch(queryOverride = restoredQuery, recordHistory = false)
        }
    }

    private suspend fun upgradeResolvedBookmarksIfNeeded(snapshot: SearchSessionSnapshot) {
        val savedIds = bookmarkedIds.value
        val matchKeys = activeBookmarkMatchKeys.value
        if (savedIds.isEmpty() && matchKeys.isEmpty()) return
        for (group in snapshot.groups) {
            val verified = group.verifiedHash ?: continue
            val canonicalDocId = QualityAndHealthParser.sanitizeDocumentId("bm_${verified.canonicalHex}")
            if (canonicalDocId in savedIds && verified.storageKey in matchKeys) continue
            val unresolvedMatchId = group.listings
                .flatMap {
                    listOf(
                        it.unresolvedStorageKey,
                        cloudSyncRepo.unresolvedBookmarkDocId(it.source, it.listingId)
                    )
                }
                .firstOrNull { it in savedIds || it in matchKeys }
            if (unresolvedMatchId != null) {
                cloudSyncRepo.saveOrUpgradeBookmark(
                    torrent = group.toRepresentativeTorrentResult(),
                    replaceUnresolvedDocId = unresolvedMatchId
                )
            }
        }
    }

    fun updateQuery(newQuery: String) {
        savedStateHandle[KEY_QUERY_INPUT] = newQuery
        _localState.update {
            it.copy(
                query = newQuery,
                pasteMagnetError = null,
                lastInvalidPasteText = null
            )
        }
    }

    fun clearSearch() {
        standaloneMagnetJob?.cancel()
        savedStateHandle[KEY_QUERY_INPUT] = ""
        savedStateHandle[KEY_SUBMITTED_QUERY] = ""
        savedStateHandle[KEY_HAS_SEARCHED] = false
        savedStateHandle[KEY_SELECTED_GROUP_KEY] = null
        _standaloneDetailGroup.value = null
        _localState.update {
            it.copy(
                query = "",
                submittedQuery = "",
                hasSearched = false,
                selectedGroupKey = null,
                pasteMagnetError = null,
                lastInvalidPasteText = null
            )
        }
        viewModelScope.launch {
            indexerRepo.coordinator.cancelSearch()
        }
    }

    fun executeSearch(
        queryOverride: String? = null,
        recordHistory: Boolean = true
    ) {
        val targetQuery = (queryOverride ?: _localState.value.query).trim()
        if (targetQuery.isBlank()) return

        standaloneMagnetJob?.cancel()
        _standaloneDetailGroup.value = null
        _pastedMagnetGroup.value = null

        val preserveSelection = queryOverride != null &&
            targetQuery == _localState.value.submittedQuery &&
            !recordHistory

        savedStateHandle[KEY_QUERY_INPUT] = targetQuery
        savedStateHandle[KEY_SUBMITTED_QUERY] = targetQuery
        savedStateHandle[KEY_HAS_SEARCHED] = true
        if (!preserveSelection) {
            savedStateHandle[KEY_SELECTED_GROUP_KEY] = null
        }

        val currentFilters = _localState.value.filters
        _localState.update {
            it.copy(
                query = targetQuery,
                submittedQuery = targetQuery,
                hasSearched = true,
                selectedGroupKey = if (preserveSelection) it.selectedGroupKey else null,
                pasteMagnetError = null,
                lastInvalidPasteText = null
            )
        }

        viewModelScope.launch {
            if (recordHistory && searchHistoryEnabled.value) {
                launch { cloudSyncRepo.recordSearchQuery(targetQuery, currentFilters.category) }
            }
            indexerRepo.coordinator.startSearch(
                query = targetQuery,
                category = currentFilters.category,
                sortOption = currentFilters.sort,
                mirrorConfigs = mirrorConfigs.value
            )
        }
    }

    fun runSearchFromHistory(query: String, category: TorrentCategory) {
        val updatedFilters = _localState.value.filters.copy(category = category)
        persistFilters(updatedFilters)
        _localState.update { it.copy(query = query, filters = updatedFilters) }
        executeSearch(queryOverride = query, recordHistory = true)
    }

    fun retryProvider(source: IndexerSource) {
        viewModelScope.launch {
            indexerRepo.coordinator.retryProvider(
                source = source,
                sortOption = _localState.value.filters.sort,
                mirrorConfigs = mirrorConfigs.value
            )
        }
    }

    fun retryAllSearch() {
        val submitted = _localState.value.submittedQuery.ifBlank { _localState.value.query }.trim()
        if (submitted.isNotBlank()) {
            executeSearch(queryOverride = submitted, recordHistory = false)
        }
    }

    // --- LOCAL PASTE MAGNET ---
    fun handlePasteMagnet(rawInput: String) {
        when (val parsed = MagnetParser.parseMagnetInput(rawInput)) {
            is MagnetParseResult.Valid -> {
                val primaryHash = parsed.primaryIdentity
                val displayName = parsed.displayName?.takeIf { it.isNotBlank() }
                    ?: "Magnet ${primaryHash.canonicalHex.take(12).uppercase()}"
                val syntheticResult = TorrentResult(
                    id = "pasted_${primaryHash.canonicalHex.take(16)}",
                    title = displayName,
                    source = IndexerSource.X1337,
                    category = TorrentCategory.OTHER,
                    sizeLabel = QualityAndHealthParser.formatKnownBytes(parsed.exactLengthBytes),
                    sizeBytes = parsed.exactLengthBytes ?: 0L,
                    seeders = 0,
                    leechers = 0,
                    uploadDateLabel = "Pasted",
                    uploadEpochSeconds = 0L,
                    magnetUri = parsed.magnetUri,
                    infoHash = primaryHash.canonicalHex,
                    detailPageUrl = "",
                    swarmHealth = SwarmHealth.UNKNOWN,
                    listingId = primaryHash.canonicalHex,
                    knownSizeBytes = parsed.exactLengthBytes,
                    knownSeeders = null,
                    knownLeechers = null,
                    knownUploadEpochSeconds = null,
                    canonicalHashHex = primaryHash.canonicalHex,
                    canonicalHashFamily = primaryHash.family
                )
                val listing = ProviderListing.fromTorrentResult(syntheticResult)
                val group = CanonicalReleaseGroup(
                    groupKey = primaryHash.storageKey,
                    canonicalIdentity = primaryHash,
                    representative = listing,
                    listings = emptyList(),
                    magnetState = MagnetResolutionState.Available(
                        magnetUri = parsed.magnetUri,
                        identity = primaryHash,
                        resolvedBySource = null
                    ),
                    qualityTags = QualityAndHealthParser.extractQualityTags(displayName),
                    swarmHealth = SwarmHealth.UNKNOWN
                )
                _localState.update {
                    it.copy(pasteMagnetError = null, lastInvalidPasteText = null)
                }
                _pastedMagnetGroup.value = group
            }
            is MagnetParseResult.Empty -> {
                _localState.update {
                    it.copy(
                        pasteMagnetError = "Clipboard or input is empty. Paste a magnet:? link to inspect it.",
                        lastInvalidPasteText = null
                    )
                }
            }
            is MagnetParseResult.Invalid -> {
                _localState.update {
                    it.copy(
                        pasteMagnetError = parsed.reason,
                        lastInvalidPasteText = rawInput.trim().take(200)
                    )
                }
            }
        }
    }

    fun dismissPasteMagnetError() {
        _localState.update { it.copy(pasteMagnetError = null, lastInvalidPasteText = null) }
    }

    fun dismissPastedMagnetSheet() {
        _pastedMagnetGroup.value = null
    }

    // --- EXPLICIT TRENDING ACTION (NOT AUTO-FETCHED ON STARTUP) ---
    fun toggleTrending() {
        val willExpand = !_localState.value.trendingExpanded
        savedStateHandle[KEY_TRENDING_EXPANDED] = willExpand
        _localState.update { it.copy(trendingExpanded = willExpand) }
        if (willExpand && !_localState.value.trendingLoadedOnce && !_localState.value.isLoadingTrending) {
            loadTrending()
        }
    }

    fun loadTrending() {
        trendingJob?.cancel()
        savedStateHandle[KEY_TRENDING_EXPANDED] = true
        trendingJob = viewModelScope.launch {
            _localState.update {
                it.copy(
                    trendingExpanded = true,
                    isLoadingTrending = true,
                    trendingError = null
                )
            }
            try {
                val items = indexerRepo.fetchTrending(mirrorConfigs.value)
                _trendingResults.value = items
                _localState.update {
                    it.copy(
                        isLoadingTrending = false,
                        trendingLoadedOnce = true,
                        trendingError = if (items.isEmpty()) {
                            "Could not reach trending mirrors right now. Check Mirror Health in Settings."
                        } else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _localState.update {
                    it.copy(
                        isLoadingTrending = false,
                        trendingLoadedOnce = true,
                        trendingError = e.localizedMessage ?: "Failed to load trending releases"
                    )
                }
            }
        }
    }

    // --- FILTER & SORT CONTROLS ---
    fun selectCategory(category: TorrentCategory) {
        applyFilters(_localState.value.filters.copy(category = category))
    }

    fun selectSort(sort: SortOption) {
        // Sorting is strictly local and never requeries providers
        val updated = _localState.value.filters.copy(sort = sort)
        persistFilters(updated)
        _localState.update { it.copy(filters = updated) }
    }

    fun toggleQualityTag(tag: QualityTag) {
        val current = _localState.value.filters.qualities
        val updatedSet = if (tag in current) current - tag else current + tag
        val updated = _localState.value.filters.copy(qualities = updatedSet)
        persistFilters(updated)
        _localState.update { it.copy(filters = updated) }
    }

    fun toggleSourceFilter(source: IndexerSource) {
        val current = _localState.value.filters.selectedSources
        val updatedSet = if (source in current) current - source else current + source
        applyFilters(_localState.value.filters.copy(selectedSources = updatedSet))
    }

    fun selectSingleSourceFilter(source: IndexerSource?) {
        val current = _localState.value.filters.selectedSources
        val updatedSet = when {
            source == null -> emptySet()
            current.size == 1 && source in current -> emptySet()
            else -> setOf(source)
        }
        applyFilters(_localState.value.filters.copy(selectedSources = updatedSet))
    }

    fun applyFilters(newFilters: SearchFilterState) {
        val previous = _localState.value.filters
        persistFilters(newFilters)
        _localState.update { it.copy(filters = newFilters) }

        if (!_localState.value.hasSearched) return
        val activeQuery = _localState.value.submittedQuery.ifBlank { _localState.value.query }.trim()
        if (activeQuery.isBlank()) return

        val categoryChanged = previous.category != newFilters.category
        val coveredSources = indexerRepo.sessionSnapshots.value.coveredSources
        val requestedUncoveredSource = newFilters.selectedSources.any { src ->
            src !in coveredSources && (mirrorConfigs.value[src]?.enabled == true)
        }
        if (categoryChanged || requestedUncoveredSource) {
            executeSearch(queryOverride = activeQuery, recordHistory = false)
        }
    }

    fun resetFilters() {
        applyFilters(SearchFilterState())
    }

    private fun persistFilters(filters: SearchFilterState) {
        savedStateHandle[KEY_CATEGORY] = filters.category.name
        savedStateHandle[KEY_SORT] = filters.sort.name
        savedStateHandle[KEY_QUALITIES] = ArrayList(filters.qualities.map { it.name })
        savedStateHandle[KEY_SOURCES] = ArrayList(filters.selectedSources.map { it.name })
    }

    // --- DETAIL SELECTION & LAZY MAGNET RESOLUTION ---
    fun selectGroup(group: CanonicalReleaseGroup) {
        standaloneMagnetJob?.cancel()
        _standaloneDetailGroup.value = null
        _pastedMagnetGroup.value = null
        savedStateHandle[KEY_SELECTED_GROUP_KEY] = group.groupKey
        _localState.update { it.copy(selectedGroupKey = group.groupKey) }

        val sessionId = indexerRepo.sessionSnapshots.value.sessionId
        if (group.magnetState !is MagnetResolutionState.Available) {
            viewModelScope.launch {
                indexerRepo.coordinator.resolveMagnetForSelection(
                    sessionId = sessionId,
                    groupKey = group.groupKey,
                    mirrorConfigs = mirrorConfigs.value
                )
            }
        } else {
            indexerRepo.coordinator.cancelSelectionMagnetResolution()
        }
    }

    fun openTorrentDetail(torrent: TorrentResult) {
        val listing = ProviderListing.fromTorrentResult(torrent)
        val initialGroup = CanonicalReleaseGroup.fromListings(
            groupKey = listing.canonicalIdentity.storageKey,
            identity = listing.canonicalIdentity,
            listings = listOf(listing)
        )
        indexerRepo.coordinator.cancelSelectionMagnetResolution()
        standaloneMagnetJob?.cancel()
        _pastedMagnetGroup.value = null
        _standaloneDetailGroup.value = initialGroup

        if (initialGroup.magnetState !is MagnetResolutionState.Available) {
            resolveStandaloneDetail(initialGroup)
        }
    }

    fun retryResolveSelectedMagnet() {
        val standalone = _standaloneDetailGroup.value
        if (standalone != null) {
            resolveStandaloneDetail(standalone)
            return
        }
        val key = _localState.value.selectedGroupKey ?: return
        val sessionId = indexerRepo.sessionSnapshots.value.sessionId
        viewModelScope.launch {
            indexerRepo.coordinator.resolveMagnetForSelection(
                sessionId = sessionId,
                groupKey = key,
                mirrorConfigs = mirrorConfigs.value
            )
        }
    }

    private fun resolveStandaloneDetail(group: CanonicalReleaseGroup) {
        standaloneMagnetJob?.cancel()
        val selectionKey = group.groupKey
        _standaloneDetailGroup.value = group.copy(
            magnetState = MagnetResolutionState.Resolving(selectionKey, group.primarySource)
        )
        standaloneMagnetJob = viewModelScope.launch {
            val result = indexerRepo.coordinator.resolveStandaloneMagnet(
                listing = group.representative,
                mirrorConfigs = mirrorConfigs.value
            )
            if (_standaloneDetailGroup.value?.groupKey != selectionKey) return@launch
            result.onSuccess { magnet ->
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
                    _standaloneDetailGroup.value = upgradedGroup

                    // Upgrade unresolved bookmark if saved before resolution
                    val unresolvedKey = group.representative.unresolvedStorageKey
                    val unresolvedId = cloudSyncRepo.unresolvedBookmarkDocId(
                        group.representative.source,
                        group.representative.listingId
                    )
                    if (unresolvedId in bookmarkedIds.value || unresolvedKey in activeBookmarkMatchKeys.value) {
                        cloudSyncRepo.saveOrUpgradeBookmark(
                            torrent = upgradedGroup.toRepresentativeTorrentResult(),
                            replaceUnresolvedDocId = unresolvedId
                        )
                    }
                } else {
                    _standaloneDetailGroup.value = group.copy(
                        magnetState = MagnetResolutionState.Failed(
                            selectionKey,
                            "Resolved magnet did not contain a valid info hash"
                        )
                    )
                }
            }.onFailure { err ->
                _standaloneDetailGroup.value = group.copy(
                    magnetState = MagnetResolutionState.Failed(
                        selectionKey,
                        err.localizedMessage ?: "Failed to extract magnet URI"
                    )
                )
            }
        }
    }

    fun dismissTorrentDetail() {
        standaloneMagnetJob?.cancel()
        standaloneMagnetJob = null
        indexerRepo.coordinator.cancelSelectionMagnetResolution()
        _standaloneDetailGroup.value = null
        savedStateHandle[KEY_SELECTED_GROUP_KEY] = null
        _localState.update { it.copy(selectedGroupKey = null) }
    }

    // --- BOOKMARKS (INCLUDING UNRESOLVED LISTING SAVE + UPGRADE) ---
    fun isGroupBookmarked(group: CanonicalReleaseGroup, savedIds: Set<String> = bookmarkedIds.value): Boolean {
        val candidateIds = bookmarkCandidateIdsForGroup(group)
        val matchKeys = activeBookmarkMatchKeys.value
        return candidateIds.any { it in savedIds || it in matchKeys }
    }

    fun toggleGroupBookmark(group: CanonicalReleaseGroup) {
        viewModelScope.launch {
            val savedIds = bookmarkedIds.value
            val matchKeys = activeBookmarkMatchKeys.value
            val candidateIds = bookmarkCandidateIdsForGroup(group)
            val existingLocalId = if (candidateIds.any { it in savedIds || it in matchKeys }) {
                cloudSyncRepo.findLocalBookmarkIdForKeys(candidateIds)
            } else null

            if (existingLocalId != null) {
                cloudSyncRepo.removeBookmarkByLocalIdOrDocId(existingLocalId).onSuccess {
                    _events.emit(SearchUiEvent.ShowMessage("Removed from Vault"))
                }.onFailure { err ->
                    _events.emit(SearchUiEvent.ShowMessage(err.localizedMessage ?: "Storage error"))
                }
            } else {
                val repTorrent = group.toRepresentativeTorrentResult()
                val res = cloudSyncRepo.saveOrUpgradeBookmark(repTorrent)
                res.onSuccess {
                    val unresolvedNote = if (group.verifiedHash == null) " (listing saved; magnet can be resolved later)" else ""
                    _events.emit(SearchUiEvent.ShowMessage("Saved to Vault$unresolvedNote"))
                }.onFailure { err ->
                    _events.emit(SearchUiEvent.ShowMessage(err.localizedMessage ?: "Storage error"))
                }
            }
        }
    }

    private fun bookmarkCandidateIdsForGroup(group: CanonicalReleaseGroup): List<String> = buildList {
        group.verifiedHash?.let { verified ->
            add(verified.storageKey)
            add(QualityAndHealthParser.sanitizeDocumentId("bm_${verified.canonicalHex}"))
        }
        group.listings.forEach { listing ->
            add(listing.unresolvedStorageKey)
            add(cloudSyncRepo.unresolvedBookmarkDocId(listing.source, listing.listingId))
        }
        add(cloudSyncRepo.bookmarkDocId(group.toRepresentativeTorrentResult()))
    }.distinct()

    // --- LEGACY VAULT COMPATIBILITY HELPERS ---
    fun isBookmarked(torrent: TorrentResult): Boolean {
        val docId = cloudSyncRepo.bookmarkDocId(torrent)
        val listing = ProviderListing.fromTorrentResult(torrent)
        return docId in bookmarkedIds.value || listing.canonicalIdentity.storageKey in activeBookmarkMatchKeys.value
    }

    fun toggleBookmark(torrent: TorrentResult) {
        val listing = ProviderListing.fromTorrentResult(torrent)
        val group = CanonicalReleaseGroup.fromListings(
            groupKey = listing.canonicalIdentity.storageKey,
            identity = listing.canonicalIdentity,
            listings = listOf(listing)
        )
        toggleGroupBookmark(group)
    }

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
            _localState.update { it.copy(resolvingActionTorrentId = torrent.id) }
            val resolved = indexerRepo.resolveMagnet(torrent, mirrorConfigs.value)
            _localState.update { it.copy(resolvingActionTorrentId = null) }
            resolved.onSuccess { magnet ->
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

    private fun filterAndSortGroups(
        groups: List<CanonicalReleaseGroup>,
        filters: SearchFilterState
    ): List<CanonicalReleaseGroup> {
        val filtered = groups.filter { group ->
            val categoryOk = filters.category == TorrentCategory.ALL ||
                group.listings.any { it.category == filters.category || it.category == TorrentCategory.OTHER }
            val qualityOk = filters.qualities.isEmpty() ||
                filters.qualities.any { it in group.qualityTags }
            val sourceOk = filters.selectedSources.isEmpty() ||
                group.sources.any { it in filters.selectedSources }
            categoryOk && qualityOk && sourceOk
        }

        val comparator = Comparator<CanonicalReleaseGroup> { a, b ->
            val primaryCmp = when (filters.sort) {
                SortOption.SEEDERS -> CanonicalReleaseGroup.compareNullableDesc(a.knownSeeders, b.knownSeeders)
                SortOption.LEECHERS -> CanonicalReleaseGroup.compareNullableDesc(a.knownLeechers, b.knownLeechers)
                SortOption.UPLOAD_DATE -> CanonicalReleaseGroup.compareNullableDesc(a.knownUploadEpochSeconds, b.knownUploadEpochSeconds)
                SortOption.SIZE -> CanonicalReleaseGroup.compareNullableDesc(a.knownSizeBytes, b.knownSizeBytes)
            }
            if (primaryCmp != 0) return@Comparator primaryCmp
            val seedTie = CanonicalReleaseGroup.compareNullableDesc(a.knownSeeders, b.knownSeeders)
            if (seedTie != 0) return@Comparator seedTie
            a.groupKey.compareTo(b.groupKey)
        }
        return filtered.sortedWith(comparator)
    }

    private fun computeContentPhase(
        hasSearched: Boolean,
        enabledCount: Int,
        snapshot: SearchSessionSnapshot,
        displayedCount: Int
    ): SearchContentPhase {
        if (!hasSearched) return SearchContentPhase.IDLE_HOME
        if (enabledCount == 0) return SearchContentPhase.ALL_SOURCES_DISABLED

        if (snapshot.isSearching) {
            return if (displayedCount > 0 || snapshot.groups.isNotEmpty()) {
                SearchContentPhase.STREAMING_RESULTS
            } else {
                SearchContentPhase.SEARCHING_EMPTY
            }
        }

        if (snapshot.groups.isNotEmpty() && displayedCount == 0) {
            return SearchContentPhase.FILTERED_OUT
        }

        val enabledStatuses = snapshot.statuses.filter { it.terminalState != ProviderTerminalState.DISABLED }
        val failedStatuses = enabledStatuses.filter { it.terminalState.isFailure }
        val cleanSuccessStatuses = enabledStatuses.filter {
            it.terminalState == ProviderTerminalState.MATCHES ||
                it.terminalState == ProviderTerminalState.ZERO_MATCHES
        }

        if (displayedCount > 0) {
            return if (failedStatuses.isNotEmpty()) {
                SearchContentPhase.PARTIAL_SUCCESS
            } else {
                SearchContentPhase.COMPLETED_SUCCESS
            }
        }

        return if (failedStatuses.isNotEmpty() && cleanSuccessStatuses.isEmpty()) {
            SearchContentPhase.TOTAL_FAILURE
        } else {
            SearchContentPhase.ZERO_MATCHES
        }
    }

    class Factory(
        private val indexerRepo: IndexerAggregatorRepository,
        private val cloudSyncRepo: CloudSyncRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val handle = extras.createSavedStateHandle()
            return SearchViewModel(indexerRepo, cloudSyncRepo, handle) as T
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(indexerRepo, cloudSyncRepo, SavedStateHandle()) as T
    }
}
