package com.simplereader.note

import android.content.Context
import com.simplereader.data.ReaderDatabase
import com.simplereader.ui.sidepanel.SidepanelListItem

data class NoteListItem (val note: Note): SidepanelListItem() {

    // take first 5 words in the note to use as the label
    override fun getLabel() : String =
        note.content.trim().takeIf { it.isNotBlank() }?.let { text ->
            Regex("\\S+").findAll(text).take(5).joinToString(" ") { it.value }
        } ?: "Note ${note.id}"

    override suspend fun persistNewText(ctx: Context, newText : String) {
        val dao = ReaderDatabase.getInstance(ctx).noteDao()
        val nn = note.copy(content=newText).toEntity()
        dao.insert(nn)
    }

    override fun updateItemText(newText: String): NoteListItem =
        copy(note = note.copy(content=newText))

    override fun areItemsTheSame(other: SidepanelListItem) : Boolean {
        return  (other is NoteListItem) &&
                (this.note.bookId == other.note.bookId) &&
                (this.note.id == other.note.id)
    }

    override fun areContentsTheSame(other: SidepanelListItem) : Boolean {
        return  (other is NoteListItem) &&
                (this == other)
    }

}