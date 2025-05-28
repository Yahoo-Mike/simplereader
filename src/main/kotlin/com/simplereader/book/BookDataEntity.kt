package com.simplereader.book

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "book_data")
data class BookDataEntity (
    @PrimaryKey val bookId: String, // hash
    val pubFile: String,            // physical location of this book
    val mediaType: String,          // EDF/PDF serialized MediaType
    val currentProgress: String?    // serialized Locator of current location
)