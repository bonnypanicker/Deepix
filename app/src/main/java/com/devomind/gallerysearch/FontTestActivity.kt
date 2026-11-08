package com.devomind.gallerysearch

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.devomind.gallerysearch.databinding.ActivityFontTestBinding
import com.devomind.gallerysearch.databinding.ItemFontTestBinding

/**
 * Settings "fonts" test page. It is a comparison surface: every typeface bundled in `res/font` is
 * listed with a sample line drawn in that family, and tapping one stores it ([IndexPreferences]) and
 * repaints the preview card. The app's own typography stays fixed by the theme, so this page shows a
 * change in context rather than re-skinning every screen.
 */
class FontTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFontTestBinding
    private val rows = mutableMapOf<AppFont, ItemFontTestBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityFontTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        applyResponsiveChrome()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        buildRows()
        renderSelection()
    }

    private fun buildRows() {
        val inflater = layoutInflater
        val container = binding.fontList
        AppFont.entries.forEachIndexed { index, font ->
            val row = ItemFontTestBinding.inflate(inflater, container, false)
            val typeface = ResourcesCompat.getFont(this, font.fontRes)
            row.fontName.text = font.displayName
            row.fontName.typeface = typeface
            row.fontSample.typeface = typeface
            row.root.setOnClickListener { select(font) }
            if (index > 0) container.addView(divider())
            container.addView(row.root)
            rows[font] = row
        }
    }

    private fun select(font: AppFont) {
        IndexPreferences.setAppFont(this, font)
        renderSelection()
    }

    /** Applies the stored pick to the preview card and marks the matching row. */
    private fun renderSelection() {
        val selected = IndexPreferences.getAppFont(this)
        val typeface = ResourcesCompat.getFont(this, selected.fontRes)
        binding.previewTitle.typeface = typeface
        binding.previewBody.typeface = typeface
        binding.previewCaption.typeface = typeface
        rows.forEach { (font, row) ->
            row.fontCheck.visibility = if (font == selected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(ContextCompat.getColor(this@FontTestActivity, R.color.metroDivider))
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.fontRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8))
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
