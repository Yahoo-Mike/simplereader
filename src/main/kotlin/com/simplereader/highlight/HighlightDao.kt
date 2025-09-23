package com.simplereader.highlight

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HighlightDao {

    @Query("SELECT * FROM highlight WHERE bookId = :bookId ORDER BY id ASC")
    suspend fun getHighlightsForBook(bookId: String): List<HighlightEntity>

    @Query("SELECT * FROM highlight WHERE bookId = :bookId AND id = :idx")
    suspend fun getHighlight(bookId: String, idx: Int): HighlightEntity?

    @Query("SELECT MAX(id) FROM highlight WHERE bookId = :bookId")
    suspend fun getMaxIdForBook(bookId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: HighlightEntity)

    @Delete
    suspend fun deleteHighlight(highlight: HighlightEntity)

    @Query("DELETE FROM highlight WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: String)

    @Query("UPDATE highlight SET lastUpdated = :updatedAt WHERE bookId = :bookId AND id = :id")
    suspend fun updateTimestamp(bookId: String, id: Int, updatedAt: Long)

}
