package com.devomind.gallerysearch

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.TextView
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
 * A horizontal drag at fit scale slides BOTH panes finger-follow style — the top one toward the
 * pull, the incoming one in from the opposite edge, like a two-page pager — and release past a
 * quarter of the width (or a fling) commits the swap; anything less springs back. The two-pane
 * header (Original | Compressed, each with size + active indicator) reflects and controls the
 * shown version: tapping a pane slides it in. The keep/discard buttons settle the decision.
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
        renderHeader()

        binding.compareBackBtn.setOnClickListener { finish() }
        binding.keepOriginalBtn.setOnClickListener {
            setResult(ResultKeepOriginal)
            finish()
        }
        binding.keepCompressedBtn.setOnClickListener {
            setResult(ResultKeepCompressed)
            finish()
        }
        // Header pans double as jump shortcuts: tapping one slides that version in pager-style.
        binding.headerOriginal.setOnClickListener { jumpTo(showCompressed = false) }
        binding.headerCompressed.setOnClickListener { jumpTo(showCompressed = true) }

        // Single tap anywhere toggles the system bars. Runs inside dispatchTouchEvent — the one
        // hook that still fires even though the zoomable PhotoView consumes every gesture.
        // Taps on the header/bottom-bar chrome are excluded: those views handle their own clicks.
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isTapOnChrome(e)) return true
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

    /**
     * Live finger-follow: both panes travel together like a pager — the top one slides toward the
     * side the finger pulls while the incoming one rides in from the opposite edge (translation
     * exactly -width at the start of the drag, 0 when the drag fully crosses the screen). The old
     * transition only moved the top pane, so the incoming photo sat static and the swap read as a
     * reveal, not a slide.
     */
    private fun translateTop(dxRaw: Float) {
        // Clamp to the direction first chosen: dragging back past origin re-covers the other
        // version (dx toward 0) instead of sliding it out the opposite side.
        val dir = swipeDirection
        val dx = if (dir >= 0) dxRaw.coerceAtLeast(0f) else dxRaw.coerceAtMost(0f)
        val w = width().toFloat()
        topView().translationX = dx
        incomingView().translationX = -dir * w + dx
        topView().alpha = 1f - (abs(dx) / w).coerceIn(0f, 0.35f)
    }

    /** Drag released past the threshold: finish sliding to the other version. */
    private fun commitSwap(dx: Float) {
        // Reveal direction matches the drag: a rightward pull (dir=+1) sends the top view off to
        // the right while the incoming one arrives from the left edge.
        swapTo(showCompressed = !showingCompressed, revealDir = if (dx >= 0f) 1 else -1)
    }

    /** Header tap: park the target off the edge of its header side, then run the pager slide. */
    private fun jumpTo(showCompressed: Boolean) {
        if (showingCompressed == showCompressed) return
        // Compressed sits on the right of the header → slides in from the right; Original from
        // the left. Same revealDir convention as a drag of the matching direction.
        val revealDir = if (showCompressed) -1 else 1
        incomingView().translationX = -revealDir * width().toFloat()
        swapTo(showCompressed, revealDir)
    }

    /**
     * Animate to [showCompressed]: the top view exits toward revealDir * width while the incoming
     * view — already finger-followed to mid-slide, or parked off-screen by [jumpTo] — rides in to
     * translation 0. Resets both views and finalizes z-order once the incoming animation lands.
     */
    private fun swapTo(showCompressed: Boolean, revealDir: Int) {
        if (showingCompressed == showCompressed) return
        val w = width().toFloat()
        val top = topView()
        val incoming = incomingView()
        incoming.alpha = 1f
        top.animate()
            .translationX(revealDir * w)
            .alpha(0f)
            .setDuration(SWAP_ANIM_MS)
            .start()
        incoming.animate()
            .translationX(0f)
            .setDuration(SWAP_ANIM_MS)
            .withEndAction {
                top.translationX = 0f
                top.alpha = 1f
                showingCompressed = showCompressed
                applyChromeZOrder()
                renderHeader()
            }
            .start()
    }

    /** Release (or cancel) without committing: both panes return to their pre-drag positions. */
    private fun springBack() {
        val dir = swipeDirection
        val w = width().toFloat()
        topView().animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(SPRING_ANIM_MS)
            .start()
        incomingView().animate()
            .translationX(-dir * w)
            .setDuration(SPRING_ANIM_MS)
            .start()
    }

    private fun incomingView(): RotatablePhotoView =
        if (showingCompressed) binding.photoOriginal else binding.photoCompressed

    /** Chrome (scrim, header, back, bottom bar) must stay above whichever photo is on top. */
    private fun applyChromeZOrder() {
        if (showingCompressed) binding.photoCompressed.bringToFront() else binding.photoOriginal.bringToFront()
        binding.headerScrim.bringToFront()
        binding.compareHeader.bringToFront()
        binding.compareBackBtn.bringToFront()
        binding.bottomBar.bringToFront()
    }

    /** Taps on the header panes or bottom bar belong to those views, not the bars toggle. */
    private fun isTapOnChrome(e: MotionEvent): Boolean =
        isPointIn(binding.compareHeader, e) || isPointIn(binding.bottomBar, e)

    private fun isPointIn(view: View, e: MotionEvent): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return e.rawX >= loc[0] && e.rawX <= loc[0] + view.width &&
            e.rawY >= loc[1] && e.rawY <= loc[1] + view.height
    }

    /** Header reflects the version on top: the active pane gets a bright title and accent bar. */
    private fun renderHeader() {
        styleHeaderPane(
            title = binding.headerOriginalTitle,
            size = binding.headerOriginalSize,
            indicator = binding.indicatorOriginal,
            active = !showingCompressed,
            sizeLabel = formatBytes(sizeBefore)
        )
        val saved = (sizeBefore - sizeAfter).coerceAtLeast(0L)
        val percent = if (sizeBefore > 0) (saved * 100 / sizeBefore).toInt() else 0
        val suffix = if (format.isNotBlank()) " $format" else ""
        styleHeaderPane(
            title = binding.headerCompressedTitle,
            size = binding.headerCompressedSize,
            indicator = binding.indicatorCompressed,
            active = showingCompressed,
            sizeLabel = "${formatBytes(sizeAfter)}$suffix · -$percent%"
        )
    }

    private fun styleHeaderPane(
        title: TextView,
        size: TextView,
        indicator: View,
        active: Boolean,
        sizeLabel: String
    ) {
        val dim = getColor(R.color.metroTextSecondary)
        title.setTextColor(if (active) getColor(R.color.metroTextPrimary) else dim)
        size.text = sizeLabel
        size.setTextColor(if (active) ACTIVE_SIZE_COLOR else dim)
        indicator.setBackgroundColor(AccentPalette.solid(this))
        indicator.visibility = if (active) View.VISIBLE else View.INVISIBLE
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

        /** Size text of the active header pane — white at 80% over the scrim. */
        private val ACTIVE_SIZE_COLOR = 0xCCFFFFFF.toInt()
    }
}
