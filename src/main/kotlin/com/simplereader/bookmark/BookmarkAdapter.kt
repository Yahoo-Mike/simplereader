package com.simplereader.bookmark

import com.simplereader.databinding.ItemSidepanelBinding
import com.simplereader.ui.sidepanel.SidepanelAdapter

class BookmarkAdapter {
    companion object {
        fun create(
            onBookmarkSelected: (BookmarkListItem) -> Unit,
            onDeleteConfirmed: (BookmarkListItem) -> Unit,
            onLongPress: (BookmarkListItem) -> Unit,
            extraItemProcessing: (ItemSidepanelBinding, BookmarkListItem, Int) -> Unit

        ): SidepanelAdapter<BookmarkListItem> {
            return SidepanelAdapter(
                onSidepanelItemSelected = onBookmarkSelected,
                onDeleteConfirmed = onDeleteConfirmed,
                onItemLongPressed = onLongPress,
                extraItemProcessing = extraItemProcessing

            )
        }
    }
}
