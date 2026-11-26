package com.example.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.auth.GoogleAuthManager
import java.util.concurrent.TimeUnit

class OutboxSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val targetUid = inputData.getString(KEY_ACCOUNT_UID)?.takeIf { it.isNotBlank() }
            ?: return Result.success()

        // Guard against account switch or sign-out while work was queued
        val currentUid = GoogleAuthManager.currentUserOrNull(applicationContext)?.uid
        if (currentUid != targetUid) {
            return Result.success()
        }

        val container = SyncRuntimeRegistry.getContainer(applicationContext)
        val syncPrefs = container.prefsStore.getSyncPrefsForAccount(targetUid)
        if (!syncPrefs.enrolled) {
            return Result.success()
        }

        val outcome = container.syncEngine.drainOutbox(targetUid)
        return when {
            outcome.hadTransientFailure -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_ACCOUNT_UID = "account_uid"

        fun uniqueWorkName(uid: String): String = "magnetvault_sync_$uid"
    }
}

class WorkManagerSyncScheduler(
    private val appContext: Context
) : SyncWorkScheduler {

    override fun enqueueForAccount(uid: String) {
        if (uid.isBlank()) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
            .setConstraints(constraints)
            .setInputData(
                Data.Builder()
                    .putString(OutboxSyncWorker.KEY_ACCOUNT_UID, uid)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                OutboxSyncWorker.uniqueWorkName(uid),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override fun cancelForAccount(uid: String) {
        if (uid.isBlank()) return
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(OutboxSyncWorker.uniqueWorkName(uid))
        }
    }
}
