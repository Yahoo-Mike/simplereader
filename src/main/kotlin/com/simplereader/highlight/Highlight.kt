package com.simplereader.highlight

import android.content.Context
import androidx.core.content.ContextCompat
import com.simplereader.R
import org.readium.r2.shared.publication.Locator

data class Highlight(
    val bookId: String,     // hash-id of the book
    val id : Int,           // unique bookmark id in this book
    val selection: Locator, // location of the highlight in the book
    var label: String?,     // what is displayed to user (text snippet)
    val color: String       // colour of the highlight
)  {
    fun getHexColor(context: Context): Int {
        return when (color) {
            "yellow" -> ContextCompat.getColor(context, R.color.highlight_yellow)
            "blue" -> ContextCompat.getColor(context, R.color.highlight_blue)
            "green" -> ContextCompat.getColor(context, R.color.highlight_green)
            "pink" -> ContextCompat.getColor(context, R.color.highlight_pink)
            else -> ContextCompat.getColor(context, R.color.white)
        }
    }
}
