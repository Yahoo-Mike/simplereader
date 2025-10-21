package com.simplereader.note

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import java.time.Instant

// Note -> NoteEntity
fun Note.toEntity(): NoteEntity {

    return NoteEntity(
        bookId = bookId,
        id = id,
        locator = locator.toJSON().toString(),
        content = content,
        lastUpdated = Instant.now().toEpochMilli()
    )
}

// NoteEntity -> Note
fun NoteEntity.toNote(): Note {
    val parsedLocator = Locator.fromJSON(JSONObject(locator))
        ?: throw IllegalArgumentException("Failed to parse Locator from JSon [$locator]")

    return Note(
        bookId = bookId,
        id = id,
        locator = parsedLocator,
        content = content
    )
}
