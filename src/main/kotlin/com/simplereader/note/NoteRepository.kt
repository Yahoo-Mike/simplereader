package com.simplereader.note

import com.simplereader.AppContext
import com.simplereader.sync.SyncManager

class NoteRepository(private val noteDao: NoteDao) {

    suspend fun getNotesForBook(bookId: String): List<Note> {
        return noteDao.getNotesForBook(bookId).map {  it.toNote() }
    }

    suspend fun getNextNoteId(bookId: String): Int = noteDao.nextId(bookId)

    // prepares the raw Note to be inserted in the db
    suspend fun insertNote(note: Note) {

        // if there is no id, insert the next available highlight id for this book
        val nextId = if (note.id == 0) {
                        getNextNoteId(note.bookId)
                     } else { note.id }

        val newEntity = Note(
            bookId = note.bookId,
            id = nextId,
            locator = note.locator,
            content = note.content
        ).toEntity()
        noteDao.insert(newEntity)
    }

    suspend fun deleteNote(note : Note) {
        noteDao.delete(note.toEntity())
        val ctx = AppContext.get() ?: return
        SyncManager.getInstance(ctx).flagNoteDeleted(note)
    }
}
