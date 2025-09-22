package com.simplereader.bookmark

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmark WHERE bookId = :bookId ORDER BY id ASC")
    suspend fun getBookmarksForBook(bookId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmark WHERE bookId = :bookId AND id = :idx")
    suspend fun getBookmark(bookId: String, idx: Int): BookmarkEntity?

    @Query("SELECT MAX(id) FROM bookmark WHERE bookId = :bookId")
    suspend fun getMaxIdForBook(bookId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmark WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: String)

    @Query("UPDATE bookmark SET lastUpdated = :updatedAt WHERE bookId = :bookId AND id = :id")
    suspend fun updateTimestamp(bookId: String, id: Int, updatedAt: Long)

}
