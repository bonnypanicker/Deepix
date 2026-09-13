package com.devomind.gallerysearch

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ActivityCompareFullscreenBinding
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Full-screen original vs. compressed preview (launched from [CompressionActivity]).
 *
 * Two stacked [RotatablePhotoView]s each keep their own pinch-zoom/pan — gestures are observed at
 * the ACTIVITY's dispatchTouchEvent, never by replacing a PhotoView's touch listener (PhotoView's
 * attacher IS its touch listener; overriding it uninstalls zoom, which is how this screen
 * originally shipped non-zoomable).
 *
 * A horizontal drag at fit scale slides the top version aside finger-follow style to reveal the
 * other; release past a quarter of the width (or a fling) commits the swap. The top label names
 * the shown version and its size; the keep/discard buttons settle the decision.
 */
class CompareFullscreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompareFullscreenBinding
    private var showingCompressed = false

    private lateinit var tapDetector: GestureDetector
    private var velocityTracker: VelocityTracker? = null
    private var swipeStartX = Float.MIN_VALUE
    private var swipeStartY = Float.MIN_VALUE
    private var swipeDragging = false
    private var swipeDirection = 0

    private val uri: String get() = intent.getStringExtra(ExtraOriginalUri).orEmpty()
    private val stagingPath: String get() = intent.getStringExtra(ExtraStagingPath).orEmpty()
    private val sizeBefore: Long get() = intent.getLongExtra(ExtraSizeBefore, 0L)
    private val sizeAfter: Long get() = intent.getLongExtra(ExtraSizeAfter, 0L)
    private val format: String get() = intent.getStringExtra(ExtraFormat).orEmpty()

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

        binding.photoOriginal.loadIntoFit(uri)
        binding.photoCompressed.loadIntoFit(stagingPath)
        renderLabel()

        binding.compareBackBtn.setOnClickListener { finish() }
        binding.keepOriginalBtn.setOnClickListener {
            setResult(ResultKeepOriginal)
            finish()
        }
        binding.keepCompressedBtn.setOnClickListener {
            setResult(ResultKeepCompressed)
            finish()
        }

        // Single tap anywhere toggles the system bars. Runs inside dispatchTouchEvent — the one
        // hook that still fires even though the zoomable PhotoView consumes every gesture.
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (windowInsetsVisible()) hideSystemBars() else showSystemBars()
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Order matters: the arbiter sees the stream FIRST so it can claim horizontal drags at
        // fit scale before the PhotoView below starts consuming them.
        observeGestures(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun observeGestures(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
                swipeStartX = ev.x
                swipeStartY = ev.y
                swipeDragging = false
                swipeDirection = 0
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means pinch-zoom/rotate territory: abandon any in-flight swipe.
                if (swipeDragging) springBack()
                swipeDragging = false
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (swipeDragging) {
                    translateTop(ev.x - swipeStartX)
                } else if (ev.pointerCount == 1 && swipeStartX != Float.MIN_VALUE) {
                    val dx = ev.x - swipeStartX
                    val dy = ev.y - swipeStartY
                    if (abs(dx) > swipeSlopPx && abs(dx) > abs(dy) * 1.5f && topView().isAtFitScale()) {
                        swipeDragging = true
                        swipeDirection = if (dx > 0f) 1 else -1
                        // Revoke the in-flight gesture from the PhotoView: without the cancel the
                        // attacher keeps acting on the photo while we slide the whole view.
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        topView().dispatchTouchEvent(cancel)
                        cancel.recycle()
                        translateTop(dx)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(ev)
                velocityTracker?.computeCurrentVelocity(1000, MAX_TRACKED_VELOCITY)
                val vx = velocityTracker?.xVelocity ?: 0f
                if (swipeDragging) {
                    val dx = ev.x - swipeStartX
                    if (abs(dx) > width() / 4f || abs(vx) > flingVelocityPx) {
                        commitSwap(dx)
                    } else {
                        springBack()
                    }
                }
                resetSwipe()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (swipeDragging) springBack()
                resetSwipe()
            }
            else -> velocityTracker?.addMovement(ev)
        }
        tapDetector.onTouchEvent(ev)
    }

    /** Live finger-follow: the top view slides toward the side the finger pulls it, slightly fading. */
    private fun translateTop(dxRaw: Float) {
        // Clamp to the direction first chosen: dragging back past origin re-covers the other
        // version (dx toward 0) instead of sliding it out the opposite side.
        val dx = if (swipeDirection >= 0) dxRaw.coerceAtLeast(0f) else dxRaw.coerceAtMost(0f)
        topView().translationX = dx
        topView().alpha = 1f - (abs(dx) / width()).coerceIn(0f, 0.35f)
    }

    /** Release: animate the top view fully off-screen, then bring the other version forward. */
    private fun commitSwap(dx: Float) {
        val escaped = (if (dx >= 0f) 1 else -1) * width().toFloat()
        val top = topView()
        top.animate()
            .translationX(escaped)
            .alpha(0f)
            .setDuration(SWAP_ANIM_MS)
            .withEndAction {
                top.translationX = 0f
                top.alpha = 1f
                showingCompressed = !showingCompressed
                // Keep the chrome (label, back, bottom bar) above the swapped-in photo.
                if (showingCompressed) binding.photoCompressed.bringToFront() else binding.photoOriginal.bringToFront()
                binding.compareSavings.bringToFront()
                binding.compareBackBtn.bringToFront()
                binding.bottomBar.bringToFront()
                renderLabel()
            }
            .start()
    }

    /** Release (or cancel) without committing: slide the top view back over. */
    private fun springBack() {
        topView().animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(SPRING_ANIM_MS)
            .start()
    }

    /** Header labels the version currently on top; savings show while the compressed one is up. */
    private fun renderLabel() {
        binding.compareSavings.text = if (showingCompressed) {
            val suffix = if (format.isNotBlank()) " · $format" else ""
            val saved = (sizeBefore - sizeAfter).coerceAtLeast(0L)
            val percent = if (sizeBefore > 0) (saved * 100 / sizeBefore).toInt() else 0
            "Compressed · ${formatBytes(sizeAfter)}$suffix  ·  saves ${formatBytes(saved)} ($percent% smaller)"
        } else {
            "Original · ${formatBytes(sizeBefore)}  ·  swipe to compare"
        }
    }

    private fun topView(): RotatablePhotoView =
        if (showingCompressed) binding.photoCompressed else binding.photoOriginal

    private fun resetSwipe() {
        velocityTracker?.recycle()
        velocityTracker = null
        swipeStartX = Float.MIN_VALUE
        swipeStartY = Float.MIN_VALUE
        swipeDragging = false
        swipeDirection = 0
    }

    private fun width(): Int = binding.compareRoot.width

    private val swipeSlopPx: Float get() = SWIPE_SLOP_DP * resources.displayMetrics.density
    private val flingVelocityPx: Float get() = FLING_VELOCITY_DP * resources.displayMetrics.density

    /** True when the photo sits at its fitted (un-zoomed) scale — swipes are only claimed there. */
    private fun RotatablePhotoView.isAtFitScale(): Boolean =
        runCatching { abs(scale - minimumScale) < 0.01f }.getOrDefault(false)

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

    private fun windowInsetsVisible(): Boolean =
        ViewCompat.getRootWindowInsets(binding.root)
            ?.isVisible(WindowInsetsCompat.Type.systemBars()) == true

    /** Loads the full image fit-centered; the compressed file loads from its staging path. */
    private fun RotatablePhotoView.loadIntoFit(source: String) {
        if (source.isBlank()) return
        if (source.startsWith("http") || !File(source).isAbsolute) {
            Glide.with(this@CompareFullscreenActivity).load(source).fitCenter().into(this)
        } else {
            Glide.with(this@CompareFullscreenActivity).load(File(source)).fitCenter().into(this)
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

        private const val SWIPE_SLOP_DP = 24f
        private const val FLING_VELOCITY_DP = 2000f
        private const val MAX_TRACKED_VELOCITY = 10000f
        private const val SWAP_ANIM_MS = 190L
        private const val SPRING_ANIM_MS = 180L
    }
}
