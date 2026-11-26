package com.example.data.indexers

import android.util.Xml
import androidx.core.text.HtmlCompat
import com.example.data.network.MirrorBlockedException
import com.example.domain.magnet.MagnetParser
import com.example.domain.magnet.TorrentHashFamily
import com.example.model.IndexerSource
import com.example.model.QualityAndHealthParser
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ParserException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

object IndexerParsers {

    private fun decodeHtml(input: String): String =
        HtmlCompat.fromHtml(input, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun formatEpoch(epochSeconds: Long?): String {
        if (epochSeconds == null || epochSeconds <= 0L) return "Unknown"
        val fmt = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        return fmt.format(Date(epochSeconds * 1000L))
    }

    /**
     * Detects Cloudflare interstitials, bot challenges, or access-denied HTML shells
     * so the aggregator fails over to the next mirror instead of stopping on HTTP 200.
     */
    fun isCloudflareOrBlockedHtml(html: String): Boolean {
        if (html.isBlank()) return true
        val sample = html.take(6000).lowercase(Locale.US)
        return sample.contains("<title>just a moment") ||
            sample.contains("cf-browser-verification") ||
            sample.contains("attention required! | cloudflare") ||
            sample.contains("challenge-platform") ||
            sample.contains("enable javascript and cookies to continue") ||
            sample.contains("checking your browser before accessing") ||
            sample.contains("ddos-guard")
    }

    private fun parseOptionalInt(obj: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val raw = obj.opt(key) ?: continue
            when (raw) {
                is Number -> return raw.toInt().coerceAtLeast(0)
                is String -> raw.trim().replace(",", "").toIntOrNull()?.let { return it.coerceAtLeast(0) }
            }
        }
        return null
    }

    private fun parseOptionalLong(obj: JSONObject, vararg keys: String): Long? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val raw = obj.opt(key) ?: continue
            when (raw) {
                is Number -> return raw.toLong().coerceAtLeast(0L)
                is String -> raw.trim().replace(",", "").toLongOrNull()?.let { return it.coerceAtLeast(0L) }
            }
        }
        return null
    }

    // --- 1. THE PIRATE BAY (ApiBay JSON API) ---
    fun parseApiBayJson(jsonBody: String, baseUrl: String): List<TorrentResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("[")) {
            throw ParserException("Expected JSON array from ApiBay")
        }
        val arr = runCatching { JSONArray(trimmed) }.getOrElse {
            throw ParserException("Malformed ApiBay JSON array", it)
        }
        val results = mutableListOf<TorrentResult>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            val name = obj.optString("name", "").trim()
            val rawHash = obj.optString("info_hash", "").trim()
            if (id == "0" || name.equals("No results returned", ignoreCase = true)) continue
            val verifiedHash = MagnetParser.normalizeHash(rawHash) ?: continue
            if (name.isBlank() || verifiedHash.canonicalHex.all { it == '0' }) continue

            val knownSeeders = parseOptionalInt(obj, "seeders")
            val knownLeechers = parseOptionalInt(obj, "leechers")
            val knownSizeBytes = parseOptionalLong(obj, "size")
            val knownAddedEpoch = parseOptionalLong(obj, "added")?.takeIf { it > 0L }
            val catCode = obj.optString("category", "0").toIntOrNull() ?: 0
            val category = mapTpbCategory(catCode)
            val magnet = MagnetParser.buildMagnetFromHash(verifiedHash.canonicalHex, name, verifiedHash.family)
            val listingId = id.ifBlank { verifiedHash.canonicalHex.take(16) }

            results += TorrentResult(
                id = "tpb_$listingId",
                title = name,
                source = IndexerSource.TPB,
                category = category,
                sizeLabel = QualityAndHealthParser.formatKnownBytes(knownSizeBytes),
                sizeBytes = knownSizeBytes ?: 0L,
                seeders = knownSeeders ?: 0,
                leechers = knownLeechers ?: 0,
                uploadDateLabel = formatEpoch(knownAddedEpoch),
                uploadEpochSeconds = knownAddedEpoch ?: 0L,
                magnetUri = magnet,
                infoHash = verifiedHash.canonicalHex,
                detailPageUrl = "${baseUrl.trimEnd('/')}/t.php?id=$listingId",
                swarmHealth = QualityAndHealthParser.computeSwarmHealth(knownSeeders, knownLeechers),
                listingId = listingId,
                knownSizeBytes = knownSizeBytes,
                knownSeeders = knownSeeders,
                knownLeechers = knownLeechers,
                knownUploadEpochSeconds = knownAddedEpoch,
                canonicalHashHex = verifiedHash.canonicalHex,
                canonicalHashFamily = verifiedHash.family
            )
        }
        return results
    }

    private fun mapTpbCategory(code: Int): TorrentCategory = when (code) {
        in 100..199 -> TorrentCategory.MUSIC
        201, 202, 207, 209 -> TorrentCategory.MOVIES
        205, 208 -> TorrentCategory.TV
        in 200..299 -> TorrentCategory.MOVIES
        in 300..399 -> TorrentCategory.APPS
        in 400..499 -> TorrentCategory.GAMES
        else -> TorrentCategory.OTHER
    }

    // --- 2. YTS (JSON API v2) ---
    fun parseYtsJson(jsonBody: String): List<TorrentResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) {
            throw ParserException("Expected JSON object from YTS")
        }
        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            throw ParserException("Malformed YTS JSON response", it)
        }
        if (root.optString("status", "").equals("error", ignoreCase = true)) {
            throw ParserException(root.optString("status_message", "YTS API error"))
        }
        val data = root.optJSONObject("data")
            ?: throw ParserException("YTS JSON missing data payload")
        val movies = data.optJSONArray("movies") ?: return emptyList()
        val results = mutableListOf<TorrentResult>()

        for (i in 0 until movies.length()) {
            val movie = movies.optJSONObject(i) ?: continue
            val titleLong = movie.optString("title_long", movie.optString("title", "")).trim()
            if (titleLong.isBlank()) continue
            val movieUrl = movie.optString("url", "")
            val torrents = movie.optJSONArray("torrents") ?: continue
            for (j in 0 until torrents.length()) {
                val t = torrents.optJSONObject(j) ?: continue
                val verifiedHash = MagnetParser.normalizeHash(t.optString("hash", "")) ?: continue
                val quality = t.optString("quality", "")
                val type = t.optString("type", "").uppercase(Locale.US)
                val codec = t.optString("video_codec", "")
                val fullTitle = buildString {
                    append(titleLong)
                    if (quality.isNotBlank()) append(" [$quality]")
                    if (type.isNotBlank()) append(" [$type]")
                    if (codec.isNotBlank() && !codec.equals("x264", true)) append(" [$codec]")
                }
                val rawSizeStr = t.optString("size", "").trim()
                val knownSizeBytes = parseOptionalLong(t, "size_bytes")?.takeIf { it > 0L }
                    ?: QualityAndHealthParser.parseSizeToKnownBytes(rawSizeStr)
                val sizeLabel = rawSizeStr.ifBlank { QualityAndHealthParser.formatKnownBytes(knownSizeBytes) }
                val knownSeeds = parseOptionalInt(t, "seeds")
                val knownPeers = parseOptionalInt(t, "peers")
                val knownEpoch = parseOptionalLong(t, "date_uploaded_unix")?.takeIf { it > 0L }
                val dateLabel = t.optString("date_uploaded", "").substringBefore(" ")
                    .trim()
                    .ifBlank { formatEpoch(knownEpoch) }
                val listingId = verifiedHash.canonicalHex.take(16)

                results += TorrentResult(
                    id = "yts_$listingId",
                    title = fullTitle,
                    source = IndexerSource.YTS,
                    category = TorrentCategory.MOVIES,
                    sizeLabel = sizeLabel,
                    sizeBytes = knownSizeBytes ?: 0L,
                    seeders = knownSeeds ?: 0,
                    leechers = knownPeers ?: 0,
                    uploadDateLabel = dateLabel,
                    uploadEpochSeconds = knownEpoch ?: 0L,
                    magnetUri = MagnetParser.buildMagnetFromHash(verifiedHash.canonicalHex, fullTitle, verifiedHash.family),
                    infoHash = verifiedHash.canonicalHex,
                    detailPageUrl = movieUrl,
                    swarmHealth = QualityAndHealthParser.computeSwarmHealth(knownSeeds, knownPeers),
                    listingId = listingId,
                    knownSizeBytes = knownSizeBytes,
                    knownSeeders = knownSeeds,
                    knownLeechers = knownPeers,
                    knownUploadEpochSeconds = knownEpoch,
                    canonicalHashHex = verifiedHash.canonicalHex,
                    canonicalHashFamily = verifiedHash.family
                )
            }
        }
        return results
    }

    // --- 3. EZTV (IMDb Suggestion ID Resolution + JSON API + HTML Search) ---
    fun parseImdbSuggestionIds(jsonBody: String): List<String> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("d") ?: return emptyList()
        val tvIds = mutableListOf<String>()
        val fallbackIds = mutableListOf<String>()
        val idRegex = Regex("^tt(\\d+)$", RegexOption.IGNORE_CASE)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val rawId = item.optString("id", "").trim()
            val digits = idRegex.find(rawId)?.groupValues?.getOrNull(1) ?: continue
            val qid = item.optString("qid", "").lowercase(Locale.US)
            val q = item.optString("q", "").lowercase(Locale.US)
            if (qid.contains("tv") || q.contains("tv") || qid == "tvseries" || qid == "tvminiseries" || qid == "tvepisode") {
                tvIds += digits
            } else {
                fallbackIds += digits
            }
        }
        return (tvIds + fallbackIds).distinct().take(3)
    }

    fun parseEztvApiJson(
        jsonBody: String,
        filterTokens: List<String> = emptyList()
    ): List<TorrentResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) {
            throw ParserException("Expected JSON object from EZTV API")
        }
        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            throw ParserException("Malformed EZTV JSON response", it)
        }
        val arr = root.optJSONArray("torrents") ?: run {
            if (root.has("torrents_count")) return emptyList()
            throw ParserException("EZTV JSON missing torrents array")
        }
        val normalizedTokens = filterTokens.map { it.lowercase(Locale.US) }.filter { it.isNotBlank() }
        val results = mutableListOf<TorrentResult>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawTitle = obj.optString("title", "").trim()
            val filename = obj.optString("filename", "").trim()
            val title = rawTitle.ifBlank { filename }
            if (title.isBlank()) continue
            if (normalizedTokens.isNotEmpty()) {
                val searchable = "$title $filename"
                    .lowercase(Locale.US)
                    .replace(Regex("[._\\-]+"), " ")
                if (!normalizedTokens.all { searchable.contains(it) }) {
                    continue
                }
            }
            val magnet = obj.optString("magnet_url", "").trim()
            val verifiedFromMagnet = MagnetParser.extractVerifiedIdentities(magnet).firstOrNull()
            val verifiedFromField = MagnetParser.normalizeHash(obj.optString("hash", ""))
            val verified = verifiedFromField ?: verifiedFromMagnet
            val finalMagnet = magnet.ifBlank {
                verified?.let { MagnetParser.buildMagnetFromHash(it.canonicalHex, title, it.family) }.orEmpty()
            }
            val knownSizeBytes = parseOptionalLong(obj, "size_bytes")
            val knownSeeds = parseOptionalInt(obj, "seeds")
            val knownPeers = parseOptionalInt(obj, "peers")
            val knownEpoch = parseOptionalLong(obj, "date_released_unix")?.takeIf { it > 0L }
            val stableId = verified?.canonicalHex ?: obj.optString("id", title.hashCode().toUInt().toString())

            results += TorrentResult(
                id = "eztv_$stableId",
                title = title,
                source = IndexerSource.EZTV,
                category = TorrentCategory.TV,
                sizeLabel = QualityAndHealthParser.formatKnownBytes(knownSizeBytes),
                sizeBytes = knownSizeBytes ?: 0L,
                seeders = knownSeeds ?: 0,
                leechers = knownPeers ?: 0,
                uploadDateLabel = formatEpoch(knownEpoch),
                uploadEpochSeconds = knownEpoch ?: 0L,
                magnetUri = finalMagnet.ifBlank { null },
                infoHash = verified?.canonicalHex.orEmpty(),
                detailPageUrl = obj.optString("episode_url", ""),
                swarmHealth = QualityAndHealthParser.computeSwarmHealth(knownSeeds, knownPeers),
                listingId = stableId,
                knownSizeBytes = knownSizeBytes,
                knownSeeders = knownSeeds,
                knownLeechers = knownPeers,
                knownUploadEpochSeconds = knownEpoch,
                canonicalHashHex = verified?.canonicalHex.orEmpty(),
                canonicalHashFamily = verified?.family
            )
        }
        return results
    }

    fun parseEztvSearchHtml(html: String, baseUrl: String): List<TorrentResult> {
        if (isCloudflareOrBlockedHtml(html)) {
            throw MirrorBlockedException("Blocked or challenge page on $baseUrl")
        }
        val lowerHtml = html.lowercase(Locale.US)
        if (!lowerHtml.contains("<table") && !lowerHtml.contains("<tr") && !lowerHtml.contains("<html")) {
            throw ParserException("Unrecognized EZTV HTML response")
        }
        val results = mutableListOf<TorrentResult>()
        val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val epLinkRegex = Regex(
            "<a[^>]*href=[\"']([^\"']*/ep/[^\"']+)[\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val titleAttrRegex = Regex("title=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val magnetRegex = Regex("href=[\"'](magnet:\\?xt=[^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val tdRegex = Regex("<td[^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val sizePattern = Regex("^\\d+(?:\\.\\d+)?\\s*(?:TB|GB|MB|KB|B)$", RegexOption.IGNORE_CASE)
        val datePattern = Regex("^\\d+\\s*[smhdwy](?:\\s+\\d+[smhdw])?$", RegexOption.IGNORE_CASE)

        for (match in rowRegex.findAll(html)) {
            val row = match.groupValues[1]
            val epMatch = epLinkRegex.find(row) ?: continue
            val rawHref = epMatch.groupValues[1].trim()
            val innerText = decodeHtml(epMatch.groupValues[2])
            val title = innerText.ifBlank {
                titleAttrRegex.find(epMatch.value)?.groupValues?.getOrNull(1)?.let { decodeHtml(it) }.orEmpty()
            }.trim()
            if (title.isBlank()) continue

            val magnet = magnetRegex.find(row)?.groupValues?.getOrNull(1)?.let { decodeHtml(it) }
            val tds = tdRegex.findAll(row).map { decodeHtml(it.groupValues[1]).trim() }.toList()

            val rawSizeText = tds.firstOrNull { sizePattern.matches(it) }
                ?: tds.getOrNull(3)?.takeIf { it.isNotBlank() && it != "-" && it != "—" }
            val knownSizeBytes = rawSizeText?.let { QualityAndHealthParser.parseSizeToKnownBytes(it) }
            val sizeLabel = rawSizeText ?: "Unknown"

            val rawDateText = tds.firstOrNull { datePattern.matches(it) }
                ?: tds.getOrNull(4)?.takeIf { it.isNotBlank() && it != "-" && it != "—" }
            val dateLabel = rawDateText ?: "Unknown"
            val knownEpoch = rawDateText?.let { estimateRelativeEpochOrNull(it) }

            val knownSeeds = tds.lastOrNull { it.replace(",", "").toIntOrNull() != null }
                ?.replace(",", "")
                ?.toIntOrNull()
                ?.coerceAtLeast(0)

            val verified = MagnetParser.extractVerifiedIdentities(magnet).firstOrNull()
            val fullDetailUrl = if (rawHref.startsWith("http", ignoreCase = true)) {
                rawHref
            } else {
                "${baseUrl.trimEnd('/')}/${rawHref.trimStart('/')}"
            }
            val finalMagnet = magnet?.ifBlank { null } ?: verified?.let {
                MagnetParser.buildMagnetFromHash(it.canonicalHex, title, it.family)
            }
            val listingId = verified?.canonicalHex ?: rawHref.hashCode().toUInt().toString()

            results += TorrentResult(
                id = "eztv_$listingId",
                title = title,
                source = IndexerSource.EZTV,
                category = TorrentCategory.TV,
                sizeLabel = sizeLabel,
                sizeBytes = knownSizeBytes ?: 0L,
                seeders = knownSeeds ?: 0,
                leechers = 0,
                uploadDateLabel = dateLabel,
                uploadEpochSeconds = knownEpoch ?: 0L,
                magnetUri = finalMagnet,
                infoHash = verified?.canonicalHex.orEmpty(),
                detailPageUrl = fullDetailUrl,
                swarmHealth = QualityAndHealthParser.computeSwarmHealth(knownSeeds, null),
                listingId = listingId,
                knownSizeBytes = knownSizeBytes,
                knownSeeders = knownSeeds,
                knownLeechers = null, // EZTV HTML table does not report leecher count
                knownUploadEpochSeconds = knownEpoch,
                canonicalHashHex = verified?.canonicalHex.orEmpty(),
                canonicalHashFamily = verified?.family
            )
            if (results.size >= 50) break
        }
        return results
    }

    // --- 4. NYAA (RSS XML) ---
    fun parseNyaaRss(xmlBody: String): List<TorrentResult> {
        val trimmed = xmlBody.trim()
        if (!trimmed.startsWith("<")) {
            throw ParserException("Expected XML feed from Nyaa")
        }
        val results = mutableListOf<TorrentResult>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(trimmed))

        var sawRssOrFeedRoot = false
        var inItem = false
        var title = ""
        var link = ""
        var pubDate = ""
        var seeders: Int? = null
        var leechers: Int? = null
        var sizeLabel = ""
        var infoHashRaw = ""
        var categoryText = ""

        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase(Locale.US)
                        if (tag == "rss" || tag == "channel" || tag == "feed") {
                            sawRssOrFeedRoot = true
                        }
                        if (tag == "item") {
                            inItem = true
                            title = ""
                            link = ""
                            pubDate = ""
                            seeders = null
                            leechers = null
                            sizeLabel = ""
                            infoHashRaw = ""
                            categoryText = ""
                        } else if (inItem) {
                            when (tag) {
                                "title" -> title = parser.nextText().trim()
                                "guid", "link" -> {
                                    val text = parser.nextText().trim()
                                    if (link.isBlank() || tag == "guid") link = text
                                }
                                "pubdate" -> pubDate = parser.nextText().trim()
                                "nyaa:seeders" -> seeders = parser.nextText().trim().toIntOrNull()?.coerceAtLeast(0)
                                "nyaa:leechers" -> leechers = parser.nextText().trim().toIntOrNull()?.coerceAtLeast(0)
                                "nyaa:size" -> sizeLabel = parser.nextText().trim()
                                "nyaa:infohash" -> infoHashRaw = parser.nextText().trim()
                                "nyaa:category" -> categoryText = parser.nextText().trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("item", ignoreCase = true) && inItem) {
                            inItem = false
                            val verified = MagnetParser.normalizeHash(infoHashRaw)
                            if (title.isNotBlank() && verified != null) {
                                val knownEpoch = parseRssDateEpochOrNull(pubDate)
                                val knownSize = QualityAndHealthParser.parseSizeToKnownBytes(sizeLabel)
                                val displayTitle = decodeHtml(title)
                                val listingId = verified.canonicalHex.take(16)
                                results += TorrentResult(
                                    id = "nyaa_$listingId",
                                    title = displayTitle,
                                    source = IndexerSource.NYAA,
                                    category = mapNyaaCategory(categoryText),
                                    sizeLabel = sizeLabel.ifBlank { QualityAndHealthParser.formatKnownBytes(knownSize) },
                                    sizeBytes = knownSize ?: 0L,
                                    seeders = seeders ?: 0,
                                    leechers = leechers ?: 0,
                                    uploadDateLabel = formatEpoch(knownEpoch),
                                    uploadEpochSeconds = knownEpoch ?: 0L,
                                    magnetUri = MagnetParser.buildMagnetFromHash(verified.canonicalHex, displayTitle, verified.family),
                                    infoHash = verified.canonicalHex,
                                    detailPageUrl = link,
                                    swarmHealth = QualityAndHealthParser.computeSwarmHealth(seeders, leechers),
                                    listingId = listingId,
                                    knownSizeBytes = knownSize,
                                    knownSeeders = seeders,
                                    knownLeechers = leechers,
                                    knownUploadEpochSeconds = knownEpoch,
                                    canonicalHashHex = verified.canonicalHex,
                                    canonicalHashFamily = verified.family
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            throw ParserException("Malformed Nyaa RSS XML", e)
        }
        if (!sawRssOrFeedRoot || inItem) {
            throw ParserException("Unexpected or truncated RSS XML response from Nyaa")
        }
        return results
    }

    private fun mapNyaaCategory(raw: String): TorrentCategory {
        val lower = raw.lowercase(Locale.US)
        return when {
            lower.contains("anime") -> TorrentCategory.ANIME
            lower.contains("audio") -> TorrentCategory.MUSIC
            lower.contains("software") -> TorrentCategory.APPS
            lower.contains("live action") -> TorrentCategory.MOVIES
            else -> TorrentCategory.ANIME
        }
    }

    // --- 5. 1337x (Hardened HTML Search + Detail Magnet / InfoHash Extraction) ---
    fun parse1337xListHtml(
        html: String,
        baseUrl: String,
        fallbackCategory: TorrentCategory
    ): List<TorrentResult> {
        if (isCloudflareOrBlockedHtml(html)) {
            throw MirrorBlockedException("Cloudflare or challenge page on $baseUrl")
        }
        val lowerSample = html.lowercase(Locale.US)
        val looksLike1337xShell = lowerSample.contains("table-list") ||
            lowerSample.contains("/torrent/") ||
            lowerSample.contains("no results were returned") ||
            lowerSample.contains("search-page") ||
            lowerSample.contains("box-info")
        if (!looksLike1337xShell) {
            throw ParserException("Unrecognized 1337x HTML payload")
        }

        val results = mutableListOf<TorrentResult>()
        val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val linkRegex = Regex(
            "<a[^>]*href=[\"']((?:https?://[^\"']+)?/torrent/\\d+/[^\"']+)[\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val seedsRegex = Regex("<td[^>]*class=[\"'][^\"']*(?:seeds|coll-2)[^\"']*[\"'][^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val leechRegex = Regex("<td[^>]*class=[\"'][^\"']*(?:leeches|coll-3)[^\"']*[\"'][^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val dateRegex = Regex("<td[^>]*class=[\"'][^\"']*coll-date[^\"']*[\"'][^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val sizeRegex = Regex("<td[^>]*class=[\"'][^\"']*(?:size|coll-4)[^\"']*[\"'][^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val tdRegex = Regex("<td[^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val spanStripRegex = Regex("<span[^>]*>.*?</span>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val sizeTokenRegex = Regex("([0-9]+(?:\\.[0-9]+)?\\s*(?:TB|GB|MB|KB|B))", RegexOption.IGNORE_CASE)

        for (rowMatch in rowRegex.findAll(html)) {
            val row = rowMatch.groupValues[1]
            val linkMatch = linkRegex.find(row) ?: continue
            val rawHref = linkMatch.groupValues[1].trim()
            val title = decodeHtml(linkMatch.groupValues[2]).trim()
            if (title.isBlank()) continue

            val rawTds = tdRegex.findAll(row).map { it.groupValues[1] }.toList()

            val knownSeeds = seedsRegex.find(row)?.groupValues?.getOrNull(1)
                ?.let { decodeHtml(it).replace(",", "").trim().toIntOrNull() }
                ?: rawTds.getOrNull(1)?.let { decodeHtml(it).replace(",", "").trim().toIntOrNull() }

            val knownLeeches = leechRegex.find(row)?.groupValues?.getOrNull(1)
                ?.let { decodeHtml(it).replace(",", "").trim().toIntOrNull() }
                ?: rawTds.getOrNull(2)?.let { decodeHtml(it).replace(",", "").trim().toIntOrNull() }

            val dateRaw = dateRegex.find(row)?.groupValues?.getOrNull(1)
                ?.let { decodeHtml(it).trim() }
                ?.takeIf { it.isNotBlank() && it != "-" && it != "—" }
                ?: rawTds.getOrNull(3)?.let { decodeHtml(it).trim() }?.takeIf { it.isNotBlank() && it != "-" && it != "—" }
            val dateLabel = dateRaw ?: "Unknown"
            val knownEpoch = dateRaw?.let { estimateRelativeEpochOrNull(it) }

            val rawSizeHtml = sizeRegex.find(row)?.groupValues?.getOrNull(1)
                ?: rawTds.getOrNull(4)
                ?: ""
            val cleanedSize = decodeHtml(rawSizeHtml.replace(spanStripRegex, "")).trim()
            val matchedSizeToken = sizeTokenRegex.find(cleanedSize)?.groupValues?.getOrNull(1)
            val knownSizeBytes = QualityAndHealthParser.parseSizeToKnownBytes(matchedSizeToken ?: cleanedSize)
            val sizeLabel = matchedSizeToken
                ?: cleanedSize.takeIf { it.isNotBlank() && it != "-" && it != "—" }
                ?: "Unknown"

            val detailPath = Regex("(/torrent/\\d+/[^\"'?#\\s]+)").find(rawHref)?.groupValues?.getOrNull(1) ?: rawHref
            val fullDetailUrl = if (rawHref.startsWith("http", ignoreCase = true)) {
                rawHref
            } else {
                "${baseUrl.trimEnd('/')}/${detailPath.trimStart('/')}"
            }
            val torrentId = Regex("/torrent/(\\d+)/").find(detailPath)?.groupValues?.getOrNull(1)
                ?: detailPath.hashCode().toUInt().toString()

            results += TorrentResult(
                id = "x1337_$torrentId",
                title = title,
                source = IndexerSource.X1337,
                category = inferCategoryFromRowOrFallback(row, fallbackCategory),
                sizeLabel = sizeLabel,
                sizeBytes = knownSizeBytes ?: 0L,
                seeders = knownSeeds?.coerceAtLeast(0) ?: 0,
                leechers = knownLeeches?.coerceAtLeast(0) ?: 0,
                uploadDateLabel = dateLabel,
                uploadEpochSeconds = knownEpoch ?: 0L,
                magnetUri = null, // Resolved lazily on tap with multi-mirror failover
                infoHash = "",
                detailPageUrl = fullDetailUrl,
                swarmHealth = QualityAndHealthParser.computeSwarmHealth(knownSeeds?.coerceAtLeast(0), knownLeeches?.coerceAtLeast(0)),
                listingId = torrentId,
                knownSizeBytes = knownSizeBytes,
                knownSeeders = knownSeeds?.coerceAtLeast(0),
                knownLeechers = knownLeeches?.coerceAtLeast(0),
                knownUploadEpochSeconds = knownEpoch
            )
            if (results.size >= 45) break
        }
        return results
    }

    private fun inferCategoryFromRowOrFallback(rowHtml: String, fallback: TorrentCategory): TorrentCategory {
        if (fallback != TorrentCategory.ALL) return fallback
        val lower = rowHtml.lowercase(Locale.US)
        return when {
            lower.contains("flaticon-movies") || lower.contains("/sub/42/") || lower.contains("/sub/1/") -> TorrentCategory.MOVIES
            lower.contains("flaticon-tv") || lower.contains("/sub/41/") || lower.contains("/sub/5/") -> TorrentCategory.TV
            lower.contains("flaticon-anime") || lower.contains("/sub/28/") -> TorrentCategory.ANIME
            lower.contains("flaticon-games") || lower.contains("/sub/10/") -> TorrentCategory.GAMES
            lower.contains("flaticon-apps") || lower.contains("/sub/18/") -> TorrentCategory.APPS
            lower.contains("flaticon-music") || lower.contains("/sub/22/") -> TorrentCategory.MUSIC
            else -> TorrentCategory.OTHER
        }
    }

    fun extractMagnetFromDetailHtml(html: String, fallbackTitle: String = "torrent"): String? {
        if (isCloudflareOrBlockedHtml(html)) return null
        val magnetRegex = Regex("(magnet:\\?xt=urn:(?:btih|btmh):[^\"'\\s<>]+)", RegexOption.IGNORE_CASE)
        val raw = magnetRegex.find(html)?.groupValues?.getOrNull(1)
        if (!raw.isNullOrBlank()) {
            val decoded = decodeHtml(raw)
            if (MagnetParser.extractVerifiedIdentities(decoded).isNotEmpty()) {
                return decoded
            }
        }
        // Fallback: 1337x detail pages also expose raw 40-char SHA1 hash inside <div class="infohash-box">
        val infoHashBoxRegex = Regex(
            "infohash-box[^>]*>.*?<span[^>]*>\\s*([a-fA-F0-9]{40}|[A-Za-z2-7]{32})\\s*</span>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val hash = infoHashBoxRegex.find(html)?.groupValues?.getOrNull(1)
        val verified = MagnetParser.normalizeHash(hash, TorrentHashFamily.BTIH)
        return verified?.let {
            MagnetParser.buildMagnetFromHash(it.canonicalHex, fallbackTitle, it.family)
        }
    }

    // --- 5B. 1337x via KNABEN DATABASE JSON API (Cloudflare-Free Index) ---
    fun parseKnaben1337xJson(
        jsonBody: String,
        fallbackCategory: TorrentCategory,
        strict1337xOnly: Boolean = true
    ): List<TorrentResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) {
            throw ParserException("Expected JSON object from Knaben")
        }
        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            throw ParserException("Malformed Knaben JSON", it)
        }
        val arr = root.optJSONArray("hits") ?: root.optJSONArray("data")
            ?: throw ParserException("Knaben JSON missing hits/data array")
        val results = mutableListOf<TorrentResult>()
        val seenKeys = HashSet<String>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val origin = obj.optString("cachedOrigin", obj.optString("tracker", "")).trim()
            val details = obj.optString("details", "").trim()
            val rawId = obj.optString("id", "").trim()
            val is1337x = origin.contains("1337x", ignoreCase = true) ||
                details.contains("1337x", ignoreCase = true) ||
                rawId.startsWith("1337x", ignoreCase = true)
            if (strict1337xOnly && !is1337x) continue

            val title = obj.optString("title", "").trim()
            if (title.isBlank()) continue

            val rawMagnet = obj.optString("magnetUrl", obj.optString("magnet", "")).trim()
            val verifiedFromMagnet = MagnetParser.extractVerifiedIdentities(rawMagnet).firstOrNull()
            val verifiedFromField = MagnetParser.normalizeHash(obj.optString("hash", ""))
            val verified = verifiedFromField ?: verifiedFromMagnet
            val finalMagnet = rawMagnet.ifBlank {
                verified?.let { MagnetParser.buildMagnetFromHash(it.canonicalHex, title, it.family) }.orEmpty()
            }
            if (finalMagnet.isBlank()) continue

            val dedupKey = verified?.storageKey ?: rawId.ifBlank { title.lowercase(Locale.US) }
            if (!seenKeys.add(dedupKey)) continue

            val knownSizeBytes = parseOptionalLong(obj, "bytes")?.takeIf { it > 0L }
                ?: QualityAndHealthParser.parseSizeToKnownBytes(obj.optString("size", ""))
            val sizeLabel = obj.optString("size", "").trim().ifBlank {
                QualityAndHealthParser.formatKnownBytes(knownSizeBytes)
            }
            val knownSeeders = parseOptionalInt(obj, "seeders")
            val knownLeechers = parseOptionalInt(obj, "peers", "leechers")
            val dateRaw = obj.optString("date", "")
            val knownEpoch = parseIsoDateEpochOrNull(dateRaw)
            val cachedId = obj.optString("cachedId", "").trim()
            val stableId = cachedId.ifBlank {
                verified?.canonicalHex?.take(16) ?: rawId.ifBlank { title.hashCode().toUInt().toString() }
            }

            results += TorrentResult(
                id = "x1337_$stableId",
                title = title,
                source = IndexerSource.X1337,
                category = inferKnabenCategory(obj, fallbackCategory),
                sizeLabel = sizeLabel,
                sizeBytes = knownSizeBytes ?: 0L,
                seeders = knownSeeders ?: 0,
                leechers = knownLeechers ?: 0,
                uploadDateLabel = formatEpoch(knownEpoch),
                uploadEpochSeconds = knownEpoch ?: 0L,
                magnetUri = finalMagnet,
                infoHash = verified?.canonicalHex.orEmpty(),
                detailPageUrl = details,
                swarmHealth = QualityAndHealthParser.computeSwarmHealth(knownSeeders, knownLeechers),
                listingId = stableId,
                knownSizeBytes = knownSizeBytes,
                knownSeeders = knownSeeders,
                knownLeechers = knownLeechers,
                knownUploadEpochSeconds = knownEpoch,
                canonicalHashHex = verified?.canonicalHex.orEmpty(),
                canonicalHashFamily = verified?.family
            )
            if (results.size >= 50) break
        }
        return results
    }

    private fun inferKnabenCategory(obj: JSONObject, fallback: TorrentCategory): TorrentCategory {
        if (fallback != TorrentCategory.ALL) return fallback
        val catIds = obj.optJSONArray("categoryId")
        if (catIds != null) {
            for (i in 0 until catIds.length()) {
                val id = catIds.optInt(i, 0)
                when {
                    id in 1000000..1999999 -> return TorrentCategory.MUSIC
                    id in 2000000..2999999 -> return TorrentCategory.TV
                    id in 3000000..3999999 -> return TorrentCategory.MOVIES
                    id in 4001000..4001999 || id in 7000000..7999999 -> return TorrentCategory.GAMES
                    id in 4000000..4999999 -> return TorrentCategory.APPS
                    id in 6000000..6999999 -> return TorrentCategory.ANIME
                }
            }
        }
        val cats = obj.optJSONArray("category")
        if (cats != null) {
            val joined = buildString {
                for (i in 0 until cats.length()) {
                    append(cats.optString(i, ""))
                    append(' ')
                }
            }.lowercase(Locale.US)
            return when {
                joined.contains("anime") -> TorrentCategory.ANIME
                joined.contains("movie") -> TorrentCategory.MOVIES
                joined.contains("tv") || joined.contains("television") -> TorrentCategory.TV
                joined.contains("game") -> TorrentCategory.GAMES
                joined.contains("audio") || joined.contains("music") -> TorrentCategory.MUSIC
                joined.contains("software") || joined.contains("pc") || joined.contains("app") -> TorrentCategory.APPS
                else -> TorrentCategory.OTHER
            }
        }
        return TorrentCategory.OTHER
    }

    private fun parseIsoDateEpochOrNull(rawDate: String): Long? {
        if (rawDate.isBlank()) return null
        val cleaned = rawDate.trim()
            .replace("Z", "+0000")
            .replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2")
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in formats) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = sdf.parse(cleaned)
                if (parsed != null) return parsed.time / 1000L
            }
        }
        return null
    }

    // --- 6. LIMETORRENTS (RSS XML) ---
    fun parseLimeTorrentsRss(xmlBody: String, fallbackCategory: TorrentCategory): List<TorrentResult> {
        val trimmed = xmlBody.trim()
        if (!trimmed.startsWith("<")) {
            throw ParserException("Expected XML feed from LimeTorrents")
        }
        val results = mutableListOf<TorrentResult>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(trimmed))

        var sawRssOrFeedRoot = false
        var inItem = false
        var title = ""
        var link = ""
        var pubDate = ""
        var description = ""
        var knownSizeBytes: Long? = null
        var enclosureUrl = ""

        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase(Locale.US)
                        if (tag == "rss" || tag == "channel" || tag == "feed") {
                            sawRssOrFeedRoot = true
                        }
                        if (tag == "item") {
                            inItem = true
                            title = ""
                            link = ""
                            pubDate = ""
                            description = ""
                            knownSizeBytes = null
                            enclosureUrl = ""
                        } else if (inItem) {
                            if (tag == "enclosure") {
                                enclosureUrl = parser.getAttributeValue(null, "url") ?: ""
                                parser.getAttributeValue(null, "length")?.toLongOrNull()?.takeIf { it > 0L }?.let {
                                    knownSizeBytes = it
                                }
                            } else {
                                when (tag) {
                                    "title" -> title = parser.nextText().trim()
                                    "link" -> link = parser.nextText().trim()
                                    "pubdate" -> pubDate = parser.nextText().trim()
                                    "description" -> description = parser.nextText().trim()
                                    "size" -> parser.nextText().trim().toLongOrNull()?.takeIf { it >= 0L }?.let { knownSizeBytes = it }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("item", ignoreCase = true) && inItem) {
                            inItem = false
                            if (title.isNotBlank()) {
                                val knownSeeds = Regex("Seeds:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                                    .find(description)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0)
                                val knownLeeches = Regex("Leechers:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                                    .find(description)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0)
                                val rawHash = Regex("/([A-Fa-f0-9]{40})\\.torrent").find(enclosureUrl)?.groupValues?.getOrNull(1)
                                    .orEmpty()
                                val verified = MagnetParser.normalizeHash(rawHash, TorrentHashFamily.BTIH)
                                val displayTitle = decodeHtml(title)
                                val magnet = verified?.let {
                                    MagnetParser.buildMagnetFromHash(it.canonicalHex, displayTitle, it.family)
                                }
                                val knownEpoch = parseRssDateEpochOrNull(pubDate)
                                val listingId = verified?.canonicalHex?.take(16) ?: link.hashCode().toUInt().toString()

                                results += TorrentResult(
                                    id = "lime_$listingId",
                                    title = displayTitle,
                                    source = IndexerSource.LIMETORRENTS,
                                    category = fallbackCategory,
                                    sizeLabel = QualityAndHealthParser.formatKnownBytes(knownSizeBytes),
                                    sizeBytes = knownSizeBytes ?: 0L,
                                    seeders = knownSeeds ?: 0,
                                    leechers = knownLeeches ?: 0,
                                    uploadDateLabel = formatEpoch(knownEpoch),
                                    uploadEpochSeconds = knownEpoch ?: 0L,
                                    magnetUri = magnet,
                                    infoHash = verified?.canonicalHex.orEmpty(),
                                    detailPageUrl = link,
                                    swarmHealth = QualityAndHealthParser.computeSwarmHealth(knownSeeds, knownLeeches),
                                    listingId = listingId,
                                    knownSizeBytes = knownSizeBytes,
                                    knownSeeders = knownSeeds,
                                    knownLeechers = knownLeeches,
                                    knownUploadEpochSeconds = knownEpoch,
                                    canonicalHashHex = verified?.canonicalHex.orEmpty(),
                                    canonicalHashFamily = verified?.family
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            throw ParserException("Malformed LimeTorrents RSS XML", e)
        }
        if (!sawRssOrFeedRoot || inItem) {
            throw ParserException("Unexpected or truncated RSS XML response from LimeTorrents")
        }
        return results
    }

    private fun parseRssDateEpochOrNull(rawDate: String): Long? {
        if (rawDate.isBlank()) return null
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (pattern in formats) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = sdf.parse(rawDate.trim())
                if (parsed != null) return parsed.time / 1000L
            }
        }
        return null
    }

    private fun estimateRelativeEpochOrNull(label: String): Long? {
        val cleaned = label.trim()
        if (cleaned.isBlank() || cleaned == "—" || cleaned == "-" || cleaned.equals("unknown", true)) {
            return null
        }
        val now = System.currentTimeMillis() / 1000L
        val lower = cleaned.lowercase(Locale.US)
        val num = Regex("(\\d+)").find(lower)?.groupValues?.getOrNull(1)?.toLongOrNull()
        return when {
            lower.contains("min") || lower.contains("am") || lower.contains("pm") || lower.contains("today") ->
                now - (num ?: 1L) * 60L
            lower.contains("hour") || lower.contains("hr") || lower.endsWith("h") ->
                now - (num ?: 1L) * 3600L
            lower.contains("day") || lower.contains("d ") || lower.endsWith("d") ->
                now - (num ?: 1L) * 86400L
            lower.contains("week") || lower.endsWith("w") ->
                now - (num ?: 1L) * 7L * 86400L
            lower.contains("month") || lower.contains("mo") ->
                now - (num ?: 1L) * 30L * 86400L
            lower.contains("year") || lower.contains("yr") || lower.endsWith("y") ->
                now - (num ?: 1L) * 365L * 86400L
            Regex("[a-zA-Z]{3}\\.?\\s+\\d{1,2}").containsMatchIn(lower) -> {
                // Absolute date string like "Nov. 15th '26"
                val sanitized = lower.replace(Regex("(st|nd|rd|th)"), "")
                    .replace("'", " 20")
                    .replace(".", "")
                    .trim()
                val parsed = listOf("MMM d yy", "MMM d 20yy", "MMM dd 20yy").firstNotNullOfOrNull { pat ->
                    runCatching {
                        SimpleDateFormat(pat, Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }.parse(sanitized)?.time?.div(1000L)
                    }.getOrNull()
                }
                parsed ?: (now - 86400L * 3L)
            }
            else -> null
        }
    }
}
