package com.simplereader.bookmark

import androidx.room.Entity

@Entity(
    tableName = "bookmark",
    primaryKeys = ["bookId","id"])
data class BookmarkEntity(
    val bookId: String,     // hash-id of the book
    val id: Int,            // unique id for this bookmark in this book
    val label: String,      // user-friendly name of this bookmark
    val locator: String     // serialised Locator
)