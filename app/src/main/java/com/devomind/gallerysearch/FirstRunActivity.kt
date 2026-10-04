package com.devomind.gallerysearch

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.devomind.gallerysearch.databinding.ActivityFirstRunBinding
import kotlin.math.min

/**
 * First-run tour: what Pixa does, one page per feature, ending on the permissions the app is about
 * to ask for. Cloned from the reference mock, which lays out at 360x760, and redrawn at the actual
 * screen's ratio — see [scaleFactor] and [OnboardingMetrics].
 *
 * The tour runs *before* the runtime permission prompts on purpose: it promises the library is never
 * uploaded, and the system dialog for reading it is the very next thing the user sees. The flag is
 * marked only when the user leaves the tour, so a process death mid-way shows it again instead of
 * silently skipping it.
 */
class FirstRunActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFirstRunBinding
    private lateinit var metrics: OnboardingMetrics
    private var pageCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityFirstRunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        metrics = OnboardingMetrics(scaleFactor(), resources.displayMetrics.density)
        applyChromeScale()

        val panels = onboardingPanels()
        pageCount = panels.size
        buildProgressTrack(panels.size)
        // ViewPager2 doesn't report its opening page, so paint the first segment here and let the
        // post below settle on the restored page if this is a rotation.
        syncChrome(0)
        binding.panels.adapter = OnboardingPanelAdapter(panels, metrics)
        binding.panels.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = syncChrome(position)
        })

        binding.btnNext.setOnClickListener {
            val current = binding.panels.currentItem
            if (current >= pageCount - 1) complete() else goTo(current + 1)
        }
        // The reference's X jumps to the permissions page rather than closing the tour: that page is
        // what explains the dialog coming next, so it is the one thing not to skip past.
        binding.dismissBtn.setOnClickListener { goTo(pageCount - 1) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = binding.panels.currentItem
                if (current > 0) goTo(current - 1) else complete()
            }
        })
        // A rotation restores the pager's page only after its first layout, so re-sync the footer to
        // whatever it lands on rather than leaving "Next" over a filled bar.
        binding.panels.post { syncChrome(binding.panels.currentItem) }
    }

    private fun goTo(position: Int) {
        binding.panels.setCurrentItem(position.coerceIn(0, pageCount - 1), true)
    }

    private fun complete() {
        IndexPreferences.setFirstRunDone(this, true)
        finish()
    }

    /** Segments fill left to right as the tour is read; the CTA relabels once the last page shows. */
    private fun syncChrome(position: Int) {
        val accent = DesignTokens.accent(this)
        val resting = ContextCompat.getColor(this, R.color.metroBgCard)
        for (index in 0 until binding.progressTrack.childCount) {
            binding.progressTrack.getChildAt(index)
                .setBackgroundColor(if (index <= position) accent else resting)
        }
        binding.btnNext.setText(
            if (position >= pageCount - 1) R.string.onboarding_get_started else R.string.onboarding_next
        )
    }

    /** One segment per page, weighted equally with the reference's 3-unit gap between them. */
    private fun buildProgressTrack(count: Int) {
        binding.progressTrack.removeAllViews()
        val gap = metrics.units(3f)
        val height = metrics.units(2f)
        repeat(count) { index ->
            binding.progressTrack.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, height, 1f).apply {
                    if (index < count - 1) marginEnd = gap
                }
            })
        }
    }

    /** The brand line, its dismiss target, the footer block and the CTA at this screen's ratio. */
    private fun applyChromeScale() {
        binding.brandRow.layoutParams = binding.brandRow.layoutParams.apply {
            height = metrics.units(46f)
        }
        binding.brandRow.setPadding(
            metrics.units(20f), metrics.units(14f), metrics.units(12f), 0
        )
        binding.brandRow.requestLayout()
        binding.brandText.setTextSize(metrics.textSp(11f))

        binding.dismissBtn.layoutParams = binding.dismissBtn.layoutParams.apply {
            width = metrics.units(40f)
            height = metrics.units(40f)
        }
        val dismissPad = metrics.units(12f)
        binding.dismissBtn.setPadding(dismissPad, dismissPad, dismissPad, dismissPad)
        binding.dismissBtn.requestLayout()

        binding.footer.setPadding(
            metrics.units(28f), metrics.units(4f), metrics.units(28f), metrics.units(26f)
        )
        binding.btnNext.layoutParams = (binding.btnNext.layoutParams as LinearLayout.LayoutParams).apply {
            height = metrics.units(48f)
            topMargin = metrics.units(22f)
        }
        binding.btnNext.setTextSize(metrics.textSp(13f))
        binding.btnNext.requestLayout()
    }

    /**
     * Scale with the tighter axis: width on a narrow phone, and the panel column's height on a short
     * or landscape one — the ~150 units of brand row plus footer come off first, because those are
     * scaled by the same factor. Clamped so a tablet reads as a generous tour rather than a blown-up
     * phone, and a small phone never clips a page.
     */
    private fun scaleFactor(): Float {
        val density = resources.displayMetrics.density
        val widthDp = resources.displayMetrics.widthPixels / density
        val panelHeightDp = resources.displayMetrics.heightPixels / density - 150f
        return min(widthDp / 360f, panelHeightDp / 600f).coerceIn(0.72f, 1.3f)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    companion object {
        /** Until the user has been through the tour once, it owns the cold start. */
        fun shouldShow(context: Context): Boolean = !IndexPreferences.hasSeenFirstRun(context)

        fun intent(context: Context): Intent = Intent(context, FirstRunActivity::class.java)
    }
}
