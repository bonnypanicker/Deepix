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
 *  - **Status** — live WorkManager progress (percent + "N of M photos"), with a single
 *    pause/play toggle beside the progress bar (play also means start / resume / re-index).
 *  - **Power** — "index only while charging" and its sub-option "night charging only" (10 PM–7 AM).
 *  - **Folders** — which device folders the AI index covers (checkboxes + a select-all master toggle).
 *    Independent of the gallery view; every photo still shows. Empty selection means "all folders".
 *  - **Media Processing** — a plain-language explainer of what the on-device scan does; the section
 *    is hidden in the layout, its copy kept for when it returns.
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

    /**
     * The last progress the pass published, kept alongside [latestIndexState] so an optimistic
     * re-render — a tap, or a constraint change — repeats the current reading instead of blanking
     * the count line for a frame.
     */
    private var latestLivePercent: Int? = null
    private var latestCurrent = -1
    private var latestTotal = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityIndexingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        applyResponsiveChrome()

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
        // Single pause/play control next to the progress bar. Running (or queued) pauses;
        // every other state — paused, stopped, never started, or fully indexed — plays,
        // which covers resume, start, and re-index in one affordance.
        binding.btnIndexToggle.setOnClickListener {
            when {
                isRunningState(latestIndexState) -> IndexController.pause(this)
                else -> IndexController.start(this)
            }
            // Optimistic refresh; the observer will correct it as WorkManager transitions.
            renderIndexing(latestIndexState, latestLivePercent, latestCurrent, latestTotal)
        }

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(IndexWorker.WorkName)
            .observe(this) { infos ->
                val work = IndexWorker.pickRelevantWorkInfo(infos)
                latestIndexState = work?.state
                val livePercent = if (work?.state == WorkInfo.State.RUNNING) {
                    work.progress.getInt(IndexWorker.ProgressPercentKey, -1).takeIf { it >= 0 }
                } else {
                    null
                }
                // The count is read whatever the state: a paused pass keeps the progress it last
                // published, so "N of M photos" stays on screen instead of vanishing on pause. Only
                // a pass that has never reported (a fresh enqueue) comes back empty, and stays hidden.
                val current = work?.progress?.getInt(IndexWorker.ProgressCurrentKey, -1) ?: -1
                val total = work?.progress?.getInt(IndexWorker.ProgressTotalKey, -1) ?: -1
                latestLivePercent = livePercent
                if (current >= 0 && total > 0) {
                    latestCurrent = current
                    latestTotal = total
                }
                renderIndexing(work?.state, livePercent, current, total)
            }
    }

    private fun isRunningState(state: WorkInfo.State?): Boolean =
        state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED

    private fun renderIndexing(state: WorkInfo.State?, livePercent: Int?, current: Int, total: Int) {
        val paused = IndexPreferences.isIndexPaused(this)
        val stopped = IndexPreferences.isIndexStopped(this)
        // The stored percent is the last value a pass published, which is the right reading for a
        // paused or idle screen. It is the wrong reading while a pass is live but has published
        // nothing yet: that is the model still loading, and the stored value belongs to the previous
        // pass — which is what flashed 100% at the start of a run.
        val percent = livePercent ?: IndexPreferences.getIndexProgressPercent(this)
        val chargingOnly = IndexPreferences.isChargingOnlyIndexing(this)
        val nightOnly = IndexPreferences.isNightChargingOnly(this)
        val waitReason = IndexPreferences.getLastIndexWaitReason(this)

        binding.indexProgress.progress = percent

        when {
            state == WorkInfo.State.RUNNING -> {
                if (livePercent != null) {
                    binding.indexProgress.isIndeterminate = false
                    binding.indexProgress.progress = livePercent
                    binding.indexStatus.text = "Indexing your photos… $livePercent%"
                } else {
                    // Live, but nothing reported yet — an honest indeterminate bar beats the
                    // previous pass's percent, which read as a complete index that then walked back.
                    binding.indexProgress.isIndeterminate = true
                    binding.indexStatus.text = "Indexing your photos…"
                }
                setToggle(R.drawable.ic_fluent_pause_24_regular, getString(R.string.pause))
            }
            state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED -> {
                binding.indexProgress.isIndeterminate = true
                binding.indexStatus.text = waitReason?.let(IndexRunPolicy::waitReasonLabel) ?: when {
                    nightOnly && chargingOnly -> "Waiting for night (10 PM – 7 AM) while charging"
                    chargingOnly -> "Waiting to charge"
                    else -> "Queued…"
                }
                setToggle(R.drawable.ic_fluent_pause_24_regular, getString(R.string.pause))
            }
            state == WorkInfo.State.FAILED -> {
                binding.indexProgress.isIndeterminate = false
                binding.indexStatus.text = "Indexing stopped because of an error"
                setToggle(R.drawable.ic_fluent_play_24_regular, "Retry")
            }
            paused -> {
                binding.indexProgress.isIndeterminate = false
                binding.indexStatus.text = "Indexing paused · $percent%"
                setToggle(R.drawable.ic_fluent_play_24_regular, "Resume")
            }
            stopped && percent < 100 -> {
                binding.indexProgress.isIndeterminate = false
                binding.indexStatus.text = "Indexing stopped · $percent%"
                setToggle(R.drawable.ic_fluent_play_24_regular, "Start")
            }
            percent >= 100 || state == WorkInfo.State.SUCCEEDED -> {
                binding.indexProgress.isIndeterminate = false
                binding.indexProgress.progress = 100
                binding.indexStatus.text = "Your photos are indexed"
                setToggle(R.drawable.ic_fluent_replay_24_regular, "Re-index")
            }
            else -> {
                binding.indexProgress.isIndeterminate = false
                binding.indexStatus.text = "Indexing not started"
                setToggle(R.drawable.ic_fluent_play_24_regular, "Start")
            }
        }

        // "N of M photos" while the pass is live, queued, paused or stopped: the count is the last
        // one the pass published, and losing it on pause is exactly what this line is here to avoid.
        // It is hidden only where it would be noise or a lie — nothing reported, finished, or failed.
        val showCount = current >= 0 && total > 0 &&
            state != WorkInfo.State.SUCCEEDED && state != WorkInfo.State.FAILED
        if (showCount) {
            binding.indexCount.text = "$current of $total photos"
            binding.indexCount.visibility = View.VISIBLE
        } else {
            binding.indexCount.visibility = View.GONE
        }
    }

    private fun setToggle(iconRes: Int, description: String) {
        binding.btnIndexToggle.setImageResource(iconRes)
        binding.btnIndexToggle.contentDescription = description
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
        renderIndexing(latestIndexState, latestLivePercent, latestCurrent, latestTotal)
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

    /**
     * The hero title carries its full size in XML, and a rotation handled in place (see the manifest's
     * configChanges) never re-inflates the layout — so the size is re-decided here, on the way in and on
     * every window shape change.
     */
    private fun applyResponsiveChrome() =
        Responsive.applyTitleText(binding.screenTitle, this, HERO_TITLE_SP)

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveChrome()
    }

    companion object {
        private const val HERO_TITLE_SP = 40f
    }
}
