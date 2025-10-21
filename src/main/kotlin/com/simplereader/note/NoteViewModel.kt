package com.simplereader.note

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator


class NoteViewModel(
    private val noteRepository: NoteRepository
) : ViewModel() {

    // this does nothing, but can be used to ensure the NoteViewModel is instantiated
    fun touch() {}

    private val _notes = MutableLiveData<List<Note>?>()
    val notes: LiveData<List<Note>?> get() = _notes

    fun loadNotesFromDb(bookId: String) {
        viewModelScope.launch {
            _notes.value = noteRepository.getNotesForBook(bookId)
        }
    }

    // we do not insert from the NoteListFragment, just goto & delete
    fun insertNote(note : Note) {
        viewModelScope.launch {
            noteRepository.insertNote(note)
            // refresh the list after insert
            loadNotesFromDb(note.bookId)
        }
    }

    fun deleteNote(note : Note) {
        viewModelScope.launch {
            noteRepository.deleteNote(note)
            // refresh the list after deletion
            loadNotesFromDb(note.bookId)
        }
    }

    fun saveNote(bookId : String, locator : Locator, content : String) {
        viewModelScope.launch {
            val note = Note( bookId,
                            0,      // insertNote() will allocate next id#
                            locator,
                            content)
            noteRepository.insertNote(note)
            // refresh the list after insert
            loadNotesFromDb(note.bookId)
        }
    }
}