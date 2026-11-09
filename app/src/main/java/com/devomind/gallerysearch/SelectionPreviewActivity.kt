package com.devomind.gallerysearch

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.Transition
import android.transition.TransitionSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.devomind.gallerysearch.databinding.ActivitySelectionPreviewBinding

/**
 * The single-photo preview opened by long-pressing an already-selected tile in multi-select mode.
 *
 * It is intentionally not [ViewerActivity]: there is no chrome, no paging and no actions — one
 * still, pinch/double-tap zoom, and a tap or back gesture to leave. The tile the user is holding is
 * handed over as a shared element (the same "media_" contract the viewer uses) so it expands into
 * the preview rather than cutting to it.
 */
class SelectionPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectionPreviewBinding
    private lateinit var tapDetector: GestureDetector
    private var enterTransitionStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivitySelectionPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        // Tap anywhere to leave. Runs at dispatch level because the zoomable PhotoView consumes
        // every gesture; PhotoView's own double-tap still wins because this waits for confirmation.
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                finish()
                return true
            }
        })

        val uri = intent.getStringExtra(ExtraUri)
        if (uri.isNullOrEmpty()) {
            finish()
            return
        }

        configureSharedElementTransitions()
        val transitionName = intent.getStringExtra(ExtraTransitionName)
        val transitionPreview = ViewerTransitionPreview.take(transitionName)
        if (transitionName != null) {
            ViewCompat.setTransitionName(binding.photoView, transitionName)
            postponeEnterTransition()
            // Glide normally lifts the postponement as soon as the photo is ready. This fallback
            // keeps a cached or recycled target from leaving the preview invisible.
            binding.photoView.postDelayed(
                { startSharedElementEnterTransition() },
                ENTER_TRANSITION_FALLBACK_MS
            )
        }

        loadPhoto(uri, transitionPreview)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun loadPhoto(uri: String, transitionPreview: Drawable?) {
        binding.photoView.resetRotation()
        binding.photoView.setImageDrawable(transitionPreview)
        if (transitionPreview != null) {
            binding.photoView.doOnPreDraw { startSharedElementEnterTransition() }
        }

        val metrics = resources.displayMetrics
        Glide.with(binding.photoView)
            .load(Uri.parse(uri))
            .format(DecodeFormat.PREFER_ARGB_8888)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .override(metrics.widthPixels, metrics.heightPixels)
            .fitCenter()
            .dontAnimate()
            .placeholder(transitionPreview)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    startSharedElementEnterTransition()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    binding.photoView.doOnPreDraw { startSharedElementEnterTransition() }
                    return false
                }
            })
            .into(binding.photoView)
    }

    private fun configureSharedElementTransitions() {
        fun transition() = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(ChangeBounds())
            duration = SHARED_ELEMENT_DURATION_MS
            interpolator = DecelerateInterpolator()
        }
        // ChangeBounds only ever animates the view's bounds. The photo's fitted matrix stays
        // PhotoView's own, and is re-fitted once the transition lands — see the listener below.
        window.sharedElementEnterTransition = transition().also { enter ->
            enter.addListener(object : Transition.TransitionListener {
                override fun onTransitionStart(transition: Transition) = Unit
                override fun onTransitionCancel(transition: Transition) = Unit
                override fun onTransitionPause(transition: Transition) = Unit
                override fun onTransitionResume(transition: Transition) = Unit

                override fun onTransitionEnd(transition: Transition) = refitPhoto()
            })
        }
        window.sharedElementReturnTransition = transition()
    }

    /** Restores PhotoView's fitted scale after the shared-element bounds animation. */
    private fun refitPhoto() {
        binding.photoView.post {
            if (binding.photoView.drawable == null) return@post
            runCatching { binding.photoView.setScale(binding.photoView.minimumScale, false) }
        }
    }

    private fun startSharedElementEnterTransition() {
        if (enterTransitionStarted) return
        enterTransitionStarted = true
        startPostponedEnterTransition()
    }

    /** Immersive edge-to-edge: the photo is the whole screen. */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val ExtraUri = "selection_preview_uri"
        const val ExtraTransitionName = "selection_preview_transition_name"

        private const val SHARED_ELEMENT_DURATION_MS = 180L
        private const val ENTER_TRANSITION_FALLBACK_MS = 220L
    }
}
