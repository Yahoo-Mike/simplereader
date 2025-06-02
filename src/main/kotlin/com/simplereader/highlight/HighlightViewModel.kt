package com.simplereader.highlight

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch


class HighlightViewModel(
    private val highlightRepository: HighlightRepository
) : ViewModel() {

    // this does nothing, but can be used to ensure the HighlightViewModel is instantiated
    fun touch() {}

    private val _highlights = MutableLiveData<List<Highlight>?>()
    val highlights: LiveData<List<Highlight>?> get() = _highlights

    fun loadHighlightsFromDb(bookId: String) {
        viewModelScope.launch {
            _highlights.value = highlightRepository.getHighlightsForBook(bookId)
        }
    }

    // we do not insert from the HighlightListFragment, just goto & delete
    fun insertHighlight(highlight: Highlight) {
        viewModelScope.launch {
            highlightRepository.insertHighlight(highlight)
            // refresh the list after insert
            highlight.bookId.let { loadHighlightsFromDb(it) }
        }
    }

    fun deleteHighlight(highlight: Highlight) {
        viewModelScope.launch {
            highlightRepository.deleteHighlight(highlight)
            // refresh the list after deletion
            highlight.bookId.let { loadHighlightsFromDb(it) }
        }
    }

}