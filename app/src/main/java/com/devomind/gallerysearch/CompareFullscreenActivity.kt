package com.devomind.gallerysearch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ActivityCompareFullscreenBinding
import java.io.File
import java.util.Locale

/**
 * Full-screen original vs. compressed preview (launched from [CompressionActivity]).
 *
 * Shows one version at a time full-bleed with pinch-zoom/pan via PhotoView; the bottom tabs and a
 * horizontal swipe toggle between the two with a short crossfade, and the two action buttons
 * settle the decision: "Keep original" discards the staged compressed file, "Keep compressed"
 * leaves the prepared entry exactly as-is (it's already staged, verified and journaled).
 */
class CompareFullscreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompareFullscreenBinding
    private var showingCompressed = false
    private var crossfading = false
    private lateinit var tapDetector: GestureDetector

    private val uri: String get() = intent.getStringExtra(ExtraOriginalUri).orEmpty()
    private val stagingPath: String get() = intent.getStringExtra(ExtraStagingPath).orEmpty()
    private val sizeBefore: Long get() = intent.getLongExtra(ExtraSizeBefore, 0L)
    private val sizeAfter: Long get() = intent.getLongExtra(ExtraSizeAfter, 0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        binding = ActivityCompareFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Must run after binding init — hideSystemBars() touches binding.root for the insets controller.
        hideSystemBars()

        val format = intent.getStringExtra(ExtraFormat).orEmpty()
        binding.tabOriginal.text = "Original · ${formatBytes(sizeBefore)}"
        binding.tabCompressed.text = "Compressed · ${formatBytes(sizeAfter)}" +
            if (format.isNotBlank()) " · $format" else ""
        val saved = sizeBefore - sizeAfter
        val percent = if (sizeBefore > 0) (saved * 100 / sizeBefore).toInt() else 0
        binding.compareSavings.text = "Saves ${formatBytes(saved)} ($percent% smaller)"

        binding.compareBackBtn.setOnClickListener { finish() }
        binding.tabOriginal.setOnClickListener { show(false) }
        binding.tabCompressed.setOnClickListener { show(true) }
        binding.keepOriginalBtn.setOnClickListener {
            setResult(ResultKeepOriginal)
            finish()
        }
        binding.keepCompressedBtn.setOnClickListener {
            setResult(ResultKeepCompressed)
            finish()
        }
        // Original starts highlighted without a crossfade.
        binding.tabOriginal.setTextColor(getColor(R.color.metroTextPrimary))
        binding.tabOriginal.setBackgroundColor(0x33FFFFFF)

        loadInto(binding.comparePhoto, uri)
        applyInsets()

        // Single tap toggles the system bars; a horizontal fling toggles the compared version.
        // Attached to the PhotoView itself (a root listener would never fire — the zoomable child
        // consumes every touch). While zoomed the user is inspecting pixels, so flings are left
        // to PhotoView's pan instead of switching versions.
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (windowInsetsVisible()) hideSystemBars() else showSystemBars()
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (e1 == null || kotlin.math.abs(vx) <= kotlin.math.abs(vy)) return false
                show(!showingCompressed)
                return true
            }
        })
        binding.comparePhoto.setOnTouchListener { _, event ->
            tapDetector.onTouchEvent(event) && binding.comparePhoto.scale <= 1.01f
        }
    }

    /** Immersive edge-to-edge: bars stay hidden; a tap on the photo toggles them. */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyInsets() {
        // The root no longer needs a click listener — tap handling moved to [tapDetector].
    }

    private fun windowInsetsVisible(): Boolean =
        androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)
            ?.isVisible(WindowInsetsCompat.Type.systemBars()) == true

    /** Swaps the displayed version with a crossfade; resets zoom between versions. */
    private fun show(compressed: Boolean) {
        if (showingCompressed == compressed || crossfading) return
        showingCompressed = compressed
        crossfading = true

        binding.tabOriginal.setTextColor(
            if (compressed) getColor(R.color.metroTextSecondary) else getColor(R.color.metroTextPrimary)
        )
        binding.tabOriginal.setBackgroundColor(
            if (compressed) Color.TRANSPARENT else 0x33FFFFFF
        )
        binding.tabCompressed.setTextColor(
            if (compressed) getColor(R.color.metroTextPrimary) else getColor(R.color.metroTextSecondary)
        )
        binding.tabCompressed.setBackgroundColor(
            if (compressed) 0x33FFFFFF else Color.TRANSPARENT
        )

        binding.comparePhoto.animate().alpha(0f).setDuration(110).setListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.comparePhoto.animate().cancel()
                    loadInto(
                        binding.comparePhoto,
                        if (compressed) stagingPath else uri
                    )
                    binding.comparePhoto.animate().alpha(1f).setDuration(110).setListener(null)
                    crossfading = false
                }
            }
        ).start()
    }

    private fun loadInto(view: RotatablePhotoView, source: String) {
        // Reset any zoom/pan from the previous version so each version opens fit-to-screen.
        runCatching { view.setScale(1f, false) }
        if (source.startsWith("http") || !File(source).isAbsolute) {
            Glide.with(this).load(source).fitCenter().into(view)
        } else {
            Glide.with(this).load(File(source)).fitCenter().into(view)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024.0 -> String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.0f MB", mb)
            else -> "${bytes / 1024L} KB"
        }
    }

    companion object {
        const val ExtraOriginalUri = "original_uri"
        const val ExtraStagingPath = "staging_path"
        const val ExtraSizeBefore = "size_before"
        const val ExtraSizeAfter = "size_after"
        const val ExtraFormat = "format"

        /** User chose the original: the caller must discard the staged compressed file. */
        const val ResultKeepOriginal = 101

        /** User chose the compressed version: the staged entry is already prepared — keep it. */
        const val ResultKeepCompressed = 102
    }
}
