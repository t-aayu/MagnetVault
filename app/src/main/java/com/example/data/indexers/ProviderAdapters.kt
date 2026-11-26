package com.example.data.indexers

import com.example.data.network.HttpStatusException
import com.example.data.network.HttpTransport
import com.example.data.network.MirrorBlockedException
import com.example.data.network.RateLimitedException
import com.example.data.network.ResponseTooLargeException
import com.example.data.network.SearchNetworkLimits
import com.example.model.IndexerSource
import com.example.model.ProviderListing
import com.example.model.SortOption
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

interface ProviderAdapter {
    val source: IndexerSource
    val supportedCategories: Set<TorrentCategory>

    fun supportsCategory(category: TorrentCategory): Boolean =
        category == TorrentCategory.ALL || category in supportedCategories

    fun attemptTimeoutMs(limits: SearchNetworkLimits): Long = limits.defaultAttemptTimeoutMs

    fun buildWebSearchUrl(mirrors: List<String>, query: String): String

    suspend fun searchOnMirror(
        mirrorBaseUrl: String,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): List<TorrentResult>

    suspend fun resolveMagnet(
        listing: ProviderListing,
        orderedMirrors: List<String>,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): String? = null
}

internal fun isKnabenUrl(url: String): Boolean =
    url.contains("knaben.org", ignoreCase = true)

internal fun urlEncodeQuery(query: String): String =
    URLEncoder.encode(query.trim(), "UTF-8")

internal fun pathEncodeQuery(query: String): String =
    urlEncodeQuery(query).replace("+", "%20")

class X1337Adapter : ProviderAdapter {
    override val source: IndexerSource = IndexerSource.X1337

    override val supportedCategories: Set<TorrentCategory> = setOf(
        TorrentCategory.ALL,
        TorrentCategory.MOVIES,
        TorrentCategory.TV,
        TorrentCategory.ANIME,
        TorrentCategory.GAMES,
        TorrentCategory.APPS,
        TorrentCategory.MUSIC,
        TorrentCategory.OTHER
    )

    override fun attemptTimeoutMs(limits: SearchNetworkLimits): Long =
        limits.fastFailoverAttemptTimeoutMs

    override fun buildWebSearchUrl(mirrors: List<String>, query: String): String {
        val cleanQuery = query.trim()
        val pathEncoded = pathEncodeQuery(cleanQuery)
        val directMirror = mirrors
            .map { it.trim().trimEnd('/') }
            .firstOrNull { !isKnabenUrl(it) && it.isNotBlank() }
            ?: "https://1337x.to"
        return if (cleanQuery.isBlank()) directMirror else "$directMirror/search/$pathEncoded/1/"
    }

    override suspend fun searchOnMirror(
        mirrorBaseUrl: String,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): List<TorrentResult> {
        val cleanQuery = query.trim()
        val pathEncoded = pathEncodeQuery(cleanQuery)
        if (isKnabenUrl(mirrorBaseUrl)) {
            val firstJson = queryKnaben(
                endpoint = mirrorBaseUrl,
                query = cleanQuery,
                category = category,
                sortOption = sortOption,
                size = 150,
                transport = transport,
                limits = limits
            )
            val parsed = IndexerParsers.parseKnaben1337xJson(firstJson, category, strict1337xOnly = true)
            if (parsed.isNotEmpty() || category == TorrentCategory.ALL || category == TorrentCategory.OTHER) {
                return parsed
            }
            val broadJson = queryKnaben(
                endpoint = mirrorBaseUrl,
                query = cleanQuery,
                category = TorrentCategory.ALL,
                sortOption = sortOption,
                size = 150,
                transport = transport,
                limits = limits
            )
            return IndexerParsers.parseKnaben1337xJson(broadJson, category, strict1337xOnly = true)
        }

        val sortKey = when (sortOption) {
            SortOption.SEEDERS -> "seeders"
            SortOption.LEECHERS -> "leechers"
            SortOption.UPLOAD_DATE -> "time"
            SortOption.SIZE -> "size"
        }
        val catSegment = mapCategorySegment(category)
        val urlsToTry = if (catSegment != null) {
            listOf(
                "$mirrorBaseUrl/category-search/$pathEncoded/$catSegment/1/",
                "$mirrorBaseUrl/sort-category-search/$pathEncoded/$catSegment/$sortKey/desc/1/"
            )
        } else {
            listOf(
                "$mirrorBaseUrl/search/$pathEncoded/1/",
                "$mirrorBaseUrl/sort-search/$pathEncoded/$sortKey/desc/1/"
            )
        }

        var lastException: Exception? = null
        var sawUnblockedResponse = false
        for (url in urlsToTry) {
            try {
                val html = transport.get(url, maxResponseBytes = limits.maxResponseBytes)
                if (IndexerParsers.isCloudflareOrBlockedHtml(html)) {
                    throw MirrorBlockedException("Cloudflare challenge on $mirrorBaseUrl")
                }
                sawUnblockedResponse = true
                val parsed = IndexerParsers.parse1337xListHtml(html, mirrorBaseUrl, category)
                if (parsed.isNotEmpty()) return parsed
            } catch (e: CancellationException) {
                throw e
            } catch (e: MirrorBlockedException) {
                throw e
            } catch (e: RateLimitedException) {
                throw e
            } catch (e: ResponseTooLargeException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.statusCode == 403 || e.statusCode == 503) {
                    throw MirrorBlockedException("Cloudflare block (${e.message})")
                }
                lastException = e
            } catch (e: Exception) {
                lastException = e
            }
        }
        if (!sawUnblockedResponse && lastException != null) {
            throw lastException
        }
        return emptyList()
    }

    override suspend fun resolveMagnet(
        listing: ProviderListing,
        orderedMirrors: List<String>,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): String? {
        val candidateUrls = buildList {
            if (listing.detailPageUrl.isNotBlank()) {
                add(listing.detailPageUrl)
                val relPath = Regex("(/(?:torrent|ep)/[^?#\\s]+)", RegexOption.IGNORE_CASE)
                    .find(listing.detailPageUrl)?.groupValues?.getOrNull(1)
                if (!relPath.isNullOrBlank()) {
                    orderedMirrors
                        .filterNot { isKnabenUrl(it) }
                        .forEach { mirror ->
                            val candidate = "${mirror.trimEnd('/')}$relPath"
                            if (candidate !in this) add(candidate)
                        }
                }
            }
        }

        var lastError: Throwable? = null
        for (url in candidateUrls) {
            try {
                val html = transport.get(url, maxResponseBytes = limits.maxResponseBytes)
                if (IndexerParsers.isCloudflareOrBlockedHtml(html)) {
                    throw MirrorBlockedException("Mirror verification challenge on $url")
                }
                val magnet = IndexerParsers.extractMagnetFromDetailHtml(html, listing.title)
                if (!magnet.isNullOrBlank()) return magnet
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Fallback via Knaben 1337x JSON index
        val knabenUrl = orderedMirrors.firstOrNull { isKnabenUrl(it) }
            ?: IndexerAggregatorRepository.KNABEN_API_URL
        if (listing.title.isNotBlank()) {
            try {
                val json = queryKnaben(
                    endpoint = knabenUrl,
                    query = listing.title.take(100),
                    category = TorrentCategory.ALL,
                    sortOption = SortOption.SEEDERS,
                    size = 25,
                    transport = transport,
                    limits = limits
                )
                val hits = IndexerParsers.parseKnaben1337xJson(json, listing.category, strict1337xOnly = true)
                val matched = hits.firstOrNull { it.listingId == listing.listingId || it.id == "x1337_${listing.listingId}" }
                    ?: hits.firstOrNull { it.title.equals(listing.title, ignoreCase = true) }
                    ?: hits.firstOrNull()
                if (!matched?.magnetUri.isNullOrBlank()) {
                    return matched?.magnetUri
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }
        if (lastError != null) throw lastError
        return null
    }

    internal suspend fun fetchTrending(
        mirrorBaseUrl: String,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): List<TorrentResult> {
        return if (isKnabenUrl(mirrorBaseUrl)) {
            val json = queryKnaben(
                endpoint = mirrorBaseUrl,
                query = "",
                category = TorrentCategory.ALL,
                sortOption = SortOption.SEEDERS,
                size = 100,
                transport = transport,
                limits = limits
            )
            IndexerParsers.parseKnaben1337xJson(json, TorrentCategory.ALL).take(12)
        } else {
            val html = transport.get("$mirrorBaseUrl/trending", maxResponseBytes = limits.maxResponseBytes)
            if (IndexerParsers.isCloudflareOrBlockedHtml(html)) {
                throw MirrorBlockedException("Cloudflare challenge on $mirrorBaseUrl")
            }
            IndexerParsers.parse1337xListHtml(html, mirrorBaseUrl, TorrentCategory.ALL).take(10)
        }
    }

    private suspend fun queryKnaben(
        endpoint: String,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        size: Int,
        transport: HttpTransport,
        limits: SearchNetworkLimits
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
            val catIds = when (category) {
                TorrentCategory.MOVIES -> listOf(3000000)
                TorrentCategory.TV -> listOf(2000000)
                TorrentCategory.ANIME -> listOf(6000000)
                TorrentCategory.GAMES -> listOf(4001000, 7000000)
                TorrentCategory.APPS -> listOf(4000000)
                TorrentCategory.MUSIC -> listOf(1000000)
                TorrentCategory.ALL, TorrentCategory.OTHER -> emptyList()
            }
            if (catIds.isNotEmpty()) {
                put("categories", JSONArray(catIds))
            }
        }
        return transport.postJson(endpoint, payload.toString(), maxResponseBytes = limits.maxResponseBytes)
    }

    private fun mapCategorySegment(cat: TorrentCategory): String? = when (cat) {
        TorrentCategory.MOVIES -> "Movies"
        TorrentCategory.TV -> "TV"
        TorrentCategory.ANIME -> "Anime"
        TorrentCategory.GAMES -> "Games"
        TorrentCategory.APPS -> "Apps"
        TorrentCategory.MUSIC -> "Music"
        TorrentCategory.OTHER -> "Other"
        TorrentCategory.ALL -> null
    }
}

class EztvAdapter : ProviderAdapter {
    override val source: IndexerSource = IndexerSource.EZTV

    override val supportedCategories: Set<TorrentCategory> = setOf(
        TorrentCategory.ALL,
        TorrentCategory.TV
    )

    @Volatile
    private var cachedImdbQuery: String = ""

    @Volatile
    private var cachedImdbLookup: Pair<List<String>, List<String>> = Pair(emptyList(), emptyList())

    override fun buildWebSearchUrl(mirrors: List<String>, query: String): String {
        val cleanQuery = query.trim()
        val host = mirrors.map { it.trim().trimEnd('/') }.firstOrNull { it.isNotBlank() } ?: "https://eztvx.to"
        val slug = cleanQuery.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (slug.isBlank()) host else "$host/search/$slug"
    }

    override suspend fun searchOnMirror(
        mirrorBaseUrl: String,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): List<TorrentResult> {
        val cleanQuery = query.trim()
        val (imdbIds, extraFilterTokens) = getOrResolveImdb(cleanQuery, transport, limits)
        val combined = LinkedHashMap<String, TorrentResult>()
        var sawHealthyResponse = false
        var lastException: Exception? = null

        for (imdbId in imdbIds) {
            try {
                val apiJson = transport.get(
                    "$mirrorBaseUrl/api/get-torrents?imdb_id=$imdbId&limit=100&page=1",
                    maxResponseBytes = limits.maxResponseBytes
                )
                if (apiJson.trimStart().startsWith("{")) {
                    sawHealthyResponse = true
                    IndexerParsers.parseEztvApiJson(apiJson, extraFilterTokens).forEach { item ->
                        val dedupKey = item.canonicalHashHex.ifBlank { item.id }
                        combined.putIfAbsent(dedupKey, item)
                    }
                    if (combined.size >= 40) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitedException) {
                throw e
            } catch (e: ResponseTooLargeException) {
                throw e
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (combined.size < 15) {
            val slug = cleanQuery.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            if (slug.isNotBlank()) {
                try {
                    val html = transport.get(
                        url = "$mirrorBaseUrl/search/$slug",
                        headers = mapOf("Cookie" to "layout=def_wlinks;"),
                        maxResponseBytes = limits.maxResponseBytes
                    )
                    if (!IndexerParsers.isCloudflareOrBlockedHtml(html)) {
                        sawHealthyResponse = true
                        IndexerParsers.parseEztvSearchHtml(html, mirrorBaseUrl).forEach { item ->
                            val dedupKey = item.canonicalHashHex.ifBlank { item.id }
                            combined.putIfAbsent(dedupKey, item)
                        }
                    } else if (!sawHealthyResponse) {
                        lastException = MirrorBlockedException("Blocked on $mirrorBaseUrl")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RateLimitedException) {
                    throw e
                } catch (e: ResponseTooLargeException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                }
            }
        }

        if (combined.isEmpty()) {
            try {
                val apiJson = transport.get(
                    "$mirrorBaseUrl/api/get-torrents?limit=100&page=1",
                    maxResponseBytes = limits.maxResponseBytes
                )
                if (apiJson.trimStart().startsWith("{")) {
                    sawHealthyResponse = true
                    val tokens = cleanQuery.lowercase(Locale.US)
                        .replace(Regex("[._\\-]+"), " ")
                        .split(Regex("\\s+"))
                        .filter { it.isNotBlank() }
                    IndexerParsers.parseEztvApiJson(apiJson, tokens).forEach { item ->
                        val dedupKey = item.canonicalHashHex.ifBlank { item.id }
                        combined.putIfAbsent(dedupKey, item)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitedException) {
                throw e
            } catch (e: ResponseTooLargeException) {
                throw e
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (!sawHealthyResponse && lastException != null) {
            throw lastException
        }
        return combined.values.toList()
    }

    override suspend fun resolveMagnet(
        listing: ProviderListing,
        orderedMirrors: List<String>,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): String? {
        val url = listing.detailPageUrl
        if (url.isBlank()) return null
        val html = transport.get(url, maxResponseBytes = limits.maxResponseBytes)
        if (IndexerParsers.isCloudflareOrBlockedHtml(html)) {
            throw MirrorBlockedException("Mirror blocked on $url")
        }
        return IndexerParsers.extractMagnetFromDetailHtml(html, listing.title)
    }

    private suspend fun getOrResolveImdb(
        query: String,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): Pair<List<String>, List<String>> {
        if (cachedImdbQuery == query) return cachedImdbLookup

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
        val encoded = urlEncodeQuery(showName).replace("+", "%20")
        val url = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$encoded.json"
        val ids = try {
            val json = transport.get(
                url = url,
                headers = mapOf("Accept" to "application/json"),
                maxResponseBytes = limits.maxResponseBytes
            )
            IndexerParsers.parseImdbSuggestionIds(json)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val pair = ids to extraTokens
        cachedImdbQuery = query
        cachedImdbLookup = pair
        return pair
    }
}

class YtsAdapter : ProviderAdapter {
    override val source: IndexerSource = IndexerSource.YTS

    override val supportedCategories: Set<TorrentCategory> = setOf(
        TorrentCategory.ALL,
        TorrentCategory.MOVIES
    )

    override fun buildWebSearchUrl(mirrors: List<String>, query: String): String {
        val cleanQuery = query.trim()
        val pathEncoded = pathEncodeQuery(cleanQuery)
        val host = mirrors.map { it.trim().trimEnd('/') }.firstOrNull { it.isNotBlank() } ?: "https://yts.mx"
        return if (cleanQuery.isBlank()) host else "$host/browse-movies/$pathEncoded/all/all/0/latest/0/all"
    }

    override suspend fun searchOnMirror(
        mirrorBaseUrl: String,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): List<TorrentResult> {
        val encoded = urlEncodeQuery(query)
        val ytsSort = when (sortOption) {
            SortOption.SEEDERS -> "seeds"
            SortOption.LEECHERS -> "peers"
            SortOption.UPLOAD_DATE -> "date_added"
            SortOption.SIZE -> "year"
        }
        val json = transport.get(
            url = "$mirrorBaseUrl/api/v2/list_movies.json?query_term=$encoded&sort_by=$ytsSort&limit=25",
            maxResponseBytes = limits.maxResponseBytes
        )
        return IndexerParsers.parseYtsJson(json)
    }
}

class TpbAdapter : ProviderAdapter {
    override val source: IndexerSource = IndexerSource.TPB

    override val supportedCategories: Set<TorrentCategory> = setOf(
        TorrentCategory.ALL,
        TorrentCategory.MOVIES,
        TorrentCategory.TV,
        TorrentCategory.ANIME,
        TorrentCategory.GAMES,
        TorrentCategory.APPS,
        TorrentCategory.MUSIC,
        TorrentCategory.OTHER
    )

    override fun buildWebSearchUrl(mirrors: List<String>, query: String): String {
        val cleanQuery = query.trim()
        val encoded = urlEncodeQuery(cleanQuery)
        val host = mirrors.map { it.trim().trimEnd('/') }
            .firstOrNull { !it.contains("apibay.org", ignoreCase = true) && it.isNotBlank() }
            ?: "https://thepiratebay.org"
        return if (cleanQuery.isBlank()) host else "$host/search.php?q=$encoded"
    }

    override suspend fun searchOnMirror(
        mirrorBaseUrl: String,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): List<TorrentResult> {
        val encoded = urlEncodeQuery(query)
        val catCode = when (category) {
            TorrentCategory.MOVIES -> "201"
            TorrentCategory.TV -> "205"
            TorrentCategory.MUSIC -> "100"
            TorrentCategory.GAMES -> "400"
            TorrentCategory.APPS -> "300"
            else -> null
        }
        val url = if (catCode != null) {
            "$mirrorBaseUrl/q.php?q=$encoded&cat=$catCode"
        } else {
            "$mirrorBaseUrl/q.php?q=$encoded"
        }
        val json = transport.get(url, maxResponseBytes = limits.maxResponseBytes)
        return IndexerParsers.parseApiBayJson(json, mirrorBaseUrl)
    }
}

class NyaaAdapter : ProviderAdapter {
    override val source: IndexerSource = IndexerSource.NYAA

    override val supportedCategories: Set<TorrentCategory> = setOf(
        TorrentCategory.ALL,
        TorrentCategory.ANIME,
        TorrentCategory.MUSIC,
        TorrentCategory.APPS
    )

    override fun buildWebSearchUrl(mirrors: List<String>, query: String): String {
        val cleanQuery = query.trim()
        val encoded = urlEncodeQuery(cleanQuery)
        val host = mirrors.map { it.trim().trimEnd('/') }.firstOrNull { it.isNotBlank() } ?: "https://nyaa.si"
        return if (cleanQuery.isBlank()) host else "$host/?f=0&c=0_0&q=$encoded"
    }

    override suspend fun searchOnMirror(
        mirrorBaseUrl: String,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): List<TorrentResult> {
        val encoded = urlEncodeQuery(query)
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
        val xml = transport.get(
            url = "$mirrorBaseUrl/?page=rss&q=$encoded&c=$nyaaCat&s=$nyaaSort&o=desc",
            maxResponseBytes = limits.maxResponseBytes
        )
        return IndexerParsers.parseNyaaRss(xml)
    }
}

class LimeTorrentsAdapter : ProviderAdapter {
    override val source: IndexerSource = IndexerSource.LIMETORRENTS

    override val supportedCategories: Set<TorrentCategory> = setOf(
        TorrentCategory.ALL,
        TorrentCategory.MOVIES,
        TorrentCategory.TV,
        TorrentCategory.ANIME,
        TorrentCategory.GAMES,
        TorrentCategory.APPS,
        TorrentCategory.MUSIC,
        TorrentCategory.OTHER
    )

    override fun buildWebSearchUrl(mirrors: List<String>, query: String): String {
        val cleanQuery = query.trim()
        val pathEncoded = pathEncodeQuery(cleanQuery)
        val host = mirrors.map { it.trim().trimEnd('/') }.firstOrNull { it.isNotBlank() }
            ?: "https://www.limetorrents.lol"
        return if (cleanQuery.isBlank()) host else "$host/search/all/$pathEncoded/"
    }

    override suspend fun searchOnMirror(
        mirrorBaseUrl: String,
        query: String,
        category: TorrentCategory,
        sortOption: SortOption,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): List<TorrentResult> {
        val pathEncoded = pathEncodeQuery(query)
        val xml = transport.get(
            url = "$mirrorBaseUrl/searchrss/$pathEncoded/",
            maxResponseBytes = limits.maxResponseBytes
        )
        return IndexerParsers.parseLimeTorrentsRss(xml, category)
    }

    override suspend fun resolveMagnet(
        listing: ProviderListing,
        orderedMirrors: List<String>,
        transport: HttpTransport,
        limits: SearchNetworkLimits
    ): String? {
        if (listing.detailPageUrl.isBlank()) return null
        val html = transport.get(listing.detailPageUrl, maxResponseBytes = limits.maxResponseBytes)
        if (IndexerParsers.isCloudflareOrBlockedHtml(html)) {
            throw MirrorBlockedException("Mirror blocked on ${listing.detailPageUrl}")
        }
        return IndexerParsers.extractMagnetFromDetailHtml(html, listing.title)
    }
}
