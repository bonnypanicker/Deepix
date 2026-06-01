package com.devomind.gallerysearch

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.devomind.gallerysearch.databinding.ActivityViewerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityViewerBinding
    private var controlsVisible = true
    private var infoVisible = false
    private var uri: Uri? = null
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { setControlsVisible(false) }
    private var downY = 0f
    private var dragDistance = 0f
    private var draggingToDismiss = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        uri = intent.data
        val currentUri = uri
        if (currentUri == null) {
            finish()
            return
        }

        Glide.with(this)
            .load(currentUri)
            .fitCenter()
            .into(binding.photoView)

        val metadata = loadMetadata(currentUri)
        bindMetadata(metadata)
        bindActions(currentUri)
        binding.infoPanel.post {
            binding.infoPanel.translationY = binding.infoPanel.height.toFloat()
        }
        scheduleAutoHide()
    }

    private fun bindActions(uri: Uri) {
        binding.backBtn.setOnClickListener { finish() }
        binding.photoView.setOnClickListener {
            if (!draggingToDismiss) toggleControls()
        }
        binding.infoBtn.setOnClickListener { toggleInfoPanel() }
        binding.shareBtn.setOnClickListener { share(uri) }
        binding.favoriteBtn.setOnClickListener {
            Toast.makeText(this, "Favorite state comes with the media database pass.", Toast.LENGTH_SHORT).show()
        }
        binding.editBtn.setOnClickListener {
            Toast.makeText(this, "Editor opens in the next feature pass.", Toast.LENGTH_SHORT).show()
        }
        binding.deleteBtn.setOnClickListener { confirmDelete(uri) }
        binding.viewerRoot.setOnTouchListener { _, event ->
            handleViewerTouch(event)
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
                    binding.photoView.translationY = dragDistance
                    val scale = 1f - (progress * 0.08f)
                    binding.photoView.scaleX = scale
                    binding.photoView.scaleY = scale
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
                        binding.photoView.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(220).start()
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
            type = "image/*"
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

    private fun deletePhoto(uri: Uri) {
        runCatching {
            contentResolver.delete(uri, null, null)
            finish()
        }.onFailure { error ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
                startIntentSenderForResult(error.userAction.actionIntent.intentSender, DeleteRequestCode, null, 0, 0, 0)
            } else {
                Toast.makeText(this, "Delete failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadMetadata(uri: Uri): PhotoMetadata {
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
                    mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
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
                        mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
                    )
                }
            }
        }
        return PhotoMetadata()
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
        val mimeType: String? = null
    )

    companion object {
        private const val DeleteRequestCode = 904
    }

    override fun onDestroy() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
        super.onDestroy()
    }
}
