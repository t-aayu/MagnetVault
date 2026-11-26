package com.example.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Transaction
    @Query(
        """
        SELECT * FROM bookmarks
        WHERE is_deleted = 0
          AND (origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND origin_scope = :accountScope))
        ORDER BY updated_at_ms DESC
        """
    )
    fun observeActiveBookmarksWithCollections(accountScope: String?): Flow<List<BookmarkWithCollections>>

    @Transaction
    @Query(
        """
        SELECT * FROM bookmarks
        WHERE is_deleted = 0
          AND (origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND origin_scope = :accountScope))
        ORDER BY updated_at_ms DESC
        """
    )
    suspend fun getActiveBookmarksWithCollectionsOnce(accountScope: String?): List<BookmarkWithCollections>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE origin_scope = 'DEVICE'
          AND is_deleted = 0
        ORDER BY updated_at_ms DESC
        """
    )
    suspend fun getActiveDeviceBookmarks(): List<BookmarkEntity>

    @Query(
        """
        SELECT canonical_key FROM bookmarks
        WHERE is_deleted = 0
          AND (origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND origin_scope = :accountScope))
        UNION
        SELECT ia.alias_key FROM identity_aliases ia
        INNER JOIN bookmarks b ON b.local_id = ia.bookmark_local_id
        WHERE b.is_deleted = 0
          AND (b.origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND b.origin_scope = :accountScope))
        """
    )
    fun observeActiveBookmarkMatchKeys(accountScope: String?): Flow<List<String>>

    @Query("SELECT * FROM bookmarks WHERE local_id = :localId LIMIT 1")
    suspend fun getByLocalId(localId: String): BookmarkEntity?

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE origin_scope = :originScope AND canonical_key = :canonicalKey
        LIMIT 1
        """
    )
    suspend fun findByScopeAndCanonicalKey(originScope: String, canonicalKey: String): BookmarkEntity?

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE canonical_key = :canonicalKey
          AND (origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND origin_scope = :accountScope))
        ORDER BY CASE WHEN origin_scope = 'DEVICE' THEN 0 ELSE 1 END, is_deleted ASC
        LIMIT 1
        """
    )
    suspend fun findVisibleOrTombstoneByCanonicalKey(
        canonicalKey: String,
        accountScope: String?
    ): BookmarkEntity?

    @Query(
        """
        SELECT b.* FROM bookmarks b
        INNER JOIN identity_aliases ia ON ia.bookmark_local_id = b.local_id
        WHERE ia.alias_key = :aliasKey
          AND (b.origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND b.origin_scope = :accountScope))
        ORDER BY CASE WHEN b.origin_scope = 'DEVICE' THEN 0 ELSE 1 END, b.is_deleted ASC
        LIMIT 1
        """
    )
    suspend fun findByAliasInVisibleScopes(aliasKey: String, accountScope: String?): BookmarkEntity?

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE legacy_doc_id = :legacyDocId
          AND (origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND origin_scope = :accountScope))
        ORDER BY CASE WHEN origin_scope = 'DEVICE' THEN 0 ELSE 1 END, is_deleted ASC
        LIMIT 1
        """
    )
    suspend fun findByLegacyDocIdInVisibleScopes(
        legacyDocId: String,
        accountScope: String?
    ): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmarkIgnore(entity: BookmarkEntity): Long

    @Update
    suspend fun updateBookmark(entity: BookmarkEntity)

    suspend fun upsertBookmark(entity: BookmarkEntity) {
        if (insertBookmarkIgnore(entity) == -1L) {
            updateBookmark(entity)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIdentityAlias(alias: IdentityAliasEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviderListing(listing: ProviderListingEntity)

    @Query("SELECT * FROM identity_aliases WHERE bookmark_local_id = :bookmarkLocalId")
    suspend fun getAliasesForBookmark(bookmarkLocalId: String): List<IdentityAliasEntity>

    @Query("SELECT * FROM provider_listings WHERE bookmark_local_id = :bookmarkLocalId")
    suspend fun getProviderListingsForBookmark(bookmarkLocalId: String): List<ProviderListingEntity>
}

@Dao
interface CollectionDao {
    @Query(
        """
        SELECT c.collection_id, c.name, c.normalized_name, c.created_at_ms, c.updated_at_ms,
               COUNT(CASE WHEN b.local_id IS NOT NULL AND b.is_deleted = 0
                    AND (b.origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND b.origin_scope = :accountScope))
                    THEN 1 END) AS bookmark_count
        FROM collections c
        LEFT JOIN collection_bookmarks cb ON c.collection_id = cb.collection_id
        LEFT JOIN bookmarks b ON cb.bookmark_local_id = b.local_id
        GROUP BY c.collection_id
        ORDER BY c.updated_at_ms DESC
        """
    )
    fun observeCollectionsWithCount(accountScope: String?): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections ORDER BY updated_at_ms DESC")
    suspend fun getAllCollectionsOnce(): List<CollectionEntity>

    @Query("SELECT * FROM collection_bookmarks")
    suspend fun getAllCrossRefsOnce(): List<CollectionBookmarkCrossRef>

    @Query("SELECT * FROM collections WHERE collection_id = :collectionId LIMIT 1")
    suspend fun getById(collectionId: String): CollectionEntity?

    @Query("SELECT * FROM collections WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionIgnore(collection: CollectionEntity): Long

    @Update
    suspend fun updateCollection(collection: CollectionEntity)

    suspend fun upsertCollection(collection: CollectionEntity) {
        if (insertCollectionIgnore(collection) == -1L) {
            updateCollection(collection)
        }
    }

    @Query("DELETE FROM collections WHERE collection_id = :collectionId")
    suspend fun deleteCollectionById(collectionId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookmarkToCollection(ref: CollectionBookmarkCrossRef)

    @Query(
        "DELETE FROM collection_bookmarks WHERE collection_id = :collectionId AND bookmark_local_id = :bookmarkLocalId"
    )
    suspend fun removeBookmarkFromCollection(collectionId: String, bookmarkLocalId: String)

    @Query("SELECT collection_id FROM collection_bookmarks WHERE bookmark_local_id = :bookmarkLocalId")
    suspend fun getCollectionIdsForBookmark(bookmarkLocalId: String): List<String>

    @Query("DELETE FROM collection_bookmarks WHERE bookmark_local_id = :bookmarkLocalId")
    suspend fun clearCollectionsForBookmark(bookmarkLocalId: String)
}

@Dao
interface SearchHistoryDao {
    @Query(
        """
        SELECT * FROM search_history
        WHERE is_deleted = 0
          AND (origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND origin_scope = :accountScope))
        ORDER BY updated_at_ms DESC
        LIMIT :limit
        """
    )
    fun observeActiveHistory(accountScope: String?, limit: Int = 50): Flow<List<SearchHistoryEntity>>

    @Query(
        """
        SELECT * FROM search_history
        WHERE is_deleted = 0
          AND (origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND origin_scope = :accountScope))
        ORDER BY updated_at_ms DESC
        """
    )
    suspend fun getActiveHistoryOnce(accountScope: String?): List<SearchHistoryEntity>

    @Query("SELECT * FROM search_history WHERE entry_id = :entryId LIMIT 1")
    suspend fun getByEntryId(entryId: String): SearchHistoryEntity?

    @Query(
        """
        SELECT * FROM search_history
        WHERE origin_scope = :originScope AND normalized_query = :normalizedQuery
        LIMIT 1
        """
    )
    suspend fun findByScopeAndNormalizedQuery(
        originScope: String,
        normalizedQuery: String
    ): SearchHistoryEntity?

    @Query(
        """
        SELECT * FROM search_history
        WHERE cloud_doc_id = :cloudDocId
          AND (origin_scope = 'DEVICE' OR (:accountScope IS NOT NULL AND origin_scope = :accountScope))
        ORDER BY CASE WHEN origin_scope = 'DEVICE' THEN 0 ELSE 1 END
        LIMIT 1
        """
    )
    suspend fun findByCloudDocIdInVisibleScopes(
        cloudDocId: String,
        accountScope: String?
    ): SearchHistoryEntity?

    @Query(
        """
        SELECT * FROM search_history
        WHERE origin_scope = 'DEVICE' AND is_deleted = 0
        ORDER BY updated_at_ms DESC
        """
    )
    suspend fun getActiveDeviceHistory(): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entity: SearchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistoryIgnore(entity: SearchHistoryEntity): Long
}

@Dao
interface CustomSiteDao {
    @Query("SELECT * FROM custom_sites ORDER BY created_at_ms DESC")
    fun observeCustomSites(): Flow<List<CustomSiteEntity>>

    @Query("SELECT * FROM custom_sites ORDER BY created_at_ms DESC")
    suspend fun getAllCustomSitesOnce(): List<CustomSiteEntity>

    @Query("SELECT * FROM custom_sites WHERE normalized_url = :normalizedUrl LIMIT 1")
    suspend fun findByNormalizedUrl(normalizedUrl: String): CustomSiteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomSite(site: CustomSiteEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCustomSiteIgnore(site: CustomSiteEntity): Long

    @Query("DELETE FROM custom_sites WHERE site_id = :siteId")
    suspend fun deleteCustomSite(siteId: String)
}

@Dao
interface MirrorConfigDao {
    @Query("SELECT * FROM mirror_configs")
    fun observeMirrorConfigs(): Flow<List<MirrorConfigEntity>>

    @Query("SELECT * FROM mirror_configs")
    suspend fun getAllMirrorsOnce(): List<MirrorConfigEntity>

    @Query("SELECT * FROM mirror_configs WHERE provider = :provider LIMIT 1")
    suspend fun getByProvider(provider: String): MirrorConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMirrorConfig(entity: MirrorConfigEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMirrorIgnore(entity: MirrorConfigEntity): Long
}

@Dao
interface MigrationStatusDao {
    @Query("SELECT * FROM migration_status WHERE id = 1 LIMIT 1")
    fun observeStatus(): Flow<MigrationStatusEntity?>

    @Query("SELECT * FROM migration_status WHERE id = 1 LIMIT 1")
    suspend fun getStatus(): MigrationStatusEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStatus(status: MigrationStatusEntity)
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_outbox WHERE account_uid = :accountUid ORDER BY created_at_ms ASC")
    fun observeOutboxForAccount(accountUid: String): Flow<List<SyncOutboxEntity>>

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE account_uid = :accountUid AND status != 'ACTION_NEEDED'
        ORDER BY created_at_ms ASC
        """
    )
    suspend fun getRunnableOutboxForAccount(accountUid: String): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE account_uid = :accountUid ORDER BY created_at_ms ASC")
    suspend fun getAllOutboxForAccount(accountUid: String): List<SyncOutboxEntity>

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE account_uid = :accountUid
          AND entity_type = :entityType
          AND local_entity_id = :localEntityId
          AND cloud_doc_id = :cloudDocId
          AND status = 'PENDING'
        LIMIT 1
        """
    )
    suspend fun findPendingOp(
        accountUid: String,
        entityType: String,
        localEntityId: String,
        cloudDocId: String
    ): SyncOutboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutbox(op: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE op_id = :opId")
    suspend fun deleteOutboxOp(opId: String)

    @Query(
        """
        SELECT * FROM cloud_record_links
        WHERE account_uid = :accountUid AND entity_type = :entityType AND cloud_doc_id = :cloudDocId
        LIMIT 1
        """
    )
    suspend fun getRecordLink(
        accountUid: String,
        entityType: String,
        cloudDocId: String
    ): CloudRecordLinkEntity?

    @Query(
        """
        SELECT * FROM cloud_record_links
        WHERE account_uid = :accountUid AND entity_type = :entityType AND local_entity_id = :localEntityId
        ORDER BY server_revision DESC
        LIMIT 1
        """
    )
    suspend fun getRecordLinkByLocalId(
        accountUid: String,
        entityType: String,
        localEntityId: String
    ): CloudRecordLinkEntity?

    @Query("SELECT * FROM cloud_record_links WHERE account_uid = :accountUid AND entity_type = :entityType")
    suspend fun getAllRecordLinksForType(accountUid: String, entityType: String): List<CloudRecordLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecordLink(link: CloudRecordLinkEntity)

    @Query(
        """
        UPDATE sync_outbox
        SET status = 'PENDING', attempt_count = 0, last_error_category = NULL, updated_at_ms = :nowMs
        WHERE account_uid = :accountUid AND status = 'ACTION_NEEDED'
        """
    )
    suspend fun retryActionNeededForAccount(accountUid: String, nowMs: Long)
}
