package com.example.data.indexers

import android.util.Xml
import androidx.core.text.HtmlCompat
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

object IndexerParsers {

    private fun decodeHtml(input: String): String =
        HtmlCompat.fromHtml(input, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun formatEpoch(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "Recent"
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

    // --- 1. THE PIRATE BAY (ApiBay JSON API) ---
    fun parseApiBayJson(jsonBody: String, baseUrl: String): List<TorrentResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val arr = JSONArray(trimmed)
        val results = mutableListOf<TorrentResult>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val name = obj.optString("name", "").trim()
            val infoHash = obj.optString("info_hash", "").trim()
            if (id == "0" || name.isBlank() || infoHash.isBlank() || infoHash.all { it == '0' }) continue
            val seeders = obj.optString("seeders", "0").toIntOrNull() ?: obj.optInt("seeders", 0)
            val leechers = obj.optString("leechers", "0").toIntOrNull() ?: obj.optInt("leechers", 0)
            val sizeBytes = obj.optString("size", "0").toLongOrNull() ?: obj.optLong("size", 0L)
            val addedEpoch = obj.optString("added", "0").toLongOrNull() ?: obj.optLong("added", 0L)
            val catCode = obj.optString("category", "0").toIntOrNull() ?: 0
            val category = mapTpbCategory(catCode)
            val magnet = QualityAndHealthParser.buildMagnetFromHash(infoHash, name)
            results += TorrentResult(
                id = "tpb_$id",
                title = name,
                source = IndexerSource.TPB,
                category = category,
                sizeLabel = QualityAndHealthParser.formatBytes(sizeBytes),
                sizeBytes = sizeBytes,
                seeders = seeders,
                leechers = leechers,
                uploadDateLabel = formatEpoch(addedEpoch),
                uploadEpochSeconds = addedEpoch,
                magnetUri = magnet,
                infoHash = infoHash.lowercase(Locale.US),
                detailPageUrl = "${baseUrl.trimEnd('/')}/t.php?id=$id"
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
        val root = JSONObject(jsonBody)
        val data = root.optJSONObject("data") ?: return emptyList()
        val movies = data.optJSONArray("movies") ?: return emptyList()
        val results = mutableListOf<TorrentResult>()
        for (i in 0 until movies.length()) {
            val movie = movies.optJSONObject(i) ?: continue
            val titleLong = movie.optString("title_long", movie.optString("title", "")).trim()
            val movieUrl = movie.optString("url", "")
            val torrents = movie.optJSONArray("torrents") ?: continue
            for (j in 0 until torrents.length()) {
                val t = torrents.optJSONObject(j) ?: continue
                val hash = t.optString("hash", "").trim()
                if (hash.isBlank()) continue
                val quality = t.optString("quality", "")
                val type = t.optString("type", "").uppercase(Locale.US)
                val codec = t.optString("video_codec", "")
                val fullTitle = buildString {
                    append(titleLong)
                    if (quality.isNotBlank()) append(" [$quality]")
                    if (type.isNotBlank()) append(" [$type]")
                    if (codec.isNotBlank() && !codec.equals("x264", true)) append(" [$codec]")
                }
                val sizeBytes = t.optLong("size_bytes", 0L)
                val sizeLabel = t.optString("size", "").ifBlank {
                    QualityAndHealthParser.formatBytes(sizeBytes)
                }
                val seeds = t.optInt("seeds", 0)
                val peers = t.optInt("peers", 0)
                val epoch = t.optLong("date_uploaded_unix", 0L)
                val dateLabel = t.optString("date_uploaded", "").substringBefore(" ")
                    .ifBlank { formatEpoch(epoch) }
                results += TorrentResult(
                    id = "yts_${hash.take(16)}",
                    title = fullTitle,
                    source = IndexerSource.YTS,
                    category = TorrentCategory.MOVIES,
                    sizeLabel = sizeLabel,
                    sizeBytes = sizeBytes,
                    seeders = seeds,
                    leechers = peers,
                    uploadDateLabel = dateLabel,
                    uploadEpochSeconds = epoch,
                    magnetUri = QualityAndHealthParser.buildMagnetFromHash(hash, fullTitle),
                    infoHash = hash.lowercase(Locale.US),
                    detailPageUrl = movieUrl
                )
            }
        }
        return results
    }

    // --- 3. EZTV (IMDb Suggestion ID Resolution + JSON API + HTML Search) ---
    /**
     * Parses IMDb public suggestion JSON (`v3.sg.media-imdb.com/suggestion/...`) to extract
     * numeric IMDb IDs (without leading "tt") for EZTV's `/api/get-torrents?imdb_id=...` API.
     */
    fun parseImdbSuggestionIds(jsonBody: String): List<String> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = JSONObject(trimmed)
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
        if (!trimmed.startsWith("{")) return emptyList()
        val root = JSONObject(trimmed)
        val arr = root.optJSONArray("torrents") ?: return emptyList()
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
            val hash = obj.optString("hash", "").trim().ifBlank {
                QualityAndHealthParser.extractHashFromMagnet(magnet)
            }
            val finalMagnet = magnet.ifBlank {
                QualityAndHealthParser.buildMagnetFromHash(hash, title)
            }
            val sizeBytes = obj.optString("size_bytes", "0").toLongOrNull()
                ?: obj.optLong("size_bytes", 0L)
            val seeds = obj.optString("seeds", "").toIntOrNull() ?: obj.optInt("seeds", 0)
            val peers = obj.optString("peers", "").toIntOrNull() ?: obj.optInt("peers", 0)
            val epoch = obj.optString("date_released_unix", "").toLongOrNull()
                ?: obj.optLong("date_released_unix", 0L)
            val stableId = hash.ifBlank { obj.optString("id", title.hashCode().toUInt().toString()) }
            results += TorrentResult(
                id = "eztv_$stableId",
                title = title,
                source = IndexerSource.EZTV,
                category = TorrentCategory.TV,
                sizeLabel = QualityAndHealthParser.formatBytes(sizeBytes),
                sizeBytes = sizeBytes,
                seeders = seeds,
                leechers = peers,
                uploadDateLabel = formatEpoch(epoch),
                uploadEpochSeconds = epoch,
                magnetUri = finalMagnet.ifBlank { null },
                infoHash = hash.lowercase(Locale.US),
                detailPageUrl = obj.optString("episode_url", "")
            )
        }
        return results
    }

    fun parseEztvSearchHtml(html: String, baseUrl: String): List<TorrentResult> {
        if (isCloudflareOrBlockedHtml(html)) return emptyList()
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

            val sizeLabel = tds.firstOrNull { sizePattern.matches(it) }
                ?: tds.getOrNull(3)?.takeIf { it.isNotBlank() }
                ?: "—"
            val dateLabel = tds.firstOrNull { datePattern.matches(it) }
                ?: tds.getOrNull(4)?.takeIf { it.isNotBlank() }
                ?: "Recent"
            val seeds = tds.lastOrNull { it.replace(",", "").toIntOrNull() != null }
                ?.replace(",", "")
                ?.toIntOrNull()
                ?: 0

            val hash = QualityAndHealthParser.extractHashFromMagnet(magnet)
            val fullDetailUrl = if (rawHref.startsWith("http", ignoreCase = true)) {
                rawHref
            } else {
                "${baseUrl.trimEnd('/')}/${rawHref.trimStart('/')}"
            }
            val finalMagnet = magnet?.ifBlank { null } ?: if (hash.isNotBlank()) {
                QualityAndHealthParser.buildMagnetFromHash(hash, title)
            } else null

            results += TorrentResult(
                id = "eztv_${hash.ifBlank { rawHref.hashCode().toUInt().toString() }}",
                title = title,
                source = IndexerSource.EZTV,
                category = TorrentCategory.TV,
                sizeLabel = sizeLabel,
                sizeBytes = QualityAndHealthParser.parseSizeToBytes(sizeLabel),
                seeders = seeds,
                leechers = 0,
                uploadDateLabel = dateLabel,
                uploadEpochSeconds = estimateRelativeEpoch(dateLabel),
                magnetUri = finalMagnet,
                infoHash = hash,
                detailPageUrl = fullDetailUrl
            )
            if (results.size >= 50) break
        }
        return results
    }

    // --- 4. NYAA (RSS XML) ---
    fun parseNyaaRss(xmlBody: String): List<TorrentResult> {
        val results = mutableListOf<TorrentResult>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlBody))

        var inItem = false
        var title = ""
        var link = ""
        var pubDate = ""
        var seeders = 0
        var leechers = 0
        var sizeLabel = ""
        var infoHash = ""
        var categoryText = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    if (tag == "item") {
                        inItem = true
                        title = ""
                        link = ""
                        pubDate = ""
                        seeders = 0
                        leechers = 0
                        sizeLabel = ""
                        infoHash = ""
                        categoryText = ""
                    } else if (inItem) {
                        val text = runCatching { parser.nextText().trim() }.getOrDefault("")
                        when (tag) {
                            "title" -> title = text
                            "guid", "link" -> if (link.isBlank() || tag == "guid") link = text
                            "pubdate" -> pubDate = text
                            "nyaa:seeders" -> seeders = text.toIntOrNull() ?: 0
                            "nyaa:leechers" -> leechers = text.toIntOrNull() ?: 0
                            "nyaa:size" -> sizeLabel = text
                            "nyaa:infohash" -> infoHash = text.lowercase(Locale.US)
                            "nyaa:category" -> categoryText = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true) && inItem) {
                        inItem = false
                        if (title.isNotBlank() && infoHash.isNotBlank()) {
                            val epoch = parseRssDateEpoch(pubDate)
                            results += TorrentResult(
                                id = "nyaa_${infoHash.take(16)}",
                                title = decodeHtml(title),
                                source = IndexerSource.NYAA,
                                category = mapNyaaCategory(categoryText),
                                sizeLabel = sizeLabel.ifBlank { "—" },
                                sizeBytes = QualityAndHealthParser.parseSizeToBytes(sizeLabel),
                                seeders = seeders,
                                leechers = leechers,
                                uploadDateLabel = formatEpoch(epoch),
                                uploadEpochSeconds = epoch,
                                magnetUri = QualityAndHealthParser.buildMagnetFromHash(infoHash, title),
                                infoHash = infoHash,
                                detailPageUrl = link
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
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
        if (isCloudflareOrBlockedHtml(html)) return emptyList()
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

            val seeds = seedsRegex.find(row)?.groupValues?.getOrNull(1)
                ?.let { decodeHtml(it).replace(",", "").trim().toIntOrNull() }
                ?: rawTds.getOrNull(1)?.let { decodeHtml(it).replace(",", "").trim().toIntOrNull() }
                ?: 0

            val leeches = leechRegex.find(row)?.groupValues?.getOrNull(1)
                ?.let { decodeHtml(it).replace(",", "").trim().toIntOrNull() }
                ?: rawTds.getOrNull(2)?.let { decodeHtml(it).replace(",", "").trim().toIntOrNull() }
                ?: 0

            val dateLabel = dateRegex.find(row)?.groupValues?.getOrNull(1)
                ?.let { decodeHtml(it).trim() }
                ?.takeIf { it.isNotBlank() }
                ?: rawTds.getOrNull(3)?.let { decodeHtml(it).trim() }?.takeIf { it.isNotBlank() }
                ?: "Recent"

            val rawSizeHtml = sizeRegex.find(row)?.groupValues?.getOrNull(1)
                ?: rawTds.getOrNull(4)
                ?: ""
            val cleanedSize = decodeHtml(rawSizeHtml.replace(spanStripRegex, "")).trim()
            val sizeLabel = sizeTokenRegex.find(cleanedSize)?.groupValues?.getOrNull(1)
                ?: cleanedSize.ifBlank { "—" }

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
                sizeBytes = QualityAndHealthParser.parseSizeToBytes(sizeLabel),
                seeders = seeds,
                leechers = leeches,
                uploadDateLabel = dateLabel,
                uploadEpochSeconds = estimateRelativeEpoch(dateLabel),
                magnetUri = null, // Resolved lazily on tap with multi-mirror failover
                infoHash = "",
                detailPageUrl = fullDetailUrl
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
        val magnetRegex = Regex("(magnet:\\?xt=urn:btih:[a-zA-Z0-9]{32,40}[^\"'\\s<>]*)", RegexOption.IGNORE_CASE)
        val raw = magnetRegex.find(html)?.groupValues?.getOrNull(1)
        if (!raw.isNullOrBlank()) {
            return decodeHtml(raw)
        }
        // Fallback: 1337x detail pages also expose raw 40-char SHA1 hash inside <div class="infohash-box">
        val infoHashBoxRegex = Regex(
            "infohash-box[^>]*>.*?<span[^>]*>\\s*([a-fA-F0-9]{40})\\s*</span>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val hash = infoHashBoxRegex.find(html)?.groupValues?.getOrNull(1)
        return if (!hash.isNullOrBlank()) {
            QualityAndHealthParser.buildMagnetFromHash(hash, fallbackTitle)
        } else {
            null
        }
    }

    // --- 5B. 1337x via KNABEN DATABASE JSON API (Cloudflare-Free Index) ---
    fun parseKnaben1337xJson(
        jsonBody: String,
        fallbackCategory: TorrentCategory,
        strict1337xOnly: Boolean = true
    ): List<TorrentResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = JSONObject(trimmed)
        val arr = root.optJSONArray("hits") ?: root.optJSONArray("data") ?: return emptyList()
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
            val hash = obj.optString("hash", "").trim().lowercase(Locale.US).ifBlank {
                QualityAndHealthParser.extractHashFromMagnet(rawMagnet)
            }
            val finalMagnet = rawMagnet.ifBlank {
                QualityAndHealthParser.buildMagnetFromHash(hash, title)
            }
            if (finalMagnet.isBlank()) continue

            val dedupKey = hash.ifBlank { rawId.ifBlank { title.lowercase(Locale.US) } }
            if (!seenKeys.add(dedupKey)) continue

            val sizeBytes = obj.optLong("bytes", 0L).takeIf { it > 0L }
                ?: obj.optString("bytes", "0").toLongOrNull()
                ?: QualityAndHealthParser.parseSizeToBytes(obj.optString("size", ""))
            val sizeLabel = obj.optString("size", "").ifBlank {
                QualityAndHealthParser.formatBytes(sizeBytes)
            }
            val seeders = obj.optString("seeders", "").toIntOrNull() ?: obj.optInt("seeders", 0)
            val leechers = obj.optString("peers", "").toIntOrNull()
                ?: obj.optInt("peers", obj.optInt("leechers", 0))
            val dateRaw = obj.optString("date", "")
            val epoch = parseIsoDateEpoch(dateRaw)
            val cachedId = obj.optString("cachedId", "").trim()
            val stableId = cachedId.ifBlank { hash.take(16).ifBlank { rawId.ifBlank { title.hashCode().toUInt().toString() } } }

            results += TorrentResult(
                id = "x1337_$stableId",
                title = title,
                source = IndexerSource.X1337,
                category = inferKnabenCategory(obj, fallbackCategory),
                sizeLabel = sizeLabel,
                sizeBytes = sizeBytes,
                seeders = seeders,
                leechers = leechers,
                uploadDateLabel = formatEpoch(epoch),
                uploadEpochSeconds = epoch,
                magnetUri = finalMagnet,
                infoHash = hash,
                detailPageUrl = details
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

    private fun parseIsoDateEpoch(rawDate: String): Long {
        if (rawDate.isBlank()) return 0L
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
        return 0L
    }

    // --- 6. LIMETORRENTS (RSS XML) ---
    fun parseLimeTorrentsRss(xmlBody: String, fallbackCategory: TorrentCategory): List<TorrentResult> {
        val results = mutableListOf<TorrentResult>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlBody))

        var inItem = false
        var title = ""
        var link = ""
        var pubDate = ""
        var description = ""
        var sizeBytes = 0L
        var enclosureUrl = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.US)
                    if (tag == "item") {
                        inItem = true
                        title = ""
                        link = ""
                        pubDate = ""
                        description = ""
                        sizeBytes = 0L
                        enclosureUrl = ""
                    } else if (inItem) {
                        if (tag == "enclosure") {
                            enclosureUrl = parser.getAttributeValue(null, "url") ?: ""
                            sizeBytes = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: sizeBytes
                        } else {
                            val text = runCatching { parser.nextText().trim() }.getOrDefault("")
                            when (tag) {
                                "title" -> title = text
                                "link" -> link = text
                                "pubdate" -> pubDate = text
                                "description" -> description = text
                                "size" -> sizeBytes = text.toLongOrNull() ?: sizeBytes
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true) && inItem) {
                        inItem = false
                        if (title.isNotBlank()) {
                            val seeds = Regex("Seeds:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                                .find(description)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            val leeches = Regex("Leechers:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                                .find(description)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            val hash = Regex("/([A-Fa-f0-9]{40})\\.torrent").find(enclosureUrl)?.groupValues?.getOrNull(1)
                                ?.lowercase(Locale.US) ?: ""
                            val magnet = if (hash.isNotBlank()) {
                                QualityAndHealthParser.buildMagnetFromHash(hash, title)
                            } else null
                            val epoch = parseRssDateEpoch(pubDate)
                            results += TorrentResult(
                                id = "lime_${hash.ifBlank { link.hashCode().toUInt().toString() }}",
                                title = decodeHtml(title),
                                source = IndexerSource.LIMETORRENTS,
                                category = fallbackCategory,
                                sizeLabel = QualityAndHealthParser.formatBytes(sizeBytes),
                                sizeBytes = sizeBytes,
                                seeders = seeds,
                                leechers = leeches,
                                uploadDateLabel = formatEpoch(epoch),
                                uploadEpochSeconds = epoch,
                                magnetUri = magnet,
                                infoHash = hash,
                                detailPageUrl = link
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return results
    }

    private fun parseRssDateEpoch(rawDate: String): Long {
        if (rawDate.isBlank()) return 0L
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
        return 0L
    }

    private fun estimateRelativeEpoch(label: String): Long {
        val now = System.currentTimeMillis() / 1000L
        val lower = label.lowercase(Locale.US)
        val num = Regex("(\\d+)").find(lower)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1L
        return when {
            lower.contains("min") || lower.contains("am") || lower.contains("pm") || lower.contains("today") -> now - num * 60L
            lower.contains("hour") || lower.contains("hr") || lower.endsWith("h") -> now - num * 3600L
            lower.contains("day") || lower.contains("d ") || lower.endsWith("d") -> now - num * 86400L
            lower.contains("week") || lower.endsWith("w") -> now - num * 7L * 86400L
            lower.contains("month") || lower.contains("mo") -> now - num * 30L * 86400L
            lower.contains("year") || lower.contains("yr") || lower.endsWith("y") -> now - num * 365L * 86400L
            else -> now - 86400L * 3L
        }
    }
}
