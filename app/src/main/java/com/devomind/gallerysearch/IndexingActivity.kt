package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.devomind.gallerysearch.databinding.ActivityIndexingBinding
import com.devomind.gallerysearch.databinding.ItemIndexFolderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated, self-contained home for everything indexing-related:
 *
 *  - **Status** — live WorkManager progress (percent + "N of M photos"), with Pause/Resume/Start/
 *    Re-index + Stop controls.
 *  - **Power** — "index only while charging" and its sub-option "night charging only" (10 PM–7 AM).
 *  - **Folders** — which device folders the AI index covers (checkboxes + a select-all master toggle).
 *    Independent of the gallery view; every photo still shows. Empty selection means "all folders".
 *  - **Media Processing** — a plain-language explainer of what the on-device scan does.
 *
 * Folder-scope and power changes are persisted on [finish] / applied immediately and trigger
 * [IndexController.rescan] (REPLACE) so the index is rebuilt against the new scope/constraints.
 */
class IndexingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIndexingBinding

    // Folder selection state
    private val selected = linkedSetOf<String>()
    private var folders: List<GalleryRepository.Album> = emptyList()
    private var originalScope: Set<String> = emptySet()

    // Power snapshot so we can re-enqueue when a constraint changes mid-run
    private var originalChargingOnly = false
    private var originalNightCharging = false

    /** Tracks the latest WorkManager state so button taps render immediately. */
    private var latestIndexState: WorkInfo.State? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityIndexingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        bindStatus()
        bindPower()
        loadFolders()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.indexingRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            insets
        }
    }

    // ------------------------------------------------------------------
    // Status + controls
    // ------------------------------------------------------------------

    private fun bindStatus() {
        binding.btnIndexPrimary.setOnClickListener {
            when {
                isRunningState(latestIndexState) -> IndexController.pause(this)
                IndexPreferences.isIndexPaused(this) -> IndexController.resume(this)
                else -> IndexController.start(this)
            }
            // Optimistic refresh; the observer will correct it as WorkManager transitions.
            renderIndexing(latestIndexState, livePercent = null, current = -1, total = -1)
        }
        binding.btnIndexStop.setOnClickListener {
            IndexController.stop(this)
            renderIndexing(WorkInfo.State.CANCELLED, livePercent = null, current = -1, total = -1)
        }

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(IndexWorker.WorkName)
            .observe(this) { infos ->
                val work = infos.firstOrNull()
                latestIndexState = work?.state
                val livePercent = if (work?.state == WorkInfo.State.RUNNING) {
                    work.progress.getInt(IndexWorker.ProgressPercentKey, -1).takeIf { it >= 0 }
                } else {
                    null
                }
                val current = if (work?.state == WorkInfo.State.RUNNING) {
                    work.progress.getInt(IndexWorker.ProgressCurrentKey, -1).takeIf { it >= 0 } ?: -1
                } else -1
                val total = if (work?.state == WorkInfo.State.RUNNING) {
                    work.progress.getInt(IndexWorker.ProgressTotalKey, -1).takeIf { it >= 0 } ?: -1
                } else -1
                renderIndexing(work?.state, livePercent, current, total)
            }

        // Phase 2 face-index pipeline status: shows running face-processing stats (faces seen,
        // persons created, gate activity) alongside the CLIP index progress.
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(FaceIndexWorker.WorkName)
            .observe(this) { infos ->
                val work = infos.firstOrNull() ?: return@observe
                if (work.state != WorkInfo.State.RUNNING) return@observe
                val faces = work.progress.getInt(FaceIndexWorker.StatsFacesKey, 0)
                val persons = work.progress.getInt(FaceIndexWorker.StatsPersonsKey, 0)
                val visited = work.progress.getInt(FaceIndexWorker.ProgressVisitedKey, 0)
                val total = work.progress.getInt(FaceIndexWorker.ProgressTotalKey, -1)
                if (total > 0) {
                    binding.indexSubStatus.text =
                        "Indexing your photos… + face pass ($visited / $total · $faces faces · $persons persons)"
                }
            }
    }

    private fun isRunningState(state: WorkInfo.State?): Boolean =
        state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED

    private fun renderIndexing(state: WorkInfo.State?, livePercent: Int?, current: Int, total: Int) {
        val paused = IndexPreferences.isIndexPaused(this)
        val stopped = IndexPreferences.isIndexStopped(this)
        val percent = livePercent ?: IndexPreferences.getIndexProgressPercent(this)
        val chargingOnly = IndexPreferences.isChargingOnlyIndexing(this)
        val nightOnly = IndexPreferences.isNightChargingOnly(this)

        binding.indexProgress.progress = percent

        // "N of M photos" count line — only while actively running with real totals.
        if (state == WorkInfo.State.RUNNING && current >= 0 && total > 0) {
            binding.indexCount.text = "$current of $total photos"
            binding.indexCount.visibility = View.VISIBLE
        } else {
            binding.indexCount.visibility = View.GONE
        }

        when {
            state == WorkInfo.State.RUNNING -> {
                binding.indexProgress.isIndeterminate = false
                binding.indexProgress.progress = percent
                binding.indexStatus.text = "Indexing your photos… $percent%"
                binding.indexSubStatus.text = "On-device AI · nothing leaves your phone"
                setPrimary("Pause")
                setStopVisible(true)
            }
            state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED -> {
                binding.indexProgress.isIndeterminate = true
                binding.indexStatus.text = when {
                    nightOnly && chargingOnly -> "Waiting for night (10 PM – 7 AM) while charging"
                    chargingOnly -> "Waiting to charge"
                    else -> "Queued…"
                }
                binding.indexSubStatus.text = "On-device AI · nothing leaves your phone"
                setPrimary("Pause")
                setStopVisible(true)
            }
            paused -> {
                binding.indexProgress.isIndeterminate = false
                binding.indexStatus.text = "Indexing paused · $percent%"
                binding.indexSubStatus.text = "Resume to finish building your AI search index"
                setPrimary("Resume")
                setStopVisible(true)
            }
            stopped && percent < 100 -> {
                binding.indexProgress.isIndeterminate = false
                binding.indexStatus.text = "Indexing stopped · $percent%"
                binding.indexSubStatus.text = "Start again to finish building your AI search index"
                setPrimary("Start")
                setStopVisible(false)
            }
            percent >= 100 || state == WorkInfo.State.SUCCEEDED -> {
                binding.indexProgress.isIndeterminate = false
                binding.indexProgress.progress = 100
                binding.indexStatus.text = "Your photos are indexed"
                binding.indexSubStatus.text = "New photos are indexed automatically"
                setPrimary("Re-index")
                setStopVisible(false)
            }
            else -> {
                binding.indexProgress.isIndeterminate = false
                binding.indexStatus.text = "Indexing not started"
                binding.indexSubStatus.text = "Build a private, on-device index to search by description"
                setPrimary("Start")
                setStopVisible(false)
            }
        }
    }

    private fun setPrimary(label: String) {
        binding.btnIndexPrimary.text = label
    }

    private fun setStopVisible(visible: Boolean) {
        binding.btnIndexStop.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // Power: charging + night-charging
    // ------------------------------------------------------------------

    private fun bindPower() {
        originalChargingOnly = IndexPreferences.isChargingOnlyIndexing(this)
        originalNightCharging = IndexPreferences.isNightChargingOnly(this)

        binding.switchCharging.isChecked = originalChargingOnly
        binding.rowCharging.setOnClickListener {
            val newValue = !binding.switchCharging.isChecked
            binding.switchCharging.isChecked = newValue
            IndexPreferences.setChargingOnlyIndexing(this, newValue)
            // Night-only is a sub-option of charging; if charging turns off, night is moot.
            updateNightChargingEnabled()
            applyPowerChangeNow()
        }

        binding.switchNightCharging.isChecked = originalNightCharging
        binding.rowNightCharging.setOnClickListener {
            if (!binding.switchCharging.isChecked) return@setOnClickListener  // gated on charging
            val newValue = !binding.switchNightCharging.isChecked
            binding.switchNightCharging.isChecked = newValue
            IndexPreferences.setNightChargingOnly(this, newValue)
            applyPowerChangeNow()
        }
        updateNightChargingEnabled()
    }

    /** Night-charging row is only interactive (and full-opacity) while charging-only is on. */
    private fun updateNightChargingEnabled() {
        val chargingOn = binding.switchCharging.isChecked
        binding.rowNightCharging.isEnabled = chargingOn
        binding.rowNightCharging.alpha = if (chargingOn) 1f else 0.45f
        if (!chargingOn && binding.switchNightCharging.isChecked) {
            binding.switchNightCharging.isChecked = false
            IndexPreferences.setNightChargingOnly(this, false)
        }
    }

    /**
     * Re-apply the power constraint so the next IndexWorker run picks it up; leaves any in-flight
     * run alone (REPLACE would cancel mid-photo, which is what churns a long index pass).
     */
    private fun applyPowerChangeNow() {
        IndexController.applyPowerConstraintOnly(this)
        // Reflect the new constraint in the status line right away.
        renderIndexing(latestIndexState, livePercent = null, current = -1, total = -1)
    }

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    private fun loadFolders() {
        binding.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                GalleryRepository(applicationContext).loadSnapshot(emptySet()).albums
            }
            folders = loaded
            originalScope = IndexScopeStore.getFolderIds(this@IndexingActivity)
            selected.clear()
            // Default selection when nothing was scoped yet: everything checked.
            if (originalScope.isEmpty()) selected.addAll(folders.map { it.id })
            else selected.addAll(originalScope)

            binding.loading.visibility = View.GONE
            buildRows()
            renderFolderState()
        }

        // Master "select all" checkbox: toggles every folder at once.
        binding.rowSelectAll.setOnClickListener {
            if (selected.size == folders.size) {
                selected.clear()
            } else {
                selected.clear()
                selected.addAll(folders.map { it.id })
            }
            renderFolderState()
        }
    }

    private fun buildRows() {
        binding.foldersContainer.removeAllViews()
        val inflater = layoutInflater
        for (folder in folders) {
            val row = ItemIndexFolderBinding.inflate(inflater, binding.foldersContainer, false)
            row.folderName.text = folder.name
            row.folderCount.text = if (folder.count == 1) "1 item" else "${folder.count} items"
            row.root.setOnClickListener {
                if (selected.add(folder.id)) {
                    // was added (now selected) — no-op beyond the add
                } else {
                    selected.remove(folder.id)
                }
                renderFolderState()
            }
            row.root.tag = folder.id
            binding.foldersContainer.addView(row.root)
        }
    }

    private fun renderFolderState() {
        // Per-folder checkboxes
        val container = binding.foldersContainer
        val selectedCount = (0 until container.childCount).count { i ->
            val id = container.getChildAt(i).tag as? String ?: return@count false
            id in selected
        }
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val id = child.tag as? String ?: continue
            val cb = child.findViewById<android.widget.CheckBox>(R.id.folderCheck)
            cb.isChecked = id in selected
        }
        // Master "select all" checkbox: checked iff every folder is selected.
        binding.checkSelectAll.isChecked = folders.isNotEmpty() && selectedCount == folders.size
    }

    // ------------------------------------------------------------------
    // Persistence on exit
    // ------------------------------------------------------------------

    override fun finish() {
        persistFolderScopeIfChanged()
        super.finish()
    }

    private fun persistFolderScopeIfChanged() {
        if (folders.isEmpty()) return  // never loaded; don't clobber existing scope
        val existingIds = folders.map { it.id }.toSet()
        val newScope: Set<String> = if (selected.isEmpty()) {
            emptySet() // unchecking everything falls back to "all"
        } else {
            selected.intersect(existingIds)
        }
        val scopeChanged = when {
            originalScope.isEmpty() && newScope.isEmpty() -> false  // both mean "all"
            originalScope.isEmpty() || newScope.isEmpty() -> true
            else -> originalScope != newScope
        }
        if (scopeChanged) {
            IndexScopeStore.setFolderIds(this, newScope)
            IndexController.rescan(this)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
