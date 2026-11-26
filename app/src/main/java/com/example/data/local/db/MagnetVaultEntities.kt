package com.example.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.example.domain.magnet.CanonicalIdentityKey
import com.example.domain.magnet.MagnetParser
import com.example.domain.magnet.TorrentHashFamily
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.QualityAndHealthParser
import com.example.model.QualityTag
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import org.json.JSONArray

object ScopeConstants {
    const val SCOPE_DEVICE = "DEVICE"
    fun accountScope(uid: String): String = "ACCOUNT:$uid"
}

@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["origin_scope", "canonical_key"], unique = true),
        Index(value = ["legacy_doc_id"]),
        Index(value = ["is_deleted", "updated_at_ms"])
    ]
)
data class BookmarkEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_id")
    val localId: String,
    @ColumnInfo(name = "origin_scope")
    val originScope: String = ScopeConstants.SCOPE_DEVICE,
    @ColumnInfo(name = "canonical_key")
    val canonicalKey: String,
    @ColumnInfo(name = "hash_family")
    val hashFamily: String? = null,
    @ColumnInfo(name = "canonical_hash_hex")
    val canonicalHashHex: String = "",
    @ColumnInfo(name = "legacy_doc_id")
    val legacyDocId: String = "",
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "size_label")
    val sizeLabel: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "known_size_bytes")
    val knownSizeBytes: Long? = null,
    @ColumnInfo(name = "seeders")
    val seeders: Int,
    @ColumnInfo(name = "known_seeders")
    val knownSeeders: Int? = null,
    @ColumnInfo(name = "leechers")
    val leechers: Int,
    @ColumnInfo(name = "known_leechers")
    val knownLeechers: Int? = null,
    @ColumnInfo(name = "upload_date_label")
    val uploadDateLabel: String,
    @ColumnInfo(name = "upload_epoch_seconds")
    val uploadEpochSeconds: Long = 0L,
    @ColumnInfo(name = "known_upload_epoch_seconds")
    val knownUploadEpochSeconds: Long? = null,
    @ColumnInfo(name = "magnet_uri")
    val magnetUri: String = "",
    @ColumnInfo(name = "info_hash")
    val infoHash: String = "",
    @ColumnInfo(name = "detail_page_url")
    val detailPageUrl: String = "",
    @ColumnInfo(name = "listing_id")
    val listingId: String = "",
    @ColumnInfo(name = "quality_tags_json")
    val qualityTagsJson: String = "[]",
    @ColumnInfo(name = "created_at_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtEpochMs: Long,
    @ColumnInfo(name = "local_revision")
    val localRevision: Long = 1L,
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,
    @ColumnInfo(name = "deleted_at_ms")
    val deletedAtEpochMs: Long? = null
) {
    fun qualityTagNames(): List<String> = runCatching {
        val arr = JSONArray(qualityTagsJson)
        buildList {
            for (i in 0 until arr.length()) {
                val raw = arr.optString(i).trim()
                if (raw.isNotEmpty()) add(raw)
            }
        }
    }.getOrDefault(emptyList())

    fun hasResolvedMagnet(): Boolean =
        magnetUri.isNotBlank() && MagnetParser.extractVerifiedIdentities(magnetUri).isNotEmpty()

    fun toTorrentResult(): TorrentResult {
        val parsedSource = IndexerSource.fromNameSafe(source)
        val parsedCategory = TorrentCategory.fromNameSafe(category)
        val parsedTags = qualityTagNames().mapNotNull { QualityTag.fromLabelOrName(it) }
            .ifEmpty { QualityAndHealthParser.extractQualityTags(title) }
        val verified = MagnetParser.extractVerifiedIdentities(magnetUri).firstOrNull()
            ?: MagnetParser.normalizeHash(canonicalHashHex.ifBlank { infoHash })
        return TorrentResult(
            id = localId,
            title = title,
            source = parsedSource,
            category = parsedCategory,
            sizeLabel = sizeLabel.ifBlank { QualityAndHealthParser.formatKnownBytes(knownSizeBytes ?: sizeBytes.takeIf { it > 0L }) },
            sizeBytes = sizeBytes.coerceAtLeast(0L),
            seeders = seeders.coerceAtLeast(0),
            leechers = leechers.coerceAtLeast(0),
            uploadDateLabel = uploadDateLabel.ifBlank { "Unknown" },
            uploadEpochSeconds = uploadEpochSeconds,
            magnetUri = magnetUri.ifBlank { null },
            infoHash = verified?.canonicalHex ?: infoHash,
            detailPageUrl = detailPageUrl,
            qualityTags = parsedTags,
            swarmHealth = QualityAndHealthParser.computeSwarmHealth(knownSeeders ?: seeders, knownLeechers ?: leechers),
            listingId = listingId.ifBlank { localId },
            knownSizeBytes = knownSizeBytes ?: sizeBytes.takeIf { it > 0L },
            knownSeeders = knownSeeders ?: seeders,
            knownLeechers = knownLeechers ?: leechers,
            knownUploadEpochSeconds = knownUploadEpochSeconds ?: uploadEpochSeconds.takeIf { it > 0L },
            canonicalHashHex = verified?.canonicalHex ?: canonicalHashHex,
            canonicalHashFamily = verified?.family ?: hashFamily?.let {
                runCatching { TorrentHashFamily.valueOf(it) }.getOrNull()
            }
        )
    }

    fun resolvedIdentityOrNull(): CanonicalIdentityKey.VerifiedHash? =
        MagnetParser.extractVerifiedIdentities(magnetUri).firstOrNull()
            ?: MagnetParser.normalizeHash(
                raw = canonicalHashHex.ifBlank { infoHash },
                familyHint = hashFamily?.let { runCatching { TorrentHashFamily.valueOf(it) }.getOrNull() }
            )
}

@Entity(
    tableName = "provider_listings",
    foreignKeys = [
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["bookmark_local_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookmark_local_id"]),
        Index(value = ["bookmark_local_id", "source", "listing_id"], unique = true)
    ]
)
data class ProviderListingEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "bookmark_local_id")
    val bookmarkLocalId: String,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "listing_id")
    val listingId: String,
    @ColumnInfo(name = "detail_page_url")
    val detailPageUrl: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "seeders")
    val seeders: Int,
    @ColumnInfo(name = "leechers")
    val leechers: Int,
    @ColumnInfo(name = "upload_date_label")
    val uploadDateLabel: String,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "identity_aliases",
    primaryKeys = ["origin_scope", "alias_key"],
    foreignKeys = [
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["bookmark_local_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookmark_local_id"])
    ]
)
data class IdentityAliasEntity(
    @ColumnInfo(name = "origin_scope")
    val originScope: String,
    @ColumnInfo(name = "alias_key")
    val aliasKey: String,
    @ColumnInfo(name = "bookmark_local_id")
    val bookmarkLocalId: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "collections",
    indices = [
        Index(value = ["normalized_name"], unique = true)
    ]
)
data class CollectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "collection_id")
    val collectionId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "collection_bookmarks",
    primaryKeys = ["collection_id", "bookmark_local_id"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["collection_id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["bookmark_local_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookmark_local_id"]),
        Index(value = ["collection_id"])
    ]
)
data class CollectionBookmarkCrossRef(
    @ColumnInfo(name = "collection_id")
    val collectionId: String,
    @ColumnInfo(name = "bookmark_local_id")
    val bookmarkLocalId: String,
    @ColumnInfo(name = "added_at_ms")
    val addedAtEpochMs: Long
)

data class BookmarkWithCollections(
    @Embedded val bookmark: BookmarkEntity,
    @Relation(
        parentColumn = "local_id",
        entityColumn = "collection_id",
        associateBy = Junction(
            value = CollectionBookmarkCrossRef::class,
            parentColumn = "bookmark_local_id",
            entityColumn = "collection_id"
        )
    )
    val collections: List<CollectionEntity>
)

data class CollectionWithCount(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "created_at_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtEpochMs: Long,
    @ColumnInfo(name = "bookmark_count") val bookmarkCount: Int
)

@Entity(
    tableName = "search_history",
    indices = [
        Index(value = ["origin_scope", "normalized_query"], unique = true),
        Index(value = ["cloud_doc_id"]),
        Index(value = ["is_deleted", "updated_at_ms"])
    ]
)
data class SearchHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "origin_scope")
    val originScope: String = ScopeConstants.SCOPE_DEVICE,
    @ColumnInfo(name = "cloud_doc_id")
    val cloudDocId: String,
    @ColumnInfo(name = "query")
    val query: String,
    @ColumnInfo(name = "normalized_query")
    val normalizedQuery: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtEpochMs: Long,
    @ColumnInfo(name = "local_revision")
    val localRevision: Long = 1L,
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,
    @ColumnInfo(name = "deleted_at_ms")
    val deletedAtEpochMs: Long? = null
)

@Entity(
    tableName = "custom_sites",
    indices = [
        Index(value = ["normalized_url"], unique = true)
    ]
)
data class CustomSiteEntity(
    @PrimaryKey
    @ColumnInfo(name = "site_id")
    val siteId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "url")
    val url: String,
    @ColumnInfo(name = "normalized_url")
    val normalizedUrl: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtEpochMs: Long
)

@Entity(tableName = "mirror_configs")
data class MirrorConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "mirrors_json")
    val mirrorsJson: String,
    @ColumnInfo(name = "active_mirror_url")
    val activeMirrorUrl: String,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtEpochMs: Long,
    @ColumnInfo(name = "local_revision")
    val localRevision: Long = 1L
) {
    fun toMirrorConfig(): MirrorConfig {
        val src = IndexerSource.fromNameSafe(provider)
        val parsed = runCatching {
            val arr = JSONArray(mirrorsJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i).trim().trimEnd('/')
                    if (u.startsWith("http://") || u.startsWith("https://")) add(u)
                }
            }
        }.getOrDefault(emptyList()).distinct().ifEmpty { src.defaultMirrors }
        val active = activeMirrorUrl.trim().trimEnd('/').takeIf { it in parsed } ?: parsed.first()
        return MirrorConfig(
            provider = src,
            enabled = enabled,
            mirrors = parsed,
            activeMirrorUrl = active
        )
    }
}

@Entity(tableName = "migration_status")
data class MigrationStatusEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = 1,
    @ColumnInfo(name = "legacy_import_completed")
    val legacyImportCompleted: Boolean = false,
    @ColumnInfo(name = "legacy_import_version")
    val legacyImportVersion: Int = 1,
    @ColumnInfo(name = "completed_at_ms")
    val completedAtEpochMs: Long? = null,
    @ColumnInfo(name = "imported_bookmarks_count")
    val importedBookmarksCount: Int = 0,
    @ColumnInfo(name = "imported_history_count")
    val importedHistoryCount: Int = 0,
    @ColumnInfo(name = "imported_sites_count")
    val importedSitesCount: Int = 0,
    @ColumnInfo(name = "imported_mirrors_count")
    val importedMirrorsCount: Int = 0,
    @ColumnInfo(name = "malformed_bookmarks_count")
    val malformedBookmarksCount: Int = 0,
    @ColumnInfo(name = "malformed_history_count")
    val malformedHistoryCount: Int = 0,
    @ColumnInfo(name = "malformed_sites_count")
    val malformedSitesCount: Int = 0,
    @ColumnInfo(name = "malformed_mirrors_count")
    val malformedMirrorsCount: Int = 0,
    @ColumnInfo(name = "last_error_category")
    val lastErrorCategory: String? = null
) {
    val totalMalformedCount: Int
        get() = malformedBookmarksCount + malformedHistoryCount + malformedSitesCount + malformedMirrorsCount
}

object SyncEntityType {
    const val BOOKMARK = "BOOKMARK"
    const val HISTORY = "HISTORY"
    const val MIRROR = "MIRROR"
}

object SyncOpType {
    const val UPSERT = "UPSERT"
    const val DELETE = "DELETE"
}

object SyncOutboxStatus {
    const val PENDING = "PENDING"
    const val IN_FLIGHT = "IN_FLIGHT"
    const val ACTION_NEEDED = "ACTION_NEEDED"
}

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["account_uid", "status", "created_at_ms"]),
        Index(value = ["account_uid", "entity_type", "local_entity_id"])
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "op_id")
    val opId: String,
    @ColumnInfo(name = "account_uid")
    val accountUid: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "local_entity_id")
    val localEntityId: String,
    @ColumnInfo(name = "cloud_doc_id")
    val cloudDocId: String,
    @ColumnInfo(name = "op_type")
    val opType: String,
    @ColumnInfo(name = "expected_server_revision")
    val expectedServerRevision: Long = 0L,
    @ColumnInfo(name = "target_local_revision")
    val targetLocalRevision: Long = 1L,
    @ColumnInfo(name = "is_explicit_resave")
    val isExplicitResave: Boolean = false,
    @ColumnInfo(name = "status")
    val status: String = SyncOutboxStatus.PENDING,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error_category")
    val lastErrorCategory: String? = null,
    @ColumnInfo(name = "created_at_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "cloud_record_links",
    primaryKeys = ["account_uid", "entity_type", "cloud_doc_id"],
    indices = [
        Index(value = ["account_uid", "entity_type", "local_entity_id"])
    ]
)
data class CloudRecordLinkEntity(
    @ColumnInfo(name = "account_uid")
    val accountUid: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "cloud_doc_id")
    val cloudDocId: String,
    @ColumnInfo(name = "local_entity_id")
    val localEntityId: String,
    @ColumnInfo(name = "server_revision")
    val serverRevision: Long = 0L,
    @ColumnInfo(name = "last_synced_local_revision")
    val lastSyncedLocalRevision: Long = 0L,
    @ColumnInfo(name = "server_deleted")
    val serverDeleted: Boolean = false,
    @ColumnInfo(name = "server_updated_at_ms")
    val serverUpdatedAtMs: Long = 0L
)
