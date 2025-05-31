package com.simplereader.highlight

import androidx.fragment.app.Fragment
import com.simplereader.ui.sidepanel.SidepanelAdapter
import com.simplereader.ui.sidepanel.SidepanelListFragment

class HighlightListFragment : SidepanelListFragment<HighlightListItem>() {

    override fun newInstance() : Fragment = HighlightListFragment()

    // prepare highlights for recyclerView and refresh it when highlights change
    override fun prepareAndObserveData() {

        // we don't add highlights in the panel interface
        hideAddButton()

        // TODO load highlights from db and handle changes to recyclerview
    }

    // make a recyclerview adapter for Highlights
    override fun createAdapter() : SidepanelAdapter<HighlightListItem> {
        return HighlightAdapter.create(
            onHighlightSelected = { item -> readerViewModel.gotoHighlight(item.highlight) },
            onDeleteConfirmed = { item -> readerViewModel.deleteHighlight(item.highlight) }
        )
    }

    // what to do when user clicks on "add" button
    override fun onAddClicked() {
        //do nothing - button should not be displayed
    }

}
