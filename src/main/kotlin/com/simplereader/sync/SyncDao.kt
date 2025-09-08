package com.simplereader.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncDao {

    @Query("SELECT * FROM sync_table WHERE tableName = :table")
    suspend fun getSync(table: String): SyncEntity?

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun saveSync(sync: SyncEntity)

}