package com.example.model

import com.example.domain.magnet.MagnetParser
import com.example.domain.magnet.TorrentHashFamily
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
    DEAD("Dead"),
    UNKNOWN("Unknown")
}

enum class AppAppearanceMode(val label: String) {
    SYSTEM("System"),
    OBSIDIAN("Obsidian"),
    ALABASTER("Alabaster");

    companion object {
        fun fromNameSafe(raw: String): AppAppearanceMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OBSIDIAN
    }
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
    val swarmHealth: SwarmHealth = QualityAndHealthParser.computeSwarmHealth(seeders, leechers),
    val listingId: String = id,
    val knownSizeBytes: Long? = sizeBytes.takeIf { it > 0L },
    val knownSeeders: Int? = null,
    val knownLeechers: Int? = null,
    val knownUploadEpochSeconds: Long? = uploadEpochSeconds.takeIf { it > 0L },
    val canonicalHashHex: String = MagnetParser.normalizeHash(infoHash)?.canonicalHex
        ?: MagnetParser.extractAnyCanonicalHex(magnetUri),
    val canonicalHashFamily: TorrentHashFamily? = MagnetParser.normalizeHash(infoHash)?.family
        ?: MagnetParser.extractVerifiedIdentities(magnetUri).firstOrNull()?.family
)

data class IndexerSearchStatus(
    val source: IndexerSource,
    val mirrorUsed: String,
    val isLoading: Boolean = false,
    val resultCount: Int = 0,
    val errorMessage: String? = null,
    val webSearchUrl: String = "",
    val terminalState: ProviderTerminalState = when {
        isLoading -> ProviderTerminalState.LOADING
        errorMessage != null -> ProviderTerminalState.NETWORK_ERROR
        resultCount > 0 -> ProviderTerminalState.MATCHES
        else -> ProviderTerminalState.ZERO_MATCHES
    },
    val httpStatusCode: Int? = null,
    val retryAfterSeconds: Long? = null,
    val elapsedMs: Long? = null,
    val isRetrying: Boolean = false
)

enum class WebsiteOpenMode(val label: String) {
    IN_APP("In-App HTML"),
    DEFAULT_BROWSER("Default Browser");

    companion object {
        fun fromNameSafe(raw: String): WebsiteOpenMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: IN_APP
    }
}

data class CustomSite(
    val id: String,
    val name: String,
    val url: String,
    val isCustom: Boolean = true
)

data class MirrorConfig(
    val provider: IndexerSource,
    val enabled: Boolean = true,
    val mirrors: List<String> = provider.defaultMirrors,
    val activeMirrorUrl: String = provider.defaultMirrors.first()
)

enum class ParserCheckStatus(val label: String) {
    NEVER_RUN("Parser: Never run"),
    VERIFIED("Parser: Verified"),
    FAILED("Parser: Failed")
}

data class MirrorHealthResult(
    val url: String,
    val isChecking: Boolean = false,
    val isReachable: Boolean? = null,
    val latencyMs: Long? = null,
    val statusCode: Int? = null,
    val error: String? = null,
    val checkedAtEpochMs: Long? = null,
    val parserStatus: ParserCheckStatus = ParserCheckStatus.NEVER_RUN,
    val parserDetail: String? = null
)

object QualityAndHealthParser {
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

    fun computeSwarmHealth(seeders: Int, leechers: Int): SwarmHealth =
        computeSwarmHealth(knownSeeders = seeders, knownLeechers = leechers)

    fun computeSwarmHealth(knownSeeders: Int?, knownLeechers: Int?): SwarmHealth {
        if (knownSeeders == null) return SwarmHealth.UNKNOWN
        if (knownSeeders <= 0) return SwarmHealth.DEAD
        val ratio = knownSeeders.toFloat() / ((knownLeechers ?: 0).coerceAtLeast(1)).toFloat()
        return when {
            knownSeeders >= 40 || (knownSeeders >= 15 && ratio >= 1.2f) -> SwarmHealth.HEALTHY
            knownSeeders >= 8 || (knownSeeders >= 4 && ratio >= 0.7f) -> SwarmHealth.MODERATE
            else -> SwarmHealth.WEAK
        }
    }

    fun buildMagnetFromHash(infoHash: String, title: String): String =
        MagnetParser.buildMagnetFromHash(infoHash, title)

    fun extractHashFromMagnet(magnetUri: String?): String =
        MagnetParser.extractAnyCanonicalHex(magnetUri)

    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "Unknown"
        if (bytes == 0L) return "Unknown"
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

    fun formatKnownBytes(knownBytes: Long?): String {
        if (knownBytes == null) return "Unknown"
        if (knownBytes == 0L) return "0 B"
        return formatBytes(knownBytes)
    }

    fun parseSizeToKnownBytes(sizeText: String): Long? {
        val cleaned = sizeText.trim().uppercase(Locale.US).replace(",", "")
        if (cleaned.isBlank() || cleaned == "—" || cleaned == "-" || cleaned == "N/A" || cleaned == "UNKNOWN") {
            return null
        }
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(TB|TIB|GB|GIB|MB|MIB|KB|KIB|B)").find(cleaned)
            ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
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

    fun parseSizeToBytes(sizeText: String): Long =
        parseSizeToKnownBytes(sizeText) ?: 0L

    fun sanitizeDocumentId(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return if (cleaned.isBlank()) "doc_${System.currentTimeMillis()}" else cleaned.take(120)
    }
}

enum class CustomDnsPreset(
    val displayName: String,
    val description: String,
    val dohUrl: String,
    val blocksAds: Boolean
) {
    ADGUARD(
        displayName = "AdGuard DNS",
        description = "Blocks ads, trackers & pop-unders",
        dohUrl = "https://dns.adguard-dns.com/dns-query",
        blocksAds = true
    ),
    MULLVAD_ADBLOCK(
        displayName = "Mullvad Adblock",
        description = "Privacy-first ad & tracker filter",
        dohUrl = "https://adblock.dns.mullvad.net/dns-query",
        blocksAds = true
    ),
    CONTROLD_ADBLOCK(
        displayName = "Control D",
        description = "Blocks ads, trackers & malware",
        dohUrl = "https://freedns.controld.com/p2",
        blocksAds = true
    ),
    CLOUDFLARE(
        displayName = "Cloudflare",
        description = "Fast 1.1.1.1 encrypted DNS",
        dohUrl = "https://cloudflare-dns.com/dns-query",
        blocksAds = false
    ),
    CUSTOM(
        displayName = "Custom DoH",
        description = "NextDNS, Control D, or custom HTTPS DNS",
        dohUrl = "",
        blocksAds = true
    ),
    SYSTEM(
        displayName = "System Default",
        description = "Use device network DNS",
        dohUrl = "",
        blocksAds = false
    );

    companion object {
        fun fromNameSafe(raw: String): CustomDnsPreset =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ADGUARD
    }
}

data class CustomDnsConfig(
    val preset: CustomDnsPreset = CustomDnsPreset.ADGUARD,
    val customEndpoint: String = ""
) {
    val activeDohUrl: String
        get() = when (preset) {
            CustomDnsPreset.SYSTEM -> ""
            CustomDnsPreset.CUSTOM -> customEndpoint
            else -> preset.dohUrl
        }

    val isEnabled: Boolean
        get() = preset != CustomDnsPreset.SYSTEM && activeDohUrl.isNotBlank()
}
