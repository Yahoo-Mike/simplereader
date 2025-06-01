package com.simplereader.highlight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class HighlightViewModelFactory(
    private val highlightRepository: HighlightRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HighlightViewModel::class.java)) {
            return HighlightViewModel(highlightRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
