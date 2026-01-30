package com.simplereader.bookmark

import com.simplereader.databinding.ItemSidepanelBinding
import com.simplereader.ui.sidepanel.SidepanelAdapter
import com.simplereader.ui.sidepanel.SidepanelListItem

class BookmarkAdapter {
    companion object {
        fun create(
            onBookmarkSelected: (SidepanelListItem) -> Unit,
            onDeleteConfirmed: (SidepanelListItem) -> Unit,
            onLongPress: (SidepanelListItem) -> Unit,
            extraItemProcessing: (ItemSidepanelBinding, SidepanelListItem, Int) -> Unit

        ): SidepanelAdapter<SidepanelListItem> {
            return SidepanelAdapter(
                onSidepanelItemSelected = onBookmarkSelected,
                onDeleteConfirmed = onDeleteConfirmed,
                onItemLongPressed = onLongPress,
                extraItemProcessing = extraItemProcessing
            )
        }
    }
}
