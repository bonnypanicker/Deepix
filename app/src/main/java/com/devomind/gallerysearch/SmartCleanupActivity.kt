package com.devomind.gallerysearch

import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.IntentSender
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
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

    private var scanRunning = false
    private var paused = false
    private var userStopped = false
    private var scanComplete = false
    private var progressDone = 0
    private var progressTotal = 0
    private var indexingRunning = false
    private var indexProgressCurrent = 0
    private var indexProgressTotal = 0
    private var itemsByUri: Map<String, GalleryRepository.MediaItem> = emptyMap()
    private val cleanupStore by lazy { CleanupResultStore(this) }

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
        AccentPalette.apply(this)
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
        binding.refreshBtn.setOnClickListener { startScan(replace = true) }
        binding.selectAllBtn.setOnClickListener { toggleSelectAll() }
        binding.deleteBar.setOnClickListener { confirmDeleteSelected() }
        binding.pauseResumeBtn.setOnClickListener { if (scanRunning) pauseScan() else resumeScan() }
        binding.stopBtn.setOnClickListener { stopScan() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.detailView.visibility == View.VISIBLE) {
                    showOverview()
                } else {
                    finishWithResult()
                }
            }
        })

        paused = IndexPreferences.isCleanupPaused(this)
        itemsByUri = items.associateBy { it.uri.toString() }
        loadSizesAsync()
        loadStoredResults()
        observeIndexing()
        observeCleanup()
        if (!paused) startScan(replace = false)
        updateScanControls()
    }

    /**
     * Smart cleanup is live: it analyzes whatever is indexed right now, and refreshes
     * automatically when background indexing finishes (or when the user taps refresh).
     */
    private fun observeIndexing() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(IndexWorker.WorkName)
            .observe(this) { infos ->
                val work = infos.firstOrNull()
                val wasRunning = indexingRunning
                indexingRunning = work?.state == WorkInfo.State.RUNNING ||
                    work?.state == WorkInfo.State.ENQUEUED
                if (work?.state == WorkInfo.State.RUNNING) {
                    indexProgressCurrent = work.progress.getInt(IndexWorker.ProgressCurrentKey, indexProgressCurrent)
                    indexProgressTotal = work.progress.getInt(IndexWorker.ProgressTotalKey, indexProgressTotal)
                }
                // Re-scan once indexing completes so new embeddings feed the categories.
                if (wasRunning && work?.state == WorkInfo.State.SUCCEEDED && !paused && !userStopped) {
                    resumeScan()
                }
                if (binding.detailView.visibility != View.VISIBLE) {
                    showIndexingBannerIfNeeded()
                }
            }
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
        when {
            indexingRunning -> {
                binding.indexingBanner.visibility = View.VISIBLE
                val progress = if (indexProgressTotal > 0) " ($indexProgressCurrent/$indexProgressTotal)" else ""
                binding.indexingBannerText.text =
                    "Indexing is still running$progress. These results cover what's indexed so far — " +
                        "they'll refresh automatically when it finishes, or tap ↻ to update now."
            }
            indexedCount in 1 until total -> {
                binding.indexingBanner.visibility = View.VISIBLE
                binding.indexingBannerText.text =
                    "$indexedCount of $total photos are indexed. Duplicate and category detection cover " +
                        "indexed photos only — tap ↻ after indexing to update."
            }
            indexedCount == 0 -> {
                binding.indexingBanner.visibility = View.VISIBLE
                binding.indexingBannerText.text =
                    "The AI index isn't built yet, so duplicate and category detection are limited. " +
                        "Blur and quality checks still work. Tap ↻ as indexing progresses."
            }
            else -> binding.indexingBanner.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Background analysis (CleanupWorker) + live store loading
    // ---------------------------------------------------------------------------------------------

    /** Enqueues the background scan. KEEP reuses an in-flight scan; REPLACE forces a fresh one. */
    private fun startScan(replace: Boolean) {
        paused = false
        userStopped = false
        IndexPreferences.setCleanupPaused(this, false)
        if (replace) cleanupStore.clear()
        val request = androidx.work.OneTimeWorkRequestBuilder<CleanupWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            CleanupWorker.WorkName,
            if (replace) androidx.work.ExistingWorkPolicy.REPLACE else androidx.work.ExistingWorkPolicy.KEEP,
            request
        )
        updateScanControls()
    }

    private fun pauseScan() {
        paused = true
        IndexPreferences.setCleanupPaused(this, true)
        WorkManager.getInstance(this).cancelUniqueWork(CleanupWorker.WorkName)
        scanRunning = false
        updateScanControls()
    }

    private fun resumeScan() {
        paused = false
        userStopped = false
        IndexPreferences.setCleanupPaused(this, false)
        val request = androidx.work.OneTimeWorkRequestBuilder<CleanupWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            CleanupWorker.WorkName, androidx.work.ExistingWorkPolicy.KEEP, request
        )
        updateScanControls()
    }

    private fun stopScan() {
        paused = true
        userStopped = true
        IndexPreferences.setCleanupPaused(this, true)
        WorkManager.getInstance(this).cancelUniqueWork(CleanupWorker.WorkName)
        scanRunning = false
        updateScanControls()
    }

    /** Shows the progress bar + pause/resume/stop controls based on the current scan state. */
    private fun updateScanControls() {
        val showRow = !userStopped && (scanRunning || (paused && !scanComplete && (progressTotal > 0 || progressDone > 0)))
        binding.progressRow.visibility = if (showRow) View.VISIBLE else View.GONE
        binding.refreshBtn.isEnabled = !scanRunning
        binding.pauseResumeBtn.text = if (scanRunning) "Pause" else "Resume"
        if (progressTotal > 0) {
            binding.progressBar.max = progressTotal
            binding.progressBar.progress = progressDone.coerceIn(0, progressTotal)
            binding.progressText.text =
                (if (paused && !scanRunning) "Paused · " else "Scanning photos for quality · ") + "$progressDone / $progressTotal"
        } else {
            binding.progressText.text = if (paused && !scanRunning) "Paused" else "Analyzing your library…"
        }
    }

    /** Streams results from the worker: reloads the persisted store on every progress update. */
    private fun observeCleanup() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(CleanupWorker.WorkName)
            .observe(this) { infos ->
                val work = infos.firstOrNull()
                scanRunning = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED

                if (work?.state == WorkInfo.State.RUNNING) {
                    progressDone = work.progress.getInt(CleanupWorker.ProgressCurrentKey, progressDone)
                    progressTotal = work.progress.getInt(CleanupWorker.ProgressTotalKey, progressTotal)
                }
                updateScanControls()

                if (binding.detailView.visibility != View.VISIBLE) {
                    loadStoredResults()
                }
            }
    }

    private fun loadStoredResults() {
        val result = cleanupStore.load() ?: return
        scanComplete = result.complete
        if (!scanRunning && result.total > 0) {
            progressDone = result.done
            progressTotal = result.total
        }
        applyStored(result)
        if (binding.detailView.visibility != View.VISIBLE) {
            renderTiles()
            updateScanControls()
        }
    }

    private fun applyStored(result: CleanupResultStore.Result) {
        for (category in CleanupAnalyzer.Category.entries) {
            categoryItems[category]!!.apply {
                clear()
                addAll(result.categoryUris[category].orEmpty().map { itemFor(it) })
            }
            suggested[category]!!.apply {
                clear()
                result.suggestedUris[category].orEmpty().forEach { add(Uri.parse(it)) }
            }
        }
    }

    /** Resolves a stored uri to its media item, or a minimal stand-in for thumbnail display. */
    private fun itemFor(uriString: String): GalleryRepository.MediaItem {
        return itemsByUri[uriString] ?: GalleryRepository.MediaItem(
            uri = Uri.parse(uriString),
            bucketId = "",
            bucketName = "",
            dateMillis = 0L,
            width = 0,
            height = 0,
            mimeType = null,
            displayName = null,
            mediaType = GalleryRepository.MediaType.Image
        )
    }

    private fun loadSizesAsync() {
        lifecycleScope.launch {
            val sizes = withContext(Dispatchers.IO) { loadImageSizes() }
            sizeByUri = sizes
            if (binding.detailView.visibility != View.VISIBLE) renderTiles()
        }
    }

    private fun saveCurrentToStore() {
        val prior = cleanupStore.load()
        val result = CleanupResultStore.Result(
            categoryUris = categoryItems.mapValues { entry -> entry.value.map { it.uri.toString() } },
            suggestedUris = suggested.mapValues { entry -> entry.value.map { it.toString() } },
            scannedUris = prior?.scannedUris ?: emptyList(),
            done = prior?.done ?: 0,
            total = prior?.total ?: 0,
            complete = prior?.complete ?: true,
            updatedAt = System.currentTimeMillis()
        )
        cleanupStore.save(result)
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

        binding.emptyText.visibility = if (nonEmpty.isEmpty() && !scanRunning) View.VISIBLE else View.GONE
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
            CleanupAnalyzer.Category.LIKELY_CLUTTER -> "Stickers, emoji & memes pre-selected"
            CleanupAnalyzer.Category.BLURRY -> "Blurry shots pre-selected"
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
        // Prefer the app-managed path (Recycle Bin / direct delete) when we have all-files access.
        if (DeleteCoordinator.canDeleteDirectly(this)) {
            if (IndexPreferences.isSkipDeleteConfirm(this)) {
                performManagedDelete(selected)
                return
            }
            val toBin = DeleteCoordinator.usesBin(this)
            val message = when {
                toBin && selected.size == 1 -> "Move this photo to the Recycle Bin? You can restore it within 30 days."
                toBin -> "Move ${selected.size} photos to the Recycle Bin? You can restore them within 30 days."
                selected.size == 1 -> "Permanently delete this photo? This can't be undone."
                else -> "Permanently delete ${selected.size} photos? This can't be undone."
            }
            AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
                .setTitle(if (toBin) "Move to Recycle Bin?" else "Delete permanently?")
                .setMessage(message)
                .setPositiveButton(if (toBin) "Move" else "Delete") { _, _ -> performManagedDelete(selected) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
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

    private fun performManagedDelete(uris: List<Uri>) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { DeleteCoordinator.delete(this@SmartCleanupActivity, uris) }
            when (outcome) {
                is DeleteCoordinator.Outcome.NeedsSystemDelete -> deleteUris(uris)
                is DeleteCoordinator.Outcome.Done -> onDeleted(uris)
            }
        }
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
        saveCurrentToStore()

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
        CleanupAnalyzer.Category.DUPLICATES -> DesignTokens.accent(this)
        CleanupAnalyzer.Category.SIMILAR -> Color.parseColor("#2E7FD6")
        CleanupAnalyzer.Category.LIKELY_CLUTTER -> Color.parseColor("#E0823D")
        CleanupAnalyzer.Category.SCREENSHOTS -> Color.parseColor("#2FA968")
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
        CleanupAnalyzer.Category.LIKELY_CLUTTER -> "Likely clutter"
        CleanupAnalyzer.Category.SCREENSHOTS -> "Screenshots"
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
