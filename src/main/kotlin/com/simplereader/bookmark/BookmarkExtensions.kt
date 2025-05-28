package com.simplereader.bookmark

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

// Bookmark -> BookmarkEntity
fun Bookmark.toEntity(): BookmarkEntity {

    return BookmarkEntity(
        bookId = bookId,
        id = id,
        label = label,
        locator = locator.toJSON().toString()
    )
}

// BookmarkEntity -> Bookmark
fun BookmarkEntity.toBookmark(): Bookmark {
    val parsedLocator = Locator.fromJSON(JSONObject(locator))
        ?: throw IllegalArgumentException("Failed to parse Locator from JSon [$locator]")

    return Bookmark(
        bookId = bookId,
        id = id,
        label = label,
        locator = parsedLocator
    )
}

