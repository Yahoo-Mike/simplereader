package com.simplereader.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.simplereader.data.ReaderDatabase
import com.simplereader.util.MiscUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


data class SyncTimes(
    var clientUpdate: Long? = null,
    var clientDelete: Long? = null,
    var serverUpdate: Long? = null,
    var serverDelete: Long? = null,
)

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
        if ( !TokenManager.isConnected() ) {
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

    private suspend fun syncBookData() {

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

for (tm in timestamps) {
    Log.d("YM DEBUG", "${tm.key}: ${tm.value.clientUpdate} ${tm.value.clientDelete} ${tm.value.serverUpdate} ${tm.value.serverDelete}")
}

        //
        // STEP TWO: work out what action to take for each row in timestamps
        //
        for (row in timestamps) {
            //syncAction(ctx, token, row)
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
            val tombstones = syncDao.getByTable(SyncTables.BOOK_DATA)
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
                    // server doesn’t have this book → purge local data & tombstones
                    db.withTransaction {
                        // delete all data for this bookid
                        db.bookmarkDao().deleteAllForBook(bookId)
                        db.highlightDao().deleteAllForBook(bookId)
                        db.bookDao().deleteByBookId(bookId)

                        // delete all references to bookId in deleted_records
                        syncDao.deleteByBookId(SyncTables.BOOK_DATA, bookId)
                        syncDao.deleteByBookId(SyncTables.BOOKMARK, bookId)
                        syncDao.deleteByBookId(SyncTables.HIGHLIGHT, bookId)
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
        val settingsDao = db.settingsDao()

        // prepare for requests to server
        val settings = settingsDao.getSettings()
        val serverConfig = ServerConfig.fromSettings(settings)
        if (serverConfig == null) {
            Log.w(TAG,"login retry failed: no server settings configured")
            return MapResult(false,null)
        }
        if (!serverConfig.isFullyConfigured()) {
            Log.w(TAG,"login retry failed: server settings not fully configured")
            return MapResult(false,null)
        }

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

}