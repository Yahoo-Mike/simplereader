package com.simplereader.ui.sidepanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.databinding.ItemSidepanelBinding

class SidepanelAdapter<T: SidepanelListItem>(
    private val onSidepanelItemSelected: (T) -> Unit,
    private val onDeleteConfirmed: (T) -> Unit,
    private val onItemLongPressed: (T) -> Unit,
    private val extraItemProcessing: (ItemSidepanelBinding, T, Int) -> Unit
) : ListAdapter<T, SidepanelAdapter<T>.ViewHolder>(createDiffCallback()) {

    private val pendingDeletePositions = mutableSetOf<Int>()

    inner class ViewHolder(val binding: ItemSidepanelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item:T, position: Int) {
            if (pendingDeletePositions.contains(position)) {
                binding.sidepanelContent.visibility = View.GONE
                binding.deleteConfirmLayout.visibility = View.VISIBLE

                // ensure the click and longPress listeners don't fire while we're in delete mode
                binding.root.setOnClickListener(null)
                binding.root.setOnLongClickListener(null)

                // what to do if the user cancels the delete...
                binding.deleteCancel.setOnClickListener {
                    pendingDeletePositions.remove(position)
                    notifyItemChanged(position)
                }

                // what to do if the user confirms the delete...
                binding.deleteConfirm.setOnClickListener {
                    pendingDeletePositions.remove(position)
                    onDeleteConfirmed(item)
                }

            } else {
                // we're not in delete mode, so just set up the click and longpress listeners
                binding.sidepanelContent.visibility = View.VISIBLE
                binding.deleteConfirmLayout.visibility = View.GONE
                binding.sidepanelLabel.text = item.getLabel()

                binding.root.setOnClickListener { onSidepanelItemSelected(item) }
                binding.root.setOnLongClickListener {
                    onItemLongPressed(item)
                    true        // signal that we consumed the long press
                }

            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSidepanelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, position)

        extraItemProcessing(holder.binding, item, position)
    }

    fun markPendingDelete(position: Int) {
        pendingDeletePositions.add(position)
        notifyItemChanged(position)
    }

    companion object {
        fun <T : SidepanelListItem> createDiffCallback() = object : DiffUtil.ItemCallback<T>() {
            override fun areItemsTheSame(oldItem: T, newItem: T): Boolean {
                return oldItem.areItemsTheSame(newItem)
            }

            override fun areContentsTheSame(oldItem: T, newItem: T): Boolean {
                return oldItem.areContentsTheSame(newItem)
            }
        }
    }

}
