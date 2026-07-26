package com.devomind.gallerysearch

import android.app.RecoverableSecurityException
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import androidx.activity.addCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var albumCoverStore: AlbumCoverStore
    private lateinit var dbRepository: DbRepository
    private var items = mutableListOf<GalleryRepository.MediaItem>()
    private var currentPosition = 0
    private var controlsVisible = true
    private var infoVisible = false
    private var contentChanged = false
    private val topDateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    private val topTimeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val infoDateFormat = SimpleDateFormat("MMMM d, yyyy  h:mm a", Locale.getDefault())
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
    private var pendingAllFilesDeleteUri: Uri? = null
    private var currentExif: ExifData? = null
    private var currentTags: List<com.devomind.gallerysearch.db.TagEntity> = emptyList()
    private var gestureDirection = GestureDirection.UNDETERMINED
    private var metadataJob: kotlinx.coroutines.Job? = null
    private var findSimilarUri: String? = null
    private var findSimilarCrop: FloatArray? = null
    private var cropMode = false

    private enum class GestureDirection {
        UNDETERMINED, HORIZONTAL_PAGE, VERTICAL_DISMISS, VERTICAL_INFO
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

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val uri = pendingAllFilesDeleteUri
        pendingAllFilesDeleteUri = null
        if (uri == null) return@registerForActivityResult
        if (StoragePermissions.hasAllFilesAccess(this)) {
            requestDelete(uri)
        } else {
            MetroBanner.show(this, "All-files access is required to delete items")
        }
    }

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val edited = result.data?.getBooleanExtra(PhotoEditorActivity.ExtraEdited, false) == true
        if (edited) onImageEdited()
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
            adapter.setPrimaryPosition(position)
        }
    }

    private var touchIntercepted = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchIntercepted = false
        }

        // Paging is strictly single-finger: the moment a 2nd finger lands (twist-to-rotate /
        // pinch), disable ViewPager2 so the gesture can't also flip to the next photo. Re-enabled
        // when the whole gesture ends. Crop mode / info panel manage paging themselves.
        if (!cropMode && !infoVisible) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN ->
                    if (ev.pointerCount >= 2) binding.viewPager.isUserInputEnabled = false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    binding.viewPager.isUserInputEnabled = true
            }
        }

        // Skip the global gesture state machine entirely while the info panel
        // is visible — attachInfoPanelDrag already owns all touches in that state,
        // and ViewPager2 paging is disabled, so there is nothing for this to do.
        if (!infoVisible && !cropMode) {
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
        AccentPalette.apply(this)
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
        // After process death the saved page wins over the launch intent's position.
        currentPosition = savedInstanceState?.getInt(StatePosition)
            ?: intent.getIntExtra(ExtraPosition, 0)
        val transitionName = intent.getStringExtra(ExtraTransitionName)

        if (items.isEmpty() || currentPosition !in items.indices) {
            finish()
            return
        }

        favoritesStore = FavoritesStore(this)
        albumCoverStore = AlbumCoverStore(this)
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
                openTagPicker(item)
            },
            onVideoCompleted = {
                setControlsVisible(true)
            },
            onScrubbingChanged = { scrubbing ->
                isScrubbing = scrubbing
                if (scrubbing) {
                    autoHideHandler.removeCallbacks(autoHideRunnable)
                } else {
                    scheduleAutoHide()
                }
            },
            onPlayStateChanged = { position, isPlaying ->
                // Ignore state changes from off-screen / previous pages so they can't mutate
                // the chrome for the page the user is actually looking at.
                if (position == currentPosition) {
                    setEditAction(isVideo = true, playing = isPlaying)
                    if (isPlaying) {
                        scheduleAutoHide()
                    } else {
                        // Paused or ended — keep the controls up so play/replay stays reachable.
                        setControlsVisible(true)
                    }
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
            binding.infoPanel.translationY = infoHiddenY()
        }

        scheduleAutoHide()

        if (!IndexPreferences.wasHintShown(this, IndexPreferences.HINT_VIEWER_DISMISS)) {
            IndexPreferences.setHintShown(this, IndexPreferences.HINT_VIEWER_DISMISS)
            MetroBanner.show(
                this,
                "Swipe down to close · swipe up for details",
                durationMs = 5000,
                bottomMarginDp = 96
            )
        }
    }

    private fun configureEdgeToEdge() {
        configureCutoutMode()
        hideStatusBar()
        ViewCompat.setOnApplyWindowInsetsListener(binding.viewerRoot) { _, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topBar.updatePadding(top = systemInsets.top)

            binding.bottomControls.updatePadding(bottom = systemInsets.bottom + dp(16))

            binding.infoPanel.updatePadding(bottom = systemInsets.bottom)
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

        // Date is bound from metadata below; clear stale text from the previous page immediately.
        binding.mediaDate.visibility = View.GONE
        binding.mediaTime.visibility = View.GONE
        renderFavoriteState(favoritesStore.isFavorite(uri))

        setEditAction(isVideo = isVideo, playing = false)
        // Image-to-image search only applies to photos.
        binding.similarBtn.visibility = if (isVideo) View.GONE else View.VISIBLE

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
        binding.infoScrim.visibility = View.GONE
        binding.infoScrim.alpha = 0f
        binding.infoPanel.translationY = infoHiddenY()
        binding.infoPanel.alpha = 1f

        setControlsVisible(true)
        scheduleAutoHide()
    }

    private fun setEditAction(isVideo: Boolean, playing: Boolean) {
        // Edit icon is always "Edit", regardless of media type or playback state.
        binding.editIcon.setImageResource(R.drawable.ic_fluent_edit_24_regular)
        binding.editLabel.text = "Edit"
        binding.editIcon.contentDescription = "Edit"
    }

    private fun bindGlobalActions() {
        binding.backBtn.setOnClickListener { supportFinishAfterTransition() }
        binding.actionShare.setOnClickListener { shareCurrent() }
        binding.favoriteBtn.setOnClickListener {
            val item = items.getOrNull(currentPosition) ?: return@setOnClickListener
            val isFavorite = favoritesStore.toggle(item.uri)
            contentChanged = true
            renderFavoriteState(isFavorite)
        }
        binding.actionEdit.setOnClickListener {
            val item = items.getOrNull(currentPosition) ?: return@setOnClickListener
            edit(item.uri)
        }
        binding.actionDelete.setOnClickListener {
            val item = items.getOrNull(currentPosition) ?: return@setOnClickListener
            requestDelete(item.uri)
        }
        binding.similarBtn.setOnClickListener { findSimilar() }
        binding.cropCancel.setOnClickListener { exitCropMode() }
        binding.cropSearch.setOnClickListener { confirmCropSearch() }
        binding.infoCloseBtn.setOnClickListener { if (infoVisible) toggleInfoPanel() }
        binding.infoScrim.setOnClickListener { if (infoVisible) toggleInfoPanel() }
        binding.moreBtn.setOnClickListener { showOverflowMenu(it) }

        onBackPressedDispatcher.addCallback(this) {
            when {
                cropMode -> exitCropMode()
                infoVisible -> toggleInfoPanel()
                else -> {
                    isEnabled = false
                    supportFinishAfterTransition()
                }
            }
        }
    }

    /** Image-to-image search: offer whole-image or region-scoped search for the current photo. */
    private fun findSimilar() {
        val item = items.getOrNull(currentPosition) ?: return
        if (item.mediaType == GalleryRepository.MediaType.Video) {
            MetroBanner.show(this, "Similar search isn't available for videos")
            return
        }
        MetroDropdownMenu.show(
            binding.similarBtn,
            listOf(
                MetroDropdownMenu.Item("Search whole image") {
                    findSimilarUri = item.uri.toString()
                    supportFinishAfterTransition()
                },
                MetroDropdownMenu.Item("Search part of image") {
                    enterCropMode()
                }
            )
        )
    }

    /** Enters region-select mode: resets transforms, hides chrome, and shows the crop overlay. */
    private fun enterCropMode() {
        if (cropMode) return
        val holder = getCurrentPageViewHolder() ?: return
        val photoView = holder.binding.photoView
        if (photoView.visibility != View.VISIBLE) return

        cropMode = true
        autoHideHandler.removeCallbacks(autoHideRunnable)

        // Neutralise zoom/rotation so the crop rect maps 1:1 onto the displayed image.
        photoView.resetRotation()
        runCatching { photoView.setScale(1f, false) }
        photoView.setZoomable(false)
        binding.viewPager.isUserInputEnabled = false

        binding.topBar.visibility = View.GONE
        binding.bottomControls.visibility = View.GONE
        binding.bottomGradient.visibility = View.GONE
        binding.cropBar.visibility = View.VISIBLE
        binding.cropOverlay.visibility = View.VISIBLE
        binding.cropOverlay.post {
            val rect = photoView.displayRect
                ?: android.graphics.RectF(0f, 0f, photoView.width.toFloat(), photoView.height.toFloat())
            binding.cropOverlay.setImageBounds(rect)
        }
    }

    /** Leaves region-select mode and restores normal viewing chrome. */
    private fun exitCropMode() {
        if (!cropMode) return
        cropMode = false
        binding.cropOverlay.visibility = View.GONE
        binding.cropBar.visibility = View.GONE
        getCurrentPageViewHolder()?.binding?.photoView?.setZoomable(true)
        binding.viewPager.isUserInputEnabled = true
        binding.topBar.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        binding.bottomGradient.visibility = View.VISIBLE
        setControlsVisible(true)
    }

    /** Confirms the selected region and returns it to the gallery to run a region-scoped search. */
    private fun confirmCropSearch() {
        val norm = binding.cropOverlay.normalizedSelection()
        val item = items.getOrNull(currentPosition)
        if (norm == null || item == null) {
            exitCropMode()
            return
        }
        findSimilarUri = item.uri.toString()
        findSimilarCrop = floatArrayOf(norm.left, norm.top, norm.right, norm.bottom)
        // Hide the crop chrome so the shared-element return transition is clean.
        binding.cropOverlay.visibility = View.GONE
        binding.cropBar.visibility = View.GONE
        supportFinishAfterTransition()
    }

    private fun showOverflowMenu(anchor: View) {
        val item = items.getOrNull(currentPosition) ?: return
        val albumId = intent.getStringExtra(ExtraAlbumId)
        val options = mutableListOf<MetroDropdownMenu.Item>()
        options += MetroDropdownMenu.Item("Add tags") { openTagPicker(item) }
        if (item.mediaType != GalleryRepository.MediaType.Video) {
            options += MetroDropdownMenu.Item("Open in Google Lens") { openInGoogleLens(item) }
            if (!albumId.isNullOrBlank()) {
                options += MetroDropdownMenu.Item("Set as album cover") { setAsAlbumCover(item) }
            }
            options += MetroDropdownMenu.Item("Rotate") { rotateCurrentPhoto() }
            options += MetroDropdownMenu.Item("Set as wallpaper") { setAsWallpaper(item) }
        }
        options += MetroDropdownMenu.Item("Info") {
            if (!infoVisible) toggleInfoPanel()
        }
        MetroDropdownMenu.show(anchor, options)
    }

    private fun rotateCurrentPhoto() {
        val holder = getCurrentPageViewHolder() ?: return
        holder.binding.photoView.rotateClockwise()
    }

    private fun setAsAlbumCover(item: GalleryRepository.MediaItem) {
        val albumId = intent.getStringExtra(ExtraAlbumId)
        val albumName = intent.getStringExtra(ExtraAlbumName).orEmpty()
        if (item.mediaType == GalleryRepository.MediaType.Video || albumId.isNullOrBlank()) {
            return
        }
        albumCoverStore.setCover(albumId, item.uri)
        contentChanged = true
        val label = albumName.ifBlank { "album" }
        MetroBanner.show(this, "Set as cover for $label")
    }

    private fun openInGoogleLens(item: GalleryRepository.MediaItem) {
        if (item.mediaType == GalleryRepository.MediaType.Video) {
            MetroBanner.show(this, "Google Lens is available for photos only")
            return
        }
        val mimeType = contentResolver.getType(item.uri) ?: "image/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            setPackage(GOOGLE_LENS_PACKAGE)
            putExtra(Intent.EXTRA_STREAM, item.uri)
            clipData = ClipData.newUri(contentResolver, "Deepix photo", item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(intent)
        }.onFailure { error ->
            Log.d(Tag, "Google Lens unavailable; opening Play Store.", error)
            openGoogleLensPlayStore()
        }
    }

    private fun openGoogleLensPlayStore() {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$GOOGLE_LENS_PACKAGE")
        ).apply {
            setPackage(GOOGLE_PLAY_STORE_PACKAGE)
        }
        runCatching {
            startActivity(marketIntent)
        }.onFailure { marketError ->
            Log.d(Tag, "Play Store app unavailable; opening browser listing.", marketError)
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_LENS_PACKAGE")
            )
            runCatching {
                startActivity(webIntent)
            }.onFailure { webError ->
                Log.w(Tag, "Unable to open Google Lens install page.", webError)
                MetroBanner.show(this, "Google Lens isn't available on this phone")
            }
        }
    }

    private fun openTagPicker(item: GalleryRepository.MediaItem) {
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
        // Delegate to the holder; the player's listener syncs the icons, controls and auto-hide.
        getCurrentPageViewHolder()?.togglePlayback()
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
        val name = metadata.displayName ?: "Photo"
        if (metadata.dateMillis > 0L) {
            val date = Date(metadata.dateMillis)
            binding.mediaDate.visibility = View.VISIBLE
            binding.mediaDate.text = topDateFormat.format(date)
            binding.mediaTime.visibility = View.VISIBLE
            binding.mediaTime.text = topTimeFormat.format(date)
        } else {
            binding.mediaDate.visibility = View.GONE
            binding.mediaTime.visibility = View.GONE
        }

        setInfoRow(binding.rowFilename, "Filename", name)
        setInfoRow(
            binding.rowDate,
            "Date",
            if (metadata.dateMillis > 0L) infoDateFormat.format(Date(metadata.dateMillis)) else null
        )
        setInfoRow(
            binding.rowSize,
            "Size",
            if (metadata.sizeBytes > 0L) formatSize(metadata.sizeBytes) else null
        )
        setInfoRow(
            binding.rowDimensions,
            "Dimensions",
            if (metadata.width > 0 && metadata.height > 0) "${metadata.width} x ${metadata.height}" else null
        )
        setInfoRow(binding.rowDuration, "Duration", formatDuration(metadata.durationMillis))
        setInfoRow(binding.rowLocation, "Location", metadata.locationName)
        setInfoRow(binding.rowDevice, "Device", buildDeviceLine(exif))
        setInfoRow(binding.rowLens, "Lens", exif?.lensModel?.takeIf { it.isNotBlank() })
        setInfoRow(binding.rowSettings, "Settings", buildSettingsLine(exif))
        setInfoRow(binding.rowPath, "Path", prettifyPath(items.getOrNull(currentPosition)?.path))

        bindMetadataTags(tags)
    }

    private fun setInfoRow(row: com.devomind.gallerysearch.databinding.ItemInfoRowBinding, label: String, value: String?) {
        if (value.isNullOrBlank()) {
            row.root.visibility = View.GONE
        } else {
            row.root.visibility = View.VISIBLE
            row.label.text = label
            row.value.text = value
        }
    }

    private fun buildDeviceLine(exif: ExifData?): String? {
        if (exif == null) return null
        val make = exif.make?.trim().orEmpty()
        val model = exif.model?.trim().orEmpty()
        if (make.isEmpty() && model.isEmpty()) return null
        // Avoid "Apple Apple iPhone 15" style duplication when the model already includes the make.
        return if (model.startsWith(make, ignoreCase = true)) model else listOf(make, model)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun buildSettingsLine(exif: ExifData?): String? {
        if (exif == null) return null
        val parts = buildList {
            exif.fNumber?.let { add("f/$it") }
            exif.exposureTime?.let { add(formatExposureTime(it)) }
            exif.focalLength?.let { add("${trimDecimal(it)} mm") }
            exif.iso?.let { add("ISO $it") }
        }
        return parts.joinToString("   ").ifBlank { null }
    }

    private fun trimDecimal(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
    }

    private fun prettifyPath(path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raw
            .replace("/storage/emulated/0/", "Internal storage/")
            .replace("/storage/self/primary/", "Internal storage/")
    }

    private fun bindMetadataTags(tags: List<com.devomind.gallerysearch.db.TagEntity>) {
        binding.infoTags.removeAllViews()
        if (tags.isEmpty()) {
            binding.rowTags.visibility = View.GONE
        } else {
            binding.rowTags.visibility = View.VISIBLE
            tags.take(12).forEach { tag ->
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
                    cornerRadius = dp(2).toFloat()
                    setStroke(dp(1), color)
                    setColor(androidx.core.content.ContextCompat.getColor(this@ViewerActivity, R.color.metroBgSecondary))
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
            if (isFavorite) {
                androidx.core.content.ContextCompat.getColor(this, R.color.metroFavorite)
            } else {
                Color.WHITE
            }
        )
    }

    private fun toggleControls() {
        setControlsVisible(!controlsVisible)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val targetAlpha = if (visible) 1f else 0f
        val topTranslation = if (visible) 0f else -dp(20).toFloat()
        val bottomTranslation = if (visible) 0f else dp(24).toFloat()
        binding.topBar.animate().alpha(targetAlpha).translationY(topTranslation).setDuration(220).start()
        binding.bottomControls.animate().alpha(targetAlpha).translationY(bottomTranslation).setDuration(220).start()
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
        getCurrentPageViewHolder()?.setVideoControlsVisible(controlsVisible && currentIsVideo())
    }

    private fun toggleInfoPanel() {
        infoVisible = !infoVisible
        binding.viewPager.isUserInputEnabled = !infoVisible
        if (infoVisible) {
            clampInfoScrollHeight()
            binding.infoScroll.scrollTo(0, 0)
            binding.infoScrim.visibility = View.VISIBLE
            binding.infoScrim.animate().alpha(SCRIM_MAX_ALPHA).setDuration(220).start()
        } else {
            binding.infoScrim.animate().alpha(0f).setDuration(220)
                .withEndAction { binding.infoScrim.visibility = View.GONE }
                .start()
        }
        val target = if (infoVisible) 0f else infoHiddenY()
        SpringAnimation(binding.infoPanel, DynamicAnimation.TRANSLATION_Y, target).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
        scheduleAutoHide()
    }

    /** Off-screen Y offset that fully hides the (content-sized) sheet regardless of its height. */
    private fun infoHiddenY(): Float {
        val h = binding.viewerRoot.height
        return if (h > 0) h.toFloat() else resources.displayMetrics.heightPixels.toFloat()
    }

    /**
     * The sheet wraps its content, but very tall metadata (long path, many tags) could exceed the
     * screen. Cap the scrollable area at ~82% of the viewer so the handle and CLOSE button stay
     * on-screen and the rows scroll within.
     */
    private fun clampInfoScrollHeight() {
        val rootH = binding.viewerRoot.height
        val content = binding.infoScroll.getChildAt(0) ?: return
        if (rootH <= 0 || binding.infoScroll.width <= 0) return
        content.measure(
            View.MeasureSpec.makeMeasureSpec(binding.infoScroll.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val natural = content.measuredHeight
        val maxScroll = (rootH * 0.82f).toInt() - binding.infoHandleArea.height - binding.infoCloseBtn.height
        val lp = binding.infoScroll.layoutParams
        lp.height = if (maxScroll in 1 until natural) maxScroll else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        binding.infoScroll.layoutParams = lp
    }

    private var infoPanelDownY = 0f
    private var infoPanelDragging = false

    @Suppress("ClickableViewAccessibility")
    private fun attachInfoPanelDrag() {
        binding.infoHandleArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    infoPanelDownY = event.rawY
                    infoPanelDragging = false
                    autoHideHandler.removeCallbacks(autoHideRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - infoPanelDownY
                    if (deltaY > dp(4) || infoPanelDragging) {
                        infoPanelDragging = true
                        val offset = deltaY.coerceAtLeast(0f)
                        binding.infoPanel.translationY = offset
                        val progress = (offset / binding.infoPanel.height.toFloat()).coerceIn(0f, 1f)
                        binding.infoScrim.alpha = SCRIM_MAX_ALPHA * (1f - progress)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_UP -> {
                    val deltaY = event.rawY - infoPanelDownY
                    if (!infoPanelDragging || deltaY > binding.infoPanel.height * 0.25f) {
                        // A tap on the handle, or a drag past the threshold, closes the panel.
                        infoVisible = false
                        binding.viewPager.isUserInputEnabled = true
                        animateInfoPanelClosed()
                    } else {
                        // Drag didn't reach the threshold — snap the sheet back open.
                        SpringAnimation(binding.infoPanel, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                            start()
                        }
                        binding.infoScrim.animate().alpha(SCRIM_MAX_ALPHA).setDuration(160).start()
                    }
                    infoPanelDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun animateInfoPanelClosed() {
        SpringAnimation(binding.infoPanel, DynamicAnimation.TRANSLATION_Y, infoHiddenY()).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
        binding.infoScrim.animate().alpha(0f).setDuration(200)
            .withEndAction { binding.infoScrim.visibility = View.GONE }
            .start()
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        // Auto-hide is disabled: controls only hide/unhide on tap.
        autoHideHandler.removeCallbacks(autoHideRunnable)
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
                    // Rotation is baked into the bitmap (RotatablePhotoView), so the resting
                    // view scale is always 1 — shrink slightly from there while dragging.
                    val scale = 1f - (progress * 0.08f)
                    mediaView?.scaleX = scale
                    mediaView?.scaleY = scale
                    binding.viewerRoot.setBackgroundColor(
                        android.graphics.Color.argb(
                            (progress * 180).toInt().coerceIn(0, 180),
                            0, 0, 0
                        )
                    )
                    if (controlsVisible) {
                        val chromeAlpha = (1f - (progress * 0.8f)).coerceIn(0f, 1f)
                        binding.topBar.alpha = chromeAlpha
                        binding.bottomControls.alpha = chromeAlpha
                    }
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
                        finishSwipeDismiss()
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

    private fun finishSwipeDismiss() {
        finish()
        // The drag gesture already supplies the transition. Suppress the platform's default
        // activity close animation so the gallery underneath does not slide sideways.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun animateDismissReset() {
        val mediaView = getCurrentMediaView() ?: return
        SpringAnimation(mediaView, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
        // Bitmap-baked rotation keeps the resting view transform identity, so snap back to 1f.
        val baseScale = 1f
        SpringAnimation(mediaView, DynamicAnimation.SCALE_X, baseScale).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            start()
        }
        SpringAnimation(mediaView, DynamicAnimation.SCALE_Y, baseScale).apply {
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
        binding.bottomControls.animate().alpha(targetAlpha).setDuration(220).start()
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

    private fun requestDelete(uri: Uri) {
        if (!DeleteCoordinator.canDeleteDirectly(this)) {
            pendingAllFilesDeleteUri = uri
            runCatching { allFilesAccessLauncher.launch(StoragePermissions.manageAllFilesIntent(this)) }
                .onFailure {
                    pendingAllFilesDeleteUri = null
                    MetroBanner.show(this, "Couldn't open storage access settings")
                }
            return
        }
        lifecycleScope.launch {
            when (val outcome = withContext(Dispatchers.IO) { DeleteCoordinator.delete(this@ViewerActivity, listOf(uri)) }) {
                is DeleteCoordinator.Outcome.NeedsSystemDelete -> deletePhoto(uri)
                is DeleteCoordinator.Outcome.Done -> onDeleteCompleted(uri)
            }
        }
    }

    private fun setAsWallpaper(item: GalleryRepository.MediaItem) {
        if (item.mediaType == GalleryRepository.MediaType.Video) {
            MetroBanner.show(this, "Wallpaper isn't available for videos")
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
                MetroBanner.show(this, "No app available to set wallpaper")
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
                MetroBanner.show(this, "Delete failed: ${error.message}")
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
        val item = items.getOrNull(currentPosition)
        val intent = if (item?.mediaType == GalleryRepository.MediaType.Video) {
            Intent(this, VideoEditorActivity::class.java).apply {
                putExtra(VideoEditorActivity.ExtraUri, uri.toString())
                putExtra(VideoEditorActivity.ExtraName, item.displayName)
            }
        } else {
            Intent(this, PhotoEditorActivity::class.java).apply {
                putExtra(PhotoEditorActivity.ExtraUri, uri.toString())
                putExtra(PhotoEditorActivity.ExtraName, item?.displayName)
            }
        }
        editorLauncher.launch(intent)
    }

    /** After an edit is saved, refresh the current page (bust Glide cache) and mark content changed. */
    private fun onImageEdited() {
        contentChanged = true
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { com.bumptech.glide.Glide.get(this@ViewerActivity).clearDiskCache() }
            withContext(Dispatchers.Main) {
                com.bumptech.glide.Glide.get(this@ViewerActivity).clearMemory()
                adapter.notifyItemChanged(currentPosition)
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
        private const val SCRIM_MAX_ALPHA = 0.72f
        private const val GOOGLE_LENS_PACKAGE = "com.google.ar.lens"
        private const val GOOGLE_PLAY_STORE_PACKAGE = "com.android.vending"
        const val ExtraContentChanged = "content_changed"
        const val ExtraItems = "items"
        const val ExtraPosition = "position"
        const val ExtraTransitionName = "transition_name"
        const val ExtraAlbumId = "album_id"
        const val ExtraAlbumName = "album_name"
        const val ExtraMarker = "marker_uri"
        const val ExtraFindSimilarUri = "find_similar_uri"
        const val ExtraFindSimilarCrop = "find_similar_crop"
        private const val StatePosition = "state_position"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(StatePosition, currentPosition)
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
        if (contentChanged || findSimilarUri != null) {
            val data = Intent().putExtra(ExtraContentChanged, contentChanged)
            findSimilarUri?.let { data.putExtra(ExtraFindSimilarUri, it) }
            findSimilarCrop?.let { data.putExtra(ExtraFindSimilarCrop, it) }
            setResult(RESULT_OK, data)
        }
        super.finish()
    }
}
