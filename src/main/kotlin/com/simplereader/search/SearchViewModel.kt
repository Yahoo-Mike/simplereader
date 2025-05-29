package com.simplereader.search

import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.text.style.BackgroundColorSpan
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search
import androidx.core.graphics.toColorInt

class SearchViewModel : ViewModel() {

    private val _searchResults = MutableLiveData<List<SearchResult>>()
    val searchResults: LiveData<List<SearchResult>> = _searchResults

    @OptIn(ExperimentalReadiumApi::class)
    fun performSearch(query: String, publication: Publication?) {
        if (publication == null) return    // do nothing

        viewModelScope.launch {
            val searchIterator : SearchIterator? = publication.search(query)
            if (searchIterator == null) {
                _searchResults.value = emptyList()
                return@launch
            }

            val results = mutableListOf<SearchResult>()
            searchIterator.forEach { locatorCollection ->
                locatorCollection.locators.forEach { locator ->
                    val textSnippet = getTextSnippet(locator) ?: ""
                    val chapterTitle = locator.title // May be null

                    results.add(
                        SearchResult(
                            textSnippet = textSnippet,
                            chapterTitle = chapterTitle,
                            locator = locator
                        )
                    )
                }
            }

            _searchResults.value = results
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    // get the context of the search result with the result highlighted
    private fun getTextSnippet(locator: Locator) : SpannableString? {
        val searchTerm = locator.text.highlight ?: return null

        val snippet =   (locator.text.before) +
                        (locator.text.highlight) +
                        (locator.text.after)
        val spannable = SpannableString(snippet)

        val regex = Regex(Regex.escape(searchTerm), RegexOption.IGNORE_CASE)
        regex.findAll(snippet).forEach { matchResult ->
            val start = matchResult.range.first
            val end = matchResult.range.last + 1

            // bold
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),  // Make it bold
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // Lemon-yellow background
            spannable.setSpan(
                BackgroundColorSpan("#FFF59D".toColorInt()), // Lemon-ish
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return  spannable
    }

}