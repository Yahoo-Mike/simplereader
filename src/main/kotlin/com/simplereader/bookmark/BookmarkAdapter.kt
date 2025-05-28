package com.simplereader.bookmark

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.databinding.ItemBookmarkBinding

class BookmarkAdapter(
    private val onBookmarkSelected: (Bookmark) -> Unit,
    private val onDeleteConfirmed: (Bookmark) -> Unit
) : ListAdapter<Bookmark, BookmarkAdapter.ViewHolder>(DiffCallback) {

    private val pendingDeletePositions = mutableSetOf<Int>()

    inner class ViewHolder(val binding: ItemBookmarkBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(bookmark: Bookmark, position: Int) {
            if (pendingDeletePositions.contains(position)) {
                binding.normalContent.visibility = View.GONE
                binding.deleteConfirmLayout.visibility = View.VISIBLE

                binding.deleteCancel.setOnClickListener {
                    pendingDeletePositions.remove(position)
                    notifyItemChanged(position)
                }

                binding.deleteConfirm.setOnClickListener {
                    pendingDeletePositions.remove(position)
                    onDeleteConfirmed(bookmark)
                }

            } else {
                binding.normalContent.visibility = View.VISIBLE
                binding.deleteConfirmLayout.visibility = View.GONE
                binding.bookmarkLabel.text = bookmark.label

                binding.root.setOnClickListener {
                    onBookmarkSelected(bookmark)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val bookmark = getItem(position)
        holder.bind(bookmark, position)
    }

    fun markPendingDelete(position: Int) {
        pendingDeletePositions.add(position)
        notifyItemChanged(position)
    }

    companion object {
        object DiffCallback : DiffUtil.ItemCallback<Bookmark>() {
            override fun areItemsTheSame(oldItem: Bookmark, newItem: Bookmark): Boolean {
                // If the ID is the same, it's the same item
                return oldItem.bookId == newItem.bookId && oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: Bookmark, newItem: Bookmark): Boolean {
                // If the contents are equal, don't rebind
                return oldItem == newItem
            }
        }
    }
}
