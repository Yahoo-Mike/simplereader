package com.simplereader.book

import android.content.Context

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
}