package com.simplereader.book

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.simplereader.bookmark.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insert(bookData: BookDataEntity)

    @Query("SELECT * FROM book_data WHERE bookId = :id")
    suspend fun getBookById(id: String): BookDataEntity?

    @Query("DELETE FROM book_data")
    suspend fun clearAll()

    @Query("UPDATE book_data SET currentProgress = :progress WHERE bookId = :bookId")
    suspend fun updateProgress(bookId: String, progress: String)

}