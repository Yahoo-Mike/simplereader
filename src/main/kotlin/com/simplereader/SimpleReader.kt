package com.simplereader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentManager
import com.simplereader.reader.ReaderActivity
import com.simplereader.settings.SettingsBottomSheet
import com.simplereader.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * kotlin version created by avez raj  on 13 Sep 2017
 * converted to kotlin by yahoo mike on  4 May 2025
 */

//
// SimpleReader: singleton class
//
class SimpleReader private constructor(ctx:Context){
    private val appContext: Context = ctx.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ioScope.launch {
            // start the syncmanager working in the background (it will automatically kick off a syncNow)
            SyncManager.getInstance(appContext).start()
        }
    }

    // open a book, given the filepath
    fun openBook(filePath: String): SimpleReader? {
        val intentReaderActivity = getIntentFromUrl(filePath)
        appContext!!.startActivity(intentReaderActivity)
        return singleton
    }

    // displays a bottom sheet for entering the server settings
    //  context:  UI context over which to display the settings
    //
    // user should call like this in a Fragment:
    //      SimpleReader.getInstance().serverSettings(parentFragmentManager)
    //  or like this in an AppCompatActivity:
    //      SimpleReader.serverSettings(requireActivity().supportFragmentManager)
    fun serverSettings(fragManager : FragmentManager) {
        val sheet = SettingsBottomSheet()
        sheet.show(fragManager, sheet.tag)
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
