package com.simplereader.bookmark

class BookmarkRepository(private val bookmarkDao: BookmarkDao) {

    suspend fun getBookmarksForBook(bookId: String): List<Bookmark> {
        return bookmarkDao.getBookmarksForBook(bookId).map {  it.toBookmark() }
    }

    suspend fun insertBookmark(bookmark: Bookmark) {
        bookmarkDao.insertBookmark(bookmark.toEntity())
    }

    suspend fun getNextBookmarkId(bookId: String): Int {
        val maxId = bookmarkDao.getMaxIdForBook(bookId) ?: 0
        return maxId + 1
    }

    suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarkDao.deleteBookmark(bookmark.toEntity())
    }
}
