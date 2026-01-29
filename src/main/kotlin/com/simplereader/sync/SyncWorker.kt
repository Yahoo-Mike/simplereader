package com.simplereader.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.simplereader.book.BookDataEntity
import com.simplereader.bookmark.BookmarkEntity
import com.simplereader.highlight.HighlightEntity
import com.simplereader.note.NoteEntity
import com.simplereader.data.ReaderDatabase
import com.simplereader.util.FileUtil
import com.simplereader.util.MiscUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.collections.plusAssign

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
// worker that synchronises the book_data, highlight, note and bookmark tables with the server
//
class SyncWorker( appCtx: Context, params: WorkerParameters) : CoroutineWorker(appCtx, params) {
    companion object {
        private val TAG: String = MiscUtil::class.java.simpleName
    }

    private val appContext = appCtx

    override suspend fun doWork(): Result {

        // sanity check: are we connected to server?
        if (!TokenManager.isConnected(appContext)) {
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
            syncBookmarkData()
        }
        if (SyncTables.HIGHLIGHT in changed) {
            // sync highlight
            syncHighlightData()
        }
        if (SyncTables.NOTE in changed) {
            // sync note
            syncNoteData()
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
            put("progress", currentProgress ?: "")   // null-safe
            put("updatedAt", lastUpdated)            // UTC millis
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
        for ((fileId, tm) in timestamps) {
            val mask = tm.toMask()
            val rule = bookRules.firstOrNull { it.mask == mask }
            if (rule != null) {
                rule.action(fileId, tm)
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
                        fileId = SyncAPI.postUploadBook(
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
                val resp = SyncAPI.postGetSince(
                    SyncTables.BOOK_DATA, since, throttle
                )
                    ?: return@withContext ServerRecordsResult(false, emptyList())

                for (row in resp.rows) {
                    records += ServerRecord (
                        row.getString("fileId"),
                        row.optString("progress").takeUnless { it.isBlank() },
                        row.getLong("updatedAt"),
                        row.optLong("deletedAt", 0L).takeIf { it != 0L } )
                }

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
                val rc = mapBookId(bookId, t.sha256, t.filesize)
                if (!rc.ok)
                    continue    // problem on server, so leave this tombstone for another day

                var fileId = rc.fileId
                if (fileId.isNullOrEmpty()) {
                    //
                    // we couldn't resolve the fileId, so server doesn't have this book.
                    // If we still have it, we'll send it to the server before processing the delete.
                    // Else, just clean up the local db
                    //
                    val book = db.bookDao().getBookById(bookId)
                    if (book?.isOnDisk() == true && book.sha256 != null) {
                        fileId = SyncAPI.postUploadBook(
                            book.pubFile,
                            book.sha256,
                            book.filesize
                        )
                    }

                    if (fileId == null) {
                        // we don't have the book anymore, or the upload failed
                        // So just delete bookId from local database
                        db.withTransaction {
                            // delete all data for this bookid
                            db.bookmarkDao().deleteAllForBook(bookId)
                            db.highlightDao().deleteAllForBook(bookId)
                            db.noteDao().deleteAllForBook(bookId)
                            db.bookDao().deleteByBookId(bookId)

                            // delete all references to bookId in deleted_records
                            syncDao.deleteDeletedRecord(SyncTables.BOOK_DATA, bookId)
                            syncDao.deleteDeletedRecord(SyncTables.BOOKMARK, bookId)
                            syncDao.deleteDeletedRecord(SyncTables.HIGHLIGHT, bookId)
                            syncDao.deleteDeletedRecord(SyncTables.NOTE, bookId)
                        }
                    } else {
                        // we now have the fileId, so allow this delete to proceed
                        records.add(DeleteRecord(fileId, t.deletedAt))
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
    data class MapResult(
        val ok: Boolean,
        val fileId: String?
    )

    suspend fun mapBookId(bookId: String, sha256: String?, filesize: Long = 0L): MapResult {

        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()

        var fileId = syncDao.getFileIdForBookId(bookId)
        if (fileId.isNullOrEmpty()) {
            // ask the server to resolve fileId using stored checksum/size from tombstone
            if (!sha256.isNullOrBlank() && filesize > 0L) {
                val rc = SyncAPI.postResolve(sha256, filesize)
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

        return MapResult(true, fileId)
    }

    // returns fileId for this bookId, null if no map exists
    private suspend fun getBookId(fileId: String): String? =
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
        BookRule("NONE", 0b0000, ::nop),       // nothing to do
        BookRule("SD", 0b0001, ::nop),       // won't happen: if SD is non-null, then SU==SD
        BookRule("SU", 0b0010, ::book0010),
        BookRule("SU+SD", 0b0011, ::nop),       // deleted on server, doesn't exist on client

        BookRule("CD", 0b0100, ::nop),       // never existed on server, doesn't exist on client
        BookRule("CD+SD", 0b0101, ::nop),       // won't happen: if SD is non-null, then SU==SD
        BookRule("CD+SU", 0b0110, ::book0110),
        BookRule("CD+SU+SD", 0b0111, ::nop),       // already deleted on server and client

        BookRule("CU", 0b1000, ::book1000),
        BookRule("CU+SD", 0b1001, ::nop),       // won't happen: if SD is non-null, then SU==SD
        BookRule("CU+SU", 0b1010, ::book1010),
        BookRule("CU+SU+SD", 0b1011, ::book1011),

        BookRule("CU+CD", 0b1100, ::book1100),
        BookRule("CU+CD+SD", 0b1101, ::nop),       // won't happen: if SD is non-null, then SU==SD
        BookRule("CU+CD+SU", 0b1110, ::book1110),
        BookRule("ALL", 0b1111, ::book1111),
    )

    private suspend fun nop(fileId: String, t: SyncTimes): Boolean = true

    // SU: Server has a book that client doesn't have
    // action:  download the file from server and update client db
    private suspend fun book0010(fileId: String, t: SyncTimes): Boolean {
        if (t.serverUpdate == null) {
            Log.e(TAG, "book0010: unexpected null timestamps")
            return false
        }

        // download the book from the server, update all book_data/bookmarks/notes/highlights from server
        val rc1 = SyncAPI.getBook(fileId)
        if (!rc1.ok)
            return false
        if (rc1.filename == null)
            return false    // we need to know the filename

        // get record info from the server
        val rc2 = SyncAPI.postGet(SyncTables.BOOK_DATA, fileId)
        if (!rc2.ok)
            return false    // something went wrong

        val rowList = rc2.rows
        if (rowList.isNotEmpty()) {
            if (rowList.size > 1)
                Log.w(
                    TAG,
                    "book0010: only processing first of [${rc2.rows.size}] rows. Was only expecting one row."
                )

            val row = rowList[0] // process the first row only
            val progress = row.getString("progress")
            val updatedAt = row.getLong("updatedAt")
//            val deletedAt = row.optLong("deletedAt",0L)  // assume the caller checked if its deleted or not (and how it wants to proceed)

            val bookId = BookDataEntity.constructBookId(appContext, rc1.filename)
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
                null,  // TODO: currentBookmark
                rc1.sha256,
                rc1.filesize,
                updatedAt       // use the server's authoritative timestamp
            )
            val db = ReaderDatabase.getInstance(appContext)
            db.bookDao().insert(book)

            // map this fileId/bookId pair
            db.syncDao().updateMapping(SyncFileIdMapEntity(fileId, bookId))
        }
        return true
    }

    // CD+SU:  Server has update, client has deleted the book
    // action:  if (SU >= CD) download the file from server and update client db
    //          else delete from server
    private suspend fun book0110(fileId: String, t: SyncTimes): Boolean {
        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        val bookDao = db.bookDao()

        if (t.clientDelete == null || t.serverUpdate == null) {
            Log.e(TAG, "book0110: unexpected null timestamps")
            return false
        }

        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG, "book0110: invalid fileId")
            return false
        }

        if (t.clientDelete!! > t.serverUpdate!!) {
            // soft delete from the server
            val rc = SyncAPI.postDelete(SyncTables.BOOK_DATA, fileId)
            if (!rc.ok)
                return false        // leave delete record to be dealt with later (maybe server is down?)

            // delete it locally
            db.bookmarkDao().deleteAllForBook(bookId)
            db.highlightDao().deleteAllForBook(bookId)
            db.noteDao().deleteAllForBook(bookId)
            db.bookDao().getBookById(bookId)?.deleteFromDisk()      // physically delete the book
            bookDao.deleteByBookId(bookId)


            // delete all references to bookId in deleted_records
            syncDao.deleteDeletedRecord(SyncTables.BOOKMARK, bookId)
            syncDao.deleteDeletedRecord(SyncTables.HIGHLIGHT, bookId)
            syncDao.deleteDeletedRecord(SyncTables.NOTE, bookId)
        } else {
            // download the book from the server, update all book_data/notes/bookmarks/highlights from server
            //  note: this is now just an SU update
            val ok = book0010(fileId, t)
            if (!ok)
                return false
        }

        syncDao.deleteDeletedRecord(SyncTables.BOOK_DATA, bookId)
        return true
    }

    // CU:  Client has book that server doesn't
    // action:  upload the update file and info to the server
    private suspend fun book1000(fileId: String, t: SyncTimes): Boolean {
        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        val bookDao = db.bookDao()

        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG, "book1000: invalid fileId")
            return false
        }

        val bookData = bookDao.getBookById(bookId)
        if (bookData == null) {
            Log.e(TAG, "book1000: no book data")
            return false
        }

        // send update to server
        val row = JSONObject().apply {
            put("fileId", fileId)
            put("progress", bookData.currentProgress)
            put("updatedAt", bookData.lastUpdated)
        }.toString()

        val rc = SyncAPI.postUpdate(SyncTables.BOOK_DATA, row)
        if (rc.ok) {
            bookDao.updateTimestamp(
                bookId,
                rc.updatedAt
            ) // use server's authoritative timestamp
        }

        return rc.ok
    }

    // CU+SU:   Server and Client both have updates
    // action:  if (SU==CU) nop  (they are in sync)
    //          if (SU > CU) update client
    //          else update server
    private suspend fun book1010(fileId: String, t: SyncTimes): Boolean {
        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        val bookDao = db.bookDao()

        if (t.serverUpdate == null || t.clientUpdate == null) {
            Log.e(TAG, "book1010: unexpected null timestamps")
            return false
        }

        if (t.serverUpdate == t.clientUpdate)
            return true // nothing to do because they are in sync

        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG, "book1010: invalid fileId")
            return false
        }

        if (t.serverUpdate!! > t.clientUpdate!!) {

            val serverRow = serverRecords.find { it.fileId == fileId }
            if (serverRow == null) {
                Log.e(TAG, "book1010: failed to find server record")
                return false
            }

            bookDao.updateProgress(bookId, serverRow.progress)
            bookDao.updateTimestamp(bookId, serverRow.updatedAt)
        } else {
            return book1000(fileId, t)  // same as a CU
        }

        return true
    }

    // CU+SU+SD:   Server has deleted book, client has not
    //             (note: SU==SD, so we can ignore SU)
    // action:  if (SD >= CU) delete from client
    //          else  undelete on server
    private suspend fun book1011(fileId: String, t: SyncTimes): Boolean {
        if (t.clientUpdate == null || t.serverUpdate == null || t.serverDelete == null) {
            Log.e(TAG, "book1011: unexpected null timestamps")
            return false
        }

        if (t.serverDelete!! >= t.clientUpdate!!) {
            // delete from the client
            val db = ReaderDatabase.getInstance(appContext)
            val bookDao = db.bookDao()

            val bookId = db.syncDao().getBookIdForFileId(fileId)
            if (bookId.isNullOrEmpty()) {
                Log.e(TAG, "book1011: invalid fileId")
                return false
            }

            // delete all the bookmarks/highlights for this book too
            db.bookmarkDao().deleteAllForBook(bookId)
            db.highlightDao().deleteAllForBook(bookId)
            db.noteDao().deleteAllForBook(bookId)
            db.bookDao().getBookById(bookId)?.deleteFromDisk()      // physically delete the book
            bookDao.deleteByBookId(bookId)

        } else {
            // undelete on the server, which is the same as an update (book1000)
            return book1000(fileId, t)
        }

        return true;
    }

    // CU+CD:   Client has deleted the book, the server has never seen it
    // action:  if (CD>=CU) delete from client
    //          else nop
    private suspend fun book1100(fileId: String, t: SyncTimes): Boolean {
        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        val bookDao = db.bookDao()

        if (t.clientUpdate == null || t.clientDelete == null) {
            Log.e(TAG, "book1100: unexpected null timestamps")
            return false
        }

        // whether we are keeping (CU) or deleting (CD) locally, we need to put it on the server
        // So we first treat it like a "CU" (1000).
        // Then we decide if we delete it, in which case we delete it on the client and "soft" delete on the server
        //
        // The reason we copy this to server (even if we might delete it straight away) is that the
        // server "soft" deletes. So if the user ever reloads this book, then the progress, bookmarks, notes,
        // and highlights will magically reappear from the server.
        //
        val ok = book1000(fileId, t); // send it to the server
        if (!ok)
            return false;    // do not continue

        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG, "book1100: invalid fileId")
            return false
        }

        // should we now delete this?
        if (t.clientDelete!! >= t.clientUpdate!!) {

            // delete it on the server (we just added it)
            // note: when deleting a book, the server soft deletes the book and all highlights, notes and bookmarks too
            // note: no point checking the error code, because we just added it so it should all be ok
            // note: no point updating local timestamps, because we're about to delete all the local records anyway
            val rc = SyncAPI.postDelete(SyncTables.BOOK_DATA, fileId)
            if (!rc.ok)
                return false        // leave delete record to be dealt with later (maybe server is down?)

            // delete it locally
            db.bookmarkDao().deleteAllForBook(bookId)
            db.highlightDao().deleteAllForBook(bookId)
            db.noteDao().deleteAllForBook(bookId)
            db.bookDao().getBookById(bookId)?.deleteFromDisk()      // physically delete the book
            bookDao.deleteByBookId(bookId)

            // delete all references to bookId in deleted_records
            syncDao.deleteDeletedRecord(SyncTables.BOOKMARK, bookId)
            syncDao.deleteDeletedRecord(SyncTables.HIGHLIGHT, bookId)
            syncDao.deleteDeletedRecord(SyncTables.NOTE, bookId)
        }

        syncDao.deleteDeletedRecord(SyncTables.BOOK_DATA, bookId)
        return true;
    }

    // CU+CD+SU:   Client has deleted book, server has not
    // action:  if (CD>=CU)  // client say "delete"
    //              if (SU>=CD) update client  (ie undelete on the client)
    //              else delete from client, and delete from server
    //          else  // client says "update", equivalent to CU+SU [if (SU>=CU) update client else update server]
    //              book1010(fileId,t)
    private suspend fun book1110(fileId: String, t: SyncTimes): Boolean {

        if (t.clientUpdate == null || t.clientDelete == null || t.serverUpdate == null) {
            Log.e(TAG, "book1110: unexpected null timestamps")
            return false
        }

        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG, "book1110: invalid fileId")
            return false
        }

        if (t.clientDelete!! >= t.clientUpdate!!) {
            // client says "delete"
            // what does server say?
            if (t.serverUpdate!! >= t.clientDelete!!) {
                // server wins: update the client
                // same as book0010  (which works in this use case because 1110 has this bit set)
                // note: we use book0010 rather than book1010 because the former will also download
                //       the book, not just update the local db
                val ok = book0010(fileId, t)
                if (!ok)
                    return false
            } else {
                // server loses: delete from both server and client

                // delete on server
                // BUG you need to pass JSON {table,fileId,id}

                val rc =
                    SyncAPI.postDelete(SyncTables.BOOK_DATA, fileId)
                if (!rc.ok)
                    return false        // leave delete record to be dealt with later (maybe server is down?)

                // delete it locally
                db.bookmarkDao().deleteAllForBook(bookId)
                db.highlightDao().deleteAllForBook(bookId)
                db.noteDao().deleteAllForBook(bookId)
                db.bookDao().deleteByBookId(bookId)
            }

        } else {
            // client says "update"
            // equivalent to CU+SU  (book1010 will sort out whether CU or SU wins)
            val ok = book1010(fileId, t)
            if (!ok)
                return false
        }

        syncDao.deleteDeletedRecord(SyncTables.BOOK_DATA, bookId)
        return true;
    }

    // CU+CD+SU+SD  Server and Client have both deleted the book
    //              note: SU==SD, so we can ignore SU
    // action:  if (CD>=CU) // client says "delete"
    //              delete from client (doesn't matter whether SD is older or newer then CD)
    //          else  // client says "update", equivalent to CU+SU+SD [if (SD>=CU) delete from client else undelete on server]
    //              book1011(fileId,t)
    private suspend fun book1111(fileId: String, t: SyncTimes): Boolean {
        val db = ReaderDatabase.getInstance(appContext)
        val syncDao = db.syncDao()
        if (t.clientUpdate == null || t.clientDelete == null || t.serverUpdate == null || t.serverDelete == null) {
            Log.e(TAG, "book1111: unexpected null timestamps")
            return false
        }

        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty()) {
            Log.e(TAG, "book1111: invalid fileId")
            return false
        }
        if (t.clientDelete!! >= t.clientUpdate!!) {
            // client says "delete"
            // delete from client (doesn't matter whether SD is older or newer then CD)
            // delete it locally
            db.bookmarkDao().deleteAllForBook(bookId)
            db.highlightDao().deleteAllForBook(bookId)
            db.noteDao().deleteAllForBook(bookId)
            db.bookDao().deleteByBookId(bookId)
        } else {
            // client says "update", so undelete on server
            // equivalent to CU (because we just eliminated CD, and don't care about SU&SD at this point)
            val ok = book1000(fileId, t)
            if (!ok)
                return false

            /// make sure we have the book, otherwise try to download it
            val book = db.bookDao().getBookById(bookId)
            if (book?.isOnDisk() == false)
                SyncAPI.getBook(fileId)
        }

        syncDao.deleteDeletedRecord(SyncTables.BOOK_DATA, bookId)
        return true;
    }

    //********************************************************************************
    // BOOKMARKS, NOTES & HIGHLIGHTS - much simpler than books
    // bookmarks, notes & highlights share the same update/delete pattern.
    // There are only four rules: update/delete server/client
    //********************************************************************************

    data class Marker(
        val table: String, // bookmark/highlight
        val updateServerRow: suspend (fileId: String, idx: Int) -> Boolean,   // update server from client
        val updateClientRow: suspend (fileId: String, idx: Int) -> Boolean,   // update client from server
        val deleteServerRow: suspend (fileId: String, idx: Int) -> Boolean,   // delete server's row (soft delete)
        val deleteClientRow: suspend (fileId: String, idx: Int) -> Boolean    // delete row on client (locally)
    )

    private val bookmarkMarkers = Marker(
        "bookmark",
        ::updateServerBookmark,
        ::updateClientBookmark,
        ::deleteServerBookmark,
        ::deleteClientBookmark
    )
    private val highlightMarkers = Marker(
        "highlight",
        ::updateServerHighlight,
        ::updateClientHighlight,
        ::deleteServerHighlight,
        ::deleteClientHighlight
    )
    private val noteMarkers = Marker(
        "note",
        ::updateServerNote,
        ::updateClientNote,
        ::deleteServerNote,
        ::deleteClientNote
    )

    enum class MarkerType { BOOKMARK, HIGHLIGHT, NOTE }
    private fun markerTable(type: MarkerType): String = when (type) {
        MarkerType.BOOKMARK  -> SyncTables.BOOKMARK
        MarkerType.HIGHLIGHT -> SyncTables.HIGHLIGHT
        MarkerType.NOTE      -> SyncTables.NOTE
    }

    data class MarkerRule(
        val name: String,
        val mask: Int,
        val action: suspend (marker: MarkerType, fileId: String, id: Int, t: SyncTimes?) -> Boolean
    )

    val markerRules = listOf(
        MarkerRule("NONE", 0b0000, ::nop1),         // nothing to do
        MarkerRule("SD", 0b0001, ::nop1),           // won't happen: if SD is non-null, then SU==SD
        MarkerRule("SU", 0b0010, ::marker0010),
        MarkerRule("SU+SD", 0b0011, ::nop1),        // deleted on server, doesn't exist on client

        MarkerRule("CD", 0b0100, ::nop1),           // never existed on server, doesn't exist on client
        MarkerRule("CD+SD", 0b0101, ::nop1 ),       // won't happen: if SD is non-null, then SU==SD
        MarkerRule("CD+SU", 0b0110, ::marker0110),
        MarkerRule("CD+SU+SD", 0b0111, ::nop1),     // already deleted on server and client

        MarkerRule("CU", 0b1000, ::marker1000),
        MarkerRule("CU+SD", 0b1001, ::nop1),        // won't happen: if SD is non-null, then SU==SD
        MarkerRule("CU+SU", 0b1010, ::marker1010),
        MarkerRule("CU+SU+SD", 0b1011, ::marker1011),

        MarkerRule("CU+CD", 0b1100, ::marker1100),
        MarkerRule("CU+CD+SD",0b1101, ::nop1),      // won't happen: if SD is non-null, then SU==SD
        MarkerRule("CU+CD+SU", 0b1110, ::marker1110),
        MarkerRule("ALL", 0b1111, ::marker1111)
    )


    private var serverBookmarks: List<ServerMarker>  = emptyList()
    private var deleteBookmarks: List<DeleteMarker>  = emptyList()
    private var clientBookmarks: List<ClientMarker>  = emptyList()
    private var serverHighlights: List<ServerMarker> = emptyList()
    private var deleteHighlights: List<DeleteMarker> = emptyList()
    private var clientHighlights: List<ClientMarker> = emptyList()
    private var serverNotes: List<ServerMarker>  = emptyList()
    private var deleteNotes: List<DeleteMarker>  = emptyList()
    private var clientNotes: List<ClientMarker>  = emptyList()

    private suspend fun syncBookmarkData() {

        // clear the lists we'll be building
        serverBookmarks = emptyList()
        deleteBookmarks = emptyList()
        clientBookmarks = emptyList()

        // our map of timestamps, which we'll later compare
        val timestamps: MutableMap<MarkerKey, SyncTimes> = mutableMapOf()

        //
        // STEP ONE: build list of all timestamps
        //
        // get timestamps for all books known to server
        val result1 = getAllServerMarkers(MarkerType.BOOKMARK)
        var ok = result1.ok;
        serverBookmarks = result1.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get server bookmarks")
            return
        }
        for (record in serverBookmarks) {
            //add to timestamps
            timestamps.getOrPut(record.key) { SyncTimes() }.apply {
                serverUpdate = record.updatedAt
                serverDelete = record.deletedAt
            }
        }

        // get timestamps for all bookmarks deleted locally
        val result2 = getLocalDeleteMarkers(MarkerType.BOOKMARK)
        ok = result2.ok;
        deleteBookmarks = result2.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get local deleted bookmarks")
            return
        }
        for (record in deleteBookmarks) {
            //add to timestamps
            timestamps.getOrPut(record.key) { SyncTimes() }.apply {
                clientDelete = record.deletedAt
            }
        }

        // get timestamps for all local bookmarks
        val result3 = getClientMarkers(MarkerType.BOOKMARK)
        ok = result3.ok;
        clientBookmarks = result3.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get local bookmarks")
            return
        }
        for (record in clientBookmarks) {
            //add to timestamps
            timestamps.getOrPut(record.key) { SyncTimes() }.apply {
                clientUpdate = record.updatedAt
            }
        }

        //
        // STEP TWO: work out what action to take for each row in timestamps
        //
        for ((key, tm) in timestamps) {
            val mask = tm.toMask()
            val rule = markerRules.firstOrNull { it.mask == mask }
            if (rule != null) {
                rule.action(MarkerType.BOOKMARK, key.fileId, key.id.toInt(), tm)
            } else {
                Log.w(TAG, "syncBookmarkData: unexpected error")
            }
        }

    }

    private suspend fun syncHighlightData() {

        // clear the lists we'll be building
        serverHighlights = emptyList()
        deleteHighlights = emptyList()
        clientHighlights = emptyList()

        // our map of timestamps, which we'll later compare
        val timestamps: MutableMap<MarkerKey, SyncTimes> = mutableMapOf()

        //
        // STEP ONE: build list of all timestamps
        //
        // get timestamps for all highlights known to server
        val result1 = getAllServerMarkers(MarkerType.HIGHLIGHT)
        var ok = result1.ok;
        serverHighlights = result1.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get server highlights")
            return
        }
        for (record in serverHighlights) {
            //add to timestamps
            timestamps.getOrPut(record.key) { SyncTimes() }.apply {
                serverUpdate = record.updatedAt
                serverDelete = record.deletedAt
            }
        }

        // get timestamps for all highlights deleted locally
        val result2 = getLocalDeleteMarkers(MarkerType.HIGHLIGHT)
        ok = result2.ok;
        deleteHighlights = result2.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get local deleted highlights")
            return
        }
        for (record in deleteHighlights) {
            //add to timestamps
            timestamps.getOrPut(record.key) { SyncTimes() }.apply {
                clientDelete = record.deletedAt
            }
        }

        // get timestamps for all local highlights
        val result3 = getClientMarkers(MarkerType.HIGHLIGHT)
        ok = result3.ok;
        clientHighlights = result3.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get local highlights")
            return
        }
        for (record in clientHighlights) {
            //add to timestamps
            timestamps.getOrPut(record.key) { SyncTimes() }.apply {
                clientUpdate = record.updatedAt
            }
        }

        //
        // STEP TWO: work out what action to take for each row in timestamps
        //
        for ((key, tm) in timestamps) {
            val mask = tm.toMask()
            val rule = markerRules.firstOrNull { it.mask == mask }
            if (rule != null) {
                rule.action(MarkerType.HIGHLIGHT, key.fileId, key.id.toInt(), tm)
            } else {
                Log.w(TAG, "syncHighlightData: unexpected error")
            }
        }

    }

    private suspend fun syncNoteData() {

        // clear the lists we'll be building
        serverNotes = emptyList()
        deleteNotes = emptyList()
        clientNotes = emptyList()

        // our map of timestamps, which we'll later compare
        val timestamps: MutableMap<MarkerKey, SyncTimes> = mutableMapOf()

        //
        // STEP ONE: build list of all timestamps
        //
        // get timestamps for all books known to server
        val result1 = getAllServerMarkers(MarkerType.NOTE)
        var ok = result1.ok;
        serverNotes = result1.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get notes from server")
            return
        }
        for (record in serverNotes) {
            //add to timestamps
            timestamps.getOrPut(record.key) { SyncTimes() }.apply {
                serverUpdate = record.updatedAt
                serverDelete = record.deletedAt
            }
        }

        // get timestamps for all notes deleted locally
        val result2 = getLocalDeleteMarkers(MarkerType.NOTE)
        ok = result2.ok;
        deleteNotes = result2.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get local deleted notes")
            return
        }
        for (record in deleteNotes) {
            //add to timestamps
            timestamps.getOrPut(record.key) { SyncTimes() }.apply {
                clientDelete = record.deletedAt
            }
        }

        // get timestamps for all local notes
        val result3 = getClientMarkers(MarkerType.NOTE)
        ok = result3.ok;
        clientNotes = result3.records
        if (!ok) {
            Log.w(TAG, "sync failed: could not get local notes")
            return
        }
        for (record in clientNotes) {
            //add to timestamps
            timestamps.getOrPut(record.key) { SyncTimes() }.apply {
                clientUpdate = record.updatedAt
            }
        }

        //
        // STEP TWO: work out what action to take for each row in timestamps
        //
        for ((key, tm) in timestamps) {
            val mask = tm.toMask()
            val rule = markerRules.firstOrNull { it.mask == mask }
            if (rule != null) {
                rule.action(MarkerType.NOTE, key.fileId, key.id.toInt(), tm)
            } else {
                Log.w(TAG, "syncNoteData: unexpected error")
            }
        }

    }

    // deletes this marker from the "deleted_records" table
    private suspend fun deleteDeletedMarker(marker: MarkerType,fileId: String, id: Int) : Boolean {
        val syncDao = ReaderDatabase.getInstance(appContext).syncDao()
        val bookId = syncDao.getBookIdForFileId(fileId)
        if (bookId==null) {
            Log.w(TAG, "unknown fileId")
            return false
        }
        syncDao.deleteDeletedRecordWithId(markerTable(marker), bookId, id)
        return true
    }

    private suspend fun nop1(marker: MarkerType, fileId: String, id: Int, t: SyncTimes?): Boolean {
        if (t?.clientDelete != null)
            deleteDeletedMarker(marker,fileId,id) // cleanup deleted_records table entry
        return true
    }

    // SU : new marker on the server, copy it to the client
    private suspend fun marker0010(marker: MarkerType, fileId: String, id: Int, t: SyncTimes?): Boolean {
        // update
        return when (marker) {
            MarkerType.BOOKMARK -> bookmarkMarkers.updateClientRow(fileId,id)
            MarkerType.HIGHLIGHT -> highlightMarkers.updateClientRow(fileId,id)
            MarkerType.NOTE -> noteMarkers.updateClientRow(fileId,id)
        }
    }

    // CD+SU:
    private suspend fun marker0110(marker: MarkerType, fileId: String, id: Int, t: SyncTimes?): Boolean {
        if (t == null) return false
        if (t.serverUpdate == null || t.clientDelete == null)
                return false

        val ok = if (t.serverUpdate!! >= t.clientDelete!!) { // SU wins
                    when (marker) {
                        MarkerType.BOOKMARK -> bookmarkMarkers.updateClientRow(fileId,id)
                        MarkerType.HIGHLIGHT -> highlightMarkers.updateClientRow(fileId,id)
                        MarkerType.NOTE -> noteMarkers.updateClientRow(fileId,id)
                    }
                } else { // CD wins
                    when (marker) {
                        MarkerType.BOOKMARK -> bookmarkMarkers.deleteServerRow(fileId,id)
                        MarkerType.HIGHLIGHT -> highlightMarkers.deleteServerRow(fileId,id)
                        MarkerType.NOTE -> noteMarkers.deleteServerRow(fileId,id)
                    }
                }

        if (ok)
            deleteDeletedMarker(marker,fileId,id)

        return ok
    }

    // CU: new marker on the client, copy it to the server
    private suspend fun marker1000(marker: MarkerType, fileId: String, id: Int, t: SyncTimes?): Boolean {
        return when (marker) {
            MarkerType.BOOKMARK -> bookmarkMarkers.updateServerRow(fileId,id)
            MarkerType.HIGHLIGHT -> highlightMarkers.updateServerRow(fileId,id)
            MarkerType.NOTE -> noteMarkers.updateServerRow(fileId,id)
        }
    }

    // CU+SU
    private suspend fun marker1010(marker: MarkerType, fileId: String, id: Int, t: SyncTimes?): Boolean {
        if (t == null) return false

        if (t.serverUpdate == null || t.clientUpdate == null)
            return false

        if (t.serverUpdate == t.clientUpdate)
            return true     // nop, client and server in sync

        if (t.serverUpdate!! > t.clientUpdate!!) { // SU wins
            when (marker) {
                MarkerType.BOOKMARK -> bookmarkMarkers.updateClientRow(fileId,id)
                MarkerType.HIGHLIGHT -> highlightMarkers.updateClientRow(fileId,id)
                MarkerType.NOTE -> noteMarkers.updateClientRow(fileId,id)
            }
        } else { // CU wins
            when (marker) {
                MarkerType.BOOKMARK -> bookmarkMarkers.updateServerRow(fileId,id)
                MarkerType.HIGHLIGHT -> highlightMarkers.updateServerRow(fileId,id)
                MarkerType.NOTE -> noteMarkers.updateServerRow(fileId,id)
            }
        }

        return true
    }

    // CU+SU+SD:  we assume SD==SD, so this is same as CU+SD (1001)
    private suspend fun marker1011(marker: MarkerType, fileId: String, id: Int, t: SyncTimes?): Boolean {
        if (t == null) return false
        if (t.clientUpdate == null || t.serverUpdate==null || t.serverDelete == null)
            return false

        if (t.serverDelete!! > t.clientUpdate!!) { // SD wins
            when (marker) {
                MarkerType.BOOKMARK -> bookmarkMarkers.deleteClientRow(fileId,id)
                MarkerType.HIGHLIGHT -> highlightMarkers.deleteClientRow(fileId,id)
                MarkerType.NOTE -> noteMarkers.deleteClientRow(fileId,id)
            }
        } else { // CU wins
            when (marker) {
                MarkerType.BOOKMARK -> bookmarkMarkers.updateServerRow(fileId,id)
                MarkerType.HIGHLIGHT -> highlightMarkers.updateServerRow(fileId,id)
                MarkerType.NOTE -> noteMarkers.updateServerRow(fileId,id)
            }
        }

        return true
    }

    // CU+CD: server has not seen this book
    // untested: hard to unit test because we've generally processed the delete before there's another update
    private suspend fun marker1100(marker: MarkerType, fileId: String, id: Int, t: SyncTimes?): Boolean {
        if (t == null) return false
        if (t.clientUpdate == null || t.clientDelete == null)
            return false

        var ok = true
        if (t.clientDelete!! >= t.clientUpdate!!) { // CD wins
            ok = when (marker) {
                MarkerType.BOOKMARK -> bookmarkMarkers.deleteClientRow(fileId,id)
                MarkerType.HIGHLIGHT -> highlightMarkers.deleteClientRow(fileId,id)
                MarkerType.NOTE -> noteMarkers.deleteClientRow(fileId,id)
            }
        } // else CU wins = nop

        if (ok)
            deleteDeletedMarker(marker,fileId,id)
        return ok
    }

    // CU+CD+SU
    // untested: hard to unit test because we've generally processed the delete before there's another update
    private suspend fun marker1110(marker: MarkerType, fileId: String, id: Int, t: SyncTimes?): Boolean {
        if (t == null) return false
        if (t.clientUpdate == null || t.clientDelete == null || t.serverUpdate==null )
            return false

        var ok = true
        if (t.clientDelete!! >= t.clientUpdate!!) { // client says "delete"
            if (t.serverUpdate!! >= t.clientDelete!!) {  // SU wins
                ok = when (marker) {
                    MarkerType.BOOKMARK -> bookmarkMarkers.updateClientRow(fileId,id)
                    MarkerType.HIGHLIGHT -> highlightMarkers.updateClientRow(fileId,id)
                    MarkerType.NOTE -> noteMarkers.updateClientRow(fileId,id)
                }
            } else { // CD wins
                ok = when (marker) {
                    MarkerType.BOOKMARK  -> {   bookmarkMarkers.deleteClientRow(fileId,id)
                                                bookmarkMarkers.deleteServerRow(fileId,id)  }
                    MarkerType.HIGHLIGHT -> {   highlightMarkers.deleteClientRow(fileId,id)
                                                highlightMarkers.deleteServerRow(fileId,id) }
                    MarkerType.NOTE      -> {   noteMarkers.deleteClientRow(fileId,id)
                                                noteMarkers.deleteServerRow(fileId,id) }
                }
            }
        } else { // client says "update" - same as CU+SU
            ok = marker1010( marker, fileId, id, t )
        }

        if (ok)
            deleteDeletedMarker(marker,fileId,id)
        return ok
    }

    // CU+CD+SU+SD:  we assume SU==SD, so this is like 1101
    // untested: hard to unit test because we've generally processed the delete before there's another update
    private suspend fun marker1111(marker: MarkerType, fileId: String, id: Int, t: SyncTimes?): Boolean {
        if (t == null) return false
        if (t.clientUpdate == null || t.clientDelete == null || t.serverUpdate==null || t.serverDelete==null)
            return false

        var ok = true
        if ((t.clientUpdate!! > t.clientDelete!!) && (t.clientUpdate!! > t.serverDelete!!)) { // CU wins
            ok = when (marker) {
                MarkerType.BOOKMARK -> bookmarkMarkers.updateServerRow(fileId,id)
                MarkerType.HIGHLIGHT -> highlightMarkers.updateServerRow(fileId,id)
                MarkerType.NOTE -> noteMarkers.updateServerRow(fileId,id)
            }
        } else { // CD and/or SD wins, so delete from client
            ok = when (marker) {
                MarkerType.BOOKMARK -> bookmarkMarkers.deleteClientRow(fileId, id)
                MarkerType.HIGHLIGHT -> highlightMarkers.deleteClientRow(fileId, id)
                MarkerType.NOTE -> noteMarkers.deleteClientRow(fileId, id)
            }
        }

        if (ok)
            deleteDeletedMarker(marker,fileId,id)
        return ok
    }

    private fun BookmarkEntity.toRowJson(fileId: String): String {
        // need to post update("bookmark",{fileId,id,locator,label,updatedAt})
        return JSONObject().apply {
            put("fileId", fileId)                    // use the given file_id, not bookId
            put("id", id)
            put("locator", locator)
            put("label", label)
            put("updatedAt", lastUpdated)
        }.toString()
    }

    private fun HighlightEntity.toRowJson(fileId: String): String {
        // need to post update("highlight",{fileId,id,selection,label,colour,updatedAt})
        return JSONObject().apply {
            put("fileId", fileId)                    // use the given file_id, not bookId
            put("id", id)
            put("selection", selection)
            put("label", label)
            put("colour", colour)
            put("updatedAt", lastUpdated)            // UTC millis
        }.toString()
    }

    private fun NoteEntity.toRowJson(fileId: String): String {
        // need to post update("note",{fileId,id,locator,label,updatedAt})
        return JSONObject().apply {
            put("fileId", fileId)                    // use the given file_id, not bookId
            put("id", id)
            put("locator", locator)
            put("content", content)
            put("updatedAt", lastUpdated)
        }.toString()
    }

    private suspend fun updateServerBookmark(fileId: String, idx: Int) : Boolean {   // update server from client
        val db = ReaderDatabase.getInstance(appContext)
        val bookId = db.syncDao().getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty())
            return false
        val bookmark = db.bookmarkDao().getBookmark(bookId,idx)
        if (bookmark == null)
            return false    // can't get data to send to server

        val rc = SyncAPI.postUpdate(SyncTables.BOOKMARK, bookmark.toRowJson(fileId))
        if (rc.ok)
            db.bookmarkDao().updateTimestamp(bookId,bookmark.id,rc.updatedAt)   // adopt server's authoritative timestamp
        return rc.ok
    }

    private suspend fun updateClientBookmark(fileId: String, idx: Int) : Boolean {   // update client from server

        val rc = SyncAPI.postGet(SyncTables.BOOKMARK, fileId,idx)
        if (!rc.ok)
            return false    // couldn't get data back from server

        if (rc.rows.isEmpty())
            return false    // row not found on server

        if (rc.rows.size > 1)
            Log.w(TAG, "more than one bookmark found for [$fileId/$idx].  Using first one only.")

        val row = rc.rows[0]

        val db = ReaderDatabase.getInstance(appContext)
        val bookId = db.syncDao().getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty())
            return false

        val newBookmark = BookmarkEntity(bookId,idx,
                                row.getString("label"),
                                row.getString("locator"),
                                row.getLong("updatedAt") )
        db.bookmarkDao().insertBookmark(newBookmark)

        return true
    }

    private suspend fun deleteServerBookmark(fileId: String, idx: Int) : Boolean {   // delete server's row (soft delete)
        val rc = SyncAPI.postDelete(SyncTables.BOOKMARK, fileId,idx)
        return rc.ok
    }

    private suspend fun deleteClientBookmark(fileId: String, idx: Int) : Boolean {   // delete row on client (locally)
        val db = ReaderDatabase.getInstance(appContext)
        val bookId = db.syncDao().getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty())
            return false
        val row = db.bookmarkDao().getBookmark(fileId,idx)
        if (row != null)
            db.bookmarkDao().deleteBookmark(row)
        return true
    }

    // need to post update("highlight",{fileId,id,selection,label,colour,updatedAt})
    private suspend fun updateServerHighlight(fileId: String, idx: Int) : Boolean {   // update server from client
        val db = ReaderDatabase.getInstance(appContext)
        val bookId = db.syncDao().getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty())
            return false
        val highlight = db.highlightDao().getHighlight(bookId,idx)
        if (highlight == null)
            return false    // can't get data to send to server

        val rc = SyncAPI.postUpdate(SyncTables.HIGHLIGHT, highlight.toRowJson(fileId))
        if (rc.ok)
            db.highlightDao().updateTimestamp(bookId,highlight.id,rc.updatedAt)   // adopt server's authoritative timestamp
        return rc.ok
    }

    private suspend fun updateClientHighlight(fileId: String, idx: Int) : Boolean {   // update client from server
        val rc = SyncAPI.postGet(SyncTables.HIGHLIGHT, fileId,idx)
        if (!rc.ok)
            return false    // couldn't get data back from server

        if (rc.rows.isEmpty())
            return false    // row not found on server

        if (rc.rows.size > 1)
            Log.w(TAG, "more than one highlight found for [$fileId/$idx].  Using first one only.")

        val row = rc.rows[0]

        val db = ReaderDatabase.getInstance(appContext)
        val bookId = db.syncDao().getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty())
            return false

        val newHighlight = HighlightEntity(bookId,idx,
            row.getString("selection"),
            row.getString("label"),
            row.getString("colour"),
            row.getLong("updatedAt") )
        db.highlightDao().insertHighlight(newHighlight)

        return true
    }

    private suspend fun deleteServerHighlight(fileId: String, idx: Int) : Boolean {   // delete server's row (soft delete)
        val rc = SyncAPI.postDelete(SyncTables.HIGHLIGHT, fileId,idx)
        return rc.ok
    }

    private suspend fun deleteClientHighlight(fileId: String, idx: Int) : Boolean {   // delete row on client (locally)
        val db = ReaderDatabase.getInstance(appContext)
        val bookId = db.syncDao().getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty())
            return false
        val row = db.highlightDao().getHighlight(fileId,idx)
        if (row != null)
            db.highlightDao().deleteHighlight(row)
        return true
    }

    // need to post update("note",{fileId,id,location,content,updatedAt})
    private suspend fun updateServerNote(fileId: String, idx: Int) : Boolean {   // update server from client
        val db = ReaderDatabase.getInstance(appContext)
        val bookId = db.syncDao().getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty())
            return false
        val note = db.noteDao().getNote(bookId,idx)
        if (note == null)
            return false    // can't get data to send to server

        val rc = SyncAPI.postUpdate(SyncTables.NOTE, note.toRowJson(fileId))
        if (rc.ok)
            db.noteDao().updateTimestamp(bookId,note.id,rc.updatedAt)   // adopt server's authoritative timestamp
        return rc.ok
    }

    private suspend fun updateClientNote(fileId: String, idx: Int) : Boolean {   // update client from server

        val rc = SyncAPI.postGet(SyncTables.NOTE, fileId,idx)
        if (!rc.ok)
            return false    // couldn't get data back from server

        if (rc.rows.isEmpty())
            return false    // row not found on server

        if (rc.rows.size > 1)
            Log.w(TAG, "more than one note found for [$fileId/$idx].  Using first one only.")

        val row = rc.rows[0]

        val db = ReaderDatabase.getInstance(appContext)
        val bookId = db.syncDao().getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty())
            return false

        val newNote = NoteEntity(bookId,idx,
            row.getString("locator"),
            row.getString("content"),
            row.getLong("updatedAt") )
        db.noteDao().insert(newNote)

        return true
    }

    private suspend fun deleteServerNote(fileId: String, idx: Int) : Boolean {   // delete server's row (soft delete)
        val rc = SyncAPI.postDelete(SyncTables.NOTE, fileId,idx)
        return rc.ok
    }

    private suspend fun deleteClientNote(fileId: String, idx: Int) : Boolean {   // delete row on client (locally)
        val db = ReaderDatabase.getInstance(appContext)
        val bookId = db.syncDao().getBookIdForFileId(fileId)
        if (bookId.isNullOrEmpty())
            return false
        val row = db.noteDao().getNote(fileId,idx)
        if (row != null)
            db.noteDao().delete(row)
        return true
    }

    private data class ServerMarkerResult(
        val ok: Boolean,
        val records: List<ServerMarker>
    )
    private suspend fun getAllServerMarkers(marker: MarkerType): ServerMarkerResult =
        withContext(Dispatchers.IO) {

            val records = mutableListOf<ServerMarker>()
            var since = 0L  // all records
            val throttle = 100 // 100 records at a time

            while (true) {
                val resp = SyncAPI.postGetSince(
                    markerTable(marker), since, throttle
                )
                    ?: return@withContext ServerMarkerResult(false, emptyList())

                for (row in resp.rows) {
                    records += ServerMarker (
                        MarkerKey(row.getString("fileId"),row.getInt("id")),
                        row.getLong("updatedAt"),
                        row.optLong("deletedAt",0L).takeIf { it != 0L } )
                }

                if (resp.rows.size < throttle) break // we've got them all, so we can leave now...

                since = resp.nextSince  // we need to read more
            }

            return@withContext ServerMarkerResult(true, records)
        }


    private data class DeleteMarkerResult(
        val ok: Boolean,
        val records: List<DeleteMarker> = emptyList()
    )
    private suspend fun getLocalDeleteMarkers(marker: MarkerType): DeleteMarkerResult =
        withContext(Dispatchers.IO) {
            val db = ReaderDatabase.getInstance(appContext)
            val syncDao = db.syncDao()

            // 1oad tombstones for bookmark/note/highlight, if there are none, we can gracefully leave
            val tombstones = syncDao.getDeletedRecordByTable(markerTable(marker))
            if (tombstones.isNullOrEmpty())
                return@withContext DeleteMarkerResult(true) // return with empty list

            val records = mutableListOf<DeleteMarker>()

            // iterate through all the tombstoned books
            for (t in tombstones) {
                val bookId = t.bookId
                val rc = mapBookId(bookId, t.sha256, t.filesize)
                if (!rc.ok)
                    continue    // problem on server, so leave this tombstone for another day

                val fileId = rc.fileId
                if (fileId.isNullOrEmpty())
                    continue    // no fileId, so we can't proceed.  Just go to the next one.

                // we now have the fileId, so allow this delete to proceed
                records.add(DeleteMarker(MarkerKey(fileId,t.idx), t.deletedAt))
            } //end for(all tombstones)

            return@withContext DeleteMarkerResult(true, records)
        }

    private data class ClientMarkerResult(
        val ok: Boolean,
        val records: List<ClientMarker>
    )
    private suspend fun getClientMarkers(marker: MarkerType): ClientMarkerResult {
        val db = ReaderDatabase.getInstance(appContext)
        val bookDao = db.bookDao()
        val books = bookDao.getAllBooks()

        val records = mutableListOf<ClientMarker>()
        for (book in books) {

            val rc = mapBookId(book.bookId, book.sha256, book.filesize)
            if (!rc.ok)
                continue    // couldnt' get fileId
            val fileId = rc.fileId
            if (fileId.isNullOrEmpty())
                continue    // we don't have the fileId, so just skip this one & goto next

            if (marker == MarkerType.BOOKMARK) {
                val bookmarks = db.bookmarkDao().getBookmarksForBook(book.bookId)
                for (bm in bookmarks)
                    records.add(ClientMarker(MarkerKey(fileId,bm.id), bm.lastUpdated))
            }
            if (marker == MarkerType.HIGHLIGHT) {
                val highlights = db.highlightDao().getHighlightsForBook(book.bookId)
                for (hl in highlights)
                    records.add(ClientMarker(MarkerKey(fileId,hl.id), hl.lastUpdated))
            }
            if (marker == MarkerType.NOTE) {
                val notes = db.noteDao().getNotesForBook(book.bookId)
                for (nn in notes)
                    records.add(ClientMarker(MarkerKey(fileId,nn.id), nn.lastUpdated))
            }
        }

        return ClientMarkerResult(true, records)
    }

}