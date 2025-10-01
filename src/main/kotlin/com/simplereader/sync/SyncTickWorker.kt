package com.simplereader.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.simplereader.data.ReaderDatabase
import com.simplereader.settings.SettingsEntity
import java.util.concurrent.TimeUnit

//
// helper class to allow scheduling of periodic syncs
//
object SyncTickManager {
    private const val SYNC_TICK = "SYNC_PERIODIC"   // unique name for the tick chain
    private const val TAG_TICK = "SYNC_TAG_TICK"    // tag only for the tick

    // self-rescheduling one-off sync instead of true periodic work (using WorkManager)
    // note: call this after a successful enable to schedule the *next* tick
    fun scheduleNextTick(wm: WorkManager, delay: Int) {

        if (delay <= SettingsEntity.NEVER) {
            // stop scheduling entirely
            cancelWork(wm)
            return
        }

        val tick = OneTimeWorkRequestBuilder<SyncTickWorker>()
            .setInitialDelay(delay.toLong(), TimeUnit.MINUTES)
            .addTag(TAG_TICK)
            .build()

        // REPLACE ensures only one future tick is queued at a time
        // note: DO NOT cancel running work here; this only schedules the next wake-up.
        wm.enqueueUniqueWork(SYNC_TICK, ExistingWorkPolicy.REPLACE, tick)
    }

    // note: this hits the db again
    suspend fun scheduleNextTick(ctx:Context, inSettings: SettingsEntity?) {
        val db = ReaderDatabase.getInstance(ctx)
        val settings = inSettings ?: db.settingsDao().getSettings()
        val configured = ServerConfig.fromSettings(settings)?.isFullyConfigured() == true

        if (!configured) {
            // no point continuing, stop scheduling entirely
            cancelWork(ctx)
        } else {
            // Replace any pending tick with the new delay
            val delay = settings?.syncFrequency ?: SettingsEntity.NEVER
            scheduleNextTick(ctx,delay)
        }

    }

    fun scheduleNextTick(ctx: Context, delay: Int) =
        scheduleNextTick(WorkManager.getInstance(ctx),delay)

    // stop scheduling entirely
    fun cancelWork(wm:WorkManager) {
        wm.cancelUniqueWork( SYNC_TICK )
        wm.cancelAllWorkByTag( TAG_TICK )
    }
    fun cancelWork(ctx: Context) =
        cancelWork(WorkManager.getInstance(ctx))

    // read settings, enqueue unique SyncWorker, maybe schedule next tick
    suspend fun onTick(ctx: Context) {
        val db = ReaderDatabase.getInstance(ctx)
        val settings = db.settingsDao().getSettings()
        val wm = WorkManager.getInstance(ctx)

        // note: sync is still enabled when server is fully configured
        val config = ServerConfig.fromSettings(settings)

        if ((config?.isFullyConfigured() == true) && (config.frequency > SettingsEntity.NEVER) ) {
            // kick off a real sync pass (unique, so multiple ticks coalesce)
            // note: no "changed_tables" passed, so all tables will be synced

            wm.enqueueUniqueWork(
                SyncManager.WORK_SYNC_NOW,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .addTag(SyncManager.TAG_SYNC)
                    .build()
            )

            // Schedule the *next* tick
            val delay = settings?.syncFrequency ?: SettingsEntity.NEVER
            scheduleNextTick(wm,delay)
        }

    }
}

class SyncTickWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        SyncTickManager.onTick(applicationContext)
        return Result.success()
    }
}
