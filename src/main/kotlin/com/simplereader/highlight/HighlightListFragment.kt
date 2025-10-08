package com.simplereader.highlight

import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.simplereader.R
import com.simplereader.ui.sidepanel.SidepanelAdapter
import com.simplereader.ui.sidepanel.SidepanelListFragment
import kotlin.getValue

class HighlightListFragment : SidepanelListFragment<HighlightListItem>() {

    private val highlightViewModel: HighlightViewModel by activityViewModels()

    override fun newInstance() : Fragment = HighlightListFragment()

    // prepare highlights for recyclerView and refresh it when highlights change
    override fun processOnViewCreated() {
        setPanelTitle("Highlights")

        // we don't add highlights in the panel interface
        hideAddButton()

        // initial load of existing bookmarks from the db
        val bookId = readerViewModel.bookData.value?.bookId()
        bookId?.let { highlightViewModel.loadHighlightsFromDb(it) }

        // when highlights change, refresh the recyclerview
        highlightViewModel.highlights.observe(viewLifecycleOwner) {highlightList ->
            val highlightItems = highlightList?.map { HighlightListItem(it) } ?: emptyList()
            adapter.submitList(highlightItems)
        }

    }
    // make a recyclerview adapter for Highlights
    override fun createAdapter() : SidepanelAdapter<HighlightListItem> {
        return HighlightAdapter.create(
            onHighlightSelected = { item -> readerViewModel.gotoLocation(item.highlight.selection) },
            onDeleteConfirmed = { item -> highlightViewModel.deleteHighlight(item.highlight) },
            onLongPress = { _ -> }, // do nothing - don't allow user to change the label text
            extraItemProcessing  = { binding, item, position ->
                // tint background of item in colour of the highlight
                val color = when (item.highlight.color) {
                    "yellow" -> ContextCompat.getColor(requireContext(), R.color.highlight_yellow)
                    "blue" -> ContextCompat.getColor(requireContext(), R.color.highlight_blue)
                    "green" -> ContextCompat.getColor(requireContext(), R.color.highlight_green)
                    "pink" -> ContextCompat.getColor(requireContext(), R.color.highlight_pink)
                    else -> Color.TRANSPARENT
                }
                if (color != Color.TRANSPARENT)
                    binding.sidepanelLabel.setBackgroundColor(color)
            }
        )
    }

    // what to do when user clicks on "add" button
    override fun onAddClicked() {
        //do nothing - button should not be displayed
        // we don't add highlights from the sidepanel, we only goto & delete
    }

}
