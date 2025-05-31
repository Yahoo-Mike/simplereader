package com.simplereader.ui.sidepanel

abstract class SidepanelListItem() {
    abstract fun getLabel(): String
    abstract fun areItemsTheSame(other: SidepanelListItem) : Boolean
    abstract fun areContentsTheSame(other: SidepanelListItem) : Boolean
}