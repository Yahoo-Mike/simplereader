package com.simplereader.highlight

import com.simplereader.databinding.ItemSidepanelBinding
import com.simplereader.ui.sidepanel.SidepanelAdapter

class HighlightAdapter {
    companion object {
        fun create(
            onHighlightSelected: (HighlightListItem) -> Unit,
            onDeleteConfirmed: (HighlightListItem) -> Unit,
            onLongPress: (HighlightListItem) -> Unit,
            extraItemProcessing: (ItemSidepanelBinding, HighlightListItem, Int) -> Unit
        ): SidepanelAdapter<HighlightListItem> {
            return SidepanelAdapter(
                onSidepanelItemSelected = onHighlightSelected,
                onDeleteConfirmed = onDeleteConfirmed,
                onItemLongPressed = onLongPress,
                extraItemProcessing = extraItemProcessing
            )
        }
    }
}
