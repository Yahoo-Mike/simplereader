package com.simplereader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.simplereader.reader.ReaderActivity

/**
 * java version created by avez raj  on 13 Sep 2017
 * converted to kotlin by yahoo mike on  4 May 2025
 */

//
// FolioReader: singleton class
//
class SimpleReader {
    private var context: Context? = null
//    private var config: Config? = null
//    private var overrideConfig = false

    // default constructor is private to prevent external instantiation
    private constructor()

    // valid constructor is private to protect singleton status
    private constructor(context: Context) {
        this.context = context
    }

    fun openBook(filePath: String): SimpleReader? {
        val intentFolioActivity = getIntentFromUrl(filePath)
        context!!.startActivity(intentFolioActivity)
        return singleton
    }

      private fun getIntentFromUrl(filePath: String): Intent {
        val intent = Intent(context, ReaderActivity::class.java)

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(ReaderActivity.INTENT_FILENAME, filePath)

        return intent
    }

    companion object {  // the singleton...
        @SuppressLint("StaticFieldLeak")
        private var singleton: SimpleReader? = null

        const val EXTRA_BOOK_ID: String = "com.simplereader.extra.BOOK_ID"

        // returns single instance of singleton class
        fun getInstance(): SimpleReader {
            if (singleton == null) {
                synchronized(this) {
                    if (singleton == null) {
                        checkNotNull(AppContext.get()) { "-> context == null" }
                        singleton = SimpleReader(AppContext.get()!!)
                    }
                }
            }
            return singleton!!
        }

    }
}
