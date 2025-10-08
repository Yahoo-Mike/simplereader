package com.simplereader.bookmark

import android.content.Context
import com.simplereader.data.ReaderDatabase
import com.simplereader.ui.sidepanel.SidepanelListItem

data class BookmarkListItem (val bookmark: Bookmark): SidepanelListItem() {

    override fun getLabel() : String = bookmark.label

    override suspend fun persistLabel(ctx: Context, newLabel : String) {
        val dao = ReaderDatabase.getInstance(ctx).bookmarkDao()
        val bm = bookmark.copy(label=newLabel).toEntity()
        dao.insertBookmark(bm)
    }

    override suspend fun updateLabel(newLabel: String): SidepanelListItem =
        copy(bookmark = bookmark.copy(label=newLabel))

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