package com.devomind.gallerysearch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.devomind.gallerysearch.databinding.ItemSafePhotoBinding

/**
 * Grid of decrypted vault thumbnails with multi-select. A long-press enters selection mode, taps
 * then toggle items for batch restore/remove. Decryption/decoding is delegated to [bindThumb]
 * (run off the main thread by the activity), which sets the bitmap once ready; [ImageView.getTag]
 * guards against a recycled holder receiving a stale bitmap.
 *
 * Selection keys are vault entry names, not positions, so it survives list refreshes; entries that
 * disappear from the vault drop out of the selection on the next [submit].
 */
class SafeItemAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (SafeManager.VaultItem) -> Unit,
    private val bindThumb: (SafeManager.VaultItem, ImageView) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<SafeItemAdapter.VH>() {

    private val items = mutableListOf<SafeManager.VaultItem>()
    private val selected = LinkedHashSet<String>()
    private var selectionMode = false

    fun submit(list: List<SafeManager.VaultItem>) {
        items.clear()
        items.addAll(list)
        selected.retainAll(items.mapTo(HashSet()) { it.entryName })
        if (selected.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun itemAt(position: Int): SafeManager.VaultItem = items[position]

    fun snapshot(): List<SafeManager.VaultItem> = items.toList()

    fun isSelectionMode(): Boolean = selectionMode

    fun selectionCount(): Int = selected.size

    fun selectedItems(): List<SafeManager.VaultItem> = items.filter { it.entryName in selected }

    fun toggle(item: SafeManager.VaultItem) {
        if (!selected.remove(item.entryName)) selected.add(item.entryName)
        if (selected.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun enterSelection(item: SafeManager.VaultItem) {
        if (selectionMode) return
        selectionMode = true
        selected.clear()
        selected.add(item.entryName)
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun selectAll() {
        if (items.isEmpty()) return
        selectionMode = true
        items.forEach { selected.add(it.entryName) }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun clearSelection() {
        if (selected.isEmpty() && !selectionMode) return
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSafePhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.thumbnail.setImageDrawable(null)
        holder.binding.thumbnail.tag = item.entryName
        bindThumb(item, holder.binding.thumbnail)
        val isSelected = item.entryName in selected
        holder.binding.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.binding.selectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.binding.root.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemSafePhotoBinding) : RecyclerView.ViewHolder(binding.root)
}
