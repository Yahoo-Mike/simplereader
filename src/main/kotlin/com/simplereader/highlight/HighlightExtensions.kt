package com.simplereader.highlight

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import java.time.Instant

// Highlight -> HighlightEntity
fun Highlight.toEntity(): HighlightEntity {

    return HighlightEntity(
        bookId = bookId,
        id = id,
        label = label ?: "\"Highlight ${this.id}\"",
        selection = selection.toJSON().toString(),
        color = color,
        lastUpdated = Instant.now().toEpochMilli()
    )
}

// HighlightEntity -> Highlight
fun HighlightEntity.toHighlight(): Highlight {
    val parsedLocator = Locator.fromJSON(JSONObject(selection))
        ?: throw IllegalArgumentException("Failed to parse Locator from JSon [$selection]")
    return Highlight(
        bookId = bookId,
        id = id,
        label = label,
        selection = parsedLocator,
        color = color
    )
}
