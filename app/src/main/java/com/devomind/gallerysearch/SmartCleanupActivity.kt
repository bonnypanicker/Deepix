package com.devomind.gallerysearch

import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.devomind.gallerysearch.databinding.ActivitySmartCleanupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SmartCleanupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmartCleanupBinding
    private lateinit var adapter: ImageAdapter

    private var items: List<GalleryRepository.MediaItem> = emptyList()
    private var indexedCount = 0
    private var contentChanged = false

    // Mutable working copy of the analysis so counts update as items are deleted.
    private val categoryItems = linkedMapOf<CleanupAnalyzer.Category, MutableList<GalleryRepository.MediaItem>>()
    private val suggested = linkedMapOf<CleanupAnalyzer.Category, MutableSet<Uri>>()
    private var sizeByUri: Map<String, Long> = emptyMap()

    private var currentCategory: CleanupAnalyzer.Category? = null
    private var pendingDeleteUris: List<Uri> = emptyList()
    private var pendingDeleteNeedsRetry = false

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uris = pendingDeleteUris
        val needsRetry = pendingDeleteNeedsRetry
        pendingDeleteUris = emptyList()
        pendingDeleteNeedsRetry = false
        if (result.resultCode != RESULT_OK || uris.isEmpty()) return@registerForActivityResult
        if (needsRetry) deleteUris(uris, afterApproval = true) else onDeleted(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivitySmartCleanupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        items = CleanupHandoff.items
        indexedCount = CleanupHandoff.indexedCount
        CleanupHandoff.release()

        if (items.isEmpty()) {
            Toast.makeText(this, "No photos to clean up.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        for (category in CleanupAnalyzer.Category.entries) {
            categoryItems[category] = mutableListOf()
            suggested[category] = linkedSetOf()
        }

        adapter = ImageAdapter(
            onPhotoClick = { item, _ -> adapter.toggle(item.uri) },
            onSelectionChanged = ::onSelectionChanged,
            onAlbumClick = {},
            onAlbumLongClick = { _, _ -> }
        )
        adapter.useCollageLayout = false
        val spanCount = IndexPreferences.getGridColumnCount(this).coerceIn(3, 6)
        adapter.gridColumnCount = spanCount
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = adapter.spanSizeAt(position, spanCount)
        }
        binding.cleanupGrid.layoutManager = layoutManager
        binding.cleanupGrid.adapter = adapter

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.selectAllBtn.setOnClickListener { toggleSelectAll() }
        binding.deleteBar.setOnClickListener { confirmDeleteSelected() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.detailView.visibility == View.VISIBLE) {
                    showOverview()
                } else {
                    finishWithResult()
                }
            }
        })

        showIndexingBannerIfNeeded()
        runAnalysis()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.cleanupRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            binding.deleteBar.updatePadding(bottom = bars.bottom + dp(18))
            insets
        }
    }

    private fun showIndexingBannerIfNeeded() {
        val total = items.size
        if (indexedCount >= total) {
            binding.indexingBanner.visibility = View.GONE
            return
        }
        binding.indexingBanner.visibility = View.VISIBLE
        binding.indexingBannerText.text = if (indexedCount == 0) {
            "The AI index hasn't been built yet, so duplicate and category detection won't work. " +
                "Finish indexing first for the best results — blur detection still works now."
        } else {
            "Only $indexedCount of $total photos are indexed. Duplicate and category detection " +
                "cover indexed photos only. Run this again after indexing completes for full results."
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Analysis
    // ---------------------------------------------------------------------------------------------

    private fun runAnalysis() {
        binding.progressRow.visibility = View.VISIBLE
        binding.tilesContainer.removeAllViews()
        binding.emptyText.visibility = View.GONE

        lifecycleScope.launch {
            val report = withContext(Dispatchers.Default) {
                val textEncoder = runCatching {
                    (application as GallerySearchApp).sharedEncoders.getTextEncoder()
                }.getOrNull()
                val repo = GalleryRepository(applicationContext, null, textEncoder)
                val embeddings = repo.allEmbeddings()
                val sizes = loadImageSizes()
                CleanupAnalyzer.analyze(
                    items = items,
                    embeddings = embeddings,
                    sizeByUri = sizes,
                    encodeText = { runCatching { repo.encodeText(it) }.getOrNull() },
                    imageStats = { computeImageStats(it) },
                    onProgress = { done, total ->
                        runOnUiThread {
                            binding.progressText.text =
                                if (total > 0) "Scanning photos… $done / $total" else "Analyzing your library…"
                        }
                    }
                )
            }

            sizeByUri = report.sizeByUri
            for (category in CleanupAnalyzer.Category.entries) {
                categoryItems[category]!!.apply {
                    clear()
                    addAll(report.categoryItems[category].orEmpty())
                }
                suggested[category]!!.apply {
                    clear()
                    addAll(report.suggestedDeleteUris[category].orEmpty())
                }
            }

            binding.progressRow.visibility = View.GONE
            renderTiles()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Overview tiles
    // ---------------------------------------------------------------------------------------------

    private fun renderTiles() {
        binding.tilesContainer.removeAllViews()
        val nonEmpty = CleanupAnalyzer.Category.entries
            .filter { categoryItems[it]!!.isNotEmpty() }
            .sortedByDescending { categoryReclaimable(it) }

        for (category in nonEmpty) {
            val count = categoryItems[category]!!.size
            val tile = layoutInflater.inflate(R.layout.item_cleanup_tile, binding.tilesContainer, false)
            tile.setBackgroundColor(tileColor(category))
            tile.findViewById<TextView>(R.id.tileCount).text = count.toString()
            tile.findViewById<TextView>(R.id.tileTitle).text = categoryTitle(category)
            val bytes = categoryReclaimable(category)
            tile.findViewById<TextView>(R.id.tileSize).text =
                if (bytes > 0L) formatBytes(bytes) else ""
            tile.setOnClickListener { openCategory(category) }
            binding.tilesContainer.addView(tile)
        }

        binding.emptyText.visibility = if (nonEmpty.isEmpty()) View.VISIBLE else View.GONE
        updateSummary()
    }

    private fun updateSummary() {
        val clutter = LinkedHashSet<Uri>()
        for (category in CleanupAnalyzer.Category.entries) clutter.addAll(categoryDeletable(category))
        if (clutter.isEmpty()) {
            binding.summaryCard.visibility = View.GONE
            return
        }
        val bytes = clutter.sumOf { sizeByUri[it.toString()] ?: 0L }
        binding.summaryCard.visibility = View.VISIBLE
        binding.summaryAmount.text = formatBytes(bytes)
        binding.summaryItems.text = "${clutter.size} items are likely clutter and safe to review."
    }

    /** Items we'd delete in a category if suggestions are accepted (else all of them). */
    private fun categoryDeletable(category: CleanupAnalyzer.Category): Set<Uri> {
        val s = suggested[category]!!
        if (s.isNotEmpty()) return s
        return categoryItems[category]!!.mapTo(LinkedHashSet()) { it.uri }
    }

    private fun categoryReclaimable(category: CleanupAnalyzer.Category): Long =
        categoryDeletable(category).sumOf { sizeByUri[it.toString()] ?: 0L }

    private fun showOverview() {
        currentCategory = null
        adapter.clearSelection()
        binding.detailView.visibility = View.GONE
        binding.overviewView.visibility = View.VISIBLE
        renderTiles()
    }

    // ---------------------------------------------------------------------------------------------
    // Category detail
    // ---------------------------------------------------------------------------------------------

    private fun openCategory(category: CleanupAnalyzer.Category) {
        currentCategory = category
        binding.overviewView.visibility = View.GONE
        binding.detailView.visibility = View.VISIBLE
        binding.detailTitle.text = categoryTitle(category)
        binding.detailHint.text = when (category) {
            CleanupAnalyzer.Category.DUPLICATES -> "Best copy kept; extra copies pre-selected"
            CleanupAnalyzer.Category.SIMILAR -> "Best shot kept; near-identical ones pre-selected"
            CleanupAnalyzer.Category.BLURRY -> "Blurry shots pre-selected"
            CleanupAnalyzer.Category.MEMES -> "Memes pre-selected"
            CleanupAnalyzer.Category.STICKERS -> "Stickers & emoji pre-selected"
            CleanupAnalyzer.Category.SCREENSHOTS -> "Tap to select the ones to remove"
            CleanupAnalyzer.Category.DOCUMENTS -> "Documents — review before deleting"
            CleanupAnalyzer.Category.RECEIPTS -> "Receipts — review before deleting"
            CleanupAnalyzer.Category.QR_CODES -> "QR codes & barcodes — tap to select"
            CleanupAnalyzer.Category.DARK -> "Very dark photos — tap to select"
            CleanupAnalyzer.Category.BRIGHT -> "Overexposed photos — tap to select"
            CleanupAnalyzer.Category.LOW_RESOLUTION -> "Low-resolution images — tap to select"
        }

        val list = categoryItems[category]!!
        adapter.replaceCells(list.map { GalleryCell.Photo(it) })
        binding.cleanupGrid.scrollToPosition(0)
        adapter.setSelection(suggested[category]!!.toList())
    }

    private fun toggleSelectAll() {
        val category = currentCategory ?: return
        val all = categoryItems[category]!!.map { it.uri }
        if (adapter.selectionCount >= all.size && all.isNotEmpty()) {
            adapter.clearSelection()
        } else {
            adapter.setSelection(all)
        }
    }

    private fun onSelectionChanged(count: Int) {
        val all = currentCategory?.let { categoryItems[it]!!.size } ?: 0
        binding.selectAllBtn.text = if (count > 0 && count >= all) "Deselect all" else "Select all"

        if (count == 0) {
            binding.deleteBar.alpha = 0.4f
            binding.deleteBar.isClickable = false
            binding.deleteBar.text = "Select items to delete"
        } else {
            binding.deleteBar.alpha = 1f
            binding.deleteBar.isClickable = true
            val bytes = adapter.selectedUris().sumOf { sizeByUri[it.toString()] ?: 0L }
            binding.deleteBar.text = buildString {
                append("Delete $count")
                if (bytes > 0L) append("  ·  ${formatBytes(bytes)}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Delete (MediaStore)
    // ---------------------------------------------------------------------------------------------

    private fun confirmDeleteSelected() {
        val selected = adapter.selectedUris()
        if (selected.isEmpty()) return
        val message = if (selected.size == 1) {
            "Delete the selected item from this device?"
        } else {
            "Delete ${selected.size} selected items from this device?"
        }
        AlertDialog.Builder(this)
            .setTitle("Delete items?")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ -> deleteUris(selected) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Suppress("InstanceOfCheckForException")
    private fun deleteUris(uris: List<Uri>, afterApproval: Boolean = false) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && uris.size > 1 && !afterApproval) {
            Toast.makeText(this, "Bulk delete requires Android 11 or newer.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !afterApproval) {
                pendingDeleteUris = uris
                pendingDeleteNeedsRetry = false
                val request = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                return
            }
            uris.forEach { contentResolver.delete(it, null, null) }
            onDeleted(uris)
        } catch (error: Throwable) {
            val recoverable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                error is RecoverableSecurityException && !afterApproval
            if (recoverable) {
                pendingDeleteUris = uris
                pendingDeleteNeedsRetry = true
                launchConsent((error as RecoverableSecurityException).userAction.actionIntent.intentSender)
            } else {
                Toast.makeText(this, "Delete failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchConsent(sender: IntentSender) {
        deleteRequestLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    private fun onDeleted(uris: List<Uri>) {
        contentChanged = true
        val removed = uris.toSet()
        // Drop the deleted items from every category + suggestion set so counts stay correct.
        for (category in CleanupAnalyzer.Category.entries) {
            categoryItems[category]!!.removeAll { it.uri in removed }
            suggested[category]!!.removeAll(removed)
        }
        Toast.makeText(this, "${uris.size} item${if (uris.size == 1) "" else "s"} deleted.", Toast.LENGTH_SHORT).show()

        val category = currentCategory
        if (category != null && categoryItems[category]!!.isNotEmpty()) {
            adapter.replaceCells(categoryItems[category]!!.map { GalleryCell.Photo(it) })
            adapter.clearSelection()
            onSelectionChanged(0)
        } else {
            showOverview()
        }
    }

    private fun finishWithResult() {
        if (contentChanged) {
            setResult(RESULT_OK, Intent().putExtra(ExtraContentChanged, true))
        }
        finish()
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun tileColor(category: CleanupAnalyzer.Category): Int = when (category) {
        CleanupAnalyzer.Category.DUPLICATES -> Color.parseColor("#3B9EFF")
        CleanupAnalyzer.Category.SIMILAR -> Color.parseColor("#2E7FD6")
        CleanupAnalyzer.Category.SCREENSHOTS -> Color.parseColor("#2FA968")
        CleanupAnalyzer.Category.MEMES -> Color.parseColor("#E0823D")
        CleanupAnalyzer.Category.STICKERS -> Color.parseColor("#D2603B")
        CleanupAnalyzer.Category.DOCUMENTS -> Color.parseColor("#5C6BC0")
        CleanupAnalyzer.Category.RECEIPTS -> Color.parseColor("#7E57C2")
        CleanupAnalyzer.Category.QR_CODES -> Color.parseColor("#00897B")
        CleanupAnalyzer.Category.BLURRY -> Color.parseColor("#8E7CD8")
        CleanupAnalyzer.Category.DARK -> Color.parseColor("#455A64")
        CleanupAnalyzer.Category.BRIGHT -> Color.parseColor("#B08968")
        CleanupAnalyzer.Category.LOW_RESOLUTION -> Color.parseColor("#6D7B8D")
    }

    private fun categoryTitle(category: CleanupAnalyzer.Category): String = when (category) {
        CleanupAnalyzer.Category.DUPLICATES -> "Duplicates"
        CleanupAnalyzer.Category.SIMILAR -> "Similar photos"
        CleanupAnalyzer.Category.SCREENSHOTS -> "Screenshots"
        CleanupAnalyzer.Category.MEMES -> "Memes"
        CleanupAnalyzer.Category.STICKERS -> "Stickers & emoji"
        CleanupAnalyzer.Category.DOCUMENTS -> "Documents"
        CleanupAnalyzer.Category.RECEIPTS -> "Receipts"
        CleanupAnalyzer.Category.QR_CODES -> "QR codes"
        CleanupAnalyzer.Category.BLURRY -> "Blurry"
        CleanupAnalyzer.Category.DARK -> "Too dark"
        CleanupAnalyzer.Category.BRIGHT -> "Overexposed"
        CleanupAnalyzer.Category.LOW_RESOLUTION -> "Low quality"
    }

    private fun loadImageSizes(): Map<String, Long> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE)
        val map = HashMap<String, Long>()
        runCatching {
            contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val size = cursor.getLong(sizeCol)
                    map[android.content.ContentUris.withAppendedId(collection, id).toString()] = size
                }
            }
        }.onFailure { Log.w(TAG, "Unable to read image sizes for cleanup.", it) }
        return map
    }

    private fun computeImageStats(uri: Uri): CleanupAnalyzer.ImageStats? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null
            val target = 128
            var sample = 1
            while (srcW / (sample * 2) >= target && srcH / (sample * 2) >= target) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val stats = imageStatsOf(bmp)
            bmp.recycle()
            stats
        }.getOrNull()
    }

    /** Laplacian variance (blur) + mean luminance (dark) + near-white fraction (overexposed). */
    private fun imageStatsOf(bmp: Bitmap): CleanupAnalyzer.ImageStats {
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return CleanupAnalyzer.ImageStats(Float.MAX_VALUE, 128f, 0f)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        var lumaSum = 0L
        var nearWhite = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            gray[i] = lum
            lumaSum += lum
            if (lum >= 245) nearWhite++
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val lap = 4 * gray[idx] - gray[idx - 1] - gray[idx + 1] - gray[idx - w] - gray[idx + w]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        val variance = if (n == 0) Float.MAX_VALUE else {
            val mean = sum / n
            (sumSq / n - mean * mean).toFloat()
        }
        val meanLuma = lumaSum.toFloat() / pixels.size
        val fracNearWhite = nearWhite.toFloat() / pixels.size
        return CleanupAnalyzer.ImageStats(variance, meanLuma, fracNearWhite)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024.0 -> String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.0f MB", mb)
            else -> "${bytes / 1024L} KB"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "SmartCleanup"
        const val ExtraContentChanged = "content_changed"
    }
}
