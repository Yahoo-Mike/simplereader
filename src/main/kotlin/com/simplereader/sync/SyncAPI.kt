package com.simplereader.sync

import android.util.Log
import com.simplereader.AppContext
import com.simplereader.model.BookData.Companion.MEDIA_TYPE_EPUB
import com.simplereader.model.BookData.Companion.MEDIA_TYPE_PDF
import com.simplereader.util.Http
import com.simplereader.util.MiscUtil
import com.simplereader.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.text.isNotBlank

object SyncAPI {

    private val LOG_TAG: String = MiscUtil::class.java.simpleName

    ////////////////////////////////////
    // POST /get {tablename,fileId}
    // Get all rows from "tablename" with "fileId" key
    //
    // RETURNS: ok=true, list of [0..n] JSON objects, being all the rows retrieved from server
    //          ok=false, an error occurred
    //
    data class PostGetReturn(
        val ok: Boolean,
        val rows: List<JSONObject> = emptyList()
    )

    suspend fun postGet(table: String, fileId: String, id: Int = -1)
            : PostGetReturn = withContext(Dispatchers.IO) {

        val appContext = AppContext.get() ?: return@withContext PostGetReturn(false)

        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postGet failed: not connected to server")
            return@withContext PostGetReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postGet failed: server name not known")
            return@withContext PostGetReturn(false)
        }

        val client = Http.api

        val payload = JSONObject().apply {
            put("table", table)
            put("fileId", fileId)
            if (id != -1)
                put("id", id)       // we only want one record from bookmark/highlight/note
        }.toString()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/get")
            .addHeader(
                "Authorization",
                "Bearer $token"
            )   // change if your server expects a different header
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(LOG_TAG, "postGet failed: request failed with ${resp.code} ${resp.message}")
                    return@withContext PostGetReturn(false)
                }

                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)

                // ok=false,err=XXX,reason=YY
                if (!obj.getBoolean("ok")) { // handle error
                    var msg = "postGet failed"
                    val err = obj.optString("error", "unspecified error")
                    val reason = obj.optString("reason")
                    msg = "$msg: $err"
                    if (!reason.isNullOrEmpty())
                        msg = "$msg [reason: $reason]"
                    Log.e(LOG_TAG, msg)
                    return@withContext PostGetReturn(false)
                }

                val rows = obj.optJSONArray("rows") ?: JSONArray()
                val rowsReturned =
                    List(rows.length()) { i -> rows.optJSONObject(i) ?: JSONObject() }

                return@withContext PostGetReturn(true, rowsReturned)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "postGet failed: ${e.message}", e)
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
    data class GetSinceResp(
        val ok: Boolean,
        val nextSince: Long = 0L,
        val rows: List<JSONObject> = emptyList()
    )

    suspend fun postGetSince(
        table: String,
        since: Long,
        limit: Int,
    ): GetSinceResp? = withContext(Dispatchers.IO) {
        val appContext = AppContext.get() ?: return@withContext GetSinceResp(false)

        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postGetSince failed: not connected to server")
            return@withContext GetSinceResp(false, since)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postGetSince failed: server name not known")
            return@withContext GetSinceResp(false, since)
        }

        val client = Http.api

        val payload = JSONObject().apply {
            put("table", table)        // e.g., "book_data"
            put("since", since)        // server-side watermark
            put("limit", limit)        // page size
        }.toString()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/getSince")
            .addHeader(
                "Authorization",
                "Bearer $token"
            )   // change if your server expects a different header
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(
                        LOG_TAG,
                        "postGetSince failed: request failed with ${resp.code} ${resp.message}"
                    )
                    return@withContext null
                }

                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)

                // ok=false,err=XXX,reason=YY
                if (!obj.getBoolean("ok")) { // handle error
                    var msg = "postGetSince failed"
                    val err = obj.optString("error", "unspecified error")
                    val reason = obj.optString("reason")
                    msg = "$msg: $err"
                    if (!reason.isNullOrEmpty())
                        msg = "$msg [reason: $reason]"
                    Log.e(LOG_TAG, msg)
                    return@withContext GetSinceResp(false, since)
                }

                val rowsArr = obj.optJSONArray("rows") ?: org.json.JSONArray()
                val nextSince = obj.optLong("nextSince", since)

                val rows = ArrayList<JSONObject>(rowsArr.length())
                for (i in 0 until rowsArr.length()) {
                    val r = rowsArr.getJSONObject(i)
                    rows += r
                }
                return@withContext GetSinceResp(true, nextSince, rows)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "getSince failed: ${e.message}", e)
        }
        return@withContext GetSinceResp(false, since)
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
    data class PostUpdateReturn(
        val ok: Boolean,
        val updatedAt: Long = 0L
    )

    suspend fun postUpdate(tablename: String, row: String, force: Boolean = false)
            : PostUpdateReturn = withContext(Dispatchers.IO) {
        val appContext = AppContext.get() ?: return@withContext PostUpdateReturn(false)

        // sanity check: make sure row is JSON object string
        require(row.trim().startsWith("{") && row.trim().endsWith("}")) {
            Log.e(LOG_TAG, "postUpdate failed: invalid rows [$row]")
            return@withContext PostUpdateReturn(false)
        }

        // make sure we're connected to server
        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postUpdate failed: not connected to server")
            return@withContext PostUpdateReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postUpdate failed: server name not known")
            return@withContext PostUpdateReturn(false)
        }

        val client = Http.api

        val payload = JSONObject().apply {
            put("table", tablename)
            put("force", force)
            put("row", JSONObject(row))
        }.toString()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/update")
            .addHeader(
                "Authorization",
                "Bearer $token"
            )   // change if your server expects a different header
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(
                        LOG_TAG,
                        "postUpdate failed: request failed with ${resp.code} ${resp.message}"
                    )
                    return@withContext PostUpdateReturn(false)
                }
                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)

                // ok=false,err=XXX,reason=YYY,serverUpdatedAt=YYY
                if (!obj.getBoolean("ok")) { // handle error
                    var msg = "postUpdate failed"
                    val err = obj.optString("error", "unknown error")
                    var reason = obj.optString("reason")
                    if (err.contains("conflict")) {
                        reason = obj.optString("serverUpdatedAt")
                    }
                    if (!err.isNullOrEmpty())
                        msg = "$msg: $err"
                    if (!reason.isNullOrEmpty())
                        msg = "$msg [reason: $reason]"
                    Log.e(LOG_TAG, msg)
                    return@withContext PostUpdateReturn(false)
                }

                // ok=true, updatedAt=XXX
                return@withContext PostUpdateReturn(true, obj.optLong("updatedAt", 0L))
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "postUpdate failed: ${e.message}", e)
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
    //      if "tablename" is book_data, then server also softdeletes bookmarks, notes and highlights for this fileId
    //                                   server ignores tag "id" for book_data
    //      if "tablename" is highlight, note or bookmark, then server expects tag "id" to be present
    //
    // SERVER RESPONSE: { "ok": true, "deletedAt": 1712345679000 } // with server’s authoritative timestamp
    //                  { "ok": false, "error": "conflict", "serverUpdatedAt": 1712345685000 }
    //
    // RETURNS: ok=true if successful, in which case client should accept & use the server's deletedAt date
    //          ok=false on error
    data class PostDeleteReturn(
        val ok: Boolean,
        val deletedAt: Long = 0L
    )

    suspend fun postDelete(tablename: String, fileId: String, id: Int = -1)
            : PostDeleteReturn = withContext(Dispatchers.IO) {

        val appContext = AppContext.get() ?: return@withContext PostDeleteReturn(false)

        // make sure we're connected to server
        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postDelete failed: not connected to server")
            return@withContext PostDeleteReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postDelete failed: server name not known")
            return@withContext PostDeleteReturn(false)
        }

        val client = Http.api

        val payload = JSONObject().apply {
            put("table", tablename)
            put("fileId", fileId)
            if (id != -1)
                put("id", id)
        }.toString()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/delete")
            .addHeader(
                "Authorization",
                "Bearer $token"
            )   // change if your server expects a different header
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(
                        LOG_TAG,
                        "postDelete failed: request failed with ${resp.code} ${resp.message}"
                    )
                    return@withContext PostDeleteReturn(false)
                }

                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)

                // ok=false,err=XXX,reason=YYY,serverUpdatedAt=YYY
                if (!obj.getBoolean("ok")) { // handle error
                    var msg = "postDelete failed"
                    val err = obj.optString("error", "unknown error")
                    var reason = obj.optString("reason")
                    if (err.contains("conflict")) {
                        reason = obj.optString("serverUpdatedAt")
                    }
                    if (!err.isNullOrEmpty())
                        msg = "$msg: $err"
                    if (!reason.isNullOrEmpty())
                        msg = "$msg [reason: $reason]"
                    Log.e(LOG_TAG, msg)
                    return@withContext PostDeleteReturn(false, 0L)
                }

                // ok=true, deletedAt=XXX
                return@withContext PostDeleteReturn(true, obj.optLong("deletedAt", 0L))
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "postDelete failed: ${e.message}", e)
        }

        return@withContext PostDeleteReturn(false, 0L)
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // GET /ruOK/token
    // RETURNS: true if server authorises that token
    //          false otherwise
    suspend fun getRUOK(token: String)
            : Boolean = withContext(Dispatchers.IO) {
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(LOG_TAG, "getRUOK failed: server name not known")
            return@withContext false
        }

        val client = Http.api

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
            Log.e(LOG_TAG, "getRUOK failed: ${e.message}", e)
        }

        return@withContext false
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // POST /resolve {sha256,filesize}
    // RETURNS: fileID of file with same sha256 checksum and filesize on server
    //          null on error or not-found
    data class PostResolveReturn(
        val ok: Boolean,
        val fileId: String? = null
    )

    suspend fun postResolve(sha256: String, filesize: Long)
            : PostResolveReturn = withContext(Dispatchers.IO) {

        val appContext = AppContext.get() ?: return@withContext PostResolveReturn(false)

        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postResolve failed: not connected to server")
            return@withContext PostResolveReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postResolve failed: server name not known")
            return@withContext PostResolveReturn(false)
        }

        val client = Http.api

        val payload = JSONObject().apply {
            put(
                "sha256",
                sha256
            )           // checksum+filesize uniquely identifies a file (epub/pdf)
            put("filesize", filesize)
        }.toString()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/resolve")
            .addHeader(
                "Authorization",
                "Bearer $token"
            )   // change if your server expects a different header
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(
                        LOG_TAG,
                        "postResolve failed: request failed with ${resp.code} ${resp.message}"
                    )
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
                    Log.e(LOG_TAG, msg)
                    return@withContext PostResolveReturn(false)
                }

                // ok=true, exists=true, fileId=XXX
                // ok=true, exists=false
                val fileId = null
                if (obj.getBoolean("exists"))
                    obj.getString("fileId")

                return@withContext PostResolveReturn(true, fileId)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "postResolve failed: ${e.message}", e)
        }

        return@withContext PostResolveReturn(false)
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // POST /uploadBook {sha256,filesize,mediaType}
    // RETURN: on success returns fileId of file on the server
    //         on failure returns null
    suspend fun postUploadBook(
        filename: String,
        sha256: String,
        filesize: Long,
        mediaType: String? = null
    )
            : String? = withContext(Dispatchers.IO) {

        val appContext = AppContext.get() ?: return@withContext null

        val file = File(filename)
        if (!file.exists()) {
            Log.e(LOG_TAG, "postUploadBook failed: cannot find file [$filename]")
            return@withContext null
        }
        if (!file.canRead()) {
            Log.e(LOG_TAG, "postUploadBook failed: cannot read file [$filename]")
            return@withContext null
        }
        val tmStart = System.currentTimeMillis()
        val shortName = file.name

        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postUpload failed: not connected to server")
            return@withContext null
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(LOG_TAG, "postUpload failed: server name not known")
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
            .addFormDataPart(
                "file",
                "a.book",
                fileBody
            ) // note server expects non-null in "filename"
            .build()

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/uploadBook")
            .addHeader(
                "Authorization",
                "Bearer $token"
            )   // change if your server expects a different header
            .post(multipart)
            .build()

        var fileId: String? = null
        val client = Http.largefile
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(
                        LOG_TAG,
                        "postUploadBook failed: request failed with ${resp.code} ${resp.message}"
                    )
                    return@withContext null
                }

                // Expect: {"ok":true,"fileId":"abc123","size":123,"sha256":"...", "fileName":"XXX"} or {"ok":false,"error":"too_large"}
                val j = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    val msg = "invalid JSON: " + e.message?.take(120)
                    Log.e(LOG_TAG, msg)
                    return@withContext null
                }

                val ok = j.optBoolean("ok", false)
                if (ok) {
                    fileId = j.optString("fileId").takeIf { it.isNotBlank() }
                } else {
                    val err = j.optString("error", "server_error")
                    val reason = j.optString("reason")
                    var msg = "postUploadBook failed: $err"
                    if (!reason.isNullOrEmpty()) {
                        msg = "$msg [reason: $reason]"
                    }
                    Log.e(LOG_TAG, msg)
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "postUploadBook failed: ${e.message}", e)
            return@withContext null
        }

        if (!fileId.isNullOrEmpty()) { // success
            val tmTotal = System.currentTimeMillis() - tmStart
            Log.i(LOG_TAG, "uploaded file [$shortName] in $tmTotal msecs")
        }

        return@withContext fileId
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // GET /book/{fileId}
    data class GetBookReturn(
        val ok: Boolean,
        val filename: String? = null,   // fully qualified filename
        val sha256: String? = null,
        val filesize: Long = 0L
    )

    suspend fun getBook(fileId: String)
            : GetBookReturn = withContext(Dispatchers.IO) {

        val appContext = AppContext.get() ?: return@withContext GetBookReturn(false)

        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(LOG_TAG, "getBook failed: not connected to server")
            return@withContext GetBookReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(LOG_TAG, "getBook failed: server name not known")
            return@withContext GetBookReturn(false)
        }

        val client = Http.largefile

        val req = Request.Builder()
            .url("${server.trimEnd('/')}/book/$fileId")
            .addHeader(
                "Authorization",
                "Bearer $token"
            )    // change if your server expects a different header
            .addHeader(
                "Accept-Encoding",
                "identity"
            )       // ensures Content-Length matches bytes written
            .get()
            .build()

        val tmStart = System.currentTimeMillis()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(LOG_TAG, "getBook failed: request failed with ${resp.code} ${resp.message}")
                    return@withContext GetBookReturn(false)
                }

                val expectedSha256 = resp.header("X-Checksum-SHA256")
                val expectedFilesize = resp.header("Content-Length")?.toLongOrNull()
                val clientFileName = resp.header("X-Filename") ?: fileId

                if (expectedSha256 == null || expectedFilesize == null) {
                    Log.e(LOG_TAG, "getBook failed: server did not provide sha256 or filesize")
                    return@withContext GetBookReturn(false)

                }

                val tmp = File("${appContext.cacheDir}/$fileId.part")
                tmp.outputStream().use { out ->
                    val body = resp.body
                    if (body == null) {
                        Log.e(LOG_TAG, "getBook failed: server did not send file")
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
                                Log.e(LOG_TAG, "getBook failed: checksum mismatch")
                                return@withContext GetBookReturn(false)
                            }
                        }
                        if (expectedFilesize != tmp.length()) {
                            tmp.delete()
                            Log.e(LOG_TAG, "getBook failed: different filesize")
                            return@withContext GetBookReturn(false)
                        }

                        // Atomically replace
                        val destDir = appContext.getExternalFilesDir(null)
                        if (destDir == null) {
                            tmp.delete()
                            Log.e(LOG_TAG, "getBook failed: cannot find external files directory")
                            return@withContext GetBookReturn(false)
                        }
                        destDir.mkdirs()

                        val dest = File(destDir, clientFileName)
                        val destName = dest.absolutePath

                        tmp.inputStream().use { ins ->
                            dest.outputStream().use { outs ->
                                ins.copyTo(outs, 128 * 1024)
                            }
                        }
                        tmp.delete() // clean up
                        val tmTotal = System.currentTimeMillis() - tmStart
                        Log.i(LOG_TAG, "downloaded file [${dest.name}] in $tmTotal msecs")

                        return@withContext GetBookReturn(
                            true,
                            destName,
                            expectedSha256,
                            expectedFilesize
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "getBook failed: ${e.message}", e)
            return@withContext GetBookReturn(false)
        }

        Log.e(LOG_TAG, "getBook failed: reason unknown")
        return@withContext GetBookReturn(false)
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // GET /catalogue
    //   get a list of all the books on the server (whether users are reading them or not)
    //
    // SERVER RESPONSE: {   "ok": true,
    //                      "count": 2,
    //                      "rows": [
    //                          {"fileId":"123","fileName":"hello"},
    //                          {"fileId":"1323","fileName":"hello2"}  ]
    //                  }
    //
    //                  { "ok": false, "error": "an error", "reason": "a reason" }
    //
    // RETURNS: ok=true if successful, in which case client should accept & use the server's updatedAt date
    //          ok=false on error
    data class GetCatalogueReturn(
        val ok: Boolean,
        val rows: List<JSONObject> = emptyList()
    )
    suspend fun getCatalogue(): GetCatalogueReturn = withContext(Dispatchers.IO) {

        val appContext = AppContext.get() ?: return@withContext GetCatalogueReturn(false )

        val token = TokenManager.getToken(appContext)
        if (token.isNullOrEmpty()) {
            Log.e(LOG_TAG, "getCatalogue failed: not connected to server")
            return@withContext GetCatalogueReturn(false)
        }
        val server = TokenManager.getServerName()
        if (server.isNullOrEmpty()) {
            Log.e(LOG_TAG, "getCatalogue failed: server not configured")
            return@withContext GetCatalogueReturn(false)
        }

        val client = Http.api

        val req = Request.Builder()
            .url(server.trimEnd('/') + "/catalogue")
            .addHeader( "Authorization", "Bearer $token" )
            .get()
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(LOG_TAG, "getCatalogue failed: request failed with ${resp.code} ${resp.message}")
                    return@withContext GetCatalogueReturn(false)
                }

                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)

                // ok=false,err=XXX,reason=YY
                if (!obj.getBoolean("ok")) { // handle error
                    var msg = "getCatalogue failed"
                    val err = obj.optString("error", "unspecified error")
                    val reason = obj.optString("reason")
                    msg = "$msg: $err"
                    if (!reason.isNullOrEmpty())
                        msg = "$msg [reason: $reason]"
                    Log.e(LOG_TAG, msg)
                    return@withContext GetCatalogueReturn(false)
                }

                val rows = obj.optJSONArray("rows") ?: JSONArray()
                val rowsReturned =
                    List(rows.length()) { i -> rows.optJSONObject(i) ?: JSONObject() }

                return@withContext GetCatalogueReturn(true, rowsReturned)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "getCatalogue failed: ${e.message}", e)
            // fall through
        }

        GetCatalogueReturn(false)
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // DELETE /catalogue/{fileId}
    //   completely removes a book from the server's database (including bookmarks, highlights etc)
    //
    // note: if you call this and it succeeds, but you do not delete that data on the client,
    //       then the next sync will copy what the client has back to the server.
    //
    // SERVER RESPONSE: { "ok": true }
    //                  { "ok": false, "error": "an error", "reason": "a reason" }
    //
    // RETURNS: ok=true if successful
    //          ok=false on error
    data class DeleteFromCatalogueReturn(
        val ok: Boolean,
        val error: String? = null,
        val reason: String? = null,
        val httpCode: Int? = null
    )
    suspend fun deleteFromCatalogue(fileId: String): DeleteFromCatalogueReturn =
        withContext(Dispatchers.IO) {

            if (fileId.isBlank()) {
                return@withContext DeleteFromCatalogueReturn(
                    ok = false,
                    error = "invalid fileId",
                    reason = "fileId was empty or blank"
                )
            }

            val appContext = AppContext.get()
                ?: return@withContext DeleteFromCatalogueReturn(false, error = "no app context")

            val token = TokenManager.getToken(appContext)
            if (token.isNullOrEmpty()) {
                Log.e(LOG_TAG, "deleteFromCatalogue failed: not connected to server")
                return@withContext DeleteFromCatalogueReturn(false, error = "not connected to server")
            }

            val server = TokenManager.getServerName()
            if (server.isNullOrEmpty()) {
                Log.e(LOG_TAG, "deleteFromCatalogue failed: server not configured")
                return@withContext DeleteFromCatalogueReturn(false, error = "server not configured")
            }

            val client = Http.api

            // If fileIds might contain weird chars, encode as a path segment:
            val base = server.trimEnd('/')
            val url = (base.toHttpUrlOrNull()
                ?: return@withContext DeleteFromCatalogueReturn(false, error = "invalid server URL"))
                .newBuilder()
                .addPathSegment("catalogue")
                .addPathSegment(fileId)
                .build()

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    val httpCode = resp.code
                    val text = resp.body?.string().orEmpty()
                    val obj = runCatching { JSONObject(text) }.getOrNull()

                    // Expected:
                    // { "ok": true }
                    // { "ok": false, "error": "...", "reason": "..." }
                    if (obj != null && obj.has("ok") && obj.opt("ok") is Boolean) {
                        val ok = obj.optBoolean("ok", false)
                        if (ok) { // successful
                            return@withContext DeleteFromCatalogueReturn(ok = true, httpCode = httpCode)
                        }
                        // failed, so return reasons to caller...
                        return@withContext DeleteFromCatalogueReturn(
                            ok = false,
                            error = obj.optString("error", "unknown error"),
                            reason = obj.optString("reason", "").takeIf { it.isNotBlank() },
                            httpCode = httpCode
                        )
                    }

                    // no JSON body (or no "ok" field)...so let's look at HTTP success/failure
                    if (!resp.isSuccessful) { // failure
                        return@withContext DeleteFromCatalogueReturn(
                            ok = false,
                            error = "http error",
                            reason = "HTTP $httpCode: ${resp.message}".trim(),
                            httpCode = httpCode
                        )
                    }

                    // successful HTTP but unexpected or empty JSON
                    return@withContext DeleteFromCatalogueReturn(
                        ok = false,
                        error = "invalid response",
                        reason = "Expected JSON with boolean 'ok' but got: ${text.take(100)}", // show first 100 chars of what server sent
                        httpCode = httpCode
                    )
                }

            } catch (e: Exception) {
                Log.e(LOG_TAG, "deleteFromCatalogue failed: ${e.message}", e)
                return@withContext DeleteFromCatalogueReturn(
                    ok = false,
                    error = "exception",
                    reason = e.message
                )
            }
        }
}
