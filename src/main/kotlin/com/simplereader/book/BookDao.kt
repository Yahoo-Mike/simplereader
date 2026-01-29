package com.simplereader.book

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insert(bookData: BookDataEntity)

    @Query("SELECT * FROM book_data")
    suspend fun getAllBooks(): List<BookDataEntity>

    @Query("SELECT * FROM book_data WHERE bookId = :id")
    suspend fun getBookById(id: String): BookDataEntity?

    @Query("DELETE FROM book_data")
    suspend fun clearAll()

    @Query("DELETE FROM book_data WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)

    @Query("UPDATE book_data SET currentProgress = :progress WHERE bookId = :bookId")
    suspend fun updateProgress(bookId: String, progress: String?)

    @Query("UPDATE book_data SET currentBookmark = :bookmark WHERE bookId = :bookId")
    suspend fun updateCurrentBookmark(bookId: String, bookmark: String?)

    @Query("UPDATE book_data SET lastUpdated = :updatedAt WHERE bookId = :bookId")
    suspend fun updateTimestamp(bookId: String, updatedAt: Long)
}