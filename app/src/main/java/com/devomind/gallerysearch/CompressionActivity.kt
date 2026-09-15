package com.devomind.gallerysearch

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ActivityCompressionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import java.util.Locale

private typealias Status = CompressionBatchStore.ItemStatus
private typealias Phase = CompressionBatchStore.Phase

/**
 * Review screen for HEIC compression (Smart Cleanup → Compression tile).
 *
 * The screen is a thin UI over [CompressionWorker]: it records the user's selection + preset into a
 * [CompressionBatchStore] batch and enqueues the encode pass as a foreground service, then observes
 * the batch back. Because the work runs in WorkManager with a system-notification progress bar,
 * closing the app (or the system killing it) can never lose a photo — interrupted work is either
 * resumed by WorkManager or settled by [CompressionEngine.recover] on next start.
 *
 * Reopening with the same selection adopts the in-flight batch instead of restarting it. Confirming
 * "Replace originals" / "Save copies" enqueues the crash-safe commit pass, which keeps running (and
 * reporting progress in the notification) even after this screen is gone.
 */
class CompressionActivity : AppCompatActivity() {

    private data class Row(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val status: Status,
        val note: String,
        val sizeBefore: Long,
        val sizeAfter: Long,
        val format: String,
        val entryId: String
    )

    private lateinit var binding: ActivityCompressionBinding
    private lateinit var store: CompressionBatchStore
    private lateinit var workManager: WorkManager

    private var batchId: String? = null
    private var batch: CompressionBatchStore.Batch? = null
    private var journalById: Map<String, CompressionEngine.Entry> = emptyMap()
    private val rows = mutableListOf<Row>()

    private var preset: CompressionEngine.Preset = CompressionEngine.Presets.BALANCED
    private var commitRunning = false
    private var commitProgress = 0 to 0
    private var prepareRunning = false
    private var prepareProgress = 0 to 0
    private var compareEntryId: String? = null
    private var finished = false
    private var refreshInFlight = false
    private var refreshPending = false

    /** A selection that arrived while a batch was already running; resolved by the prompt in
     *  [promptReplaceActiveBatchIfNeeded] instead of silently discarding the in-flight batch. */
    private var pendingSelectionPrompt: PendingSelection? = null

    private data class PendingSelection(
        val items: List<GalleryRepository.MediaItem>,
        val selected: List<Uri>,
        val activeCount: Int
    )

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (StoragePermissions.hasAllFilesAccess(this)) {
            confirmBatch(CompressionBatchStore.CommitMode.REPLACE)
        } else {
            MetroBanner.show(this, "All-files access is required to replace originals")
        }
    }

    /** Full-screen compare result: "Keep original" discards the staged entry for that row. */
    private val compareResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val entryId = compareEntryId
        compareEntryId = null
        if (entryId == null) return@registerForActivityResult
        if (result.resultCode == CompareFullscreenActivity.ResultKeepOriginal) {
            keepOriginal(entryId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityCompressionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        store = CompressionBatchStore(this)
        workManager = WorkManager.getInstance(this)

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.qualityHigh.setOnClickListener { setPreset(CompressionEngine.Presets.HIGH) }
        binding.qualityBalanced.setOnClickListener { setPreset(CompressionEngine.Presets.BALANCED) }
        binding.qualitySmall.setOnClickListener { setPreset(CompressionEngine.Presets.SMALL) }
        binding.replaceBar.setOnClickListener { onReplaceClicked() }
        binding.keepBothBar.setOnClickListener { confirmBatch(CompressionBatchStore.CommitMode.COPY) }
        binding.compressionList.layoutManager = LinearLayoutManager(this)
        binding.compressionList.adapter = RowAdapter()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { onBack() }
        })

        if (!initBatch()) {
            Toast.makeText(this, "No photos to compress.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        updateQualityChips()
        observeWork()
        refresh()
        promptReplaceActiveBatchIfNeeded()

        binding.batchCancelBtn.setOnClickListener { confirmCancelCompression() }
    }

    // ---------------------------------------------------------------------------------------------
    // Batch set-up: adopt an in-flight batch, or start a fresh one from the hand-off
    // ---------------------------------------------------------------------------------------------

    /** Returns false when there is nothing to show (no selection and no active batch). */
    private fun initBatch(): Boolean {
        val items = CompressionHandoff.items
        val selected = CompressionHandoff.selectedUris
        CompressionHandoff.release()
        val existing = store.load()
        val selectedSet = selected.map { it.toString() }.toSet()

        // Process death cleared the in-memory hand-off: adopt whatever batch is still active.
        if (selected.isEmpty()) {
            val active = existing?.takeIf { it.isActive } ?: return false
            return adopt(active)
        }

        // Reopening with the same selection adopts the in-flight batch instead of restarting it.
        if (existing != null && existing.isActive &&
            existing.items.map { it.uri }.toSet() == selectedSet
        ) {
            return adopt(existing)
        }

        // A different selection arrived while a batch is already running: never throw the user's
        // in-flight batch away silently — adopt it and let them choose what happens to it.
        if (existing != null && existing.isActive) {
            pendingSelectionPrompt = PendingSelection(items, selected, existing.items.size)
            return adopt(existing)
        }
        return startFresh(items, selected)
    }

    private fun adopt(existing: CompressionBatchStore.Batch): Boolean {
        batchId = existing.id
        preset = presetFor(existing.quality, existing.maxDimension)
        when (existing.phase) {
            // Prepare may have been interrupted by process death; KEEP re-attaches without duplicating.
            Phase.PREPARING -> enqueuePrepare(ExistingWorkPolicy.KEEP)
            // A confirmed commit that stalled (work failed/interrupted) resumes on re-entry.
            Phase.COMMITTING -> enqueueCommit()
            else -> Unit
        }
        return true
    }

    private fun startFresh(
        items: List<GalleryRepository.MediaItem>,
        selected: List<Uri>
    ): Boolean {
        val byUri = items.associateBy { it.uri }
        val newItems = mutableListOf<CompressionBatchStore.Item>()
        for (uri in selected) {
            val item = byUri[uri] ?: continue
            val mime = item.mimeType.orEmpty()
            val compressible = CompressionEngine.isCompressibleMime(mime)
            newItems.add(
                CompressionBatchStore.Item(
                    uri = uri.toString(),
                    displayName = item.displayName.orEmpty(),
                    mimeType = mime,
                    sizeBytes = item.sizeBytes,
                    status = if (compressible) Status.PENDING else Status.FAILED,
                    note = if (compressible) "" else "Already an efficient format"
                )
            )
        }
        if (newItems.isEmpty()) return false
        val id = CompressionBatchStore.newBatchId()
        val newBatch = CompressionBatchStore.Batch(
            id = id,
            phase = Phase.PREPARING,
            quality = preset.quality,
            maxDimension = preset.maxDimension,
            items = newItems
        )
        store.save(newBatch)
        batchId = id
        enqueuePrepare(ExistingWorkPolicy.REPLACE)
        return true
    }

    private fun presetFor(quality: Int, maxDimension: Int): CompressionEngine.Preset = when {
        quality == CompressionEngine.Presets.HIGH.quality &&
            maxDimension == CompressionEngine.Presets.HIGH.maxDimension -> CompressionEngine.Presets.HIGH
        quality == CompressionEngine.Presets.SMALL.quality &&
            maxDimension == CompressionEngine.Presets.SMALL.maxDimension -> CompressionEngine.Presets.SMALL
        else -> CompressionEngine.Presets.BALANCED
    }

    private fun enqueuePrepare(policy: ExistingWorkPolicy) {
        val id = batchId ?: return
        val request = OneTimeWorkRequestBuilder<CompressionWorker>()
            .setInputData(
                Data.Builder()
                    .putString(CompressionWorker.KeyBatchId, id)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(CompressionWorker.PrepareWorkName, policy, request)
    }

    private fun enqueueCommit() {
        val id = batchId ?: return
        val request = OneTimeWorkRequestBuilder<CompressionWorker>()
            .setInputData(
                Data.Builder()
                    .putString(CompressionWorker.KeyBatchId, id)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(CompressionWorker.CommitWorkName, ExistingWorkPolicy.KEEP, request)
    }

    /** Cancels in-flight work and discards the batch's staged temp files (originals untouched).
     *  Cleanup runs on a daemon thread: the caller is usually finishing this screen, and a
     *  lifecycleScope coroutine would be cancelled before the staged files are ever settled. */
    private fun abandon(batch: CompressionBatchStore.Batch) {
        workManager.cancelUniqueWork(CompressionWorker.PrepareWorkName)
        workManager.cancelUniqueWork(CompressionWorker.CommitWorkName)
        store.clear()
        val entryIds = batch.items.mapNotNull { it.entryId.takeIf(String::isNotBlank) }
        if (entryIds.isEmpty()) return
        val appContext = applicationContext
        thread(isDaemon = true) {
            runCatching {
                val journal = CompressionEngine.loadJournal(appContext)
                journal.filter { it.id in entryIds }.forEach {
                    runCatching { CompressionEngine.discard(appContext, it) }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Observe the worker + batch store
    // ---------------------------------------------------------------------------------------------

    private fun observeWork() {
        workManager.getWorkInfosForUniqueWorkLiveData(CompressionWorker.PrepareWorkName)
            .observe(this) { infos ->
                val work = IndexWorker.pickRelevantWorkInfo(infos)
                prepareRunning = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED
                if (work?.state == WorkInfo.State.RUNNING) {
                    prepareProgress = work.progress.getInt(CompressionWorker.ProgressCurrentKey, prepareProgress.first) to
                        work.progress.getInt(CompressionWorker.ProgressTotalKey, prepareProgress.second)
                }
                refresh()
            }
        workManager.getWorkInfosForUniqueWorkLiveData(CompressionWorker.CommitWorkName)
            .observe(this) { infos ->
                val work = IndexWorker.pickRelevantWorkInfo(infos)
                commitRunning = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED
                if (work?.state == WorkInfo.State.RUNNING) {
                    commitProgress = work.progress.getInt(CompressionWorker.ProgressCurrentKey, commitProgress.first) to
                        work.progress.getInt(CompressionWorker.ProgressTotalKey, commitProgress.second)
                }
                refresh()
            }
    }

    /** Reloads the batch + journal off the main thread and re-renders. Bursts are coalesced so a
     *  stream of worker progress ticks never piles up reloads (mirrors SmartCleanupActivity). */
    private fun refresh() {
        val id = batchId ?: return
        if (refreshInFlight) {
            refreshPending = true
            return
        }
        refreshInFlight = true
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val loaded = store.load()?.takeIf { it.id == id }
                val journal = CompressionEngine.loadJournal(this@CompressionActivity)
                    .associateBy { it.id }
                loaded to journal
            }
            val (loaded, journal) = snapshot
            journalById = journal
            batch = loaded
            refreshInFlight = false
            if (loaded == null) {
                // Batch cleared underneath us (abandoned elsewhere): nothing to show.
                if (!finished) finish()
                return@launch
            }
            render(loaded)
            if (loaded.phase == Phase.DONE) {
                finishWithResult(loaded)
                return@launch
            }
            if (refreshPending) {
                refreshPending = false
                refresh()
            }
        }
    }

    private fun render(batch: CompressionBatchStore.Batch) {
        rows.clear()
        for (item in batch.items) {
            rows.add(
                Row(
                    uri = Uri.parse(item.uri),
                    displayName = item.displayName,
                    sizeBytes = item.sizeBytes,
                    status = item.status,
                    note = item.note,
                    sizeBefore = item.sizeBefore,
                    sizeAfter = item.sizeAfter,
                    format = item.format,
                    entryId = item.entryId
                )
            )
        }
        val count = batch.items.size
        binding.titleText.text = "Compress $count ${if (count == 1) "photo" else "photos"}"
        binding.compressionList.adapter?.notifyDataSetChanged()
        updateSummary(batch)
        updateProgressRow(batch)
    }

    // ---------------------------------------------------------------------------------------------
    // Preset
    // ---------------------------------------------------------------------------------------------

    private fun setPreset(newPreset: CompressionEngine.Preset) {
        if (newPreset == preset) return
        val current = batch ?: return
        // Presets change the encoder output, so they only apply before the commit is confirmed.
        if (current.phase == Phase.COMMITTING || current.phase == Phase.DONE || commitRunning) {
            MetroBanner.show(this, "Can't change quality while replacing")
            return
        }
        preset = newPreset
        updateQualityChips()
        // Restart the encode pass at the new preset as a fresh batch (discards old staged files).
        workManager.cancelUniqueWork(CompressionWorker.PrepareWorkName)
        val entryIds = current.items.mapNotNull { it.entryId.takeIf(String::isNotBlank) }
        val reused = current.items.map {
            CompressionBatchStore.Item(
                uri = it.uri,
                displayName = it.displayName,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                status = if (it.note == "Already an efficient format") Status.FAILED else Status.PENDING,
                note = if (it.note == "Already an efficient format") it.note else ""
            )
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                CompressionEngine.loadJournal(this@CompressionActivity)
                    .filter { it.id in entryIds }
                    .forEach { runCatching { CompressionEngine.discard(this@CompressionActivity, it) } }
            }
            val id = CompressionBatchStore.newBatchId()
            store.save(
                CompressionBatchStore.Batch(
                    id = id,
                    phase = Phase.PREPARING,
                    quality = newPreset.quality,
                    maxDimension = newPreset.maxDimension,
                    items = reused.toMutableList()
                )
            )
            batchId = id
            enqueuePrepare(ExistingWorkPolicy.REPLACE)
            refresh()
        }
    }

    private fun updateQualityChips() {
        val accent = DesignTokens.accent(this)
        val card = getColor(R.color.metroBgCard)
        val chips = mapOf(
            CompressionEngine.Presets.HIGH to binding.qualityHigh,
            CompressionEngine.Presets.BALANCED to binding.qualityBalanced,
            CompressionEngine.Presets.SMALL to binding.qualitySmall
        )
        for ((value, chip) in chips) {
            if (value == preset) {
                chip.setBackgroundColor(accent)
                chip.setTextColor(getColor(R.color.metroTextPrimary))
            } else {
                chip.setBackgroundColor(card)
                chip.setTextColor(getColor(R.color.metroTextStrong))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Summary + progress
    // ---------------------------------------------------------------------------------------------

    private fun updateSummary(batch: CompressionBatchStore.Batch) {
        val ready = batch.items.filter { it.status == Status.READY }
        val pending = batch.items.count { it.status == Status.PENDING || it.status == Status.PREPARING }
        val committing = batch.phase == Phase.COMMITTING
        val done = batch.items.count { it.status == Status.DONE }
        when {
            committing || done > 0 -> {
                val verb = if (batch.commitMode == CompressionBatchStore.CommitMode.REPLACE) "Replacing" else "Saving copies"
                binding.summaryText.text = if (batch.savedBytes > 0L) {
                    "${formatBytes(batch.savedBytes)} saved so far"
                } else {
                    "$verb…"
                }
                binding.summaryDetail.text = "$done of ${batch.items.size} done"
            }
            ready.isEmpty() && pending > 0 -> {
                binding.summaryText.text = "Still analyzing photos… ($pending left)"
                binding.summaryDetail.text = "Exact sizes appear as each photo is compressed."
            }
            ready.isEmpty() -> {
                binding.summaryText.text = "Nothing to gain"
                binding.summaryDetail.text = "These photos are already stored efficiently."
            }
            else -> {
                val before = ready.sumOf { it.sizeBefore }
                val after = ready.sumOf { it.sizeAfter }
                val formats = ready.map { it.format }.filter { it.isNotBlank() }.toSet()
                val formatLabel = if (formats.size == 1) "as ${formats.first()}" else "as HEIC/WebP"
                binding.summaryText.text = "Save ≈ ${formatBytes(before - after)}"
                binding.summaryDetail.text = buildString {
                    append("${formatBytes(before)} → ${formatBytes(after)} · $formatLabel")
                    if (pending > 0) append(" · still analyzing $pending")
                    val skipped = batch.items.count { it.status == Status.NO_GAIN }
                    if (skipped > 0) append(" · $skipped already optimal")
                }
            }
        }
        // Actions unlock only when the encode pass has settled every row and nothing is committing.
        val actionable = !committing && !commitRunning && pending == 0 &&
            ready.isNotEmpty() && batch.phase != Phase.DONE
        binding.replaceBar.alpha = if (actionable) 1f else 0.4f
        binding.replaceBar.isClickable = actionable
        binding.keepBothBar.alpha = if (actionable) 1f else 0.4f
        binding.keepBothBar.isClickable = actionable
    }

    private fun updateProgressRow(batch: CompressionBatchStore.Batch) {
        val committing = batch.phase == Phase.COMMITTING || commitRunning
        val preparing = batch.phase == Phase.PREPARING || prepareRunning
        binding.batchProgressRow.visibility = if (preparing || committing) View.VISIBLE else View.GONE
        if (!(preparing || committing)) return
        val replacing = batch.commitMode == CompressionBatchStore.CommitMode.REPLACE
        // Prefer the worker's own reported totals so the bar matches what it is actually processing;
        // fall back to persisted per-item counts before the first progress tick arrives.
        val workerTotal = if (committing) commitProgress.second else prepareProgress.second
        val settled = batch.items.count {
            it.status == Status.READY || it.status == Status.NO_GAIN || it.status == Status.FAILED || it.status == Status.DONE
        }
        val total = (if (workerTotal > 0) workerTotal else settled).coerceAtLeast(1)
        val done = if (workerTotal > 0) {
            (if (committing) commitProgress.first else prepareProgress.first).coerceIn(0, total)
        } else {
            settled.coerceIn(0, total)
        }
        binding.batchProgressBar.max = total
        binding.batchProgressBar.progress = done
        binding.batchProgressText.text = when {
            committing -> "${if (replacing) "Replacing" else "Saving copy"} $done / $total"
            else -> "Compressing $done / $total"
        }
        // Cancel is only meaningful before a destructive commit is confirmed — recovery, not the
        // user, settles an interrupted replace.
        binding.batchCancelBtn.visibility = if (committing) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------------------------------------
    // Compare
    // ---------------------------------------------------------------------------------------------

    private fun showCompare(row: Row) {
        val entry = journalById[row.entryId] ?: return
        compareEntryId = row.entryId
        compareResultLauncher.launch(
            Intent(this, CompareFullscreenActivity::class.java)
                .putExtra(CompareFullscreenActivity.ExtraOriginalUri, row.uri.toString())
                .putExtra(CompareFullscreenActivity.ExtraStagingPath, entry.stagingPath)
                .putExtra(CompareFullscreenActivity.ExtraSizeBefore, entry.sizeBefore)
                .putExtra(CompareFullscreenActivity.ExtraSizeAfter, entry.sizeAfter)
                .putExtra(CompareFullscreenActivity.ExtraFormat, entry.format.name)
        )
    }

    private fun keepOriginal(entryId: String) {
        val id = batchId ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                journalById[entryId]?.let { runCatching { CompressionEngine.discard(this@CompressionActivity, it) } }
            }
            store.update(id) { b ->
                b.items.firstOrNull { it.entryId == entryId }?.let {
                    it.status = Status.NO_GAIN
                    it.note = "You chose to keep the original"
                    it.entryId = ""
                }
            }
            refresh()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Commit
    // ---------------------------------------------------------------------------------------------

    private fun onReplaceClicked() {
        if (!StoragePermissions.hasAllFilesAccess(this)) {
            runCatching { allFilesLauncher.launch(StoragePermissions.manageAllFilesIntent(this)) }
                .onFailure { MetroBanner.show(this, "Couldn't open storage access settings") }
            return
        }
        confirmBatch(CompressionBatchStore.CommitMode.REPLACE)
    }

    private fun confirmBatch(mode: CompressionBatchStore.CommitMode) {
        val current = batch ?: return
        if (current.phase == Phase.COMMITTING || commitRunning) return
        if (current.items.any { it.status == Status.PENDING || it.status == Status.PREPARING }) {
            MetroBanner.show(this, "Still analyzing — available in a moment")
            return
        }
        val count = current.items.count { it.status == Status.READY }
        if (count == 0) return
        val replace = mode == CompressionBatchStore.CommitMode.REPLACE
        val noun = if (count == 1) "1 photo" else "$count photos"
        MetroDialog.confirm(
            context = this,
            title = if (replace) "Replace originals?" else "Save compressed copies?",
            message = if (replace) {
                "The compressed versions of $noun replace the original files (already prepared, " +
                    "so this is quick). Originals are backed up until each replacement verifies; " +
                    "if anything goes wrong mid-way, they're restored automatically. This keeps " +
                    "running in the background if you leave."
            } else {
                "$noun will be saved as smaller copies next to the originals; nothing is deleted. " +
                    "This keeps running in the background if you leave."
            },
            positive = if (replace) "Replace originals" else "Save copies",
            iconRes = R.drawable.ic_fluent_image_24_regular
        ) { startCommit(mode) }
    }

    private fun startCommit(mode: CompressionBatchStore.CommitMode) {
        val id = batchId ?: return
        val ok = store.update(id) { b ->
            b.commitMode = mode
            b.phase = Phase.COMMITTING
            // Retry any row that failed a previous commit attempt but still has a staged entry.
            b.items.forEach {
                if (it.status == Status.FAILED && it.entryId.isNotBlank()) it.status = Status.READY
            }
        }
        if (!ok) return
        commitProgress = 0 to 0
        enqueueCommit()
        refresh()
    }

    // ---------------------------------------------------------------------------------------------
    // Back / finish
    // ---------------------------------------------------------------------------------------------

    private fun onBack() {
        val current = batch
        if (current != null && current.phase == Phase.DONE) {
            finishWithResult(current)
            return
        }
        if (current != null && current.isActive) {
            // Encoding and committing both run in WorkManager, so leaving the screen no longer
            // cancels anything — the batch keeps going and the notification brings the user
            // straight back to it. Only the explicit cancel discards work.
            MetroBanner.show(this, "Still running in the background — tap the notification to return")
        }
        setResult(RESULT_CANCELED)
        finished = true
        finish()
    }

    /** Explicit discard for a pre-decision batch: staged previews go, originals stay untouched. */
    private fun confirmCancelCompression() {
        val current = batch ?: return
        if (current.phase == Phase.COMMITTING || commitRunning) {
            MetroBanner.show(this, "Can't cancel while replacing — let it finish")
            return
        }
        MetroDialog.confirm(
            context = this,
            title = "Cancel compression?",
            message = "The compressed previews are discarded and the batch stops. " +
                "Your original photos are never touched.",
            positive = "Cancel compression",
            negative = "Keep going",
            danger = true,
            iconRes = R.drawable.ic_fluent_delete_24_regular
        ) {
            abandon(current)
            finished = true
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    /** A new selection arrived while a batch was already running: ask before discarding it. */
    private fun promptReplaceActiveBatchIfNeeded() {
        val pending = pendingSelectionPrompt ?: return
        pendingSelectionPrompt = null
        if (isFinishing || isDestroyed) return
        MetroDialog.confirm(
            context = this,
            title = "Compression already running",
            message = "A batch of ${pending.activeCount} photos is still running in the background. " +
                "Discard it and start compressing the ${pending.selected.size} newly selected photos instead?",
            positive = "Discard and start new",
            negative = "Keep current",
            danger = true,
            iconRes = R.drawable.ic_fluent_image_24_regular
        ) {
            batch?.let { abandon(it) }
            startFresh(pending.items, pending.selected)
            refresh()
        }
    }

    private fun finishWithResult(batch: CompressionBatchStore.Batch) {
        if (finished) return
        finished = true
        val replaced = ArrayList(batch.replacedUris)
        val copied = ArrayList(batch.copiedUris)
        if (replaced.isNotEmpty() || copied.isNotEmpty()) {
            setResult(
                RESULT_OK,
                Intent()
                    .putStringArrayListExtra(ExtraReplacedUris, replaced)
                    .putStringArrayListExtra(ExtraCompressedUris, copied)
            )
        }
        finish()
    }

    // ---------------------------------------------------------------------------------------------
    // List adapter
    // ---------------------------------------------------------------------------------------------

    private inner class RowAdapter : RecyclerView.Adapter<RowVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH =
            RowVH(layoutInflater.inflate(R.layout.item_compression_row, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RowVH, position: Int) {
            val row = rows[position]
            holder.name.text = row.displayName.ifBlank { "Photo" }
            Glide.with(holder.thumbnail).load(row.uri).centerCrop().into(holder.thumbnail)

            val before = row.sizeBefore.takeIf { it > 0L } ?: row.sizeBytes
            holder.sizes.text = when (row.status) {
                Status.READY ->
                    "${formatBytes(row.sizeBefore)} → ${formatBytes(row.sizeAfter)} · ${row.format}"
                Status.DONE ->
                    "${formatBytes(row.sizeBefore)} → ${formatBytes(row.sizeAfter)}"
                else -> if (before > 0L) formatBytes(before) else ""
            }
            holder.status.text = when (row.status) {
                Status.PENDING -> "Waiting…"
                Status.PREPARING -> "Compressing…"
                Status.READY -> "Tap to compare · saves ${formatBytes(row.sizeBefore - row.sizeAfter)}"
                Status.NO_GAIN -> row.note.ifBlank { "Already optimal" }
                Status.FAILED -> row.note.ifBlank { "Couldn't compress this photo" }
                Status.DONE ->
                    if (batch?.commitMode == CompressionBatchStore.CommitMode.REPLACE) "Original replaced"
                    else "Copy saved"
            }
            holder.status.setTextColor(
                when (row.status) {
                    Status.READY -> DesignTokens.accent(this@CompressionActivity)
                    Status.FAILED -> getColor(R.color.metroDanger)
                    Status.DONE -> getColor(R.color.metroTextStrong)
                    else -> getColor(R.color.metroTextSecondary)
                }
            )
            holder.spinner.visibility =
                if (row.status == Status.PREPARING) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                if (row.status == Status.READY && row.entryId.isNotBlank()) showCompare(row)
            }
        }
    }

    private class RowVH(root: View) : RecyclerView.ViewHolder(root) {
        val thumbnail: ImageView = root.findViewById(R.id.rowThumbnail)
        val name: TextView = root.findViewById(R.id.rowName)
        val sizes: TextView = root.findViewById(R.id.rowSizes)
        val status: TextView = root.findViewById(R.id.rowStatus)
        val spinner: ProgressBar = root.findViewById(R.id.rowSpinner)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.compressionRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            binding.replaceBar.updatePadding(bottom = bars.bottom + dp(18))
            insets
        }
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
        const val ExtraReplacedUris = "replaced_uris"
        const val ExtraCompressedUris = "compressed_uris"
    }
}
