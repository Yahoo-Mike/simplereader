package com.simplereader.highlight

class HighlightRepository(private val highlightDao: HighlightDao) {

    suspend fun getHighlightsForBook(bookId: String): List<Highlight> {
        return highlightDao.getHighlightsForBook(bookId).map {  it.toHighlight() }
    }

    suspend fun getNextHighlightId(bookId: String): Int {
        val maxId = highlightDao.getMaxIdForBook(bookId) ?: 0
        return maxId + 1
    }

    // updates the "id" member with the next available id#
    suspend fun insertHighlight(highlight: Highlight) {
        val nextId = if (highlight.id == 0) {
                        getNextHighlightId(highlight.bookId)
                     } else { highlight.id }
        if (highlight.label == null) {
            highlight.label = "Highlight ${nextId}"
        }
        val newEntity = Highlight (
                    bookId = highlight.bookId,
                    id = nextId,
                    selection = highlight.selection,
                    label = highlight.label,
                    color = highlight.color   ).toEntity()
        highlightDao.insertHighlight(newEntity)
    }

    suspend fun deleteHighlight(highlight: Highlight) {
        highlightDao.deleteHighlight(highlight.toEntity())
    }
}
