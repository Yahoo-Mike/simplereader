package com.simplereader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LiveData
import com.simplereader.reader.ReaderActivity
import com.simplereader.settings.SettingsBottomSheet
import com.simplereader.sync.SyncManager
import com.simplereader.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

//
// SimpleReader: singleton class to display an EPUB or PDF using the Readium libraries
//
class SimpleReader private constructor(ctx:Context){

    // user can observe this flag to determine if SimpleReader is currently syncing with a server
    //    SimpleReader.getInstance().isSyncing.observer(lifecycleOwner) { syncing ->
    //       // do some UI updates, like an animated sync icon
    //    }
    val isSyncing : LiveData<Boolean> get() = SyncStatus.isSyncing

    //
    // open a book, given the filepath
    fun openBook(filePath: String): SimpleReader? {
        val ctx = requireNotNull(appContext) { "no app context" }

        val intentReaderActivity = getIntentFromUrl(filePath)
        ctx.startActivity(intentReaderActivity)
        return singleton
    }

    // displays a bottom sheet for entering the server settings
    //
    // user should call like this in a Fragment:
    //      SimpleReader.getInstance().serverSettings(parentFragmentManager)
    //  or like this in an AppCompatActivity:
    //      SimpleReader.serverSettings(requireActivity().supportFragmentManager)
    fun serverSettings(fragManager : FragmentManager) {
        val sheet = SettingsBottomSheet()
        sheet.show(fragManager, sheet.tag)
    }

    //
    ///////////////////////////////////////////////////////////////////////////
    //
    private val appContext: Context = ctx.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // before starting syncmanager, start the SyncStatus singleton, so we can expose it immediately
        // note: SyncStatus needs to run on the main thread
        CoroutineScope(Dispatchers.Main).launch {
            SyncStatus.start(appContext)
        }
        ioScope.launch {
            // start the syncmanager working in the background (it will automatically kick off a syncNow)
            SyncManager.getInstance(appContext).start()
        }
    }

    private fun getIntentFromUrl(filePath: String): Intent {
        val intent = Intent(appContext, ReaderActivity::class.java)

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
