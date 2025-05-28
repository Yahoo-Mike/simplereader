package com.simplereader.bookmark

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.databinding.FragmentBookmarkListBinding
import com.simplereader.reader.ReaderViewModel
import kotlin.getValue

class BookmarkListFragment : Fragment() {

    private var _binding: FragmentBookmarkListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: BookmarkAdapter
    private val viewModel: ReaderViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBookmarkListBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = BookmarkAdapter(
            onBookmarkSelected = { bookmark -> viewModel.gotoBookmark(bookmark) },
            onDeleteConfirmed = { bookmark -> viewModel.deleteBookmark(bookmark) }
        )
        binding.bookmarkList.adapter = adapter
        binding.bookmarkList.layoutManager = LinearLayoutManager(requireContext())

        // initial load of existing bookmarks from the db
        val bookId = viewModel.bookData.value?.bookId()
        bookId?.let { viewModel.loadBookmarks(it) }

        viewModel.bookmarks.observe(viewLifecycleOwner) { bookmarkList ->
            adapter.submitList(bookmarkList)
        }

        // watch for user pressing the "add bookmark" button
        binding.addBookmarkButton.setOnClickListener {
            viewModel.bookData.value?.currentLocation?.let { locator ->
                viewModel.addBookmark(locator)
            }
        }

        // setup ability to swipe a bookmark to delete it
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                adapter.markPendingDelete(position)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.bookmarkList)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // avoid memory leak
    }

}
