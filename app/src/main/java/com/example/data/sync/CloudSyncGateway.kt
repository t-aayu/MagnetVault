package com.example.data.sync

import com.example.R
import com.example.data.local.db.BookmarkEntity
import com.example.data.local.db.MirrorConfigEntity
import com.example.data.local.db.SearchHistoryEntity
import com.example.model.BookmarkRecord
import com.example.model.IndexerSource
import com.example.model.MirrorConfigRecord
import com.example.model.SearchHistoryRecord
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed class CasMutationOutcome<out T> {
    data class Committed<T>(
        val serverRevision: Long,
        val serverDeleted: Boolean,
        val serverUpdatedAtMs: Long,
        val serverRecord: T?
    ) : CasMutationOutcome<T>()

    data class AlreadyCommitted<T>(
        val serverRevision: Long,
        val serverDeleted: Boolean,
        val serverUpdatedAtMs: Long,
        val serverRecord: T?
    ) : CasMutationOutcome<T>()

    data class ServerTombstoneWon<T>(
        val serverRevision: Long,
        val serverUpdatedAtMs: Long
    ) : CasMutationOutcome<T>()
}

interface CloudSyncGateway {
    suspend fun fetchBookmarks(uid: String): List<BookmarkRecord>
    suspend fun fetchSearchHistory(uid: String): List<SearchHistoryRecord>
    suspend fun fetchMirrors(uid: String): List<MirrorConfigRecord>

    fun observeBookmarks(uid: String): Flow<List<BookmarkRecord>>
    fun observeSearchHistory(uid: String): Flow<List<SearchHistoryRecord>>
    fun observeMirrors(uid: String): Flow<List<MirrorConfigRecord>>

    suspend fun runBookmarkMutation(
        uid: String,
        cloudDocId: String,
        opId: String,
        isDelete: Boolean,
        expectedServerRevision: Long,
        isExplicitResave: Boolean,
        localEntity: BookmarkEntity
    ): CasMutationOutcome<BookmarkRecord>

    suspend fun runHistoryMutation(
        uid: String,
        cloudDocId: String,
        opId: String,
        isDelete: Boolean,
        expectedServerRevision: Long,
        isExplicitResave: Boolean,
        localEntity: SearchHistoryEntity
    ): CasMutationOutcome<SearchHistoryRecord>

    suspend fun runMirrorMutation(
        uid: String,
        cloudDocId: String,
        opId: String,
        expectedServerRevision: Long,
        localEntity: MirrorConfigEntity
    ): CasMutationOutcome<MirrorConfigRecord>
}

class FirebaseFirestoreGateway(
    private val firestoreProvider: () -> FirebaseFirestore
) : CloudSyncGateway {

    private val db: FirebaseFirestore get() = firestoreProvider()

    override suspend fun fetchBookmarks(uid: String): List<BookmarkRecord> {
        val snap = db.collection("users").document(uid).collection("bookmarks").get().await()
        return snap.toObjects(BookmarkRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
    }

    override suspend fun fetchSearchHistory(uid: String): List<SearchHistoryRecord> {
        val snap = db.collection("users").document(uid).collection("search_history").get().await()
        return snap.toObjects(SearchHistoryRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
    }

    override suspend fun fetchMirrors(uid: String): List<MirrorConfigRecord> {
        val snap = db.collection("users").document(uid).collection("mirror_configs").get().await()
        return snap.toObjects(MirrorConfigRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
    }

    override fun observeBookmarks(uid: String): Flow<List<BookmarkRecord>> = callbackFlow {
        val registration = db.collection("users").document(uid).collection("bookmarks")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snap != null) {
                    val items = snap.toObjects(
                        BookmarkRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    trySend(items)
                }
            }
        awaitClose { registration.remove() }
    }

    override fun observeSearchHistory(uid: String): Flow<List<SearchHistoryRecord>> = callbackFlow {
        val registration = db.collection("users").document(uid).collection("search_history")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snap != null) {
                    val items = snap.toObjects(
                        SearchHistoryRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    trySend(items)
                }
            }
        awaitClose { registration.remove() }
    }

    override fun observeMirrors(uid: String): Flow<List<MirrorConfigRecord>> = callbackFlow {
        val registration = db.collection("users").document(uid).collection("mirror_configs")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snap != null) {
                    val items = snap.toObjects(
                        MirrorConfigRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    trySend(items)
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun runBookmarkMutation(
        uid: String,
        cloudDocId: String,
        opId: String,
        isDelete: Boolean,
        expectedServerRevision: Long,
        isExplicitResave: Boolean,
        localEntity: BookmarkEntity
    ): CasMutationOutcome<BookmarkRecord> {
        val ref = db.collection("users").document(uid).collection("bookmarks").document(cloudDocId)
        return db.runTransaction { tx ->
            val snap = tx.get(ref)
            val existing = if (snap.exists()) {
                snap.toObject(BookmarkRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null

            if (existing != null && existing.lastOpId == opId) {
                return@runTransaction CasMutationOutcome.AlreadyCommitted(
                    serverRevision = existing.effectiveRevision,
                    serverDeleted = existing.isTombstone,
                    serverUpdatedAtMs = (existing.updatedAt?.seconds ?: 0L) * 1000L,
                    serverRecord = existing
                )
            }

            val serverRev = existing?.effectiveRevision ?: 0L
            val serverDeleted = existing?.isTombstone == true

            if (isDelete) {
                if (existing == null) {
                    val payload = buildBookmarkMap(uid, localEntity, revision = 1L, deleted = true, opId = opId, includeCreatedAt = true)
                    tx.set(ref, payload)
                    return@runTransaction CasMutationOutcome.Committed(1L, true, System.currentTimeMillis(), null)
                }
                if (serverDeleted && serverRev >= expectedServerRevision) {
                    return@runTransaction CasMutationOutcome.AlreadyCommitted(
                        serverRevision = serverRev,
                        serverDeleted = true,
                        serverUpdatedAtMs = (existing.updatedAt?.seconds ?: 0L) * 1000L,
                        serverRecord = existing
                    )
                }
                val nextRev = serverRev + 1L
                val updates = hashMapOf<String, Any?>(
                    "deleted" to true,
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "revision" to nextRev,
                    "lastOpId" to opId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                tx.update(ref, updates)
                return@runTransaction CasMutationOutcome.Committed(nextRev, true, System.currentTimeMillis(), existing)
            }

            // UPSERT path
            if (existing != null && serverDeleted) {
                if (!isExplicitResave && serverRev > expectedServerRevision) {
                    return@runTransaction CasMutationOutcome.ServerTombstoneWon(
                        serverRevision = serverRev,
                        serverUpdatedAtMs = (existing.deletedAt?.seconds ?: existing.updatedAt?.seconds ?: 0L) * 1000L
                    )
                }
            }

            if (existing == null) {
                val payload = buildBookmarkMap(uid, localEntity, revision = 1L, deleted = false, opId = opId, includeCreatedAt = true)
                tx.set(ref, payload)
                CasMutationOutcome.Committed(1L, false, System.currentTimeMillis(), null)
            } else {
                val nextRev = serverRev + 1L
                // Deterministic merge on concurrent edit: prefer resolved magnet & non-blank infoHash/detailUrl
                val mergedMagnet = localEntity.magnetUri.ifBlank { existing.magnetUri }.take(4000)
                val mergedHash = localEntity.canonicalHashHex.ifBlank { localEntity.infoHash }.ifBlank { existing.infoHash }.take(128)
                val mergedSeeders = maxOf(localEntity.seeders.toLong(), existing.seeders).coerceAtLeast(0L)
                val mergedLeechers = maxOf(localEntity.leechers.toLong(), existing.leechers).coerceAtLeast(0L)
                val mergedSizeBytes = localEntity.sizeBytes.takeIf { it > 0L } ?: existing.sizeBytes.coerceAtLeast(0L)
                val mergedSizeLabel = localEntity.sizeLabel.takeIf { it.isNotBlank() && it != "Unknown" } ?: existing.sizeLabel

                val updates = hashMapOf<String, Any?>(
                    "magnetUri" to mergedMagnet,
                    "infoHash" to mergedHash,
                    "seeders" to mergedSeeders,
                    "leechers" to mergedLeechers,
                    "sizeLabel" to mergedSizeLabel.take(64),
                    "sizeBytes" to mergedSizeBytes,
                    "deleted" to false,
                    "deletedAt" to FieldValue.delete(),
                    "revision" to nextRev,
                    "lastOpId" to opId,
                    "canonicalIdentity" to localEntity.canonicalKey.take(200),
                    "legacyDocId" to localEntity.legacyDocId.take(128),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                tx.update(ref, updates)
                CasMutationOutcome.Committed(
                    nextRev,
                    false,
                    System.currentTimeMillis(),
                    existing.copy(
                        magnetUri = mergedMagnet,
                        infoHash = mergedHash,
                        seeders = mergedSeeders,
                        leechers = mergedLeechers,
                        sizeBytes = mergedSizeBytes,
                        sizeLabel = mergedSizeLabel,
                        revision = nextRev,
                        deleted = false,
                        lastOpId = opId
                    )
                )
            }
        }.await()
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
        val ref = db.collection("users").document(uid).collection("search_history").document(cloudDocId)
        return db.runTransaction { tx ->
            val snap = tx.get(ref)
            val existing = if (snap.exists()) {
                snap.toObject(SearchHistoryRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null

            if (existing != null && existing.lastOpId == opId) {
                return@runTransaction CasMutationOutcome.AlreadyCommitted(
                    serverRevision = existing.effectiveRevision,
                    serverDeleted = existing.isTombstone,
                    serverUpdatedAtMs = (existing.updatedAt?.seconds ?: 0L) * 1000L,
                    serverRecord = existing
                )
            }

            val serverRev = existing?.effectiveRevision ?: 0L
            val serverDeleted = existing?.isTombstone == true

            if (isDelete) {
                if (existing == null) {
                    val payload = mapOf(
                        "userId" to uid.take(128),
                        "query" to localEntity.query.trim().take(200),
                        "category" to localEntity.category,
                        "deleted" to true,
                        "deletedAt" to FieldValue.serverTimestamp(),
                        "revision" to 1L,
                        "lastOpId" to opId,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    tx.set(ref, payload)
                    return@runTransaction CasMutationOutcome.Committed(1L, true, System.currentTimeMillis(), null)
                }
                if (serverDeleted && serverRev >= expectedServerRevision) {
                    return@runTransaction CasMutationOutcome.AlreadyCommitted(
                        serverRevision = serverRev,
                        serverDeleted = true,
                        serverUpdatedAtMs = (existing.updatedAt?.seconds ?: 0L) * 1000L,
                        serverRecord = existing
                    )
                }
                val nextRev = serverRev + 1L
                tx.update(
                    ref,
                    mapOf(
                        "deleted" to true,
                        "deletedAt" to FieldValue.serverTimestamp(),
                        "revision" to nextRev,
                        "lastOpId" to opId,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                return@runTransaction CasMutationOutcome.Committed(nextRev, true, System.currentTimeMillis(), existing)
            }

            if (existing != null && serverDeleted && !isExplicitResave && serverRev > expectedServerRevision) {
                return@runTransaction CasMutationOutcome.ServerTombstoneWon(
                    serverRevision = serverRev,
                    serverUpdatedAtMs = (existing.deletedAt?.seconds ?: existing.updatedAt?.seconds ?: 0L) * 1000L
                )
            }

            if (existing == null) {
                val payload = mapOf(
                    "userId" to uid.take(128),
                    "query" to localEntity.query.trim().take(200),
                    "category" to localEntity.category,
                    "deleted" to false,
                    "revision" to 1L,
                    "lastOpId" to opId,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                tx.set(ref, payload)
                CasMutationOutcome.Committed(1L, false, System.currentTimeMillis(), null)
            } else {
                val nextRev = serverRev + 1L
                tx.update(
                    ref,
                    mapOf(
                        "category" to localEntity.category,
                        "deleted" to false,
                        "deletedAt" to FieldValue.delete(),
                        "revision" to nextRev,
                        "lastOpId" to opId,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                CasMutationOutcome.Committed(nextRev, false, System.currentTimeMillis(), existing)
            }
        }.await()
    }

    override suspend fun runMirrorMutation(
        uid: String,
        cloudDocId: String,
        opId: String,
        expectedServerRevision: Long,
        localEntity: MirrorConfigEntity
    ): CasMutationOutcome<MirrorConfigRecord> {
        val ref = db.collection("users").document(uid).collection("mirror_configs").document(cloudDocId)
        val cfg = localEntity.toMirrorConfig()
        return db.runTransaction { tx ->
            val snap = tx.get(ref)
            val existing = if (snap.exists()) {
                snap.toObject(MirrorConfigRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null

            if (existing != null && existing.lastOpId == opId) {
                return@runTransaction CasMutationOutcome.AlreadyCommitted(
                    serverRevision = existing.effectiveRevision,
                    serverDeleted = false,
                    serverUpdatedAtMs = (existing.updatedAt?.seconds ?: 0L) * 1000L,
                    serverRecord = existing
                )
            }

            val serverRev = existing?.effectiveRevision ?: 0L
            if (existing == null) {
                val payload = MirrorConfigRecord.toCreateMap(uid, cfg) + mapOf(
                    "revision" to 1L,
                    "lastOpId" to opId
                )
                tx.set(ref, payload)
                CasMutationOutcome.Committed(1L, false, System.currentTimeMillis(), null)
            } else {
                val nextRev = serverRev + 1L
                val updates = MirrorConfigRecord.toUpdateMap(cfg) + mapOf(
                    "revision" to nextRev,
                    "lastOpId" to opId
                )
                tx.update(ref, updates)
                CasMutationOutcome.Committed(nextRev, false, System.currentTimeMillis(), existing)
            }
        }.await()
    }

    private fun buildBookmarkMap(
        uid: String,
        entity: BookmarkEntity,
        revision: Long,
        deleted: Boolean,
        opId: String,
        includeCreatedAt: Boolean
    ): Map<String, Any> = buildMap {
        put("userId", uid.take(128))
        put("title", entity.title.trim().ifBlank { "Untitled Torrent" }.take(500))
        put("source", entity.source)
        put("category", entity.category)
        put("sizeLabel", entity.sizeLabel.take(64))
        put("sizeBytes", entity.sizeBytes.coerceAtLeast(0L))
        put("seeders", entity.seeders.toLong().coerceAtLeast(0L))
        put("leechers", entity.leechers.toLong().coerceAtLeast(0L))
        put("uploadDateLabel", entity.uploadDateLabel.take(64))
        put("magnetUri", entity.magnetUri.take(4000))
        put("infoHash", entity.canonicalHashHex.ifBlank { entity.infoHash }.take(128))
        put("detailPageUrl", entity.detailPageUrl.take(1000))
        put("qualityTags", entity.qualityTagNames().take(10))
        put("revision", revision)
        put("deleted", deleted)
        put("lastOpId", opId)
        put("canonicalIdentity", entity.canonicalKey.take(200))
        put("legacyDocId", entity.legacyDocId.take(128))
        if (deleted) {
            put("deletedAt", FieldValue.serverTimestamp())
        }
        if (includeCreatedAt) {
            put("createdAt", FieldValue.serverTimestamp())
        }
        put("updatedAt", FieldValue.serverTimestamp())
    }

    companion object {
        fun fromContext(context: android.content.Context): FirebaseFirestoreGateway {
            val dbId = context.getString(R.string.firestore_database_id)
            return FirebaseFirestoreGateway { FirebaseFirestore.getInstance(dbId) }
        }
    }
}
