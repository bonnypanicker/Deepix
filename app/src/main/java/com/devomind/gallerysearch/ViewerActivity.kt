package com.devomind.gallerysearch

import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
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
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ActivityViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class ViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityViewerBinding
    private var controlsVisible = true
    private var infoVisible = false
    private var uri: Uri? = null
    private lateinit var favoritesStore: FavoritesStore
    private var contentChanged = false
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { setControlsVisible(false) }
    private var downY = 0f
    private var dragDistance = 0f
    private var draggingToDismiss = false
    private var pendingDeleteUri: Uri? = null
    private var pendingDeleteNeedsRetry = false
    private var isVideo = false
    private var videoPrepared = false

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
            onDeleteCompleted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureEdgeToEdge()

        uri = intent.data
        val currentUri = uri
        if (currentUri == null) {
            finish()
            return
        }
        favoritesStore = FavoritesStore(this)

        isVideo = (contentResolver.getType(currentUri) ?: "").startsWith("video/")
        if (isVideo) {
            bindVideo(currentUri)
        } else {
            binding.videoView.visibility = View.GONE
            binding.photoView.visibility = View.VISIBLE
            Glide.with(this)
                .load(currentUri)
                .fitCenter()
                .into(binding.photoView)
        }

        renderFavoriteState(favoritesStore.isFavorite(currentUri))
        bindActions(currentUri)
        binding.infoPanel.post {
            binding.infoPanel.translationY = binding.infoPanel.height.toFloat()
        }
        lifecycleScope.launch {
            val metadata = withContext(Dispatchers.IO) { loadMetadata(currentUri) }
            bindMetadata(metadata)
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
            binding.topBar.updatePadding(top = systemInsets.top + dp(8))

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

    private fun bindActions(uri: Uri) {
        binding.backBtn.setOnClickListener { finish() }
        binding.photoView.setOnClickListener {
            if (!draggingToDismiss) toggleControls()
        }
        binding.videoView.setOnClickListener {
            if (!draggingToDismiss) toggleControls()
        }
        binding.infoBtn.setOnClickListener { toggleInfoPanel() }
        binding.shareBtn.setOnClickListener { share(uri) }
        binding.favoriteBtn.setOnClickListener {
            val isFavorite = favoritesStore.toggle(uri)
            contentChanged = true
            renderFavoriteState(isFavorite)
        }
        binding.editBtn.setOnClickListener {
            if (isVideo) toggleVideoPlayback() else edit(uri)
        }
        binding.deleteBtn.setOnClickListener { confirmDelete(uri) }
        binding.viewerRoot.setOnTouchListener { _, event ->
            handleViewerTouch(event)
        }
    }

    private fun bindVideo(uri: Uri) {
        binding.photoView.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        binding.editBtn.setImageResource(R.drawable.ic_fluent_pause_24_regular)
        binding.editBtn.contentDescription = "Pause video"

        val controller = MediaController(this)
        controller.setAnchorView(binding.videoView)
        binding.videoView.setMediaController(controller)
        binding.videoView.setVideoURI(uri)
        binding.videoView.setOnPreparedListener { player ->
            videoPrepared = true
            player.isLooping = false
            binding.videoView.start()
            scheduleAutoHide()
        }
        binding.videoView.setOnCompletionListener {
            binding.editBtn.setImageResource(R.drawable.ic_fluent_play_24_regular)
            binding.editBtn.contentDescription = "Play video"
            setControlsVisible(true)
        }
        binding.videoView.setOnErrorListener { _, what, extra ->
            Log.w(Tag, "Video playback failed for $uri. what=$what extra=$extra")
            Toast.makeText(this, "This video cannot be played.", Toast.LENGTH_LONG).show()
            true
        }
    }

    private fun toggleVideoPlayback() {
        if (!isVideo || !videoPrepared) return
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            binding.editBtn.setImageResource(R.drawable.ic_fluent_play_24_regular)
            binding.editBtn.contentDescription = "Play video"
            setControlsVisible(true)
        } else {
            binding.videoView.start()
            binding.editBtn.setImageResource(R.drawable.ic_fluent_pause_24_regular)
            binding.editBtn.contentDescription = "Pause video"
            scheduleAutoHide()
        }
    }

    private fun bindMetadata(metadata: PhotoMetadata) {
        binding.fileNameText.text = metadata.displayName ?: "Photo"
        binding.infoDate.text = if (metadata.dateMillis > 0L) {
            dateFormat.format(Date(metadata.dateMillis))
        } else {
            "Date unknown"
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
        if (metadata.locationName.isNullOrBlank()) {
            binding.infoLocation.visibility = View.GONE
            binding.infoLocation.text = ""
        } else {
            binding.infoLocation.visibility = View.VISIBLE
            binding.infoLocation.text = metadata.locationName
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
        binding.topBar.animate().alpha(targetAlpha).setDuration(200).start()
        binding.viewerPill.animate().alpha(targetAlpha).setDuration(200).start()
        if (visible) {
            scheduleAutoHide()
        } else {
            autoHideHandler.removeCallbacks(autoHideRunnable)
        }
    }

    private fun toggleInfoPanel() {
        infoVisible = !infoVisible
        val target = if (infoVisible) 0f else binding.infoPanel.height.toFloat()
        binding.infoPanel.animate()
            .translationY(target)
            .setDuration(220)
            .start()
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
        if (controlsVisible && !infoVisible) {
            autoHideHandler.postDelayed(autoHideRunnable, 3000)
        }
    }

    private fun handleViewerTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                dragDistance = 0f
                draggingToDismiss = false
            }
            MotionEvent.ACTION_MOVE -> {
                dragDistance = (event.rawY - downY).coerceAtLeast(0f)
                if (!infoVisible && dragDistance > 8f) {
                    draggingToDismiss = true
                    val progress = (dragDistance / binding.viewerRoot.height).coerceIn(0f, 1f)
                    val mediaView = if (isVideo) binding.videoView else binding.photoView
                    mediaView.translationY = dragDistance
                    val scale = 1f - (progress * 0.08f)
                    mediaView.scaleX = scale
                    mediaView.scaleY = scale
                    val chromeAlpha = (1f - (progress * 0.8f)).coerceIn(0f, 1f)
                    binding.topBar.alpha = chromeAlpha
                    binding.viewerPill.alpha = chromeAlpha
                }
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                val dismissThreshold = binding.viewerRoot.height * 0.22f
                if (draggingToDismiss) {
                    if (dragDistance > dismissThreshold) {
                        finish()
                    } else {
                        val mediaView = if (isVideo) binding.videoView else binding.photoView
                        mediaView.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(220).start()
                        binding.topBar.animate().alpha(if (controlsVisible) 1f else 0f).setDuration(220).start()
                        binding.viewerPill.animate().alpha(if (controlsVisible) 1f else 0f).setDuration(220).start()
                    }
                    dragDistance = 0f
                    draggingToDismiss = false
                    return true
                }
                if (event.y > binding.viewerRoot.height * 0.72f) {
                    toggleInfoPanel()
                    return true
                }
                scheduleAutoHide()
            }
        }
        return false
    }

    private fun share(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share photo"))
    }

    private fun confirmDelete(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Delete photo?")
            .setMessage("This removes the photo from the device.")
            .setPositiveButton("Delete") { _, _ -> deletePhoto(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            onDeleteCompleted()
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

    private fun onDeleteCompleted() {
        contentChanged = true
        finish()
    }

    private fun loadMetadata(uri: Uri): PhotoMetadata {
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
                    locationName = formatDuration(duration)
                )
            }
        }
        return PhotoMetadata(mimeType = "video/*")
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

    private fun resolveLocationName(uri: Uri): String? {
        val latLong = readLatLong(uri) ?: return null
        if (!Geocoder.isPresent()) return null

        return runCatching {
            val geocoder = Geocoder(this, Locale.getDefault())
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
        val locationName: String? = null
    )

    companion object {
        private const val Tag = "ViewerActivity"
        const val ExtraContentChanged = "content_changed"
    }

    override fun onDestroy() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
        if (isVideo) {
            binding.videoView.stopPlayback()
        }
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
