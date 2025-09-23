package com.simplereader.settings

import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.simplereader.settings.Settings.Companion.DEFAULT_FONT
import com.simplereader.settings.Settings.Companion.DEFAULT_FONT_SIZE
import com.simplereader.util.Encrypted
import com.simplereader.util.decryptToString
import com.simplereader.util.encryptString
import com.simplereader.util.getOrCreateSecretKey

//
// stores & retrieves global reader settings from database
//
class SettingsRepository(private val db: RoomDatabase, private val dao: SettingsDao) {

    private val cryptoKey by lazy { getOrCreateSecretKey() }    // local crypto key (in AndroidKeyStore)

    suspend fun getSettings(): Settings? {
        val settings = dao.getSettings()
        return settings?.toReaderSettings() ?: Settings.DEFAULT
    }

    suspend fun saveSettings(settings: Settings) {
        dao.saveSettings(settings.toSettingsEntity())
    }

    // update sync server name and username
    suspend fun updateOrInsertServerAndUser(server: String?, user: String?) {

        dao.insertIfMissing(SettingsEntity(
            id = 1,
            font = DEFAULT_FONT.name,
            fontSize = DEFAULT_FONT_SIZE,
            syncServer = null,
            syncUser = null,
            syncPasswordIv = null,
            syncPasswordCt = null
        ))
        dao.updateSyncServerAndUser(server, user)
    }

    // update password for sync server
    suspend fun updateOrInsertPassword(plain: String?) {
        // ensure the singleton row exists
        dao.insertIfMissing(
            SettingsEntity(
                id = 1,
                font = DEFAULT_FONT.name,
                fontSize = DEFAULT_FONT_SIZE,
                syncServer = null,
                syncUser = null,
                syncPasswordIv = null,
                syncPasswordCt = null
            )
        )

        var iv: ByteArray? = null
        var ct: ByteArray? = null
        if (plain != null) {
            val enc = encryptString(cryptoKey, plain)
            iv = enc.iv
            ct = enc.ct
        }

        dao.updateSyncPassword(iv, ct)
    }

    // read the password for the sync server
    suspend fun readPassword(): String? {
        val s = dao.getSettings() ?: return null
        val iv = s.syncPasswordIv ?: return null
        val ct = s.syncPasswordCt ?: return null
        return try {
            decryptToString(cryptoKey, Encrypted(iv, ct))
        } catch (e: Exception) {
            null     // tampered or corrupt
        }
    }
}
