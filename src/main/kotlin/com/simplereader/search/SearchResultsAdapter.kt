package com.simplereader.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.databinding.ItemSearchResultBinding

class SearchResultsAdapter(
    private val onResultClick: (SearchResult) -> Unit
) : ListAdapter<SearchResult, SearchResultsAdapter.SearchResultViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SearchResult>() {
            override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
                // Unique locator should identify the same result
                return oldItem.locator == newItem.locator
            }

            override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
                return oldItem == newItem
            }
        }
    }

    inner class SearchResultViewHolder(private val binding: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(result: SearchResult) {
            binding.tvSnippet.text = result.textSnippet

            if (!result.chapterTitle.isNullOrEmpty()) {
                binding.tvChapter.text = result.chapterTitle
                binding.tvChapter.visibility = View.VISIBLE
            } else {
                binding.tvChapter.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onResultClick(result)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemSearchResultBinding.inflate(inflater, parent, false)
        return SearchResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
