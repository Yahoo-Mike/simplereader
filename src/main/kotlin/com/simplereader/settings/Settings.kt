package com.simplereader.settings

import androidx.room.ColumnInfo
import com.simplereader.ui.font.ANDADA
import com.simplereader.ui.font.LATO
import com.simplereader.ui.font.LORA
import com.simplereader.ui.font.RALEWAY
import org.readium.r2.navigator.preferences.FontFamily

// encapsulates user preferences for the look of the SimpleReader (font & fontSize)
data class Settings(
    val font: FontFamily,
    val fontSize: Double,

    val syncServer: String?,        // address of server running sync daemon
    val syncUser: String?,          // username for login to server
    val syncPasswordIv: ByteArray?, // GCM IV
    val syncPasswordCt: ByteArray?  // ciphertext
) {
    companion object {
        val DEFAULT_FONT = FontFamily.SERIF
        val DEFAULT_FONT_SIZE = 1.0
        val DEFAULT = Settings(DEFAULT_FONT, DEFAULT_FONT_SIZE,null,null,null,null)

        // supported fonts in readium's FontFamily, see: navigator.preferences.Types.kt
        val supportedFonts = listOf(
            FontFamily.SERIF,
            FontFamily.SANS_SERIF,
            FontFamily.MONOSPACE,
            FontFamily.ACCESSIBLE_DFA,
            FontFamily.IA_WRITER_DUOSPACE,
            FontFamily.OPEN_DYSLEXIC,
            FontFamily.ANDADA,      // sans-serif
            FontFamily.LATO,        // serif
            FontFamily.LORA,        // serif
            FontFamily.RALEWAY      // sans-serif
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Settings) return false

        return font == other.font &&
                fontSize == other.fontSize &&
                syncServer == other.syncServer &&
                syncUser == other.syncUser &&
                (syncPasswordIv?.contentEquals(other.syncPasswordIv) ?: (other.syncPasswordIv == null)) &&
                (syncPasswordCt?.contentEquals(other.syncPasswordCt) ?: (other.syncPasswordCt == null))
    }

    override fun hashCode(): Int {
        var result = font.hashCode()
        result = 31 * result + fontSize.hashCode()
        result = 31 * result + (syncServer?.hashCode() ?: 0)
        result = 31 * result + (syncUser?.hashCode() ?: 0)
        result = 31 * result + (syncPasswordIv?.contentHashCode() ?: 0)
        result = 31 * result + (syncPasswordCt?.contentHashCode() ?: 0)
        return result
    }
}