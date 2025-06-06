package com.simplereader.bookmark

import org.readium.r2.shared.publication.Locator

data class Bookmark(
    val bookId: String,     // hash-id of the book
    val id : Int,           // unique bookmark id in this book
    val label: String,      // user-friendly name of this bookmark
    val locator: Locator    // Locator in book
)