package com.devomind.gallerysearch

import android.content.Intent
import android.graphics.Color
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
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ActivityCompressionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Review screen for HEIC compression (Smart Cleanup → Compression tile).
 *
 * Every selected photo is encoded to a verified, journaled staging file in the background so the
 * list shows EXACT before/after sizes; tapping a row opens a side-by-side comparison. The user then
 * either replaces the originals or saves compressed copies — both flows go through
 * [CompressionEngine]'s crash-safe pipeline, so leaving or killing the app mid-batch can never
 * lose a photo (interrupted work is settled by [CompressionEngine.recover] on next start).
 */
class CompressionActivity : AppCompatActivity() {

    private enum class RowStatus { PENDING, PREPARING, READY, NO_GAIN, FAILED, DONE }

    private data class Row(
        val item: GalleryRepository.MediaItem,
        var entry: CompressionEngine.Entry? = null,
        var status: RowStatus = RowStatus.PENDING,
        var note: String = ""
    )

    private lateinit var binding: ActivityCompressionBinding
    private val rows = mutableListOf<Row>()
    private var preset: CompressionEngine.Preset = CompressionEngine.Presets.BALANCED
    private var prepareJob: Job? = null
    private var batchRunning = false
    private var compareRowIndex = -1

    private val replacedUris = mutableListOf<String>()
    private val compressedUris = mutableListOf<String>()
    private var totalSavedBytes = 0L

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (StoragePermissions.hasAllFilesAccess(this)) {
            confirmBatch(replace = true)
        } else {
            MetroBanner.show(this, "All-files access is required to replace originals")
        }
    }

    /** Full-screen compare result: "Keep original" discards the staged entry for that row. */
    private val compareResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val index = compareRowIndex
        compareRowIndex = -1
        if (index !in rows.indices) return@registerForActivityResult
        val row = rows[index]
        when (result.resultCode) {
            CompareFullscreenActivity.ResultKeepOriginal -> {
                row.entry?.let { entry ->
                    thread(isDaemon = true) {
                        runCatching { CompressionEngine.discard(this@CompressionActivity, entry) }
                    }
                }
                row.entry = null
                row.status = RowStatus.NO_GAIN
                row.note = "You chose to keep the original"
                notifyRow(index)
                updateSummary()
            }
            CompareFullscreenActivity.ResultKeepCompressed -> Unit // stays READY, staged as-is
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

        val items = CompressionHandoff.items
        val selected = CompressionHandoff.selectedUris.toSet()
        CompressionHandoff.release()

        val byUri = items.associateBy { it.uri }
        for (uri in selected) {
            val item = byUri[uri] ?: continue
            if (CompressionEngine.isCompressibleMime(item.mimeType)) {
                rows.add(Row(item))
            } else {
                rows.add(Row(item, status = RowStatus.FAILED, note = "Already an efficient format"))
            }
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, "No photos to compress.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.titleText.text = "Compress ${rows.size} ${if (rows.size == 1) "photo" else "photos"}"
        binding.compressionList.layoutManager = LinearLayoutManager(this)
        binding.compressionList.adapter = RowAdapter()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.qualityHigh.setOnClickListener { setPreset(CompressionEngine.Presets.HIGH) }
        binding.qualityBalanced.setOnClickListener { setPreset(CompressionEngine.Presets.BALANCED) }
        binding.qualitySmall.setOnClickListener { setPreset(CompressionEngine.Presets.SMALL) }
        binding.replaceBar.setOnClickListener { onReplaceClicked() }
        binding.keepBothBar.setOnClickListener { confirmBatch(replace = false) }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (batchRunning) {
                    MetroBanner.show(this@CompressionActivity, "Finishing the current photo…")
                } else {
                    finishWithResult()
                }
            }
        })

        updateQualityChips()
        updateSummary()
        startPrepare()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.compressionRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            binding.replaceBar.updatePadding(bottom = bars.bottom + dp(18))
            insets
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Background preparation (exact sizes + compare previews)
    // ---------------------------------------------------------------------------------------------

    private fun startPrepare() {
        prepareJob?.cancel()
        prepareJob = lifecycleScope.launch {
            for (index in rows.indices) {
                val row = rows[index]
                if (row.status != RowStatus.PENDING) continue
                row.status = RowStatus.PREPARING
                notifyRow(index)
                val entry = withContext(Dispatchers.IO) {
                    CompressionEngine.prepare(
                        this@CompressionActivity, row.item.uri, row.item.displayName,
                        preset.quality, CompressionEngine.Mode.REPLACE, preset.maxDimension
                    )
                }
                when {
                    entry == null -> {
                        row.status = RowStatus.FAILED
                        row.note = "Couldn't compress this photo"
                    }
                    entry.sizeBefore > 0L && entry.sizeAfter >= entry.sizeBefore -> {
                        withContext(Dispatchers.IO) { CompressionEngine.discard(this@CompressionActivity, entry) }
                        row.status = RowStatus.NO_GAIN
                        row.note = "Already optimal — skipped"
                    }
                    else -> {
                        row.entry = entry
                        row.status = RowStatus.READY
                    }
                }
                notifyRow(index)
                updateSummary()
            }
        }
    }

    private fun setPreset(newPreset: CompressionEngine.Preset) {
        if (newPreset == preset || batchRunning) return
        preset = newPreset
        updateQualityChips()
        prepareJob?.cancel()
        // Prepared files used the old preset: discard them and re-encode.
        val toDiscard = rows.filter { it.entry != null && it.status == RowStatus.READY }
        thread(isDaemon = true) {
            toDiscard.forEach { runCatching { CompressionEngine.discard(this@CompressionActivity, it.entry!!) } }
        }
        for (row in rows) {
            if (row.status != RowStatus.FAILED && row.status != RowStatus.DONE) {
                row.entry = null
                row.status = RowStatus.PENDING
                row.note = ""
            }
        }
        binding.compressionList.adapter?.notifyDataSetChanged()
        updateSummary()
        startPrepare()
    }

    private fun updateQualityChips() {
        val accent = DesignTokens.accent(this)
        val card = getColor(R.color.metroBgCard)
        val selected = mapOf(
            CompressionEngine.Presets.HIGH to binding.qualityHigh,
            CompressionEngine.Presets.BALANCED to binding.qualityBalanced,
            CompressionEngine.Presets.SMALL to binding.qualitySmall
        )
        for ((value, chip) in selected) {
            if (value == preset) {
                chip.setBackgroundColor(accent)
                chip.setTextColor(getColor(R.color.metroTextPrimary))
            } else {
                chip.setBackgroundColor(card)
                chip.setTextColor(getColor(R.color.metroTextStrong))
            }
        }
    }

    private fun updateSummary() {
        val ready = rows.filter { it.status == RowStatus.READY }
        val pending = rows.count { it.status == RowStatus.PENDING || it.status == RowStatus.PREPARING }
        if (ready.isEmpty() && pending > 0) {
            binding.summaryText.text = "Still analyzing photos… ($pending left)"
            binding.summaryDetail.text = "Exact sizes appear as each photo is compressed."
        } else if (ready.isEmpty()) {
            binding.summaryText.text = "Nothing to gain"
            binding.summaryDetail.text = "These photos are already stored efficiently."
        } else {
            val before = ready.sumOf { it.entry!!.sizeBefore }
            val after = ready.sumOf { it.entry!!.sizeAfter }
            val formats = ready.map { it.entry!!.format }.toSet()
            val formatLabel = when {
                formats.size == 1 -> "as ${formats.first().name}"
                else -> "as HEIC/WebP"
            }
            binding.summaryText.text = "Save ≈ ${formatBytes(before - after)}"
            binding.summaryDetail.text = buildString {
                append("${formatBytes(before)} → ${formatBytes(after)} · $formatLabel")
                if (pending > 0) append(" · still analyzing $pending")
                val skipped = rows.count { it.status == RowStatus.NO_GAIN }
                if (skipped > 0) append(" · $skipped already optimal")
            }
        }
        // Batch actions stay unavailable until every row is settled: the batch then only commits
        // already-staged files (fast copies), never re-encodes, so "Replacing X / N" is honest.
        val actionable = !batchRunning && pending == 0 && rows.any { it.status == RowStatus.READY }
        binding.replaceBar.alpha = if (actionable) 1f else 0.4f
        binding.replaceBar.isClickable = actionable
        binding.keepBothBar.alpha = if (actionable) 1f else 0.4f
        binding.keepBothBar.isClickable = actionable
    }

    // ---------------------------------------------------------------------------------------------
    // Full-screen compare (CompareFullscreenActivity)
    // ---------------------------------------------------------------------------------------------

    private fun showCompare(row: Row) {
        val entry = row.entry ?: return
        compareRowIndex = rows.indexOf(row)
        compareResultLauncher.launch(
            Intent(this, CompareFullscreenActivity::class.java)
                .putExtra(CompareFullscreenActivity.ExtraOriginalUri, row.item.uri.toString())
                .putExtra(CompareFullscreenActivity.ExtraStagingPath, entry.stagingPath)
                .putExtra(CompareFullscreenActivity.ExtraSizeBefore, entry.sizeBefore)
                .putExtra(CompareFullscreenActivity.ExtraSizeAfter, entry.sizeAfter)
                .putExtra(CompareFullscreenActivity.ExtraFormat, entry.format.name)
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Batch execution
    // ---------------------------------------------------------------------------------------------

    private fun onReplaceClicked() {
        if (!StoragePermissions.hasAllFilesAccess(this)) {
            runCatching { allFilesLauncher.launch(StoragePermissions.manageAllFilesIntent(this)) }
                .onFailure { MetroBanner.show(this, "Couldn't open storage access settings") }
            return
        }
        confirmBatch(replace = true)
    }

    private fun confirmBatch(replace: Boolean) {
        if (batchRunning) return
        // Preparation must be finished: the batch only commits staged files, it never encodes.
        if (rows.any { it.status == RowStatus.PENDING || it.status == RowStatus.PREPARING }) {
            MetroBanner.show(this, "Still analyzing — available in a moment")
            return
        }
        val count = rows.count { it.status == RowStatus.READY }
        if (count == 0) return
        val noun = if (count == 1) "1 photo" else "$count photos"
        MetroDialog.confirm(
            context = this,
            title = if (replace) "Replace originals?" else "Save compressed copies?",
            message = if (replace) {
                "The compressed versions of $noun replace the original files (already prepared, " +
                    "so this is quick). Originals are backed up until each replacement verifies; " +
                    "if anything goes wrong mid-way, they're restored automatically."
            } else {
                "$noun will be saved as smaller copies next to the originals; nothing is deleted."
            },
            positive = if (replace) "Replace originals" else "Save copies",
            iconRes = R.drawable.ic_fluent_image_24_regular
        ) { runBatch(replace) }
    }

    /**
     * Commit-only batch: every target is already staged and verified by [startPrepare], so this
     * loop runs fast file operations (verified copy + delete) — no re-encoding pass. Rows that
     * failed or gained nothing during preparation keep their verdict and are skipped.
     */
    private fun runBatch(replace: Boolean) {
        batchRunning = true
        prepareJob?.cancel()
        updateSummary()
        binding.batchProgressRow.visibility = View.VISIBLE

        lifecycleScope.launch {
            val targets = rows.filter { it.status == RowStatus.READY && it.entry != null }
            val total = targets.size.coerceAtLeast(1)
            binding.batchProgressBar.max = total
            binding.batchProgressText.text = "${if (replace) "Replacing" else "Saving copy"} 0 / $total"
            var processed = 0
            var failed = 0

            for (row in targets) {
                val index = rows.indexOf(row)
                val entry = row.entry!!
                binding.batchProgressText.text =
                    "${if (replace) "Replacing" else "Saving copy"} ${processed + 1} / $total"

                val ok = withContext(Dispatchers.IO) {
                    try {
                        if (replace) {
                            CompressionEngine.commitReplace(this@CompressionActivity, entry)
                        } else {
                            CompressionEngine.commitCopy(this@CompressionActivity, entry)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        false
                    }
                }

                processed++
                binding.batchProgressBar.progress = processed
                if (ok) {
                    row.entry = null
                    row.status = RowStatus.DONE
                    row.note = "${formatBytes(entry.sizeBefore)} → ${formatBytes(entry.sizeAfter)}"
                    compressedUris.add(row.item.uri.toString())
                    if (replace) replacedUris.add(row.item.uri.toString())
                    totalSavedBytes += (entry.sizeBefore - entry.sizeAfter)
                } else {
                    row.entry = null
                    row.status = RowStatus.FAILED
                    row.note = "Couldn't ${if (replace) "replace" else "save"} this photo"
                    failed++
                }
                notifyRow(index)
            }

            batchRunning = false
            binding.batchProgressRow.visibility = View.GONE
            val verb = if (replace) "replaced" else "saved as copies"
            val message = buildString {
                append("${processed - failed} ${if (processed - failed == 1) "photo" else "photos"} $verb")
                if (totalSavedBytes > 0L) append(" · ${formatBytes(totalSavedBytes)} saved")
                if (failed > 0) append(" · $failed failed")
            }
            MetroBanner.show(this@CompressionActivity, message)
            updateSummary()

            val remaining = rows.any { it.status == RowStatus.READY }
            if (!remaining) finishWithResult()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Finish — never abandon a staged file without settling it
    // ---------------------------------------------------------------------------------------------

    private fun finishWithResult() {
        prepareJob?.cancel()
        val uncommitted = rows.mapNotNull { row ->
            row.entry?.takeIf { row.status == RowStatus.READY }
        }
        if (uncommitted.isNotEmpty()) {
            thread(isDaemon = true) {
                uncommitted.forEach { runCatching { CompressionEngine.discard(this@CompressionActivity, it) } }
            }
        }
        if (replacedUris.isNotEmpty() || compressedUris.isNotEmpty()) {
            setResult(
                RESULT_OK,
                Intent()
                    .putStringArrayListExtra(ExtraReplacedUris, ArrayList(replacedUris))
                    .putStringArrayListExtra(ExtraCompressedUris, ArrayList(compressedUris))
            )
        }
        finish()
    }

    // ---------------------------------------------------------------------------------------------
    // List adapter
    // ---------------------------------------------------------------------------------------------

    private fun notifyRow(index: Int) {
        if (index >= 0) binding.compressionList.adapter?.notifyItemChanged(index)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH =
            RowVH(layoutInflater.inflate(R.layout.item_compression_row, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RowVH, position: Int) {
            val row = rows[position]
            holder.name.text = row.item.displayName ?: "Photo"
            Glide.with(holder.thumbnail).load(row.item.uri).centerCrop().into(holder.thumbnail)

            val before = row.entry?.sizeBefore ?: row.item.sizeBytes
            holder.sizes.text = when (row.status) {
                RowStatus.READY -> {
                    val entry = row.entry!!
                    "${formatBytes(entry.sizeBefore)} → ${formatBytes(entry.sizeAfter)} · ${entry.format.name}"
                }
                RowStatus.DONE -> row.note
                else -> if (before > 0L) formatBytes(before) else ""
            }
            holder.status.text = when (row.status) {
                RowStatus.PENDING -> "Waiting…"
                RowStatus.PREPARING -> "Compressing…"
                RowStatus.READY -> "Tap to compare · saves ${
                    formatBytes(row.entry!!.sizeBefore - row.entry!!.sizeAfter)
                }"
                RowStatus.NO_GAIN -> row.note
                RowStatus.FAILED -> row.note
                RowStatus.DONE -> if (row.item.uri.toString() in replacedUris) "Original replaced" else "Copy saved"
            }
            holder.status.setTextColor(
                when (row.status) {
                    RowStatus.READY -> DesignTokens.accent(this@CompressionActivity)
                    RowStatus.FAILED -> getColor(R.color.metroDanger)
                    RowStatus.DONE -> getColor(R.color.metroTextStrong)
                    else -> getColor(R.color.metroTextSecondary)
                }
            )
            holder.spinner.visibility =
                if (row.status == RowStatus.PREPARING) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                if (row.status == RowStatus.READY && row.entry != null) showCompare(row)
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
