package com.simplereader.settings

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.readium.r2.navigator.preferences.FontFamily

@Entity(tableName = "settings")
data class SettingsEntity (
    @PrimaryKey val id: Int = 1, // always a singleton row for global settings
                                 // Room requires a primary key
    val font: String,
    val fontSize: Double
)

// extension functions ---
fun Settings.toSettingsEntity(): SettingsEntity = SettingsEntity(
    font = font.name,
    fontSize = fontSize
)

fun SettingsEntity.toReaderSettings(): Settings = Settings(
    font = FontFamily(font),
    fontSize = fontSize
)