package com.devomind.gallerysearch

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ItemImageBinding

class ImageAdapter(
    private val items: MutableList<Uri> = mutableListOf()
) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newList: List<Uri>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
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
                .centerCrop()
                .placeholder(ColorDrawable(Color.DKGRAY))
                .into(binding.thumbnail)
        }
    }
}
