package com.simplereader.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncDao {

        // ----------------------
        // Checkpoint operations
        // ----------------------
        @Query("SELECT lastSync FROM sync_checkpoint WHERE tableName = :tableName")
        suspend fun getCheckpoint(tableName: String): Long?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun updateCheckpoint(entity: SyncCheckpointEntity)

        @Query("SELECT * FROM sync_checkpoint")
        suspend fun getAllCheckpoints(): List<SyncCheckpointEntity>

        // ----------------------
        // Mapping operations
        // ----------------------
        @Query("SELECT bookId FROM sync_fileid_map WHERE fileId = :fileId LIMIT 1")
        suspend fun getBookIdForFileId(fileId: String): String?

        @Query("SELECT fileId FROM sync_fileid_map WHERE bookId = :bookId LIMIT 1")
        suspend fun getFileIdForBookId(bookId: String): String?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun updateMapping(entity: SyncFileIdMapEntity)

        @Query("DELETE FROM sync_fileid_map WHERE fileId IN (:fileIds)")
        suspend fun deleteMappingsByFileId(fileIds: List<String>)

        @Query("DELETE FROM sync_fileid_map WHERE bookId IN (:bookIds)")
        suspend fun deleteMappingsByBookId(bookIds: List<String>)

        // ----------------------
        // deleted_records table
        // ----------------------
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun addDeletedRecord(row: DeletedRecordsEntity)

        @Query("SELECT * FROM deleted_records WHERE tableName = :tabname")
        suspend fun getDeletedRecordByTable(tabname: String): List<DeletedRecordsEntity>

        @Query("DELETE FROM deleted_records WHERE bookId = :bookId AND tableName = :tabnam")
        suspend fun deleteDeletedRecord(tabnam: String, bookId: String)
}