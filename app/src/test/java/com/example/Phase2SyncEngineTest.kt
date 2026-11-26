package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LegacyDataMigrator
import com.example.data.local.LocalPrefsStore
import com.example.data.local.VaultLocalRepository
import com.example.data.local.db.BookmarkEntity
import com.example.data.local.db.MagnetVaultDatabase
import com.example.data.local.db.MirrorConfigEntity
import com.example.data.local.db.SearchHistoryEntity
import com.example.data.local.db.SyncEntityType
import com.example.data.sync.CasMutationOutcome
import com.example.data.sync.CloudSyncGateway
import com.example.data.sync.FirestoreSyncEngine
import com.example.data.sync.SyncSessionStatus
import com.example.data.sync.SyncWorkScheduler
import com.example.model.BookmarkRecord
import com.example.model.IndexerSource
import com.example.model.MirrorConfigRecord
import com.example.model.SearchHistoryRecord
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2SyncEngineTest {

    private lateinit var context: Context
    private lateinit var db: MagnetVaultDatabase
    private lateinit var prefsStore: LocalPrefsStore

    private val btih1 = "1111111111111111111111111111111111111111"
    private val btih2 = "2222222222222222222222222222222222222222"
    private val magnet1 = "magnet:?xt=urn:btih:$btih1&dn=Release+One"
    private val magnet2 = "magnet:?xt=urn:btih:$btih2&dn=Release+Two"

    private class FakeWorkScheduler : SyncWorkScheduler {
        val enqueuedUids = mutableListOf<String>()
        val cancelledUids = mutableListOf<String>()
        override fun enqueueForAccount(uid: String) {
            enqueuedUids += uid
        }
        override fun cancelForAccount(uid: String) {
            cancelledUids += uid
        }
    }

    private class FakeCloudSyncGateway : CloudSyncGateway {
        val bookmarksStore = linkedMapOf<String, LinkedHashMap<String, BookmarkRecord>>()
        val historyStore = linkedMapOf<String, LinkedHashMap<String, SearchHistoryRecord>>()
        val mirrorsStore = linkedMapOf<String, LinkedHashMap<String, MirrorConfigRecord>>()

        private val bookmarkFlow = MutableStateFlow<Map<String, List<BookmarkRecord>>>(emptyMap())
        private val historyFlow = MutableStateFlow<Map<String, List<SearchHistoryRecord>>>(emptyMap())
        private val mirrorsFlow = MutableStateFlow<Map<String, List<MirrorConfigRecord>>>(emptyMap())

        val fetchCalls = AtomicInteger(0)
        val mutationCalls = AtomicInteger(0)
        var nextMutationFailure: Throwable? = null

        fun seedRemoteBookmark(uid: String, record: BookmarkRecord) {
            val map = bookmarksStore.getOrPut(uid) { linkedMapOf() }
            map[record.id] = record
            emitBookmarks(uid)
        }

        private fun emitBookmarks(uid: String) {
            bookmarkFlow.value = bookmarkFlow.value + (uid to bookmarksStore[uid]?.values?.toList().orEmpty())
        }

        private fun emitHistory(uid: String) {
            historyFlow.value = historyFlow.value + (uid to historyStore[uid]?.values?.toList().orEmpty())
        }

        private fun emitMirrors(uid: String) {
            mirrorsFlow.value = mirrorsFlow.value + (uid to mirrorsStore[uid]?.values?.toList().orEmpty())
        }

        override suspend fun fetchBookmarks(uid: String): List<BookmarkRecord> {
            fetchCalls.incrementAndGet()
            return bookmarksStore[uid]?.values?.toList().orEmpty()
        }

        override suspend fun fetchSearchHistory(uid: String): List<SearchHistoryRecord> {
            fetchCalls.incrementAndGet()
            return historyStore[uid]?.values?.toList().orEmpty()
        }

        override suspend fun fetchMirrors(uid: String): List<MirrorConfigRecord> {
            fetchCalls.incrementAndGet()
            return mirrorsStore[uid]?.values?.toList().orEmpty()
        }

        override fun observeBookmarks(uid: String): Flow<List<BookmarkRecord>> =
            bookmarkFlow.map { it[uid].orEmpty() }

        override fun observeSearchHistory(uid: String): Flow<List<SearchHistoryRecord>> =
            historyFlow.map { it[uid].orEmpty() }

        override fun observeMirrors(uid: String): Flow<List<MirrorConfigRecord>> =
            mirrorsFlow.map { it[uid].orEmpty() }

        override suspend fun runBookmarkMutation(
            uid: String,
            cloudDocId: String,
            opId: String,
            isDelete: Boolean,
            expectedServerRevision: Long,
            isExplicitResave: Boolean,
            localEntity: BookmarkEntity
        ): CasMutationOutcome<BookmarkRecord> {
            nextMutationFailure?.let {
                nextMutationFailure = null
                throw it
            }
            mutationCalls.incrementAndGet()
            val map = bookmarksStore.getOrPut(uid) { linkedMapOf() }
            val existing = map[cloudDocId]
            if (existing != null && existing.lastOpId == opId) {
                return CasMutationOutcome.AlreadyCommitted(
                    serverRevision = existing.effectiveRevision,
                    serverDeleted = existing.isTombstone,
                    serverUpdatedAtMs = (existing.updatedAt?.seconds ?: 0L) * 1000L,
                    serverRecord = existing
                )
            }
            val serverRev = existing?.effectiveRevision ?: 0L
            val serverDeleted = existing?.isTombstone == true
            val nowTs = Timestamp(1_750_000_000L, 0)

            if (isDelete) {
                if (existing != null && serverDeleted && serverRev >= expectedServerRevision) {
                    return CasMutationOutcome.AlreadyCommitted(serverRev, true, 1_750_000_000_000L, existing)
                }
                val nextRev = serverRev + 1L
                val updated = (existing ?: BookmarkRecord(
                    id = cloudDocId,
                    userId = uid,
                    title = localEntity.title,
                    source = localEntity.source,
                    category = localEntity.category,
                    sizeLabel = localEntity.sizeLabel,
                    sizeBytes = localEntity.sizeBytes,
                    seeders = localEntity.seeders.toLong(),
                    leechers = localEntity.leechers.toLong(),
                    uploadDateLabel = localEntity.uploadDateLabel,
                    magnetUri = localEntity.magnetUri,
                    infoHash = localEntity.canonicalHashHex,
                    detailPageUrl = localEntity.detailPageUrl,
                    qualityTags = localEntity.qualityTagNames(),
                    createdAt = nowTs
                )).copy(
                    deleted = true,
                    deletedAt = nowTs,
                    updatedAt = nowTs,
                    revision = nextRev,
                    lastOpId = opId
                )
                map[cloudDocId] = updated
                emitBookmarks(uid)
                return CasMutationOutcome.Committed(nextRev, true, 1_750_000_000_000L, updated)
            }

            if (existing != null && serverDeleted && !isExplicitResave && serverRev > expectedServerRevision) {
                return CasMutationOutcome.ServerTombstoneWon(serverRev, 1_750_000_000_000L)
            }

            val nextRev = serverRev + 1L
            val updated = BookmarkRecord(
                id = cloudDocId,
                userId = uid,
                title = localEntity.title,
                source = localEntity.source,
                category = localEntity.category,
                sizeLabel = localEntity.sizeLabel,
                sizeBytes = localEntity.sizeBytes,
                seeders = localEntity.seeders.toLong(),
                leechers = localEntity.leechers.toLong(),
                uploadDateLabel = localEntity.uploadDateLabel,
                magnetUri = localEntity.magnetUri,
                infoHash = localEntity.canonicalHashHex,
                detailPageUrl = localEntity.detailPageUrl,
                qualityTags = localEntity.qualityTagNames(),
                createdAt = existing?.createdAt ?: nowTs,
                updatedAt = nowTs,
                revision = nextRev,
                deleted = false,
                deletedAt = null,
                lastOpId = opId,
                canonicalIdentity = localEntity.canonicalKey,
                legacyDocId = localEntity.legacyDocId
            )
            map[cloudDocId] = updated
            emitBookmarks(uid)
            return CasMutationOutcome.Committed(nextRev, false, 1_750_000_000_000L, updated)
        }

        override suspend fun runHistoryMutation(
            uid: String,
            cloudDocId: String,
            opId: String,
            isDelete: Boolean,
            expectedServerRevision: Long,
            isExplicitResave: Boolean,
            localEntity: SearchHistoryEntity
        ): CasMutationOutcome<SearchHistoryRecord> {
            val map = historyStore.getOrPut(uid) { linkedMapOf() }
            val existing = map[cloudDocId]
            val nextRev = (existing?.effectiveRevision ?: 0L) + 1L
            val nowTs = Timestamp(1_750_000_000L, 0)
            val rec = SearchHistoryRecord(
                id = cloudDocId,
                userId = uid,
                query = localEntity.query,
                category = localEntity.category,
                createdAt = existing?.createdAt ?: nowTs,
                updatedAt = nowTs,
                revision = nextRev,
                deleted = isDelete,
                deletedAt = if (isDelete) nowTs else null,
                lastOpId = opId
            )
            map[cloudDocId] = rec
            emitHistory(uid)
            return CasMutationOutcome.Committed(nextRev, isDelete, 1_750_000_000_000L, rec)
        }

        override suspend fun runMirrorMutation(
            uid: String,
            cloudDocId: String,
            opId: String,
            expectedServerRevision: Long,
            localEntity: MirrorConfigEntity
        ): CasMutationOutcome<MirrorConfigRecord> {
            val map = mirrorsStore.getOrPut(uid) { linkedMapOf() }
            val existing = map[cloudDocId]
            val nextRev = (existing?.effectiveRevision ?: 0L) + 1L
            val cfg = localEntity.toMirrorConfig()
            val nowTs = Timestamp(1_750_000_000L, 0)
            val rec = MirrorConfigRecord(
                id = cloudDocId,
                userId = uid,
                provider = cfg.provider.name,
                enabled = cfg.enabled,
                mirrors = cfg.mirrors,
                activeMirrorUrl = cfg.activeMirrorUrl,
                createdAt = existing?.createdAt ?: nowTs,
                updatedAt = nowTs,
                revision = nextRev,
                lastOpId = opId
            )
            map[cloudDocId] = rec
            emitMirrors(uid)
            return CasMutationOutcome.Committed(nextRev, false, 1_750_000_000_000L, rec)
        }
    }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        prefsStore = LocalPrefsStore(context)
        prefsStore.clearAllForTesting()
        db = MagnetVaultDatabase.createInMemory(context)
    }

    @After
    fun tearDown() = runBlocking {
        prefsStore.clearAllForTesting()
        db.close()
    }

    private fun sampleTorrent(id: String, title: String, btih: String, magnet: String) = TorrentResult(
        id = id,
        title = title,
        source = IndexerSource.TPB,
        category = TorrentCategory.APPS,
        sizeLabel = "1.20 GB",
        sizeBytes = 1_288_490_188L,
        seeders = 150,
        leechers = 8,
        uploadDateLabel = "Today",
        uploadEpochSeconds = 1_750_000_000L,
        magnetUri = magnet,
        infoHash = btih,
        detailPageUrl = "https://tpb.party/$id"
    )

    @Test
    fun `google sign-in alone performs zero cloud reads or writes until explicit enrollment`() = runBlocking {
        var authUid: String? = "alice_uid"
        val gateway = FakeCloudSyncGateway()
        val localRepo = VaultLocalRepository(db, LegacyDataMigrator(db, prefsStore))
        val engine = FirestoreSyncEngine(
            db = db,
            localRepo = localRepo,
            prefsStore = prefsStore,
            gateway = gateway,
            currentAuthUidProvider = { authUid },
            isOnlineProvider = { true },
            workScheduler = FakeWorkScheduler()
        )

        // Signed in WITHOUT sync enrollment
        engine.startOrUpdateSession("alice_uid")
        localRepo.saveOrUpdateBookmark(
            sampleTorrent("bm_1", "Local Release One", btih1, magnet1),
            syncContext = engine.getActiveSyncContext("alice_uid")
        ).getOrThrow()

        assertEquals(SyncSessionStatus.LocalOnly, engine.syncStatus.value)
        assertEquals(0, gateway.fetchCalls.get())
        assertEquals(0, gateway.mutationCalls.get())
        assertTrue(gateway.bookmarksStore.isEmpty())

        // Remote has 2 bookmarks (1 overlapping btih1, 1 remote-only btih2)
        gateway.seedRemoteBookmark(
            "alice_uid",
            BookmarkRecord(
                id = "bm_$btih1",
                userId = "alice_uid",
                title = "Cloud Overlap",
                magnetUri = magnet1,
                infoHash = btih1,
                canonicalIdentity = "btih:$btih1",
                revision = 1L
            )
        )
        gateway.seedRemoteBookmark(
            "alice_uid",
            BookmarkRecord(
                id = "bm_$btih2",
                userId = "alice_uid",
                title = "Cloud Only Release",
                magnetUri = magnet2,
                infoHash = btih2,
                canonicalIdentity = "btih:$btih2",
                revision = 1L
            )
        )

        val preview = engine.fetchEnrollmentPreview("alice_uid").getOrThrow()
        assertEquals(1, preview.localBookmarkCount)
        assertEquals(2, preview.cloudBookmarkCount)
        assertEquals(1, preview.overlapCount)
        assertEquals(0, preview.newToUploadCount)
        assertEquals(1, preview.newToDownloadCount)

        // Enroll with history & mirrors opted out (default)
        engine.enrollAccountAndSync("alice_uid", syncHistory = false, syncMirrors = false).getOrThrow()
        localRepo.recordSearchQuery("secret local query", TorrentCategory.ALL, engine.getActiveSyncContext("alice_uid"))

        // Bookmarks synced (2 visible), history NEVER uploaded because opt-in is false
        assertEquals(2, localRepo.observeBookmarks("alice_uid").first().size)
        assertTrue(gateway.historyStore["alice_uid"].isNullOrEmpty())
        assertTrue(gateway.mirrorsStore["alice_uid"].isNullOrEmpty())

        // Stop session for sign-out removes listeners and isolates alice's account-scoped data from signed-out / bob view
        engine.stopSessionForSignOut("alice_uid")
        authUid = null
        assertEquals(SyncSessionStatus.LocalOnly, engine.syncStatus.value)
        // Signed-out workspace sees only DEVICE-scoped bookmarks (bm_1), NOT alice's ACCOUNT-scoped bm_2
        val signedOutVisible = localRepo.observeBookmarks(null).first()
        assertEquals(1, signedOutVisible.size)
        assertEquals("btih:$btih1", signedOutVisible.first().bookmark.canonicalKey)
    }

    @Test
    fun `outbox mutation is idempotent after simulated crash before local ack`() = runBlocking {
        val crashOnce = AtomicBoolean(true)
        val gateway = FakeCloudSyncGateway()
        val localRepo = VaultLocalRepository(db, LegacyDataMigrator(db, prefsStore))
        val engine = FirestoreSyncEngine(
            db = db,
            localRepo = localRepo,
            prefsStore = prefsStore,
            gateway = gateway,
            currentAuthUidProvider = { "alice_uid" },
            isOnlineProvider = { true },
            beforeOutboxAckHook = {
                if (crashOnce.compareAndSet(true, false)) {
                    throw IllegalStateException("Simulated crash after server ack before Room ack")
                }
            }
        )

        prefsStore.setSyncEnrollment("alice_uid", enrolled = true)
        val syncCtx = engine.getActiveSyncContext("alice_uid")!!
        val saved = localRepo.saveOrUpdateBookmark(
            sampleTorrent("bm_crash", "Crash Safe ISO", btih1, magnet1),
            syncContext = syncCtx
        ).getOrThrow()

        // First drain hits hook after server commits rev = 1 -> outbox stays PENDING
        val firstDrain = engine.drainOutbox("alice_uid")
        assertEquals(1, firstDrain.remainingPendingCount)
        assertEquals(1L, gateway.bookmarksStore["alice_uid"]?.get("bm_$btih1")?.effectiveRevision)

        // Second drain replays exact same opId -> gateway returns AlreadyCommitted (revision remains 1)
        val secondDrain = engine.drainOutbox("alice_uid")
        assertEquals(0, secondDrain.remainingPendingCount)
        assertEquals(1L, gateway.bookmarksStore["alice_uid"]?.get("bm_$btih1")?.effectiveRevision)
        val link = db.syncDao().getRecordLinkByLocalId("alice_uid", SyncEntityType.BOOKMARK, saved.localId)
        assertEquals(1L, link?.serverRevision)
    }

    @Test
    fun `remote tombstone wins over stale offline edit while explicit resave resurrects`() = runBlocking {
        val gateway = FakeCloudSyncGateway()
        val localRepo = VaultLocalRepository(db, LegacyDataMigrator(db, prefsStore))
        val engine = FirestoreSyncEngine(
            db = db,
            localRepo = localRepo,
            prefsStore = prefsStore,
            gateway = gateway,
            currentAuthUidProvider = { "alice_uid" },
            isOnlineProvider = { true }
        )

        prefsStore.setSyncEnrollment("alice_uid", enrolled = true)
        val syncCtx = engine.getActiveSyncContext("alice_uid")!!
        val torrent = sampleTorrent("bm_res", "Tombstone Race ISO", btih1, magnet1)
        val saved = localRepo.saveOrUpdateBookmark(torrent, syncContext = syncCtx).getOrThrow()
        engine.drainOutbox("alice_uid")

        // Server receives tombstone deletion at revision 2 from another device
        gateway.seedRemoteBookmark(
            "alice_uid",
            gateway.bookmarksStore["alice_uid"]!!["bm_$btih1"]!!.copy(
                deleted = true,
                deletedAt = Timestamp(1_750_000_500L, 0),
                revision = 2L,
                lastOpId = "other_device_delete"
            )
        )

        // Stale non-explicit offline edit queued against expectedServerRevision = 1 -> ServerTombstoneWon!
        localRepo.saveOrUpdateBookmark(
            torrent.copy(seeders = 999),
            existingLocalIdHint = saved.localId,
            syncContext = syncCtx,
            forceResave = false
        ).getOrThrow()
        engine.drainOutbox("alice_uid")

        // Delete won -> local bookmark is now marked deleted
        val afterConflict = db.bookmarkDao().getByLocalId(saved.localId)!!
        assertTrue(afterConflict.isDeleted)

        // Explicit user re-save resurrects the tombstone to revision 3!
        localRepo.saveOrUpdateBookmark(
            torrent.copy(seeders = 1001),
            existingLocalIdHint = saved.localId,
            syncContext = syncCtx,
            forceResave = true
        ).getOrThrow()
        engine.drainOutbox("alice_uid")

        val resurrectedLocal = db.bookmarkDao().getByLocalId(saved.localId)!!
        assertFalse(resurrectedLocal.isDeleted)
        val resurrectedRemote = gateway.bookmarksStore["alice_uid"]!!["bm_$btih1"]!!
        assertFalse(resurrectedRemote.isTombstone)
        assertEquals(3L, resurrectedRemote.effectiveRevision)
    }

    @Test
    fun `permission denied moves outbox to ACTION_NEEDED and syncNowOrRetry resets and retries`() = runBlocking {
        val gateway = FakeCloudSyncGateway()
        val scheduler = FakeWorkScheduler()
        val localRepo = VaultLocalRepository(db, LegacyDataMigrator(db, prefsStore))
        val engine = FirestoreSyncEngine(
            db = db,
            localRepo = localRepo,
            prefsStore = prefsStore,
            gateway = gateway,
            currentAuthUidProvider = { "alice_uid" },
            isOnlineProvider = { true },
            workScheduler = scheduler
        )
        prefsStore.setSyncEnrollment("alice_uid", enrolled = true)
        val syncCtx = engine.getActiveSyncContext("alice_uid")!!
        localRepo.saveOrUpdateBookmark(sampleTorrent("bm_perm", "Rule Test", btih1, magnet1), syncContext = syncCtx)

        gateway.nextMutationFailure = FirebaseFirestoreException(
            "Permission denied",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
        val failedDrain = engine.drainOutbox("alice_uid")
        assertEquals("PERMISSION_DENIED", failedDrain.actionNeededCategory)
        val allOutbox = db.syncDao().getAllOutboxForAccount("alice_uid")
        assertEquals(1, allOutbox.size)
        assertEquals("ACTION_NEEDED", allOutbox.first().status)

        // Retry clears ACTION_NEEDED and succeeds
        val retryDrain = engine.syncNowOrRetry("alice_uid")
        assertEquals(0, retryDrain.remainingPendingCount)
        assertEquals(null, retryDrain.actionNeededCategory)
        assertTrue(db.syncDao().getAllOutboxForAccount("alice_uid").isEmpty())
    }
}
