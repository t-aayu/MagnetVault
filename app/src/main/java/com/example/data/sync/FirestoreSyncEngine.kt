package com.example.data.sync

import androidx.room.withTransaction
import com.example.data.firebase.OperationType
import com.example.data.firebase.classifyFirestoreFailureCategory
import com.example.data.firebase.handleFirestoreError
import com.example.data.local.ActiveSyncContext
import com.example.data.local.LocalPrefsStore
import com.example.data.local.SyncAccountPrefs
import com.example.data.local.VaultLocalRepository
import com.example.data.local.db.BookmarkEntity
import com.example.data.local.db.CloudRecordLinkEntity
import com.example.data.local.db.IdentityAliasEntity
import com.example.data.local.db.MagnetVaultDatabase
import com.example.data.local.db.MirrorConfigEntity
import com.example.data.local.db.ScopeConstants
import com.example.data.local.db.SearchHistoryEntity
import com.example.data.local.db.SyncEntityType
import com.example.data.local.db.SyncOpType
import com.example.data.local.db.SyncOutboxEntity
import com.example.data.local.db.SyncOutboxStatus
import com.example.domain.magnet.CanonicalIdentityKey
import com.example.domain.magnet.MagnetParser
import com.example.model.BookmarkRecord
import com.example.model.IndexerSource
import com.example.model.MirrorConfigRecord
import com.example.model.QualityAndHealthParser
import com.example.model.SearchHistoryRecord
import com.example.model.TorrentCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.Locale
import java.util.UUID

sealed class SyncSessionStatus {
    data object LocalOnly : SyncSessionStatus()
    data class Synced(val lastSyncedEpochMs: Long?) : SyncSessionStatus()
    data class Pending(val pendingCount: Int, val isSyncing: Boolean = false) : SyncSessionStatus()
    data class Offline(val pendingCount: Int) : SyncSessionStatus()
    data class ActionNeeded(val errorCategory: String, val pendingCount: Int) : SyncSessionStatus()
}

data class EnrollmentMergePreview(
    val localBookmarkCount: Int,
    val cloudBookmarkCount: Int,
    val overlapCount: Int,
    val newToUploadCount: Int,
    val newToDownloadCount: Int
)

interface SyncWorkScheduler {
    fun enqueueForAccount(uid: String)
    fun cancelForAccount(uid: String)
}

object NoOpSyncWorkScheduler : SyncWorkScheduler {
    override fun enqueueForAccount(uid: String) = Unit
    override fun cancelForAccount(uid: String) = Unit
}

data class DrainResult(
    val processedCount: Int,
    val remainingPendingCount: Int,
    val hadTransientFailure: Boolean,
    val actionNeededCategory: String?
)

class FirestoreSyncEngine(
    private val db: MagnetVaultDatabase,
    private val localRepo: VaultLocalRepository,
    private val prefsStore: LocalPrefsStore,
    private val gateway: CloudSyncGateway?,
    private val currentAuthUidProvider: () -> String?,
    private val isOnlineProvider: () -> Boolean = { true },
    private val workScheduler: SyncWorkScheduler = NoOpSyncWorkScheduler,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val beforeOutboxAckHook: ((SyncOutboxEntity) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainMutex = Mutex()

    private val _syncStatus = MutableStateFlow<SyncSessionStatus>(SyncSessionStatus.LocalOnly)
    val syncStatus: StateFlow<SyncSessionStatus> = _syncStatus.asStateFlow()

    private val _isOnline = MutableStateFlow(isOnlineProvider())
    private val _isDraining = MutableStateFlow(false)
    private val _lastSyncedEpochMs = MutableStateFlow<Long?>(null)

    private var activeObservedUid: String? = null
    private var sessionJob: Job? = null
    private var listenerJobs: MutableList<Job> = mutableListOf()

    fun refreshConnectivity() {
        val nowOnline = isOnlineProvider()
        _isOnline.value = nowOnline
        val uid = activeObservedUid
        if (nowOnline && uid != null) {
            scope.launch {
                val prefs = prefsStore.getSyncPrefsForAccount(uid)
                if (prefs.enrolled && currentAuthUidProvider() == uid) {
                    drainOutbox(uid)
                }
            }
        }
    }

    fun startOrUpdateSession(uid: String?) {
        val normalizedUid = uid?.takeIf { it.isNotBlank() }
        val previousUid = activeObservedUid
        if (previousUid != null && previousUid != normalizedUid) {
            stopListenersOnly()
            workScheduler.cancelForAccount(previousUid)
        }
        if (normalizedUid == null) {
            stopListenersOnly()
            sessionJob?.cancel()
            sessionJob = null
            activeObservedUid = null
            _syncStatus.value = SyncSessionStatus.LocalOnly
            return
        }
        if (activeObservedUid == normalizedUid && sessionJob?.isActive == true) {
            return
        }

        activeObservedUid = normalizedUid
        sessionJob?.cancel()
        sessionJob = scope.launch {
            combine(
                prefsStore.observeSyncPrefsForAccount(normalizedUid),
                db.syncDao().observeOutboxForAccount(normalizedUid),
                _isOnline,
                _isDraining,
                _lastSyncedEpochMs
            ) { prefs, outbox, online, draining, lastSynced ->
                Triple(prefs, outbox, Triple(online, draining, lastSynced))
            }.collectLatest { (prefs, outbox, flags) ->
                val (online, draining, lastSynced) = flags
                if (!prefs.enrolled || currentAuthUidProvider() != normalizedUid || gateway == null) {
                    stopListenersOnly()
                    _syncStatus.value = SyncSessionStatus.LocalOnly
                    return@collectLatest
                }

                ensureListenersAttached(normalizedUid, prefs)

                val actionNeededOps = outbox.filter { it.status == SyncOutboxStatus.ACTION_NEEDED }
                val pendingOps = outbox.filter { it.status != SyncOutboxStatus.ACTION_NEEDED }

                _syncStatus.value = when {
                    actionNeededOps.isNotEmpty() -> SyncSessionStatus.ActionNeeded(
                        errorCategory = actionNeededOps.first().lastErrorCategory ?: "ACTION_NEEDED",
                        pendingCount = actionNeededOps.size + pendingOps.size
                    )
                    !online -> SyncSessionStatus.Offline(pendingCount = pendingOps.size)
                    pendingOps.isNotEmpty() -> SyncSessionStatus.Pending(
                        pendingCount = pendingOps.size,
                        isSyncing = draining
                    )
                    else -> SyncSessionStatus.Synced(lastSyncedEpochMs = lastSynced)
                }
            }
        }
    }

    fun stopSessionForSignOut(signingOutUid: String?) {
        stopListenersOnly()
        sessionJob?.cancel()
        sessionJob = null
        activeObservedUid = null
        if (!signingOutUid.isNullOrBlank()) {
            workScheduler.cancelForAccount(signingOutUid)
        }
        scope.launch {
            prefsStore.setActiveSyncUid(null)
        }
        _syncStatus.value = SyncSessionStatus.LocalOnly
    }

    private fun stopListenersOnly() {
        listenerJobs.forEach { it.cancel() }
        listenerJobs.clear()
    }

    private var listenerSignature: String? = null

    private fun ensureListenersAttached(uid: String, prefs: SyncAccountPrefs) {
        if (gateway == null) return
        val signature = "$uid:${prefs.enrolled}:${prefs.syncHistory}:${prefs.syncMirrors}"
        if (listenerSignature == signature && listenerJobs.any { it.isActive }) {
            return
        }
        stopListenersOnly()
        listenerSignature = signature

        // Bookmarks listener (always active when enrolled)
        listenerJobs += scope.launch {
            gateway.observeBookmarks(uid)
                .catch { error ->
                    if (error is CancellationException) throw error
                    handleFirestoreError(error, OperationType.LIST, "bookmarks")
                }
                .collect { records ->
                    if (isSessionAuthorized(uid)) {
                        applyPulledBookmarks(uid, records)
                        _lastSyncedEpochMs.value = clockMs()
                    }
                }
        }

        // History listener (strictly opt-in)
        if (prefs.syncHistory) {
            listenerJobs += scope.launch {
                gateway.observeSearchHistory(uid)
                    .catch { error ->
                        if (error is CancellationException) throw error
                        handleFirestoreError(error, OperationType.LIST, "search_history")
                    }
                    .collect { records ->
                        if (isSessionAuthorized(uid)) {
                            applyPulledHistory(uid, records)
                        }
                    }
            }
        }

        // Mirrors listener (strictly opt-in)
        if (prefs.syncMirrors) {
            listenerJobs += scope.launch {
                gateway.observeMirrors(uid)
                    .catch { error ->
                        if (error is CancellationException) throw error
                        handleFirestoreError(error, OperationType.LIST, "mirror_configs")
                    }
                    .collect { records ->
                        if (isSessionAuthorized(uid)) {
                            applyPulledMirrors(uid, records)
                        }
                    }
            }
        }
    }

    suspend fun fetchEnrollmentPreview(accountUid: String): Result<EnrollmentMergePreview> = runCatching {
        localRepo.ensureMigrated()
        val gw = gateway ?: throw IllegalStateException("Firebase sync is not configured in this build")
        val cloudRecords = gw.fetchBookmarks(accountUid).filterNot { it.isTombstone }
        val localDeviceBookmarks = db.bookmarkDao().getActiveDeviceBookmarks()

        val cloudIdentitySet = cloudRecords.flatMapTo(LinkedHashSet()) { record ->
            buildList {
                record.canonicalIdentity?.takeIf { it.isNotBlank() }?.let(::add)
                val verified = MagnetParser.extractVerifiedIdentities(record.magnetUri).firstOrNull()
                    ?: MagnetParser.normalizeHash(record.infoHash)
                if (verified != null) add(verified.storageKey)
                add("legacy:${record.id}")
                record.legacyDocId?.takeIf { it.isNotBlank() }?.let { add("legacy:$it") }
            }
        }

        var overlap = 0
        for (local in localDeviceBookmarks) {
            val matched = local.canonicalKey in cloudIdentitySet ||
                (local.legacyDocId.isNotBlank() && "legacy:${local.legacyDocId}" in cloudIdentitySet)
            if (matched) overlap++
        }
        val newToUpload = (localDeviceBookmarks.size - overlap).coerceAtLeast(0)
        val newToDownload = (cloudRecords.size - overlap).coerceAtLeast(0)
        EnrollmentMergePreview(
            localBookmarkCount = localDeviceBookmarks.size,
            cloudBookmarkCount = cloudRecords.size,
            overlapCount = overlap,
            newToUploadCount = newToUpload,
            newToDownloadCount = newToDownload
        )
    }

    suspend fun enrollAccountAndSync(
        accountUid: String,
        syncHistory: Boolean,
        syncMirrors: Boolean
    ): Result<Unit> = runCatching {
        localRepo.ensureMigrated()
        val gw = gateway ?: throw IllegalStateException("Firebase sync is not configured in this build")
        require(currentAuthUidProvider() == accountUid) { "Account mismatch during sync enrollment" }

        prefsStore.setSyncEnrollment(
            uid = accountUid,
            enrolled = true,
            syncHistory = syncHistory,
            syncMirrors = syncMirrors
        )

        // 1. Pull cloud state into Room
        val remoteBookmarks = gw.fetchBookmarks(accountUid)
        if (!isSessionAuthorized(accountUid)) return@runCatching
        applyPulledBookmarks(accountUid, remoteBookmarks)

        if (syncHistory) {
            val remoteHistory = gw.fetchSearchHistory(accountUid)
            if (!isSessionAuthorized(accountUid)) return@runCatching
            applyPulledHistory(accountUid, remoteHistory)
        }
        if (syncMirrors) {
            val remoteMirrors = gw.fetchMirrors(accountUid)
            if (!isSessionAuthorized(accountUid)) return@runCatching
            applyPulledMirrors(accountUid, remoteMirrors)
        }

        // 2. Enqueue local DEVICE items that do not yet have a synced link for this account
        val nowMs = clockMs()
        db.withTransaction {
            val deviceBookmarks = db.bookmarkDao().getActiveDeviceBookmarks()
            for (b in deviceBookmarks) {
                val link = db.syncDao().getRecordLinkByLocalId(accountUid, SyncEntityType.BOOKMARK, b.localId)
                if (link == null || (!link.serverDeleted && b.localRevision > link.lastSyncedLocalRevision)) {
                    val cloudDocId = link?.cloudDocId ?: b.legacyDocId.ifBlank {
                        QualityAndHealthParser.sanitizeDocumentId("bm_${b.canonicalHashHex.ifBlank { b.localId }}")
                    }
                    localRepo.enqueueOutboxTx(
                        accountUid = accountUid,
                        entityType = SyncEntityType.BOOKMARK,
                        localEntityId = b.localId,
                        cloudDocId = cloudDocId,
                        opType = SyncOpType.UPSERT,
                        expectedServerRevision = link?.serverRevision ?: 0L,
                        targetLocalRevision = b.localRevision,
                        isExplicitResave = true,
                        nowMs = nowMs
                    )
                }
            }

            if (syncHistory) {
                val deviceHistory = db.searchHistoryDao().getActiveDeviceHistory()
                for (h in deviceHistory) {
                    val link = db.syncDao().getRecordLinkByLocalId(accountUid, SyncEntityType.HISTORY, h.entryId)
                    if (link == null || (!link.serverDeleted && h.localRevision > link.lastSyncedLocalRevision)) {
                        localRepo.enqueueOutboxTx(
                            accountUid = accountUid,
                            entityType = SyncEntityType.HISTORY,
                            localEntityId = h.entryId,
                            cloudDocId = link?.cloudDocId ?: h.cloudDocId,
                            opType = SyncOpType.UPSERT,
                            expectedServerRevision = link?.serverRevision ?: 0L,
                            targetLocalRevision = h.localRevision,
                            isExplicitResave = true,
                            nowMs = nowMs
                        )
                    }
                }
            }

            if (syncMirrors) {
                val deviceMirrors = db.mirrorConfigDao().getAllMirrorsOnce()
                for (m in deviceMirrors) {
                    val link = db.syncDao().getRecordLink(accountUid, SyncEntityType.MIRROR, m.provider)
                    if (link == null || m.localRevision > link.lastSyncedLocalRevision) {
                        localRepo.enqueueOutboxTx(
                            accountUid = accountUid,
                            entityType = SyncEntityType.MIRROR,
                            localEntityId = m.provider,
                            cloudDocId = m.provider,
                            opType = SyncOpType.UPSERT,
                            expectedServerRevision = link?.serverRevision ?: 0L,
                            targetLocalRevision = m.localRevision,
                            isExplicitResave = true,
                            nowMs = nowMs
                        )
                    }
                }
            }
        }

        // 3. Start listeners and drain outbox immediately (or schedule WorkManager if offline)
        startOrUpdateSession(accountUid)
        if (isOnlineProvider()) {
            drainOutbox(accountUid)
        } else {
            workScheduler.enqueueForAccount(accountUid)
        }
    }

    suspend fun disableSyncForAccount(accountUid: String) {
        stopListenersOnly()
        workScheduler.cancelForAccount(accountUid)
        prefsStore.setSyncEnrollment(accountUid, enrolled = false, syncHistory = false, syncMirrors = false)
        _syncStatus.value = SyncSessionStatus.LocalOnly
    }

    suspend fun setHistorySyncOptIn(accountUid: String, enabled: Boolean) {
        prefsStore.setHistorySyncOptIn(accountUid, enabled)
        val gw = gateway
        if (enabled && isSessionAuthorized(accountUid) && gw != null) {
            runCatching {
                val remoteHistory = gw.fetchSearchHistory(accountUid)
                if (isSessionAuthorized(accountUid)) {
                    applyPulledHistory(accountUid, remoteHistory)
                }
            }
        }
    }

    suspend fun setMirrorsSyncOptIn(accountUid: String, enabled: Boolean) {
        prefsStore.setMirrorsSyncOptIn(accountUid, enabled)
        val gw = gateway
        if (enabled && isSessionAuthorized(accountUid) && gw != null) {
            runCatching {
                val remoteMirrors = gw.fetchMirrors(accountUid)
                if (isSessionAuthorized(accountUid)) {
                    applyPulledMirrors(accountUid, remoteMirrors)
                }
            }
        }
    }

    suspend fun syncNowOrRetry(accountUid: String): DrainResult {
        db.syncDao().retryActionNeededForAccount(accountUid, clockMs())
        return drainOutbox(accountUid)
    }

    fun scheduleBackgroundSyncIfPending(accountUid: String) {
        workScheduler.enqueueForAccount(accountUid)
    }

    suspend fun getActiveSyncContext(accountUid: String?): ActiveSyncContext? {
        val uid = accountUid?.takeIf { it.isNotBlank() } ?: return null
        if (currentAuthUidProvider() != uid) return null
        val prefs = prefsStore.getSyncPrefsForAccount(uid)
        if (!prefs.enrolled) return null
        return ActiveSyncContext(
            accountUid = uid,
            syncBookmarks = true,
            syncHistory = prefs.syncHistory,
            syncMirrors = prefs.syncMirrors
        )
    }

    suspend fun drainOutbox(expectedUid: String): DrainResult = drainMutex.withLock {
        val gw = gateway ?: return@withLock DrainResult(0, 0, false, null)
        if (!isSessionAuthorized(expectedUid)) {
            return@withLock DrainResult(0, 0, false, null)
        }
        val prefs = prefsStore.getSyncPrefsForAccount(expectedUid)
        val ops = db.syncDao().getRunnableOutboxForAccount(expectedUid)
        if (ops.isEmpty()) {
            _lastSyncedEpochMs.value = clockMs()
            return@withLock DrainResult(0, 0, false, null)
        }

        _isDraining.value = true
        var processed = 0
        var hadTransient = false
        var actionNeededCat: String? = null

        try {
            for (op in ops) {
                // Re-verify UID authorization before every single outbox op
                if (!isSessionAuthorized(expectedUid)) {
                    break
                }
                if (op.entityType == SyncEntityType.HISTORY && !prefs.syncHistory) {
                    db.syncDao().deleteOutboxOp(op.opId)
                    continue
                }
                if (op.entityType == SyncEntityType.MIRROR && !prefs.syncMirrors) {
                    db.syncDao().deleteOutboxOp(op.opId)
                    continue
                }

                try {
                    when (op.entityType) {
                        SyncEntityType.BOOKMARK -> processBookmarkOutboxOp(gw, expectedUid, op)
                        SyncEntityType.HISTORY -> processHistoryOutboxOp(gw, expectedUid, op)
                        SyncEntityType.MIRROR -> processMirrorOutboxOp(gw, expectedUid, op)
                    }
                    processed++
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    val cat = classifyFirestoreFailureCategory(t)
                    handleFirestoreError(t, OperationType.WRITE, op.entityType)
                    val isPermanentAuthOrSchema = cat == "PERMISSION_DENIED" ||
                        cat == "UNAUTHENTICATED" ||
                        cat == "SCHEMA_REJECTED"
                    if (isPermanentAuthOrSchema) {
                        actionNeededCat = cat
                        db.syncDao().upsertOutbox(
                            op.copy(
                                status = SyncOutboxStatus.ACTION_NEEDED,
                                attemptCount = op.attemptCount + 1,
                                lastErrorCategory = cat,
                                updatedAtEpochMs = clockMs()
                            )
                        )
                    } else {
                        hadTransient = true
                        db.syncDao().upsertOutbox(
                            op.copy(
                                status = SyncOutboxStatus.PENDING,
                                attemptCount = op.attemptCount + 1,
                                lastErrorCategory = cat,
                                updatedAtEpochMs = clockMs()
                            )
                        )
                    }
                }
            }
        } finally {
            _isDraining.value = false
        }

        val remaining = db.syncDao().getRunnableOutboxForAccount(expectedUid).size
        if (remaining == 0 && !hadTransient && actionNeededCat == null) {
            _lastSyncedEpochMs.value = clockMs()
        } else if (remaining > 0 && hadTransient) {
            workScheduler.enqueueForAccount(expectedUid)
        }

        DrainResult(
            processedCount = processed,
            remainingPendingCount = remaining,
            hadTransientFailure = hadTransient,
            actionNeededCategory = actionNeededCat
        )
    }

    private suspend fun processBookmarkOutboxOp(
        gw: CloudSyncGateway,
        uid: String,
        op: SyncOutboxEntity
    ) {
        val local = db.bookmarkDao().getByLocalId(op.localEntityId)
        if (local == null) {
            db.syncDao().deleteOutboxOp(op.opId)
            return
        }
        val outcome = gw.runBookmarkMutation(
            uid = uid,
            cloudDocId = op.cloudDocId,
            opId = op.opId,
            isDelete = (op.opType == SyncOpType.DELETE),
            expectedServerRevision = op.expectedServerRevision,
            isExplicitResave = op.isExplicitResave,
            localEntity = local
        )

        // Hook for simulating post-server-ack crash before local ack commits
        beforeOutboxAckHook?.invoke(op)

        db.withTransaction {
            when (outcome) {
                is CasMutationOutcome.Committed -> {
                    db.syncDao().upsertRecordLink(
                        CloudRecordLinkEntity(
                            accountUid = uid,
                            entityType = SyncEntityType.BOOKMARK,
                            cloudDocId = op.cloudDocId,
                            localEntityId = local.localId,
                            serverRevision = outcome.serverRevision,
                            lastSyncedLocalRevision = op.targetLocalRevision,
                            serverDeleted = outcome.serverDeleted,
                            serverUpdatedAtMs = outcome.serverUpdatedAtMs
                        )
                    )
                    db.syncDao().deleteOutboxOp(op.opId)
                }
                is CasMutationOutcome.AlreadyCommitted -> {
                    val deletedOnServer = outcome.serverDeleted
                    val currentLocal = db.bookmarkDao().getByLocalId(local.localId)
                    if (currentLocal != null && deletedOnServer && !currentLocal.isDeleted && op.opType == SyncOpType.DELETE) {
                        db.bookmarkDao().upsertBookmark(
                            currentLocal.copy(
                                isDeleted = true,
                                deletedAtEpochMs = outcome.serverUpdatedAtMs.takeIf { it > 0L } ?: clockMs(),
                                updatedAtEpochMs = clockMs()
                            )
                        )
                    }
                    db.syncDao().upsertRecordLink(
                        CloudRecordLinkEntity(
                            accountUid = uid,
                            entityType = SyncEntityType.BOOKMARK,
                            cloudDocId = op.cloudDocId,
                            localEntityId = local.localId,
                            serverRevision = outcome.serverRevision,
                            lastSyncedLocalRevision = op.targetLocalRevision,
                            serverDeleted = deletedOnServer,
                            serverUpdatedAtMs = outcome.serverUpdatedAtMs
                        )
                    )
                    db.syncDao().deleteOutboxOp(op.opId)
                }
                is CasMutationOutcome.ServerTombstoneWon -> {
                    // Delete wins over stale offline update unless explicit re-save
                    val currentLocal = db.bookmarkDao().getByLocalId(local.localId)
                    if (currentLocal != null) {
                        db.bookmarkDao().upsertBookmark(
                            currentLocal.copy(
                                isDeleted = true,
                                deletedAtEpochMs = outcome.serverUpdatedAtMs.takeIf { it > 0L } ?: clockMs(),
                                updatedAtEpochMs = clockMs(),
                                localRevision = currentLocal.localRevision + 1L
                            )
                        )
                    }
                    db.syncDao().upsertRecordLink(
                        CloudRecordLinkEntity(
                            accountUid = uid,
                            entityType = SyncEntityType.BOOKMARK,
                            cloudDocId = op.cloudDocId,
                            localEntityId = local.localId,
                            serverRevision = outcome.serverRevision,
                            lastSyncedLocalRevision = (currentLocal?.localRevision ?: op.targetLocalRevision) + 1L,
                            serverDeleted = true,
                            serverUpdatedAtMs = outcome.serverUpdatedAtMs
                        )
                    )
                    db.syncDao().deleteOutboxOp(op.opId)
                }
            }
        }
    }

    private suspend fun processHistoryOutboxOp(
        gw: CloudSyncGateway,
        uid: String,
        op: SyncOutboxEntity
    ) {
        val local = db.searchHistoryDao().getByEntryId(op.localEntityId)
        if (local == null) {
            db.syncDao().deleteOutboxOp(op.opId)
            return
        }
        val outcome = gw.runHistoryMutation(
            uid = uid,
            cloudDocId = op.cloudDocId,
            opId = op.opId,
            isDelete = (op.opType == SyncOpType.DELETE),
            expectedServerRevision = op.expectedServerRevision,
            isExplicitResave = op.isExplicitResave,
            localEntity = local
        )
        beforeOutboxAckHook?.invoke(op)
        db.withTransaction {
            when (outcome) {
                is CasMutationOutcome.Committed -> {
                    db.syncDao().upsertRecordLink(
                        CloudRecordLinkEntity(
                            accountUid = uid,
                            entityType = SyncEntityType.HISTORY,
                            cloudDocId = op.cloudDocId,
                            localEntityId = local.entryId,
                            serverRevision = outcome.serverRevision,
                            lastSyncedLocalRevision = op.targetLocalRevision,
                            serverDeleted = outcome.serverDeleted,
                            serverUpdatedAtMs = outcome.serverUpdatedAtMs
                        )
                    )
                    db.syncDao().deleteOutboxOp(op.opId)
                }
                is CasMutationOutcome.AlreadyCommitted -> {
                    db.syncDao().upsertRecordLink(
                        CloudRecordLinkEntity(
                            accountUid = uid,
                            entityType = SyncEntityType.HISTORY,
                            cloudDocId = op.cloudDocId,
                            localEntityId = local.entryId,
                            serverRevision = outcome.serverRevision,
                            lastSyncedLocalRevision = op.targetLocalRevision,
                            serverDeleted = outcome.serverDeleted,
                            serverUpdatedAtMs = outcome.serverUpdatedAtMs
                        )
                    )
                    db.syncDao().deleteOutboxOp(op.opId)
                }
                is CasMutationOutcome.ServerTombstoneWon -> {
                    val cur = db.searchHistoryDao().getByEntryId(local.entryId)
                    if (cur != null) {
                        db.searchHistoryDao().upsertHistory(
                            cur.copy(
                                isDeleted = true,
                                deletedAtEpochMs = outcome.serverUpdatedAtMs.takeIf { it > 0L } ?: clockMs(),
                                updatedAtEpochMs = clockMs()
                            )
                        )
                    }
                    db.syncDao().upsertRecordLink(
                        CloudRecordLinkEntity(
                            accountUid = uid,
                            entityType = SyncEntityType.HISTORY,
                            cloudDocId = op.cloudDocId,
                            localEntityId = local.entryId,
                            serverRevision = outcome.serverRevision,
                            lastSyncedLocalRevision = op.targetLocalRevision,
                            serverDeleted = true,
                            serverUpdatedAtMs = outcome.serverUpdatedAtMs
                        )
                    )
                    db.syncDao().deleteOutboxOp(op.opId)
                }
            }
        }
    }

    private suspend fun processMirrorOutboxOp(
        gw: CloudSyncGateway,
        uid: String,
        op: SyncOutboxEntity
    ) {
        val local = db.mirrorConfigDao().getByProvider(op.localEntityId)
        if (local == null) {
            db.syncDao().deleteOutboxOp(op.opId)
            return
        }
        val outcome = gw.runMirrorMutation(
            uid = uid,
            cloudDocId = op.cloudDocId,
            opId = op.opId,
            expectedServerRevision = op.expectedServerRevision,
            localEntity = local
        )
        beforeOutboxAckHook?.invoke(op)
        db.withTransaction {
            val (rev, updatedMs) = when (outcome) {
                is CasMutationOutcome.Committed -> outcome.serverRevision to outcome.serverUpdatedAtMs
                is CasMutationOutcome.AlreadyCommitted -> outcome.serverRevision to outcome.serverUpdatedAtMs
                is CasMutationOutcome.ServerTombstoneWon -> outcome.serverRevision to outcome.serverUpdatedAtMs
            }
            db.syncDao().upsertRecordLink(
                CloudRecordLinkEntity(
                    accountUid = uid,
                    entityType = SyncEntityType.MIRROR,
                    cloudDocId = op.cloudDocId,
                    localEntityId = local.provider,
                    serverRevision = rev,
                    lastSyncedLocalRevision = op.targetLocalRevision,
                    serverDeleted = false,
                    serverUpdatedAtMs = updatedMs
                )
            )
            db.syncDao().deleteOutboxOp(op.opId)
        }
    }

    suspend fun applyPulledBookmarks(uid: String, remoteRecords: List<BookmarkRecord>) {
        if (!isSessionAuthorized(uid)) return
        val accountScope = ScopeConstants.accountScope(uid)
        val nowMs = clockMs()

        db.withTransaction {
            if (!isSessionAuthorized(uid)) return@withTransaction
            val existingLinks = db.syncDao().getAllRecordLinksForType(uid, SyncEntityType.BOOKMARK)
                .associateBy { it.cloudDocId }
            val remoteByDocId = remoteRecords.associateBy { it.id }

            // 1. Apply remote upserts & tombstones
            for (record in remoteRecords) {
                val link = existingLinks[record.id]
                val verified = MagnetParser.extractVerifiedIdentities(record.magnetUri).firstOrNull()
                    ?: MagnetParser.normalizeHash(record.infoHash)
                val source = IndexerSource.fromNameSafe(record.source)
                val listingSeed = (record.legacyDocId ?: record.id).removePrefix("bm_${source.name}_").removePrefix("bm_")
                val canonicalKey = record.canonicalIdentity?.takeIf { it.isNotBlank() }
                    ?: verified?.storageKey
                    ?: CanonicalIdentityKey.UnresolvedListing(source, listingSeed).storageKey

                val existingEntity = link?.let { db.bookmarkDao().getByLocalId(it.localEntityId) }
                    ?: db.bookmarkDao().findVisibleOrTombstoneByCanonicalKey(canonicalKey, accountScope)
                    ?: db.bookmarkDao().findByAliasInVisibleScopes("legacy:${record.id}", accountScope)
                    ?: db.bookmarkDao().findByLegacyDocIdInVisibleScopes(record.id, accountScope)

                val pendingOp = existingEntity?.let {
                    db.syncDao().findPendingOp(uid, SyncEntityType.BOOKMARK, it.localId, record.id)
                }
                val serverRev = record.effectiveRevision
                val serverUpdatedMs = ((record.updatedAt?.seconds ?: 0L) * 1000L).takeIf { it > 0L } ?: nowMs
                val serverDeletedAtMs = ((record.deletedAt?.seconds ?: 0L) * 1000L).takeIf { it > 0L } ?: serverUpdatedMs

                if (record.isTombstone) {
                    if (existingEntity != null) {
                        val localExplicitlyNewer = pendingOp != null &&
                            pendingOp.opType == SyncOpType.UPSERT &&
                            pendingOp.isExplicitResave &&
                            serverRev <= pendingOp.expectedServerRevision
                        if (!localExplicitlyNewer) {
                            db.bookmarkDao().upsertBookmark(
                                existingEntity.copy(
                                    isDeleted = true,
                                    deletedAtEpochMs = serverDeletedAtMs,
                                    updatedAtEpochMs = maxOf(existingEntity.updatedAtEpochMs, serverUpdatedMs)
                                )
                            )
                            if (pendingOp != null) db.syncDao().deleteOutboxOp(pendingOp.opId)
                        }
                        db.syncDao().upsertRecordLink(
                            CloudRecordLinkEntity(
                                accountUid = uid,
                                entityType = SyncEntityType.BOOKMARK,
                                cloudDocId = record.id,
                                localEntityId = existingEntity.localId,
                                serverRevision = serverRev,
                                lastSyncedLocalRevision = existingEntity.localRevision,
                                serverDeleted = true,
                                serverUpdatedAtMs = serverUpdatedMs
                            )
                        )
                    }
                    continue
                }

                // Active remote document
                if (pendingOp != null && pendingOp.opType == SyncOpType.DELETE && serverRev <= pendingOp.expectedServerRevision) {
                    // Local deletion hasn't flushed to server yet; don't resurrect prematurely
                    continue
                }

                val targetLocalId = existingEntity?.localId
                    ?: UUID.nameUUIDFromBytes("$accountScope:$canonicalKey".toByteArray()).toString()
                val targetScope = existingEntity?.originScope ?: accountScope
                val mergedMagnet = existingEntity?.magnetUri?.takeIf { it.isNotBlank() && record.magnetUri.isBlank() }
                    ?: record.magnetUri.take(4000)
                val mergedHash = verified?.canonicalHex ?: existingEntity?.canonicalHashHex.orEmpty().ifBlank { record.infoHash }

                val mergedEntity = BookmarkEntity(
                    localId = targetLocalId,
                    originScope = targetScope,
                    canonicalKey = existingEntity?.canonicalKey?.takeIf {
                        it.startsWith("btih:") || it.startsWith("btmh:")
                    } ?: canonicalKey,
                    hashFamily = verified?.family?.name ?: existingEntity?.hashFamily,
                    canonicalHashHex = mergedHash.take(128),
                    legacyDocId = record.legacyDocId?.takeIf { it.isNotBlank() } ?: record.id,
                    title = record.title.ifBlank { existingEntity?.title ?: "Untitled Torrent" }.take(500),
                    source = record.source.ifBlank { existingEntity?.source ?: IndexerSource.X1337.name },
                    category = record.category.ifBlank { existingEntity?.category ?: TorrentCategory.ALL.name },
                    sizeLabel = record.sizeLabel.ifBlank { existingEntity?.sizeLabel ?: "Unknown" }.take(64),
                    sizeBytes = record.sizeBytes.takeIf { it > 0L } ?: (existingEntity?.sizeBytes ?: 0L),
                    knownSizeBytes = record.sizeBytes.takeIf { it > 0L } ?: existingEntity?.knownSizeBytes,
                    seeders = record.seeders.toInt().coerceAtLeast(0),
                    knownSeeders = record.seeders.toInt().coerceAtLeast(0),
                    leechers = record.leechers.toInt().coerceAtLeast(0),
                    knownLeechers = record.leechers.toInt().coerceAtLeast(0),
                    uploadDateLabel = record.uploadDateLabel.ifBlank { existingEntity?.uploadDateLabel ?: "Unknown" }.take(64),
                    uploadEpochSeconds = existingEntity?.uploadEpochSeconds ?: (record.createdAt?.seconds ?: 0L),
                    knownUploadEpochSeconds = existingEntity?.knownUploadEpochSeconds,
                    magnetUri = mergedMagnet,
                    infoHash = mergedHash.take(128),
                    detailPageUrl = record.detailPageUrl.ifBlank { existingEntity?.detailPageUrl.orEmpty() }.take(1000),
                    listingId = existingEntity?.listingId?.ifBlank { listingSeed } ?: listingSeed,
                    qualityTagsJson = JSONArray(record.qualityTags.take(10)).toString(),
                    createdAtEpochMs = existingEntity?.createdAtEpochMs
                        ?: ((record.createdAt?.seconds ?: 0L) * 1000L).takeIf { it > 0L }
                        ?: nowMs,
                    updatedAtEpochMs = maxOf(existingEntity?.updatedAtEpochMs ?: 0L, serverUpdatedMs),
                    localRevision = existingEntity?.localRevision ?: 1L,
                    isDeleted = false,
                    deletedAtEpochMs = null
                )
                db.bookmarkDao().upsertBookmark(mergedEntity)
                db.bookmarkDao().upsertIdentityAlias(
                    IdentityAliasEntity(
                        originScope = targetScope,
                        aliasKey = "legacy:${record.id}",
                        bookmarkLocalId = targetLocalId,
                        createdAtEpochMs = nowMs
                    )
                )
                db.syncDao().upsertRecordLink(
                    CloudRecordLinkEntity(
                        accountUid = uid,
                        entityType = SyncEntityType.BOOKMARK,
                        cloudDocId = record.id,
                        localEntityId = targetLocalId,
                        serverRevision = serverRev,
                        lastSyncedLocalRevision = mergedEntity.localRevision,
                        serverDeleted = false,
                        serverUpdatedAtMs = serverUpdatedMs
                    )
                )
            }

            // 2. Legacy client hard-delete compatibility: if a previously linked cloud doc disappeared completely
            for ((docId, link) in existingLinks) {
                if (docId !in remoteByDocId && !link.serverDeleted) {
                    val local = db.bookmarkDao().getByLocalId(link.localEntityId)
                    val pendingOp = db.syncDao().findPendingOp(uid, SyncEntityType.BOOKMARK, link.localEntityId, docId)
                    val explicitResave = pendingOp != null && pendingOp.opType == SyncOpType.UPSERT && pendingOp.isExplicitResave
                    if (local != null && !explicitResave) {
                        db.bookmarkDao().upsertBookmark(
                            local.copy(
                                isDeleted = true,
                                deletedAtEpochMs = nowMs,
                                updatedAtEpochMs = nowMs
                            )
                        )
                        if (pendingOp != null) db.syncDao().deleteOutboxOp(pendingOp.opId)
                        db.syncDao().upsertRecordLink(
                            link.copy(serverDeleted = true, serverUpdatedAtMs = nowMs)
                        )
                    }
                }
            }
        }
    }

    suspend fun applyPulledHistory(uid: String, remoteHistory: List<SearchHistoryRecord>) {
        if (!isSessionAuthorized(uid)) return
        val accountScope = ScopeConstants.accountScope(uid)
        val nowMs = clockMs()

        db.withTransaction {
            if (!isSessionAuthorized(uid)) return@withTransaction
            val existingLinks = db.syncDao().getAllRecordLinksForType(uid, SyncEntityType.HISTORY)
                .associateBy { it.cloudDocId }
            val remoteByDocId = remoteHistory.associateBy { it.id }

            for (record in remoteHistory) {
                val query = record.query.trim().take(200)
                if (query.isBlank()) continue
                val normalizedQuery = query.lowercase(Locale.US)
                val link = existingLinks[record.id]
                val existingEntity = link?.let { db.searchHistoryDao().getByEntryId(it.localEntityId) }
                    ?: db.searchHistoryDao().findByScopeAndNormalizedQuery(accountScope, normalizedQuery)
                    ?: db.searchHistoryDao().findByScopeAndNormalizedQuery(ScopeConstants.SCOPE_DEVICE, normalizedQuery)

                val pendingOp = existingEntity?.let {
                    db.syncDao().findPendingOp(uid, SyncEntityType.HISTORY, it.entryId, record.id)
                }
                val serverRev = record.effectiveRevision
                val serverUpdatedMs = ((record.updatedAt?.seconds ?: 0L) * 1000L).takeIf { it > 0L } ?: nowMs
                val serverDeletedMs = ((record.deletedAt?.seconds ?: 0L) * 1000L).takeIf { it > 0L } ?: serverUpdatedMs

                if (record.isTombstone) {
                    if (existingEntity != null) {
                        val newerResave = pendingOp != null &&
                            pendingOp.opType == SyncOpType.UPSERT &&
                            pendingOp.isExplicitResave &&
                            serverRev <= pendingOp.expectedServerRevision
                        if (!newerResave) {
                            db.searchHistoryDao().upsertHistory(
                                existingEntity.copy(
                                    isDeleted = true,
                                    deletedAtEpochMs = serverDeletedMs,
                                    updatedAtEpochMs = maxOf(existingEntity.updatedAtEpochMs, serverUpdatedMs)
                                )
                            )
                            if (pendingOp != null) db.syncDao().deleteOutboxOp(pendingOp.opId)
                        }
                        db.syncDao().upsertRecordLink(
                            CloudRecordLinkEntity(
                                accountUid = uid,
                                entityType = SyncEntityType.HISTORY,
                                cloudDocId = record.id,
                                localEntityId = existingEntity.entryId,
                                serverRevision = serverRev,
                                lastSyncedLocalRevision = existingEntity.localRevision,
                                serverDeleted = true,
                                serverUpdatedAtMs = serverUpdatedMs
                            )
                        )
                    }
                    continue
                }

                if (pendingOp != null && pendingOp.opType == SyncOpType.DELETE && serverRev <= pendingOp.expectedServerRevision) {
                    continue
                }

                val targetId = existingEntity?.entryId
                    ?: UUID.nameUUIDFromBytes("$accountScope:hist:$normalizedQuery".toByteArray()).toString()
                val targetScope = existingEntity?.originScope ?: accountScope
                val merged = SearchHistoryEntity(
                    entryId = targetId,
                    originScope = targetScope,
                    cloudDocId = record.id,
                    query = query,
                    normalizedQuery = normalizedQuery,
                    category = TorrentCategory.fromNameSafe(record.category).name,
                    createdAtEpochMs = existingEntity?.createdAtEpochMs
                        ?: ((record.createdAt?.seconds ?: 0L) * 1000L).takeIf { it > 0L }
                        ?: nowMs,
                    updatedAtEpochMs = maxOf(existingEntity?.updatedAtEpochMs ?: 0L, serverUpdatedMs),
                    localRevision = existingEntity?.localRevision ?: 1L,
                    isDeleted = false,
                    deletedAtEpochMs = null
                )
                db.searchHistoryDao().upsertHistory(merged)
                db.syncDao().upsertRecordLink(
                    CloudRecordLinkEntity(
                        accountUid = uid,
                        entityType = SyncEntityType.HISTORY,
                        cloudDocId = record.id,
                        localEntityId = targetId,
                        serverRevision = serverRev,
                        lastSyncedLocalRevision = merged.localRevision,
                        serverDeleted = false,
                        serverUpdatedAtMs = serverUpdatedMs
                    )
                )
            }

            // Legacy client hard-delete compatibility for search history
            for ((docId, link) in existingLinks) {
                if (docId !in remoteByDocId && !link.serverDeleted) {
                    val local = db.searchHistoryDao().getByEntryId(link.localEntityId)
                    val pendingOp = db.syncDao().findPendingOp(uid, SyncEntityType.HISTORY, link.localEntityId, docId)
                    val explicitResave = pendingOp != null && pendingOp.opType == SyncOpType.UPSERT && pendingOp.isExplicitResave
                    if (local != null && !explicitResave) {
                        db.searchHistoryDao().upsertHistory(
                            local.copy(isDeleted = true, deletedAtEpochMs = nowMs, updatedAtEpochMs = nowMs)
                        )
                        if (pendingOp != null) db.syncDao().deleteOutboxOp(pendingOp.opId)
                        db.syncDao().upsertRecordLink(link.copy(serverDeleted = true, serverUpdatedAtMs = nowMs))
                    }
                }
            }
        }
    }

    suspend fun applyPulledMirrors(uid: String, remoteMirrors: List<MirrorConfigRecord>) {
        if (!isSessionAuthorized(uid)) return
        val nowMs = clockMs()
        db.withTransaction {
            if (!isSessionAuthorized(uid)) return@withTransaction
            for (record in remoteMirrors) {
                val cfg = record.toMirrorConfig()
                val existing = db.mirrorConfigDao().getByProvider(cfg.provider.name)
                val pending = db.syncDao().findPendingOp(uid, SyncEntityType.MIRROR, cfg.provider.name, cfg.provider.name)
                if (pending != null) continue
                val serverUpdatedMs = ((record.updatedAt?.seconds ?: 0L) * 1000L).takeIf { it > 0L } ?: nowMs
                if (existing == null || serverUpdatedMs >= existing.updatedAtEpochMs) {
                    db.mirrorConfigDao().upsertMirrorConfig(
                        MirrorConfigEntity(
                            provider = cfg.provider.name,
                            enabled = cfg.enabled,
                            mirrorsJson = JSONArray(cfg.mirrors).toString(),
                            activeMirrorUrl = cfg.activeMirrorUrl,
                            updatedAtEpochMs = serverUpdatedMs,
                            localRevision = (existing?.localRevision ?: 0L) + 1L
                        )
                    )
                }
                db.syncDao().upsertRecordLink(
                    CloudRecordLinkEntity(
                        accountUid = uid,
                        entityType = SyncEntityType.MIRROR,
                        cloudDocId = cfg.provider.name,
                        localEntityId = cfg.provider.name,
                        serverRevision = record.effectiveRevision,
                        lastSyncedLocalRevision = existing?.localRevision ?: 1L,
                        serverDeleted = false,
                        serverUpdatedAtMs = serverUpdatedMs
                    )
                )
            }
        }
    }

    private suspend fun isSessionAuthorized(expectedUid: String): Boolean {
        val currentAuthUid = currentAuthUidProvider()
        if (currentAuthUid != expectedUid) return false
        return prefsStore.getSyncPrefsForAccount(expectedUid).enrolled
    }
}
