package com.simplereader.note

import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.simplereader.ui.sidepanel.LongPressConfig
import com.simplereader.ui.sidepanel.SidepanelAdapter
import com.simplereader.ui.sidepanel.SidepanelListFragment
import org.readium.r2.shared.util.mediatype.MediaType
import kotlin.getValue

class NoteListFragment : SidepanelListFragment<NoteListItem>() {

    private val noteViewModel: NoteViewModel by activityViewModels()

    override fun newInstance() : Fragment = NoteListFragment()

    // prepare notes for recyclerView and refresh it when highlights change
    override fun processOnViewCreated() {
        setPanelTitle("Notes")

        // we use the AddButton for notes in PDFs (because we can't select text),
        // but we don't use the AddButton to add notes for EPUBs)
        if ( readerViewModel.bookData.value?.getMediaType() == MediaType.EPUB )
            hideAddButton()

        // initial load of existing notes from the db
        val bookId = readerViewModel.bookData.value?.bookId()
        bookId?.let { noteViewModel.loadNotesFromDb(it) }

        // when notes change, refresh the recyclerview
        noteViewModel.notes.observe(viewLifecycleOwner) { noteList ->
            val noteItems =  noteList?.map { NoteListItem(it) } ?: emptyList()
            adapter.submitList(noteItems)
        }

    }

    // make a recyclerview adapter for Highlights
    override fun createAdapter() : SidepanelAdapter<NoteListItem> {

        return NoteAdapter.Companion.create(
            onNoteSelected = { item -> item.note.locator?.let(readerViewModel::gotoLocation) },
            onDeleteConfirmed = { item -> noteViewModel.deleteNote(item.note) },
            onLongPress = { item -> onLongPressed(item) }, // let user edit the note
            extraItemProcessing  = { _, _, _ -> }   // nothing else to do
        )
    }

    // what to do when user clicks on "add" button
    // note: this is only used for PDFs (because EPUBS add notes by selecting text)
    override fun onAddClicked() {
        val ctx = requireContext()

        val input = EditText(ctx).apply {
            hint = "Add note here"
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            minLines = 4
            maxLines = 10
            setHorizontallyScrolling(false)
            gravity = Gravity.TOP
            requestFocus()
        }

        val padH = (24 * resources.displayMetrics.density).toInt()
        val padV = (8 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(ctx).apply {
            setPadding(padH, padV, padH, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(ctx)
            .setTitle("New note")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val noteText = input.text?.toString()?.trim().orEmpty()
                if (noteText.isBlank()) return@setPositiveButton

                val data = readerViewModel.bookData.value ?: return@setPositiveButton
                val locator = data.currentLocation ?: return@setPositiveButton
                val bookId = data.bookId()

                val note = Note(
                    bookId = bookId,
                    id = 0,                    // insertNote() will allocate this
                    locator = locator,
                    content = noteText
                )
                noteViewModel.insertNote(note)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // bookmark label dialog configuration
    override fun longPressCfg(item: NoteListItem)  : LongPressConfig<NoteListItem> = LongPressConfig(
        title = { "Edit note" },
        initialText = { it.note.content },
        configureInput = { input, _ ->
            input.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            input.isSingleLine = false
            input.minLines = 4
            input.maxLines = 10
            input.setHorizontallyScrolling(false)
            input.gravity = Gravity.TOP
        },
        persistUpdate = { ctx, i, newTxt -> i.persistNewText(ctx,newTxt) },
        applyLocal = { i, newTxt -> i.updateItemText(newTxt) },
        persistDelete = { _, i -> noteViewModel.deleteNote(i.note) }
    )

}
