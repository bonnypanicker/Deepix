package com.devomind.gallerysearch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.devomind.gallerysearch.databinding.ItemSearchSectionTabBinding

class SearchSectionAdapter(
    private val onClick: (SearchSection) -> Unit
) : ListAdapter<SearchSectionResult, SearchSectionAdapter.ViewHolder>(Diff) {
    var selected: SearchSection? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemSearchSectionTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), getItem(position).section == selected, onClick)

    class ViewHolder(private val binding: ItemSearchSectionTabBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SearchSectionResult, selected: Boolean, onClick: (SearchSection) -> Unit) {
            binding.sectionLabel.text = item.section.label.uppercase()
            binding.sectionCount.text = item.count.toString()
            val context = binding.root.context
            binding.sectionLabel.setTextColor(ContextCompat.getColor(
                context,
                if (selected) R.color.metroTextPrimary else R.color.metroTextSecondary
            ))
            binding.sectionCount.setTextColor(ContextCompat.getColor(
                context,
                if (selected) R.color.metroAccent else R.color.metroTextTertiary
            ))
            binding.sectionIndicator.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            binding.root.setOnClickListener { onClick(item.section) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<SearchSectionResult>() {
        override fun areItemsTheSame(old: SearchSectionResult, new: SearchSectionResult) = old.section == new.section
        override fun areContentsTheSame(old: SearchSectionResult, new: SearchSectionResult) = old == new
    }
}
