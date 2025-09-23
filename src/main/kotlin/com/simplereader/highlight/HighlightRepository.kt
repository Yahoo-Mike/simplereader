package com.simplereader.highlight

import com.simplereader.AppContext
import com.simplereader.sync.SyncManager

class HighlightRepository(private val highlightDao: HighlightDao) {

    suspend fun getHighlightsForBook(bookId: String): List<Highlight> {
        return highlightDao.getHighlightsForBook(bookId).map {  it.toHighlight() }
    }

    suspend fun getNextHighlightId(bookId: String): Int {
        val maxId = highlightDao.getMaxIdForBook(bookId) ?: 0
        return maxId + 1
    }

    // prepares the raw Highlight to be inserted in the db
    suspend fun insertHighlight(highlight: Highlight) {

        // if there is no id, insert the next available highlight id for this book
        val nextId = if (highlight.id == 0) {
                        getNextHighlightId(highlight.bookId)
                     } else { highlight.id }

        // curate the label (what is displayed in the recycler view)
        var displayLabel = highlight.label
        if (displayLabel == null) { // try to use the highlight text
            displayLabel = highlight.selection.text.highlight
        }

        if (displayLabel == null) { // still null, so use default label
            highlight.label = "Highlight $nextId"
        } else {
            val wordCount = displayLabel.trim().split("\\s+".toRegex()).size
            if (wordCount > 5) {
                // limit the "highlight" to five words (so it fits in the recyclerview)
                // if wordcount > 5, then take first 5 words and add "..."
                val firstFourWords = displayLabel
                    .trim()
                    .split("\\s+".toRegex())
                    .take(5)
                    .joinToString(" ")

                displayLabel = "$firstFourWords..."
            }
        }

        val newEntity = Highlight (
                    bookId = highlight.bookId,
                    id = nextId,
                    selection = highlight.selection,
                    label = displayLabel,
                    color = highlight.color.lowercase()
        ).toEntity()
        highlightDao.insertHighlight(newEntity)
    }

    suspend fun deleteHighlight(highlight: Highlight) {
        highlightDao.deleteHighlight(highlight.toEntity())
        val ctx = AppContext.get() ?: return
        SyncManager.getInstance(ctx).flagHighlightDeleted(highlight)

    }
}
