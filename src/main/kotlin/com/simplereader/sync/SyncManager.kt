package com.simplereader.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.room.InvalidationTracker
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.FlowPreview
import java.io.File

import com.simplereader.util.MiscUtil
import com.simplereader.data.ReaderDatabase
import com.simplereader.bookmark.Bookmark
import com.simplereader.highlight.Highlight
import com.simplereader.note.Note

class SyncManager private constructor (ctx:Context) {
    private val appContext = ctx.applicationContext

    // Build DB/DAOs/repos on demand (process-wide singletons under the hood)
    private val db by lazy { ReaderDatabase.getInstance(appContext) }

    // Any push observers (Flows) we attach when enabled
    private val pushJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()

    // WorkManager names/tags used by this manager
    private val wm by lazy { WorkManager.getInstance(appContext) }
    private val started = AtomicBoolean(false)

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private val syncMutex = Mutex()

    companion object {
        private val TAG: String = MiscUtil::class.java.simpleName

        const val TAG_SYNC = "SR_SYNC_TAG"
        const val WORK_SYNC_NOW = "SR_SYNC_NOW"

        @Volatile
        private var INSTANCE: SyncManager? = null

        fun getInstance(context: Context): SyncManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    suspend fun start() {
        if (!started.compareAndSet(false, true)) return

        // do some housekeeping
        housekeepBooks()

        // Observe settings; enable/disable sync as they become valid.
        db.settingsDao().observeSettings()
            .onEach { s ->
                val newConfig = ServerConfig.fromSettings(s)
                TokenManager.updateServerConfig(appContext, newConfig)
                enableSyncing()
            }
            .launchIn(scope)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        scope.cancel() // cancels our observing Settings, but WorkManager jobs already enqueued keep running
    }

    //////////////////////////////////////////////////////////////////
    // enable the synchronisation process
    // note: if we can't get a valid token (ie connect to server), then
    //       syncing will be disabled
    //////////////////////////////////////////////////////////////////
    private suspend fun enableSyncing() {

        disableSyncing()  // make sure everything is cleared, before we enable

        // STEP ONE: login to acquire token
        //           note: this call to getToken() will login
        //                 & get a new token because disableSync() just cleared it
        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            return  // syncing is disabled until enableSync() is called again
        }

        // STEP TWO: attach DB observers for push & periodic work
        pushJobs += attachPushObserversForPush()

        // STEP THREE: kick off background periodic schedule
        SyncTickManager.scheduleNextTick(appContext, null)

        // STEP FOUR: immediately kick a one-off full sync using unique work
        wm.enqueueUniqueWork(
            WORK_SYNC_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag(TAG_SYNC)
                .build()
        )
    }

    //////////////////////////////////////////////////////////////////
    // disable the synchronisation process
    //////////////////////////////////////////////////////////////////
    private suspend fun disableSyncing() {
        // pause/clear auth, cancel observers if you keep any, etc.
        // Cancel push observers (if any)
        pushJobs.forEach { job -> runCatching { job.cancel() } }
        pushJobs.clear()

        // cancel any scheduled/ongoing work for these managers
        wm.cancelUniqueWork(WORK_SYNC_NOW)
        wm.cancelAllWorkByTag(TAG_SYNC)
        SyncTickManager.cancelWork(wm)

        // Drop in-memory auth/session
        TokenManager.clearToken()
    }

    ///////////////////////////////////////////////////////////////////////////////////////
    // observe the local Room tables book_data/bookmark/highlight/note for any edits.
    // When there is an edit, it "debounces" (wait 1.5secs) and then queues on WorkManager
    // "sync now" job, which pushes the changes to the simplereaderd server
    // note: if syncing is disabled, the observer is cancelled & so stops listening.
    ///////////////////////////////////////////////////////////////////////////////////////
    @OptIn(FlowPreview::class)
    private fun attachPushObserversForPush(): Job {

        // collate list of tables to be synced after debounce period has expired
        val pending = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // use Room's InvalidationTracker as a Flow, debounce, then enqueue a sync
        return callbackFlow {
            val observer = object : InvalidationTracker.Observer(SyncTables.ALL) {
                override fun onInvalidated(tables: Set<String>) {
                    pending += tables       // add invalidated tables to list of pending syncs
                    trySend(Unit)
                }
            }
            db.invalidationTracker.addObserver(observer)
            awaitClose { db.invalidationTracker.removeObserver(observer) }
        }
            .debounce(1500)
            .onEach {

                // copy and clear list of pending tables to update
                val changed: Set<String> =
                    synchronized(pending) {
                        val snap = pending.toSet()
                        pending.clear()
                        snap
                    }
                if (changed.isNotEmpty()) {
                    // hand the changed tablenames to the worker
                    val tables = androidx.work.Data.Builder()
                        .putStringArray("changed_tables", changed.toTypedArray())
                        .build()

                    wm.enqueueUniqueWork(
                        WORK_SYNC_NOW,
                        ExistingWorkPolicy.KEEP, // coalesce if one is already queued
                        OneTimeWorkRequestBuilder<SyncWorker>()
                            .setInputData(tables)
                            .addTag(TAG_SYNC)
                            .build()
                    )
                }
            }
            .launchIn(scope)
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // func used by SettingsBottomSheet when user presses "Sync Now" button
    // RETURNS: false if a sync is already running
    ////////////////////////////////////////////////////////////////////////////////////////
    fun syncNow(): Boolean {
        if (!syncMutex.tryLock()) return false // already running (in case user presses button multiple times)

        return try {
            doFullSync()
            true
        } finally {
            syncMutex.unlock()
        }
    }

    //
    // queue the actual work as unique one-off job
    // note: KEEP policy prevents overlapping runs in the WorkManager
    // note: no "changed_tables" passed, so all tables will be synced
    //
    private fun doFullSync() {
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WORK_SYNC_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().addTag(TAG_SYNC).build()
        )
    }

    // check if any books have been deleted, if so mark them for delete in our records too
    private suspend fun housekeepBooks() {
        val db = ReaderDatabase.getInstance(appContext)
        val daoBook = db.bookDao()
        val syncDao = db.syncDao()

        val allBooks = daoBook.getAllBooks()
        for (book in allBooks) {
            if (!File(book.pubFile).exists()) {
                // book no longer exists, so mark it for deletion from our local db
                // note: during the sync, the server will "soft" delete this on the server
                // note: if record already exists, this will update the deletedAt timestamp
                syncDao.addDeletedRecord(
                    DeletedRecordsEntity(
                        SyncTables.BOOK_DATA,
                        book.bookId,
                        deletedAt = System.currentTimeMillis()
                    )
                )
            }
        }

    }

    suspend fun flagBookmarkDeleted(bookmark: Bookmark) {
        val db = ReaderDatabase.getInstance(appContext)
        db.syncDao().addDeletedRecord(
            DeletedRecordsEntity(
                SyncTables.BOOKMARK,
                bookmark.bookId,
                bookmark.id,
                deletedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun flagHighlightDeleted(highlight: Highlight) {
        val db = ReaderDatabase.getInstance(appContext)
        db.syncDao().addDeletedRecord(
            DeletedRecordsEntity(
                SyncTables.HIGHLIGHT,
                highlight.bookId,
                highlight.id,
                deletedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun flagNoteDeleted(note: Note) {
        val db = ReaderDatabase.getInstance(appContext)
        db.syncDao().addDeletedRecord(
            DeletedRecordsEntity(
                SyncTables.NOTE,
                note.bookId,
                note.id,
                deletedAt = System.currentTimeMillis()
            )
        )
    }

}