package com.simplereader.note

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NoteDao {

    @Query("SELECT * FROM note WHERE bookId = :bookId ORDER BY id ASC")
    suspend fun getNotesForBook(bookId: String): List<NoteEntity>

    @Query("SELECT * FROM note WHERE bookId = :bookId AND id = :idx")
    suspend fun getNote(bookId: String, idx: Int): NoteEntity?

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM note WHERE bookId = :bookId")
    suspend fun nextId(bookId: String): Int


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("DELETE FROM note WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: String)

    @Query("UPDATE note SET lastUpdated = :updatedAt WHERE bookId = :bookId AND id = :id")
    suspend fun updateTimestamp(bookId: String, id: Int, updatedAt: Long)

}
