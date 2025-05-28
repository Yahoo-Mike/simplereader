package com.simplereader.reader

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.simplereader.book.BookRepository
import com.simplereader.bookmark.BookmarkRepository
import com.simplereader.settings.SettingsRepository

class ReaderViewModelFactory(
        private val application: Application,
        private val bookRepository: BookRepository,
        private val bookmarkRepository: BookmarkRepository,
        private val settingsRepository: SettingsRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReaderViewModel(application,bookRepository,bookmarkRepository,settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}