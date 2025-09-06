package com.simplereader.settings

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.readium.r2.navigator.preferences.FontFamily
import java.time.Instant

@Entity(tableName = "settings")
data class SettingsEntity (
    @PrimaryKey val id: Int = 1, // always a singleton row for global settings
                                 // Room requires a primary key
    val font: String,
    val fontSize: Double,
    val lastUpdated: Long        // timestamp of last update
)

// extension functions ---
fun Settings.toSettingsEntity(): SettingsEntity = SettingsEntity(
    font = font.name,
    fontSize = fontSize,
    lastUpdated = Instant.now().toEpochMilli()  //assuming is toSettingsEntity() is only called on dao.saveSettings()
)

fun SettingsEntity.toReaderSettings(): Settings = Settings(
    font = FontFamily(font),
    fontSize = fontSize
)