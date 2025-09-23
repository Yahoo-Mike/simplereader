package com.simplereader.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncDao {

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

        @Query("DELETE FROM deleted_records WHERE idx = :id AND bookId = :bookId AND tableName = :tabnam")
        suspend fun deleteDeletedRecordWithId(tabnam: String, bookId: String, id: Int)
}