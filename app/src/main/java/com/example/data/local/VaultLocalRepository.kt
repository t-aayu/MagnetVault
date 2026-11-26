package com.example.data.local

import androidx.room.withTransaction
import com.example.data.local.db.BookmarkEntity
import com.example.data.local.db.BookmarkWithCollections
import com.example.data.local.db.CloudRecordLinkEntity
import com.example.data.local.db.CollectionBookmarkCrossRef
import com.example.data.local.db.CollectionEntity
import com.example.data.local.db.CollectionWithCount
import com.example.data.local.db.CustomSiteEntity
import com.example.data.local.db.IdentityAliasEntity
import com.example.data.local.db.MagnetVaultDatabase
import com.example.data.local.db.MigrationStatusEntity
import com.example.data.local.db.MirrorConfigEntity
import com.example.data.local.db.ProviderListingEntity
import com.example.data.local.db.ScopeConstants
import com.example.data.local.db.SearchHistoryEntity
import com.example.data.local.db.SyncEntityType
import com.example.data.local.db.SyncOpType
import com.example.data.local.db.SyncOutboxEntity
import com.example.data.local.db.SyncOutboxStatus
import com.example.domain.magnet.CanonicalIdentityKey
import com.example.domain.magnet.MagnetParser
import com.example.model.CustomSite
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.QualityAndHealthParser
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.util.Locale
import java.util.UUID

data class ActiveSyncContext(
    val accountUid: String,
    val syncBookmarks: Boolean = true,
    val syncHistory: Boolean = false,
    val syncMirrors: Boolean = false
)

data class BookmarkSaveOutcome(
    val localId: String,
    val canonicalKey: String,
    val upgradedFromUnresolved: Boolean
)

class VaultLocalRepository(
    val db: MagnetVaultDatabase,
    private val migrator: LegacyDataMigrator,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val bookmarkDao = db.bookmarkDao()
    private val collectionDao = db.collectionDao()
    private val historyDao = db.searchHistoryDao()
    private val customSiteDao = db.customSiteDao()
    private val mirrorDao = db.mirrorConfigDao()
    private val migrationDao = db.migrationStatusDao()
    private val syncDao = db.syncDao()

    suspend fun ensureMigrated(): MigrationStatusEntity = migrator.ensureMigrated()

    suspend fun retryMigration(): MigrationStatusEntity = migrator.retryMigration()

    fun observeMigrationStatus(): Flow<MigrationStatusEntity?> = flow {
        ensureMigrated()
        emitAll(migrationDao.observeStatus())
    }

    // --- BOOKMARKS ---
    fun observeBookmarks(accountUid: String?): Flow<List<BookmarkWithCollections>> = flow {
        ensureMigrated()
        val scope = accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }
        emitAll(bookmarkDao.observeActiveBookmarksWithCollections(scope))
    }

    fun observeActiveMatchKeys(accountUid: String?): Flow<Set<String>> = flow {
        ensureMigrated()
        val scope = accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }
        emitAll(bookmarkDao.observeActiveBookmarkMatchKeys(scope).map { it.toSet() })
    }

    suspend fun getBookmarkMatchKeysOnce(accountUid: String?): List<BookmarkWithCollections> {
        ensureMigrated()
        val scope = accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }
        return bookmarkDao.getActiveBookmarksWithCollectionsOnce(scope)
    }

    suspend fun saveOrUpdateBookmark(
        torrent: TorrentResult,
        existingLocalIdHint: String? = null,
        syncContext: ActiveSyncContext? = null,
        forceResave: Boolean = true
    ): Result<BookmarkSaveOutcome> = runCatching {
        ensureMigrated()
        val nowMs = clockMs()
        val verified = MagnetParser.extractVerifiedIdentities(torrent.magnetUri).firstOrNull()
            ?: MagnetParser.normalizeHash(
                raw = torrent.canonicalHashHex.ifBlank { torrent.infoHash },
                familyHint = torrent.canonicalHashFamily
            )
        val listingId = torrent.listingId.ifBlank { torrent.id }.ifBlank {
            UUID.nameUUIDFromBytes("${torrent.source.name}:${torrent.title}".toByteArray()).toString()
        }
        val unresolvedKey = CanonicalIdentityKey.UnresolvedListing(torrent.source, listingId).storageKey
        val targetCanonicalKey = verified?.storageKey ?: unresolvedKey
        val accountScope = syncContext?.accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }

        var resultOutcome: BookmarkSaveOutcome? = null

        db.withTransaction {
            val byHint = existingLocalIdHint?.takeIf { it.isNotBlank() }?.let { bookmarkDao.getByLocalId(it) }
            val byCanonical = bookmarkDao.findVisibleOrTombstoneByCanonicalKey(targetCanonicalKey, accountScope)
            val byAlias = bookmarkDao.findByAliasInVisibleScopes(targetCanonicalKey, accountScope)
                ?: bookmarkDao.findByAliasInVisibleScopes(unresolvedKey, accountScope)
                ?: bookmarkDao.findVisibleOrTombstoneByCanonicalKey(unresolvedKey, accountScope)

            val existingPrimary = byHint ?: byCanonical ?: byAlias
            val conflictTarget = if (existingPrimary != null && existingPrimary.canonicalKey != targetCanonicalKey) {
                bookmarkDao.findByScopeAndCanonicalKey(existingPrimary.originScope, targetCanonicalKey)
                    ?.takeIf { it.localId != existingPrimary.localId }
            } else {
                null
            }

            val mergedCollections = LinkedHashSet<String>()
            val mergedAliases = LinkedHashSet<String>().apply {
                add(targetCanonicalKey)
                add(unresolvedKey)
            }

            if (existingPrimary != null && conflictTarget != null) {
                // Upgrade collided with an already-existing bookmark holding the verified hash in this scope:
                // preserve collection memberships, merge aliases/listings onto conflictTarget, retire unresolved row.
                mergedCollections += collectionDao.getCollectionIdsForBookmark(existingPrimary.localId)
                mergedCollections += collectionDao.getCollectionIdsForBookmark(conflictTarget.localId)
                bookmarkDao.getAliasesForBookmark(existingPrimary.localId).forEach { mergedAliases += it.aliasKey }
                bookmarkDao.getAliasesForBookmark(conflictTarget.localId).forEach { mergedAliases += it.aliasKey }

                val retired = existingPrimary.copy(
                    isDeleted = true,
                    deletedAtEpochMs = nowMs,
                    updatedAtEpochMs = nowMs,
                    localRevision = existingPrimary.localRevision + 1L
                )
                bookmarkDao.upsertBookmark(retired)
                if (syncContext != null && syncContext.syncBookmarks) {
                    val retiredLink = syncDao.getRecordLinkByLocalId(
                        syncContext.accountUid,
                        SyncEntityType.BOOKMARK,
                        retired.localId
                    )
                    if (retiredLink != null && !retiredLink.serverDeleted) {
                        enqueueOutboxTx(
                            accountUid = syncContext.accountUid,
                            entityType = SyncEntityType.BOOKMARK,
                            localEntityId = retired.localId,
                            cloudDocId = retiredLink.cloudDocId,
                            opType = SyncOpType.DELETE,
                            expectedServerRevision = retiredLink.serverRevision,
                            targetLocalRevision = retired.localRevision,
                            isExplicitResave = false,
                            nowMs = nowMs
                        )
                    }
                }
            }

            val survivor = conflictTarget ?: existingPrimary
            val upgradedFromUnresolved = survivor != null &&
                survivor.canonicalKey.startsWith("listing:") &&
                verified != null

            val finalLocalId = survivor?.localId ?: LegacyDataMigrator.deterministicDeviceBookmarkId(targetCanonicalKey)
            val safeTitle = torrent.title.trim().ifBlank { survivor?.title ?: "Untitled Torrent" }.take(500)
            val safeMagnet = torrent.magnetUri?.trim().orEmpty().ifBlank { survivor?.magnetUri.orEmpty() }.take(4000)
            val safeHash = verified?.canonicalHex ?: survivor?.canonicalHashHex.orEmpty().ifBlank { torrent.infoHash }.take(128)
            if (survivor != null && survivor.legacyDocId.isNotBlank()) {
                mergedAliases += "legacy:${survivor.legacyDocId}"
            }
            val safeLegacyDocId = if (upgradedFromUnresolved) {
                stableBookmarkCloudDocId(targetCanonicalKey, torrent)
            } else {
                survivor?.legacyDocId?.takeIf { it.isNotBlank() }
                    ?: stableBookmarkCloudDocId(targetCanonicalKey, torrent)
            }
            val knownSize = torrent.knownSizeBytes ?: torrent.sizeBytes.takeIf { it > 0L } ?: survivor?.knownSizeBytes
            val sizeLabel = torrent.sizeLabel.takeIf { it.isNotBlank() && it != "Unknown" }
                ?: survivor?.sizeLabel?.takeIf { it.isNotBlank() }
                ?: QualityAndHealthParser.formatKnownBytes(knownSize)
            val tags = torrent.qualityTags.map { it.label }.ifEmpty {
                survivor?.qualityTagNames().orEmpty()
            }.distinct().take(10)

            val updatedEntity = BookmarkEntity(
                localId = finalLocalId,
                originScope = survivor?.originScope ?: ScopeConstants.SCOPE_DEVICE,
                canonicalKey = targetCanonicalKey,
                hashFamily = verified?.family?.name ?: survivor?.hashFamily,
                canonicalHashHex = safeHash,
                legacyDocId = safeLegacyDocId,
                title = safeTitle,
                source = torrent.source.name,
                category = torrent.category.name,
                sizeLabel = sizeLabel.take(64),
                sizeBytes = (knownSize ?: torrent.sizeBytes).coerceAtLeast(0L),
                knownSizeBytes = knownSize,
                seeders = torrent.seeders.coerceAtLeast(0),
                knownSeeders = torrent.knownSeeders ?: survivor?.knownSeeders,
                leechers = torrent.leechers.coerceAtLeast(0),
                knownLeechers = torrent.knownLeechers ?: survivor?.knownLeechers,
                uploadDateLabel = torrent.uploadDateLabel.ifBlank { survivor?.uploadDateLabel ?: "Unknown" }.take(64),
                uploadEpochSeconds = torrent.uploadEpochSeconds,
                knownUploadEpochSeconds = torrent.knownUploadEpochSeconds ?: survivor?.knownUploadEpochSeconds,
                magnetUri = safeMagnet,
                infoHash = safeHash,
                detailPageUrl = torrent.detailPageUrl.ifBlank { survivor?.detailPageUrl.orEmpty() }.take(1000),
                listingId = listingId,
                qualityTagsJson = JSONArray(tags).toString(),
                createdAtEpochMs = survivor?.createdAtEpochMs ?: nowMs,
                updatedAtEpochMs = nowMs,
                localRevision = (survivor?.localRevision ?: 0L) + 1L,
                isDeleted = false,
                deletedAtEpochMs = null
            )

            bookmarkDao.upsertBookmark(updatedEntity)

            mergedAliases += "legacy:$safeLegacyDocId"
            mergedAliases.forEach { aliasKey ->
                bookmarkDao.upsertIdentityAlias(
                    IdentityAliasEntity(
                        originScope = updatedEntity.originScope,
                        aliasKey = aliasKey,
                        bookmarkLocalId = finalLocalId,
                        createdAtEpochMs = nowMs
                    )
                )
            }

            bookmarkDao.upsertProviderListing(
                ProviderListingEntity(
                    bookmarkLocalId = finalLocalId,
                    source = torrent.source.name,
                    listingId = listingId,
                    detailPageUrl = torrent.detailPageUrl.take(1000),
                    sizeBytes = (knownSize ?: torrent.sizeBytes).coerceAtLeast(0L),
                    seeders = torrent.seeders.coerceAtLeast(0),
                    leechers = torrent.leechers.coerceAtLeast(0),
                    uploadDateLabel = torrent.uploadDateLabel.take(64),
                    updatedAtEpochMs = nowMs
                )
            )

            mergedCollections.forEach { collectionId ->
                collectionDao.addBookmarkToCollection(
                    CollectionBookmarkCrossRef(
                        collectionId = collectionId,
                        bookmarkLocalId = finalLocalId,
                        addedAtEpochMs = nowMs
                    )
                )
            }

            if (syncContext != null && syncContext.syncBookmarks) {
                val existingLink = syncDao.getRecordLinkByLocalId(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.BOOKMARK,
                    localEntityId = finalLocalId
                )
                val cloudDocId = existingLink?.cloudDocId ?: updatedEntity.legacyDocId
                enqueueOutboxTx(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.BOOKMARK,
                    localEntityId = finalLocalId,
                    cloudDocId = cloudDocId,
                    opType = SyncOpType.UPSERT,
                    expectedServerRevision = existingLink?.serverRevision ?: 0L,
                    targetLocalRevision = updatedEntity.localRevision,
                    isExplicitResave = forceResave,
                    nowMs = nowMs
                )
            }

            resultOutcome = BookmarkSaveOutcome(
                localId = finalLocalId,
                canonicalKey = targetCanonicalKey,
                upgradedFromUnresolved = upgradedFromUnresolved
            )
        }
        resultOutcome!!
    }

    suspend fun removeBookmark(
        localId: String,
        syncContext: ActiveSyncContext? = null
    ): Result<Boolean> = runCatching {
        ensureMigrated()
        val nowMs = clockMs()
        var removed = false
        db.withTransaction {
            val entity = bookmarkDao.getByLocalId(localId) ?: return@withTransaction
            if (entity.isDeleted) return@withTransaction
            val nextRev = entity.localRevision + 1L
            bookmarkDao.upsertBookmark(
                entity.copy(
                    isDeleted = true,
                    deletedAtEpochMs = nowMs,
                    updatedAtEpochMs = nowMs,
                    localRevision = nextRev
                )
            )
            if (syncContext != null && syncContext.syncBookmarks) {
                val link = syncDao.getRecordLinkByLocalId(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.BOOKMARK,
                    localEntityId = entity.localId
                )
                val cloudDocId = link?.cloudDocId ?: entity.legacyDocId.ifBlank {
                    QualityAndHealthParser.sanitizeDocumentId("bm_${entity.localId}")
                }
                enqueueOutboxTx(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.BOOKMARK,
                    localEntityId = entity.localId,
                    cloudDocId = cloudDocId,
                    opType = SyncOpType.DELETE,
                    expectedServerRevision = link?.serverRevision ?: 0L,
                    targetLocalRevision = nextRev,
                    isExplicitResave = false,
                    nowMs = nowMs
                )
            }
            removed = true
        }
        removed
    }

    suspend fun undoRemoveBookmark(
        localId: String,
        syncContext: ActiveSyncContext? = null
    ): Result<Boolean> = runCatching {
        ensureMigrated()
        val nowMs = clockMs()
        var restored = false
        db.withTransaction {
            val entity = bookmarkDao.getByLocalId(localId) ?: return@withTransaction
            val nextRev = entity.localRevision + 1L
            bookmarkDao.upsertBookmark(
                entity.copy(
                    isDeleted = false,
                    deletedAtEpochMs = null,
                    updatedAtEpochMs = nowMs,
                    localRevision = nextRev
                )
            )
            if (syncContext != null && syncContext.syncBookmarks) {
                val link = syncDao.getRecordLinkByLocalId(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.BOOKMARK,
                    localEntityId = entity.localId
                )
                val cloudDocId = link?.cloudDocId ?: entity.legacyDocId.ifBlank {
                    QualityAndHealthParser.sanitizeDocumentId("bm_${entity.localId}")
                }
                enqueueOutboxTx(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.BOOKMARK,
                    localEntityId = entity.localId,
                    cloudDocId = cloudDocId,
                    opType = SyncOpType.UPSERT,
                    expectedServerRevision = link?.serverRevision ?: 0L,
                    targetLocalRevision = nextRev,
                    isExplicitResave = true,
                    nowMs = nowMs
                )
            }
            restored = true
        }
        restored
    }

    suspend fun findLocalIdForCandidateKeys(
        candidateKeys: List<String>,
        accountUid: String?
    ): String? {
        ensureMigrated()
        val accountScope = accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }
        for (key in candidateKeys) {
            bookmarkDao.findVisibleOrTombstoneByCanonicalKey(key, accountScope)
                ?.takeIf { !it.isDeleted }
                ?.let { return it.localId }
            bookmarkDao.findByAliasInVisibleScopes(key, accountScope)
                ?.takeIf { !it.isDeleted }
                ?.let { return it.localId }
            bookmarkDao.findByLegacyDocIdInVisibleScopes(key, accountScope)
                ?.takeIf { !it.isDeleted }
                ?.let { return it.localId }
        }
        return null
    }

    // --- COLLECTIONS (ALWAYS DEVICE-LOCAL) ---
    fun observeCollections(accountUid: String?): Flow<List<CollectionWithCount>> = flow {
        ensureMigrated()
        val scope = accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }
        emitAll(collectionDao.observeCollectionsWithCount(scope))
    }

    suspend fun createCollection(rawName: String): Result<CollectionEntity> = runCatching {
        ensureMigrated()
        val cleanName = rawName.trim().take(50)
        require(cleanName.isNotBlank()) { "Collection name cannot be empty" }
        val normalized = cleanName.lowercase(Locale.US)
        val nowMs = clockMs()
        var created: CollectionEntity? = null
        db.withTransaction {
            val existing = collectionDao.findByNormalizedName(normalized)
            if (existing != null) {
                throw IllegalStateException("Collection '$cleanName' already exists")
            }
            val entity = CollectionEntity(
                collectionId = UUID.randomUUID().toString(),
                name = cleanName,
                normalizedName = normalized,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            )
            collectionDao.upsertCollection(entity)
            created = entity
        }
        created!!
    }

    suspend fun renameCollection(collectionId: String, rawNewName: String): Result<Unit> = runCatching {
        ensureMigrated()
        val cleanName = rawNewName.trim().take(50)
        require(cleanName.isNotBlank()) { "Collection name cannot be empty" }
        val normalized = cleanName.lowercase(Locale.US)
        val nowMs = clockMs()
        db.withTransaction {
            val existing = collectionDao.getById(collectionId)
                ?: throw IllegalStateException("Collection not found")
            val collision = collectionDao.findByNormalizedName(normalized)
            if (collision != null && collision.collectionId != collectionId) {
                throw IllegalStateException("Another collection already uses '$cleanName'")
            }
            collectionDao.upsertCollection(
                existing.copy(
                    name = cleanName,
                    normalizedName = normalized,
                    updatedAtEpochMs = nowMs
                )
            )
        }
    }

    suspend fun deleteCollection(collectionId: String): Result<Unit> = runCatching {
        ensureMigrated()
        // Deletes only the collection row (Room CASCADE deletes cross-refs only; bookmarks remain untouched).
        collectionDao.deleteCollectionById(collectionId)
    }

    suspend fun setBookmarkCollections(
        bookmarkLocalId: String,
        collectionIds: Set<String>
    ): Result<Unit> = runCatching {
        ensureMigrated()
        val nowMs = clockMs()
        db.withTransaction {
            collectionDao.clearCollectionsForBookmark(bookmarkLocalId)
            collectionIds.forEach { cid ->
                collectionDao.addBookmarkToCollection(
                    CollectionBookmarkCrossRef(
                        collectionId = cid,
                        bookmarkLocalId = bookmarkLocalId,
                        addedAtEpochMs = nowMs
                    )
                )
            }
        }
    }

    // --- SEARCH HISTORY ---
    fun observeHistory(accountUid: String?, limit: Int = 50): Flow<List<SearchHistoryEntity>> = flow {
        ensureMigrated()
        val scope = accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }
        emitAll(historyDao.observeActiveHistory(scope, limit))
    }

    suspend fun recordSearchQuery(
        query: String,
        category: TorrentCategory,
        syncContext: ActiveSyncContext? = null
    ): Result<Unit> = runCatching {
        ensureMigrated()
        val trimmed = query.trim().take(200)
        if (trimmed.isBlank()) return@runCatching
        val normalized = trimmed.lowercase(Locale.US)
        val cloudDocId = QualityAndHealthParser.sanitizeDocumentId("q_${normalized.hashCode().toUInt()}")
        val nowMs = clockMs()
        val accountScope = syncContext?.accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }

        db.withTransaction {
            val existing = (accountScope?.let { historyDao.findByScopeAndNormalizedQuery(it, normalized) })
                ?: historyDao.findByScopeAndNormalizedQuery(ScopeConstants.SCOPE_DEVICE, normalized)
            val entity = if (existing != null) {
                existing.copy(
                    query = trimmed,
                    category = category.name,
                    updatedAtEpochMs = nowMs,
                    localRevision = existing.localRevision + 1L,
                    isDeleted = false,
                    deletedAtEpochMs = null
                )
            } else {
                SearchHistoryEntity(
                    entryId = UUID.nameUUIDFromBytes("device:hist:$normalized".toByteArray()).toString(),
                    originScope = ScopeConstants.SCOPE_DEVICE,
                    cloudDocId = cloudDocId,
                    query = trimmed,
                    normalizedQuery = normalized,
                    category = category.name,
                    createdAtEpochMs = nowMs,
                    updatedAtEpochMs = nowMs,
                    localRevision = 1L,
                    isDeleted = false,
                    deletedAtEpochMs = null
                )
            }
            historyDao.upsertHistory(entity)

            if (syncContext != null && syncContext.syncHistory) {
                val link = syncDao.getRecordLinkByLocalId(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.HISTORY,
                    localEntityId = entity.entryId
                )
                enqueueOutboxTx(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.HISTORY,
                    localEntityId = entity.entryId,
                    cloudDocId = link?.cloudDocId ?: entity.cloudDocId,
                    opType = SyncOpType.UPSERT,
                    expectedServerRevision = link?.serverRevision ?: 0L,
                    targetLocalRevision = entity.localRevision,
                    isExplicitResave = true,
                    nowMs = nowMs
                )
            }
        }
    }

    suspend fun deleteHistoryEntry(
        entryIdOrCloudId: String,
        syncContext: ActiveSyncContext? = null
    ): Result<Unit> = runCatching {
        ensureMigrated()
        val nowMs = clockMs()
        val accountScope = syncContext?.accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }
        db.withTransaction {
            val existing = historyDao.getByEntryId(entryIdOrCloudId)
                ?: historyDao.findByCloudDocIdInVisibleScopes(entryIdOrCloudId, accountScope)
                ?: return@withTransaction
            if (existing.isDeleted) return@withTransaction
            val nextRev = existing.localRevision + 1L
            historyDao.upsertHistory(
                existing.copy(
                    isDeleted = true,
                    deletedAtEpochMs = nowMs,
                    updatedAtEpochMs = nowMs,
                    localRevision = nextRev
                )
            )
            if (syncContext != null && syncContext.syncHistory) {
                val link = syncDao.getRecordLinkByLocalId(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.HISTORY,
                    localEntityId = existing.entryId
                )
                enqueueOutboxTx(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.HISTORY,
                    localEntityId = existing.entryId,
                    cloudDocId = link?.cloudDocId ?: existing.cloudDocId,
                    opType = SyncOpType.DELETE,
                    expectedServerRevision = link?.serverRevision ?: 0L,
                    targetLocalRevision = nextRev,
                    isExplicitResave = false,
                    nowMs = nowMs
                )
            }
        }
    }

    suspend fun clearAllHistory(syncContext: ActiveSyncContext? = null): Result<Unit> = runCatching {
        ensureMigrated()
        val nowMs = clockMs()
        val accountScope = syncContext?.accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }
        db.withTransaction {
            val active = historyDao.getActiveHistoryOnce(accountScope)
            active.forEach { entry ->
                val nextRev = entry.localRevision + 1L
                historyDao.upsertHistory(
                    entry.copy(
                        isDeleted = true,
                        deletedAtEpochMs = nowMs,
                        updatedAtEpochMs = nowMs,
                        localRevision = nextRev
                    )
                )
                if (syncContext != null && syncContext.syncHistory) {
                    val link = syncDao.getRecordLinkByLocalId(
                        accountUid = syncContext.accountUid,
                        entityType = SyncEntityType.HISTORY,
                        localEntityId = entry.entryId
                    )
                    enqueueOutboxTx(
                        accountUid = syncContext.accountUid,
                        entityType = SyncEntityType.HISTORY,
                        localEntityId = entry.entryId,
                        cloudDocId = link?.cloudDocId ?: entry.cloudDocId,
                        opType = SyncOpType.DELETE,
                        expectedServerRevision = link?.serverRevision ?: 0L,
                        targetLocalRevision = nextRev,
                        isExplicitResave = false,
                        nowMs = nowMs
                    )
                }
            }
        }
    }

    // --- CUSTOM SITES (DEVICE-LOCAL) ---
    fun observeCustomSites(): Flow<List<CustomSite>> = flow {
        ensureMigrated()
        emitAll(
            customSiteDao.observeCustomSites().map { list ->
                list.map { CustomSite(id = it.siteId, name = it.name, url = it.url, isCustom = true) }
            }
        )
    }

    suspend fun addCustomSite(name: String, normalizedUrl: String): Result<Unit> = runCatching {
        ensureMigrated()
        val nowMs = clockMs()
        val cleanName = name.trim().take(40).ifBlank {
            normalizedUrl.removePrefix("https://").removePrefix("http://").substringBefore("/").take(30)
        }
        val normalizedKey = normalizedUrl.trim().lowercase(Locale.US).trimEnd('/')
        val siteId = QualityAndHealthParser.sanitizeDocumentId("site_${normalizedKey.hashCode().toUInt()}")
        db.withTransaction {
            val existing = customSiteDao.findByNormalizedUrl(normalizedKey)
            val entity = CustomSiteEntity(
                siteId = existing?.siteId ?: siteId,
                name = cleanName,
                url = normalizedUrl.trim(),
                normalizedUrl = normalizedKey,
                createdAtEpochMs = existing?.createdAtEpochMs ?: nowMs,
                updatedAtEpochMs = nowMs
            )
            customSiteDao.upsertCustomSite(entity)
        }
    }

    suspend fun updateCustomSite(siteId: String, name: String, normalizedUrl: String): Result<Unit> = runCatching {
        ensureMigrated()
        val nowMs = clockMs()
        val cleanName = name.trim().take(40).ifBlank {
            normalizedUrl.removePrefix("https://").removePrefix("http://").substringBefore("/").take(30)
        }
        val cleanUrl = normalizedUrl.trim()
        val normalizedKey = cleanUrl.lowercase(Locale.US).trimEnd('/')
        db.withTransaction {
            val all = customSiteDao.getAllCustomSitesOnce()
            val target = all.firstOrNull { it.siteId == siteId }
                ?: throw IllegalStateException("Custom site not found")
            val collision = all.firstOrNull { it.normalizedUrl == normalizedKey && it.siteId != siteId }
            if (collision != null) {
                customSiteDao.deleteCustomSite(collision.siteId)
            }
            customSiteDao.upsertCustomSite(
                target.copy(
                    name = cleanName,
                    url = cleanUrl,
                    normalizedUrl = normalizedKey,
                    updatedAtEpochMs = nowMs
                )
            )
        }
    }

    suspend fun removeCustomSite(siteId: String): Result<Unit> = runCatching {
        ensureMigrated()
        customSiteDao.deleteCustomSite(siteId)
    }

    // --- MIRROR CONFIGS ---
    fun observeMirrorConfigs(): Flow<Map<IndexerSource, MirrorConfig>> = flow {
        ensureMigrated()
        emitAll(
            mirrorDao.observeMirrorConfigs().map { entities ->
                val bySource = entities.associate {
                    val cfg = it.toMirrorConfig()
                    cfg.provider to cfg
                }
                IndexerSource.entries.associateWith { source ->
                    bySource[source] ?: MirrorConfig(provider = source)
                }
            }
        )
    }

    suspend fun saveMirrorConfig(
        config: MirrorConfig,
        syncContext: ActiveSyncContext? = null
    ): Result<Unit> = runCatching {
        ensureMigrated()
        val nowMs = clockMs()
        val cleanMirrors = config.mirrors
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .take(12)
            .ifEmpty { config.provider.defaultMirrors }
        val active = config.activeMirrorUrl.trim().trimEnd('/')
            .takeIf { it in cleanMirrors } ?: cleanMirrors.first()

        db.withTransaction {
            val existing = mirrorDao.getByProvider(config.provider.name)
            val nextRev = (existing?.localRevision ?: 0L) + 1L
            val entity = MirrorConfigEntity(
                provider = config.provider.name,
                enabled = config.enabled,
                mirrorsJson = JSONArray(cleanMirrors).toString(),
                activeMirrorUrl = active,
                updatedAtEpochMs = nowMs,
                localRevision = nextRev
            )
            mirrorDao.upsertMirrorConfig(entity)

            if (syncContext != null && syncContext.syncMirrors) {
                val link = syncDao.getRecordLink(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.MIRROR,
                    cloudDocId = config.provider.name
                )
                enqueueOutboxTx(
                    accountUid = syncContext.accountUid,
                    entityType = SyncEntityType.MIRROR,
                    localEntityId = config.provider.name,
                    cloudDocId = config.provider.name,
                    opType = SyncOpType.UPSERT,
                    expectedServerRevision = link?.serverRevision ?: 0L,
                    targetLocalRevision = nextRev,
                    isExplicitResave = true,
                    nowMs = nowMs
                )
            }
        }
    }

    suspend fun resetAllMirrorsToDefault(syncContext: ActiveSyncContext? = null): Result<Unit> = runCatching {
        IndexerSource.entries.forEach { src ->
            saveMirrorConfig(MirrorConfig(provider = src), syncContext).getOrThrow()
        }
    }

    // --- EXPORT SNAPSHOT ---
    suspend fun buildExportInput(accountUid: String?): VaultExportInput {
        ensureMigrated()
        val scope = accountUid?.takeIf { it.isNotBlank() }?.let { ScopeConstants.accountScope(it) }
        val bookmarks = bookmarkDao.getActiveBookmarksWithCollectionsOnce(scope)
        val activeBookmarkIds = bookmarks.map { it.bookmark.localId }.toSet()
        val collections = collectionDao.getAllCollectionsOnce().map { col ->
            val crossCount = collectionDao.getAllCrossRefsOnce()
                .count { it.collectionId == col.collectionId && it.bookmarkLocalId in activeBookmarkIds }
            CollectionWithCount(
                collectionId = col.collectionId,
                name = col.name,
                normalizedName = col.normalizedName,
                createdAtEpochMs = col.createdAtEpochMs,
                updatedAtEpochMs = col.updatedAtEpochMs,
                bookmarkCount = crossCount
            )
        }
        val history = historyDao.getActiveHistoryOnce(scope)
        val sites = customSiteDao.getAllCustomSitesOnce()
        val mirrorsMap = mirrorDao.getAllMirrorsOnce().associate {
            val cfg = it.toMirrorConfig()
            cfg.provider to cfg
        }.let { saved ->
            IndexerSource.entries.associateWith { saved[it] ?: MirrorConfig(provider = it) }
        }
        return VaultExportInput(
            bookmarks = bookmarks,
            collections = collections,
            history = history,
            customSites = sites,
            mirrors = mirrorsMap
        )
    }

    // --- SYNC OUTBOX ENQUEUEING ---
    suspend fun enqueueOutboxTx(
        accountUid: String,
        entityType: String,
        localEntityId: String,
        cloudDocId: String,
        opType: String,
        expectedServerRevision: Long,
        targetLocalRevision: Long,
        isExplicitResave: Boolean,
        nowMs: Long = clockMs()
    ) {
        val existingPending = syncDao.findPendingOp(
            accountUid = accountUid,
            entityType = entityType,
            localEntityId = localEntityId,
            cloudDocId = cloudDocId
        )
        if (existingPending != null) {
            syncDao.upsertOutbox(
                existingPending.copy(
                    opId = UUID.randomUUID().toString(),
                    opType = opType,
                    expectedServerRevision = maxOf(existingPending.expectedServerRevision, expectedServerRevision),
                    targetLocalRevision = targetLocalRevision,
                    isExplicitResave = existingPending.isExplicitResave || isExplicitResave,
                    status = SyncOutboxStatus.PENDING,
                    lastErrorCategory = null,
                    updatedAtEpochMs = nowMs
                )
            )
        } else {
            syncDao.upsertOutbox(
                SyncOutboxEntity(
                    opId = UUID.randomUUID().toString(),
                    accountUid = accountUid,
                    entityType = entityType,
                    localEntityId = localEntityId,
                    cloudDocId = cloudDocId,
                    opType = opType,
                    expectedServerRevision = expectedServerRevision,
                    targetLocalRevision = targetLocalRevision,
                    isExplicitResave = isExplicitResave,
                    status = SyncOutboxStatus.PENDING,
                    attemptCount = 0,
                    lastErrorCategory = null,
                    createdAtEpochMs = nowMs,
                    updatedAtEpochMs = nowMs
                )
            )
        }
    }

    companion object {
        fun stableBookmarkCloudDocId(canonicalKey: String, torrent: TorrentResult): String {
            val hash = torrent.canonicalHashHex.ifBlank { torrent.infoHash }
            val seed = if (canonicalKey.startsWith("btih:") || canonicalKey.startsWith("btmh:")) {
                canonicalKey.substringAfter(':')
            } else {
                hash.ifBlank { "${torrent.source.name}_${torrent.listingId.ifBlank { torrent.id }}" }
            }
            return QualityAndHealthParser.sanitizeDocumentId("bm_$seed")
        }
    }
}
