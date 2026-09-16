package com.devomind.gallerysearch

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.devomind.gallerysearch.databinding.ItemSafeViewerPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pages of the Safe's full-screen viewer: one decrypted photo per page, swiped via ViewPager2.
 * Bitmaps are decrypted lazily per page through [decrypt] (off the main thread) and memoized in
 * [cache]; a recycled holder drops its bitmap reference — the cache owns the memory. The
 * [ImageView.getTag]-style entry-name guard keeps a recycled holder from showing a stale page's
 * bitmap. Tapping a photo invokes [onTap] so the activity can toggle its chrome.
 */
class SafeViewerPagerAdapter(
    private val items: List<SafeManager.VaultItem>,
    private val cache: LruCache<String, Bitmap>,
    private val scope: CoroutineScope,
    private val decrypt: (SafeManager.VaultItem) -> Bitmap?,
    private val onTap: () -> Unit
) : RecyclerView.Adapter<SafeViewerPagerAdapter.PageVH>() {

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH =
        PageVH(ItemSafeViewerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        val item = items[position]
        holder.binding.pagePhoto.tag = item.entryName
        holder.binding.pagePhoto.setImageDrawable(null)
        holder.binding.pagePhoto.setOnClickListener { onTap() }
        val cached = cache.get(item.entryName)
        if (cached != null) {
            holder.binding.pageSpinner.visibility = View.GONE
            holder.binding.pagePhoto.setImageBitmap(cached)
            return
        }
        holder.binding.pageSpinner.visibility = View.VISIBLE
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decrypt(item) }
            if (bmp != null) cache.put(item.entryName, bmp)
            if (holder.binding.pagePhoto.tag == item.entryName) {
                holder.binding.pageSpinner.visibility = View.GONE
                if (bmp != null) holder.binding.pagePhoto.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(holder: PageVH) {
        holder.binding.pagePhoto.setImageDrawable(null)
    }

    class PageVH(val binding: ItemSafeViewerPageBinding) : RecyclerView.ViewHolder(binding.root)
}
