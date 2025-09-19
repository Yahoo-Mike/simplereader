package com.simplereader.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.simplereader.book.BookDataEntity
import com.simplereader.data.ReaderDatabase
import com.simplereader.util.FileUtil
import com.simplereader.util.MiscUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SyncTimes(
    var clientUpdate: Long? = null,
    var clientDelete: Long? = null,
    var serverUpdate: Long? = null,
    var serverDelete: Long? = null,
) {
    fun toMask() : Int {
        var mask = 0
        if (clientUpdate != null) mask = mask or 0b1000
        if (clientDelete != null) mask = mask or 0b0100
        if (serverUpdate != null) mask = mask or 0b0010
        if (serverDelete != null) mask = mask or 0b0001

        return mask
    }
}

//
// worker that synchronises the book_data, highlight and bookmark tables with the server
//
class SyncWorker( appCtx: Context, params: WorkerParameters) : CoroutineWorker(appCtx, params) {
    companion object {
        private val TAG: String = MiscUtil::class.java.simpleName
    }

    private val appContext = appCtx

    override suspend fun doWork(): Result {

        // sanity check: are we connected to server?
        if ( !TokenManager.isConnected(appContext) ) {
            Log.w(TAG, "server sync abandoned: not connected to server")
            return Result.success()
        }

        var changed = inputData.getStringArray("changed_tables")?.toSet().orEmpty()
        // If empty, you can treat it as “sync everything” or just return.
        // Otherwise, switch on table names and only push/pull for those.
        // e.g., if (SyncTables.BOOKMARK in changed) syncBookmarks()
        if (changed.isEmpty()) {
            // treat this as a request to sync all SyncTables
            changed = SyncTables.ALL.toSet()
        }

        Log.i(TAG, "START: sync for $changed")

        if (SyncTables.BOOK_DATA in changed) {
            // sync book_data.
            // note: always do this before bookmark/highlight, because it might be a new book
            //       and syncing bookmark/highlight might fail if there is no corresponding book_data entry
            syncBookData()
        }
        if (SyncTables.BOOKMARK in changed) {
            // sync bookmark
        }
        if (SyncTables.HIGHLIGHT in changed) {
            // sync highlight
        }

        Log.i(TAG, "  END: sync for $changed")

        return Result.success()
    }

    //
    // This is a "robust" approach and assumes there are very few book records on the server & client tables
    //
    // timestamps
    // ==========
    // We need to compare timestamps by fileId.  The timestamps come from three sources:
    //      1. POST /getSince(0) gives us all server records in the book table
    //      2. local table: select * from deleted_records where tablename="book_data"
    //      3. local table: select * from book_data
    //
    // fileId
    // ======
    // The data from the server is already keyed on fileId.
    //
    // However, the local tables are keyed on book_id.  So there's a mapping table kept locally:
    // TABLE `sync_fileid_map` (`fileId` TEXT NOT NULL, `bookId` TEXT NOT NULL, PRIMARY KEY(`fileId`));
    //
    // If a bookId is not in sync_fileid_map, then we need to add it.  The process is:
    //   1. POST /resolve {sha256,filesize} -> {fileId}
    //   2. if the server cannot resolve the fileId, then we need to add it to the server with:
    //          (a) POST /uploadBook {multipart} and response will include the new fileId
    //          (b) if the book is in the deleted_records table, there's no point uploading it just
    //              to delete immediately.  So in this case, just delete the local db references
    //              to bookId (if they exist) and then it's safe to exclude this case in the sync.
    //              At this point, remove the bookId from deleted_records.
    //
    // mutableMap of timestamps
    // ========================
    // Now we build a mutable map of timestamps keyed by fileId:
    //      data class SyncTimes(
    //          var clientUpdate: Long? = null,
    //          var clientDelete: Long? = null,
    //          var serverUpdate: Long? = null,
    //          var serverDelete: Long? = null,
    //      )
    //      val timestamps: MutableMap<String, SyncTimes> = mutableMapOf()
    //
    // for (all server records)
    //     add fileId.server.lastUpdated    => timestamps.serverUpdate
    //     add fileId.server.lastDeleted    => timestamps.serverDelete
    // for (all deleted_records)
    //      add fileId.deleted.deletedAt    => timestamps.clientDelete
    // for (all book_data)
    //      add fileId.lastUpdated          => timestamps.clientUpdate
    //
    // determine action
    // ================
    // Go through the mutable map, examine the timestamps and determine the action to take.
    //  for (book in timestamps)
    //      syncAction(book,SyncTimes)
    //      if (successful && !SyncTimes.clientDelete.isNull)
    //          delete this book_data,fileId from deleted_records
    //
    //  note: syncAction() will have to update the sync_fileid_map table if an add or delete is performed.
    //
    private var serverRecords: List<ServerRecord> = emptyList()
    private var deleteRecords: List<DeleteRecord> = emptyList()
    private var clientRecords: List<ClientRecord> = emptyList()

    // convert to a Json row, like {"fileId":"XXX","progress":"YYY",updatedAt=1234}
    fun BookDataEntity.toRowJson(fileId: String): String {
        return JSONObject().apply {
            put("fileId", fileId)                    // use the given file_id, not bookId
            put("progress", currentProgress ?: "")    // null-safe
            put("updatedAt", lastUpdated)             // UTC millis
        }.toString()
    }
    private suspend fun syncBookData() {

        // clear the lists we'll be building
        serverRecords = emptyList()
        deleteRecords = emptyList()
        clientRecords = emptyList()

        // our map of timestamps, which we'll later compare
        val timestamps: MutableMap<String, SyncTimes> = mutableMapOf()

        //
        // STEP ONE: build list of all timestamps
        //
        // get timestamps for all books known to server
        val result1 = getAllServerBooks()
        var ok = result1.ok; serverRecords = result1.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get server book records")
            return
        }
        for (record in serverRecords) {
            //add to timestamps
            timestamps.getOrPut(record.fileId) { SyncTimes() }.apply {
                serverUpdate = record.updatedAt
                serverDelete = record.deletedAt
            }
        }

        // get timestamps for all books deleted locally
        val result2 = getLocalDeleteRecords()
        ok = result2.ok; deleteRecords = result2.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get local delete records")
            return
        }
        for (record in deleteRecords) {
            //add to timestamps
            timestamps.getOrPut(record.fileId) { SyncTimes() }.apply {
                clientDelete = record.deletedAt
            }
        }

        // get timestamps for all local books user is currently reading
        val result3 = getClientRecords()
        ok = result3.ok; clientRecords = result3.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get local book records")
            return
        }
        for (record in clientRecords) {
            //add to timestamps
            timestamps.getOrPut(record.fileId) { SyncTimes() }.apply {
                clientUpdate = record.updatedAt
            }
        }

        //
        // STEP TWO: work out what action to take for each row in timestamps
        //
        for ( (fileId,tm) in timestamps) {
            val mask = tm.toMask()
            val rule = bookRules.firstOrNull {it.mask == mask}
            if (rule != null) {
                Log.d("YM_DEBUG","[$fileId] ${rule.name} ${tm.clientUpdate} ${tm.clientDelete} ${tm.serverUpdate} ${tm.serverDelete} ")
                rule.action(fileId,tm)
            } else {
                Log.w(TAG, "syncBookData: unexpected error")
            }
        }

    }

    //
    // get timestamps for all our local book_data records
    //
    private data class ClientRecordsResult(
        val ok: Boolean,
        val records: List<ClientRecord>
    )
    private suspend fun getClientRecords(): ClientRecordsResult {
        val db = ReaderDatabase.getInstance(appContext)
        val bookDao = db.bookDao()
        val books = bookDao.getAllBooks()

        val records = mutableListOf<ClientRecord>()
        for (book in books) {

            val rc = mapBookId(book.bookId, book.sha256, book.filesize)
            if (rc.ok) {
                var fileId = rc.fileId
                if (fileId.isNullOrEmpty()) {
                    // assume that this book is not on the server, so we add it
                    // POST /uploadBook {multipart} and response will include the new fileId
                    if (!book.sha256.isNullOrEmpty()) {
                        fileId = SyncManager.getInstance(appContext)
                            .postUploadBook(
                                book.pubFile,
                                book.sha256,
                                book.filesize,
                                book.mediaType
                            )

                        if (!fileId.isNullOrEmpty()) {
                            // add this to the map
                            db.syncDao().updateMapping(SyncFileIdMapEntity(fileId, book.bookId))
                        }
                    }
                }
                if (!fileId.isNullOrEmpty())
                    records.add(ClientRecord(fileId, book.lastUpdated))
            }
        }

        return ClientRecordsResult(true, records)
    }

    //
    // get timestamps for all our book records on the server
    //
    private data class ServerRecordsResult(
        val ok: Boolean,
        val records: List<ServerRecord>
    )
    private suspend fun getAllServerBooks(): ServerRecordsResult =
        withContext(Dispatchers.IO) {

            val records = mutableListOf<ServerRecord>()
            var since = 0L  // all records
            val throttle = 100 // 100 records at a time

            while (true) {
                val resp = SyncManager.getInstance(appContext).postGetSince(
                    SyncTables.BOOK_DATA, since, throttle )
                    ?: return@withContext ServerRecordsResult(false, emptyList())
                records += resp.rows

                if (resp.rows.size < throttle) break // we've got them all, so we can leave now...

                since = resp.nextSince  // we need to read more
            }

            return@withContext ServerRecordsResult(true, records)
        }

    //
    // get timestamps for all the books we have deleted locally
    //
    private data class DeleteRecordsResult(
        val ok: Boolean,
        val records: List<DeleteRecord>
    )
    private suspend fun getLocalDeleteRecords(): DeleteRecordsResult =
        withContext(Dispatchers.IO) {
            val db = ReaderDatabase.getInstance(appContext)
            val syncDao = db.syncDao()

            // 1oad tombstones for book_data, if there are none, we can gracefully leave
            val tombstones = syncDao.getDeletedRecordByTable(SyncTables.BOOK_DATA)
            if (tombstones.isNullOrEmpty())
                return@withContext DeleteRecordsResult(true, emptyList())

            val records = mutableListOf<DeleteRecord>()

            // iterate through all the tombstoned books
            for (t in tombstones) {
                val bookId = t.bookId
                val rc =
                    mapBookId(bookId, t.sha256, t.filesize)
                if (!rc.ok)
                    continue    // problem on server, so leave this tombstone for another day

                val fileId = rc.fileId
                if (fileId.isNullOrEmpty()) {
                    // could not resolve fileId, so just delete bookId from local database
                    // server doesn’t have this book, so we can't update it on the server
                    db.withTransaction {
                        // delete all data for this bookid
                        db.bookmarkDao().deleteAllForBook(bookId)
                        db.highlightDao().deleteAllForBook(bookId)
                        db.bookDao().deleteByBookId(bookId)

                        // delete all references to bookId in deleted_records
                        syncDao.deleteDeletedRecord(SyncTables.BOOK_DATA, bookId)
                        syncDao.deleteDeletedRecord(SyncTables.BOOKMARK, bookId)
                        syncDao.deleteDeletedRecord(SyncTables.HIGHLIGHT, bookId)
                    }
                } else {
                    // we have a fileId, so add it to the list...
                    records.add(DeleteRecord(fileId, t.deletedAt))
                }
            } //end for(all tombstones)

            return@withContext DeleteRecordsResult(true, records)
        }

    // maps bookId (used by client) to fileId (used by server)
    // RETURNS: ok=true on success, fileId as mapped (or null if there is no mapping)
    //          ok=false on server error
    data class MapResult (
        val ok: Boolean,
        val fileId: String?
    )
    suspend fun mapBookId(bookId: String, sha256 : String?, filesize : Long = 0L) : MapResult {

        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()

        var fileId = syncDao.getFileIdForBookId(bookId)
        if (fileId.isNullOrEmpty()) {
            // ask the server to resolve fileId using stored checksum/size from tombstone
            if (!sha256.isNullOrBlank() && filesize > 0L) {
                val rc = SyncManager.getInstance(appContext).postResolve(sha256, filesize)
                if (rc.ok)
                    fileId = rc.fileId
                else
                    return MapResult(false, null)
            }
            if (!fileId.isNullOrEmpty()) {
                // we got fileId from server, so add it to our fileId<->bookId map
                syncDao.updateMapping(SyncFileIdMapEntity(fileId, bookId))
            }
        }

        return MapResult(true,fileId)
    }

    // returns fileId for this bookId, null if no map exists
    private suspend fun getBookId(fileId:String) : String? =
        ReaderDatabase.getInstance(appContext).syncDao().getBookIdForFileId(fileId)


    ///////////////////////////////////////////////////////////////////////////////////////////
    // process book updates
    //
    // we use a four-bit matrix to represent all the possible scenarios of timestamps for each book
    //   The most significant bit (3) represents client updates (CU)    (1=book_data exists,0=doesn't exist)
    //   The next (2) represents client deletes (CD)                    (1=deleted on client,0=not deleted)
    //   next (1) represents server updates (SU)                        (1=server has seen this file; 0=server has never seen this file)
    //   least significant bit (0) represents server deletes (SD)       (1=deleted on server; 0=not deleted on server)
    //
    // These bits correspond to the timestamps in a SyncTimes() object.  A value of 1 means that
    // that timestamp is non-null.  A value of zero means that that timestamp is null.
    // Some scenarios can be ignored: when SD is non-null, then we can ignore SU
    ///////////////////////////////////////////////////////////////////////////////////////////
    data class BookRule(
        val name: String,
        val mask: Int,
        val action: suspend (fileId: String, t: SyncTimes) -> Boolean
    )
    private val bookRules = listOf(
        BookRule("NONE",      0b0000, ::nop),       // nothing to do
        BookRule("SD",        0b0001, ::nop),       // won't happen: if SD is non-null, then SU==SD
        BookRule("SU",        0b0010, ::book0010),
        BookRule("SU+SD",     0b0011, ::nop),       // deleted on server, doesn't exist on client

        BookRule("CD",        0b0100, ::nop),       // never existed on server, doesn't exist on client
        BookRule("CD+SD",     0b0101, ::nop),       // won't happen: if SD is non-null, then SU==SD
        BookRule("CD+SU",     0b0110, ::book0110),
        BookRule("CD+SU+SD",  0b0111, ::nop),       // already deleted on server and client

        BookRule("CU",        0b1000, ::book1000),
        BookRule("CU+SD",     0b1001, ::nop),       // won't happen: if SD is non-null, then SU==SD
        BookRule("CU+SU",     0b1010, ::book1010),
        BookRule("CU+SU+SD",  0b1011, ::book1011),

        BookRule("CU+CD",     0b1100, ::book1100),
        BookRule("CU+CD+SD",  0b1101, ::nop),       // won't happen: if SD is non-null, then SU==SD
        BookRule("CU+CD+SU",  0b1110, ::book1110),
        BookRule("ALL",       0b1111, ::book1111),
    )

    private suspend fun nop(fileId:String, t:SyncTimes) : Boolean = true

    // SU: exists on server, not on client
    // action:  download the file from server and update client db
    private suspend fun book0010(fileId:String,t:SyncTimes) : Boolean {
        if (t.serverUpdate == null) {
            Log.e(TAG,"book0010: unexpected null timestamps")
            return false
        }

        // download the book from the server, update all book_data/bookmarks/highlights from server
        val rc1 = SyncManager.getInstance(appContext).getBook(fileId)
        if (!rc1.ok)
            return false
        if (rc1.filename==null)
            return false    // we need to know the filename

        // get record info from the server
        val rc2 = SyncManager.getInstance(appContext).postGet(SyncTables.BOOK_DATA,fileId)
        if (!rc2.ok)
            return false    // something went wrong

        val rowList = rc2.rows
        if (rowList.isNotEmpty()) {
            if (rowList.size > 1)
                Log.w(TAG, "book0010: only processing first of [${rc2.rows.size}] rows. Was only expecting one row.")

            val row = rowList[0] // process the first row only
            val progress = row.getString("progress")
            val updatedAt = row.getLong("updatedAt")
//            val deletedAt = row.optLong("deletedAt",0L)  // assume the caller checked if its deleted or not (and how it wants to proceed)

            val bookId = BookDataEntity.constructBookId(appContext,rc1.filename)
            if (bookId == null)
                return false    // we need to know bookId
            val bookExt = FileUtil.getExtensionUppercase(rc1.filename)
            if (bookExt == null)
                return false    // we need to know the extension

            val book = BookDataEntity(
                bookId,
                rc1.filename,
                bookExt,
                progress,
                rc1.sha256,
                rc1.filesize,
                updatedAt       // use the server's authoritative timestamp
            )
            val db = ReaderDatabase.getInstance(appContext)
            db.bookDao().insert(book)

            // map this fileId/bookId pair
            db.syncDao().updateMapping(SyncFileIdMapEntity(fileId,bookId))
        }
        return true
    }

    // CD+SU
    // action:  if (SU >= CD) download the file from server and update client db
    //          else delete from server
    private suspend fun book0110(fileId:String,t:SyncTimes) : Boolean {
        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        val bookDao = db.bookDao()

        if (t.clientDelete == null || t.serverUpdate == null) {
            Log.e(TAG,"book0110: unexpected null timestamps")
            return false
        }

        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG,"book0110: invalid fileId")
            return false
        }

        if (t.clientDelete!! > t.serverUpdate!!) {
            // soft delete from the server
            SyncManager.getInstance(appContext).postDelete(SyncTables.BOOK_DATA,fileId)

            // delete it locally
            db.bookmarkDao().deleteAllForBook(bookId)
            db.highlightDao().deleteAllForBook(bookId)
            bookDao.deleteByBookId(bookId)

            // delete all references to bookId in deleted_records
            syncDao.deleteDeletedRecord(SyncTables.BOOKMARK, bookId)
            syncDao.deleteDeletedRecord(SyncTables.HIGHLIGHT, bookId)
        } else {
            // download the book from the server, update all book_data/bookmarks/highlights from server
            //  note: this is now just an SU update
            val ok = book0010(fileId, t)
            if (!ok)
                return false
        }

        syncDao.deleteDeletedRecord(SyncTables.BOOK_DATA,bookId)
        return true
    }

    // CU
    // action:  upload the update info to the server
    private suspend fun book1000(fileId:String,t:SyncTimes) : Boolean {
        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        val bookDao = db.bookDao()

        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG,"book1000: invalid fileId")
            return false
        }

        val bookData = bookDao.getBookById(bookId)
        if (bookData == null) {
            Log.e(TAG,"book1000: no book data")
            return false
        }

        // send update to server
        val row = bookData.toRowJson(fileId)
        val rc = SyncManager.getInstance(appContext).postUpdate(SyncTables.BOOK_DATA,row)
        if (rc.ok) {
            bookDao.updateBookTimestamp(bookId,rc.updatedAt) // use server's authoritative timestamp
        }

        return rc.ok
    }

    // CU+SU
    // action:  if (SU==CU) nop  (they are in sync)
    //          if (SU > CU) update client
    //          else update server
    private suspend fun book1010(fileId:String,t:SyncTimes) : Boolean {
        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        val bookDao = db.bookDao()

        if (t.serverUpdate == null || t.clientUpdate == null) {
            Log.e(TAG,"book1010: unexpected null timestamps")
            return false
        }

        if (t.serverUpdate==t.clientUpdate)
            return true // nothing to do because they are in sync

        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG,"book1010: invalid fileId")
            return false
        }

        if (t.serverUpdate!! > t.clientUpdate!!) {

            val serverRow = serverRecords.find { it.fileId == fileId }
            if (serverRow == null) {
                Log.e(TAG,"book1010: failed to find server record")
                return false
            }

            bookDao.updateProgress(bookId,serverRow.progress)
            bookDao.updateBookTimestamp(bookId,serverRow.updatedAt)
        } else {
            return book1000(fileId,t)  // same as a CU
        }

        return true
    }

    // CU+SU+SD     (note: SU==SD, so we can ignore SU)
    // action:  if (SD >= CU) delete from client
    //          else  undelete on server
    private suspend fun book1011(fileId:String,t:SyncTimes) : Boolean {
        if (t.clientUpdate == null || t.serverUpdate == null || t.serverDelete == null) {
            Log.e(TAG,"book1011: unexpected null timestamps")
            return false
        }

        if (t.serverDelete!! >= t.clientUpdate!!) {
            // delete from the client
            val db = ReaderDatabase.getInstance(appContext)
            val bookDao = db.bookDao()

            val bookId = db.syncDao().getBookIdForFileId(fileId)
            if (bookId.isNullOrEmpty()) {
                Log.e(TAG,"book1011: invalid fileId")
                return false
            }

            // delete all the bookmarks/highlights for this book too
            db.bookmarkDao().deleteAllForBook(bookId)
            db.highlightDao().deleteAllForBook(bookId)
            bookDao.deleteByBookId(bookId)
        } else {
            // undelete on the server, which is the same as an update (book1000)
            return book1000(fileId,t)
        }

        return true;
    }

    // CU+CD
    // action:  if (CD>=CU) delete from client
    //          else nop
    private suspend fun book1100(fileId:String,t:SyncTimes) : Boolean {
        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        val bookDao = db.bookDao()

        if (t.clientUpdate == null || t.clientDelete == null) {
            Log.e(TAG,"book1100: unexpected null timestamps")
            return false
        }

        // whether we are keeping (CU) or deleting (CD) this locally, we need to put it on the server
        // So we first treat it like a "CU" (1000).
        // Then we decide if we delete it, in which case we delete it on the client and "soft" delete on the server
        //
        // The reason we copy this to server (even if we might delete it straight away) is that the
        // server "soft" deletes. So if the user ever reloads this book, then the progress, bookmarks
        // and highlights will magically reappear from the server.
        //
        val ok = book1000(fileId,t); // send it to the server
        if (!ok)
            return false;    // do not continue

        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG,"book1100: invalid fileId")
            return false
        }

        // should we now delete this?
        if (t.clientDelete!! >= t.clientUpdate!!) {

            // delete it on the server (we just added it)
            // note: when deleting a book, the server soft deletes the book and all highlights and bookmarks too
            // note: no point checking the error code, because we just added it so it should all be ok
            // note: no point updating local timestamps, because we're about to delete all the local records anyway
            SyncManager.getInstance(appContext).postDelete(SyncTables.BOOK_DATA,fileId)

            // delete it locally
            db.bookmarkDao().deleteAllForBook(bookId)
            db.highlightDao().deleteAllForBook(bookId)
            bookDao.deleteByBookId(bookId)

            // delete all references to bookId in deleted_records
            syncDao.deleteDeletedRecord(SyncTables.BOOKMARK, bookId)
            syncDao.deleteDeletedRecord(SyncTables.HIGHLIGHT, bookId)
        }

        syncDao.deleteDeletedRecord(SyncTables.BOOK_DATA,bookId)
        return true;
    }

    // CU+CD+SU
    // action:  if (CD>=CU)  // client say "delete"
    //              if (SU>=CD) update client  (ie undelete on the client)
    //              else delete from client, and delete from server
    //          else  // client says "update", equivalent to CU+SU [if (SU>=CU) update client else update server]
    //              book1010(fileId,t)
    private suspend fun book1110(fileId:String,t:SyncTimes) : Boolean {

        if (t.clientUpdate == null || t.clientDelete == null || t.serverUpdate == null) {
            Log.e(TAG,"book1110: unexpected null timestamps")
            return false
        }

        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG,"book1110: invalid fileId")
            return false
        }

        if (t.clientDelete!! >= t.clientUpdate!!) {
            // client says "delete"
            // what does server say?
            if (t.serverUpdate!! >= t.clientDelete!!) {
                // server wins: update the client
                // same as book1010  (which works in this use case because 1110 has those two bits set
                //                    and serverUpdate > clientUpdate)
                // get data from the server
                val ok = book1010(fileId,t)
                if (!ok)
                    return false
            } else {
                // server loses: delete from both server and client

                // delete on server
                SyncManager.getInstance(appContext).postDelete(SyncTables.BOOK_DATA,fileId)

                // delete it locally
                db.bookmarkDao().deleteAllForBook(bookId)
                db.highlightDao().deleteAllForBook(bookId)
                db.bookDao().deleteByBookId(bookId)
            }

        } else {
            // client says "update"
            // equivalent to CU+SU  (book1010 will sort out whether CU or SU wins)
            val ok = book1010(fileId,t)
            if (!ok)
                return false
        }

        syncDao.deleteDeletedRecord(SyncTables.BOOK_DATA,bookId)
        return true;
    }

    // CU+CD+SU+SD  (note: SU==SD, so we can ignore SU)
    // action:  if (CD>=CU) // client says "delete"
    //              delete from client (doesn't matter whether SD is older or newer then CD)
    //          else  // client says "update", equivalent to CU+SU+SD [if (SD>=CU) delete from client else undelete on server]
    //              book1011(fileId,t)
    private suspend fun book1111(fileId:String,t:SyncTimes) : Boolean {
        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        if (t.clientUpdate == null || t.clientDelete == null || t.serverUpdate == null || t.serverDelete == null) {
            Log.e(TAG,"book1111: unexpected null timestamps")
            return false
        }

        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG,"book1111: invalid fileId")
            return false
        }
        if (t.clientDelete!! >= t.clientUpdate!!) {
            // client says "delete"
            // delete from client (doesn't matter whether SD is older or newer then CD)
            // delete it locally
            db.bookmarkDao().deleteAllForBook(bookId)
            db.highlightDao().deleteAllForBook(bookId)
            db.bookDao().deleteByBookId(bookId)
        } else {
            // client says "update"
            // equivalent to CU+SU+SD (because we just eliminated CD)
            val ok = book1011(fileId,t)
            if (!ok)
                return false
        }

        syncDao.deleteDeletedRecord(SyncTables.BOOK_DATA,bookId)
        return true;
    }

}