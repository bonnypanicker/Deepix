package com.devomind.gallerysearch

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ItemImageBinding

class ImageAdapter(
    private val items: MutableList<Uri> = mutableListOf()
) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].hashCode().toLong()

    fun updateList(newList: List<Uri>) {
        val diff = DiffUtil.calculateDiff(ImageDiffCallback(items, newList))
        items.clear()
        items.addAll(newList)
        diff.dispatchUpdatesTo(this)
    }

    fun appendList(newList: List<Uri>) {
        val startPos = items.size
        items.addAll(newList)
        notifyItemRangeInserted(startPos, newList.size)
    }

    class ImageViewHolder(private val binding: ItemImageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(uri: Uri) {
            Glide.with(binding.thumbnail.context)
                .load(uri)
                .override(360, 360)
                .centerCrop()
                .placeholder(ColorDrawable(Color.DKGRAY))
                .into(binding.thumbnail)
        }
    }

    private class ImageDiffCallback(
        private val oldItems: List<Uri>,
        private val newItems: List<Uri>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldItems.size

        override fun getNewListSize(): Int = newItems.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldItems[oldItemPosition] == newItems[newItemPosition]
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldItems[oldItemPosition] == newItems[newItemPosition]
        }
    }
}
