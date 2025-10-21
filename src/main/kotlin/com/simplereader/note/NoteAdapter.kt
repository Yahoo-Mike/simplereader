package com.simplereader.note

import com.simplereader.databinding.ItemSidepanelBinding
import com.simplereader.ui.sidepanel.SidepanelAdapter

class NoteAdapter {
    companion object {
        fun create(
            onNoteSelected: (NoteListItem) -> Unit,
            onDeleteConfirmed: (NoteListItem) -> Unit,
            onLongPress: (NoteListItem) -> Unit,
            extraItemProcessing: (ItemSidepanelBinding, NoteListItem, Int) -> Unit
        ): SidepanelAdapter<NoteListItem> {
            return SidepanelAdapter(
                onSidepanelItemSelected = onNoteSelected,
                onDeleteConfirmed = onDeleteConfirmed,
                onItemLongPressed = onLongPress,
                extraItemProcessing = extraItemProcessing
            )
        }
    }
}
