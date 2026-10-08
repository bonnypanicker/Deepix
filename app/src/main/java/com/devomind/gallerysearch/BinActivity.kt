package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
    private lateinit var gridLayoutManager: GridLayoutManager

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
        gridLayoutManager = GridLayoutManager(this, gridColumns())
        binding.binGrid.layoutManager = gridLayoutManager
        binding.binGrid.adapter = adapter
        binding.binGrid.setHasFixedSize(true)
        // The bin is not recreated when the window changes shape, so the grid's new width — not the
        // configuration callback, which runs before that width is measured — is what re-lays it.
        binding.binGrid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width > 0 && width != oldRight - oldLeft) {
                gridLayoutManager.spanCount = gridColumns()
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        applyResponsiveChrome()

        binding.backBtn.setOnClickListener { finish() }
        binding.emptyBinBtn.setOnClickListener { confirmEmpty() }

        refresh()
    }

    /**
     * These tiles are square, so a wider window gets more of them instead of wider ones — the same
     * rule the gallery grid follows, at this screen's own density.
     */
    private fun gridColumns(): Int = Responsive.gridColumns(this, GRID_COLUMNS)

    private fun tileSizePx(): Int =
        Responsive.tileSizePx(Responsive.widthOf(binding.binGrid), gridColumns())

    private fun applyResponsiveChrome() {
        Responsive.applyTitleText(binding.screenTitle, this, HERO_TITLE_SP)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveChrome()
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

    private fun showOptions(entry: BinEntry) {
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

    private fun restore(entry: BinEntry) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { BinManager.restore(this@BinActivity, entry) }
            MetroBanner.show(
                this@BinActivity,
                if (ok) "Restored to gallery" else "Couldn't restore"
            )
            refresh()
        }
    }

    private fun confirmDeleteForever(entry: BinEntry) {
        MetroDialog.confirm(
            this,
            title = "Delete forever?",
            message = "This permanently removes the photo. It can't be recovered.",
            positive = "Delete",
            danger = true
        ) {
            lifecycleScope.launch {
                val gone = withContext(Dispatchers.IO) { BinManager.deleteForever(this@BinActivity, entry) }
                if (!gone) MetroBanner.show(this@BinActivity, "Couldn't free the photo — it stays in the bin")
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
        val onClick: (BinEntry) -> Unit
    ) : RecyclerView.Adapter<BinAdapter.VH>() {

        private val items = mutableListOf<BinEntry>()

        fun submit(list: List<BinEntry>) {
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
            // The item's XML height is the portrait tile; the column count is not, so the side is
            // set from the grid here and a sideways window gets more squares, not taller ones.
            holder.image.layoutParams = holder.image.layoutParams.apply { height = tileSizePx() }
            Glide.with(holder.image)
                .load(BinManager.storedFile(this@BinActivity, entry))
                .centerCrop()
                .into(holder.image)
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        override fun getItemCount(): Int = items.size

        inner class VH(view: View, val image: ImageView) : RecyclerView.ViewHolder(view)
    }

    private companion object {
        // Portrait density of this screen, and the title size its layout declares.
        const val GRID_COLUMNS = 3
        const val HERO_TITLE_SP = 40f
    }
}
