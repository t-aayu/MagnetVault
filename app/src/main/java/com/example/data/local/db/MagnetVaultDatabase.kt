package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookmarkEntity::class,
        ProviderListingEntity::class,
        IdentityAliasEntity::class,
        CollectionEntity::class,
        CollectionBookmarkCrossRef::class,
        SearchHistoryEntity::class,
        CustomSiteEntity::class,
        MirrorConfigEntity::class,
        MigrationStatusEntity::class,
        SyncOutboxEntity::class,
        CloudRecordLinkEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class MagnetVaultDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun collectionDao(): CollectionDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun customSiteDao(): CustomSiteDao
    abstract fun mirrorConfigDao(): MirrorConfigDao
    abstract fun migrationStatusDao(): MigrationStatusDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val DATABASE_NAME = "magnetvault_local.db"

        @Volatile
        private var INSTANCE: MagnetVaultDatabase? = null

        fun getInstance(context: Context): MagnetVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MagnetVaultDatabase::class.java,
                    DATABASE_NAME
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun createInMemory(context: Context): MagnetVaultDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                MagnetVaultDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }

        internal fun resetForTesting(context: Context) {
            synchronized(this) {
                runCatching { INSTANCE?.close() }
                INSTANCE = null
                runCatching { context.applicationContext.deleteDatabase(DATABASE_NAME) }
            }
        }
    }
}
