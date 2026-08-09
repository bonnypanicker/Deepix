package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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
    private var accentChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        accentChanged = savedInstanceState?.getBoolean(StateAccentChanged, false) ?: false
        updateAccentResult()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        bindToggles()
        bindThumbnailSize()
        bindAccentColor()
        bindBottomBar()
        bindStorage()
        bindSafe()
        bindIndexing()
        bindAbout()
    }

    override fun onResume() {
        super.onResume()
        binding.accentColorSubtitle.text = AccentPalette.current(this).displayName
        updateIndexedFoldersSubtitle()
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

    private fun bindAccentColor() {
        binding.accentColorSubtitle.text = AccentPalette.current(this).displayName
        binding.rowAccentColor.setOnClickListener { showAccentColorDialog() }
    }

    private fun bindBottomBar() {
        updateBottomBarSettings()
        binding.rowBottomBarOrder.setOnClickListener { showBottomBarOrderDialog() }
        binding.rowBottomBarFolders.setOnClickListener {
            val enabled = !binding.switchBottomBarFolders.isChecked
            BottomBarConfig.setFoldersEnabled(this, enabled)
            updateBottomBarSettings()
        }
        binding.rowBottomBarSafe.setOnClickListener {
            val enabled = !binding.switchBottomBarSafe.isChecked
            BottomBarConfig.setSafeEnabled(this, enabled)
            updateBottomBarSettings()
        }
        binding.rowDefaultPage.setOnClickListener { showDefaultPageDialog() }
    }

    private fun updateBottomBarSettings() {
        val enabled = BottomBarConfig.enabledOrder(this)
        binding.switchBottomBarFolders.isChecked = BottomBarConfig.isFoldersEnabled(this)
        binding.switchBottomBarSafe.isChecked = BottomBarConfig.isSafeEnabled(this)
        binding.bottomBarOrderSubtitle.text = enabled.joinToString(" · ") { getString(it.labelRes) }
        binding.defaultPageSubtitle.text = getString(BottomBarConfig.defaultPage(this).labelRes)
    }

    private fun showDefaultPageDialog() {
        val enabled = BottomBarConfig.enabledOrder(this)
        val current = BottomBarConfig.defaultPage(this)
        MetroDialog.singleChoice(
            this,
            title = getString(R.string.default_page),
            options = enabled.map { getString(it.labelRes) },
            checkedIndex = enabled.indexOf(current).coerceAtLeast(0)
        ) { which ->
            BottomBarConfig.setDefaultPage(this, enabled[which])
            updateBottomBarSettings()
        }
    }

    private fun showBottomBarOrderDialog() {
        val root = layoutInflater.inflate(R.layout.dialog_bottom_bar_order, null)
        val list = root.findViewById<LinearLayout>(R.id.bottomBarOrderList)
        val order = BottomBarConfig.order(this).toMutableList()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_GallerySearch_Dialog)
            .setView(root)
            .create()

        fun renderRows() {
            list.removeAllViews()
            order.forEachIndexed { index, destination ->
                val row = layoutInflater.inflate(R.layout.item_bottom_bar_order, list, false)
                row.findViewById<ImageView>(R.id.orderIcon).setImageResource(destination.iconRes)
                row.findViewById<TextView>(R.id.orderTitle).text = getString(destination.labelRes)
                val optionalHidden = when (destination) {
                    BottomBarDestination.Folders -> !BottomBarConfig.isFoldersEnabled(this)
                    BottomBarDestination.Safe -> !BottomBarConfig.isSafeEnabled(this)
                    else -> false
                }
                row.findViewById<TextView>(R.id.orderSubtitle).apply {
                    visibility = if (optionalHidden) View.VISIBLE else View.GONE
                    text = getString(R.string.bottom_bar_hidden)
                }
                row.findViewById<ImageButton>(R.id.orderUp).apply {
                    isEnabled = index > 0
                    alpha = if (isEnabled) 1f else 0.3f
                    contentDescription = getString(R.string.move_up, getString(destination.labelRes))
                    setOnClickListener {
                        if (index > 0) {
                            java.util.Collections.swap(order, index, index - 1)
                            BottomBarConfig.setOrder(this@SettingsActivity, order)
                            renderRows()
                            updateBottomBarSettings()
                        }
                    }
                }
                row.findViewById<ImageButton>(R.id.orderDown).apply {
                    isEnabled = index < order.lastIndex
                    alpha = if (isEnabled) 1f else 0.3f
                    contentDescription = getString(R.string.move_down, getString(destination.labelRes))
                    setOnClickListener {
                        if (index < order.lastIndex) {
                            java.util.Collections.swap(order, index, index + 1)
                            BottomBarConfig.setOrder(this@SettingsActivity, order)
                            renderRows()
                            updateBottomBarSettings()
                        }
                    }
                }
                list.addView(row)
            }
        }
        root.findViewById<TextView>(R.id.bottomBarOrderDone).setOnClickListener { dialog.dismiss() }
        renderRows()
        dialog.show()
    }

    private fun showAccentColorDialog() {
        val choices = AccentPalette.choices
        val labels = choices.map { it.displayName }.toTypedArray()
        val current = AccentPalette.current(this)
        val checked = choices.indexOfFirst { it == current }.coerceAtLeast(0)
        MetroDialog.singleChoice(
            this,
            title = "Accent color",
            options = labels.toList(),
            checkedIndex = checked,
            swatches = choices.map { it.solid }
        ) { which ->
            val choice = choices[which]
            IndexPreferences.setAccentColor(this, choice.key)
            accentChanged = true
            binding.accentColorSubtitle.text = choice.displayName
            updateAccentResult()
            recreate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(StateAccentChanged, accentChanged)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        updateAccentResult()
        super.finish()
    }

    private fun updateAccentResult() {
        setResult(
            if (accentChanged) RESULT_OK else RESULT_CANCELED,
            android.content.Intent().putExtra(ExtraAccentChanged, accentChanged)
        )
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

    private fun bindStorage() {
        binding.switchRecycleBin.isChecked = IndexPreferences.isRecycleBinEnabled(this)
        binding.rowRecycleBin.setOnClickListener {
            val newValue = !binding.switchRecycleBin.isChecked
            binding.switchRecycleBin.isChecked = newValue
            IndexPreferences.setRecycleBinEnabled(this, newValue)
        }
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
        MetroDialog.singleChoice(
            this,
            title = "Safe storage location",
            options = options.toList(),
            checkedIndex = checked
        ) { which ->
            val newRoot =
                if (which == 1) IndexPreferences.SAFE_ROOT_DOCUMENTS else IndexPreferences.SAFE_ROOT_PICTURES
            changeSafeRoot(current, newRoot)
        }
    }

    private fun changeSafeRoot(oldRoot: String, newRoot: String) {
        if (SafeStore.isConfigured(this)) {
            if (!StoragePermissions.hasAllFilesAccess(this)) {
                MetroBanner.show(this, "All-files access is needed to move the Safe")
                return
            }
            lifecycleScope.launch {
                val moved = withContext(Dispatchers.IO) {
                    SafeManager.moveVault(this@SettingsActivity, oldRoot, newRoot)
                }
                if (!moved) {
                    MetroBanner.show(this@SettingsActivity, "Couldn't move the Safe file to the new location")
                    return@launch
                }
                IndexPreferences.setSafeStorageRoot(this@SettingsActivity, newRoot)
                updateSafeSubtitles()
                MetroBanner.show(this@SettingsActivity, "Safe moved to ${rootLabel(newRoot)}")
            }
        } else {
            IndexPreferences.setSafeStorageRoot(this, newRoot)
            updateSafeSubtitles()
            MetroBanner.show(this, "New Safe will be created in ${rootLabel(newRoot)}")
        }
    }

    private fun updateSafeSubtitles() {
        val root = IndexPreferences.getSafeStorageRoot(this)
        binding.safeLocationSubtitle.text = rootLabel(root)
    }

    private fun rootLabel(root: String): String =
        if (root == IndexPreferences.SAFE_ROOT_DOCUMENTS) "Documents" else "Pictures"

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

        binding.rowFaceValidation.apply {
            if (BuildConfig.DEBUG) visibility = View.VISIBLE
            setOnClickListener { FaceValidationActivity.launch(this@SettingsActivity) }
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
            else -> "Manage indexing"
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

    companion object {
        const val ExtraAccentChanged = "extra_accent_changed"
        private const val StateAccentChanged = "state_accent_changed"
    }
}
