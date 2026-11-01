package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
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
 * The Recycle Bin screen: a grid of soft-deleted media. Tapping an item offers Restore or
 * Delete forever; long-pressing enters multi-select for batch Restore / Delete forever, and
 * "Empty bin" clears everything. Retention (30 days) is enforced by [BinManager.purgeExpired] on
 * app start, so this screen only shows still-recoverable items.
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

        adapter = BinAdapter(
            onClick = { entry -> showOptions(entry) },
            onLongClick = { entry -> adapter.enterSelection(entry) },
            onSelectionChanged = { count -> updateSelectionUi(count) }
        )
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

        binding.backBtn.setOnClickListener { handleBack() }
        binding.emptyBinBtn.setOnClickListener { confirmEmpty() }
        binding.selectionAllBtn.setOnClickListener { adapter.selectAll() }
        binding.selectionRestoreBtn.setOnClickListener { restoreSelected() }
        binding.selectionDeleteBtn.setOnClickListener { confirmDeleteSelected() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        refresh()
    }

    /** Back clears an active selection first; otherwise it leaves the screen. */
    private fun handleBack() {
        if (adapter.isSelectionMode()) adapter.clearSelection() else finish()
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
            binding.selectionBar.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { BinManager.list(this@BinActivity) }
            adapter.submit(items)
            binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updateSelectionUi(count: Int) {
        binding.selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.emptyBinBtn.visibility =
            if (count > 0 || adapter.itemCount == 0) View.GONE else View.VISIBLE
        binding.selectionCount.text = if (count == 1) "1 item selected" else "$count items selected"
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

    /** Restores every selected entry back to its original folder in one batch. */
    private fun restoreSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) { BinManager.restoreByIds(this@BinActivity, ids) }
            val failed = ids.size - restored
            adapter.clearSelection()
            MetroBanner.show(this@BinActivity, buildString {
                append("$restored ${if (restored == 1) "item" else "items"} restored to gallery")
                if (failed > 0) append(" · $failed failed")
            })
            refresh()
        }
    }

    private fun confirmDeleteForever(entry: BinEntry) {
        MetroDialog.confirm(
            this,
            title = "Delete forever?",
            message = "This permanently removes the item. It can't be recovered.",
            positive = "Delete",
            danger = true
        ) {
            lifecycleScope.launch {
                val gone = withContext(Dispatchers.IO) { BinManager.deleteForever(this@BinActivity, entry) }
                if (!gone) MetroBanner.show(this@BinActivity, "Couldn't free the item — it stays in the bin")
                refresh()
            }
        }
    }

    private fun confirmDeleteSelected() {
        val targets = adapter.selectedItems()
        if (targets.isEmpty()) return
        val noun = if (targets.size == 1) "item" else "items"
        MetroDialog.confirm(
            this,
            title = "Delete ${targets.size} $noun forever?",
            message = "This permanently removes them. It can't be undone.",
            positive = "Delete",
            danger = true
        ) {
            lifecycleScope.launch {
                val freed = withContext(Dispatchers.IO) {
                    targets.count { BinManager.deleteForever(this@BinActivity, it) }
                }
                adapter.clearSelection()
                val failed = targets.size - freed
                if (failed > 0) MetroBanner.show(this@BinActivity, "$freed deleted · $failed failed")
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
                adapter.clearSelection()
                refresh()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Reuses item_safe_photo (a plain thumbnail cell). Loads binned files via Glide.
     *
     * Selection keys are entry ids, not positions, so it survives a refresh; entries that disappear
     * from the bin drop out of the selection on the next [submit].
     */
    private inner class BinAdapter(
        private val onClick: (BinEntry) -> Unit,
        private val onLongClick: (BinEntry) -> Unit,
        private val onSelectionChanged: (Int) -> Unit = {}
    ) : RecyclerView.Adapter<BinAdapter.VH>() {

        private val items = mutableListOf<BinEntry>()
        private val selected = LinkedHashSet<String>()
        private var selectionMode = false

        fun submit(list: List<BinEntry>) {
            items.clear()
            items.addAll(list)
            selected.retainAll(items.mapTo(HashSet()) { it.id })
            if (selected.isEmpty()) selectionMode = false
            notifyDataSetChanged()
            onSelectionChanged(selected.size)
        }

        fun isSelectionMode(): Boolean = selectionMode

        fun selectedIds(): List<String> = items.filter { it.id in selected }.map { it.id }

        fun selectedItems(): List<BinEntry> = items.filter { it.id in selected }

        fun toggle(entry: BinEntry) {
            if (!selected.remove(entry.id)) selected.add(entry.id)
            if (selected.isEmpty()) selectionMode = false
            notifyDataSetChanged()
            onSelectionChanged(selected.size)
        }

        fun enterSelection(entry: BinEntry) {
            if (selectionMode) return
            selectionMode = true
            selected.clear()
            selected.add(entry.id)
            notifyDataSetChanged()
            onSelectionChanged(selected.size)
        }

        fun selectAll() {
            if (items.isEmpty()) return
            selectionMode = true
            items.forEach { selected.add(it.id) }
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
            return VH(ItemSafePhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            // The item's XML height is the portrait tile; the column count is not, so the side is
            // set from the grid here and a sideways window gets more squares, not taller ones.
            holder.binding.thumbnail.layoutParams = holder.binding.thumbnail.layoutParams.apply {
                height = tileSizePx()
            }
            Glide.with(holder.binding.thumbnail)
                .load(BinManager.storedFile(this@BinActivity, entry))
                .centerCrop()
                .into(holder.binding.thumbnail)
            holder.binding.videoBadge.visibility =
                if (MediaFormats.isVideoMime(entry.mimeType) ||
                    MediaFormats.isVideoFileName(entry.fileName)
                ) View.VISIBLE else View.GONE
            val isSelected = entry.id in selected
            holder.binding.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.binding.selectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.binding.root.setOnClickListener {
                if (selectionMode) toggle(entry) else onClick(entry)
            }
            holder.binding.root.setOnLongClickListener {
                onLongClick(entry)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        inner class VH(val binding: ItemSafePhotoBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private companion object {
        // Portrait density of this screen, and the title size its layout declares.
        const val GRID_COLUMNS = 3
        const val HERO_TITLE_SP = 40f
    }
}
