package com.example.data.indexers

import com.example.data.network.HttpStatusException
import com.example.data.network.HttpTransport
import com.example.data.network.MirrorBlockedException
import com.example.data.network.OkHttpTransport
import com.example.data.network.RateLimitedException
import com.example.data.network.ResponseTooLargeException
import com.example.data.network.SearchNetworkLimits
import com.example.domain.magnet.CanonicalIdentityKey
import com.example.domain.magnet.MagnetParser
import com.example.model.CanonicalReleaseGroup
import com.example.model.IndexerSearchStatus
import com.example.model.IndexerSource
import com.example.model.MagnetResolutionState
import com.example.model.MirrorConfig
import com.example.model.ProviderListing
import com.example.model.ProviderTerminalState
import com.example.model.SortOption
import com.example.model.TorrentCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

data class SearchSessionSnapshot(
    val sessionId: Long = 0L,
    val query: String = "",
    val category: TorrentCategory = TorrentCategory.ALL,
    val hasStarted: Boolean = false,
    val isSearching: Boolean = false,
    val groups: List<CanonicalReleaseGroup> = emptyList(),
    val statuses: List<IndexerSearchStatus> = emptyList(),
    val aliasToCanonicalKey: Map<String, String> = emptyMap(),
    val coveredSources: Set<IndexerSource> = emptySet()
) {
    fun resolveGroupKey(rawKey: String?): String? {
        if (rawKey.isNullOrBlank()) return null
        var cursor = rawKey
        val visited = HashSet<String>()
        while (cursor != null && visited.add(cursor)) {
            val next = aliasToCanonicalKey[cursor] ?: break
            cursor = next
        }
        return cursor
    }

    fun findGroup(rawKey: String?): CanonicalReleaseGroup? {
        val canonicalKey = resolveGroupKey(rawKey) ?: return null
        return groups.firstOrNull { it.groupKey == canonicalKey }
            ?: groups.firstOrNull { it.groupKey == rawKey }
    }

    companion object {
        val EMPTY = SearchSessionSnapshot()
    }
}

class SearchCoordinator(
    private val adapters: Map<IndexerSource, ProviderAdapter> = defaultAdapters(),
    private val transport: HttpTransport = OkHttpTransport(),
    val limits: SearchNetworkLimits = SearchNetworkLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stateMutex = Mutex()
    private val sessionSeq = AtomicLong(0L)

    private val _snapshots = MutableStateFlow(SearchSessionSnapshot.EMPTY)
    val snapshots: StateFlow<SearchSessionSnapshot> = _snapshots.asStateFlow()

    private var activeSearchJob: Job? = null
    private var activeMagnetJob: Job? = null

    @Volatile
    private var activeSelectionKey: String? = null

    // Per-session mutable stores protected by stateMutex
    private var sessionListings: MutableList<ProviderListing> = mutableListOf()
    private var sessionAliasMap: MutableMap<String, String> = mutableMapOf()
    private var sessionMagnetOverrides: MutableMap<String, MagnetResolutionState> = mutableMapOf()
    private var sessionStatuses: MutableMap<IndexerSource, IndexerSearchStatus> = LinkedHashMap()

    suspend fun startSearch(
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        mirrorConfigs: Map<IndexerSource, MirrorConfig>
    ): Long {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            cancelSearch()
            return sessionSeq.get()
        }

        val prevSearch = activeSearchJob
        val prevMagnet = activeMagnetJob
        activeSearchJob = null
        activeMagnetJob = null
        activeSelectionKey = null
        prevMagnet?.cancel()
        prevSearch?.cancel()

        val newSessionId = sessionSeq.incrementAndGet()
        val orderedAdapters = IndexerSource.entries.mapNotNull { adapters[it] }
        val semaphore = Semaphore(limits.maxConcurrentProviderCalls.coerceAtLeast(1))
        val sessionDeadlineAtMs = clockMs() + limits.wholeSearchDeadlineMs
        val covered = LinkedHashSet<IndexerSource>()

        val runnableAdapters = mutableListOf<Triple<ProviderAdapter, List<String>, String>>()

        stateMutex.withLock {
            sessionListings = mutableListOf()
            sessionAliasMap = mutableMapOf()
            sessionMagnetOverrides = mutableMapOf()
            sessionStatuses = LinkedHashMap()

            for (adapter in orderedAdapters) {
                val cfg = mirrorConfigs[adapter.source] ?: MirrorConfig(provider = adapter.source)
                val orderedMirrors = buildOrderedMirrors(cfg)
                val webUrl = adapter.buildWebSearchUrl(orderedMirrors, cleanQuery)

                if (!cfg.enabled) {
                    sessionStatuses[adapter.source] = IndexerSearchStatus(
                        source = adapter.source,
                        mirrorUsed = orderedMirrors.firstOrNull().orEmpty(),
                        isLoading = false,
                        resultCount = 0,
                        errorMessage = null,
                        webSearchUrl = webUrl,
                        terminalState = ProviderTerminalState.DISABLED
                    )
                } else if (!adapter.supportsCategory(category)) {
                    covered += adapter.source
                    sessionStatuses[adapter.source] = IndexerSearchStatus(
                        source = adapter.source,
                        mirrorUsed = orderedMirrors.firstOrNull().orEmpty(),
                        isLoading = false,
                        resultCount = 0,
                        errorMessage = null,
                        webSearchUrl = webUrl,
                        terminalState = ProviderTerminalState.UNSUPPORTED_CATEGORY
                    )
                } else {
                    covered += adapter.source
                    sessionStatuses[adapter.source] = IndexerSearchStatus(
                        source = adapter.source,
                        mirrorUsed = orderedMirrors.firstOrNull().orEmpty(),
                        isLoading = true,
                        resultCount = 0,
                        errorMessage = null,
                        webSearchUrl = webUrl,
                        terminalState = ProviderTerminalState.LOADING
                    )
                    runnableAdapters += Triple(adapter, orderedMirrors, webUrl)
                }
            }

            _snapshots.value = SearchSessionSnapshot(
                sessionId = newSessionId,
                query = cleanQuery,
                category = category,
                hasStarted = true,
                isSearching = runnableAdapters.isNotEmpty(),
                groups = emptyList(),
                statuses = sessionStatuses.values.toList(),
                aliasToCanonicalKey = emptyMap(),
                coveredSources = covered.toSet()
            )
        }

        if (runnableAdapters.isEmpty()) {
            return newSessionId
        }

        activeSearchJob = scope.launch {
            val completedWithinBudget = withTimeoutOrNull(limits.wholeSearchDeadlineMs) {
                coroutineScope {
                    for ((adapter, mirrors, webUrl) in runnableAdapters) {
                        launch(ioDispatcher) {
                            semaphore.withPermit {
                                val (listings, status) = executeProviderSearch(
                                    adapter = adapter,
                                    mirrors = mirrors,
                                    webSearchUrl = webUrl,
                                    query = cleanQuery,
                                    category = category,
                                    sortOption = sortOption,
                                    sessionDeadlineAtMs = sessionDeadlineAtMs
                                )
                                commitProviderResult(newSessionId, adapter.source, listings, status)
                            }
                        }
                    }
                }
                true
            }

            if (completedWithinBudget == null) {
                markRemainingTimedOut(newSessionId)
            }
        }
        return newSessionId
    }

    suspend fun retryProvider(
        source: IndexerSource,
        sortOption: SortOption,
        mirrorConfigs: Map<IndexerSource, MirrorConfig>
    ) {
        val adapter = adapters[source] ?: return
        val snapshotSnapshot = _snapshots.value
        if (!snapshotSnapshot.hasStarted || snapshotSnapshot.query.isBlank()) return

        val targetSessionId = snapshotSnapshot.sessionId
        val cleanQuery = snapshotSnapshot.query
        val category = snapshotSnapshot.category
        val cfg = mirrorConfigs[source] ?: MirrorConfig(provider = source)
        val orderedMirrors = buildOrderedMirrors(cfg)
        val webUrl = adapter.buildWebSearchUrl(orderedMirrors, cleanQuery)

        val shouldLaunch = stateMutex.withLock {
            if (_snapshots.value.sessionId != targetSessionId) return@withLock false
            val currentStatus = sessionStatuses[source]
            if (currentStatus?.isLoading == true || currentStatus?.isRetrying == true) {
                return@withLock false
            }
            if (!cfg.enabled) {
                sessionStatuses[source] = IndexerSearchStatus(
                    source = source,
                    mirrorUsed = orderedMirrors.firstOrNull().orEmpty(),
                    isLoading = false,
                    webSearchUrl = webUrl,
                    terminalState = ProviderTerminalState.DISABLED
                )
                publishSnapshotLocked(targetSessionId)
                return@withLock false
            }
            if (!adapter.supportsCategory(category)) {
                sessionStatuses[source] = IndexerSearchStatus(
                    source = source,
                    mirrorUsed = orderedMirrors.firstOrNull().orEmpty(),
                    isLoading = false,
                    webSearchUrl = webUrl,
                    terminalState = ProviderTerminalState.UNSUPPORTED_CATEGORY
                )
                publishSnapshotLocked(targetSessionId)
                return@withLock false
            }

            sessionStatuses[source] = IndexerSearchStatus(
                source = source,
                mirrorUsed = orderedMirrors.firstOrNull().orEmpty(),
                isLoading = true,
                resultCount = currentStatus?.resultCount ?: 0,
                errorMessage = null,
                webSearchUrl = webUrl,
                terminalState = ProviderTerminalState.LOADING,
                isRetrying = true
            )
            publishSnapshotLocked(targetSessionId)
            true
        }

        if (!shouldLaunch) return

        val deadlineAtMs = clockMs() + limits.wholeSearchDeadlineMs
        scope.launch(ioDispatcher) {
            val (listings, status) = try {
                withTimeout(limits.wholeSearchDeadlineMs) {
                    executeProviderSearch(
                        adapter = adapter,
                        mirrors = orderedMirrors,
                        webSearchUrl = webUrl,
                        query = cleanQuery,
                        category = category,
                        sortOption = sortOption,
                        sessionDeadlineAtMs = deadlineAtMs
                    )
                }
            } catch (e: TimeoutCancellationException) {
                emptyList<ProviderListing>() to IndexerSearchStatus(
                    source = source,
                    mirrorUsed = orderedMirrors.firstOrNull().orEmpty(),
                    isLoading = false,
                    resultCount = 0,
                    errorMessage = "Timed out retrying ${source.displayName}",
                    webSearchUrl = webUrl,
                    terminalState = ProviderTerminalState.TIMEOUT,
                    isRetrying = false
                )
            }
            commitProviderResult(targetSessionId, source, listings, status.copy(isRetrying = false))
        }
    }

    suspend fun cancelSearch() {
        val prevSearch = activeSearchJob
        val prevMagnet = activeMagnetJob
        activeSearchJob = null
        activeMagnetJob = null
        activeSelectionKey = null
        prevMagnet?.cancel()
        prevSearch?.cancel()

        val idleSessionId = sessionSeq.incrementAndGet()
        stateMutex.withLock {
            sessionListings = mutableListOf()
            sessionAliasMap = mutableMapOf()
            sessionMagnetOverrides = mutableMapOf()
            sessionStatuses = LinkedHashMap()
            _snapshots.value = SearchSessionSnapshot.EMPTY.copy(sessionId = idleSessionId)
        }
    }

    fun cancelSelectionMagnetResolution() {
        activeSelectionKey = null
        val prev = activeMagnetJob
        activeMagnetJob = null
        prev?.cancel()
    }

    suspend fun resolveMagnetForSelection(
        sessionId: Long,
        groupKey: String,
        mirrorConfigs: Map<IndexerSource, MirrorConfig>
    ) {
        val currentSnap = _snapshots.value
        if (currentSnap.sessionId != sessionId) return
        val group = currentSnap.findGroup(groupKey) ?: return
        val resolvedKey = group.groupKey

        activeMagnetJob?.cancel()
        activeSelectionKey = resolvedKey

        if (group.magnetState is MagnetResolutionState.Available) {
            return
        }

        stateMutex.withLock {
            if (_snapshots.value.sessionId != sessionId || activeSelectionKey != resolvedKey) return@withLock
            sessionMagnetOverrides[resolvedKey] = MagnetResolutionState.Resolving(
                selectionId = resolvedKey,
                source = group.primarySource
            )
            publishSnapshotLocked(sessionId)
        }

        activeMagnetJob = scope.launch(ioDispatcher) {
            var resolvedMagnet: String? = null
            var resolvedBySource: IndexerSource? = null
            var lastError: Throwable? = null

            for (listing in group.listings) {
                if (!isActive || activeSelectionKey != resolvedKey) return@launch
                if (!listing.magnetUri.isNullOrBlank()) {
                    resolvedMagnet = listing.magnetUri
                    resolvedBySource = listing.source
                    break
                }
                val adapter = adapters[listing.source] ?: continue
                val cfg = mirrorConfigs[listing.source] ?: MirrorConfig(provider = listing.source)
                val mirrors = buildOrderedMirrors(cfg)
                try {
                    val magnet = withTimeout(limits.magnetResolveTimeoutMs) {
                        adapter.resolveMagnet(listing, mirrors, transport, limits)
                    }
                    if (!magnet.isNullOrBlank() && MagnetParser.extractVerifiedIdentities(magnet).isNotEmpty()) {
                        resolvedMagnet = magnet
                        resolvedBySource = listing.source
                        break
                    }
                } catch (e: TimeoutCancellationException) {
                    lastError = IllegalStateException("Magnet lookup timed out on ${listing.source.displayName}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }

            if (!isActive || activeSelectionKey != resolvedKey) return@launch

            stateMutex.withLock {
                if (_snapshots.value.sessionId != sessionId || activeSelectionKey != resolvedKey) {
                    return@withLock
                }
                val magnet = resolvedMagnet
                val verified = MagnetParser.extractVerifiedIdentities(magnet).firstOrNull()
                if (magnet != null && verified != null) {
                    val newKey = verified.storageKey
                    val upgradedState = MagnetResolutionState.Available(
                        magnetUri = magnet,
                        identity = verified,
                        resolvedBySource = resolvedBySource
                    )
                    // Upgrade matching unresolved listings so canonical grouping merges seamlessly
                    sessionListings = sessionListings.map { listing ->
                        val sameListing = group.listings.any {
                            it.source == listing.source && it.listingId == listing.listingId
                        }
                        if (sameListing) {
                            listing.copy(
                                magnetUri = magnet,
                                canonicalIdentity = verified,
                                rawResult = listing.rawResult.copy(
                                    magnetUri = magnet,
                                    infoHash = verified.canonicalHex,
                                    canonicalHashHex = verified.canonicalHex,
                                    canonicalHashFamily = verified.family
                                )
                            )
                        } else {
                            listing
                        }
                    }.toMutableList()

                    if (resolvedKey != newKey) {
                        sessionAliasMap[resolvedKey] = newKey
                        sessionMagnetOverrides.remove(resolvedKey)
                        activeSelectionKey = newKey
                    }
                    sessionMagnetOverrides[newKey] = upgradedState
                    publishSnapshotLocked(sessionId)
                } else {
                    val errMsg = lastError?.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "Could not resolve magnet link from ${group.primarySource.displayName} mirrors"
                    sessionMagnetOverrides[resolvedKey] = MagnetResolutionState.Failed(
                        selectionId = resolvedKey,
                        message = errMsg
                    )
                    publishSnapshotLocked(sessionId)
                }
            }
        }
    }

    /**
     * Resolves a single standalone TorrentResult (e.g., from Vault or Trending) outside the search session.
     */
    suspend fun resolveStandaloneMagnet(
        listing: ProviderListing,
        mirrorConfigs: Map<IndexerSource, MirrorConfig>
    ): Result<String> = withContext(ioDispatcher) {
        if (!listing.magnetUri.isNullOrBlank()) {
            return@withContext Result.success(listing.magnetUri)
        }
        val hash = listing.canonicalIdentity as? CanonicalIdentityKey.VerifiedHash
        if (hash != null) {
            return@withContext Result.success(
                MagnetParser.buildMagnetFromHash(hash.canonicalHex, listing.title, hash.family)
            )
        }
        val adapter = adapters[listing.source]
            ?: return@withContext Result.failure(IllegalStateException("Unsupported source"))
        val cfg = mirrorConfigs[listing.source] ?: MirrorConfig(provider = listing.source)
        val mirrors = buildOrderedMirrors(cfg)
        try {
            val resolved = withTimeout(limits.magnetResolveTimeoutMs) {
                adapter.resolveMagnet(listing, mirrors, transport, limits)
            }
            if (!resolved.isNullOrBlank()) {
                Result.success(resolved)
            } else {
                Result.failure(
                    IllegalStateException("Magnet link not found on ${listing.source.displayName} mirrors")
                )
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Timed out resolving magnet on ${listing.source.displayName}"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun commitProviderResult(
        sessionId: Long,
        source: IndexerSource,
        listings: List<ProviderListing>,
        status: IndexerSearchStatus
    ) {
        stateMutex.withLock {
            if (_snapshots.value.sessionId != sessionId) return@withLock
            sessionListings.removeAll { it.source == source }
            sessionListings.addAll(listings)
            sessionStatuses[source] = status
            publishSnapshotLocked(sessionId)
        }
    }

    private suspend fun markRemainingTimedOut(sessionId: Long) {
        stateMutex.withLock {
            if (_snapshots.value.sessionId != sessionId) return@withLock
            var changed = false
            for ((src, status) in sessionStatuses) {
                if (status.isLoading) {
                    sessionStatuses[src] = status.copy(
                        isLoading = false,
                        isRetrying = false,
                        errorMessage = status.errorMessage ?: "Timed out waiting for ${src.displayName}",
                        terminalState = ProviderTerminalState.TIMEOUT
                    )
                    changed = true
                }
            }
            if (changed) {
                publishSnapshotLocked(sessionId)
            }
        }
    }

    private fun publishSnapshotLocked(sessionId: Long) {
        val existing = _snapshots.value
        if (existing.sessionId != sessionId) return

        val grouped = buildCanonicalGroups(
            listings = sessionListings,
            aliasMap = sessionAliasMap,
            overrides = sessionMagnetOverrides
        )
        val statusesList = sessionStatuses.values.toList()
        _snapshots.value = existing.copy(
            isSearching = statusesList.any { it.isLoading },
            groups = grouped,
            statuses = statusesList,
            aliasToCanonicalKey = sessionAliasMap.toMap()
        )
    }

    private fun buildCanonicalGroups(
        listings: List<ProviderListing>,
        aliasMap: Map<String, String>,
        overrides: Map<String, MagnetResolutionState>
    ): List<CanonicalReleaseGroup> {
        if (listings.isEmpty()) return emptyList()

        val buckets = LinkedHashMap<String, MutableList<ProviderListing>>()
        val bucketIdentity = LinkedHashMap<String, CanonicalIdentityKey>()

        for (listing in listings) {
            val rawKey = listing.canonicalIdentity.storageKey
            val resolvedKey = aliasMap[rawKey] ?: rawKey
            buckets.getOrPut(resolvedKey) { mutableListOf() }.add(listing)
            val currentId = bucketIdentity[resolvedKey]
            if (currentId == null || (currentId is CanonicalIdentityKey.UnresolvedListing && listing.canonicalIdentity is CanonicalIdentityKey.VerifiedHash)) {
                bucketIdentity[resolvedKey] = listing.canonicalIdentity
            }
        }

        return buckets.entries.map { (groupKey, bucketListings) ->
            val identity = bucketIdentity[groupKey] ?: bucketListings.first().canonicalIdentity
            CanonicalReleaseGroup.fromListings(
                groupKey = groupKey,
                identity = identity,
                listings = bucketListings,
                overrideMagnetState = overrides[groupKey]
            )
        }
    }

    private suspend fun executeProviderSearch(
        adapter: ProviderAdapter,
        mirrors: List<String>,
        webSearchUrl: String,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        sessionDeadlineAtMs: Long
    ): Pair<List<ProviderListing>, IndexerSearchStatus> {
        val startedMs = clockMs()
        val perMirrorTimeout = adapter.attemptTimeoutMs(limits)
        var lastErrorMsg: String? = null
        var lastTerminalState: ProviderTerminalState? = null
        var lastHttpCode: Int? = null
        var lastRetryAfterSec: Long? = null
        var lastMirrorUsed = mirrors.firstOrNull().orEmpty()

        for (mirror in mirrors) {
            val cleanMirror = mirror.trim().trimEnd('/')
            lastMirrorUsed = cleanMirror

            val remainingBeforeAttempt = sessionDeadlineAtMs - clockMs()
            if (remainingBeforeAttempt <= 0L) {
                lastTerminalState = ProviderTerminalState.TIMEOUT
                lastErrorMsg = "Search deadline exceeded before trying $cleanMirror"
                break
            }

            var attemptDone = false
            var retriedRateLimitOnMirror = false

            while (!attemptDone) {
                attemptDone = true
                val remainingMs = sessionDeadlineAtMs - clockMs()
                if (remainingMs <= 0L) {
                    lastTerminalState = ProviderTerminalState.TIMEOUT
                    lastErrorMsg = "Timeout on $cleanMirror"
                    break
                }
                val timeoutMs = minOf(perMirrorTimeout, remainingMs)
                try {
                    val rawResults = withTimeout(timeoutMs) {
                        adapter.searchOnMirror(
                            mirrorBaseUrl = cleanMirror,
                            query = query,
                            category = category,
                            sortOption = sortOption,
                            transport = transport,
                            limits = limits
                        )
                    }
                    val listings = rawResults.map {
                        ProviderListing.fromTorrentResult(it, mirrorUsed = cleanMirror)
                    }
                    val elapsed = (clockMs() - startedMs).coerceAtLeast(1L)
                    val terminal = if (listings.isEmpty()) {
                        ProviderTerminalState.ZERO_MATCHES
                    } else {
                        ProviderTerminalState.MATCHES
                    }
                    return listings to IndexerSearchStatus(
                        source = adapter.source,
                        mirrorUsed = cleanMirror,
                        isLoading = false,
                        resultCount = listings.size,
                        errorMessage = null,
                        webSearchUrl = webSearchUrl,
                        terminalState = terminal,
                        elapsedMs = elapsed
                    )
                } catch (e: TimeoutCancellationException) {
                    lastTerminalState = ProviderTerminalState.TIMEOUT
                    lastErrorMsg = "Timed out on $cleanMirror"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RateLimitedException) {
                    lastHttpCode = 429
                    val requestedDelay = e.retryAfterMs
                    val retrySec = requestedDelay?.div(1000L)?.coerceAtLeast(1L)
                    lastRetryAfterSec = retrySec
                    val delayToHonor = (requestedDelay ?: 750L).coerceAtMost(limits.maxRateLimitDelayMs)
                    val timeLeft = sessionDeadlineAtMs - clockMs()

                    if (!retriedRateLimitOnMirror &&
                        (requestedDelay == null || requestedDelay <= limits.maxRateLimitDelayMs) &&
                        delayToHonor + 250L < timeLeft
                    ) {
                        retriedRateLimitOnMirror = true
                        attemptDone = false
                        delay(delayToHonor)
                    } else {
                        lastTerminalState = ProviderTerminalState.RATE_LIMITED
                        lastErrorMsg = e.localizedMessage ?: "Rate limited (HTTP 429) on $cleanMirror"
                    }
                } catch (e: ParserException) {
                    lastTerminalState = ProviderTerminalState.PARSER_ERROR
                    lastErrorMsg = e.localizedMessage ?: "Malformed response from $cleanMirror"
                } catch (e: ResponseTooLargeException) {
                    lastTerminalState = ProviderTerminalState.PARSER_ERROR
                    lastErrorMsg = e.localizedMessage ?: "Response exceeded safety size limit"
                } catch (e: HttpStatusException) {
                    lastHttpCode = e.statusCode
                    lastTerminalState = ProviderTerminalState.NETWORK_ERROR
                    lastErrorMsg = e.localizedMessage ?: "HTTP ${e.statusCode} on $cleanMirror"
                } catch (e: MirrorBlockedException) {
                    lastTerminalState = ProviderTerminalState.NETWORK_ERROR
                    lastErrorMsg = e.localizedMessage ?: "Mirror blocked on $cleanMirror"
                } catch (e: Exception) {
                    lastTerminalState = ProviderTerminalState.NETWORK_ERROR
                    lastErrorMsg = e.localizedMessage ?: "Mirror unreachable: $cleanMirror"
                }
            }
        }

        val elapsed = (clockMs() - startedMs).coerceAtLeast(1L)
        return emptyList<ProviderListing>() to IndexerSearchStatus(
            source = adapter.source,
            mirrorUsed = lastMirrorUsed,
            isLoading = false,
            resultCount = 0,
            errorMessage = lastErrorMsg ?: "${adapter.source.displayName} unavailable",
            webSearchUrl = webSearchUrl,
            terminalState = lastTerminalState ?: ProviderTerminalState.NETWORK_ERROR,
            httpStatusCode = lastHttpCode,
            retryAfterSeconds = lastRetryAfterSec,
            elapsedMs = elapsed
        )
    }

    fun buildOrderedMirrors(config: MirrorConfig): List<String> {
        val merged = config.mirrors
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .ifEmpty { config.provider.defaultMirrors }
            .distinct()
        if (config.provider == IndexerSource.X1337) {
            val hasKnaben = merged.any { isKnabenUrl(it) }
            if (hasKnaben) {
                val withoutKnaben = merged.filterNot { isKnabenUrl(it) }
                val knabenUrl = merged.first { isKnabenUrl(it) }
                return if (isKnabenUrl(config.activeMirrorUrl) || config.activeMirrorUrl.isBlank()) {
                    listOf(knabenUrl) + withoutKnaben
                } else {
                    val active = config.activeMirrorUrl.trim().trimEnd('/')
                    listOf(active, knabenUrl) + withoutKnaben.filterNot { it == active }
                }
            }
        }
        val active = config.activeMirrorUrl.trim().trimEnd('/')
        return if (active in merged) {
            listOf(active) + merged.filterNot { it == active }
        } else {
            merged
        }
    }

    fun buildWebSearchUrl(source: IndexerSource, mirrors: List<String>, query: String): String =
        adapters[source]?.buildWebSearchUrl(mirrors, query) ?: ""

    companion object {
        fun defaultAdapters(): Map<IndexerSource, ProviderAdapter> = mapOf(
            IndexerSource.X1337 to X1337Adapter(),
            IndexerSource.EZTV to EztvAdapter(),
            IndexerSource.YTS to YtsAdapter(),
            IndexerSource.TPB to TpbAdapter(),
            IndexerSource.NYAA to NyaaAdapter(),
            IndexerSource.LIMETORRENTS to LimeTorrentsAdapter()
        )
    }
}
