package com.simplereader.ui.sidepanel

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.R
import com.simplereader.databinding.FragmentSidepanelListBinding
import com.simplereader.reader.ReaderViewModel

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
    abstract fun prepareAndObserveData()                // load recyclerview and observe for updates


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

        // initial prep for data underlying recyclerview and refreshing the recycler view
        prepareAndObserveData()

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

}