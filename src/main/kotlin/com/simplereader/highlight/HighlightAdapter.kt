package com.simplereader.highlight

import com.simplereader.ui.sidepanel.SidepanelAdapter

class HighlightAdapter {
    companion object {
        fun create(
            onHighlightSelected: (HighlightListItem) -> Unit,
            onDeleteConfirmed: (HighlightListItem) -> Unit
        ): SidepanelAdapter<HighlightListItem> {
            return SidepanelAdapter(
                onSidepanelItemSelected = onHighlightSelected,
                onDeleteConfirmed = onDeleteConfirmed
            )
        }
    }
}
