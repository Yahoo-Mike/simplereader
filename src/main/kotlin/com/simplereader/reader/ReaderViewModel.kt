/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 *
 * modified by yahoo mike 18 May 2025
 *
 */

@file:OptIn(ExperimentalReadiumApi::class)

package com.simplereader.reader

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplereader.book.BookDataEntity
import com.simplereader.book.BookRepository
import com.simplereader.book.toBookEntity
import com.simplereader.bookmark.Bookmark
import com.simplereader.bookmark.BookmarkRepository
import com.simplereader.model.BookData
import com.simplereader.settings.Settings
import com.simplereader.settings.SettingsRepository
import com.simplereader.util.ReaderEvent
import kotlinx.coroutines.launch
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.mediatype.MediaType
import kotlin.math.roundToInt

@OptIn(ExperimentalReadiumApi::class)
class ReaderViewModel(
    private val bookRepository: BookRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _bookData = MutableLiveData<BookData?>()
    val bookData: LiveData<BookData?> = _bookData

    private val _bookmarks = MutableLiveData<List<Bookmark>?>()
    val bookmarks: LiveData<List<Bookmark>?> = _bookmarks

    // LiveData event to notify navigation commands (like gotoBookmark)
    private val _gotoLocator = MutableLiveData<ReaderEvent<Locator>>()
    val gotoLocator: LiveData<ReaderEvent<Locator>> = _gotoLocator

    private val _readerSettings = MutableLiveData<Settings?>()
    val readerSettings: LiveData<Settings?> = _readerSettings

    init {
        viewModelScope.launch {
            val settings = settingsRepository.getSettings()
            _readerSettings.postValue(settings)
        }
    }

    companion object {
            private val LOG_TAG: String = ReaderViewModel::class.java.simpleName
    }

    fun setBookData(newData: BookData) {
        _bookData.value = newData

       viewModelScope.launch {
           val entity = newData.toBookEntity()
           bookRepository.saveBookData(entity)
       }
    }

    suspend fun loadBook(bookId: String) : BookDataEntity? {
        return bookRepository.loadBookData(bookId)
    }

    fun saveReadingProgression(locator: Locator) {

        // update local copy of progress location
        bookData.value?.currentLocation = locator

        // persist the progress
        viewModelScope.launch {
            val json = locator.toJSON().toString()
            bookRepository.updateProgress(bookData.value!!.hashId(), json)
        }
    }

    fun setFont(font: FontFamily) {
        viewModelScope.launch {
            val current = _readerSettings.value ?: return@launch
            val updated = current.copy(font = font)
            settingsRepository.saveSettings(updated)
            _readerSettings.postValue(updated)
        }
    }

    fun setFontSize(size: Double) {
        viewModelScope.launch {
            val current = _readerSettings.value ?: return@launch
            val updated = current.copy(fontSize = size)
            settingsRepository.saveSettings(updated)
            _readerSettings.postValue(updated)
        }
    }

    fun addBookmark(locator: Locator) {
        val thisBookId = bookData.value?.bookId() ?: return

        viewModelScope.launch {
            val nextId = bookmarkRepository.getNextBookmarkId(thisBookId)
            val label = "Bookmark $nextId"

            val newBookmark = Bookmark (
                bookId = thisBookId,
                id = nextId,
                label = label,
                locator = locator
            )

            bookmarkRepository.insertBookmark( newBookmark )
            loadBookmarks(thisBookId)  // refresh the bookmark list
        }
    }

    fun loadBookmarks(bookId: String) {
        viewModelScope.launch {
            _bookmarks.value = bookmarkRepository.getBookmarksForBook(bookId)
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            bookmarkRepository.deleteBookmark(bookmark)
            bookmark.bookId.let { loadBookmarks(it) }  // refresh the recyclerView
        }
    }

    fun gotoLocation(location: Locator) {
        _gotoLocator.value = ReaderEvent(location)

    }

    // called when user inputs a position to go to (in ReaderActivity)
    // for PDFs (fixed-page layout) this is a page number
    // for EPUBS (reflowable or fixed-page) this could be a percentage
    fun gotoPosition(pos: Int) {
        viewModelScope.launch {
            val book = bookData.value ?: return@launch

            when (book.getMediaType()) {
                MediaType.Companion.EPUB -> gotoPercent(pos)
                MediaType.Companion.PDF -> gotoPage(pos)
                else -> return@launch
            }
        }
    }

    private suspend fun gotoPercent(percent: Int) {
        val book = bookData.value ?: return
        val positions = book.publication.positions()      // one locator per “location”
        if (positions.isEmpty()) return

        val p = percent.coerceIn(0, 100)
        val idx = ((p / 100.0) * (positions.size - 1)).roundToInt()
        gotoLocation(positions[idx])                      // emits your _gotoLocator
    }

    private suspend fun gotoPage(pageIn: Int) {
        if (pageIn < 1) return    // guardrail
        val book = bookData.value ?: return
        val link = book.publication.readingOrder.firstOrNull() ?: return
        val mime = link.mediaType ?: book.getMediaType() ?: return

        val totalPages = book.publication.positions().size
        val page = pageIn.coerceIn(1, totalPages)

        val locator = Locator(
            href = link.url(),
            mediaType = mime,
            locations = Locator.Locations(
                fragments = listOf("page=$page"),
                position = page
            )
        )
        gotoLocation(locator) // your existing method
    }
}
