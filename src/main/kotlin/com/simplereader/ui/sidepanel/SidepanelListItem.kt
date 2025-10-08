package com.simplereader.ui.sidepanel

import android.content.Context

abstract class SidepanelListItem() {
    abstract fun getLabel(): String     // get the label to display in the list
    abstract suspend fun updateLabel(newLabel: String): SidepanelListItem   // change label to display in the list
    abstract suspend fun persistLabel(ctx: Context, newLabel : String)      // user changed the label, so persist it
    abstract fun areItemsTheSame(other: SidepanelListItem) : Boolean
    abstract fun areContentsTheSame(other: SidepanelListItem) : Boolean
}