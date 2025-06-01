package com.simplereader.highlight

import androidx.room.Entity

@Entity(
    tableName = "highlight",
    primaryKeys = ["bookId","id"])
data class HighlightEntity(
    val bookId: String,     // hash-id of the book
    val id: Int,            // unique id for this highlight in this book
    val selection: String,  // serialised pointer to highlighted part of book
    val label: String,      // text to be displayed to user
    val color: String       // colour of the highlight
)
