package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ActivityBinBinding
import com.devomind.gallerysearch.databinding.ItemSafePhotoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Recycle Bin screen: a grid of soft-deleted photos. Tapping an item offers Restore or
 * Delete forever; "Empty bin" clears everything. Retention (30 days) is enforced by
 * [BinManager.purgeExpired] on app start, so this screen only shows still-recoverable items.
 */
class BinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBinBinding
    private lateinit var adapter: BinAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityBinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        adapter = BinAdapter { entry -> showOptions(entry) }
        binding.binGrid.layoutManager = GridLayoutManager(this, 3)
        binding.binGrid.adapter = adapter
        binding.binGrid.setHasFixedSize(true)

        binding.backBtn.setOnClickListener { finish() }
        binding.emptyBinBtn.setOnClickListener { confirmEmpty() }

        refresh()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.binRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            binding.binGrid.updatePadding(bottom = bars.bottom + dp(12))
            insets
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { BinManager.list(this@BinActivity) }
            adapter.submit(items)
            binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.emptyBinBtn.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showOptions(entry: BinManager.BinEntry) {
        MetroDialog.items(
            this,
            options = listOf("Restore", "Delete forever"),
            dangerIndices = setOf(1)
        ) { which ->
            when (which) {
                0 -> restore(entry)
                1 -> confirmDeleteForever(entry)
            }
        }
    }

    private fun restore(entry: BinManager.BinEntry) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { BinManager.restore(this@BinActivity, entry) }
            Toast.makeText(
                this@BinActivity,
                if (ok) "Restored to gallery" else "Couldn't restore",
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }

    private fun confirmDeleteForever(entry: BinManager.BinEntry) {
        MetroDialog.confirm(
            this,
            title = "Delete forever?",
            message = "This permanently removes the photo. It can't be recovered.",
            positive = "Delete",
            danger = true
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { BinManager.deleteForever(this@BinActivity, entry) }
                refresh()
            }
        }
    }

    private fun confirmEmpty() {
        MetroDialog.confirm(
            this,
            title = "Empty recycle bin?",
            message = "Permanently deletes everything in the bin. This can't be undone.",
            positive = "Empty",
            danger = true
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { BinManager.emptyBin(this@BinActivity) }
                refresh()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Reuses item_safe_photo (a plain thumbnail cell). Loads binned files via Glide. */
    private inner class BinAdapter(
        val onClick: (BinManager.BinEntry) -> Unit
    ) : RecyclerView.Adapter<BinAdapter.VH>() {

        private val items = mutableListOf<BinManager.BinEntry>()

        fun submit(list: List<BinManager.BinEntry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSafePhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b.root, b.thumbnail)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            Glide.with(holder.image)
                .load(entry.storedFile(this@BinActivity))
                .centerCrop()
                .into(holder.image)
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        override fun getItemCount(): Int = items.size

        inner class VH(view: View, val image: ImageView) : RecyclerView.ViewHolder(view)
    }
}
