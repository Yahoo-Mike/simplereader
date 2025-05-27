package com.simplereader.settings

//
// stores & retrieves global reader settings from database
//
class SettingsRepository(private val dao: SettingsDao) {
    suspend fun getSettings(): Settings? {
        val settings = dao.getSettings()
        return settings?.toReaderSettings() ?: Settings.DEFAULT
    }

    suspend fun saveSettings(settings: Settings) {
        dao.saveSettings(settings.toSettingsEntity())
    }
}
