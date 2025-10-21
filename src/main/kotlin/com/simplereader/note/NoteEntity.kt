package com.simplereader.note

import androidx.room.Entity

@Entity(
    tableName = "note",
    primaryKeys = ["bookId","id"]
)
data class NoteEntity(
    val bookId: String,      // hash-id of the book
    val id: Int,             // unique id for this note in this book
    val locator: String,     // EPUB: serialised pointer to highlighted part of book
                             // PDF: location where note was added
    val content: String,     // user's note

    val lastUpdated: Long    // timestamp of last update
)
