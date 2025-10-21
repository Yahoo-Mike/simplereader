package com.simplereader.note

import com.simplereader.R
import org.readium.r2.shared.publication.Locator

data class Note(
    val bookId: String,      // hash-id of the book
    val id: Int,             // unique id for this note in this book
    val locator: Locator,    // EPUB: location of the note in the book (with selection info)
                             // PDF: location in the PDF where the note was entered
    val content: String,     // user's note
)  {
    fun getHexColor(): Int = R.color.highlight_note
}