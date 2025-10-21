package com.simplereader.ui.sidepanel

import android.content.Context
import android.text.InputType
import android.widget.EditText

// this class is used to configure the text editing dialog that pops up when
// a user long presses on a dialog.
data class LongPressConfig<T : SidepanelListItem>(

    // text to put in the dialog titlebar
    val title: (T) -> String,

    // text to show when in editbox when dialog opens
    val initialText: (T) -> String,

    // editbox input configuration: defaults to one line plain text dialog
    val configureInput: (EditText, T) -> Unit = { input, _ ->
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setSingleLine(true)
    },

    // did the user change the text?
    val validateChange: (String, T) -> Boolean = { newText, item ->
        newText.isNotBlank() && newText != initialText(item)
    },

    // update the db
    val persistUpdate: suspend (Context, T, String) -> Unit,

    // maps the updated text to a new list item used for UI refresh
    val applyLocal: (T, String) -> T,

    // what to do if the user presses "save" and the text is empty
    val persistDelete: (suspend (Context, T) -> Unit)? = null
)