package com.example.data.indexers

import com.example.data.network.HttpTransport
import com.example.data.network.OkHttpTransport
import com.example.data.network.SearchNetworkLimits
import com.example.model.IndexerSearchStatus
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.MirrorHealthResult
import com.example.model.ParserCheckStatus
import com.example.model.ProviderListing
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class AggregatedSearchResult(
    val results: List<TorrentResult>,
    val statuses: List<IndexerSearchStatus>
)

class IndexerAggregatorRepository(
    private val transport: HttpTransport = OkHttpTransport(),
    val limits: SearchNetworkLimits = SearchNetworkLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val KNABEN_API_URL = "https://api.knaben.org/v1"
    }

    private val adapters: Map<IndexerSource, ProviderAdapter> = SearchCoordinator.defaultAdapters()

    val coordinator: SearchCoordinator = SearchCoordinator(
        adapters = adapters,
        transport = transport,
        limits = limits,
        ioDispatcher = ioDispatcher
    )

    val sessionSnapshots: StateFlow<SearchSessionSnapshot>
        get() = coordinator.snapshots

    suspend fun searchAll(
        query: String,
        category: TorrentCategory,
        sortOption: com.example.model.SortOption,
        mirrorConfigs: Map<IndexerSource, MirrorConfig>,
        onProviderProgress: (IndexerSearchStatus) -> Unit = {}
    ): AggregatedSearchResult {
        val sessionId = coordinator.startSearch(query, category, sortOption, mirrorConfigs)
        val terminalSnapshot = coordinator.snapshots.first { snap ->
            snap.sessionId == sessionId && !snap.isSearching
        }
        terminalSnapshot.statuses.forEach(onProviderProgress)
        return AggregatedSearchResult(
            results = terminalSnapshot.groups.map { it.toRepresentativeTorrentResult() },
            statuses = terminalSnapshot.statuses
        )
    }

    suspend fun fetchTrending(
        mirrorConfigs: Map<IndexerSource, MirrorConfig>
    ): List<TorrentResult> = coroutineScope {
        val sources = listOf(
            IndexerSource.TPB,
            IndexerSource.YTS,
            IndexerSource.EZTV,
            IndexerSource.X1337,
            IndexerSource.NYAA
        )
        val jobs = sources.mapNotNull { src ->
            val cfg = mirrorConfigs[src] ?: MirrorConfig(provider = src)
            if (!cfg.enabled) return@mapNotNull null
            async(ioDispatcher) {
                val mirrors = coordinator.buildOrderedMirrors(cfg)
                fetchTrendingOnMirrors(src, mirrors)
            }
        }
        jobs.awaitAll()
            .flatten()
            .sortedByDescending { it.knownSeeders ?: it.seeders }
    }

    private suspend fun fetchTrendingOnMirrors(
        source: IndexerSource,
        mirrors: List<String>
    ): List<TorrentResult> {
        val x1337Adapter = adapters[IndexerSource.X1337] as? X1337Adapter
        val timeout = if (source == IndexerSource.X1337) limits.fastFailoverAttemptTimeoutMs else limits.defaultAttemptTimeoutMs
        for (mirror in mirrors) {
            val base = mirror.trim().trimEnd('/')
            val list = withTimeoutOrNull(timeout) {
                try {
                    when (source) {
                        IndexerSource.TPB -> {
                            val body = transport.get("$base/precompiled/data_top100_all.json", maxResponseBytes = limits.maxResponseBytes)
                            IndexerParsers.parseApiBayJson(body, base).take(12)
                        }
                        IndexerSource.YTS -> {
                            val body = transport.get("$base/api/v2/list_movies.json?sort_by=download_count&limit=10", maxResponseBytes = limits.maxResponseBytes)
                            IndexerParsers.parseYtsJson(body).take(12)
                        }
                        IndexerSource.EZTV -> {
                            val body = transport.get("$base/api/get-torrents?limit=15&page=1", maxResponseBytes = limits.maxResponseBytes)
                            IndexerParsers.parseEztvApiJson(body).take(10)
                        }
                        IndexerSource.X1337 -> {
                            x1337Adapter?.fetchTrending(base, transport, limits).orEmpty()
                        }
                        IndexerSource.NYAA -> {
                            val body = transport.get("$base/?page=rss&s=seeders&o=desc", maxResponseBytes = limits.maxResponseBytes)
                            IndexerParsers.parseNyaaRss(body).take(10)
                        }
                        else -> emptyList()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (!list.isNullOrEmpty()) return list
        }
        return emptyList()
    }

    suspend fun resolveMagnet(
        torrent: TorrentResult,
        mirrorConfigs: Map<IndexerSource, MirrorConfig> = emptyMap()
    ): Result<String> {
        val listing = ProviderListing.fromTorrentResult(torrent)
        return coordinator.resolveStandaloneMagnet(listing, mirrorConfigs)
    }

    suspend fun checkMirrorHealth(
        url: String,
        sourceHint: IndexerSource? = null,
        verifyParser: Boolean = false
    ): MirrorHealthResult = withContext(ioDispatcher) {
        val cleanUrl = url.trim().trimEnd('/')
        val start = System.currentTimeMillis()
        try {
            if (isKnabenUrl(cleanUrl)) {
                val payload = JSONObject().apply {
                    put("query", "ubuntu")
                    put("size", 1)
                }.toString()
                val resp = transport.probe(cleanUrl, postJsonBody = payload)
                val now = System.currentTimeMillis()
                val elapsed = (now - start).coerceAtLeast(1L)
                val httpOk = resp.code in 200..299
                val parserStatus = if (httpOk) {
                    val parsed = runCatching {
                        IndexerParsers.parseKnaben1337xJson(resp.body, TorrentCategory.ALL)
                    }
                    if (parsed.isSuccess && resp.body.contains("hits")) {
                        ParserCheckStatus.VERIFIED
                    } else {
                        ParserCheckStatus.FAILED
                    }
                } else {
                    ParserCheckStatus.NEVER_RUN
                }
                val parserDetail = when (parserStatus) {
                    ParserCheckStatus.VERIFIED -> "JSON hits schema verified"
                    ParserCheckStatus.FAILED -> "HTTP ${resp.code} reachable, unexpected JSON"
                    ParserCheckStatus.NEVER_RUN -> "HTTP error prevented parser check"
                }
                return@withContext MirrorHealthResult(
                    url = cleanUrl,
                    isChecking = false,
                    isReachable = httpOk,
                    latencyMs = elapsed,
                    statusCode = resp.code,
                    error = if (!httpOk) "HTTP ${resp.code}" else null,
                    checkedAtEpochMs = now,
                    parserStatus = parserStatus,
                    parserDetail = parserDetail
                )
            }

            if (verifyParser && sourceHint != null) {
                val adapter = adapters[sourceHint]
                if (adapter != null) {
                    val parsed = adapter.searchOnMirror(
                        mirrorBaseUrl = cleanUrl,
                        query = "ubuntu",
                        category = TorrentCategory.ALL,
                        sortOption = com.example.model.SortOption.SEEDERS,
                        transport = transport,
                        limits = limits
                    )
                    val now = System.currentTimeMillis()
                    val elapsed = (now - start).coerceAtLeast(1L)
                    return@withContext MirrorHealthResult(
                        url = cleanUrl,
                        isChecking = false,
                        isReachable = true,
                        latencyMs = elapsed,
                        statusCode = 200,
                        error = null,
                        checkedAtEpochMs = now,
                        parserStatus = if (parsed.isNotEmpty()) ParserCheckStatus.VERIFIED else ParserCheckStatus.FAILED,
                        parserDetail = if (parsed.isNotEmpty()) {
                            "Parsed ${parsed.size} release(s)"
                        } else {
                            "Search returned 0 parsed rows"
                        }
                    )
                }
            }

            val probeUrl = when {
                cleanUrl.contains("apibay.org", true) -> "$cleanUrl/q.php?q=ubuntu"
                cleanUrl.contains("eztv", true) -> "$cleanUrl/api/get-torrents?limit=1&page=1"
                else -> cleanUrl
            }
            val resp = transport.probe(probeUrl)
            val now = System.currentTimeMillis()
            val elapsed = (now - start).coerceAtLeast(1L)
            val blocked = IndexerParsers.isCloudflareOrBlockedHtml(resp.body) ||
                (resp.code == 403 && (resp.headers["cf-mitigated"] != null || resp.headers["server"]?.contains("cloudflare", true) == true))
            val reachable = resp.code in 200..399 && !blocked
            val (parserStatus, parserDetail) = when {
                !reachable -> ParserCheckStatus.NEVER_RUN to "HTTP probe failed before parser check"
                cleanUrl.contains("apibay.org", true) -> {
                    val ok = runCatching { IndexerParsers.parseApiBayJson(resp.body, cleanUrl) }.isSuccess
                    if (ok) ParserCheckStatus.VERIFIED to "ApiBay JSON schema verified"
                    else ParserCheckStatus.FAILED to "Invalid ApiBay JSON response"
                }
                cleanUrl.contains("eztv", true) -> {
                    val ok = runCatching { IndexerParsers.parseEztvApiJson(resp.body) }.isSuccess
                    if (ok) ParserCheckStatus.VERIFIED to "EZTV JSON schema verified"
                    else ParserCheckStatus.FAILED to "Invalid EZTV JSON response"
                }
                else -> ParserCheckStatus.NEVER_RUN to "HTTP reachability only • tap Verify Parser to test search output"
            }
            MirrorHealthResult(
                url = cleanUrl,
                isChecking = false,
                isReachable = reachable,
                latencyMs = elapsed,
                statusCode = resp.code,
                error = when {
                    blocked -> "Cloudflare"
                    resp.code !in 200..399 -> "HTTP ${resp.code}"
                    else -> null
                },
                checkedAtEpochMs = now,
                parserStatus = parserStatus,
                parserDetail = parserDetail
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            val elapsed = (now - start).coerceAtLeast(1L)
            val parserStatus = if (verifyParser) ParserCheckStatus.FAILED else ParserCheckStatus.NEVER_RUN
            MirrorHealthResult(
                url = cleanUrl,
                isChecking = false,
                isReachable = false,
                latencyMs = elapsed,
                statusCode = null,
                error = e.javaClass.simpleName,
                checkedAtEpochMs = now,
                parserStatus = parserStatus,
                parserDetail = if (verifyParser) (e.message?.take(64) ?: e.javaClass.simpleName) else null
            )
        }
    }

    fun buildWebSearchUrl(
        source: IndexerSource,
        mirrors: List<String>,
        query: String
    ): String = coordinator.buildWebSearchUrl(source, mirrors, query)
}
