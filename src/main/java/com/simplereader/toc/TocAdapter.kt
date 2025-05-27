package com.simplereader.toc

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.databinding.TocItemEntryBinding
import org.readium.r2.shared.publication.Link

class TocAdapter(
    private val tocItems: List<Link>,
    private val onItemClick: (Link) -> Unit
) : RecyclerView.Adapter<TocAdapter.TocViewHolder>() {

    inner class TocViewHolder(private val binding: TocItemEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(link: Link) {
            binding.titleText.text = link.title ?: link.href.toString()
            binding.root.setOnClickListener { onItemClick(link) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TocViewHolder {
        val binding = TocItemEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TocViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TocViewHolder, position: Int) {
        holder.bind(tocItems[position])
    }

    override fun getItemCount(): Int = tocItems.size
}