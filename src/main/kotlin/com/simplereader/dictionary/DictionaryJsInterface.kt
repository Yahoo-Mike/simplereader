package com.simplereader.dictionary

import android.webkit.JavascriptInterface

// TODO

class DictionaryJsInterface(private val onWordSelected: (String) -> Unit) {
    @JavascriptInterface
    fun onWordLongPressed(word: String) {
        onWordSelected(word)
    }
}
