package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
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
        bindThumbnailSize()
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

        binding.switchAlbumFolderSize.isChecked = IndexPreferences.isShowAlbumFolderSize(this)
        binding.rowAlbumFolderSize.setOnClickListener {
            val newValue = !binding.switchAlbumFolderSize.isChecked
            binding.switchAlbumFolderSize.isChecked = newValue
            IndexPreferences.setShowAlbumFolderSize(this, newValue)
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

    /**
     * One slider that sets thumbnail size for both grid and collage layouts in lockstep. The slider
     * is a 5-position index (0..4); grid columns and collage scale are derived as mirror images so a
     * higher position always means bigger thumbnails:
     *   gridColumns  = GRID_MAX_COLUMNS  - position   (6..2)
     *   collageScale = COLLAGE_SCALE_MAX - position   (5..1)
     */
    private fun bindThumbnailSize() {
        val slider = binding.thumbnailSizeSlider
        slider.max = DesignTokens.GRID_MAX_COLUMNS - DesignTokens.GRID_MIN_COLUMNS
        slider.progress = sliderPositionFromGrid(IndexPreferences.getGridColumnCount(this))
        updateThumbnailSizeLabel(slider.progress)

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seeker: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val grid = DesignTokens.GRID_MAX_COLUMNS - progress
                val collage = DesignTokens.COLLAGE_SCALE_MAX - progress
                IndexPreferences.setGridColumnCount(this@SettingsActivity, grid)
                IndexPreferences.setCollageScale(this@SettingsActivity, collage)
                updateThumbnailSizeLabel(progress)
            }

            override fun onStartTrackingTouch(seeker: SeekBar) {}
            override fun onStopTrackingTouch(seeker: SeekBar) {}
        })
    }

    private fun sliderPositionFromGrid(gridColumns: Int): Int =
        (DesignTokens.GRID_MAX_COLUMNS - gridColumns)
            .coerceIn(0, DesignTokens.GRID_MAX_COLUMNS - DesignTokens.GRID_MIN_COLUMNS)

    private fun updateThumbnailSizeLabel(position: Int) {
        val total = DesignTokens.GRID_MAX_COLUMNS - DesignTokens.GRID_MIN_COLUMNS
        binding.thumbnailSizeValue.text = when (position) {
            0 -> "Smallest"
            total -> "Largest"
            else -> "Medium"
        }
    }

    private fun bindActions() {
        binding.rowClearCleanup.setOnClickListener {
            CleanupResultStore(this).clear()
            Toast.makeText(this, "Smart Cleanup cache cleared.", Toast.LENGTH_SHORT).show()
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
        // Indexing status now lives on the dedicated Indexing page; show a one-line summary here.
        binding.indexingSubtitle.text = indexSummary()
    }

    // ------------------------------------------------------------------
    // Indexing — link to the dedicated page
    // ------------------------------------------------------------------

    private var latestIndexState: WorkInfo.State? = null

    private fun bindIndexing() {
        binding.rowIndexing.setOnClickListener {
            startActivity(android.content.Intent(this, IndexingActivity::class.java))
        }

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(IndexWorker.WorkName)
            .observe(this) { infos ->
                latestIndexState = infos.firstOrNull()?.state
                binding.indexingSubtitle.text = indexSummary()
            }
    }

    /** One-line status shown under the "Indexing" row so users can see the state without opening it. */
    private fun indexSummary(): String {
        val percent = IndexPreferences.getIndexProgressPercent(this)
        val paused = IndexPreferences.isIndexPaused(this)
        val stopped = IndexPreferences.isIndexStopped(this)
        val state = latestIndexState
        val nightOnly = IndexPreferences.isNightChargingOnly(this)
        val chargingOnly = IndexPreferences.isChargingOnlyIndexing(this)
        return when {
            state == WorkInfo.State.RUNNING -> "Indexing… $percent%"
            state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED -> when {
                nightOnly && chargingOnly -> "Waiting for night (10 PM – 7 AM)"
                chargingOnly -> "Waiting to charge"
                else -> "Queued"
            }
            paused -> "Paused · $percent%"
            stopped && percent < 100 -> "Stopped · $percent%"
            percent >= 100 || state == WorkInfo.State.SUCCEEDED -> "Up to date"
            else -> "Status, folders, charging & power options"
        }
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
