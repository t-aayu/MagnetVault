package com.example.model

import com.example.domain.magnet.CanonicalIdentityKey
import com.example.domain.magnet.MagnetParser
import com.example.domain.magnet.TorrentHashFamily

enum class ProviderTerminalState(val label: String) {
    LOADING("Searching"),
    MATCHES("Ready"),
    ZERO_MATCHES("No matches"),
    TIMEOUT("Timed out"),
    RATE_LIMITED("Rate limited"),
    PARSER_ERROR("Parse error"),
    NETWORK_ERROR("Unreachable"),
    DISABLED("Disabled"),
    UNSUPPORTED_CATEGORY("Unsupported category");

    val isFinished: Boolean
        get() = this != LOADING

    val isFailure: Boolean
        get() = this == TIMEOUT || this == RATE_LIMITED || this == PARSER_ERROR || this == NETWORK_ERROR
}

sealed class MagnetResolutionState {
    data object Idle : MagnetResolutionState()
    data class Resolving(
        val selectionId: String,
        val source: IndexerSource? = null
    ) : MagnetResolutionState()

    data class Available(
        val magnetUri: String,
        val identity: CanonicalIdentityKey.VerifiedHash,
        val resolvedBySource: IndexerSource? = null
    ) : MagnetResolutionState()

    data class Failed(
        val selectionId: String,
        val message: String
    ) : MagnetResolutionState()
}

data class ProviderListing(
    val source: IndexerSource,
    val listingId: String,
    val title: String,
    val category: TorrentCategory,
    val knownSizeBytes: Long?,
    val sizeLabel: String,
    val knownSeeders: Int?,
    val knownLeechers: Int?,
    val uploadDateLabel: String,
    val knownUploadEpochSeconds: Long?,
    val magnetUri: String?,
    val canonicalIdentity: CanonicalIdentityKey,
    val detailPageUrl: String,
    val mirrorUsed: String,
    val rawResult: TorrentResult
) {
    val unresolvedStorageKey: String
        get() = CanonicalIdentityKey.UnresolvedListing(source, listingId).storageKey

    companion object {
        fun fromTorrentResult(
            result: TorrentResult,
            mirrorUsed: String = ""
        ): ProviderListing {
            val identities = MagnetParser.extractVerifiedIdentities(result.magnetUri)
            val hashFromField = MagnetParser.normalizeHash(
                raw = result.canonicalHashHex.ifBlank { result.infoHash },
                familyHint = result.canonicalHashFamily
            )
            val verifiedIdentity = hashFromField ?: identities.firstOrNull()
            val listingId = result.listingId.ifBlank { result.id }
            val identity: CanonicalIdentityKey = verifiedIdentity
                ?: CanonicalIdentityKey.UnresolvedListing(result.source, listingId)

            val resolvedKnownSize = result.knownSizeBytes
                ?: QualityAndHealthParser.parseSizeToKnownBytes(result.sizeLabel)
            val safeSizeLabel = when {
                resolvedKnownSize != null -> QualityAndHealthParser.formatKnownBytes(resolvedKnownSize)
                result.sizeLabel.isNotBlank() && result.sizeLabel != "—" -> result.sizeLabel
                else -> "Unknown"
            }
            val safeDateLabel = when {
                result.knownUploadEpochSeconds != null -> result.uploadDateLabel.ifBlank { "Recent" }
                result.uploadDateLabel.isNotBlank() && result.uploadDateLabel != "—" -> result.uploadDateLabel
                else -> "Unknown"
            }

            return ProviderListing(
                source = result.source,
                listingId = listingId,
                title = result.title,
                category = result.category,
                knownSizeBytes = resolvedKnownSize,
                sizeLabel = safeSizeLabel,
                knownSeeders = result.knownSeeders,
                knownLeechers = result.knownLeechers,
                uploadDateLabel = safeDateLabel,
                knownUploadEpochSeconds = result.knownUploadEpochSeconds,
                magnetUri = result.magnetUri?.takeIf { it.isNotBlank() },
                canonicalIdentity = identity,
                detailPageUrl = result.detailPageUrl,
                mirrorUsed = mirrorUsed,
                rawResult = result
            )
        }
    }
}

data class CanonicalReleaseGroup(
    val groupKey: String,
    val canonicalIdentity: CanonicalIdentityKey,
    val representative: ProviderListing,
    val listings: List<ProviderListing>,
    val magnetState: MagnetResolutionState,
    val qualityTags: List<QualityTag>,
    val swarmHealth: SwarmHealth
) {
    val title: String get() = representative.title
    val primarySource: IndexerSource get() = representative.source
    val sources: List<IndexerSource> get() = listings.map { it.source }.distinct()
    val knownSeeders: Int? get() = representative.knownSeeders
    val knownLeechers: Int? get() = representative.knownLeechers
    val knownSizeBytes: Long? get() = representative.knownSizeBytes
    val sizeLabel: String get() = representative.sizeLabel
    val uploadDateLabel: String get() = representative.uploadDateLabel
    val knownUploadEpochSeconds: Long? get() = representative.knownUploadEpochSeconds
    val category: TorrentCategory get() = representative.category

    val verifiedHash: CanonicalIdentityKey.VerifiedHash?
        get() = (canonicalIdentity as? CanonicalIdentityKey.VerifiedHash)
            ?: (magnetState as? MagnetResolutionState.Available)?.identity

    val resolvedMagnetUri: String?
        get() = (magnetState as? MagnetResolutionState.Available)?.magnetUri
            ?: listings.firstNotNullOfOrNull { it.magnetUri }

    fun toRepresentativeTorrentResult(): TorrentResult {
        val base = representative.rawResult
        val verified = verifiedHash
        val magnet = resolvedMagnetUri
        return base.copy(
            title = title,
            category = category,
            sizeLabel = sizeLabel,
            sizeBytes = knownSizeBytes ?: 0L,
            seeders = knownSeeders ?: 0,
            leechers = knownLeechers ?: 0,
            uploadDateLabel = uploadDateLabel,
            uploadEpochSeconds = knownUploadEpochSeconds ?: 0L,
            magnetUri = magnet,
            infoHash = verified?.canonicalHex ?: base.infoHash,
            qualityTags = qualityTags,
            swarmHealth = swarmHealth,
            listingId = representative.listingId,
            knownSizeBytes = knownSizeBytes,
            knownSeeders = knownSeeders,
            knownLeechers = knownLeechers,
            knownUploadEpochSeconds = knownUploadEpochSeconds,
            canonicalHashHex = verified?.canonicalHex ?: base.canonicalHashHex,
            canonicalHashFamily = verified?.family ?: base.canonicalHashFamily
        )
    }

    companion object {
        val listingComparator: Comparator<ProviderListing> = Comparator { a, b ->
            // Higher known seeders first (unknown ranked after known 0)
            val sCmp = compareNullableDesc(a.knownSeeders, b.knownSeeders)
            if (sCmp != 0) return@Comparator sCmp
            // Listing already holding a verified magnet next
            val mCmp = (b.magnetUri != null).compareTo(a.magnetUri != null)
            if (mCmp != 0) return@Comparator mCmp
            // Known size before unknown
            val sizeCmp = compareNullableDesc(a.knownSizeBytes, b.knownSizeBytes)
            if (sizeCmp != 0) return@Comparator sizeCmp
            // Higher known leechers next
            val lCmp = compareNullableDesc(a.knownLeechers, b.knownLeechers)
            if (lCmp != 0) return@Comparator lCmp
            // Deterministic provider & listing ID tie-break
            val pCmp = a.source.ordinal.compareTo(b.source.ordinal)
            if (pCmp != 0) return@Comparator pCmp
            a.listingId.compareTo(b.listingId)
        }

        fun compareNullableDesc(a: Int?, b: Int?): Int = when {
            a != null && b != null -> b.compareTo(a)
            a != null && b == null -> -1
            a == null && b != null -> 1
            else -> 0
        }

        fun compareNullableDesc(a: Long?, b: Long?): Int = when {
            a != null && b != null -> b.compareTo(a)
            a != null && b == null -> -1
            a == null && b != null -> 1
            else -> 0
        }

        fun fromListings(
            groupKey: String,
            identity: CanonicalIdentityKey,
            listings: List<ProviderListing>,
            overrideMagnetState: MagnetResolutionState? = null
        ): CanonicalReleaseGroup {
            val ordered = listings
                .distinctBy { "${it.source.name}:${it.listingId}:${it.detailPageUrl}" }
                .sortedWith(listingComparator)
            val rep = ordered.first()
            val mergedQualityTags = ordered.flatMap { QualityAndHealthParser.extractQualityTags(it.title) }.distinct()
            val resolvedMagnet = ordered.firstNotNullOfOrNull { listing ->
                val uri = listing.magnetUri ?: return@firstNotNullOfOrNull null
                val hash = MagnetParser.extractVerifiedIdentities(uri).firstOrNull()
                    ?: (identity as? CanonicalIdentityKey.VerifiedHash)
                    ?: return@firstNotNullOfOrNull null
                MagnetResolutionState.Available(
                    magnetUri = uri,
                    identity = hash,
                    resolvedBySource = listing.source
                )
            } ?: (identity as? CanonicalIdentityKey.VerifiedHash)?.let { hash ->
                val synthesized = MagnetParser.buildMagnetFromHash(hash.canonicalHex, rep.title, hash.family)
                if (synthesized.isNotBlank()) {
                    MagnetResolutionState.Available(
                        magnetUri = synthesized,
                        identity = hash,
                        resolvedBySource = rep.source
                    )
                } else null
            }

            return CanonicalReleaseGroup(
                groupKey = groupKey,
                canonicalIdentity = identity,
                representative = rep,
                listings = ordered,
                magnetState = overrideMagnetState ?: resolvedMagnet ?: MagnetResolutionState.Idle,
                qualityTags = mergedQualityTags,
                swarmHealth = QualityAndHealthParser.computeSwarmHealth(rep.knownSeeders, rep.knownLeechers)
            )
        }
    }
}

data class SearchFilterState(
    val category: TorrentCategory = TorrentCategory.ALL,
    val sort: SortOption = SortOption.SEEDERS,
    val qualities: Set<QualityTag> = emptySet(),
    val selectedSources: Set<IndexerSource> = emptySet()
) {
    val activeFilterCount: Int
        get() = (if (category != TorrentCategory.ALL) 1 else 0) +
            qualities.size +
            selectedSources.size +
            (if (sort != SortOption.SEEDERS) 1 else 0)

    val hasNonDefaultFilters: Boolean
        get() = category != TorrentCategory.ALL ||
            qualities.isNotEmpty() ||
            selectedSources.isNotEmpty() ||
            sort != SortOption.SEEDERS
}

enum class SearchContentPhase {
    IDLE_HOME,
    SEARCHING_EMPTY,
    STREAMING_RESULTS,
    ZERO_MATCHES,
    FILTERED_OUT,
    PARTIAL_SUCCESS,
    COMPLETED_SUCCESS,
    TOTAL_FAILURE,
    ALL_SOURCES_DISABLED
}
