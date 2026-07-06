package com.devomind.gallerysearch

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.devomind.gallerysearch.databinding.ItemSafePhotoBinding

/**
 * Grid of decrypted vault thumbnails. Decryption/decoding is delegated to [bindThumb] (run off the
 * main thread by the activity), which sets the bitmap once ready; [ImageView.getTag] guards against
 * a recycled holder receiving a stale bitmap.
 */
class SafeItemAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (SafeManager.VaultItem) -> Unit,
    private val bindThumb: (SafeManager.VaultItem, ImageView) -> Unit
) : RecyclerView.Adapter<SafeItemAdapter.VH>() {

    private val items = mutableListOf<SafeManager.VaultItem>()

    fun submit(list: List<SafeManager.VaultItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): SafeManager.VaultItem = items[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSafePhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.thumbnail.setImageDrawable(null)
        holder.binding.thumbnail.tag = item.entryName
        bindThumb(item, holder.binding.thumbnail)
        holder.binding.root.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemSafePhotoBinding) : RecyclerView.ViewHolder(binding.root)
}
