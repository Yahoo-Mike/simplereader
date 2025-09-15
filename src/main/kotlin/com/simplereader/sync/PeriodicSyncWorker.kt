package com.simplereader.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.simplereader.data.ReaderDatabase
import java.util.concurrent.TimeUnit


//
// worker that runs on a cadence and re-schedules itself if sync is still enabled.
//
class PeriodicSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val db = ReaderDatabase.getInstance(ctx)
        val settings = db.settingsDao().getSettings()

        // note: sync is still enabled when server is fully configured
        val config = ServerConfig.fromSettings(settings)

        if (config?.isFullyConfigured() == true) {
            // kick off a real sync pass (unique, so multiple ticks coalesce)
            // note: no "changed_tables" passed, so all tables will be synced

            WorkManager.getInstance(ctx).enqueueUniqueWork(
                SyncManager.WORK_SYNC_NOW,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .addTag(SyncManager.TAG_SYNC)
                    .build()
            )

            // Schedule the *next* tick (only if still enabled)
            scheduleNextTick(ctx)
        }

        return Result.success()
    }

    private fun scheduleNextTick(context: Context, delayMinutes: Long = 15) {
        val tick = OneTimeWorkRequestBuilder<PeriodicSyncWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .addTag(SyncManager.TAG_SYNC)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(SyncManager.WORK_SYNC_PERIODIC, ExistingWorkPolicy.REPLACE, tick)
    }
}