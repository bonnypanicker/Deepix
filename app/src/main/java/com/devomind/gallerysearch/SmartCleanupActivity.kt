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
    private var pendingAllFilesDeleteUris: List<Uri> = emptyList()
    private var pendingSafeMove = false

    /** Categories whose items are worth locking away rather than deleting. */
    private val safeCapableCategories = setOf(
        CleanupAnalyzer.Category.NSFW,
        CleanupAnalyzer.Category.DOCUMENTS,
        CleanupAnalyzer.Category.RECEIPTS,
        CleanupAnalyzer.Category.QR_CODES
    )

    private val safeResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Photos successfully encrypted into the Safe: remove the originals, mirroring the
        // main-gallery selection flow (direct delete when possible, consent dialog otherwise).
        @Suppress("DEPRECATION")
        val imported = result.data?.getParcelableArrayListExtra<Uri>(SafeActivity.ExtraImportedUris)
        if (result.resultCode == RESULT_OK && !imported.isNullOrEmpty()) {
            deleteSafeOriginals(imported)
        }
    }

    /**
     * Removes the gallery copies of photos now encrypted in the Safe. The direct pass deletes
     * permanently (the vault copy replaces them); per-URI results are tracked so anything it
     * couldn't remove (unresolvable file path, locked file) is retried through the system consent
     * flow instead of being silently left behind. Only verifiably-deleted items leave the cleanup
     * lists — a photo is never dropped from review while it still exists on disk.
     */
    private fun deleteSafeOriginals(imported: List<Uri>) {
        lifecycleScope.launch {
            val deleted = mutableListOf<Uri>()
            val needsConsent = mutableListOf<Uri>()
            if (DeleteCoordinator.canDeleteDirectly(this@SmartCleanupActivity)) {
                withContext(Dispatchers.IO) {
                    imported.forEach { uri ->
                        (if (MediaFileOps.deleteFileDirect(this@SmartCleanupActivity, uri)) deleted else needsConsent)
                            .add(uri)
                    }
                }
            } else {
                needsConsent.addAll(imported)
            }
            if (deleted.isNotEmpty()) {
                pendingSafeMove = true
                onDeleted(deleted)
            }
            if (needsConsent.isNotEmpty()) {
                pendingSafeMove = true
                deleteUris(needsConsent)
            }
        }
    }

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

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val uris = pendingAllFilesDeleteUris
        pendingAllFilesDeleteUris = emptyList()
        if (uris.isEmpty()) return@registerForActivityResult
        if (StoragePermissions.hasAllFilesAccess(this)) {
            performManagedDelete(uris)
        } else {
            MetroBanner.show(this, "All-files access is required to delete items")
        }
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
        binding.safeBar.setOnClickListener { confirmMoveSelectedToSafe() }
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
            CleanupAnalyzer.Category.NSFW -> "Possibly sensitive photos — review, delete, or move to Safe"
            CleanupAnalyzer.Category.BURSTS -> "Sequences shot seconds apart — best shot kept, extras pre-selected"
            CleanupAnalyzer.Category.DARK -> "Very dark photos — tap to select"
            CleanupAnalyzer.Category.BRIGHT -> "Overexposed photos — tap to select"
            CleanupAnalyzer.Category.LOW_RESOLUTION -> "Low-resolution images — tap to select"
        }

        val list = categoryItems[category]!!
        adapter.replaceCells(detailCells(category))
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

        // Lock-away action only exists for the categories where keeping (not deleting) makes sense.
        if (currentCategory in safeCapableCategories) {
            binding.safeBar.visibility = View.VISIBLE
            if (count == 0) {
                binding.safeBar.alpha = 0.4f
                binding.safeBar.isClickable = false
                binding.safeBar.text = "Select items to move to Safe"
            } else {
                binding.safeBar.alpha = 1f
                binding.safeBar.isClickable = true
                binding.safeBar.text = "Move $count to Safe"
            }
        } else {
            binding.safeBar.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Delete (MediaStore)
    // ---------------------------------------------------------------------------------------------

    private fun confirmDeleteSelected() {
        val selected = adapter.selectedUris()
        if (selected.isEmpty()) return
        if (!DeleteCoordinator.canDeleteDirectly(this)) {
            pendingAllFilesDeleteUris = selected
            runCatching { allFilesAccessLauncher.launch(StoragePermissions.manageAllFilesIntent(this)) }
                .onFailure {
                    pendingAllFilesDeleteUris = emptyList()
                    MetroBanner.show(this, "Couldn't open storage access settings")
                }
            return
        }
        // Cleanup batches are large — always confirm, and be explicit about permanence when the
        // Recycle Bin is off. The system consent dialog path confirms itself.
        val toBin = DeleteCoordinator.usesBin(this)
        val noun = if (selected.size == 1) "1 photo" else "${selected.size} photos"
        MetroDialog.confirm(
            context = this,
            title = if (toBin) "Move to Bin?" else "Delete permanently?",
            message = if (toBin) {
                "$noun will move to the Recycle Bin. You can restore them for 30 days."
            } else {
                "Recycle Bin is off, so $noun will be deleted permanently. There's no undo."
            },
            positive = if (toBin) "Move to Bin" else "Delete",
            danger = !toBin,
            iconRes = R.drawable.ic_fluent_delete_24_regular
        ) { performManagedDelete(selected) }
    }

    /** Encrypts the selection into the Safe, then removes the originals from the gallery. */
    private fun confirmMoveSelectedToSafe() {
        val selected = adapter.selectedUris()
        if (selected.isEmpty()) return
        // Only still images can be locked; the Safe stores encrypted image zips.
        val images = selected.filter { uri ->
            val type = contentResolver.getType(uri)
            type == null || type.startsWith("image/")
        }
        if (images.isEmpty()) {
            MetroBanner.show(this, "Only photos can be moved to the Safe")
            return
        }
        val noun = if (images.size == 1) "1 photo" else "${images.size} photos"
        MetroDialog.confirm(
            context = this,
            title = "Move to Safe?",
            message = "$noun will be encrypted into your Safe and removed from the gallery.",
            positive = "Move to Safe",
            iconRes = R.drawable.ic_deepix_safe_24_regular
        ) {
            adapter.clearSelection()
            safeResultLauncher.launch(
                Intent(this, SafeActivity::class.java)
                    .putParcelableArrayListExtra(SafeActivity.ExtraImportUris, ArrayList(images))
            )
        }
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
            MetroBanner.show(this, "Bulk delete requires Android 11 or newer")
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
                MetroBanner.show(this, "Delete failed: ${error.message}")
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
        val verb = when {
            pendingSafeMove -> { pendingSafeMove = false; "moved to Safe" }
            DeleteCoordinator.usesBin(this) -> "moved to Bin"
            else -> "deleted"
        }
        MetroBanner.show(this, "${uris.size} ${if (uris.size == 1) "photo" else "photos"} $verb")
        saveCurrentToStore()

        val category = currentCategory
        if (category != null && categoryItems[category]!!.isNotEmpty()) {
            adapter.replaceCells(detailCells(category))
            adapter.clearSelection()
            onSelectionChanged(0)
        } else {
            showOverview()
        }
    }

    /**
     * Detail cells for a category. Bursts persist as a flat list built group-by-group with each
     * group's kept shot leading it, so splitting at every kept (unselected) item rebuilds the
     * per-burst sections, rendered here as full-span headers.
     */
    private fun detailCells(category: CleanupAnalyzer.Category): List<GalleryCell> {
        val list = categoryItems[category]!!
        if (category != CleanupAnalyzer.Category.BURSTS) return list.map { GalleryCell.Photo(it) }
        val extras = suggested[category]!!
        val cells = mutableListOf<GalleryCell>()
        var index = 0
        while (index < list.size) {
            if (list[index].uri in extras) {
                // Boundary lost (a kept shot was deleted earlier) — render as a standalone photo.
                cells.add(GalleryCell.Photo(list[index]))
                index++
                continue
            }
            val group = mutableListOf(list[index])
            index++
            while (index < list.size && list[index].uri in extras) {
                group.add(list[index])
                index++
            }
            val times = group.map { it.dateMillis }.filter { it > 0L }
            val subtitle = if (times.size >= 2) android.text.format.DateUtils.formatDateTime(
                this,
                times.min(),
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or
                    android.text.format.DateUtils.FORMAT_SHOW_TIME
            ) else ""
            cells.add(GalleryCell.Header("Burst · ${group.size} photos", subtitle))
            cells.addAll(group.map { GalleryCell.Photo(it) })
        }
        return cells
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
        CleanupAnalyzer.Category.NSFW -> Color.parseColor("#C2483D")
        CleanupAnalyzer.Category.BURSTS -> Color.parseColor("#4A7A96")
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
        CleanupAnalyzer.Category.NSFW -> "Sensitive"
        CleanupAnalyzer.Category.BURSTS -> "Bursts"
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
