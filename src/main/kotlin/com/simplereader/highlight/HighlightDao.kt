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

    @Query("SELECT MAX(id) FROM highlight WHERE bookId = :bookId")
    suspend fun getMaxIdForBook(bookId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: HighlightEntity)

    @Delete
    suspend fun deleteHighlight(highlight: HighlightEntity)
}
