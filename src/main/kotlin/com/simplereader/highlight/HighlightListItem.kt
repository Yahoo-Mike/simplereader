package com.simplereader.highlight

import android.content.Context
import com.simplereader.ui.sidepanel.SidepanelListItem

data class HighlightListItem (val highlight: Highlight): SidepanelListItem() {

    override fun getLabel() : String = highlight.label ?: "Highlight ${highlight.id}"

    // we don't allow user to update label text for highlights, so this does nothing
    override suspend fun persistNewText(ctx: Context, newText : String) { }
    override fun updateItemText(newText: String): SidepanelListItem = this  // we don't update labels

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