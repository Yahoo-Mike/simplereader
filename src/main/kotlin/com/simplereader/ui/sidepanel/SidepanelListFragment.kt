package com.simplereader.ui.sidepanel

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.AppContext
import com.simplereader.R
import com.simplereader.databinding.FragmentSidepanelListBinding
import com.simplereader.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class SidepanelListFragment<T: SidepanelListItem> : Fragment() {

    private var _binding: FragmentSidepanelListBinding? = null
    private val binding get() = _binding!!

    protected lateinit var adapter: SidepanelAdapter<T>
    protected val readerViewModel: ReaderViewModel by activityViewModels()

    companion object {
        fun getPanelTag(): String = "SidePanel"         //get the fragment tag for back stack
    }

    abstract fun newInstance(): Fragment                // instantiate the fragment for the panel
    abstract fun createAdapter() : SidepanelAdapter<T>  // make adapter for the recycleview
    abstract fun onAddClicked()                         // what to do when user presses "add" button
    abstract fun processOnViewCreated()                 // load recyclerview and observe for updates etc

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSidepanelListBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = createAdapter()
        binding.sidepanelList.adapter = adapter
        binding.sidepanelList.layoutManager = LinearLayoutManager(requireContext())

        // initial prep for data underlying recyclerview and refreshing the recycler view etc
        processOnViewCreated()

        // watch for user pressing the "add" button
        binding.sidepanelAddButton.setOnClickListener { onAddClicked() }

        // setup ability to swipe a bookmark to delete it
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                adapter.markPendingDelete(position)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.sidepanelList)

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // avoid memory leak
    }

    // Common logic for showing the panel
    fun showPanel(fragmentManager: FragmentManager) {
        fragmentManager.beginTransaction()
            .replace(R.id.side_panel_container, newInstance(), getPanelTag())
            .addToBackStack(getPanelTag())
            .commit()
    }

    fun hideAddButton () {
        binding.sidepanelAddButton.visibility = View.GONE
    }

    fun setPanelTitle(title: String) {
        binding.sidepanelTitle.text = title
    }

    // onLongPressed: base class handles the recycler view and UI
    //                if user changes the label, we call labelUpdated() for children to persist it
    fun onLongPressed(item: SidepanelListItem) {
        // allow user to enter a new label
        val ctx = requireContext()

        val input = EditText(ctx).apply {
            setText(item.getLabel())
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(ctx).apply {
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(ctx)
            .setTitle("Rename bookmark")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = input.text?.toString()?.trim().orEmpty()
                if (newLabel.isNotEmpty() && newLabel != item.getLabel()) {

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val appctx = AppContext.get() ?: ctx
                        item.persistLabel(appctx, newLabel)

                        // update the recyclerview
                        withContext(Dispatchers.Main) {
                            @Suppress("UNCHECKED_CAST")
                            val newList : List<T> = adapter.currentList.map { cur ->
                                if (cur.areItemsTheSame(item))
                                    cur.updateLabel(newLabel) as T
                                else
                                    cur
                            }
                            adapter.submitList(newList)
                        }
                    }

                }
            }
            .setNegativeButton("Cancel", null)
            .show()

    }
}