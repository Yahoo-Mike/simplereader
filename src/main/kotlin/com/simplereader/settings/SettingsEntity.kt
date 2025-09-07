package com.simplereader.settings

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.readium.r2.navigator.preferences.FontFamily

@Entity(tableName = "settings")
data class SettingsEntity (
    @PrimaryKey val id: Int = 1, // always a singleton row for global settings
                                 // Room requires a primary key
    val font: String,
    val fontSize: Double,

    val syncServer: String?,        // address of server running sync daemon
    val syncUser: String?,          // username for login to server
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val syncPasswordIv: ByteArray?,     // GCM IV
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val syncPasswordCt: ByteArray?      // ciphertext
)  {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SettingsEntity) return false

        return id == other.id &&
                font == other.font &&
                fontSize == other.fontSize &&
                syncServer == other.syncServer &&
                syncUser == other.syncUser &&
                (syncPasswordIv?.contentEquals(other.syncPasswordIv) ?: (other.syncPasswordIv == null)) &&
                (syncPasswordCt?.contentEquals(other.syncPasswordCt) ?: (other.syncPasswordCt == null))
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + font.hashCode()
        result = 31 * result + fontSize.hashCode()
        result = 31 * result + (syncServer?.hashCode() ?: 0)
        result = 31 * result + (syncUser?.hashCode() ?: 0)
        result = 31 * result + (syncPasswordIv?.contentHashCode() ?: 0)
        result = 31 * result + (syncPasswordCt?.contentHashCode() ?: 0)
        return result
    }
}

// extension functions ---
fun Settings.toSettingsEntity(): SettingsEntity = SettingsEntity(
    1,              // singleton
    font = font.name,
    fontSize = fontSize,
    null,
    null,
    null,   // initialisation vector
    null   // ciphertext
)

fun SettingsEntity.toReaderSettings(): Settings = Settings(
    font = FontFamily(font),
    fontSize = fontSize,
    syncServer = syncServer,
    syncUser = syncUser,
    syncPasswordIv = syncPasswordIv,
    syncPasswordCt = syncPasswordCt
)