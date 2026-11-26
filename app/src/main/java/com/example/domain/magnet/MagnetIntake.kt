package com.example.domain.magnet

import com.example.model.CanonicalReleaseGroup
import com.example.model.IndexerSource
import com.example.model.MagnetResolutionState
import com.example.model.ProviderListing
import com.example.model.QualityAndHealthParser
import com.example.model.SwarmHealth
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import java.util.Locale

sealed class MagnetIntakeResolution {
    data object Empty : MagnetIntakeResolution()

    data class Single(
        val magnet: MagnetParseResult.Valid,
        val group: CanonicalReleaseGroup
    ) : MagnetIntakeResolution()

    data class Multiple(
        val magnets: List<MagnetParseResult.Valid>
    ) : MagnetIntakeResolution()

    data class Unsupported(
        val reason: String,
        val rawPreview: String = ""
    ) : MagnetIntakeResolution()
}

/**
 * Shared intake pipeline used by Search paste, in-app Sites WebView capture,
 * and Android ACTION_SEND share-sheet capture.
 */
object MagnetIntake {
    const val MAX_SHARE_TEXT_LENGTH = 64 * 1024
    const val MAX_SHARE_CANDIDATES = 25

    private val MAGNET_CANDIDATE_REGEX = Regex("""magnet:\?[^\s"'<>\]\)]+""", RegexOption.IGNORE_CASE)

    fun resolveSingleMagnetUri(
        rawInput: String?,
        originLabel: String = "Captured",
        sourceUrl: String = ""
    ): MagnetIntakeResolution {
        val trimmed = rawInput?.trim().orEmpty()
        if (trimmed.isEmpty()) return MagnetIntakeResolution.Empty
        return when (val parsed = MagnetParser.parseMagnetInput(trimmed)) {
            MagnetParseResult.Empty -> MagnetIntakeResolution.Empty
            is MagnetParseResult.Invalid -> MagnetIntakeResolution.Unsupported(
                reason = parsed.reason,
                rawPreview = trimmed.take(160)
            )
            is MagnetParseResult.Valid -> MagnetIntakeResolution.Single(
                magnet = parsed,
                group = parsed.toCanonicalReleaseGroup(
                    originLabel = originLabel,
                    sourcePageUrl = sourceUrl
                )
            )
        }
    }

    fun resolveSharedText(rawText: String?): MagnetIntakeResolution {
        if (rawText.isNullOrBlank()) return MagnetIntakeResolution.Empty
        if (rawText.length > MAX_SHARE_TEXT_LENGTH) {
            return MagnetIntakeResolution.Unsupported(
                reason = "Shared text exceeds the safe 64 KB input limit. Share or paste a single magnet:? link instead.",
                rawPreview = ""
            )
        }

        val trimmed = rawText.trim()
        // Fast path if the entire shared string is a magnet link candidate
        if (trimmed.startsWith("magnet:?", ignoreCase = true) && !trimmed.any { it.isWhitespace() }) {
            return resolveSingleMagnetUri(trimmed, originLabel = "Shared")
        }

        val matches = MAGNET_CANDIDATE_REGEX.findAll(rawText)
            .take(MAX_SHARE_CANDIDATES)
            .map { cleanCandidateTrailingPunctuation(it.value) }
            .filter { it.isNotBlank() }
            .toList()

        if (matches.isEmpty()) {
            return MagnetIntakeResolution.Unsupported(
                reason = "No valid magnet link found in shared text. MagnetVault accepts text containing magnet:? links with a valid BTIH or BTMH info hash.",
                rawPreview = trimmed.take(160)
            )
        }

        val dedupedByIdentity = LinkedHashMap<String, MagnetParseResult.Valid>()
        var invalidReason: String? = null

        for (candidate in matches) {
            when (val parsed = MagnetParser.parseMagnetInput(candidate)) {
                is MagnetParseResult.Valid -> {
                    val key = parsed.primaryIdentity.storageKey
                    val prev = dedupedByIdentity[key]
                    if (prev == null || scoreMetadataRichness(parsed) > scoreMetadataRichness(prev)) {
                        dedupedByIdentity[key] = parsed
                    }
                }
                is MagnetParseResult.Invalid -> {
                    if (invalidReason == null) invalidReason = parsed.reason
                }
                MagnetParseResult.Empty -> Unit
            }
        }

        val validList = dedupedByIdentity.values.toList()
        return when {
            validList.isEmpty() -> MagnetIntakeResolution.Unsupported(
                reason = invalidReason
                    ?: "No valid magnet link found in shared text. MagnetVault accepts valid magnet:? links with a 40-char hex / 32-char Base32 btih or v2 btmh hash.",
                rawPreview = trimmed.take(160)
            )
            validList.size == 1 -> {
                val single = validList.first()
                MagnetIntakeResolution.Single(
                    magnet = single,
                    group = single.toCanonicalReleaseGroup(originLabel = "Shared")
                )
            }
            else -> MagnetIntakeResolution.Multiple(validList.take(20))
        }
    }

    private fun cleanCandidateTrailingPunctuation(candidate: String): String =
        candidate.trimEnd('.', ',', ';', ')', ']', '>', '"', '\'')

    private fun scoreMetadataRichness(valid: MagnetParseResult.Valid): Int {
        var score = valid.trackers.size + valid.allIdentities.size * 10
        if (!valid.displayName.startsWith("Magnet (")) score += 50
        if (valid.exactLengthBytes != null) score += 20
        return score
    }
}

fun MagnetParseResult.Valid.toCanonicalReleaseGroup(
    originLabel: String = "Captured",
    sourcePageUrl: String = ""
): CanonicalReleaseGroup {
    val primaryHash = primaryIdentity
    val cleanTitle = displayName.takeIf { it.isNotBlank() }
        ?: "Magnet ${primaryHash.canonicalHex.take(12).uppercase(Locale.US)}"
    val syntheticResult = TorrentResult(
        id = "intake_${primaryHash.canonicalHex.take(16)}",
        title = cleanTitle,
        source = IndexerSource.X1337,
        category = TorrentCategory.OTHER,
        sizeLabel = QualityAndHealthParser.formatKnownBytes(exactLengthBytes),
        sizeBytes = exactLengthBytes ?: 0L,
        seeders = 0,
        leechers = 0,
        uploadDateLabel = originLabel,
        uploadEpochSeconds = 0L,
        magnetUri = magnetUri,
        infoHash = primaryHash.canonicalHex,
        detailPageUrl = sourcePageUrl,
        swarmHealth = SwarmHealth.UNKNOWN,
        listingId = primaryHash.canonicalHex,
        knownSizeBytes = exactLengthBytes,
        knownSeeders = null,
        knownLeechers = null,
        knownUploadEpochSeconds = null,
        canonicalHashHex = primaryHash.canonicalHex,
        canonicalHashFamily = primaryHash.family
    )
    val listing = ProviderListing.fromTorrentResult(syntheticResult)
    val listings = if (sourcePageUrl.startsWith("http", ignoreCase = true)) {
        listOf(listing)
    } else {
        emptyList()
    }
    return CanonicalReleaseGroup(
        groupKey = primaryHash.storageKey,
        canonicalIdentity = primaryHash,
        representative = listing,
        listings = listings,
        magnetState = MagnetResolutionState.Available(
            magnetUri = magnetUri,
            identity = primaryHash,
            resolvedBySource = null
        ),
        qualityTags = QualityAndHealthParser.extractQualityTags(cleanTitle),
        swarmHealth = SwarmHealth.UNKNOWN
    )
}
