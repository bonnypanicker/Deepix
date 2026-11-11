package com.devomind.gallerysearch

import android.app.Activity
import android.content.IntentSender
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
 * "Empty bin" clears everything.
 *
 * The bin itself is [BinStore]: the platform's system trash on API 30+ (Restore / Delete-forever are
 * MediaStore consent requests, so those actions finish through [consentLauncher]) and the
 * app-private bin on older releases (they complete in place). Retention (30 days) is enforced by the
 * system on 30+ and by [BinManager.purgeExpired] on app start below that.
 */
class BinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBinBinding
    private lateinit var adapter: BinAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    /** Message to show once the pending system consent returns, or null when none is in flight. */
    private var pendingAction: String? = null

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val label = pendingAction
        pendingAction = null
        if (result.resultCode == Activity.RESULT_OK && label != null) {
            MetroBanner.show(this, label)
        }
        refresh()
    }

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
            onClick = { item -> showOptions(item) },
            onLongClick = { item -> adapter.enterSelection(item) },
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
            val items = withContext(Dispatchers.IO) { BinStore.list(this@BinActivity) }
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

    private fun showOptions(item: BinItem) {
        MetroDialog.items(
            this,
            options = listOf("Restore", "Delete forever"),
            dangerIndices = setOf(1)
        ) { which ->
            when (which) {
                0 -> restore(listOf(item), restoredLabel(1))
                1 -> confirmDeleteForever(listOf(item))
            }
        }
    }

    /** Restores every selected entry back to the gallery in one batch. */
    private fun restoreSelected() {
        val targets = adapter.selectedItems()
        if (targets.isEmpty()) return
        adapter.clearSelection()
        restore(targets, restoredLabel(targets.size))
    }

    private fun confirmDeleteForever(targets: List<BinItem>) {
        val single = targets.size == 1
        MetroDialog.confirm(
            this,
            title = if (single) "Delete forever?" else "Delete ${targets.size} items forever?",
            message = if (single) {
                "This permanently removes the item. It can't be recovered."
            } else {
                "This permanently removes them. It can't be undone."
            },
            positive = "Proceed",
            danger = true
        ) {
            runBinAction(if (single) "Deleted" else "${targets.size} deleted") {
                BinStore.deleteForever(this, targets)
            }
        }
    }

    private fun confirmDeleteSelected() {
        val targets = adapter.selectedItems()
        if (targets.isEmpty()) return
        adapter.clearSelection()
        confirmDeleteForever(targets)
    }

    private fun confirmEmpty() {
        MetroDialog.confirm(
            this,
            title = "Empty recycle bin?",
            message = "Permanently deletes everything in the bin. This can't be undone.",
            positive = "Proceed",
            danger = true
        ) {
            runBinAction("Bin emptied") { BinStore.empty(this) }
        }
    }

    private fun restore(targets: List<BinItem>, label: String) {
        if (targets.isEmpty()) return
        runBinAction(label) { BinStore.restore(this, targets) }
    }

    private fun restoredLabel(count: Int): String =
        if (count == 1) "Restored to gallery" else "$count items restored"

    /**
     * Runs a bin mutation and finishes it the right way for the backing store: a system consent
     * dialog on API 30+, or an in-place result below that.
     */
    private fun runBinAction(label: String, action: () -> IntentSender?) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { action() } }
            result.fold(
                onSuccess = { request ->
                    if (request != null) {
                        pendingAction = label
                        consentLauncher.launch(IntentSenderRequest.Builder(request).build())
                    } else {
                        MetroBanner.show(this@BinActivity, label)
                        refresh()
                    }
                },
                onFailure = {
                    MetroBanner.show(this@BinActivity, "Couldn't complete")
                    refresh()
                }
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Reuses item_safe_photo (a plain thumbnail cell). Loads each item from its content uri.
     *
     * Selection keys are item keys, not positions, so it survives a refresh; items that disappear
     * from the bin drop out of the selection on the next [submit].
     */
    private inner class BinAdapter(
        private val onClick: (BinItem) -> Unit,
        private val onLongClick: (BinItem) -> Unit,
        private val onSelectionChanged: (Int) -> Unit = {}
    ) : RecyclerView.Adapter<BinAdapter.VH>() {

        private val items = mutableListOf<BinItem>()
        private val selected = LinkedHashSet<String>()
        private var selectionMode = false

        fun submit(list: List<BinItem>) {
            items.clear()
            items.addAll(list)
            selected.retainAll(items.mapTo(HashSet()) { it.key })
            if (selected.isEmpty()) selectionMode = false
            notifyDataSetChanged()
            onSelectionChanged(selected.size)
        }

        fun isSelectionMode(): Boolean = selectionMode

        fun selectedItems(): List<BinItem> = items.filter { it.key in selected }

        fun toggle(item: BinItem) {
            if (!selected.remove(item.key)) selected.add(item.key)
            if (selected.isEmpty()) selectionMode = false
            notifyDataSetChanged()
            onSelectionChanged(selected.size)
        }

        fun enterSelection(item: BinItem) {
            if (selectionMode) return
            selectionMode = true
            selected.clear()
            selected.add(item.key)
            notifyDataSetChanged()
            onSelectionChanged(selected.size)
        }

        fun selectAll() {
            if (items.isEmpty()) return
            selectionMode = true
            items.forEach { selected.add(it.key) }
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
            val item = items[position]
            // The item's XML height is the portrait tile; the column count is not, so the side is
            // set from the grid here and a sideways window gets more squares, not taller ones.
            holder.binding.thumbnail.layoutParams = holder.binding.thumbnail.layoutParams.apply {
                height = tileSizePx()
            }
            Glide.with(holder.binding.thumbnail)
                .load(item.displayUri)
                .centerCrop()
                .into(holder.binding.thumbnail)
            holder.binding.videoBadge.visibility =
                if (MediaFormats.isVideoMime(item.mimeType) ||
                    MediaFormats.isVideoFileName(item.fileName)
                ) View.VISIBLE else View.GONE
            val isSelected = item.key in selected
            holder.binding.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.binding.selectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.binding.root.setOnClickListener {
                if (selectionMode) toggle(item) else onClick(item)
            }
            holder.binding.root.setOnLongClickListener {
                onLongClick(item)
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
