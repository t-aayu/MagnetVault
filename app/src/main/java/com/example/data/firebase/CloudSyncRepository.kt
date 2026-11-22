package com.example.data.firebase

import android.content.Context
import com.example.R
import com.example.data.local.LocalPrefsStore
import com.example.model.BookmarkRecord
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.MirrorConfigRecord
import com.example.model.QualityAndHealthParser
import com.example.model.SearchHistoryRecord
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Dual-mode repository:
 * - When [isCloudMode] is true (user signed in with Google): reads & writes per-user Firestore collections.
 * - When [isCloudMode] is false (Guest / Local Mode): persists strictly in [LocalPrefsStore] without ever touching Firestore.
 */
class CloudSyncRepository(
    private val appContext: Context,
    private val prefsStore: LocalPrefsStore,
    val isCloudMode: Boolean
) {
    private val auth: FirebaseAuth? by lazy {
        if (FirebaseApp.getApps(appContext).isNotEmpty()) Firebase.auth else null
    }

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(
            appContext.getString(R.string.firestore_database_id)
        )
    }

    private fun requireUserId(): String {
        val uid = auth?.currentUser?.uid
        if (uid.isNullOrBlank()) {
            throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        }
        return uid
    }

    // --- BOOKMARKS ---
    fun observeBookmarks(): Flow<List<BookmarkRecord>> {
        if (!isCloudMode) return prefsStore.localBookmarksFlow
        return flow {
            val uid = requireUserId()
            val path = "users/$uid/bookmarks"
            emitAll(
                db.collection("users").document(uid).collection("bookmarks")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .snapshots()
                    .map { snap ->
                        snap.toObjects(
                            BookmarkRecord::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }
                    .catch { error ->
                        val fsErr = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (fsErr == null && error is CancellationException) throw error
                        val toLog = fsErr ?: (error as? Exception)
                        if (toLog != null && auth?.currentUser?.uid == uid) {
                            handleFirestoreError(toLog, OperationType.LIST, path)
                        }
                        throw error
                    }
            )
        }
    }

    suspend fun addBookmark(torrent: TorrentResult): Result<Unit> {
        val docId = bookmarkDocId(torrent)
        if (!isCloudMode) {
            return runCatching { prefsStore.addLocalBookmark(docId, torrent) }
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauthenticated"}/bookmarks/$docId"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("bookmarks").document(docId)
            docPath = ref.path
            val existing = ref.get().await()
            if (existing.exists()) {
                val updatePayload = mapOf(
                    "magnetUri" to (torrent.magnetUri ?: "").take(4000),
                    "seeders" to torrent.seeders.toLong().coerceAtLeast(0L),
                    "leechers" to torrent.leechers.toLong().coerceAtLeast(0L),
                    "sizeLabel" to torrent.sizeLabel.take(64),
                    "sizeBytes" to torrent.sizeBytes.coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.update(updatePayload).await()
            } else {
                val createPayload = BookmarkRecord.toCreateMap(uid, torrent)
                ref.set(createPayload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun removeBookmark(bookmarkId: String): Result<Unit> {
        val safeId = QualityAndHealthParser.sanitizeDocumentId(bookmarkId)
        if (!isCloudMode) {
            return runCatching { prefsStore.removeLocalBookmark(safeId) }
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauthenticated"}/bookmarks/$safeId"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("bookmarks").document(safeId)
            docPath = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun bookmarkDocId(torrent: TorrentResult): String {
        val seed = torrent.infoHash.ifBlank { "${torrent.source.name}_${torrent.id}" }
        return QualityAndHealthParser.sanitizeDocumentId("bm_$seed")
    }

    // --- SEARCH HISTORY ---
    fun observeSearchHistory(): Flow<List<SearchHistoryRecord>> {
        if (!isCloudMode) return prefsStore.localSearchHistoryFlow
        return flow {
            val uid = requireUserId()
            val path = "users/$uid/search_history"
            emitAll(
                db.collection("users").document(uid).collection("search_history")
                    .orderBy("updatedAt", Query.Direction.DESCENDING)
                    .limit(20)
                    .snapshots()
                    .map { snap ->
                        snap.toObjects(
                            SearchHistoryRecord::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }
                    .catch { error ->
                        val fsErr = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (fsErr == null && error is CancellationException) throw error
                        val toLog = fsErr ?: (error as? Exception)
                        if (toLog != null && auth?.currentUser?.uid == uid) {
                            handleFirestoreError(toLog, OperationType.LIST, path)
                        }
                        throw error
                    }
            )
        }
    }

    suspend fun recordSearchQuery(query: String, category: TorrentCategory): Result<Unit> {
        val trimmed = query.trim().take(200)
        if (trimmed.isBlank()) return Result.success(Unit)
        if (!isCloudMode) {
            return runCatching { prefsStore.recordLocalSearchQuery(trimmed, category) }
        }
        val entryId = QualityAndHealthParser.sanitizeDocumentId(
            "q_${trimmed.lowercase(Locale.US).hashCode().toUInt()}"
        )
        var docPath = "users/${auth?.currentUser?.uid ?: "unauthenticated"}/search_history/$entryId"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("search_history").document(entryId)
            docPath = ref.path
            val existing = ref.get().await()
            if (existing.exists()) {
                ref.update(
                    mapOf(
                        "category" to category.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                ref.set(SearchHistoryRecord.toCreateMap(uid, trimmed, category)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteSearchHistoryEntry(entryId: String): Result<Unit> {
        val safeId = QualityAndHealthParser.sanitizeDocumentId(entryId)
        if (!isCloudMode) {
            return runCatching { prefsStore.deleteLocalSearchHistory(safeId) }
        }
        var docPath = "users/${auth?.currentUser?.uid ?: "unauthenticated"}/search_history/$safeId"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid).collection("search_history").document(safeId)
            docPath = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun clearAllSearchHistory(): Result<Unit> {
        if (!isCloudMode) {
            return runCatching { prefsStore.clearAllLocalSearchHistory() }
        }
        var path = "users/${auth?.currentUser?.uid ?: "unauthenticated"}/search_history"
        return try {
            val uid = requireUserId()
            path = "users/$uid/search_history"
            val docs = db.collection("users").document(uid).collection("search_history").get().await()
            val batch = db.batch()
            docs.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- MIRROR CONFIGS ---
    fun observeMirrorConfigs(): Flow<Map<IndexerSource, MirrorConfig>> {
        if (!isCloudMode) return prefsStore.localMirrorConfigsFlow
        return flow {
            val uid = requireUserId()
            val path = "users/$uid/mirror_configs"
            emitAll(
                db.collection("users").document(uid).collection("mirror_configs")
                    .snapshots()
                    .map { snap ->
                        val saved = snap.toObjects(
                            MirrorConfigRecord::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ).associate { record ->
                            val cfg = record.toMirrorConfig()
                            cfg.provider to cfg
                        }
                        IndexerSource.entries.associateWith { source ->
                            saved[source] ?: MirrorConfig(provider = source)
                        }
                    }
                    .catch { error ->
                        val fsErr = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (fsErr == null && error is CancellationException) throw error
                        val toLog = fsErr ?: (error as? Exception)
                        if (toLog != null && auth?.currentUser?.uid == uid) {
                            handleFirestoreError(toLog, OperationType.LIST, path)
                        }
                        throw error
                    }
            )
        }
    }

    suspend fun saveMirrorConfig(config: MirrorConfig): Result<Unit> {
        if (!isCloudMode) {
            return runCatching { prefsStore.saveLocalMirrorConfig(config) }
        }
        val providerId = config.provider.name
        var docPath = "users/${auth?.currentUser?.uid ?: "unauthenticated"}/mirror_configs/$providerId"
        return try {
            val uid = requireUserId()
            val ref = db.collection("users").document(uid)
                .collection("mirror_configs").document(providerId)
            docPath = ref.path
            val existing = ref.get().await()
            if (existing.exists()) {
                ref.update(MirrorConfigRecord.toUpdateMap(config)).await()
            } else {
                ref.set(MirrorConfigRecord.toCreateMap(uid, config)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun resetAllMirrorsToDefault(): Result<Unit> {
        if (!isCloudMode) {
            return runCatching { prefsStore.resetLocalMirrorsToDefault() }
        }
        return try {
            IndexerSource.entries.forEach { src ->
                val res = saveMirrorConfig(MirrorConfig(provider = src))
                if (res.isFailure) return res
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
