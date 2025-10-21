package com.simplereader.reader

import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commitNow
import com.simplereader.R
import com.simplereader.data.ReaderDatabase

import com.simplereader.model.BookData
import com.simplereader.model.PdfData
import com.simplereader.note.NoteRepository
import com.simplereader.note.NoteViewModel
import com.simplereader.note.NoteViewModelFactory
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFactory
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
import org.readium.adapter.pdfium.navigator.PdfiumPreferences

import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.getValue

class PdfReaderFragment :  ReaderFragment() {

    private lateinit var noteRepository: NoteRepository
    private val noteViewModel: NoteViewModel by activityViewModels() {
        NoteViewModelFactory(noteRepository)
    }

    companion object {
        fun newInstance(): PdfReaderFragment {
            val fragment = PdfReaderFragment()
            return fragment
        }
    }

    @ExperimentalReadiumApi
    override fun onCreate(savedInstanceState: Bundle?) {

        // if bookData has been set, restore the NavigatorFactory
        val pdfInitData = readerViewModel.bookData.value as? PdfData
        if ( pdfInitData != null) {
            val navigatorFactory : PdfiumNavigatorFactory = pdfInitData.navigatorFactory

            // You should restore the initial location from your view model.
            childFragmentManager.fragmentFactory =
                navigatorFactory.createFragmentFactory(
                    initialLocator = pdfInitData.currentLocation
                )
        }

        // create the note repository (before accessing noteViewModel)
        val daoNote =
            ReaderDatabase.Companion.getInstance(requireActivity()).noteDao()
        noteRepository = NoteRepository(daoNote)
        noteViewModel.touch()  // instantiate the noteViewModel

        // IMPORTANT: Set the `fragmentFactory` before calling `super`.
        super.onCreate(savedInstanceState)
    }

    @OptIn(ExperimentalReadiumApi::class)
    override fun publish(data: BookData) {
        val initData = data as? PdfData
            ?: throw IllegalArgumentException("Expected PdfData but got ${data::class.simpleName}")

        // note: initData is never null
        val tag = "PdfiumNavigatorFragment"

        if ( childFragmentManager.findFragmentByTag(tag) == null )  {
            // scroll horizontally
            // NOTE: pagination is not supported by Pdfium, it is just continuous
            val preferences = PdfiumPreferences(scrollAxis=Axis.HORIZONTAL)

            // setup the factory
            val navigatorFactory: PdfiumNavigatorFactory = initData.navigatorFactory

            childFragmentManager.fragmentFactory =
                navigatorFactory.createFragmentFactory(
                    initialLocator = initData.currentLocation,
                    initialPreferences = preferences
                )

            // add this fragment...
            childFragmentManager.commitNow {
                add(R.id.fragment_reader_container, PdfiumNavigatorFragment::class.java, null, tag)
            }
        }

        @Suppress("UNCHECKED_CAST")
        navigator = childFragmentManager.findFragmentByTag(tag) as? PdfiumNavigatorFragment

        // load all the notes for this PDF from db
        noteViewModel.loadNotesFromDb(initData.bookId())

    }

}