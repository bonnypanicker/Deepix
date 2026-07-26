package com.devomind.gallerysearch

import android.app.RecoverableSecurityException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Metro-themed video editor: trim (filmstrip with drag handles), crop (free/1:1/4:3/16:9)
 * and 90-degree rotation, previewed live on ExoPlayer and exported with Media3 Transformer.
 * Save overwrites the original (with the platform write-consent flow on Q+); Save a copy
 * writes a new MP4 to Movies/Deepix. Mirrors PhotoEditorActivity's contract:
 * returns [ExtraEdited] so the viewer refreshes.
 */
@UnstableApi
class VideoEditorActivity : AppCompatActivity() {

    private lateinit var binding: com.devomind.gallerysearch.databinding.ActivityVideoEditorBinding
    private lateinit var sourceUri: Uri
    private var displayName: String = "video"

    private var player: ExoPlayer? = null
    private var durationMs = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var rotationApplied = 0        // MediaStore/container rotation already applied by players

    // Committed edits (what Save exports).
    private var trimStartMs = 0L
    private var trimEndMs = 0L
    private var cropRect: RectF? = null    // normalized 0..1 in DISPLAYED orientation, null = full frame
    private var extraRotationDegrees = 0   // user-added rotation on top of the container's own

    private var cropToolActive = false
    private var transformer: Transformer? = null
    private var exportFile: File? = null
    private var pendingOverwriteFile: File? = null
    private var edited = false

    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (durationMs > 0) {
                binding.trimView.playheadFraction = p.currentPosition.toFloat() / durationMs
            }
            // Loop preview inside the kept range, like Google Photos' trim preview.
            if (p.isPlaying && p.currentPosition >= trimEndMs && trimEndMs > trimStartMs) {
                p.seekTo(trimStartMs)
            }
            progressHandler.postDelayed(this, 100L)
        }
    }

    private val transformerProgressHolder = androidx.media3.transformer.ProgressHolder()
    private val exportProgressTick = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val state = t.getProgress(transformerProgressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                binding.exportProgress.isIndeterminate = false
                binding.exportProgress.progress = transformerProgressHolder.progress
            } else {
                binding.exportProgress.isIndeterminate = true
            }
            progressHandler.postDelayed(this, 200L)
        }
    }

    private val writeRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val file = pendingOverwriteFile
        pendingOverwriteFile = null
        if (result.resultCode == RESULT_OK && file != null) {
            finishOverwrite(file, afterConsent = true)
        } else {
            file?.delete()
            hideExportOverlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = com.devomind.gallerysearch.databinding.ActivityVideoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        val uriString = intent.getStringExtra(ExtraUri)
        if (uriString == null) {
            finish()
            return
        }
        sourceUri = Uri.parse(uriString)
        displayName = intent.getStringExtra(ExtraName) ?: "video"

        bindChrome()
        loadMetadataAndPreview()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.videoEditorRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(4))
            binding.bottomArea.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    // ------------------------------------------------------------------ setup

    private fun bindChrome() {
        binding.closeBtn.setOnClickListener { confirmDiscardAndExit() }
        binding.saveBtn.setOnClickListener { showSaveMenu() }
        binding.playPauseBtn.setOnClickListener { togglePlayback() }
        binding.playerView.setOnClickListener { togglePlayback() }

        binding.toolTrim.setOnClickListener { if (cropToolActive) exitCropTool() }
        binding.toolCrop.setOnClickListener { if (!cropToolActive) enterCropTool() }
        binding.toolCancel.setOnClickListener { exitCropTool() }
        binding.toolApply.setOnClickListener { applyCrop() }

        binding.cropFree.setOnClickListener { binding.cropView.setAspect(null); highlightAspect(binding.cropFree) }
        binding.cropSquare.setOnClickListener { binding.cropView.setAspect(1f); highlightAspect(binding.cropSquare) }
        binding.crop43.setOnClickListener { binding.cropView.setAspect(4f / 3f); highlightAspect(binding.crop43) }
        binding.crop169.setOnClickListener { binding.cropView.setAspect(16f / 9f); highlightAspect(binding.crop169) }
        binding.rotateBtn.setOnClickListener { rotatePreview() }

        binding.trimView.onRangeChanged = { start, end ->
            trimStartMs = (start * durationMs).toLong()
            trimEndMs = (end * durationMs).toLong()
            edited = true
            updateTrimLabels()
        }
        binding.trimView.onRangeCommitted = { start, _ ->
            player?.seekTo((start * durationMs).toLong())
        }

        binding.exportCancel.setOnClickListener { cancelExport() }

        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.exportOverlay.visibility == View.VISIBLE -> cancelExport()
                cropToolActive -> exitCropTool()
                else -> confirmDiscardAndExit()
            }
        }
    }

    private fun loadMetadataAndPreview() {
        lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) { readVideoMetadata() }
            if (meta == null || meta.durationMs <= 0L) {
                MetroBanner.show(this@VideoEditorActivity, "Couldn't open this video")
                finish()
                return@launch
            }
            durationMs = meta.durationMs
            videoWidth = meta.width
            videoHeight = meta.height
            rotationApplied = meta.rotationDegrees
            trimStartMs = 0L
            trimEndMs = durationMs
            updateTrimLabels()
            setUpPlayer()
            loadFilmstrip()
        }
    }

    private data class VideoMeta(val durationMs: Long, val width: Int, val height: Int, val rotationDegrees: Int)

    private fun readVideoMetadata(): VideoMeta? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, sourceUri)
            VideoMeta(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            )
        } finally {
            // close() only exists on API 29+; release() is the safe equivalent on minSdk 26.
            retriever.release()
        }
    }.getOrNull()

    private fun setUpPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerView.player = exo
        exo.setMediaItem(Media3Item.fromUri(sourceUri))
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.prepare()
        exo.playWhenReady = false
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.playPauseBtn.setImageResource(
                    if (isPlaying) R.drawable.ic_fluent_pause_24_regular else R.drawable.ic_fluent_play_24_regular
                )
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exo.seekTo(trimStartMs)
                    exo.pause()
                }
            }
        })
        progressHandler.post(progressTick)
    }

    /** Extracts ~8 evenly spaced frames for the filmstrip off the main thread. */
    private fun loadFilmstrip() {
        lifecycleScope.launch(Dispatchers.IO) {
            val frames = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@VideoEditorActivity, sourceUri)
                    val count = 8
                    (0 until count).mapNotNull { i ->
                        if (!isActive) return@mapNotNull null
                        val timeUs = durationMs * 1000L * (2L * i + 1) / (2L * count)
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { scaleFrame(it) }
                    }
                } finally {
                    retriever.release()
                }
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { binding.trimView.setFrames(frames) }
        }
    }

    private fun scaleFrame(src: Bitmap): Bitmap {
        val targetH = dp(52)
        val targetW = (src.width * targetH.toFloat() / src.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    // ------------------------------------------------------------------ playback

    private fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.currentPosition < trimStartMs || p.currentPosition >= trimEndMs) p.seekTo(trimStartMs)
            p.play()
        }
    }

    private fun updateTrimLabels() {
        binding.trimStartLabel.text = formatTime(trimStartMs)
        binding.trimEndLabel.text = formatTime(trimEndMs)
        binding.trimDurationLabel.text = formatTime(trimEndMs - trimStartMs)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    // ------------------------------------------------------------------ crop tool

    private fun enterCropTool() {
        cropToolActive = true
        player?.pause()
        binding.playPauseBtn.visibility = View.GONE
        binding.trimPanel.visibility = View.GONE
        binding.panelCrop.visibility = View.VISIBLE
        binding.toolBar.visibility = View.GONE
        binding.toolActionBar.visibility = View.VISIBLE
        binding.cropView.visibility = View.VISIBLE
        highlightAspect(binding.cropFree)
        binding.cropView.post {
            val bounds = displayedVideoBounds()
            binding.cropView.setImageBounds(bounds)
            binding.cropView.setAspect(null)
            binding.cropView.setNormalizedSelection(cropRect)
        }
    }

    private fun exitCropTool() {
        cropToolActive = false
        binding.cropView.visibility = View.GONE
        binding.panelCrop.visibility = View.GONE
        binding.toolActionBar.visibility = View.GONE
        binding.toolBar.visibility = View.VISIBLE
        binding.trimPanel.visibility = View.VISIBLE
        binding.playPauseBtn.visibility = View.VISIBLE
    }

    private fun applyCrop() {
        binding.cropView.normalizedSelection()?.let { sel ->
            // Preview intentionally remains the full source; reopening Crop replaces the prior crop
            // rather than composing against pixels that are not visually cropped yet.
            cropRect = RectF(sel)
            edited = true
        }
        exitCropTool()
    }

    private fun rotatePreview() {
        extraRotationDegrees = (extraRotationDegrees + 90) % 360
        edited = true
        // Rotate the crop rect with the content so it stays aligned: 90° CW in display space.
        cropRect = cropRect?.let { RectF(it.top, 1f - it.right, it.bottom, 1f - it.left) }
        applyPreviewRotation()
        if (cropToolActive) {
            binding.cropView.post {
                binding.cropView.setImageBounds(displayedVideoBounds())
                binding.cropView.setAspect(null)
            }
        }
    }

    /** Visually rotates the PlayerView (scaled to stay contained); Transformer bakes it on save. */
    private fun applyPreviewRotation() {
        val view = binding.playerView
        view.rotation = extraRotationDegrees.toFloat()
        if (extraRotationDegrees % 180 == 0) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        val viewW = binding.previewArea.width.toFloat()
        val viewH = binding.previewArea.height.toFloat()
        if (viewW <= 0f || viewH <= 0f || videoWidth <= 0 || videoHeight <= 0) return
        // The un-rotated fitted content size inside the view (fit resize mode).
        val containerUpright = rotationApplied % 180 != 0
        val srcW = if (containerUpright) videoHeight.toFloat() else videoWidth.toFloat()
        val srcH = if (containerUpright) videoWidth.toFloat() else videoHeight.toFloat()
        val fitScale = minOf(viewW / srcW, viewH / srcH)
        val fittedW = srcW * fitScale
        val fittedH = srcH * fitScale
        // After a 90° view rotation the content occupies fittedH x fittedW; rescale to contain.
        val scale = minOf(viewW / fittedH, viewH / fittedW)
        view.scaleX = scale
        view.scaleY = scale
    }

    /** The fitted video rect inside the PlayerView, accounting for container + user rotation. */
    private fun displayedVideoBounds(): RectF {
        val viewW = binding.previewArea.width.toFloat()
        val viewH = binding.previewArea.height.toFloat()
        if (viewW <= 0f || viewH <= 0f || videoWidth <= 0 || videoHeight <= 0) {
            return RectF(0f, 0f, viewW, viewH)
        }
        val upright = (rotationApplied + extraRotationDegrees) % 180 != 0
        val contentW = if (upright) videoHeight.toFloat() else videoWidth.toFloat()
        val contentH = if (upright) videoWidth.toFloat() else videoHeight.toFloat()
        val scale = minOf(viewW / contentW, viewH / contentH)
        val w = contentW * scale
        val h = contentH * scale
        val left = (viewW - w) / 2f
        val top = (viewH - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    private fun highlightAspect(active: View) {
        listOf(binding.cropFree, binding.cropSquare, binding.crop43, binding.crop169).forEach {
            it.alpha = if (it === active) 1f else 0.55f
        }
    }

    // ------------------------------------------------------------------ save

    private fun showSaveMenu() {
        if (!edited) {
            MetroBanner.show(this, "No changes to save")
            return
        }
        MetroDropdownMenu.show(
            binding.saveBtn,
            listOf(
                MetroDropdownMenu.Item(getString(R.string.save)) { startExport(overwriteOriginal = true) },
                MetroDropdownMenu.Item(getString(R.string.save_a_copy)) { startExport(overwriteOriginal = false) }
            )
        )
    }

    private fun startExport(overwriteOriginal: Boolean) {
        player?.pause()
        binding.exportOverlay.visibility = View.VISIBLE
        binding.exportProgress.isIndeterminate = true

        val output = File(cacheDir, "video_edit_${System.currentTimeMillis()}.mp4")
        exportFile = output

        val clipped = Media3Item.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                Media3Item.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(trimEndMs)
                    .build()
            )
            .build()

        val videoEffects = buildList {
            // Rotation first: cropRect is tracked in the rotated display space, and effects
            // apply in list order, so the crop must see the already-rotated frame.
            if (extraRotationDegrees != 0) {
                add(
                    ScaleAndRotateTransformation.Builder()
                        // Transformer rotates counter-clockwise; the UI rotates clockwise.
                        .setRotationDegrees((360 - extraRotationDegrees).toFloat())
                        .build()
                )
            }
            cropRect?.let { rect ->
                // Convert display-space normalized rect (top-left origin) to NDC (-1..1, y up).
                add(
                    Crop(
                        /* left = */ rect.left * 2f - 1f,
                        /* right = */ rect.right * 2f - 1f,
                        /* bottom = */ 1f - rect.bottom * 2f,
                        /* top = */ 1f - rect.top * 2f
                    )
                )
            }
        }

        val editedItem = EditedMediaItem.Builder(clipped)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val t = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressHandler.removeCallbacks(exportProgressTick)
                    transformer = null
                    if (overwriteOriginal) {
                        finishOverwrite(output, afterConsent = false)
                    } else {
                        finishSaveCopy(output)
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    progressHandler.removeCallbacks(exportProgressTick)
                    transformer = null
                    output.delete()
                    hideExportOverlay()
                    MetroBanner.show(this@VideoEditorActivity, "Save failed: ${exportException.errorCodeName}")
                }
            })
            .build()
        transformer = t
        t.start(editedItem, output.absolutePath)
        progressHandler.post(exportProgressTick)
    }

    private fun cancelExport() {
        transformer?.cancel()
        transformer = null
        progressHandler.removeCallbacks(exportProgressTick)
        exportFile?.delete()
        exportFile = null
        hideExportOverlay()
    }

    private fun hideExportOverlay() {
        binding.exportOverlay.visibility = View.GONE
    }

    private fun finishOverwrite(file: File, afterConsent: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    MediaVideoSaver.overwrite(
                        this@VideoEditorActivity,
                        sourceUri,
                        file,
                        trimEndMs - trimStartMs
                    )
                }
            }
            result.onSuccess {
                file.delete()
                finishSaved()
            }.onFailure { error ->
                val recoverable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    error is RecoverableSecurityException
                when {
                    !afterConsent && recoverable -> {
                        pendingOverwriteFile = file
                        writeRequestLauncher.launch(
                            IntentSenderRequest.Builder(
                                (error as RecoverableSecurityException).userAction.actionIntent.intentSender
                            ).build()
                        )
                    }
                    !afterConsent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        pendingOverwriteFile = file
                        val pi = MediaStore.createWriteRequest(contentResolver, listOf(sourceUri))
                        writeRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    }
                    else -> {
                        file.delete()
                        hideExportOverlay()
                        MetroBanner.show(this@VideoEditorActivity, "Save failed: ${error.message}")
                    }
                }
            }
        }
    }

    private fun finishSaveCopy(file: File) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    MediaVideoSaver.saveCopy(
                        this@VideoEditorActivity,
                        file,
                        displayName,
                        trimEndMs - trimStartMs
                    )
                }
            }
            file.delete()
            result.onSuccess {
                MetroBanner.show(this@VideoEditorActivity, "Saved a copy")
                finishSaved()
            }.onFailure {
                hideExportOverlay()
                MetroBanner.show(this@VideoEditorActivity, "Save failed: ${it.message}")
            }
        }
    }

    private fun finishSaved() {
        setResult(RESULT_OK, Intent().putExtra(ExtraEdited, true))
        finish()
    }

    private fun confirmDiscardAndExit() {
        if (!edited) {
            finish()
            return
        }
        MetroDialog.confirm(
            this,
            title = "Discard changes?",
            message = "Your edits haven't been saved.",
            positive = "Discard",
            negative = "Keep editing"
        ) { finish() }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacksAndMessages(null)
        transformer?.cancel()
        transformer = null
        exportFile?.delete()
        binding.trimView.releaseFrames()
        player?.release()
        player = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ExtraUri = "editor_uri"
        const val ExtraName = "editor_name"
        // Same key as PhotoEditorActivity.ExtraEdited: the viewer uses one launcher for both editors.
        const val ExtraEdited = "editor_edited"
    }
}
