package com.simplereader.book

import com.google.gson.Gson
import com.simplereader.model.BookData
import com.simplereader.reader.ReaderViewModel
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import java.time.Instant

// BookData -> BookDataEntity
fun BookData.toBookEntity(): BookDataEntity {
    val gson = Gson()

    return BookDataEntity(
        bookId = hashId(),
        pubFile = getFileName(),
        mediaType = getMediaType().let { gson.toJson(it) },
        currentProgress = currentLocation?.toJSON()?.toString(),
        currentBookmark = currentBookmark?.toJSON()?.toString(),
        sha256 = getSHA256(),
        filesize = getFileSize(),
        lastUpdated = Instant.now().toEpochMilli()
    )
}

// get BookDataEntity from db
suspend fun BookData.loadBook(viewModel: ReaderViewModel): BookDataEntity? {
    return viewModel.loadBook(hashId())
}

// get current reading progress from db (without updating this BookData)
suspend fun BookData.getProgress(viewModel: ReaderViewModel) : Locator? {
    val book = this.loadBook(viewModel)
    return book?.currentProgress?.let { Locator.fromJSON(JSONObject(it)) }
}

// load progress from db into this BookData
suspend fun BookData.loadProgressFromDb(viewModel: ReaderViewModel)  {
    this.currentLocation = this.getProgress(viewModel)
}

// get current bookmark from db (without updating this BookData)
suspend fun BookData.getCurrentBookmark(viewModel: ReaderViewModel): Locator? {
    val book = this.loadBook(viewModel)
    return book?.currentBookmark?.let { Locator.fromJSON(JSONObject(it)) }
}

// load current bookmark from db into this BookData
suspend fun BookData.loadCurrentBookmarkFromDb(viewModel: ReaderViewModel) {
    this.currentBookmark = this.getCurrentBookmark(viewModel)
}
