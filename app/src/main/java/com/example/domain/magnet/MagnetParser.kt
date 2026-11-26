package com.example.domain.magnet

import com.example.model.IndexerSource
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

enum class TorrentHashFamily(
    val urnPrefix: String,
    val keyPrefix: String,
    val displayLabel: String
) {
    BTIH(urnPrefix = "urn:btih:", keyPrefix = "btih", displayLabel = "BTIH v1"),
    BTMH(urnPrefix = "urn:btmh:", keyPrefix = "btmh", displayLabel = "BTIH v2")
}

sealed class CanonicalIdentityKey {
    abstract val storageKey: String

    data class VerifiedHash(
        val family: TorrentHashFamily,
        val canonicalHex: String
    ) : CanonicalIdentityKey() {
        override val storageKey: String = "${family.keyPrefix}:$canonicalHex"
        val shortDisplay: String = "${family.displayLabel} • ${canonicalHex.take(12).uppercase(Locale.US)}…"
    }

    data class UnresolvedListing(
        val source: IndexerSource,
        val listingId: String
    ) : CanonicalIdentityKey() {
        override val storageKey: String = "listing:${source.name}:$listingId"
    }
}

sealed class MagnetParseResult {
    data object Empty : MagnetParseResult()

    data class Invalid(
        val reason: String,
        val rawInput: String
    ) : MagnetParseResult()

    data class Valid(
        val magnetUri: String,
        val primaryIdentity: CanonicalIdentityKey.VerifiedHash,
        val allIdentities: List<CanonicalIdentityKey.VerifiedHash>,
        val displayName: String,
        val trackers: List<String>,
        val exactLengthBytes: Long?
    ) : MagnetParseResult()
}

object MagnetParser {
    const val MAX_MAGNET_LENGTH = 16 * 1024

    private val BTIH_HEX_REGEX = Regex("^[0-9a-fA-F]{40}$")
    private val BTIH_BASE32_REGEX = Regex("^[A-Za-z2-7=]{32,40}$")
    private val BTMH_HEX_REGEX = Regex("^1220[0-9a-fA-F]{64}$")

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

    /**
     * Normalizes either a 40-char hex SHA-1 BTIH or a 32-char RFC 4648 Base32 BTIH
     * into a lowercase 40-char hexadecimal string. Returns null for malformed values.
     */
    fun normalizeBtihToHex(raw: String?): String? {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        if (BTIH_HEX_REGEX.matches(cleaned)) {
            return cleaned.lowercase(Locale.US)
        }
        if (BTIH_BASE32_REGEX.matches(cleaned)) {
            val decoded = decodeBase32Rfc4648(cleaned) ?: return null
            if (decoded.size != 20) return null
            return decoded.joinToString("") { byte ->
                String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
            }
        }
        return null
    }

    /**
     * Validates a v2 BitTorrent multihash (`urn:btmh:1220<64-hex-sha256>`).
     */
    fun normalizeBtmhToHex(raw: String?): String? {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        if (BTMH_HEX_REGEX.matches(cleaned)) {
            return cleaned.lowercase(Locale.US)
        }
        return null
    }

    fun normalizeHash(raw: String?, familyHint: TorrentHashFamily? = null): CanonicalIdentityKey.VerifiedHash? {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        if (familyHint == TorrentHashFamily.BTMH) {
            return normalizeBtmhToHex(cleaned)?.let {
                CanonicalIdentityKey.VerifiedHash(TorrentHashFamily.BTMH, it)
            }
        }
        normalizeBtihToHex(cleaned)?.let {
            return CanonicalIdentityKey.VerifiedHash(TorrentHashFamily.BTIH, it)
        }
        if (familyHint == null) {
            normalizeBtmhToHex(cleaned)?.let {
                return CanonicalIdentityKey.VerifiedHash(TorrentHashFamily.BTMH, it)
            }
        }
        return null
    }

    fun extractVerifiedIdentities(magnetUri: String?): List<CanonicalIdentityKey.VerifiedHash> {
        val raw = magnetUri?.trim().orEmpty()
        if (!raw.startsWith("magnet:?", ignoreCase = true)) return emptyList()
        val query = raw.substringAfter('?', "")
        if (query.isBlank()) return emptyList()

        val found = LinkedHashMap<String, CanonicalIdentityKey.VerifiedHash>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val key = pair.substringBefore('=').lowercase(Locale.US)
            if (key != "xt" && !key.startsWith("xt.")) continue
            val value = decodeUrlComponent(pair.substringAfter('=', "")).trim()
            when {
                value.startsWith(TorrentHashFamily.BTIH.urnPrefix, ignoreCase = true) -> {
                    val token = value.substring(TorrentHashFamily.BTIH.urnPrefix.length)
                    normalizeBtihToHex(token)?.let { hex ->
                        val id = CanonicalIdentityKey.VerifiedHash(TorrentHashFamily.BTIH, hex)
                        found[id.storageKey] = id
                    }
                }
                value.startsWith(TorrentHashFamily.BTMH.urnPrefix, ignoreCase = true) -> {
                    val token = value.substring(TorrentHashFamily.BTMH.urnPrefix.length)
                    normalizeBtmhToHex(token)?.let { hex ->
                        val id = CanonicalIdentityKey.VerifiedHash(TorrentHashFamily.BTMH, hex)
                        found[id.storageKey] = id
                    }
                }
            }
        }

        // Prefer BTIH primary over BTMH if both appear in a hybrid magnet while keeping both distinct
        return found.values.sortedBy { it.family.ordinal }
    }

    fun extractPrimaryBtihHex(magnetUri: String?): String =
        extractVerifiedIdentities(magnetUri)
            .firstOrNull { it.family == TorrentHashFamily.BTIH }
            ?.canonicalHex
            .orEmpty()

    fun extractAnyCanonicalHex(magnetUri: String?): String =
        extractVerifiedIdentities(magnetUri).firstOrNull()?.canonicalHex.orEmpty()

    fun buildMagnetFromHash(
        infoHash: String,
        title: String,
        family: TorrentHashFamily = TorrentHashFamily.BTIH
    ): String {
        val verified = normalizeHash(infoHash, family) ?: return ""
        val encodedName = runCatching {
            URLEncoder.encode(title.trim().ifBlank { "torrent" }.take(160), "UTF-8")
        }.getOrDefault("torrent")
        val trackers = defaultTrackers.joinToString("") { tracker ->
            "&tr=${URLEncoder.encode(tracker, "UTF-8")}"
        }
        return "magnet:?xt=${verified.family.urnPrefix}${verified.canonicalHex}&dn=$encodedName$trackers"
    }

    fun parseMagnetInput(rawInput: String): MagnetParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return MagnetParseResult.Empty
        }
        if (trimmed.length > MAX_MAGNET_LENGTH) {
            return MagnetParseResult.Invalid(
                reason = "Magnet link exceeds maximum supported length ($MAX_MAGNET_LENGTH characters).",
                rawInput = trimmed
            )
        }
        if (trimmed.any { it.isISOControl() }) {
            return MagnetParseResult.Invalid(
                reason = "Magnet link contains invalid control characters.",
                rawInput = trimmed
            )
        }
        if (!trimmed.startsWith("magnet:?", ignoreCase = true)) {
            return MagnetParseResult.Invalid(
                reason = "Not a valid magnet link. Expected input starting with \"magnet:?\".",
                rawInput = trimmed
            )
        }
        val query = trimmed.substringAfter('?', "")
        if (query.isBlank()) {
            return MagnetParseResult.Invalid(
                reason = "Magnet link has no parameters.",
                rawInput = trimmed
            )
        }

        val identities = extractVerifiedIdentities(trimmed)
        if (identities.isEmpty()) {
            return MagnetParseResult.Invalid(
                reason = "Magnet link must include a valid 40-char hex / 32-char Base32 btih or v2 btmh info hash.",
                rawInput = trimmed
            )
        }

        var displayName = ""
        val trackers = mutableListOf<String>()
        var exactLength: Long? = null

        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val key = pair.substringBefore('=').lowercase(Locale.US)
            val rawVal = pair.substringAfter('=', "")
            val decoded = decodeUrlComponent(rawVal).trim()
            when (key) {
                "dn" -> if (displayName.isBlank() && decoded.isNotBlank()) {
                    displayName = decoded.take(300)
                }
                "tr" -> if (decoded.startsWith("udp://", true) ||
                    decoded.startsWith("http://", true) ||
                    decoded.startsWith("https://", true) ||
                    decoded.startsWith("wss://", true)
                ) {
                    if (decoded !in trackers && trackers.size < 32) {
                        trackers += decoded
                    }
                }
                "xl" -> decoded.toLongOrNull()?.takeIf { it > 0L }?.let { exactLength = it }
            }
        }

        val effectiveTitle = displayName.ifBlank {
            "Magnet (${identities.first().canonicalHex.take(10).uppercase(Locale.US)})"
        }

        val canonicalMagnet = buildString {
            append("magnet:?xt=")
            append(identities.first().family.urnPrefix)
            append(identities.first().canonicalHex)
            identities.drop(1).forEachIndexed { idx, extra ->
                append("&xt.")
                append(idx + 2)
                append('=')
                append(extra.family.urnPrefix)
                append(extra.canonicalHex)
            }
            if (displayName.isNotBlank()) {
                append("&dn=")
                append(URLEncoder.encode(displayName, "UTF-8"))
            }
            if (exactLength != null) {
                append("&xl=")
                append(exactLength)
            }
            trackers.forEach { tracker ->
                append("&tr=")
                append(URLEncoder.encode(tracker, "UTF-8"))
            }
        }

        return MagnetParseResult.Valid(
            magnetUri = canonicalMagnet,
            primaryIdentity = identities.first(),
            allIdentities = identities,
            displayName = effectiveTitle,
            trackers = trackers,
            exactLengthBytes = exactLength
        )
    }

    private fun decodeUrlComponent(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private fun decodeBase32Rfc4648(input: String): ByteArray? {
        val noPadding = input.trim().uppercase(Locale.US).trimEnd('=')
        if (noPadding.length != 32) return null
        val out = ByteArray(20)
        var buffer = 0
        var bitsLeft = 0
        var outIndex = 0

        for (ch in noPadding) {
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in '2'..'7' -> 26 + (ch - '2')
                else -> return null
            }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                if (outIndex >= out.size) return null
                out[outIndex++] = ((buffer shr bitsLeft) and 0xFF).toByte()
            }
        }
        return if (outIndex == 20 && bitsLeft == 0) out else null
    }
}
