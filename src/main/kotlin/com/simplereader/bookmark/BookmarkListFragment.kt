package com.simplereader.bookmark

import androidx.fragment.app.Fragment
import com.simplereader.ui.sidepanel.LongPressConfig
import com.simplereader.ui.sidepanel.SidepanelAdapter
import com.simplereader.ui.sidepanel.SidepanelListFragment

class BookmarkListFragment : SidepanelListFragment<BookmarkListItem>() {

    override fun newInstance() : Fragment = BookmarkListFragment()

        // prepare bookmarks for recyclerView and refresh it when bookmarks change
    override fun processOnViewCreated() {
        setPanelTitle("Bookmarks")

        // initial load of existing bookmarks from the db
        val bookId = readerViewModel.bookData.value?.bookId()
        bookId?.let { readerViewModel.loadBookmarks(it) }

        // when bookmarks change, refresh the recyclerview
        readerViewModel.bookmarks.observe(viewLifecycleOwner) { bookmarkList ->
            val bookmarkItems = bookmarkList?.map { BookmarkListItem(it) } ?: emptyList()
            adapter.submitList(bookmarkItems)
        }
    }
    // make a recyclerview adapter for Bookmarks
    override fun createAdapter() : SidepanelAdapter<BookmarkListItem> {
        return BookmarkAdapter.create(
            onBookmarkSelected = { item -> readerViewModel.gotoLocation(item.bookmark.locator) },
            onDeleteConfirmed = { item -> readerViewModel.deleteBookmark(item.bookmark) },
            onLongPress = { item -> onLongPressed(item) },
            extraItemProcessing = { _, _, _ -> } // do nothing
        )
    }

    // what to do when user clicks on "add" button
    override fun onAddClicked() {
        readerViewModel.bookData.value?.currentLocation?.let { locator ->
            readerViewModel.addBookmark(locator)
        }
    }

    // bookmark label dialog configuration
    override fun longPressCfg(item: BookmarkListItem)  : LongPressConfig<BookmarkListItem> = LongPressConfig(
        title = { "Rename bookmark" },
        initialText = { it.getLabel() },
        persistUpdate = { ctx, i, newTxt -> i.persistNewText(ctx,newTxt) },
        applyLocal = { i, newTxt -> i.updateItemText(newTxt) as BookmarkListItem }
    )

}
