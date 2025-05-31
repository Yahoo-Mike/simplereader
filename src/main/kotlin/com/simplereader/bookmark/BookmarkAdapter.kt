package com.simplereader.bookmark

import com.simplereader.ui.sidepanel.SidepanelAdapter

class BookmarkAdapter {
    companion object {
        fun create(
            onBookmarkSelected: (BookmarkListItem) -> Unit,
            onDeleteConfirmed: (BookmarkListItem) -> Unit
        ): SidepanelAdapter<BookmarkListItem> {
            return SidepanelAdapter(
                onSidepanelItemSelected = onBookmarkSelected,
                onDeleteConfirmed = onDeleteConfirmed
            )
        }
    }
}
