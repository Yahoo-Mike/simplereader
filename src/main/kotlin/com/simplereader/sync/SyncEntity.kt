package com.simplereader.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

//
// Maps the server-assigned fileId (global across devices) to a local bookId (primary key in local db).
//
// NOTE: We keep a UNIQUE index on bookId so a book maps to at most one fileId.
//
@Entity(
    tableName = "sync_fileid_map",
    indices = [Index(value = ["bookId"], unique = true)]
)
data class SyncFileIdMapEntity(
    @PrimaryKey val fileId: String,
    val bookId: String
)

//
// Stores the server-side incremental sync checkpoint per local table.
// lastSync is a server timestamp (e.g., epoch millis) returned by server.
//
@Entity(tableName = "sync_checkpoint")
data class SyncCheckpointEntity(
    @PrimaryKey val tableName: String, // see SyncTables
    val lastSync: Long = 0L
)

//
// keep track of records we've deleted, for syncing later with the server
//
@Entity(
    tableName = "deleted_records",
    primaryKeys = ["tableName","bookId","idx"],
)
data class DeletedRecordsEntity(
    val tableName: String,      // see SyncTables
    val bookId: String,
    val idx: Int = -1,         // if tableName=bookmark or highlight
    val sha256: String? = null, // if tableName=book_data, checksum of the file
    val filesize: Long = 0L,    // if tableName=book_data, size of the file
    val deletedAt : Long,       // timestamp of deletion
)

//
// tablenames that get sync'ed. Matches existing schema names
//
object SyncTables {
    const val BOOK_DATA = "book_data"
    const val BOOKMARK = "bookmark"
    const val HIGHLIGHT = "highlight"

    val ALL = arrayOf(BOOK_DATA, BOOKMARK, HIGHLIGHT)
}