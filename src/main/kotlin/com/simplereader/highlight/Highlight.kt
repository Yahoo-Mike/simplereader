package com.simplereader.highlight

import org.readium.r2.shared.publication.Locator

data class Highlight(
    val bookId: String,     // hash-id of the book
    val id : Int,           // unique bookmark id in this book
    val selection: Locator, // location of the highlight in the book
    var label: String?,     // what is displayed to user (text snippet)
    val color: String       // colour of the highlight
)
