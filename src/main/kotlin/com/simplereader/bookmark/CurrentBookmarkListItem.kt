package com.simplereader.bookmark

import android.content.Context
import com.simplereader.AppContext
import com.simplereader.R
import com.simplereader.ui.sidepanel.SidepanelListItem
import org.readium.r2.shared.publication.Locator

//
// this is the special case of the "current bookmark" in the bookmark sidepanel
// we can only goto it from here, we cannot rename or delete it.
//
class CurrentBookmarkListItem(
    private val bookId: String,
    val locator: Locator
) : SidepanelListItem() {

    override fun getLabel(): String =  AppContext.get()?.getString(R.string.current_bookmark) ?: ""

    override suspend fun persistNewText(ctx: Context, newText: String) {
        // no-op: nothing to persist
    }

    override fun updateItemText(newText: String): SidepanelListItem = this

    override fun areItemsTheSame(other: SidepanelListItem): Boolean {
        return (other is CurrentBookmarkListItem) && (this.bookId == other.bookId)
    }

    override fun areContentsTheSame(other: SidepanelListItem): Boolean {
        return (other is CurrentBookmarkListItem) &&
                (this.bookId == other.bookId) &&
                (this.locator.toJSON().toString() == other.locator.toJSON().toString())
    }
}
