package com.devomind.gallerysearch

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
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
import java.io.File

/**
 * Metro-styled preferences screen. All toggles write to [IndexPreferences] immediately; MainActivity
 * re-reads and applies them when this screen returns.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var accentChanged = false

    /** Held as a property so the exact same instance is registered and removed; a lambda built per call
     *  would leave one observer behind for every pause/resume cycle. */
    private val billingListener: () -> Unit = { updateProSubtitle() }

    /** The accent this instance actually applied in [onCreate], for noticing a verdict change while open. */
    private var appliedAccent: AccentPalette.Choice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        appliedAccent = AccentPalette.current(this)
        accentChanged = savedInstanceState?.getBoolean(StateAccentChanged, false) ?: false
        updateAccentResult()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        applyResponsiveChrome()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        bindToggles()
        bindThumbnailSize()
        bindAccentColor()
        bindPro()
        bindBottomBar()
        bindStorage()
        bindSafe()
        bindIndexing()
        bindAbout()
    }

    override fun onResume() {
        super.onResume()
        // A purchase made from this screen's own paywall is what unlocks an accent the user had already
        // picked before the gate existed — their stored choice is theirs, and the theme should say so
        // without making them reopen the picker. recreate() re-runs onCreate, which re-reads the accent,
        // so this can only fire once per verdict change.
        if (appliedAccent != null && appliedAccent != AccentPalette.current(this)) {
            recreate()
            return
        }
        binding.accentColorSubtitle.text = AccentPalette.current(this).displayName
        updateIndexedFoldersSubtitle()
        updateSafeSubtitles()
        BillingGateway.observe(billingListener)
        updateProSubtitle()
        // Play's sheet backgrounds the app, so coming back here is also how a finished purchase is
        // noticed. The query answers from the Google app's own cache, so it costs no network most times.
        BillingGateway.refresh()
    }

    override fun onPause() {
        BillingGateway.unobserve(billingListener)
        super.onPause()
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
        binding.privacyHeader.visibility = View.GONE
        binding.rowBlurSensitive.visibility = View.GONE
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

    private fun bindPro() {
        updateProSubtitle()
        binding.rowPro.setOnClickListener { ProPaywall.show(this) }
    }

    /**
     * Reads the stored verdict, never a live Play call — the row has to say the right thing on a plane.
     * [Entitlement.Unknown] keeps saying "checking" because that is what each resume retries; only an
     * answer from Play moves it.
     */
    private fun updateProSubtitle() {
        binding.proSubtitle.text = when (Entitlements.state(this)) {
            Entitlement.Owned -> getString(R.string.pro_owned_subtitle)
            Entitlement.NotOwned -> getString(R.string.pro_not_purchased)
            Entitlement.Unknown -> getString(R.string.pro_checking)
        }
    }

    private fun bindBottomBar() {
        updateBottomBarSettings()
        binding.rowBottomBarOrder.setOnClickListener { showBottomBarOrderDialog() }
        binding.rowDefaultPage.setOnClickListener { showDefaultPageDialog() }
    }

    private fun updateBottomBarSettings() {
        val enabled = BottomBarConfig.enabledOrder(this)
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
                val check = row.findViewById<AppCompatCheckBox>(R.id.orderCheck)
                if (destination.optional) {
                    check.visibility = View.VISIBLE
                    // State before listener, so the initial sync can't write a spurious enable.
                    check.isChecked = when (destination) {
                        BottomBarDestination.Albums -> BottomBarConfig.isAlbumsEnabled(this)
                        BottomBarDestination.Folders -> BottomBarConfig.isFoldersEnabled(this)
                        else -> BottomBarConfig.isSafeEnabled(this)
                    }
                    check.setOnCheckedChangeListener { _, checked ->
                        when (destination) {
                            BottomBarDestination.Albums -> BottomBarConfig.setAlbumsEnabled(this, checked)
                            BottomBarDestination.Folders -> BottomBarConfig.setFoldersEnabled(this, checked)
                            else -> BottomBarConfig.setSafeEnabled(this, checked)
                        }
                        updateBottomBarSettings()
                    }
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

    /**
     * The whole palette is listed, paid swatches included, because a picker that shows three colors reads
     * as a three-color app. [AccentPalette.current] has already demoted an unowned stored choice, so the
     * check mark can never sit on a row the user cannot tap.
     */
    private fun showAccentColorDialog() {
        val choices = AccentPalette.choicesFor(this)
        val unlocked = Entitlements.isUnlocked(this)
        val labels = choices.map { it.displayName }.toTypedArray()
        val current = AccentPalette.current(this)
        val checked = choices.indexOfFirst { it == current }.coerceAtLeast(0)
        MetroDialog.singleChoice(
            this,
            title = "Accent color",
            options = labels.toList(),
            checkedIndex = checked,
            swatches = choices.map { AccentPalette.swatchColor(this, it) },
            locked = choices.withIndex()
                .filter { FreeTier.accentLocked(it.value, unlocked) }
                .map { it.index }
                .toSet()
        ) { which ->
            applyAccent(choices[which])
        }
    }

    private fun applyAccent(choice: AccentPalette.Choice) {
        if (FreeTier.accentLocked(choice, Entitlements.isUnlocked(this))) {
            ProPaywall.show(this, getString(R.string.gate_accent))
            return
        }
        IndexPreferences.setAccentColor(this, choice.key)
        accentChanged = true
        binding.accentColorSubtitle.text = choice.displayName
        updateAccentResult()
        recreate()
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
        MetroDialog.items(
            this,
            title = "Safe storage location",
            options = listOf("Choose folder…", "Reset to Pictures (default)")
        ) { which ->
            when (which) {
                0 -> {
                    runCatching {
                        safeFolderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                    }.onFailure {
                        MetroBanner.show(this, "Couldn't open the folder picker")
                    }
                }
                1 -> changeSafeVaultDir(null)
            }
        }
    }

    private val safeFolderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val treeUri: Uri? = result.data?.data
        if (result.resultCode != RESULT_OK || treeUri == null) return@registerForActivityResult
        val dir = SafeManager.customDirFromTreeUri(treeUri)
        if (dir == null) {
            MetroBanner.show(this, "Please choose a folder on internal storage")
            return@registerForActivityResult
        }
        changeSafeVaultDir(dir)
    }

    /**
     * Moves the vault to a custom folder (or back to the Pictures default when [newDir] is null)
     * and persists the choice. The vault only moves when the target has no Safe file, so nothing
     * can be overwritten or lost.
     */
    private fun changeSafeVaultDir(newDir: File?) {
        val target = newDir ?: SafeManager.defaultVaultDir(this)
        if (SafeStore.isConfigured(this)) {
            if (!StoragePermissions.hasAllFilesAccess(this)) {
                MetroBanner.show(this, "All-files access is needed to move the Safe")
                return
            }
            lifecycleScope.launch {
                val moved = withContext(Dispatchers.IO) {
                    SafeManager.moveVaultToDir(this@SettingsActivity, target)
                }
                if (!moved) {
                    MetroBanner.show(this@SettingsActivity, "The chosen folder already has a Safe file")
                    return@launch
                }
                IndexPreferences.setSafeVaultDir(this@SettingsActivity, newDir?.absolutePath)
                // Reset to default also clears the legacy Pictures/Documents choice, so a stale
                // root can't point the vault at the folder we just moved the zip out of.
                if (newDir == null) IndexPreferences.setSafeStorageRoot(
                    this@SettingsActivity,
                    IndexPreferences.SAFE_ROOT_PICTURES
                )
                updateSafeSubtitles()
                MetroBanner.show(this@SettingsActivity, "Safe moved to ${target.name}")
            }
        } else {
            IndexPreferences.setSafeVaultDir(this, newDir?.absolutePath)
            // No vault yet: keep the default root clean too, so the next Safe is created in Pictures
            // even if a legacy Documents choice predates the folder picker.
            if (newDir == null) IndexPreferences.setSafeStorageRoot(
                this,
                IndexPreferences.SAFE_ROOT_PICTURES
            )
            updateSafeSubtitles()
            MetroBanner.show(this, "New Safe will be created in ${target.name}")
        }
    }

    private fun updateSafeSubtitles() {
        val custom = IndexPreferences.getSafeVaultDir(this)
        binding.safeLocationSubtitle.text =
            if (!custom.isNullOrBlank()) java.io.File(custom).name
            else rootLabel(IndexPreferences.getSafeStorageRoot(this))
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
        val waitReason = IndexPreferences.getLastIndexWaitReason(this)
        return when {
            state == WorkInfo.State.RUNNING -> "Indexing… $percent%"
            state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED -> when {
                waitReason != null -> IndexRunPolicy.waitReasonLabel(waitReason)
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

        // Clearing the flag first makes the tour run exactly as it does on a fresh install; it
        // re-sets it on exit, so replaying can never leave the flag in the wrong state.
        binding.rowReplayTour.setOnClickListener {
            IndexPreferences.setFirstRunDone(this, false)
            startActivity(FirstRunActivity.intent(this))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * The hero title carries its full size in XML. A rotation is handled in place (see the manifest's
     * configChanges), so the layout is never re-inflated and the size has to be re-decided here — on the
     * way in and again whenever the window changes shape.
     */
    private fun applyResponsiveChrome() =
        Responsive.applyTitleText(binding.screenTitle, this, HERO_TITLE_SP)

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveChrome()
    }

    companion object {
        const val ExtraAccentChanged = "extra_accent_changed"
        private const val StateAccentChanged = "state_accent_changed"
        private const val HERO_TITLE_SP = 40f
    }
}
