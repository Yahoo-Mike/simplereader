package com.simplereader.search

import org.readium.r2.shared.publication.Locator

data class SearchResult(
    val textSnippet: CharSequence,
    val chapterTitle: String?,
    val locator: Locator
)
