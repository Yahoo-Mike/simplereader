package com.simplereader.bookmark

import androidx.fragment.app.Fragment
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
            onBookmarkSelected = { item -> readerViewModel.gotoBookmark(item.bookmark) },
            onDeleteConfirmed = { item -> readerViewModel.deleteBookmark(item.bookmark) }
        )
    }

    // what to do when user clicks on "add" button
    override fun onAddClicked() {
        readerViewModel.bookData.value?.currentLocation?.let { locator ->
            readerViewModel.addBookmark(locator)
        }
    }

}
