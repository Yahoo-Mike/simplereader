package com.simplereader.sync

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.room.InvalidationTracker
import com.simplereader.bookmark.Bookmark
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
import com.simplereader.highlight.Highlight
import com.simplereader.model.BookData.Companion.MEDIA_TYPE_EPUB
import com.simplereader.model.BookData.Companion.MEDIA_TYPE_PDF
import com.simplereader.note.Note
import com.simplereader.util.sha256Hex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

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

        const val TAG_SYNC = "SYNC_TAG"
        const val WORK_SYNC_NOW = "SYNC_NOW"

        @Volatile private var INSTANCE: SyncManager? = null

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
            return  // syncing is disabled until enableSync() is called again
        }

        // STEP TWO: attach DB observers for push & periodic work
        pushJobs += attachPushObserversForPush()

        // STEP THREE: kick off background periodic schedule
        SyncTickManager.scheduleNextTick(appContext,null)

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
                        deletedAt = System.currentTimeMillis()) )
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
                    deletedAt=System.currentTimeMillis()) )
    }
    suspend fun flagHighlightDeleted(highlight: Highlight) {
        val db = ReaderDatabase.getInstance(appContext)
        db.syncDao().addDeletedRecord(
            DeletedRecordsEntity(
                SyncTables.HIGHLIGHT,
                highlight.bookId,
                highlight.id,
                deletedAt=System.currentTimeMillis()) )
    }
    suspend fun flagNoteDeleted(note: Note) {
        val db = ReaderDatabase.getInstance(appContext)
        db.syncDao().addDeletedRecord(
            DeletedRecordsEntity(
                SyncTables.NOTE,
                note.bookId,
                note.id,
                deletedAt=System.currentTimeMillis()) )
    }

    ////////////////////////////////////
    // POST /get {tablename,fileId}
    // Get all rows from "tablename" with "fileId" key
    //
    // RETURNS: ok=true, list of [0..n] JSON objects, being all the rows retrieved from server
    //          ok=false, an error occurred
    //
    data class PostGetReturn(val ok: Boolean,
                             val rows: List<JSONObject> = emptyList() )
    suspend fun postGet( table: String, fileId: String, id: Int = -1 )
                    : PostGetReturn = withContext(Dispatchers.IO) {
        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "postGet failed: not connected to server")
            return@withContext PostGetReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(TAG, "postGet failed: server name not known")
            return@withContext PostGetReturn(false)
        }

        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        val payload = JSONObject().apply {
            put("table", table)
            put("fileId", fileId)
            if (id != -1)
                put("id", id)       // we only want one record from bookmark/highlight
        }.toString()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/get")
            .addHeader("Authorization", "Bearer $token")   // change if your server expects a different header
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "postGet failed: request failed with ${resp.code} ${resp.message}")
                    return@withContext PostGetReturn(false)
                }

                val text = resp.body?.string().orEmpty()
                val obj  = JSONObject(text)

                // ok=false,err=XXX,reason=YY
                if (!obj.getBoolean("ok")) { // handle error
                    var msg = "postGet failed"
                    val err = obj.optString("error","unspecified error")
                    val reason = obj.optString("reason")
                    msg = "$msg: $err"
                    if (!reason.isNullOrEmpty())
                        msg = "$msg [reason: $reason]"
                    Log.e(TAG, msg)
                    return@withContext PostGetReturn(false)
                }

                val rows = obj.optJSONArray("rows") ?: JSONArray()
                val rowsReturned = List(rows.length()){ i -> rows.optJSONObject(i) ?: JSONObject() }

                return@withContext PostGetReturn(true,rowsReturned)
            }
        } catch (e: Exception) {
            Log.e(TAG, "postGet failed: ${e.message}", e)
        }

        return@withContext PostGetReturn(false)
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // POST /getSince {tablename,since,limit}
    // Get all rows that have been update since timestamp, response limited to "limit" rows per response
    //
    //      since:  timestamp in UTC msecs   (use since=0 to get all records from tablename)
    //      limit:  maximum number of rows to send in each response
    // RETURNS: ok=true, list of [0..n] JSON objects, being all the rows retrieved from server
    //                   nextSince = UTC time for next batch of records (if more than "limit" to retrieve)
    //          ok=false, an error occurred
    data class GetSinceResp(val ok: Boolean, val rows: List<JSONObject> = emptyList(), val nextSince: Long=0L)
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

        val payload = JSONObject().apply {
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
                if (!resp.isSuccessful) {
                    Log.e(TAG, "postGetSince failed: request failed with ${resp.code} ${resp.message}")
                    return@withContext null
                }

                val text = resp.body?.string().orEmpty()
                val obj  = JSONObject(text)

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

                val rows = ArrayList<JSONObject>(rowsArr.length())
                for (i in 0 until rowsArr.length()) {
                    val r = rowsArr.getJSONObject(i)
                    rows += r
                }
                return@withContext GetSinceResp( true, rows, nextSince)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSince failed: ${e.message}", e)
        }
        return@withContext GetSinceResp(false, emptyList(), since)
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // POST /update {tablename,row{file_id:xxx,updatedAt:xxx,...},force=false}
    //   update the server db for this record.  Note: we only do one row at a time, so we can get the updatedAt for each row
    //   On the server:
    //      if file_id is not found, row is ignored.
    //      if server's timestamp is greater than rows.updatedAt, row is ignored unless "force" is set to true.
    //   force says update the row even if server's timestamp is greater than row's updatedAt
    //      "force" is optional and defaults to false
    //
    // SERVER RESPONSE: { "ok": true, "updatedAt": 1712345679000 } // with server’s authoritative timestamp
    //                  { "ok": false, "error": "conflict", "serverUpdatedAt": 1712345685000 }
    //
    // RETURNS: ok=true if successful, in which case client should accept & use the server's updatedAt date
    //          ok=false on error
    data class PostUpdateReturn (
        val ok: Boolean,
        val updatedAt: Long = 0L
    )
    suspend fun postUpdate( tablename: String, row: String, force: Boolean = false)
            : PostUpdateReturn = withContext(Dispatchers.IO) {

        // sanity check: make sure row is JSON object string
        require(row.trim().startsWith("{") && row.trim().endsWith("}")) {
            Log.e(TAG,"postUpdate failed: invalid rows [$row]")
            return@withContext PostUpdateReturn(false)
        }

        // make sure we're connected to server
        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "postUpdate failed: not connected to server")
            return@withContext PostUpdateReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(TAG, "postUpdate failed: server name not known")
            return@withContext PostUpdateReturn(false)
        }

        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        val payload = JSONObject().apply {
            put("table", tablename)
            put("force", force)
            put("row", JSONObject(row))
        }.toString()
        
        val req = Request.Builder()
            .url(server.trimEnd('/') + "/update")
            .addHeader("Authorization", "Bearer $token")   // change if your server expects a different header
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "postUpdate failed: request failed with ${resp.code} ${resp.message}")
                    return@withContext PostUpdateReturn(false)
                }
                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)

                // ok=false,err=XXX,reason=YYY,serverUpdatedAt=YYY
                if (!obj.getBoolean("ok")) { // handle error
                    var msg = "postUpdate failed"
                    val err = obj.optString("error","unknown error")
                    var reason = obj.optString("reason")
                    if (err.contains("conflict")) {
                        reason = obj.optString("serverUpdatedAt")
                    }
                    if (!err.isNullOrEmpty())
                        msg = "$msg: $err"
                    if (!reason.isNullOrEmpty())
                        msg = "$msg [reason: $reason]"
                    Log.e(TAG, msg)
                    return@withContext PostUpdateReturn(false)
                }

                // ok=true, updatedAt=XXX
                return@withContext PostUpdateReturn(true,obj.optLong("updatedAt",0L))
            }
        } catch (e: Exception) {
            Log.e(TAG, "postUpdate failed: ${e.message}", e)
        }

        return@withContext PostUpdateReturn(false)
    }


    /////////////////////////////////////////////////////////////////////////////////////
    // POST /delete {table,fileId,id}
    //   soft delete this row from table on the server db.
    //   note: for table=book_data, server ignores "id"
    //   note: we only do one row at a time, so we can get the deletedAt for each row
    //
    //   On the server:
    //      if file_id is not found, delete is ignored.
    //      if server's timestamp is greater than rows.updatedAt, row is ignored
    //      if "tablename" is book_data, then server also softdeletes bookmarks and highlights for this fileId
    //                                   server ignores tag "id" for book_data
    //      if "tablename" is highlight or bookmark, then server expects tag "id" to be present
    //
    // SERVER RESPONSE: { "ok": true, "deletedAt": 1712345679000 } // with server’s authoritative timestamp
    //                  { "ok": false, "error": "conflict", "serverUpdatedAt": 1712345685000 }
    //
    // RETURNS: ok=true if successful, in which case client should accept & use the server's deletedAt date
    //          ok=false on error
    data class PostDeleteReturn (
        val ok: Boolean,
        val deletedAt: Long = 0L
    )
    suspend fun postDelete( tablename: String, fileId: String, id: Int = -1 )
            : PostDeleteReturn = withContext(Dispatchers.IO) {

        // make sure we're connected to server
        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "postDelete failed: not connected to server")
            return@withContext PostDeleteReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(TAG, "postDelete failed: server name not known")
            return@withContext PostDeleteReturn(false)
        }

        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        val payload = JSONObject().apply {
            put("table", tablename)
            put("fileId", fileId)
            if (id != -1)
                put("id", id)
        }.toString()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/delete")
            .addHeader("Authorization", "Bearer $token")   // change if your server expects a different header
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "postDelete failed: request failed with ${resp.code} ${resp.message}")
                    return@withContext PostDeleteReturn(false)
                }

                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)

                // ok=false,err=XXX,reason=YYY,serverUpdatedAt=YYY
                if (!obj.getBoolean("ok")) { // handle error
                    var msg = "postDelete failed"
                    val err = obj.optString("error","unknown error")
                    var reason = obj.optString("reason")
                    if (err.contains("conflict")) {
                        reason = obj.optString("serverUpdatedAt")
                    }
                    if (!err.isNullOrEmpty())
                        msg = "$msg: $err"
                    if (!reason.isNullOrEmpty())
                        msg = "$msg [reason: $reason]"
                    Log.e(TAG, msg)
                    return@withContext PostDeleteReturn(false,0L)
                }

                // ok=true, deletedAt=XXX
                return@withContext PostDeleteReturn(true,obj.optLong("deletedAt",0L))
            }
        } catch (e: Exception) {
            Log.e(TAG, "postDelete failed: ${e.message}", e)
        }

        return@withContext PostDeleteReturn(false,0L)
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // GET /ruOK/token
    // RETURNS: true if server authorises that token
    //          false otherwise
    suspend fun getRUOK( token: String )
            : Boolean = withContext(Dispatchers.IO) {
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(TAG, "getRUOK failed: server name not known")
            return@withContext false
        }

        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder()
            .url("${server.trimEnd('/')}/ruOK/$token")
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    if (obj.optBoolean("ok"))
                        return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRUOK failed: ${e.message}", e)
        }

        return@withContext false
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // POST /resolve {sha256,filesize}
    // RETURNS: fileID of file with same sha256 checksum and filesize on server
    //          null on error or not-found
    data class PostResolveReturn (
        val ok: Boolean,
        val fileId: String? = null
    )
    suspend fun postResolve( sha256: String, filesize: Long)
                    : PostResolveReturn = withContext(Dispatchers.IO) {
        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "postResolve failed: not connected to server")
            return@withContext PostResolveReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(TAG, "postResolve failed: server name not known")
            return@withContext PostResolveReturn(false)
        }

        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        val payload = JSONObject().apply {
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
                if (!resp.isSuccessful) {
                    Log.e(TAG, "postResolve failed: request failed with ${resp.code} ${resp.message}")
                    return@withContext PostResolveReturn(false)
                }
                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)

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
                    return@withContext PostResolveReturn(false)
                }

                // ok=true, exists=true, fileId=XXX
                // ok=true, exists=false
                val fileId = null
                if (obj.getBoolean("exists"))
                    obj.getString("fileId")

                return@withContext PostResolveReturn(true,fileId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "postResolve failed: ${e.message}", e)
        }

        return@withContext PostResolveReturn(false)
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // POST /uploadBook {sha256,filesize,mediaType}
    // RETURN: on success returns fileId of file on the server
    //         on failure returns null
    suspend fun postUploadBook( filename: String, sha256: String, filesize: Long, mediaType : String? = null)
        : String? = withContext(Dispatchers.IO) {

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
            Log.e(TAG, "postUpload failed: not connected to server")
            return@withContext null
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(TAG, "postUpload failed: server name not known")
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
            .addFormDataPart("fileId", "0")  // always send with 0 (let server allocate fileId
            .addFormDataPart("size", filesize.toString())
            .addFormDataPart("sha256", sha256)
            .addFormDataPart("fileName", shortName)
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
                    Log.e(TAG, "postUploadBook failed: request failed with ${resp.code} ${resp.message}")
                    return@withContext null
                }

                // Expect: {"ok":true,"fileId":"abc123","size":123,"sha256":"...", "fileName":"XXX"} or {"ok":false,"error":"too_large"}
                val j = try {
                    JSONObject(body)
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
            Log.e(TAG, "postUploadBook failed: ${e.message}", e)
            return@withContext null
        }

        if (!fileId.isNullOrEmpty()) { // success
            val tmTotal = System.currentTimeMillis() - tmStart
            Log.i(TAG,"uploaded file [$shortName] in $tmTotal msecs")
        }

        return@withContext fileId
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // GET /book/{fileId}
    data class GetBookReturn (
        val ok: Boolean,
        val filename: String? = null,   // fully qualified filename
        val sha256: String? = null,
        val filesize: Long = 0L
    )
    suspend fun getBook( fileId: String )
            : GetBookReturn = withContext(Dispatchers.IO) {

        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "getBook failed: not connected to server")
            return@withContext GetBookReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(TAG, "getBook failed: server name not known")
            return@withContext GetBookReturn(false)
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)       // how long a single read() may block
            .writeTimeout(5, TimeUnit.MINUTES)      // harmless for GETs
            .callTimeout(0, TimeUnit.MILLISECONDS)  // 0 = no overall cap
            .retryOnConnectionFailure(true)
            .build()

        val req = Request.Builder()
            .url("${server.trimEnd('/')}/book/$fileId")
            .addHeader("Authorization", "Bearer $token")    // change if your server expects a different header
            .addHeader("Accept-Encoding", "identity")       // ensures Content-Length matches bytes written
            .get()
            .build()

        val tmStart = System.currentTimeMillis()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "getBook failed: request failed with ${resp.code} ${resp.message}")
                    return@withContext GetBookReturn(false)
                }

                val expectedSha256 = resp.header("X-Checksum-SHA256")
                val expectedFilesize = resp.header("Content-Length")?.toLongOrNull()
                val clientFileName = resp.header("X-Filename") ?: fileId

                if (expectedSha256==null || expectedFilesize == null) {
                    Log.e(TAG, "getBook failed: server did not provide sha256 or filesize")
                    return@withContext GetBookReturn(false)

                }

                val tmp = File("${appContext.cacheDir}/$fileId.part")
                tmp.outputStream().use { out ->
                    val body = resp.body
                    if (body==null) {
                        Log.e(TAG, "getBook failed: server did not send file")
                        return@withContext GetBookReturn(false)
                    }
                    body?.byteStream()?.use { ins ->
                        val buf = ByteArray(128 * 1024)
                        var total = 0L
                        while (true) {
                            val n = ins.read(buf); if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                        out.flush()

                        // Verify checksum if provided
                        if (expectedSha256.isNotBlank()) {
                            val got = sha256Hex(tmp)
                            if (!got.equals(expectedSha256, ignoreCase = true)) {
                                tmp.delete()
                                Log.e(TAG, "getBook failed: checksum mismatch")
                                return@withContext GetBookReturn(false)
                            }
                        }
                        if (expectedFilesize != tmp.length()) {
                            tmp.delete()
                            Log.e(TAG, "getBook failed: different filesize")
                            return@withContext GetBookReturn(false)
                        }

                        // Atomically replace
                        val destDir = appContext.getExternalFilesDir(null)
                        if (destDir == null) {
                            tmp.delete()
                            Log.e(TAG, "getBook failed: cannot find external files directory")
                            return@withContext GetBookReturn(false)
                        }
                        destDir.mkdirs()

                        val dest = File(destDir,clientFileName)
                        val destName = dest.absolutePath

                        tmp.inputStream().use { ins ->
                            dest.outputStream().use { outs ->
                                ins.copyTo(outs, 128 * 1024)
                            }
                        }
                        tmp.delete() // clean up
                        val tmTotal = System.currentTimeMillis() - tmStart
                        Log.i(TAG,"downloaded file [${dest.name}] in $tmTotal msecs")

                        return@withContext GetBookReturn(true,destName, expectedSha256, expectedFilesize)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBook failed: ${e.message}", e)
            return@withContext GetBookReturn(false)
        }

        Log.e(TAG, "getBook failed: reason unknown")
        return@withContext GetBookReturn(false)
    }

}