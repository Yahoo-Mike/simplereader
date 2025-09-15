package com.simplereader.sync

import android.content.Context
import android.util.Log
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

import com.simplereader.util.MiscUtil
import com.simplereader.data.ReaderDatabase
import com.simplereader.model.BookData.Companion.MEDIA_TYPE_EPUB
import com.simplereader.model.BookData.Companion.MEDIA_TYPE_PDF

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class SyncManager private constructor (ctx:Context) {
    private val appContext = ctx.applicationContext

    // Build DB/DAOs/repos on demand (process-wide singletons under the hood)
    private val db by lazy { ReaderDatabase.getInstance(appContext) }
    private val syncDao by lazy { db.syncDao() }
    private val settingsDao by lazy { db.settingsDao() }
    private val bookDao by lazy { db.bookDao() }
    private val bookmarkDao by lazy { db.bookmarkDao() }
    private val highlightDao by lazy { db.highlightDao() }

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

        const val TAG_SYNC = "SYNC_TAG"
        const val WORK_SYNC_NOW = "SYNC_NOW"
        const val WORK_SYNC_PERIODIC = "SYNC_PERIODIC"    // periodic background sync

        @Volatile private var INSTANCE: SyncManager? = null

        fun getInstance(context: Context): SyncManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return

        // Observe settings; enable/disable sync as they become valid.
        settingsDao.observeSettings()
            .onEach { s ->
                val newConfig = ServerConfig.fromSettings(s)
                TokenManager.updateServerConfig(appContext,newConfig)
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
            Log.e(TAG,"failed to get token")
            return  // syncing is disabled until enableSync() is called again
        }

        // STEP TWO: attach DB observers for push & periodic work
        pushJobs += attachPushObserversForPush()
        scheduleSelfReschedulingSync()

        // STEP THREE: immediately kick a one-off full sync using unique work
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
        wm.cancelUniqueWork(WORK_SYNC_PERIODIC)
        wm.cancelAllWorkByTag(TAG_SYNC)

        // Drop in-memory auth/session
        TokenManager.clearToken()
    }

    ///////////////////////////////////////////////////////////////////////////////////////
    // observe the local Room tables book_data/bookmark/highlight for any edits.
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

    // self-rescheduling one-off sync instead of true periodic work (using WorkManager)
    // note: call this after a successful enable to schedule the *next* tick
    private fun scheduleSelfReschedulingSync(delayMinutes: Long = 15) {
        val tick = OneTimeWorkRequestBuilder<PeriodicSyncWorker>()
            .setInitialDelay(delayMinutes, java.util.concurrent.TimeUnit.MINUTES)
            .addTag(TAG_SYNC)
            .build()

        // REPLACE ensures only one future tick is queued at a time
        // we intentionally DO NOT cancel running work here; this only schedules the next wake-up.
        wm.enqueueUniqueWork(WORK_SYNC_PERIODIC, ExistingWorkPolicy.REPLACE, tick)
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

    /////////////////////////////////////////////////////////////////////////////////////
    // POST /getSince {tablename,since,limit}
    // Get all rows that have been update since timestamp, response limited to "limit" rows per response
    //
    //      since:  timestamp in UTC msecs   (use since=0 to get all records from tablename)
    //      limit:  maximum number of rows to send in each response
    // RETURNS: list of ServerRecord objects (to max of limit)
    //          "nextSince", being the next timestamp as advised by server
    data class GetSinceResp(val ok: Boolean, val rows: List<ServerRecord>, val nextSince: Long)
    suspend fun postGetSince(
        table: String,
        since: Long,
        limit: Int,
    ): GetSinceResp? = withContext(Dispatchers.IO) {
        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "postGetSince failed: not connected to server")
            return@withContext GetSinceResp(false,emptyList(),since)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(TAG, "postGetSince failed: server name not known")
            return@withContext GetSinceResp(false,emptyList(),since)
        }

        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        val payload = org.json.JSONObject().apply {
            put("table", table)        // e.g., "book_data"
            put("since", since)        // server-side watermark
            put("limit", limit)        // page size
        }.toString()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/getSince")
            .addHeader("Authorization", "Bearer $token")   // change if your server expects a different header
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val text = resp.body?.string().orEmpty()
                val obj  = org.json.JSONObject(text)

                // ok=false,err=XXX,reason=YY
                if (!obj.getBoolean("ok")) { // handle error
                    var msg = "postGetSince failed"
                    val err = obj.optString("error","unspecified error")
                    val reason = obj.optString("reason")
                    msg = "$msg: $err"
                    if (!reason.isNullOrEmpty())
                        msg = "$msg [reason: $reason]"
                    Log.e(TAG, msg)
                    return@withContext GetSinceResp(false,emptyList(),since)
                }

                val rowsArr  = obj.optJSONArray("rows") ?: org.json.JSONArray()
                val nextSince = obj.optLong("nextSince", since)

                val rows = ArrayList<ServerRecord>(rowsArr.length())
                for (i in 0 until rowsArr.length()) {
                    val r = rowsArr.getJSONObject(i)

                    val deleted = r.getBoolean("deleted")
                    val timestamp = r.getLong("updatedAt")

                    rows += ServerRecord(
                        fileId       = r.getString("fileId"),
                        progress = r.optString("progress"),
                        updatedAt = timestamp,
                        deletedAt = if (deleted) timestamp else null
                    )
                }
                GetSinceResp( true, rows, nextSince)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSince failed ($table, since=$since, limit=$limit)", e)
            null
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // POST /resolve {sha256,filesize}
    // RETURNS: fileID of file with same sha256 checksum and filesize on server
    //          null on error or not-found
    data class PostResolveReturn (
        val ok: Boolean,
        val fileId: String?
    )
    suspend fun postResolve( sha256: String, filesize: Long)
                    : PostResolveReturn = withContext(Dispatchers.IO) {
        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "postResolve failed: not connected to server")
            return@withContext PostResolveReturn(false,null)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(TAG, "postResolve failed: server name not known")
            return@withContext PostResolveReturn(false,null)
        }

        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        val payload = org.json.JSONObject().apply {
            put("sha256", sha256)           // checksum+filesize uniquely identifies a file (epub/pdf)
            put("filesize", filesize)
        }.toString()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/resolve")
            .addHeader("Authorization", "Bearer $token")   // change if your server expects a different header
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val text = resp.body?.string().orEmpty()
                val obj = org.json.JSONObject(text)

                // ok=false,err=XXX,reason=YYY
                if (!obj.getBoolean("ok")) { // handle error
                    var msg = "postResolve failed"
                    val err = obj.optString("error")
                    val reason = obj.optString("reason")
                    if (!err.isNullOrEmpty())
                        msg = "$msg: $err"
                    if (!reason.isNullOrEmpty())
                        msg = "$msg [reason: $reason]"
                    Log.e(TAG, msg)
                    return@withContext PostResolveReturn(false,null)
                }

                // ok=true, exists=true, fileId=XXX
                // ok=true, exists=false
                val fileId = null
                if (obj.getBoolean("exists"))
                    obj.getString("fileId")

                return@withContext PostResolveReturn(true,fileId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "postResolve failed: $e", e)
        }

        return@withContext PostResolveReturn(false,null)
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // POST /uploadBook {sha256,filesize,mediaType}
    // RETURN: on success returns fileId of file on the server
    //         on failure returns null
    suspend fun postUploadBook( filename: String, sha256: String, filesize: Long, mediaType : String? = null)
        : String? ? = withContext(Dispatchers.IO) {


        val file = File(filename)
        if (!file.exists()) {
            Log.e(TAG, "postUploadBook failed: cannot find file [$filename]")
            return@withContext null
        }
        if (!file.canRead()) {
            Log.e(TAG, "postUploadBook failed: cannot read file [$filename]")
            return@withContext null
        }
        val tmStart = System.currentTimeMillis()
        val shortName = file.name

        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "postResolve failed: not connected to server")
            return@withContext null
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(TAG, "postResolve failed: server name not known")
            return@withContext null
        }

        val mt = when (mediaType) {
            MEDIA_TYPE_EPUB -> "application/epub+zip"
            MEDIA_TYPE_PDF -> "application/pdf"
            else -> "application/octet-stream"  // fall back to octet-stream
        }.toMediaTypeOrNull()
        val fileBody = file.asRequestBody(mt)

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file_id", "0")  // always send with 0 (let server allocate fileId
            .addFormDataPart("size", filesize.toString())
            .addFormDataPart("sha256", sha256)
            .addFormDataPart( "file", "a.book", fileBody ) // note server expects non-null in "filename"
            .build()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/uploadBook")
            .addHeader("Authorization", "Bearer $token")   // change if your server expects a different header
            .post(multipart)
            .build()

        var fileId : String? = null
        val client = OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "postUploadBook failed: ${resp.code}")
                    return@withContext null
                }

                // Expect: {"ok":true,"fileId":"abc123","size":123,"sha256":"..."} or {"ok":false,"error":"too_large"}
                val j = try {
                    org.json.JSONObject(body)
                } catch (e:Exception) {
                    val msg = "invalid JSON: " + e.message?.take(120)
                    Log.e(TAG,msg)
                    return@withContext null
                }

                val ok = j.optBoolean("ok", false)
                if (ok) {
                    fileId = j.optString("fileId").takeIf { it.isNotBlank() }
                } else {
                    val err = j.optString("error", "server_error" )
                    val reason = j.optString("reason")
                    var msg = "postUploadBook failed: $err"
                    if (!reason.isNullOrEmpty()) {
                        msg = "$msg [reason: $reason]"
                    }
                    Log.e(TAG, msg)
                }
            }
        } catch (e: Exception) {
            val msg = "network error: " + e.message
            Log.e(TAG,msg)
        }

        if (!fileId.isNullOrEmpty()) { // success
            val tmTotal = System.currentTimeMillis() - tmStart
            Log.i(TAG,"uploaded file [$shortName] in $tmTotal msecs")
        }

        return@withContext fileId
    }
}