package com.devomind.gallerysearch

import android.app.RecoverableSecurityException
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.devomind.gallerysearch.databinding.ActivityViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class ViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityViewerBinding
    private lateinit var adapter: MediaPagerAdapter
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var dbRepository: DbRepository
    private var items = mutableListOf<GalleryRepository.MediaItem>()
    private var currentPosition = 0
    private var controlsVisible = true
    private var infoVisible = false
    private var contentChanged = false
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
    private val topBarDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { setControlsVisible(false) }
    private var downY = 0f
    private var downX = 0f
    private var dragDistance = 0f
    private var draggingToDismiss = false
    private var isScrubbing = false
    private var velocityTracker: VelocityTracker? = null
    private var pendingDeleteUri: Uri? = null
    private var pendingDeleteNeedsRetry = false
    private var currentExif: ExifData? = null
    private var currentTags: List<com.devomind.gallerysearch.db.TagEntity> = emptyList()
    private var gestureDirection = GestureDirection.UNDETERMINED
    private var metadataJob: kotlinx.coroutines.Job? = null

    private enum class GestureDirection {
        UNDETERMINED, HORIZONTAL_PAGE, VERTICAL_DISMISS, VERTICAL_INFO, TAP
    }

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val targetUri = pendingDeleteUri
        val needsRetry = pendingDeleteNeedsRetry
        pendingDeleteUri = null
        pendingDeleteNeedsRetry = false

        if (result.resultCode != RESULT_OK || targetUri == null) return@registerForActivityResult
        if (needsRetry) {
            deletePhoto(targetUri, afterApproval = true)
        } else {
            onDeleteCompleted(targetUri)
        }
    }

    private var previousPosition = -1

    private val pageChangeCallback = object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            if (previousPosition >= 0 && previousPosition != position) {
                getPageViewHolder(previousPosition)?.pausePlayback()
            }
            previousPosition = position
            currentPosition = position
            bindPage(position)
            getPageViewHolder(position)?.startPlayback()
        }
    }

    private var touchIntercepted = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchIntercepted = false
        }

        // Skip the global gesture state machine entirely while the info panel
        // is visible — attachInfoPanelDrag already owns all touches in that state,
        // and ViewPager2 paging is disabled, so there is nothing for this to do.
        if (!infoVisible) {
            handleViewerTouch(ev)
        }

        if (draggingToDismiss) {
            if (!touchIntercepted) {
                touchIntercepted = true
                val cancelEvent = MotionEvent.obtain(ev)
                cancelEvent.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancelEvent)
                cancelEvent.recycle()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureEdgeToEdge()

        val markerUri = intent.getStringExtra(ExtraMarker)
        if (markerUri != null) {
            ViewerItemsHolder.retrieve(markerUri)?.let { items.addAll(it) }
            ViewerItemsHolder.release()
        }
        if (items.isEmpty()) {
            val parcelables = intent.getParcelableArrayListExtra<GalleryRepository.MediaItem>(ExtraItems)
            if (parcelables != null) {
                items.addAll(parcelables)
            }
        }
        currentPosition = intent.getIntExtra(ExtraPosition, 0)
        val transitionName = intent.getStringExtra(ExtraTransitionName)

        if (items.isEmpty() || currentPosition !in items.indices) {
            finish()
            return
        }

        favoritesStore = FavoritesStore(this)
        dbRepository = DbRepository(this)

        if (transitionName != null) {
            postponeEnterTransition()
        }

        adapter = MediaPagerAdapter(
            items = items,
            initialPosition = currentPosition,
            initialTransitionName = transitionName,
            onInitialImageLoaded = {
                startPostponedEnterTransition()
            },
            onMediaTap = {
                if (gestureDirection == GestureDirection.HORIZONTAL_PAGE) {
                    // A completed page-swipe can still fire a click on some devices;
                    // ignore taps that were actually part of a paging gesture.
                } else if (infoVisible) {
                    toggleInfoPanel()
                } else if (!draggingToDismiss) {
                    toggleControls()
                }
            },
            onMediaLongClick = {
                val item = items.getOrNull(currentPosition) ?: return@MediaPagerAdapter
                TagPickerDialog(
                    context = this,
                    lifecycleOwner = this,
                    dbRepository = dbRepository,
                    mediaUri = item.uri.toString()
                ) {
                    lifecycleScope.launch {
                        val tags = withContext(Dispatchers.IO) {
                            dbRepository.getTagsForMedia(item.uri.toString())
                        }
                        currentTags = tags
                        bindMetadataTags(tags)
                    }
                }.show()
            },
            onVideoCompleted = {
                binding.editBtn.setImageResource(R.drawable.ic_fluent_play_24_regular)
                binding.editBtn.contentDescription = "Play video"
                setControlsVisible(true)
            },
            onScrubbingChanged = { scrubbing ->
                isScrubbing = scrubbing
                if (scrubbing) {
                    autoHideHandler.removeCallbacks(autoHideRunnable)
                } else {
                    scheduleAutoHide()
                }
            }
        )
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(currentPosition, false)
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)

        bindPage(currentPosition)
        bindGlobalActions()
        attachInfoPanelDrag()

        binding.infoPanel.post {
            binding.infoPanel.translationY = binding.infoPanel.height.toFloat()
        }

        scheduleAutoHide()
    }

    private fun configureEdgeToEdge() {
        configureCutoutMode()
        hideStatusBar()
        ViewCompat.setOnApplyWindowInsetsListener(binding.viewerRoot) { _, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topBar.updatePadding(top = systemInsets.top)

            val pillParams = binding.viewerPill.layoutParams as android.widget.FrameLayout.LayoutParams
            pillParams.bottomMargin = systemInsets.bottom + dp(24)
            binding.viewerPill.layoutParams = pillParams

            binding.infoPanel.updatePadding(
                left = binding.infoPanel.paddingLeft,
                top = binding.infoPanel.paddingTop,
                right = binding.infoPanel.paddingRight,
                bottom = systemInsets.bottom + dp(36)
            )
            insets
        }
    }

    private fun configureCutoutMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val params = window.attributes
        params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = params
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, binding.root)?.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun bindPage(position: Int) {
        val item = items.getOrNull(position) ?: return
        val uri = item.uri
        val isVideo = item.mediaType == GalleryRepository.MediaType.Video

        binding.fileNameText.text = item.displayName ?: "Photo"
        bindDots(position, items.size)
        renderFavoriteState(favoritesStore.isFavorite(uri))

        binding.editBtn.setImageResource(
            if (isVideo) R.drawable.ic_fluent_play_24_regular else R.drawable.ic_fluent_edit_24_regular
        )
        binding.editBtn.contentDescription = if (isVideo) "Play video" else "Edit photo"

        // Cancel any in-flight metadata load from a previous page before starting a new one.
        metadataJob?.cancel()
        metadataJob = lifecycleScope.launch {
            val metadata = withContext(Dispatchers.IO) { loadMetadata(uri, isVideo) }
            val exif = withContext(Dispatchers.IO) {
                val cached = dbRepository.getExif(uri.toString())
                if (cached != null || isVideo) {
                    cached
                } else {
                    val extracted = ExifExtractor.extract(this@ViewerActivity, uri)
                    dbRepository.upsertExif(uri.toString(), extracted)
                    extracted
                }
            }
            val tags = withContext(Dispatchers.IO) {
                dbRepository.getTagsForMedia(uri.toString())
            }

            // Guard against a final race: if this job wasn't cancelled in time but the
            // page has already moved on by the time we reach here, drop the stale result.
            if (position != currentPosition) return@launch

            currentExif = exif
            currentTags = tags
            bindMetadata(metadata, exif, tags)
        }

        // Force-close the info panel without animation so it never carries over between pages.
        infoVisible = false
        binding.viewPager.isUserInputEnabled = true
        binding.infoPanel.translationY = binding.infoPanel.height.toFloat()
        binding.infoPanel.alpha = 1f

        setControlsVisible(true)
        scheduleAutoHide()
    }

    private fun bindDots(position: Int, count: Int) {
        binding.dotIndicator.removeAllViews()
        if (count <= 1) return
        val maxDots = 20
        val start = (position - maxDots / 2).coerceAtLeast(0).coerceAtMost((count - maxDots).coerceAtLeast(0))
        val end = (start + maxDots).coerceAtMost(count)
        
        for (i in start until end) {
            val view = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    marginEnd = dp(4)
                    marginStart = dp(4)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == position) Color.WHITE else Color.parseColor("#4DFFFFFF"))
                }
            }
            binding.dotIndicator.addView(view)
        }
    }

    private fun bindGlobalActions() {
        binding.backBtn.setOnClickListener { supportFinishAfterTransition() }
        binding.shareBtn.setOnClickListener { shareCurrent() }
        binding.wallpaperBtn.setOnClickListener {
            val item = items.getOrNull(currentPosition) ?: return@setOnClickListener
            setAsWallpaper(item)
        }
        binding.favoriteBtn.setOnClickListener {
            val item = items.getOrNull(currentPosition) ?: return@setOnClickListener
            val isFavorite = favoritesStore.toggle(item.uri)
            contentChanged = true
            renderFavoriteState(isFavorite)
        }
        binding.editBtn.setOnClickListener {
            val item = items.getOrNull(currentPosition) ?: return@setOnClickListener
            if (item.mediaType == GalleryRepository.MediaType.Video) {
                toggleVideoPlayback()
            } else {
                edit(item.uri)
            }
        }
        binding.deleteBtn.setOnClickListener {
            val item = items.getOrNull(currentPosition) ?: return@setOnClickListener
            confirmDelete(item.uri)
        }
    }

    private fun shareCurrent() {
        val item = items.getOrNull(currentPosition) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (item.mediaType == GalleryRepository.MediaType.Video) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share photo"))
    }

    private fun toggleVideoPlayback() {
        val holder = getCurrentPageViewHolder() ?: return
        if (holder.isPlaying()) {
            holder.pausePlayback()
            binding.editBtn.setImageResource(R.drawable.ic_fluent_play_24_regular)
            binding.editBtn.contentDescription = "Play video"
            setControlsVisible(true)
        } else {
            holder.startPlayback()
            binding.editBtn.setImageResource(R.drawable.ic_fluent_pause_24_regular)
            binding.editBtn.contentDescription = "Pause video"
            scheduleAutoHide()
        }
    }

    private fun getPageViewHolder(position: Int): MediaPagerAdapter.PageViewHolder? {
        val recyclerView = binding.viewPager.getChildAt(0) as? RecyclerView ?: return null
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val holder = recyclerView.getChildViewHolder(child) as? MediaPagerAdapter.PageViewHolder
            if (holder != null && recyclerView.layoutManager?.getPosition(child) == position) {
                return holder
            }
        }
        return null
    }

    private fun getCurrentPageViewHolder(): MediaPagerAdapter.PageViewHolder? {
        return getPageViewHolder(binding.viewPager.currentItem)
    }

    private fun isCurrentPageZoomed(): Boolean {
        return getCurrentPageViewHolder()?.isZoomed() == true
    }

    private fun bindMetadata(metadata: PhotoMetadata, exif: ExifData?, tags: List<com.devomind.gallerysearch.db.TagEntity>) {
        binding.fileNameText.text = metadata.displayName ?: "Photo"
        if (metadata.dateMillis > 0L) {
            binding.topBarDate.visibility = View.VISIBLE
            binding.topBarDate.text = topBarDateFormat.format(Date(metadata.dateMillis))
        } else {
            binding.topBarDate.visibility = View.GONE
        }
        binding.infoDate.text = if (metadata.dateMillis > 0L) {
            dateFormat.format(Date(metadata.dateMillis))
        } else {
            "Date unknown"
        }

        if (metadata.durationMillis > 0L) {
            binding.infoDurationRow.visibility = View.VISIBLE
            binding.infoDuration.text = "Duration  ·  ${formatDuration(metadata.durationMillis) ?: "—"}"
        } else {
            binding.infoDurationRow.visibility = View.GONE
        }
        binding.infoSummary.text = buildString {
            if (metadata.width > 0 && metadata.height > 0) {
                append("${metadata.width} x ${metadata.height}")
            } else {
                append("Resolution unknown")
            }
            append("  ·  ")
            append(formatSize(metadata.sizeBytes))
            append("  ·  ")
            append(metadata.mimeType?.substringAfter("/")?.uppercase(Locale.getDefault()) ?: "IMAGE")
        }

        if (exif?.hasCameraInfo == true) {
            binding.infoCamera.visibility = View.VISIBLE
            binding.infoCamera.text = formatCameraLine(exif)
        } else {
            binding.infoCamera.visibility = View.GONE
            binding.infoCamera.text = ""
        }

        if (metadata.locationName.isNullOrBlank()) {
            binding.infoLocationRow.visibility = View.GONE
            binding.infoLocation.text = ""
        } else {
            binding.infoLocationRow.visibility = View.VISIBLE
            binding.infoLocation.text = metadata.locationName
        }

        bindMetadataTags(tags)
    }

    private fun bindMetadataTags(tags: List<com.devomind.gallerysearch.db.TagEntity>) {
        binding.infoTags.removeAllViews()
        if (tags.isEmpty()) {
            binding.infoTags.visibility = View.GONE
        } else {
            binding.infoTags.visibility = View.VISIBLE
            tags.take(6).forEach { tag ->
                binding.infoTags.addView(createTagChip(tag.name, tag.color))
            }
        }
    }

    private fun createTagChip(name: String, color: Int): TextView {
        return TextView(this).apply {
            text = name
            textSize = 12f
            setTextColor(color)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = android.content.res.ColorStateList.valueOf(color).let { _ ->
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), color)
                    setColor(android.graphics.Color.parseColor("#0A0A0A"))
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
        }
    }

    private fun formatCameraLine(exif: ExifData): String {
        return buildString {
            val camera = listOfNotNull(exif.make, exif.model).joinToString(" ")
            if (camera.isNotBlank()) append(camera)
            exif.focalLength?.let {
                if (isNotEmpty()) append("  ·  ")
                append("${it.roundToInt()} mm")
            }
            exif.fNumber?.let {
                if (isNotEmpty()) append("  ·  ")
                append("f/${it}")
            }
            exif.exposureTime?.let {
                if (isNotEmpty()) append("  ·  ")
                append(formatExposureTime(it))
            }
            exif.iso?.let {
                if (isNotEmpty()) append("  ·  ")
                append("ISO $it")
            }
        }
    }

    private fun formatExposureTime(seconds: Double): String {
        return if (seconds >= 1.0) {
            String.format(Locale.getDefault(), "%.1fs", seconds)
        } else {
            val denominator = (1.0 / seconds).roundToInt()
            "1/$denominator s"
        }
    }

    private fun renderFavoriteState(isFavorite: Boolean) {
        binding.favoriteBtn.setImageResource(
            if (isFavorite) R.drawable.ic_fluent_heart_24_filled else R.drawable.ic_fluent_heart_24_regular
        )
        binding.favoriteBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            if (isFavorite) Color.parseColor("#FF6B8A") else Color.WHITE
        )
    }

    private fun toggleControls() {
        setControlsVisible(!controlsVisible)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val targetAlpha = if (visible) 1f else 0f
        val topTranslation = if (visible) 0f else -dp(20).toFloat()
        val pillTranslation = if (visible) 0f else dp(20).toFloat()
        binding.topBar.animate().alpha(targetAlpha).translationY(topTranslation).setDuration(220).start()
        binding.viewerPill.animate().alpha(targetAlpha).translationY(pillTranslation).setDuration(220).start()
        binding.bottomGradient.animate().alpha(targetAlpha).setDuration(220).start()
        syncScrubber()
        if (visible) {
            scheduleAutoHide()
        } else {
            autoHideHandler.removeCallbacks(autoHideRunnable)
        }
    }

    private fun currentIsVideo(): Boolean =
        items.getOrNull(currentPosition)?.mediaType == GalleryRepository.MediaType.Video

    private fun syncScrubber() {
        getCurrentPageViewHolder()?.setScrubberVisible(controlsVisible && currentIsVideo())
    }

    private fun toggleInfoPanel() {
        infoVisible = !infoVisible
        binding.viewPager.isUserInputEnabled = !infoVisible
        val target = if (infoVisible) 0f else binding.infoPanel.height.toFloat()
        SpringAnimation(binding.infoPanel, DynamicAnimation.TRANSLATION_Y, target).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
        scheduleAutoHide()
    }

    private var infoPanelDownY = 0f
    private var infoPanelDragging = false

    @Suppress("ClickableViewAccessibility")
    private fun attachInfoPanelDrag() {
        binding.infoPanel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    infoPanelDownY = event.rawY
                    infoPanelDragging = false
                    autoHideHandler.removeCallbacks(autoHideRunnable)
                    // Consume immediately — this view should own its own touch stream
                    // from the first frame, not just once a threshold is crossed.
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - infoPanelDownY
                    if (deltaY > dp(4) || infoPanelDragging) {
                        infoPanelDragging = true
                        val offset = deltaY.coerceAtLeast(0f)
                        binding.infoPanel.translationY = offset
                        val progress = (offset / binding.infoPanel.height.toFloat()).coerceIn(0f, 1f)
                        binding.infoPanel.alpha = 1f - progress * 0.5f
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_UP -> {
                    val deltaY = event.rawY - infoPanelDownY
                    if (infoPanelDragging && deltaY > binding.infoPanel.height * 0.25f) {
                        infoVisible = false
                        binding.viewPager.isUserInputEnabled = true
                        animateInfoPanelClosed()
                    } else if (infoPanelDragging) {
                        SpringAnimation(binding.infoPanel, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                            start()
                        }
                        binding.infoPanel.alpha = 1f
                    } else {
                        // A tap that landed on the info panel but wasn't a drag —
                        // treat it the same as tapping the photo: close the panel.
                        infoVisible = false
                        binding.viewPager.isUserInputEnabled = true
                        animateInfoPanelClosed()
                    }
                    infoPanelDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun animateInfoPanelClosed() {
        SpringAnimation(binding.infoPanel, DynamicAnimation.TRANSLATION_Y, binding.infoPanel.height.toFloat()).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
        binding.infoPanel.animate().alpha(1f).setDuration(200).start()
    }

    private fun scheduleAutoHide() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
        val isVideoPlaying = getCurrentPageViewHolder()?.isPlaying() == true
        if (controlsVisible && !infoVisible && !isScrubbing && !isVideoPlaying) {
            autoHideHandler.postDelayed(autoHideRunnable, 3000)
        }
    }

    private fun handleViewerTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                downX = event.rawX
                dragDistance = 0f
                draggingToDismiss = false
                gestureDirection = GestureDirection.UNDETERMINED
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landed (pinch-to-zoom) — abandon any in-progress dismiss drag
                // and snap the media back so zoom and dismiss never fight each other.
                if (draggingToDismiss) {
                    animateDismissReset()
                    draggingToDismiss = false
                    dragDistance = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val deltaY = event.rawY - downY
                val deltaX = event.rawX - downX
                val absDeltaY = kotlin.math.abs(deltaY)
                val absDeltaX = kotlin.math.abs(deltaX)

                // Lock the gesture direction once movement is past the slop threshold.
                // Until locked, do nothing — let the ambiguity resolve itself.
                if (gestureDirection == GestureDirection.UNDETERMINED) {
                    val slop = dp(10)
                    if (absDeltaX > slop || absDeltaY > slop) {
                        gestureDirection = when {
                            // Horizontal movement dominates — this is a page swipe.
                            // ViewPager2 owns this gesture; we do nothing further.
                            absDeltaX > absDeltaY * 1.2f -> GestureDirection.HORIZONTAL_PAGE

                            // Vertical movement dominates downward, panel closed, not zoomed
                            deltaY > 0 && !infoVisible && !isCurrentPageZoomed() ->
                                GestureDirection.VERTICAL_DISMISS

                            // Vertical movement dominates upward, panel closed
                            deltaY < 0 && !infoVisible ->
                                GestureDirection.VERTICAL_INFO

                            else -> GestureDirection.HORIZONTAL_PAGE // safe default: don't hijack
                        }
                    }
                }

                // Only the dismiss path animates the photo live; info-open is decided at ACTION_UP.
                if (gestureDirection == GestureDirection.VERTICAL_DISMISS &&
                    event.pointerCount == 1 && deltaY > 0
                ) {
                    draggingToDismiss = true
                    dragDistance = deltaY.coerceAtLeast(0f)
                    val progress = (dragDistance / binding.viewerRoot.height).coerceIn(0f, 1f)
                    val mediaView = getCurrentMediaView()
                    mediaView?.translationY = dragDistance
                    val scale = 1f - (progress * 0.08f)
                    mediaView?.scaleX = scale
                    mediaView?.scaleY = scale
                    binding.viewerRoot.setBackgroundColor(
                        android.graphics.Color.argb(
                            (progress * 180).toInt().coerceIn(0, 180),
                            0, 0, 0
                        )
                    )
                    val chromeAlpha = (1f - (progress * 0.8f)).coerceIn(0f, 1f)
                    binding.topBar.alpha = chromeAlpha
                    binding.viewerPill.alpha = chromeAlpha
                }
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocityY = velocityTracker?.yVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                val dismissThreshold = binding.viewerRoot.height * 0.40f
                val shouldDismiss = draggingToDismiss &&
                    (dragDistance > dismissThreshold || velocityY > DISMISS_VELOCITY_PX_PER_SEC)

                if (draggingToDismiss) {
                    if (shouldDismiss) {
                        finish()
                    } else {
                        animateDismissReset()
                    }
                    dragDistance = 0f
                    draggingToDismiss = false
                    gestureDirection = GestureDirection.UNDETERMINED
                    return true
                }

                // Only treat as "open info" if the gesture was LOCKED as vertical-info,
                // not just because the final velocity happened to be upward.
                if (gestureDirection == GestureDirection.VERTICAL_INFO) {
                    val upY = event.rawY
                    val isUpwardSwipe = downY - upY > dp(24) && velocityY < -INFO_PANEL_VELOCITY_PX_PER_SEC
                    if (isUpwardSwipe && !infoVisible) {
                        toggleInfoPanel()
                        gestureDirection = GestureDirection.UNDETERMINED
                        return true
                    }
                }

                gestureDirection = GestureDirection.UNDETERMINED
                scheduleAutoHide()
            }
        }
        return false
    }

    private fun animateDismissReset() {
        val mediaView = getCurrentMediaView() ?: return
        SpringAnimation(mediaView, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
        SpringAnimation(mediaView, DynamicAnimation.SCALE_X, 1f).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
        SpringAnimation(mediaView, DynamicAnimation.SCALE_Y, 1f).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
        binding.viewerRoot.animate()
            .setDuration(220)
            .withEndAction { binding.viewerRoot.setBackgroundColor(android.graphics.Color.BLACK) }
            .start()
        val targetAlpha = if (controlsVisible) 1f else 0f
        binding.topBar.animate().alpha(targetAlpha).setDuration(220).start()
        binding.viewerPill.animate().alpha(targetAlpha).setDuration(220).start()
    }

    private fun getCurrentMediaView(): View? {
        val holder = getCurrentPageViewHolder() ?: return null
        val item = items.getOrNull(currentPosition) ?: return null
        return if (item.mediaType == GalleryRepository.MediaType.Video) {
            holder.binding.playerView
        } else {
            holder.binding.photoView
        }
    }

    private fun confirmDelete(uri: Uri) {
        val isVideo = items.getOrNull(currentPosition)?.mediaType == GalleryRepository.MediaType.Video
        val noun = if (isVideo) "video" else "photo"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(16))
        }
        val title = TextView(this).apply {
            text = "Delete $noun?"
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val message = TextView(this).apply {
            text = "This removes the $noun from your device."
            setTextColor(Color.parseColor("#8A8A8A"))
            textSize = 14f
            setPadding(0, dp(12), 0, dp(22))
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        container.addView(title)
        container.addView(message)
        container.addView(buttonRow)

        val dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#0A0A0A"))
                setStroke(dp(1), Color.parseColor("#2A2A2A"))
            }
        )

        fun dialogButton(label: String, color: Int, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                setTextColor(color)
                textSize = 14f
                isAllCaps = true
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(18), dp(10), dp(18), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            }

        buttonRow.addView(dialogButton("Cancel", Color.parseColor("#8A8A8A")) { dialog.dismiss() })
        buttonRow.addView(dialogButton("Delete", Color.parseColor("#FF6B8A")) {
            dialog.dismiss()
            deletePhoto(uri)
        })

        dialog.show()
    }

    private fun setAsWallpaper(item: GalleryRepository.MediaItem) {
        if (item.mediaType == GalleryRepository.MediaType.Video) {
            Toast.makeText(this, "Wallpaper isn't available for videos.", Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = contentResolver.getType(item.uri) ?: "image/*"
        try {
            val wallpaperManager = WallpaperManager.getInstance(this)
            val intent = wallpaperManager.getCropAndSetWallpaperIntent(item.uri)
            startActivity(intent)
        } catch (error: Exception) {
            Log.d(Tag, "Crop-and-set wallpaper unavailable; falling back to ATTACH_DATA.", error)
            val fallback = Intent(Intent.ACTION_ATTACH_DATA).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(item.uri, mimeType)
                putExtra("mimeType", mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(fallback, "Set as"))
            } catch (notFound: ActivityNotFoundException) {
                Log.w(Tag, "No app available to set wallpaper.", notFound)
                Toast.makeText(this, "No app available to set wallpaper.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deletePhoto(uri: Uri, afterApproval: Boolean = false) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !afterApproval) {
                pendingDeleteUri = uri
                pendingDeleteNeedsRetry = false
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                return
            }
            contentResolver.delete(uri, null, null)
            onDeleteCompleted(uri)
        }.onFailure { error ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException && !afterApproval) {
                pendingDeleteUri = uri
                pendingDeleteNeedsRetry = true
                deleteRequestLauncher.launch(
                    IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build()
                )
            } else {
                Toast.makeText(this, "Delete failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onDeleteCompleted(uri: Uri) {
        contentChanged = true
        val index = items.indexOfFirst { it.uri == uri }
        if (index >= 0) {
            items.removeAt(index)
            adapter.notifyItemRemoved(index)
            if (items.isEmpty()) {
                finish()
                return
            }
            if (index >= items.size) {
                currentPosition = items.size - 1
            }
            binding.viewPager.setCurrentItem(currentPosition, false)
            bindPage(currentPosition)
        }
    }

    private fun edit(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: "image/*"
        val editIntent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mimeType)
            clipData = ClipData.newUri(contentResolver, "photo", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(editIntent, "Edit photo"))
        } catch (missingEditor: ActivityNotFoundException) {
            Log.d(Tag, "No edit activity available; falling back to view.", missingEditor)
            try {
                startActivity(fallbackIntent)
            } catch (error: ActivityNotFoundException) {
                Log.w(Tag, "No viewer fallback available for edit action.", error)
                Toast.makeText(this, "No editor available for this photo.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun loadMetadata(uri: Uri, isVideo: Boolean): PhotoMetadata {
        if (isVideo) return loadVideoMetadata(uri)

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000L
                return PhotoMetadata(
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)),
                    dateMillis = if (dateTaken > 0L) dateTaken else dateAdded,
                    width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
                    height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
                    sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
                    mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)),
                    locationName = resolveLocationName(uri)
                )
            }
        }

        val id = uri.lastPathSegment?.toLongOrNull()
        if (id != null) {
            contentResolver.query(ContentUris.withAppendedId(collection, id), projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                    val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000L
                    return PhotoMetadata(
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)),
                        dateMillis = if (dateTaken > 0L) dateTaken else dateAdded,
                        width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
                        height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
                        sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
                        mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)),
                        locationName = resolveLocationName(uri)
                    )
                }
            }
        }
        return PhotoMetadata()
    }

    private fun loadVideoMetadata(uri: Uri): PhotoMetadata {
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)) * 1000L
                val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                return PhotoMetadata(
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)),
                    dateMillis = if (dateTaken > 0L) dateTaken else dateAdded,
                    width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)),
                    height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)),
                    sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)),
                    mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)),
                    durationMillis = duration
                )
            }
        }
        return PhotoMetadata(mimeType = "video/*")
    }

    private suspend fun resolveLocationName(uri: Uri): String? {
        val latLong = readLatLong(uri) ?: return null
        if (!Geocoder.isPresent()) return null

        // Geocoder does network I/O; bound it so a slow/offline lookup never stalls the panel.
        return withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val geocoder = Geocoder(this@ViewerActivity, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latLong.first, latLong.second, 1).orEmpty()
                    val address = addresses.firstOrNull() ?: return@runCatching null
                    listOfNotNull(
                        address.locality,
                        address.subAdminArea,
                        address.adminArea,
                        address.countryName
                    ).distinct().take(2).joinToString(", ").ifBlank { null }
                }.onFailure { error ->
                    Log.d(Tag, "Unable to resolve location for $uri.", error)
                }.getOrNull()
            }
        }
    }

    private fun readLatLong(uri: Uri): Pair<Double, Double>? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    latLong[0].toDouble() to latLong[1].toDouble()
                } else {
                    null
                }
            }
        }.onFailure { error ->
            Log.d(Tag, "Unable to read EXIF location for $uri.", error)
        }.getOrNull()
    }

    private fun formatDuration(durationMillis: Long): String? {
        if (durationMillis <= 0L) return null
        val totalSeconds = durationMillis / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "Size unknown"
        val mb = bytes / (1024f * 1024f)
        return if (mb >= 1f) String.format(Locale.getDefault(), "%.1f MB", mb) else "${bytes / 1024L} KB"
    }

    private data class PhotoMetadata(
        val displayName: String? = null,
        val dateMillis: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val sizeBytes: Long = 0L,
        val mimeType: String? = null,
        val locationName: String? = null,
        val durationMillis: Long = 0L
    )

    companion object {
        private const val Tag = "ViewerActivity"
        private const val DISMISS_VELOCITY_PX_PER_SEC = 1200f
        private const val INFO_PANEL_VELOCITY_PX_PER_SEC = 600f
        private const val GEOCODER_TIMEOUT_MS = 3000L
        const val ExtraContentChanged = "content_changed"
        const val ExtraItems = "items"
        const val ExtraPosition = "position"
        const val ExtraTransitionName = "transition_name"
        const val ExtraMarker = "marker_uri"
    }

    override fun onDestroy() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        // Releases every tracked player, including off-screen holders RecyclerView is caching.
        if (::adapter.isInitialized) adapter.releaseAll()
        ViewerItemsHolder.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun finish() {
        if (contentChanged) {
            setResult(RESULT_OK, Intent().putExtra(ExtraContentChanged, true))
        }
        super.finish()
    }
}
