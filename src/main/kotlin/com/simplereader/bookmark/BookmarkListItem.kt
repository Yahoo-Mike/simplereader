package com.simplereader.bookmark

import com.simplereader.ui.sidepanel.SidepanelListItem

data class BookmarkListItem (val bookmark: Bookmark): SidepanelListItem() {

    override fun getLabel() : String = bookmark.label

    override fun areItemsTheSame(other: SidepanelListItem) : Boolean {
        return  (other is BookmarkListItem) &&
                (this.bookmark.bookId == other.bookmark.bookId) &&
                (this.bookmark.id == other.bookmark.id)
    }
    override fun areContentsTheSame(other: SidepanelListItem) : Boolean {
        return  (other is BookmarkListItem) &&
                (this == other)
    }

}