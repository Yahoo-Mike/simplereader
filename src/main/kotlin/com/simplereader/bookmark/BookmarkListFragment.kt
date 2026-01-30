package com.simplereader.bookmark

import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import com.simplereader.R
import com.simplereader.model.BookData
import com.simplereader.ui.sidepanel.LongPressConfig
import com.simplereader.ui.sidepanel.SidepanelAdapter
import com.simplereader.ui.sidepanel.SidepanelListFragment
import com.simplereader.ui.sidepanel.SidepanelListItem

class BookmarkListFragment : SidepanelListFragment<SidepanelListItem>() {

    private var latestBookmarks: List<Bookmark> = emptyList()
    private var latestBookData: BookData? = null

    override fun newInstance() : Fragment = BookmarkListFragment()

    // prepare bookmarks for recyclerView and refresh it when bookmarks change
    override fun processOnViewCreated() {
        setPanelTitle("Bookmarks")

        // initial load of existing bookmarks from the db
        val bookId = readerViewModel.bookData.value?.bookId()
        bookId?.let { readerViewModel.loadBookmarks(it) }

        // when bookmarks change, refresh the recyclerview
        readerViewModel.bookmarks.observe(viewLifecycleOwner) { bookmarkList ->
            latestBookmarks = bookmarkList ?: emptyList()
            refreshList()
        }

        // when bookData changes, refresh the recyclerview (in case "current bookmark" changed)
        readerViewModel.bookData.observe(viewLifecycleOwner) { data ->
            latestBookData = data
            refreshList()
        }
    }
    // make a recyclerview adapter for Bookmarks
    override fun createAdapter() : SidepanelAdapter<SidepanelListItem> {
        return BookmarkAdapter.create(
            onBookmarkSelected = { item ->
                when (item) {
                    is CurrentBookmarkListItem -> readerViewModel.gotoLocation(item.locator)
                    is BookmarkListItem -> readerViewModel.gotoLocation(item.bookmark.locator)
                }
            },
            onDeleteConfirmed = { item ->
                if (item is BookmarkListItem) readerViewModel.deleteBookmark(item.bookmark)
            },
            onLongPress = { item ->
                if (item is BookmarkListItem) onLongPressed(item)
            },
            extraItemProcessing = { binding, item, _ ->
                // put the current bookmark icon on "Current Bookmark" item in the list,
                // otherwise no icon (null)
                val icon = if (item is CurrentBookmarkListItem)
                    AppCompatResources.getDrawable(requireContext(), R.drawable.ic_current_bookmark)
                else
                    null

                val textview = binding.sidepanelLabel
                textview.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)

            }
        )
    }

    // what to do when user clicks on "add" button
    override fun onAddClicked() {
        readerViewModel.bookData.value?.currentLocation?.let { locator ->
            readerViewModel.addBookmark(locator)
        }
    }

    // bookmark label dialog configuration
    override fun longPressCfg(item: SidepanelListItem)  : LongPressConfig<SidepanelListItem> = LongPressConfig(
        title = { "Rename bookmark" },
        initialText = { it.getLabel() },
        persistUpdate = { ctx, i, newTxt -> i.persistNewText(ctx,newTxt) },
        applyLocal = { i, newTxt -> i.updateItemText(newTxt) as BookmarkListItem }
    )

    private fun refreshList() {
        val items = mutableListOf<SidepanelListItem>()

        val data = latestBookData
        val currentBookmark = data?.currentBookmark

        // if we have a currentBookmark, then put it at the top of the list
        if (data != null && currentBookmark != null) {
            items += CurrentBookmarkListItem(data.bookId(), currentBookmark)
        }

        // add any other bookmarks
        items += latestBookmarks.map { BookmarkListItem(it) }

        adapter.submitList(items)
    }

}
