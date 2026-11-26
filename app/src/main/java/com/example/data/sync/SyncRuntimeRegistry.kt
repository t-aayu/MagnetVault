package com.example.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.auth.GoogleAuthManager
import com.example.data.local.LegacyDataMigrator
import com.example.data.local.LocalPrefsStore
import com.example.data.local.VaultLocalRepository
import com.example.data.local.db.MagnetVaultDatabase

class VaultRuntimeContainer(
    val db: MagnetVaultDatabase,
    val prefsStore: LocalPrefsStore,
    val migrator: LegacyDataMigrator,
    val localRepo: VaultLocalRepository,
    val syncEngine: FirestoreSyncEngine
)

object SyncRuntimeRegistry {
    @Volatile
    private var instance: VaultRuntimeContainer? = null

    fun getContainer(context: Context): VaultRuntimeContainer {
        val appContext = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: createContainer(appContext).also { instance = it }
        }
    }

    internal fun resetForTesting() {
        synchronized(this) {
            instance = null
        }
    }

    private fun createContainer(appContext: Context): VaultRuntimeContainer {
        val db = MagnetVaultDatabase.getInstance(appContext)
        val prefsStore = LocalPrefsStore(appContext)
        val migrator = LegacyDataMigrator(db, prefsStore)
        val localRepo = VaultLocalRepository(db, migrator)
        val gateway = if (GoogleAuthManager.isFirebaseConfigured(appContext)) {
            FirebaseFirestoreGateway.fromContext(appContext)
        } else {
            null
        }
        val syncEngine = FirestoreSyncEngine(
            db = db,
            localRepo = localRepo,
            prefsStore = prefsStore,
            gateway = gateway,
            currentAuthUidProvider = { GoogleAuthManager.currentUserOrNull(appContext)?.uid },
            isOnlineProvider = { isNetworkConnected(appContext) },
            workScheduler = WorkManagerSyncScheduler(appContext)
        )
        return VaultRuntimeContainer(
            db = db,
            prefsStore = prefsStore,
            migrator = migrator,
            localRepo = localRepo,
            syncEngine = syncEngine
        )
    }

    fun isNetworkConnected(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }
}
