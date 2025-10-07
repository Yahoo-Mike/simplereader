package com.simplereader.search

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplereader.R
import com.simplereader.databinding.FragmentSearchBinding
import com.simplereader.reader.ReaderActivity
import com.simplereader.reader.ReaderViewModel
import kotlin.getValue

class SearchFragment : Fragment(R.layout.fragment_search) {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val searchViewModel: SearchViewModel by activityViewModels()
    private val readerViewModel: ReaderViewModel by activityViewModels()

    private lateinit var adapter: SearchResultsAdapter


    @SuppressLint("NotifyDataSetChanged")
    override fun onStart() {
        super.onStart()

        // reset search query to nothing
        binding.searchInput.text.clear()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSearchBinding.bind(view)

        // search adapter
        adapter = SearchResultsAdapter { searchResult ->
            // when user selects a search result we jump to that location
            readerViewModel.gotoLocation(searchResult.locator)
            (requireActivity() as? ReaderActivity)?.closeSearchUI()
        }
        binding.searchResultsRecycler.adapter = adapter

        binding.searchResultsRecycler.layoutManager = LinearLayoutManager(requireContext())

        // callback for when user clicks on back arrow to cancel the search interface
        binding.buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
            (requireActivity() as? ReaderActivity)?.closeSearchUI()
        }

        // callback for when user presses the search icon
        binding.buttonSearch.setOnClickListener {
            performSearch()
        }

        // callback for when user presses Search/Enter on keyboard
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            }
            false
        }

        binding.searchInput.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)

        // Show no results initially, hide recycler view
        binding.noResultsText.visibility = View.VISIBLE
        binding.searchResultsRecycler.visibility = View.GONE

        // observe search results from viewModel
        searchViewModel.searchResults.observe(viewLifecycleOwner) { results ->
            adapter.submitList(results)
            val hasResults = !results.isNullOrEmpty()

            binding.searchResultsRecycler.visibility = if (hasResults) View.VISIBLE else View.GONE
            binding.noResultsText.visibility = if (hasResults) View.GONE else View.VISIBLE
        }

        // watch for respond to click on "search icon" or press of enter on keyboard
        binding.buttonSearch.setOnClickListener {
            performSearch()
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                performSearch()
                true
            }
            false
        }

        // display "No results" and hide recyclerview if there are no search results
        searchViewModel.searchResults.observe(viewLifecycleOwner) { results ->
            if (results.isNullOrEmpty()) {
                binding.noResultsText.visibility = View.VISIBLE
                binding.searchResultsRecycler.visibility = View.GONE
            } else {
                binding.noResultsText.visibility = View.GONE
                binding.searchResultsRecycler.visibility = View.VISIBLE
                adapter.submitList(results)
            }
        }
    }

    private fun performSearch() {
        val query = binding.searchInput.text.toString().trim()
        if (query.isNotEmpty()) {
            searchViewModel.performSearch(query,readerViewModel.bookData.value?.publication)
        } else {
            searchViewModel.clearSearchResults()
        }

        // hide the keyboard, if you need to
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
