package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.devomind.gallerysearch.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        bindStorage()
        bindSafe()
        bindIndexing()
        bindAbout()
    }

    override fun onResume() {
        super.onResume()
        updateIndexedFoldersSubtitle()
        updateStorageSubtitles()
        updateSafeSubtitles()
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

    private fun bindStorage() {
        binding.switchRecycleBin.isChecked = IndexPreferences.isRecycleBinEnabled(this)
        binding.rowRecycleBin.setOnClickListener {
            val newValue = !binding.switchRecycleBin.isChecked
            binding.switchRecycleBin.isChecked = newValue
            IndexPreferences.setRecycleBinEnabled(this, newValue)
        }

        binding.switchDirectDelete.isChecked = IndexPreferences.isSkipDeleteConfirm(this)
        binding.rowDirectDelete.setOnClickListener {
            if (!StoragePermissions.hasAllFilesAccess(this) && !binding.switchDirectDelete.isChecked) {
                promptAllFilesAccess()
                return@setOnClickListener
            }
            val newValue = !binding.switchDirectDelete.isChecked
            binding.switchDirectDelete.isChecked = newValue
            IndexPreferences.setSkipDeleteConfirm(this, newValue)
        }

        binding.rowOpenBin.setOnClickListener {
            startActivity(android.content.Intent(this, BinActivity::class.java))
        }

        binding.rowAllFilesAccess.setOnClickListener { promptAllFilesAccess() }
    }

    // ------------------------------------------------------------------
    // Safe (encrypted locker) storage location
    // ------------------------------------------------------------------

    private fun bindSafe() {
        updateSafeSubtitles()
        binding.rowSafeLocation.setOnClickListener { showSafeLocationDialog() }
    }

    private fun showSafeLocationDialog() {
        val current = IndexPreferences.getSafeStorageRoot(this)
        val options = arrayOf("Pictures", "Documents")
        val checked = if (current == IndexPreferences.SAFE_ROOT_DOCUMENTS) 1 else 0
        AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
            .setTitle("Safe storage location")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val newRoot =
                    if (which == 1) IndexPreferences.SAFE_ROOT_DOCUMENTS else IndexPreferences.SAFE_ROOT_PICTURES
                dialog.dismiss()
                if (newRoot != current) changeSafeRoot(current, newRoot)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun changeSafeRoot(oldRoot: String, newRoot: String) {
        if (SafeStore.isConfigured(this)) {
            if (!StoragePermissions.hasAllFilesAccess(this)) {
                Toast.makeText(this, "All-files access is needed to move the Safe", Toast.LENGTH_LONG).show()
                return
            }
            lifecycleScope.launch {
                val moved = withContext(Dispatchers.IO) {
                    SafeManager.moveVault(this@SettingsActivity, oldRoot, newRoot)
                }
                if (!moved) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Couldn't move the Safe file to the new location",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                IndexPreferences.setSafeStorageRoot(this@SettingsActivity, newRoot)
                updateSafeSubtitles()
                Toast.makeText(this@SettingsActivity, "Safe moved to ${rootLabel(newRoot)}", Toast.LENGTH_LONG).show()
            }
        } else {
            IndexPreferences.setSafeStorageRoot(this, newRoot)
            updateSafeSubtitles()
            Toast.makeText(this, "New Safe will be created in ${rootLabel(newRoot)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSafeSubtitles() {
        val root = IndexPreferences.getSafeStorageRoot(this)
        binding.safeLocationSubtitle.text = "${rootLabel(root)}/Deepix Safe/"
        binding.safePathSubtitle.text = when {
            SafeManager.archiveExists(this) -> SafeManager.vaultLocationLabel(this)
            SafeStore.isConfigured(this) -> "Configured · no file yet"
            else -> "Not set up"
        }
    }

    private fun rootLabel(root: String): String =
        if (root == IndexPreferences.SAFE_ROOT_DOCUMENTS) "Documents" else "Pictures"

    private fun promptAllFilesAccess() {
        if (StoragePermissions.hasAllFilesAccess(this)) {
            Toast.makeText(this, "All-files access is already granted.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(StoragePermissions.manageAllFilesIntent(this)) }
            .onFailure { Toast.makeText(this, "Couldn't open settings.", Toast.LENGTH_SHORT).show() }
    }

    private fun updateStorageSubtitles() {
        val granted = StoragePermissions.hasAllFilesAccess(this)
        binding.allFilesSubtitle.text =
            if (granted) "Granted · powers the recycle bin and instant deletion"
            else "Not granted · tap to allow the recycle bin and instant deletion"
        val count = BinManager.count(this)
        binding.binCountSubtitle.text = when (count) {
            0 -> "Restore or permanently remove deleted photos"
            1 -> "1 photo in the bin"
            else -> "$count photos in the bin"
        }
        // Keep the direct-delete toggle honest if access was revoked outside the app.
        if (!granted && binding.switchDirectDelete.isChecked) {
            binding.switchDirectDelete.isChecked = false
            IndexPreferences.setSkipDeleteConfirm(this, false)
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
