package com.simplereader.book

import java.time.Instant

//
// stores & retrieves book info from database
//
class BookRepository(private val bookDao: BookDao) {

    suspend fun saveBookData(data: BookDataEntity) {
        bookDao.insert(data)
    }

    suspend fun loadBookData(id: String): BookDataEntity? {
        return bookDao.getBookById(id)
    }

    suspend fun updateProgress(bookId: String, progressJson: String) {
        bookDao.updateProgress(bookId, progressJson)
    }

    suspend fun updateCurrentBookmark(bookId: String, progressJson: String?) {
        bookDao.updateCurrentBookmark(bookId, progressJson)
        updateBookTimestamp(bookId)
    }

    suspend fun updateBookTimestamp(bookId: String) {
        bookDao.updateTimestamp(bookId, Instant.now().toEpochMilli())
    }
}