package com.simplereader.highlight

import com.simplereader.ui.sidepanel.SidepanelListItem

data class HighlightListItem (val highlight: Highlight): SidepanelListItem() {

    override fun getLabel() : String = highlight.label

    override fun areItemsTheSame(other: SidepanelListItem) : Boolean {
        return  (other is HighlightListItem) &&
                (this.highlight.bookId == other.highlight.bookId) &&
                (this.highlight.id == other.highlight.id)
    }
    override fun areContentsTheSame(other: SidepanelListItem) : Boolean {
        return  (other is HighlightListItem) &&
                (this == other)
    }

}