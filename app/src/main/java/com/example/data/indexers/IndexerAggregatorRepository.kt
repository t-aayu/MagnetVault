package com.example.data.indexers

import com.example.data.network.HttpClientFactory
import com.example.model.IndexerSearchStatus
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.MirrorHealthResult
import com.example.model.QualityAndHealthParser
import com.example.model.SortOption
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class AggregatedSearchResult(
    val results: List<TorrentResult>,
    val statuses: List<IndexerSearchStatus>
)

private class MirrorBlockedException(message: String) : IllegalStateException(message)

class IndexerAggregatorRepository {
    companion object {
        const val KNABEN_API_URL = "https://api.knaben.org/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = HttpClientFactory.defaultClient
    private val pingClient = HttpClientFactory.pingClient

    suspend fun searchAll(
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        mirrorConfigs: Map<IndexerSource, MirrorConfig>,
        onProviderProgress: (IndexerSearchStatus) -> Unit = {}
    ): AggregatedSearchResult = coroutineScope {
        val enabledConfigs = IndexerSource.entries.mapNotNull { source ->
            val cfg = mirrorConfigs[source] ?: MirrorConfig(provider = source)
            if (cfg.enabled) cfg else null
        }

        val deferreds = enabledConfigs.map { config ->
            async(Dispatchers.IO) {
                val orderedMirrors = buildOrderedMirrors(config)
                val webUrl = buildWebSearchUrl(config.provider, orderedMirrors, query)
                onProviderProgress(
                    IndexerSearchStatus(
                        source = config.provider,
                        mirrorUsed = orderedMirrors.firstOrNull().orEmpty(),
                        isLoading = true,
                        webSearchUrl = webUrl
                    )
                )
                val outcome = searchSingleProviderWithFailover(
                    source = config.provider,
                    mirrors = orderedMirrors,
                    query = query,
                    category = category,
                    sortOption = sortOption
                )
                onProviderProgress(outcome.second)
                outcome
            }
        }

        val outcomes = deferreds.awaitAll()
        val combined = outcomes.flatMap { it.first }
        val statuses = outcomes.map { it.second }
        AggregatedSearchResult(results = combined, statuses = statuses)
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
            async(Dispatchers.IO) {
                val mirrors = buildOrderedMirrors(cfg)
                when (src) {
                    IndexerSource.TPB -> fetchWithMirrors(mirrors) { base ->
                        val body = executeGet("$base/precompiled/data_top100_all.json")
                        IndexerParsers.parseApiBayJson(body, base).take(12)
                    }.first
                    IndexerSource.YTS -> fetchWithMirrors(mirrors) { base ->
                        val body = executeGet("$base/api/v2/list_movies.json?sort_by=download_count&limit=10")
                        IndexerParsers.parseYtsJson(body).take(12)
                    }.first
                    IndexerSource.EZTV -> fetchWithMirrors(mirrors) { base ->
                        val body = executeGet("$base/api/get-torrents?limit=15&page=1")
                        IndexerParsers.parseEztvApiJson(body).take(10)
                    }.first
                    IndexerSource.X1337 -> fetchWithMirrors(mirrors, perMirrorTimeoutMs = 7_500L) { base ->
                        if (isKnabenEndpoint(base)) {
                            val json = queryKnaben1337x(
                                endpoint = base,
                                query = "",
                                category = TorrentCategory.ALL,
                                sortOption = SortOption.SEEDERS,
                                size = 100
                            )
                            IndexerParsers.parseKnaben1337xJson(json, TorrentCategory.ALL).take(12)
                        } else {
                            val html = executeGet("$base/trending")
                            if (IndexerParsers.isCloudflareOrBlockedHtml(html)) {
                                throw MirrorBlockedException("Cloudflare challenge on $base")
                            }
                            IndexerParsers.parse1337xListHtml(html, base, TorrentCategory.ALL).take(10)
                        }
                    }.first
                    IndexerSource.NYAA -> fetchWithMirrors(mirrors) { base ->
                        val body = executeGet("$base/?page=rss&s=seeders&o=desc")
                        IndexerParsers.parseNyaaRss(body).take(10)
                    }.first
                    else -> emptyList()
                }
            }
        }
        jobs.awaitAll()
            .flatten()
            .sortedByDescending { it.seeders }
    }

    /**
     * Resolves a lazy magnet link (e.g. direct 1337x detail page) with automatic failover across
     * all configured mirrors and a Knaben 1337x index fallback if direct mirrors are Cloudflare-blocked.
     */
    suspend fun resolveMagnet(
        torrent: TorrentResult,
        mirrorConfigs: Map<IndexerSource, MirrorConfig> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!torrent.magnetUri.isNullOrBlank()) {
            return@withContext Result.success(torrent.magnetUri)
        }
        if (torrent.infoHash.isNotBlank()) {
            return@withContext Result.success(
                QualityAndHealthParser.buildMagnetFromHash(torrent.infoHash, torrent.title)
            )
        }

        val candidateUrls = buildList {
            if (torrent.detailPageUrl.isNotBlank()) {
                add(torrent.detailPageUrl)
                val relPath = Regex("(/(?:torrent|ep)/[^?#\\s]+)", RegexOption.IGNORE_CASE)
                    .find(torrent.detailPageUrl)?.groupValues?.getOrNull(1)
                if (!relPath.isNullOrBlank()) {
                    val cfg = mirrorConfigs[torrent.source] ?: MirrorConfig(provider = torrent.source)
                    buildOrderedMirrors(cfg)
                        .filterNot { isKnabenEndpoint(it) }
                        .forEach { mirror ->
                            val candidate = "${mirror.trimEnd('/')}$relPath"
                            if (candidate !in this) add(candidate)
                        }
                }
            }
        }

        var lastError: Throwable? = null
        for (url in candidateUrls) {
            val attempt = withTimeoutOrNull(7_500L) {
                runCatching {
                    val html = executeGet(url)
                    if (IndexerParsers.isCloudflareOrBlockedHtml(html)) {
                        throw MirrorBlockedException("Mirror verification challenge on $url")
                    }
                    IndexerParsers.extractMagnetFromDetailHtml(html, torrent.title)
                }
            }
            val magnet = attempt?.getOrNull()
            if (!magnet.isNullOrBlank()) {
                return@withContext Result.success(magnet)
            }
            if (attempt?.exceptionOrNull() != null) {
                lastError = attempt.exceptionOrNull()
            }
        }

        // Fallback for 1337x: look up the exact torrent title via Knaben 1337x index to retrieve its magnet
        if (torrent.source == IndexerSource.X1337 && torrent.title.isNotBlank()) {
            val knabenMagnet = withTimeoutOrNull(8_000L) {
                runCatching {
                    val json = queryKnaben1337x(
                        endpoint = KNABEN_API_URL,
                        query = torrent.title.take(100),
                        category = TorrentCategory.ALL,
                        sortOption = SortOption.SEEDERS,
                        size = 25
                    )
                    val hits = IndexerParsers.parseKnaben1337xJson(json, torrent.category, strict1337xOnly = true)
                    val matched = hits.firstOrNull { it.id == torrent.id }
                        ?: hits.firstOrNull { it.title.equals(torrent.title, ignoreCase = true) }
                        ?: hits.firstOrNull()
                    matched?.magnetUri
                }.getOrNull()
            }
            if (!knabenMagnet.isNullOrBlank()) {
                return@withContext Result.success(knabenMagnet)
            }
        }

        Result.failure(
            lastError ?: IllegalStateException(
                "Magnet link not found on ${torrent.source.displayName} mirrors. Try switching mirror in Settings."
            )
        )
    }

    suspend fun checkMirrorHealth(url: String): MirrorHealthResult = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim().trimEnd('/')
        val start = System.currentTimeMillis()
        try {
            if (isKnabenEndpoint(cleanUrl)) {
                val payload = JSONObject().apply {
                    put("query", "ubuntu")
                    put("size", 1)
                }.toString()
                val request = Request.Builder()
                    .url(cleanUrl)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .build()
                pingClient.newCall(request).execute().use { resp ->
                    val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    val bodyPreview = runCatching { resp.body?.string()?.take(2000).orEmpty() }.getOrDefault("")
                    val reachable = resp.isSuccessful && bodyPreview.contains("hits")
                    return@withContext MirrorHealthResult(
                        url = cleanUrl,
                        isChecking = false,
                        isReachable = reachable,
                        latencyMs = elapsed,
                        statusCode = resp.code,
                        error = if (!reachable) "HTTP ${resp.code}" else null
                    )
                }
            }

            val probeUrl = when {
                cleanUrl.contains("apibay.org", true) -> "$cleanUrl/q.php?q=ubuntu"
                cleanUrl.contains("eztv", true) -> "$cleanUrl/api/get-torrents?limit=1&page=1"
                else -> cleanUrl
            }
            val request = Request.Builder().url(probeUrl).get().build()
            pingClient.newCall(request).execute().use { resp ->
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                val bodyPreview = runCatching { resp.body?.string()?.take(4000).orEmpty() }.getOrDefault("")
                val blocked = IndexerParsers.isCloudflareOrBlockedHtml(bodyPreview) ||
                    (resp.code == 403 && (resp.header("cf-mitigated") != null || resp.header("server")?.contains("cloudflare", true) == true))
                val reachable = resp.code in 200..399 && !blocked
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
                    }
                )
            }
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            MirrorHealthResult(
                url = cleanUrl,
                isChecking = false,
                isReachable = false,
                latencyMs = elapsed,
                statusCode = null,
                error = e.javaClass.simpleName
            )
        }
    }

    private suspend fun searchSingleProviderWithFailover(
        source: IndexerSource,
        mirrors: List<String>,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption
    ): Pair<List<TorrentResult>, IndexerSearchStatus> {
        val cleanQuery = query.trim()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val pathEncoded = encoded.replace("+", "%20")

        val webUrl = buildWebSearchUrl(source, mirrors, cleanQuery)

        if (source == IndexerSource.YTS && category != TorrentCategory.ALL && category != TorrentCategory.MOVIES) {
            return emptyList<TorrentResult>() to IndexerSearchStatus(source, mirrors.firstOrNull().orEmpty(), false, 0, null, webUrl)
        }
        if (source == IndexerSource.EZTV && category != TorrentCategory.ALL && category != TorrentCategory.TV) {
            return emptyList<TorrentResult>() to IndexerSearchStatus(source, mirrors.firstOrNull().orEmpty(), false, 0, null, webUrl)
        }
        if (source == IndexerSource.NYAA && category != TorrentCategory.ALL && category != TorrentCategory.ANIME && category != TorrentCategory.MUSIC && category != TorrentCategory.APPS) {
            return emptyList<TorrentResult>() to IndexerSearchStatus(source, mirrors.firstOrNull().orEmpty(), false, 0, null, webUrl)
        }

        val eztvImdbLookup = if (source == IndexerSource.EZTV) {
            resolveImdbIdsForShow(cleanQuery)
        } else {
            Pair(emptyList(), emptyList())
        }

        val perMirrorTimeout = if (source == IndexerSource.X1337) 7_500L else 12_000L
        val (items, usedMirror, lastError) = fetchWithMirrors(mirrors, perMirrorTimeoutMs = perMirrorTimeout) { base ->
            when (source) {
                IndexerSource.X1337 -> search1337xOnMirror(base, cleanQuery, pathEncoded, category, sortOption)
                IndexerSource.EZTV -> searchEztvOnMirror(
                    base = base,
                    query = cleanQuery,
                    imdbIds = eztvImdbLookup.first,
                    extraFilterTokens = eztvImdbLookup.second
                )
                IndexerSource.YTS -> {
                    val ytsSort = when (sortOption) {
                        SortOption.SEEDERS -> "seeds"
                        SortOption.LEECHERS -> "peers"
                        SortOption.UPLOAD_DATE -> "date_added"
                        SortOption.SIZE -> "year"
                    }
                    val json = executeGet("$base/api/v2/list_movies.json?query_term=$encoded&sort_by=$ytsSort&limit=25")
                    IndexerParsers.parseYtsJson(json)
                }
                IndexerSource.TPB -> {
                    val catCode = mapTpbCategoryCode(category)
                    val url = if (catCode != null) {
                        "$base/q.php?q=$encoded&cat=$catCode"
                    } else {
                        "$base/q.php?q=$encoded"
                    }
                    val json = executeGet(url)
                    IndexerParsers.parseApiBayJson(json, base)
                }
                IndexerSource.NYAA -> {
                    val nyaaCat = when (category) {
                        TorrentCategory.ANIME -> "1_0"
                        TorrentCategory.MUSIC -> "2_0"
                        TorrentCategory.APPS -> "6_2"
                        else -> "0_0"
                    }
                    val nyaaSort = when (sortOption) {
                        SortOption.SEEDERS -> "seeders"
                        SortOption.LEECHERS -> "leechers"
                        SortOption.UPLOAD_DATE -> "id"
                        SortOption.SIZE -> "size"
                    }
                    val xml = executeGet("$base/?page=rss&q=$encoded&c=$nyaaCat&s=$nyaaSort&o=desc")
                    IndexerParsers.parseNyaaRss(xml)
                }
                IndexerSource.LIMETORRENTS -> {
                    val xml = executeGet("$base/searchrss/$pathEncoded/")
                    IndexerParsers.parseLimeTorrentsRss(xml, category)
                }
            }
        }

        val status = IndexerSearchStatus(
            source = source,
            mirrorUsed = usedMirror,
            isLoading = false,
            resultCount = items.size,
            errorMessage = if (items.isEmpty()) lastError else null,
            webSearchUrl = webUrl
        )
        return items to status
    }

    /**
     * Builds the human-browsable HTML search page URL for an indexer so users can open it
     * directly inside the in-app HTML view or their default browser if API/scraping fails.
     */
    fun buildWebSearchUrl(
        source: IndexerSource,
        mirrors: List<String>,
        query: String
    ): String {
        val cleanQuery = query.trim()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val pathEncoded = encoded.replace("+", "%20")
        val directMirror = mirrors
            .map { it.trim().trimEnd('/') }
            .firstOrNull { !isKnabenEndpoint(it) && !it.contains("apibay.org", ignoreCase = true) }

        return when (source) {
            IndexerSource.X1337 -> {
                val host = directMirror ?: "https://1337x.to"
                if (cleanQuery.isBlank()) host else "$host/search/$pathEncoded/1/"
            }
            IndexerSource.EZTV -> {
                val host = directMirror ?: "https://eztvx.to"
                val slug = cleanQuery.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                if (slug.isBlank()) host else "$host/search/$slug"
            }
            IndexerSource.YTS -> {
                val host = directMirror ?: "https://yts.mx"
                if (cleanQuery.isBlank()) host else "$host/browse-movies/$pathEncoded/all/all/0/latest/0/all"
            }
            IndexerSource.TPB -> {
                val host = directMirror ?: "https://thepiratebay.org"
                if (cleanQuery.isBlank()) host else "$host/search.php?q=$encoded"
            }
            IndexerSource.NYAA -> {
                val host = directMirror ?: "https://nyaa.si"
                if (cleanQuery.isBlank()) host else "$host/?f=0&c=0_0&q=$encoded"
            }
            IndexerSource.LIMETORRENTS -> {
                val host = directMirror ?: "https://www.limetorrents.lol"
                if (cleanQuery.isBlank()) host else "$host/search/all/$pathEncoded/"
            }
        }
    }

    /**
     * Searches 1337x either via Knaben's Cloudflare-free 1337x JSON index (`api.knaben.org/v1`)
     * or via direct 1337x HTML mirrors (failing fast on Cloudflare 403/JS challenges so failover triggers immediately).
     */
    private fun search1337xOnMirror(
        base: String,
        rawQuery: String,
        pathEncoded: String,
        category: TorrentCategory,
        sortOption: SortOption
    ): List<TorrentResult> {
        if (isKnabenEndpoint(base)) {
            val firstJson = queryKnaben1337x(
                endpoint = base,
                query = rawQuery,
                category = category,
                sortOption = sortOption,
                size = 150
            )
            val parsed = IndexerParsers.parseKnaben1337xJson(firstJson, category, strict1337xOnly = true)
            if (parsed.isNotEmpty() || category == TorrentCategory.ALL || category == TorrentCategory.OTHER) {
                return parsed
            }
            // Retry without server-side category filter in case 1337x tagged the release under a sibling category
            val broadJson = queryKnaben1337x(
                endpoint = base,
                query = rawQuery,
                category = TorrentCategory.ALL,
                sortOption = sortOption,
                size = 150
            )
            return IndexerParsers.parseKnaben1337xJson(broadJson, category, strict1337xOnly = true)
        }

        val sortKey = when (sortOption) {
            SortOption.SEEDERS -> "seeders"
            SortOption.LEECHERS -> "leechers"
            SortOption.UPLOAD_DATE -> "time"
            SortOption.SIZE -> "size"
        }
        val catSegment = map1337xCategory(category)
        val urlsToTry = if (catSegment != null) {
            listOf(
                "$base/category-search/$pathEncoded/$catSegment/1/",
                "$base/sort-category-search/$pathEncoded/$catSegment/$sortKey/desc/1/"
            )
        } else {
            listOf(
                "$base/search/$pathEncoded/1/",
                "$base/sort-search/$pathEncoded/$sortKey/desc/1/"
            )
        }

        var lastException: Exception? = null
        var sawUnblockedResponse = false
        for (url in urlsToTry) {
            try {
                val html = executeGet(url)
                if (IndexerParsers.isCloudflareOrBlockedHtml(html)) {
                    throw MirrorBlockedException("Cloudflare challenge on $base")
                }
                sawUnblockedResponse = true
                val parsed = IndexerParsers.parse1337xListHtml(html, base, category)
                if (parsed.isNotEmpty()) return parsed
            } catch (e: MirrorBlockedException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                // On HTTP 403/503 (Cloudflare block), stop retrying paths on this host and fail over immediately
                if (e.message?.contains("HTTP 403") == true || e.message?.contains("HTTP 503") == true) {
                    throw MirrorBlockedException("Cloudflare block (${e.message})")
                }
            }
        }
        if (!sawUnblockedResponse && lastException != null) {
            throw lastException
        }
        return emptyList()
    }

    private fun queryKnaben1337x(
        endpoint: String,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        size: Int
    ): String {
        val orderBy = when (sortOption) {
            SortOption.SEEDERS -> "seeders"
            SortOption.LEECHERS -> "peers"
            SortOption.UPLOAD_DATE -> "date"
            SortOption.SIZE -> "bytes"
        }
        val payload = JSONObject().apply {
            put("query", query)
            put("search_field", "title")
            put("order_by", orderBy)
            put("order_direction", "desc")
            put("from", 0)
            put("size", size)
            put("hide_unsafe", true)
            put("hide_xxx", true)
            val catIds = mapKnabenCategoryIds(category)
            if (catIds.isNotEmpty()) {
                put("categories", JSONArray(catIds))
            }
        }
        return executePostJson(endpoint, payload.toString())
    }

    private fun mapKnabenCategoryIds(cat: TorrentCategory): List<Int> = when (cat) {
        TorrentCategory.MOVIES -> listOf(3000000)
        TorrentCategory.TV -> listOf(2000000)
        TorrentCategory.ANIME -> listOf(6000000)
        TorrentCategory.GAMES -> listOf(4001000, 7000000)
        TorrentCategory.APPS -> listOf(4000000)
        TorrentCategory.MUSIC -> listOf(1000000)
        TorrentCategory.ALL, TorrentCategory.OTHER -> emptyList()
    }

    private fun searchEztvOnMirror(
        base: String,
        query: String,
        imdbIds: List<String>,
        extraFilterTokens: List<String>
    ): List<TorrentResult> {
        val combined = LinkedHashMap<String, TorrentResult>()
        var sawHealthyResponse = false
        var lastException: Exception? = null

        for (imdbId in imdbIds) {
            try {
                val apiJson = executeGet("$base/api/get-torrents?imdb_id=$imdbId&limit=100&page=1")
                if (apiJson.trimStart().startsWith("{")) {
                    sawHealthyResponse = true
                    val parsed = IndexerParsers.parseEztvApiJson(apiJson, extraFilterTokens)
                    parsed.forEach { item ->
                        val dedupKey = item.infoHash.ifBlank { item.id }
                        combined.putIfAbsent(dedupKey, item)
                    }
                    if (combined.size >= 40) break
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (combined.size < 15) {
            val slug = query.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            if (slug.isNotBlank()) {
                try {
                    val html = executeGet(
                        url = "$base/search/$slug",
                        extraHeaders = mapOf("Cookie" to "layout=def_wlinks;")
                    )
                    if (!IndexerParsers.isCloudflareOrBlockedHtml(html)) {
                        sawHealthyResponse = true
                        IndexerParsers.parseEztvSearchHtml(html, base).forEach { item ->
                            val dedupKey = item.infoHash.ifBlank { item.id }
                            combined.putIfAbsent(dedupKey, item)
                        }
                    }
                } catch (e: Exception) {
                    lastException = e
                }
            }
        }

        if (combined.isEmpty()) {
            try {
                val apiJson = executeGet("$base/api/get-torrents?limit=100&page=1")
                if (apiJson.trimStart().startsWith("{")) {
                    sawHealthyResponse = true
                    val tokens = query.lowercase(Locale.US)
                        .replace(Regex("[._\\-]+"), " ")
                        .split(Regex("\\s+"))
                        .filter { it.isNotBlank() }
                    IndexerParsers.parseEztvApiJson(apiJson, tokens).forEach { item ->
                        val dedupKey = item.infoHash.ifBlank { item.id }
                        combined.putIfAbsent(dedupKey, item)
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (!sawHealthyResponse && lastException != null) {
            throw lastException
        }
        return combined.values.toList()
    }

    private fun resolveImdbIdsForShow(query: String): Pair<List<String>, List<String>> {
        val rawTokens = query.trim().lowercase(Locale.US)
            .replace(Regex("[._\\-]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val episodeOrQualityRegex = Regex(
            "^(?:s\\d{1,2}e\\d{1,2}|s\\d{1,2}|e\\d{1,2}|\\d{1,2}x\\d{1,2}|2160p|1080p|720p|480p|4k|hevc|x265|x264|h265|h264|webrip|web|hdtv|bluray|complete)$",
            RegexOption.IGNORE_CASE
        )
        val extraTokens = rawTokens.filter { episodeOrQualityRegex.matches(it) }
        val showTokens = rawTokens.filterNot { episodeOrQualityRegex.matches(it) }
        val showName = (if (showTokens.isNotEmpty()) showTokens else rawTokens).joinToString(" ").trim()
        if (showName.isBlank()) return emptyList<String>() to extraTokens

        val firstChar = showName.firstOrNull { it.isLetterOrDigit() }?.lowercaseChar() ?: 'x'
        val encoded = URLEncoder.encode(showName, "UTF-8").replace("+", "%20")
        val url = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$encoded.json"
        val ids = runCatching {
            val json = executeGet(url, extraHeaders = mapOf("Accept" to "application/json"))
            IndexerParsers.parseImdbSuggestionIds(json)
        }.getOrDefault(emptyList())
        return ids to extraTokens
    }

    private suspend fun fetchWithMirrors(
        mirrors: List<String>,
        perMirrorTimeoutMs: Long = 12_000L,
        fetcher: suspend (String) -> List<TorrentResult>
    ): Triple<List<TorrentResult>, String, String?> {
        var lastError: String? = null
        var lastMirror = mirrors.firstOrNull().orEmpty()
        var hadCleanZeroResultMirror = false

        for (mirror in mirrors) {
            val clean = mirror.trim().trimEnd('/')
            lastMirror = clean
            val attempt = withTimeoutOrNull(perMirrorTimeoutMs) {
                runCatching { fetcher(clean) }
            }
            if (attempt == null) {
                lastError = "Timeout on $clean"
                continue
            }
            if (attempt.isSuccess) {
                val list = attempt.getOrDefault(emptyList())
                if (list.isNotEmpty()) {
                    return Triple(list, clean, null)
                }
                hadCleanZeroResultMirror = true
            } else {
                lastError = attempt.exceptionOrNull()?.localizedMessage ?: "Mirror unreachable"
            }
        }
        return Triple(
            emptyList(),
            lastMirror,
            if (hadCleanZeroResultMirror) null else lastError
        )
    }

    private fun executeGet(url: String, extraHeaders: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).get()
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} from ${request.url.host}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun executePostJson(url: String, jsonBody: String): String {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} from ${request.url.host}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun isKnabenEndpoint(url: String): Boolean =
        url.contains("knaben.org", ignoreCase = true)

    private fun buildOrderedMirrors(config: MirrorConfig): List<String> {
        val merged = (config.mirrors + config.provider.defaultMirrors).distinct()
        if (config.provider == IndexerSource.X1337) {
            // Keep Knaben's Cloudflare-free 1337x index immediately after the active mirror (or first)
            // so users with previously saved Cloudflare-blocked mirrors fail over in <1s.
            val withoutKnaben = merged.filterNot { isKnabenEndpoint(it) }
            return if (isKnabenEndpoint(config.activeMirrorUrl) || config.activeMirrorUrl.isBlank()) {
                listOf(KNABEN_API_URL) + withoutKnaben
            } else {
                val active = config.activeMirrorUrl
                listOf(active, KNABEN_API_URL) + withoutKnaben.filterNot { it == active }
            }
        }
        return if (config.activeMirrorUrl in merged) {
            listOf(config.activeMirrorUrl) + merged.filterNot { it == config.activeMirrorUrl }
        } else {
            merged
        }
    }

    private fun map1337xCategory(cat: TorrentCategory): String? = when (cat) {
        TorrentCategory.MOVIES -> "Movies"
        TorrentCategory.TV -> "TV"
        TorrentCategory.ANIME -> "Anime"
        TorrentCategory.GAMES -> "Games"
        TorrentCategory.APPS -> "Apps"
        TorrentCategory.MUSIC -> "Music"
        TorrentCategory.OTHER -> "Other"
        TorrentCategory.ALL -> null
    }

    private fun mapTpbCategoryCode(cat: TorrentCategory): String? = when (cat) {
        TorrentCategory.MOVIES -> "201"
        TorrentCategory.TV -> "205"
        TorrentCategory.MUSIC -> "100"
        TorrentCategory.GAMES -> "400"
        TorrentCategory.APPS -> "300"
        else -> null
    }
}
