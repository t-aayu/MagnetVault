package com.example.data.firebase

import android.content.Context
import com.example.data.auth.GoogleAuthManager
import com.example.data.local.BookmarkSaveOutcome
import com.example.data.local.LocalPrefsStore
import com.example.data.local.VaultExportInput
import com.example.data.local.VaultLocalRepository
import com.example.data.local.db.BookmarkEntity
import com.example.data.local.db.BookmarkWithCollections
import com.example.data.local.db.CollectionEntity
import com.example.data.local.db.CollectionWithCount
import com.example.data.local.db.MigrationStatusEntity
import com.example.data.sync.FirestoreSyncEngine
import com.example.data.sync.SyncRuntimeRegistry
import com.example.data.sync.SyncSessionStatus
import com.example.model.BookmarkRecord
import com.example.model.CustomSite
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.QualityAndHealthParser
import com.example.model.SearchHistoryRecord
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import com.google.firebase.Timestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Local-first Vault & Sync repository.
 * Room is always the single source of truth; Firestore sync is strictly optional,
 * opt-in per account, and coordinated via the durable outbox in [FirestoreSyncEngine].
 */
class CloudSyncRepository(
    private val appContext: Context,
    private val prefsStore: LocalPrefsStore,
    val isCloudMode: Boolean = false,
    private val accountUidOverride: String? = null,
    private val localRepoOverride: VaultLocalRepository? = null,
    private val syncEngineOverride: FirestoreSyncEngine? = null
) {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val runtimeContainer by lazy { SyncRuntimeRegistry.getContainer(appContext) }
    val localRepo: VaultLocalRepository get() = localRepoOverride ?: runtimeContainer.localRepo
    val syncEngine: FirestoreSyncEngine get() = syncEngineOverride ?: runtimeContainer.syncEngine

    private fun currentUid(): String? =
        accountUidOverride?.takeIf { it.isNotBlank() }
            ?: GoogleAuthManager.currentUserOrNull(appContext)?.uid

    val syncStatus: StateFlow<SyncSessionStatus> get() = syncEngine.syncStatus

    fun observeMigrationStatus(): Flow<MigrationStatusEntity?> = localRepo.observeMigrationStatus()

    suspend fun retryMigration(): MigrationStatusEntity = localRepo.retryMigration()

    // --- BOOKMARKS ---
    fun observeBookmarksWithCollections(): Flow<List<BookmarkWithCollections>> =
        localRepo.observeBookmarks(currentUid())

    fun observeBookmarks(): Flow<List<BookmarkRecord>> =
        localRepo.observeBookmarks(currentUid()).map { list ->
            list.map { it.bookmark.toBookmarkRecord() }
        }

    fun observeActiveBookmarkMatchKeys(): Flow<Set<String>> =
        localRepo.observeActiveMatchKeys(currentUid())

    fun observeSearchHistoryEnabled(): Flow<Boolean> = prefsStore.searchHistoryEnabledFlow

    suspend fun addBookmark(
        torrent: TorrentResult,
        replaceUnresolvedDocId: String? = null,
        existingLocalIdHint: String? = null
    ): Result<Unit> {
        return saveOrUpgradeBookmark(
            torrent = torrent,
            replaceUnresolvedDocId = replaceUnresolvedDocId,
            existingLocalIdHint = existingLocalIdHint
        ).map { Unit }
    }

    suspend fun saveOrUpgradeBookmark(
        torrent: TorrentResult,
        replaceUnresolvedDocId: String? = null,
        existingLocalIdHint: String? = null
    ): Result<BookmarkSaveOutcome> {
        val uid = currentUid()
        val resolvedHint = existingLocalIdHint ?: replaceUnresolvedDocId?.let { legacyHint ->
            localRepo.findLocalIdForCandidateKeys(listOf(legacyHint, "legacy:$legacyHint"), uid)
        }
        val syncCtx = syncEngine.getActiveSyncContext(uid)
        val outcome = localRepo.saveOrUpdateBookmark(
            torrent = torrent,
            existingLocalIdHint = resolvedHint,
            syncContext = syncCtx,
            forceResave = true
        )
        if (outcome.isSuccess && syncCtx != null) {
            triggerDrainOrSchedule(syncCtx.accountUid)
        }
        return outcome
    }

    suspend fun removeBookmarkByLocalIdOrDocId(idOrKey: String): Result<String?> {
        val uid = currentUid()
        val resolvedLocalId = localRepo.findLocalIdForCandidateKeys(listOf(idOrKey, "legacy:$idOrKey"), uid)
            ?: idOrKey
        val syncCtx = syncEngine.getActiveSyncContext(uid)
        val res = localRepo.removeBookmark(resolvedLocalId, syncCtx)
        if (res.isSuccess && syncCtx != null) {
            triggerDrainOrSchedule(syncCtx.accountUid)
        }
        return res.map { if (it) resolvedLocalId else null }
    }

    suspend fun removeBookmark(bookmarkId: String): Result<Unit> =
        removeBookmarkByLocalIdOrDocId(bookmarkId).map { Unit }

    suspend fun undoRemoveBookmark(localId: String): Result<Boolean> {
        val uid = currentUid()
        val syncCtx = syncEngine.getActiveSyncContext(uid)
        val res = localRepo.undoRemoveBookmark(localId, syncCtx)
        if (res.isSuccess && syncCtx != null) {
            triggerDrainOrSchedule(syncCtx.accountUid)
        }
        return res
    }

    suspend fun findLocalBookmarkIdForKeys(candidateKeys: List<String>): String? =
        localRepo.findLocalIdForCandidateKeys(candidateKeys, currentUid())

    fun unresolvedBookmarkDocId(source: IndexerSource, listingId: String): String =
        QualityAndHealthParser.sanitizeDocumentId("bm_${source.name}_$listingId")

    fun bookmarkDocId(torrent: TorrentResult): String {
        val canonicalHash = torrent.canonicalHashHex.ifBlank { torrent.infoHash }
        val seed = canonicalHash.ifBlank { "${torrent.source.name}_${torrent.listingId.ifBlank { torrent.id }}" }
        return QualityAndHealthParser.sanitizeDocumentId("bm_$seed")
    }

    // --- COLLECTIONS (LOCAL ONLY) ---
    fun observeCollections(): Flow<List<CollectionWithCount>> =
        localRepo.observeCollections(currentUid())

    suspend fun createCollection(name: String): Result<CollectionEntity> =
        localRepo.createCollection(name)

    suspend fun renameCollection(collectionId: String, newName: String): Result<Unit> =
        localRepo.renameCollection(collectionId, newName)

    suspend fun deleteCollection(collectionId: String): Result<Unit> =
        localRepo.deleteCollection(collectionId)

    suspend fun setBookmarkCollections(bookmarkLocalId: String, collectionIds: Set<String>): Result<Unit> =
        localRepo.setBookmarkCollections(bookmarkLocalId, collectionIds)

    // --- SEARCH HISTORY ---
    fun observeSearchHistory(): Flow<List<SearchHistoryRecord>> =
        localRepo.observeHistory(currentUid()).map { entries ->
            entries.map { entry ->
                SearchHistoryRecord(
                    id = entry.entryId,
                    userId = entry.originScope,
                    query = entry.query,
                    category = entry.category,
                    createdAt = Timestamp(entry.createdAtEpochMs / 1000L, 0),
                    updatedAt = Timestamp(entry.updatedAtEpochMs / 1000L, 0),
                    revision = entry.localRevision,
                    deleted = entry.isDeleted
                )
            }
        }

    suspend fun recordSearchQuery(query: String, category: TorrentCategory): Result<Unit> {
        val uid = currentUid()
        val syncCtx = syncEngine.getActiveSyncContext(uid)
        val res = localRepo.recordSearchQuery(query, category, syncCtx)
        if (res.isSuccess && syncCtx != null && syncCtx.syncHistory) {
            triggerDrainOrSchedule(syncCtx.accountUid)
        }
        return res
    }

    suspend fun deleteSearchHistoryEntry(entryId: String): Result<Unit> {
        val uid = currentUid()
        val syncCtx = syncEngine.getActiveSyncContext(uid)
        val res = localRepo.deleteHistoryEntry(entryId, syncCtx)
        if (res.isSuccess && syncCtx != null && syncCtx.syncHistory) {
            triggerDrainOrSchedule(syncCtx.accountUid)
        }
        return res
    }

    suspend fun clearAllSearchHistory(): Result<Unit> {
        val uid = currentUid()
        val syncCtx = syncEngine.getActiveSyncContext(uid)
        val res = localRepo.clearAllHistory(syncCtx)
        if (res.isSuccess && syncCtx != null && syncCtx.syncHistory) {
            triggerDrainOrSchedule(syncCtx.accountUid)
        }
        return res
    }

    // --- CUSTOM SITES (LOCAL ONLY) ---
    fun observeCustomSites(): Flow<List<CustomSite>> = localRepo.observeCustomSites()

    suspend fun addCustomSite(name: String, normalizedUrl: String): Result<Unit> =
        localRepo.addCustomSite(name, normalizedUrl)

    suspend fun updateCustomSite(siteId: String, name: String, normalizedUrl: String): Result<Unit> =
        localRepo.updateCustomSite(siteId, name, normalizedUrl)

    suspend fun removeCustomSite(siteId: String): Result<Unit> =
        localRepo.removeCustomSite(siteId)

    // --- MIRROR CONFIGS ---
    fun observeMirrorConfigs(): Flow<Map<IndexerSource, MirrorConfig>> =
        localRepo.observeMirrorConfigs()

    suspend fun saveMirrorConfig(config: MirrorConfig): Result<Unit> {
        val uid = currentUid()
        val syncCtx = syncEngine.getActiveSyncContext(uid)
        val res = localRepo.saveMirrorConfig(config, syncCtx)
        if (res.isSuccess && syncCtx != null && syncCtx.syncMirrors) {
            triggerDrainOrSchedule(syncCtx.accountUid)
        }
        return res
    }

    suspend fun resetAllMirrorsToDefault(): Result<Unit> {
        val uid = currentUid()
        val syncCtx = syncEngine.getActiveSyncContext(uid)
        val res = localRepo.resetAllMirrorsToDefault(syncCtx)
        if (res.isSuccess && syncCtx != null && syncCtx.syncMirrors) {
            triggerDrainOrSchedule(syncCtx.accountUid)
        }
        return res
    }

    // --- EXPORT ---
    suspend fun buildExportInput(): VaultExportInput =
        localRepo.buildExportInput(currentUid())

    private fun triggerDrainOrSchedule(uid: String) {
        syncScope.launch {
            if (SyncRuntimeRegistry.isNetworkConnected(appContext)) {
                val res = syncEngine.drainOutbox(uid)
                if (res.hadTransientFailure) {
                    syncEngine.scheduleBackgroundSyncIfPending(uid)
                }
            } else {
                syncEngine.scheduleBackgroundSyncIfPending(uid)
            }
        }
    }

    private fun BookmarkEntity.toBookmarkRecord(): BookmarkRecord = BookmarkRecord(
        id = localId,
        userId = originScope,
        title = title,
        source = source,
        category = category,
        sizeLabel = sizeLabel,
        sizeBytes = sizeBytes,
        seeders = seeders.toLong(),
        leechers = leechers.toLong(),
        uploadDateLabel = uploadDateLabel,
        magnetUri = magnetUri,
        infoHash = canonicalHashHex.ifBlank { infoHash },
        detailPageUrl = detailPageUrl,
        qualityTags = qualityTagNames(),
        createdAt = Timestamp(createdAtEpochMs / 1000L, 0),
        updatedAt = Timestamp(updatedAtEpochMs / 1000L, 0),
        revision = localRevision,
        deleted = isDeleted,
        canonicalIdentity = canonicalKey,
        legacyDocId = legacyDocId
    )
}
