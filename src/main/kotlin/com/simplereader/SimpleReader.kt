package com.simplereader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.simplereader.reader.ReaderActivity
import com.simplereader.sync.SyncManager

/**
 * kotlin version created by avez raj  on 13 Sep 2017
 * converted to kotlin by yahoo mike on  4 May 2025
 */

//
// SimpleReader: singleton class
//
class SimpleReader private constructor(ctx:Context){
    private val appContext:Context = ctx
    //    private val appContext: Context = ctx.applicationContext

    init {
        // start the syncmanager working in the background (it will automatically kick off a syncNow)
        SyncManager.getInstance(appContext).start()
//        // optional first kick:
//        SyncManager.getInstance(appContext).enqueueSync()
    }

//    // default constructor is private to prevent external instantiation
//    private constructor()
//
//    // valid constructor is private to protect singleton status
//    private constructor(context: Context) {
//        this.context = context
//    }

    fun openBook(filePath: String): SimpleReader? {
        val intentReaderActivity = getIntentFromUrl(filePath)
        appContext!!.startActivity(intentReaderActivity)
        return singleton
    }

      private fun getIntentFromUrl(filePath: String): Intent {
        val intent = Intent(appContext, ReaderActivity::class.java)

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(ReaderActivity.INTENT_FILENAME, filePath)

        return intent
    }

    companion object {  // the singleton...
        @SuppressLint("StaticFieldLeak")
        private var singleton: SimpleReader? = null

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
