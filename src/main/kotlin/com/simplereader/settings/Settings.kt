package com.simplereader.settings

import com.simplereader.ui.font.ANDADA
import com.simplereader.ui.font.LATO
import com.simplereader.ui.font.LORA
import com.simplereader.ui.font.RALEWAY
import org.readium.r2.navigator.preferences.FontFamily

// encapsulates user preferences for the look of the SimpleReader (font & fontSize)
data class Settings(
    val font: FontFamily,
    val fontSize: Double
) {
    companion object {
        val DEFAULT_FONT = FontFamily.SERIF
        val DEFAULT_FONT_SIZE = 1.0
        val DEFAULT = Settings(DEFAULT_FONT, DEFAULT_FONT_SIZE)

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

}