package com.simplereader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LiveData
import com.simplereader.reader.ReaderActivity
import com.simplereader.settings.SettingsBottomSheet
import com.simplereader.sync.SyncAPI
import com.simplereader.sync.SyncManager
import com.simplereader.sync.SyncStatus
import com.simplereader.util.MiscUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

//
// SimpleReader: singleton class to display an EPUB or PDF using the Readium libraries
//
class SimpleReader private constructor(ctx:Context){

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

    // user can observe this flag to determine if SimpleReader is currently syncing with a server
    //    SimpleReader.getInstance().isSyncing.observer(lifecycleOwner) { syncing ->
    //       // do some UI updates, like an animated sync icon
    //    }
    val isSyncing : LiveData<Boolean> get() = SyncStatus.isSyncing

    // downloads a list of all the books on the SimpleReader server (being read by users or not)
    // PARAMETERS:
    //   onCompletion:  callback with (booklist : List<CatalogueBook))
    data class CatalogueBook(
        val fileName: String,   // name that the client gave the server originally
        val fileId: String )    // server's internal fileId (can be used to getBook())
    fun getServerCatalogue(onCompletion: (List<CatalogueBook>) -> Unit) {

        ioScope.launch {
            // kick off the GET /catalogue
            val rows: List<JSONObject> =
                try {
                    val rc = SyncAPI.getCatalogue()
                    if (!rc.ok) {   // log error, but continue
                        Log.e(LOG_TAG, "failed to download catalogue")
                    }
                    rc.rows
                } catch (t: Throwable) {
                    Log.e(LOG_TAG, "getServerCatalogue error: ${t.message}")
                    emptyList()
                }

            // call callback on main thread with results (list of books)
            val booklist = mutableListOf<CatalogueBook>()
            for (row in rows) {
                val id = row.optString("fileId","")
                val name = row.optString("fileName","")
                if (id.isNotBlank() && name.isNotBlank())
                    booklist += CatalogueBook( fileName = name, fileId = id )
            }
            booklist.sortBy { it.fileName.lowercase() }

            withContext(Dispatchers.Main) { // run on the main thread
                onCompletion(booklist)
            }
        }

    }

    // downloads a book from the SimpleReader server to the app's external file/ directory
    // note: this runs asynchronously on an IO thread
    // PARAMETERS:
    //      fileId: server's fileId of the book to download
    //   onCompletion:  callback with (success : Boolean)
    fun downloadServerBook(fileId : String, onCompletion: (Boolean) -> Unit) {

        ioScope.launch {
            // download the book into directory
            val success: Boolean =
                try {
                    val rc = SyncAPI.getBook(fileId)
                    if (!rc.ok) {   // log error, but continue
                        Log.e(LOG_TAG, "failed to download book")
                    }
                    rc.ok
                } catch (t: Throwable) {
                    Log.e(LOG_TAG, "getServerBook error: ${t.message}")
                    false
                }

            // call callback on main thread with success flag
            withContext(Dispatchers.Main) { // run on the main thread
                onCompletion(success)
            }
        }

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
        private val LOG_TAG: String = MiscUtil::class.java.simpleName

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
