package com.simplereader.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: SettingsEntity): Long
    // returns -1 if row already exists

    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun saveSettings(settings: SettingsEntity)

    //
    // sync servername, username & password
    //
    @Query("UPDATE settings SET syncServer = :syncServer, syncUser = :syncUser, syncFrequency = :freq WHERE id = 1")
    suspend fun updateSyncServerUserFreq(syncServer: String?, syncUser: String?, freq: Int)

    @Query("UPDATE settings SET syncPasswordIv = :iv, syncPasswordCt = :ct WHERE id = 1")
    suspend fun updateSyncPassword(iv: ByteArray?, ct: ByteArray?)

    // for SyncManager
    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<SettingsEntity?>
}