package com.simplereader.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.simplereader.databinding.ActivityReaderBinding
import com.simplereader.R
import com.simplereader.util.Readium
import com.simplereader.book.BookRepository
import com.simplereader.data.ReaderDatabase
import com.simplereader.error.GenericError
import com.simplereader.error.OpeningError
import com.simplereader.error.PublicationError
import com.simplereader.model.BookData
import com.simplereader.model.EpubData
import com.simplereader.model.PdfData
import com.simplereader.reader.ReaderFragment.OnSingleTapListener
import com.simplereader.settings.SettingsBottomSheet
import kotlinx.coroutines.launch
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.allAreHtml
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.protectionError
import org.readium.r2.shared.util.CoroutineQueue
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.toUrl
import java.io.File
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.simplereader.book.loadProgressFromDb
import com.simplereader.bookmark.BookmarkListFragment
import com.simplereader.bookmark.BookmarkRepository
import com.simplereader.highlight.HighlightListFragment
import com.simplereader.search.SearchFragment
import com.simplereader.search.SearchViewModel
import com.simplereader.settings.SettingsRepository
import com.simplereader.ui.sidepanel.SidepanelListFragment
import kotlin.math.roundToInt

@OptIn(InternalReadiumApi::class)
@Suppress("DEPRECATION")
class ReaderActivity : AppCompatActivity(), OnSingleTapListener {

    // expose the binding (used by hosted fragments)
    lateinit var binding: ActivityReaderBinding
        private set

    // Readium helper class
    lateinit var readium: Readium
        private set // make the setter private

    private lateinit var readerViewModel: ReaderViewModel
    private val searchViewModel: SearchViewModel by viewModels()

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    private var savedInstanceState: Bundle? = null

    private var mFilename: String? = null

    // multitasking
    private val coroutineQueue: CoroutineQueue = CoroutineQueue()

    companion object {

        @JvmField
        val LOG_TAG: String = ReaderActivity::class.java.simpleName

        const val INTENT_FILENAME = "com.simplereader.asset_path"

        const val FIVE_SECONDS: Long = 5000
        const val TEN_SECONDS: Long = 10000

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readium = Readium(applicationContext)

        val bookDao = ReaderDatabase.Companion.getInstance(applicationContext).bookDao()
        val bookRepository = BookRepository(bookDao)

        val bookmarkDao = ReaderDatabase.Companion.getInstance(applicationContext).bookmarkDao()
        val bookmarkRepository = BookmarkRepository(bookmarkDao)

        val settingsDao =
            ReaderDatabase.Companion.getInstance(applicationContext).readerSettingsDao()
        val settingsRepository = SettingsRepository(settingsDao)

        readerViewModel = ViewModelProvider(
            this,
            ReaderViewModelFactory(
                bookRepository,
                bookmarkRepository,
                settingsRepository
            )
        )[ReaderViewModel::class.java]

        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fix for screen get turned off while reading
        //window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mFilename = intent.extras!!.getString(INTENT_FILENAME)

        // setup the action bar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)  // We'll handle the title ourselves

        //Set up the back button & toc hamburger on the appbar
        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed() // exit SimpleReader
        }

        binding.tocHamburger.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // autohide the toolbar after a while
        autoHideAppBarAfterDelay(FIVE_SECONDS)

        // when user presses system Back button, dismiss Bookmark panel if it's open
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dismissSidepanel()) {
                    // Let system handle back press normally (e.g. finish activity)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        this.savedInstanceState = savedInstanceState
        // note: fragment is not started until we know which type to start...

        // check permissions
        // only need to ask for write access for sdk 28 & 29
        // don't need to ask for internet accesss, because the Manifest statement is sufficient
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (SDK 30+) - skip requesting WRITE_EXTERNAL_STORAGE
            openBook()
        } else {
            // Register the permission launcher
            requestPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    openBook()
                } else { // User denied permission
                    Toast.makeText(this, "cannot access storage", Toast.LENGTH_LONG).show()
                    finish()
                    return@registerForActivityResult
                }
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                // permission already granted, so call openBook()
                openBook()
            } else {  // request permission, if granted the callback will call openBook()
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_icons, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        return when (item.itemId) {
            R.id.itemSearch -> {
                openSearchUI()
                true
            }

            R.id.itemSettings -> {
                val sheet = SettingsBottomSheet()
                sheet.show(supportFragmentManager, sheet.tag)
                true
            }

            R.id.itemBookmarks -> {
                showSidepanel(BookmarkListFragment())
                true
            }

            R.id.itemHighlight -> {
                showSidepanel(HighlightListFragment())
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openBook() {
        Log.v(LOG_TAG, "-> openBook")
        lifecycleScope.launch {
            openSingleBook()
                .onFailure {
                    Log.e(LOG_TAG, "-> Failed to initialize book: $mFilename")
                    onBookInitFailure()
                }
                .onSuccess {
                    onBookInitSuccess(it)
                }
        }
    }

    private suspend fun openSingleBook(): Try<BookData, OpeningError> =
        coroutineQueue.await { initBook() }

    private suspend fun initBook(): Try<BookData, OpeningError> {
        Log.v(LOG_TAG, "-> initBook")

        val bookUrl = File(mFilename!!).toUrl()
        val bookType: MediaType? = BookData.getMediaType(mFilename!!)
        if (bookType == null) {
            return Try.Companion.failure(
                OpeningError.PublicationError(
                    PublicationError.UnsupportedScheme(GenericError(mFilename!!))
                )
            )
        }

        // retrieve asset to access the file content
        val asset = readium.assetRetriever.retrieve(bookUrl, bookType).getOrElse {
            return Try.Companion.failure(
                OpeningError.PublicationError(
                    PublicationError.Companion(it)
                )
            )
        }

        //
        // open publication from the asset
        //
        val publication = readium.publicationOpener.open(
            asset,
            allowUserInteraction = false    // don't ask user for credentials
        ).getOrElse {
            return Try.Companion.failure(
                OpeningError.PublicationError(
                    PublicationError.Companion(it)
                )
            )
        }

        if (publication.isRestricted) {
            return Try.Companion.failure(
                OpeningError.RestrictedPublication(
                    publication.protectionError
                        ?: DebugError("Publication is restricted.")
                )
            )
        }

        if (publication.conformsTo(Publication.Profile.EPUB)) {
            showSettingsToolbarIcon(true)
            showHighlightsToolbarIcon(true)

            if (savedInstanceState == null) {
                // put an EpubReaderFragment in the navigator_container
                supportFragmentManager.beginTransaction()
                    .replace(binding.navigatorContainer.id, EpubReaderFragment.newInstance())
                    .commit()
            }
        } else {
            if (publication.conformsTo(Publication.Profile.PDF)) {
                showSettingsToolbarIcon(false)
                showHighlightsToolbarIcon(false)

                if (savedInstanceState == null) {
                    // put an PdfReaderFragment in the navigator_container
                    supportFragmentManager.beginTransaction()
                        .replace(binding.navigatorContainer.id, PdfReaderFragment.newInstance())
                        .commit()
                }
            }
        }

        val initData = when {
            publication.conformsTo(Publication.Profile.EPUB) || publication.readingOrder.allAreHtml -> {
                openEpub(publication)
            }

            publication.conformsTo(Publication.Profile.PDF) -> {
                openPdf(publication)
            }

            else ->
                Try.Companion.failure(
                    OpeningError.CannotRender(
                        DebugError("Book type not supported.  Use EPUB or PDFs only.")
                    )
                )
        }

        return initData
    }

    private var showSettings = true
    private fun showSettingsToolbarIcon(show: Boolean) {
        showSettings = show
        invalidateOptionsMenu()
    }

    private var showHighlights = true
    private fun showHighlightsToolbarIcon(show: Boolean) {
        showHighlights = show
        invalidateOptionsMenu()
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.itemSettings)?.isVisible = showSettings
        menu?.findItem(R.id.itemHighlight)?.isVisible = showHighlights
        return super.onPrepareOptionsMenu(menu)
    }

    private fun onBookInitFailure() {
        //TODO -> Fail gracefully
        Snackbar.make(currentFocus!!, "onBookInitFailure()", Snackbar.LENGTH_LONG).show()
        finish()
    }

    private fun onBookInitSuccess(initData: BookData) {

        // pass the book info to the viewModel
        readerViewModel.setBookData(initData)

    }

    private suspend fun openEpub(publication: Publication): Try<EpubData, OpeningError> {

        // create navFactory for this publication
        // note: you can set user preferences here too: see https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/navigator.md
        val navigatorFactory = EpubNavigatorFactory(publication)

        val filename = mFilename ?: "unknown"

        var initData = EpubData(
            publication = publication,
            pubName = filename,
            currentLocation = null,     // a default location
            navigatorFactory
        )

        // if the book is already in the db, load the progress from the db record
        initData.loadProgressFromDb(readerViewModel)

        return Try.Companion.success(initData)
    }

    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun openPdf(publication: Publication): Try<PdfData, OpeningError> {
        val pdfEngine = PdfiumEngineProvider()
        val navigatorFactory = PdfNavigatorFactory(publication, pdfEngine)
        val filename = mFilename ?: "unknown"

        val initData = PdfData(
            publication,
            pubName = filename,
            currentLocation = null,     // a default location
            navigatorFactory
        )

        // if the book is already in the db, load the progress from the db record
        initData.loadProgressFromDb(readerViewModel)

        return Try.Companion.success(initData)
    }

    // listener for a tap in the ReaderFragment
    // when user taps, toggle the appBar
    override fun onSingleTap() {

        val handled = dismissSidepanel()
        if (!handled)
            toggleAppBar()
    }


    private fun toggleAppBar() {
        if (binding.appBarLayout.isVisible)
            hideAppBar()
        else
            showAppBar()
    }

    // if positive non-null "duration" is passed, then AppBar shown for "duration" milliseconds
    // if duration==0, then appBar remains shown (until screen is tapped again)
    private fun showAppBar(showMsecs: Long = 0) {
        binding.appBarLayout.apply {
            if (visibility != View.VISIBLE) {
                alpha = 0f
                visibility = View.VISIBLE
                animate().alpha(1f).setDuration(300).start()
            }
        }

        // show system nav and status bars
        val windowInsetsController = ViewCompat.getWindowInsetsController(window.decorView)
        windowInsetsController?.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())

        if (showMsecs > 0) {
            autoHideAppBarAfterDelay(showMsecs)
        }
    }

    private fun hideAppBar() {
        binding.appBarLayout.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.appBarLayout.visibility = View.GONE
            }
            .start()

        // Hide the system bars (status bar and navigation bar)
        val windowInsetsController = ViewCompat.getWindowInsetsController(window.decorView)
        windowInsetsController?.let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // hide the appBar after "delayMsecs" milliseconds
    private fun autoHideAppBarAfterDelay(delayMsecs: Long = FIVE_SECONDS) {
        Handler(Looper.getMainLooper()).postDelayed({
            hideAppBar()
        }, delayMsecs)
    }

    fun showSidepanel(panelFragment: SidepanelListFragment<*>) {
        val panel = findViewById<FrameLayout>(R.id.side_panel_container)
        if (panel.visibility != View.VISIBLE) {

            panel.visibility = View.VISIBLE
            panel.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_right))

            panelFragment.showPanel(supportFragmentManager)
        }
    }

    fun dismissSidepanel(): Boolean {
        var handled = false

        val panel = binding.sidePanelContainer

        if (panel.isVisible) {
            val panelTag = SidepanelListFragment.getPanelTag()
            val sidePanel =
                supportFragmentManager.findFragmentByTag(panelTag) as? SidepanelListFragment<*>

            if (sidePanel != null) {
                // dismiss the bookmark/highlight list...
                // Start slide-out animation
                val slideOut = AnimationUtils.loadAnimation(this, R.anim.slide_out_right)
                slideOut.setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(animation: Animation) {}

                    override fun onAnimationEnd(animation: Animation) {
                        // After animation ends, remove the fragment and hide the panel
                        supportFragmentManager.popBackStack(
                            panelTag,
                            FragmentManager.POP_BACK_STACK_INCLUSIVE
                        )
                        panel.visibility = View.GONE
                    }

                    override fun onAnimationRepeat(animation: Animation) {}
                })
                panel.startAnimation(slideOut)
            }

            // restore appbar for 5secs
            showAppBar(FIVE_SECONDS)
            handled = true
        }

        return handled
    }

    // kick off the search UI (SearchFragment)
    private fun openSearchUI() {

        //before opening it, make sure the search results recylerview is empty
        searchViewModel.clearSearchResults()

        // only load the SearchUI if it's not already visible  (don't load multiple SearchFragments)
        if (!binding.searchContainer.isVisible) {
            // make the search container visible
            binding.searchContainer.visibility = View.VISIBLE

            supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    android.R.anim.fade_in,
                    android.R.anim.fade_out,
                    android.R.anim.fade_in,
                    android.R.anim.fade_out
                )
                .add(R.id.search_container, SearchFragment())
                .addToBackStack(null)
                .commit()
        }

    }

    fun closeSearchUI() {
        binding.searchContainer.isVisible = false
    }

    // the text for "n% read" indication on the appbar
    fun updateProgressIndicator(progress: Double) {
        val percent = (progress * 100.0).roundToInt()
        val text = when (percent) {
            in 1..99 -> "$percent% read"
            100 -> "Finished"
            else -> ""
        }
        binding.progressIndicator.text = text
    }

}