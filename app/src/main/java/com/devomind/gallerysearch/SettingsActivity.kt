package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.devomind.gallerysearch.databinding.ActivitySettingsBinding

/**
 * Metro-styled preferences screen. All toggles write to [IndexPreferences] immediately; MainActivity
 * re-reads and applies them when this screen returns.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        bindToggles()
        bindGridColumns()
        bindCollageSize()
        bindActions()
        bindIndexing()
        bindAbout()
    }

    override fun onResume() {
        super.onResume()
        updateIndexedFoldersSubtitle()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            insets
        }
    }

    private fun bindToggles() {
        binding.switchCollage.isChecked = IndexPreferences.isCollageLayout(this)
        binding.rowCollage.setOnClickListener {
            val newValue = !binding.switchCollage.isChecked
            binding.switchCollage.isChecked = newValue
            IndexPreferences.setCollageLayout(this, newValue)
        }

        binding.switchPinned.isChecked = IndexPreferences.isShowPinnedInCollections(this)
        binding.rowPinned.setOnClickListener {
            val newValue = !binding.switchPinned.isChecked
            binding.switchPinned.isChecked = newValue
            IndexPreferences.setShowPinnedInCollections(this, newValue)
        }

        binding.switchCharging.isChecked = IndexPreferences.isChargingOnlyIndexing(this)
        binding.rowCharging.setOnClickListener {
            val newValue = !binding.switchCharging.isChecked
            binding.switchCharging.isChecked = newValue
            IndexPreferences.setChargingOnlyIndexing(this, newValue)
        }

        // Beta sensitive-content blur is temporarily disabled — hide the whole Privacy section.
        if (NsfwClassifier.FEATURE_ENABLED) {
            binding.switchBlurSensitive.isChecked = IndexPreferences.isBlurSensitive(this)
            binding.rowBlurSensitive.setOnClickListener {
                val newValue = !binding.switchBlurSensitive.isChecked
                binding.switchBlurSensitive.isChecked = newValue
                IndexPreferences.setBlurSensitive(this, newValue)
            }
        } else {
            binding.privacyHeader.visibility = View.GONE
            binding.rowBlurSensitive.visibility = View.GONE
        }
    }

    private fun bindGridColumns() {
        updateColumnLabel()
        binding.colMinus.setOnClickListener { changeColumns(-1) }
        binding.colPlus.setOnClickListener { changeColumns(+1) }
    }

    private fun changeColumns(delta: Int) {
        val current = IndexPreferences.getGridColumnCount(this)
        val next = (current + delta).coerceIn(DesignTokens.GRID_MIN_COLUMNS, DesignTokens.GRID_MAX_COLUMNS)
        if (next == current) return
        IndexPreferences.setGridColumnCount(this, next)
        updateColumnLabel()
    }

    private fun updateColumnLabel() {
        binding.colValue.text = IndexPreferences.getGridColumnCount(this).toString()
        val count = IndexPreferences.getGridColumnCount(this)
        binding.colMinus.isEnabled = count > DesignTokens.GRID_MIN_COLUMNS
        binding.colPlus.isEnabled = count < DesignTokens.GRID_MAX_COLUMNS
        binding.colMinus.alpha = if (binding.colMinus.isEnabled) 1f else 0.35f
        binding.colPlus.alpha = if (binding.colPlus.isEnabled) 1f else 0.35f
    }

    private fun bindCollageSize() {
        updateCollageSizeLabel()
        // "+" grows tiles (fewer per row), "−" shrinks them (more per row).
        binding.collagePlus.setOnClickListener { changeCollageSize(+1) }
        binding.collageMinus.setOnClickListener { changeCollageSize(-1) }
    }

    private fun changeCollageSize(sizeDelta: Int) {
        val current = IndexPreferences.getCollageScale(this)
        // A larger displayed size maps to a lower scale level (fewer, bigger tiles).
        val next = (current - sizeDelta)
            .coerceIn(DesignTokens.COLLAGE_SCALE_MIN, DesignTokens.COLLAGE_SCALE_MAX)
        if (next == current) return
        IndexPreferences.setCollageScale(this, next)
        updateCollageSizeLabel()
    }

    private fun updateCollageSizeLabel() {
        val level = IndexPreferences.getCollageScale(this)
        // Show an intuitive size number: higher = bigger tiles (inverse of the internal level).
        val displaySize = DesignTokens.COLLAGE_SCALE_MAX + DesignTokens.COLLAGE_SCALE_MIN - level
        binding.collageValue.text = displaySize.toString()
        binding.collagePlus.isEnabled = level > DesignTokens.COLLAGE_SCALE_MIN
        binding.collageMinus.isEnabled = level < DesignTokens.COLLAGE_SCALE_MAX
        binding.collagePlus.alpha = if (binding.collagePlus.isEnabled) 1f else 0.35f
        binding.collageMinus.alpha = if (binding.collageMinus.isEnabled) 1f else 0.35f
    }

    private fun bindActions() {
        binding.rowClearCleanup.setOnClickListener {
            CleanupResultStore(this).clear()
            Toast.makeText(this, "Smart Cleanup cache cleared.", Toast.LENGTH_SHORT).show()
        }
        binding.rowIndexedFolders.setOnClickListener {
            startActivity(android.content.Intent(this, IndexedFoldersActivity::class.java))
        }
    }

    private fun updateIndexedFoldersSubtitle() {
        val scoped = !IndexScopeStore.isAllFolders(this)
        val count = IndexScopeStore.getFolderIds(this).size
        binding.indexedFoldersSubtitle.text = if (scoped)
            "Indexing $count selected folder${if (count == 1) "" else "s"}"
        else
            "Indexing all folders"
    }

    // ------------------------------------------------------------------
    // Indexing status + controls
    // ------------------------------------------------------------------

    /** Tracks the latest WorkManager state so button taps render immediately without waiting. */
    private var latestIndexState: WorkInfo.State? = null

    private fun bindIndexing() {
        binding.btnIndexPrimary.setOnClickListener {
            when {
                isRunningState(latestIndexState) -> IndexController.pause(this)
                IndexPreferences.isIndexPaused(this) -> IndexController.resume(this)
                else -> IndexController.start(this)
            }
            // Optimistic refresh; the observer will correct it as WorkManager transitions.
            renderIndexing(latestIndexState, livePercent = null)
        }
        binding.btnIndexStop.setOnClickListener {
            IndexController.stop(this)
            renderIndexing(WorkInfo.State.CANCELLED, livePercent = null)
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
                renderIndexing(work?.state, livePercent)
            }
    }

    private fun isRunningState(state: WorkInfo.State?): Boolean =
        state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED

    private fun renderIndexing(state: WorkInfo.State?, livePercent: Int?) {
        val paused = IndexPreferences.isIndexPaused(this)
        val stopped = IndexPreferences.isIndexStopped(this)
        val percent = livePercent ?: IndexPreferences.getIndexProgressPercent(this)
        val chargingOnly = IndexPreferences.isChargingOnlyIndexing(this)

        binding.indexProgress.progress = percent

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
                binding.indexStatus.text = if (chargingOnly) "Waiting to charge" else "Queued…"
                binding.indexSubStatus.text = if (chargingOnly)
                    "Indexing resumes when the device is plugged in"
                else
                    "On-device AI · nothing leaves your phone"
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

    private fun bindAbout() {
        val version = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }.versionName
        }.getOrNull() ?: "—"
        binding.aboutVersion.text = "Version $version"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
