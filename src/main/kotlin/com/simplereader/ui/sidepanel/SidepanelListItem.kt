package com.simplereader.ui.sidepanel

import android.content.Context

abstract class SidepanelListItem() {
    abstract fun getLabel(): String     // get the label to display in the list
    abstract fun updateItemText(newText: String): SidepanelListItem     // change local copy of the item with new text
    abstract suspend fun persistNewText(ctx: Context, newText : String) // user changed the text, so persist it
    abstract fun areItemsTheSame(other: SidepanelListItem) : Boolean
    abstract fun areContentsTheSame(other: SidepanelListItem) : Boolean
}