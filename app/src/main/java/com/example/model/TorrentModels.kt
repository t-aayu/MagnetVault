package com.example.model

import java.net.URLEncoder
import java.util.Locale

enum class IndexerSource(
    val displayName: String,
    val shortBadge: String,
    val defaultMirrors: List<String>,
    val supportsCategories: Boolean = true
) {
    X1337(
        displayName = "1337x",
        shortBadge = "1337x",
        defaultMirrors = listOf(
            "https://api.knaben.org/v1",
            "https://x1337x.cc",
            "https://1337x.to",
            "https://1337x.st",
            "https://x1337x.eu",
            "https://x1337x.ws",
            "https://1337x.hashhackers.com"
        )
    ),
    EZTV(
        displayName = "EZTV",
        shortBadge = "EZTV",
        defaultMirrors = listOf(
            "https://eztvx.to",
            "https://eztv1.xyz",
            "https://eztv.wf",
            "https://eztv.tf",
            "https://eztv.yt",
            "https://eztv.re"
        )
    ),
    YTS(
        displayName = "YTS",
        shortBadge = "YTS",
        defaultMirrors = listOf(
            "https://yts.mx",
            "https://yts.lt",
            "https://yts.am"
        )
    ),
    TPB(
        displayName = "ThePirateBay",
        shortBadge = "TPB",
        defaultMirrors = listOf(
            "https://apibay.org",
            "https://piratebay.party"
        )
    ),
    NYAA(
        displayName = "Nyaa",
        shortBadge = "Nyaa",
        defaultMirrors = listOf(
            "https://nyaa.si"
        )
    ),
    LIMETORRENTS(
        displayName = "LimeTorrents",
        shortBadge = "Lime",
        defaultMirrors = listOf(
            "https://www.limetorrents.lol",
            "https://www.limetorrents.fun"
        )
    );

    companion object {
        fun fromNameSafe(raw: String): IndexerSource =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: X1337
    }
}

enum class TorrentCategory(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    TV("TV"),
    ANIME("Anime"),
    GAMES("Games"),
    APPS("Apps"),
    MUSIC("Music"),
    OTHER("Other");

    companion object {
        fun fromNameSafe(raw: String): TorrentCategory =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALL
    }
}

enum class SortOption(val label: String) {
    SEEDERS("Seeders"),
    LEECHERS("Leechers"),
    UPLOAD_DATE("Upload Date"),
    SIZE("Size")
}

enum class QualityTag(val label: String) {
    UHD_4K("4K"),
    FHD_1080P("1080p"),
    HD_720P("720p"),
    HEVC("HEVC"),
    HDR("HDR"),
    REMUX("REMUX");

    companion object {
        fun fromLabelOrName(raw: String): QualityTag? =
            entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true) || it.label.equals(raw, ignoreCase = true)
            }
    }
}

enum class SwarmHealth(val label: String) {
    HEALTHY("Healthy"),
    MODERATE("Moderate"),
    WEAK("Weak"),
    DEAD("Dead")
}

data class TorrentResult(
    val id: String,
    val title: String,
    val source: IndexerSource,
    val category: TorrentCategory,
    val sizeLabel: String,
    val sizeBytes: Long,
    val seeders: Int,
    val leechers: Int,
    val uploadDateLabel: String,
    val uploadEpochSeconds: Long,
    val magnetUri: String?,
    val infoHash: String,
    val detailPageUrl: String,
    val qualityTags: List<QualityTag> = QualityAndHealthParser.extractQualityTags(title),
    val swarmHealth: SwarmHealth = QualityAndHealthParser.computeSwarmHealth(seeders, leechers)
)

data class IndexerSearchStatus(
    val source: IndexerSource,
    val mirrorUsed: String,
    val isLoading: Boolean = false,
    val resultCount: Int = 0,
    val errorMessage: String? = null
)

data class MirrorConfig(
    val provider: IndexerSource,
    val enabled: Boolean = true,
    val mirrors: List<String> = provider.defaultMirrors,
    val activeMirrorUrl: String = provider.defaultMirrors.first()
)

data class MirrorHealthResult(
    val url: String,
    val isChecking: Boolean = false,
    val isReachable: Boolean? = null,
    val latencyMs: Long? = null,
    val statusCode: Int? = null,
    val error: String? = null
)

object QualityAndHealthParser {
    private val defaultTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://public.popcorn-tracker.org:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce"
    )

    fun extractQualityTags(title: String): List<QualityTag> {
        val upper = title.uppercase(Locale.US)
        val tags = mutableListOf<QualityTag>()
        if (upper.contains("2160P") || upper.contains(" 4K") || upper.contains(".4K") || upper.contains("UHD")) {
            tags += QualityTag.UHD_4K
        }
        if (upper.contains("1080P")) {
            tags += QualityTag.FHD_1080P
        }
        if (upper.contains("720P")) {
            tags += QualityTag.HD_720P
        }
        if (upper.contains("HEVC") || upper.contains("X265") || upper.contains("H265") || upper.contains("H.265")) {
            tags += QualityTag.HEVC
        }
        if (upper.contains("HDR") || upper.contains("DOLBY VISION") || upper.contains(".DV.") || upper.contains("HDR10")) {
            tags += QualityTag.HDR
        }
        if (upper.contains("REMUX")) {
            tags += QualityTag.REMUX
        }
        return tags
    }

    fun computeSwarmHealth(seeders: Int, leechers: Int): SwarmHealth {
        if (seeders <= 0) return SwarmHealth.DEAD
        val ratio = seeders.toFloat() / (leechers.coerceAtLeast(1)).toFloat()
        return when {
            seeders >= 40 || (seeders >= 15 && ratio >= 1.2f) -> SwarmHealth.HEALTHY
            seeders >= 8 || (seeders >= 4 && ratio >= 0.7f) -> SwarmHealth.MODERATE
            else -> SwarmHealth.WEAK
        }
    }

    fun buildMagnetFromHash(infoHash: String, title: String): String {
        val cleanHash = infoHash.trim().lowercase(Locale.US)
        if (cleanHash.isBlank()) return ""
        val encodedName = try {
            URLEncoder.encode(title.take(160), "UTF-8")
        } catch (_: Exception) {
            "torrent"
        }
        val trackers = defaultTrackers.joinToString("") { tracker ->
            "&tr=${URLEncoder.encode(tracker, "UTF-8")}"
        }
        return "magnet:?xt=urn:btih:$cleanHash&dn=$encodedName$trackers"
    }

    fun extractHashFromMagnet(magnetUri: String?): String {
        if (magnetUri.isNullOrBlank()) return ""
        val regex = Regex("xt=urn:btih:([a-zA-Z0-9]{32,40})", RegexOption.IGNORE_CASE)
        return regex.find(magnetUri)?.groupValues?.getOrNull(1)?.lowercase(Locale.US) ?: ""
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val tb = gb * 1024.0
        return when {
            bytes >= tb -> String.format(Locale.US, "%.2f TB", bytes / tb)
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun parseSizeToBytes(sizeText: String): Long {
        val cleaned = sizeText.trim().uppercase(Locale.US).replace(",", "")
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(TB|TIB|GB|GIB|MB|MIB|KB|KIB|B)").find(cleaned)
            ?: return 0L
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2]
        val multiplier = when (unit) {
            "TB", "TIB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            "GB", "GIB" -> 1024.0 * 1024.0 * 1024.0
            "MB", "MIB" -> 1024.0 * 1024.0
            "KB", "KIB" -> 1024.0
            else -> 1.0
        }
        return (value * multiplier).toLong().coerceAtLeast(0L)
    }

    fun sanitizeDocumentId(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return if (cleaned.isBlank()) "doc_${System.currentTimeMillis()}" else cleaned.take(120)
    }
}
