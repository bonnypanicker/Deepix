package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
        bindActions()
        bindAbout()
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

    private fun bindActions() {
        binding.rowClearCleanup.setOnClickListener {
            CleanupResultStore(this).clear()
            Toast.makeText(this, "Smart Cleanup cache cleared.", Toast.LENGTH_SHORT).show()
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
