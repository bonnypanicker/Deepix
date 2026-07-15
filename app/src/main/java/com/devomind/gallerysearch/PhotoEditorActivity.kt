package com.devomind.gallerysearch

import android.app.RecoverableSecurityException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.devomind.gallerysearch.databinding.ActivityPhotoEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight in-app image editor: crop (+ rotate/flip/aspect), perspective correction, freehand
 * draw, and basic tuning (brightness/contrast/saturation + one-tap Document).
 *
 * Edits are recorded as a resolution-independent [EditOp] list (normalized to each intermediate
 * result). A downscaled base drives the interactive preview by replaying the ops; on save the same
 * ops are replayed on the **full-resolution** source, so exports keep the original detail.
 */
class PhotoEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoEditorBinding
    private var accentColor: Int = 0

    private lateinit var sourceUri: Uri
    private var displayName: String = "image"

    private var basePreview: Bitmap? = null          // downscaled source (immutable base for preview)
    private var working: Bitmap? = null              // current preview = replay(basePreview, ops)
    private val ops = ArrayList<EditOp>()
    private var edited = false

    private enum class Tool { CROP, PERSPECTIVE, DRAW, ADJUST }
    private var tool: Tool? = null

    private var pendingSaveBitmap: Bitmap? = null    // rendered full-res result awaiting write consent

    private sealed class EditOp {
        class Rotate(val degrees: Float) : EditOp()
        object Flip : EditOp()
        class Crop(val l: Float, val t: Float, val r: Float, val b: Float) : EditOp()
        class Perspective(val corners: FloatArray) : EditOp()
        class Adjust(val brightness: Float, val contrast: Float, val saturation: Float) : EditOp()
        class Draw(val strokes: List<PhotoEditOps.Stroke>) : EditOp()
    }

    private val writeRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && pendingSaveBitmap != null) {
            performOverwrite(afterConsent = true)
        } else {
            pendingSaveBitmap?.recycle()
            pendingSaveBitmap = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        accentColor = DesignTokens.accent(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        binding = ActivityPhotoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(ExtraUri)
        if (uriStr == null) { finish(); return }
        sourceUri = Uri.parse(uriStr)
        displayName = intent.getStringExtra(ExtraName)?.substringBeforeLast('.') ?: "image"

        wireChrome()
        buildSwatches()
        loadSource()
    }

    // ------------------------------------------------------------------ loading

    private fun loadSource() {
        binding.editorLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodePreview(sourceUri, EDIT_MAX_DIM) }
            binding.editorLoading.visibility = View.GONE
            if (bmp == null) {
                Toast.makeText(this@PhotoEditorActivity, "Couldn't open this image.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            basePreview = bmp
            working = bmp
            binding.editImage.setImageBitmap(bmp)
        }
    }

    private fun decodePreview(uri: Uri, maxEdge: Int): Bitmap? {
        val (rawW, rawH) = readBounds(uri) ?: return null
        var sample = 1
        while (maxOf(rawW, rawH) / sample > maxEdge) sample *= 2
        return decodeSampled(uri, sample)
    }

    private fun readBounds(uri: Uri): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    private fun decodeSampled(uri: Uri, inSampleSize: Int): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val orientation = runCatching {
            contentResolver.openInputStream(uri)?.use { s ->
                ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        applyExif(decoded, orientation)
    }.getOrNull()

    private fun applyExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }.getOrDefault(bitmap)
    }

    // ------------------------------------------------------------------ op replay

    /** Applies [list] to a copy pipeline starting from [base]; recycles intermediates, not [base]. */
    private fun replay(base: Bitmap, list: List<EditOp>): Bitmap {
        var cur = base
        for (op in list) {
            val next = when (op) {
                is EditOp.Rotate -> PhotoEditOps.rotate(cur, op.degrees)
                is EditOp.Flip -> PhotoEditOps.flipHorizontal(cur)
                is EditOp.Crop -> PhotoEditOps.crop(
                    cur,
                    Rect(
                        (op.l * cur.width).toInt(), (op.t * cur.height).toInt(),
                        (op.r * cur.width).toInt(), (op.b * cur.height).toInt()
                    )
                )
                is EditOp.Perspective -> {
                    val px = FloatArray(8)
                    for (i in 0 until 4) {
                        px[i * 2] = op.corners[i * 2] * cur.width
                        px[i * 2 + 1] = op.corners[i * 2 + 1] * cur.height
                    }
                    PhotoEditOps.perspective(cur, px)
                }
                is EditOp.Adjust -> PhotoEditOps.applyColorMatrix(
                    cur, PhotoEditOps.colorMatrix(op.brightness, op.contrast, op.saturation)
                )
                is EditOp.Draw -> PhotoEditOps.drawStrokes(cur, op.strokes)
            }
            if (cur !== base) cur.recycle()
            cur = next
        }
        return cur
    }

    private fun addOp(op: EditOp) {
        ops.add(op)
        edited = true
        rebuildPreview()
    }

    private fun rebuildPreview() {
        val base = basePreview ?: return
        val newWorking = replay(base, ops)
        val old = working
        working = newWorking
        binding.editImage.setImageBitmap(newWorking)
        binding.editImage.clearColorFilter()
        if (old != null && old !== base && old !== newWorking) old.recycle()
    }

    // ------------------------------------------------------------------ chrome

    private fun wireChrome() {
        binding.closeBtn.setOnClickListener { confirmDiscardAndExit() }
        binding.undoBtn.setOnClickListener { undo() }
        binding.saveBtn.setOnClickListener { showSaveMenu() }

        binding.toolCrop.setOnClickListener { enterTool(Tool.CROP) }
        binding.toolPerspective.setOnClickListener { enterTool(Tool.PERSPECTIVE) }
        binding.toolDraw.setOnClickListener { enterTool(Tool.DRAW) }
        binding.toolAdjust.setOnClickListener { enterTool(Tool.ADJUST) }

        binding.toolCancelBtn.setOnClickListener { cancelTool() }
        binding.toolApplyBtn.setOnClickListener { applyTool() }

        binding.cropFree.setOnClickListener { binding.cropView.setAspect(null) }
        binding.cropSquare.setOnClickListener { binding.cropView.setAspect(1f) }
        binding.crop43.setOnClickListener { binding.cropView.setAspect(4f / 3f) }
        binding.crop169.setOnClickListener { binding.cropView.setAspect(16f / 9f) }
        binding.rotateLeftBtn.setOnClickListener { rotateWorking(-90f) }
        binding.flipBtn.setOnClickListener { flipWorking() }

        binding.undoStrokeBtn.setOnClickListener { binding.drawView.undo() }
        binding.seekBrush.setOnSeekBarChangeListener(simpleSeek {
            binding.drawView.strokeWidth = dp((it + 2).toFloat())
        })

        val adjust = simpleSeek { updateAdjustPreview() }
        binding.seekBrightness.setOnSeekBarChangeListener(adjust)
        binding.seekContrast.setOnSeekBarChangeListener(adjust)
        binding.seekSaturation.setOnSeekBarChangeListener(adjust)
        binding.documentBtn.setOnClickListener {
            binding.seekBrightness.progress = 112
            binding.seekContrast.progress = 142
            binding.seekSaturation.progress = 35
            updateAdjustPreview()
        }

        onBackPressedDispatcher.addCallback(this) {
            if (tool != null) cancelTool() else confirmDiscardAndExit()
        }
    }

    private fun buildSwatches() {
        val colors = intArrayOf(
            Color.WHITE, Color.BLACK, 0xFFFF5252.toInt(), 0xFF3B9EFF.toInt(),
            0xFFFFD740.toInt(), 0xFF69F0AE.toInt()
        )
        val size = dp(30f).toInt()
        val margin = dp(6f).toInt()
        colors.forEachIndexed { index, color ->
            val v = View(this)
            val lp = android.widget.LinearLayout.LayoutParams(size, size)
            lp.marginEnd = margin
            v.layoutParams = lp
            v.background = swatchDrawable(color, selected = index == 0)
            v.setOnClickListener {
                binding.drawView.strokeColor = color
                for (i in 0 until binding.swatchRow.childCount) {
                    val child = binding.swatchRow.getChildAt(i)
                    child.background = swatchDrawable(colors[i], selected = child === v)
                }
            }
            binding.swatchRow.addView(v)
        }
        binding.drawView.strokeColor = colors[0]
    }

    private fun swatchDrawable(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(if (selected) 3f else 1f).toInt(), if (selected) accentColor else 0x55FFFFFF)
        }

    // ------------------------------------------------------------------ tools

    private fun enterTool(t: Tool) {
        tool = t
        binding.toolBar.visibility = View.GONE
        binding.toolActionBar.visibility = View.VISIBLE
        binding.panelCrop.visibility = View.GONE
        binding.panelPerspective.visibility = View.GONE
        binding.panelDraw.visibility = View.GONE
        binding.panelAdjust.visibility = View.GONE

        when (t) {
            Tool.CROP -> {
                binding.toolTitle.text = "Crop"
                binding.panelCrop.visibility = View.VISIBLE
                binding.cropView.setAspect(null)
            }
            Tool.PERSPECTIVE -> {
                binding.toolTitle.text = "Perspective"
                binding.panelPerspective.visibility = View.VISIBLE
            }
            Tool.DRAW -> {
                binding.toolTitle.text = "Draw"
                binding.panelDraw.visibility = View.VISIBLE
                binding.drawView.clear()
                binding.drawView.strokeWidth = dp((binding.seekBrush.progress + 2).toFloat())
            }
            Tool.ADJUST -> {
                binding.toolTitle.text = "Adjust"
                binding.panelAdjust.visibility = View.VISIBLE
                binding.seekBrightness.progress = 100
                binding.seekContrast.progress = 100
                binding.seekSaturation.progress = 100
                updateAdjustPreview()
            }
        }
        syncActiveToolBoundsAfterLayout()
    }

    private fun syncActiveToolBoundsAfterLayout() {
        binding.editArea.doOnPreDraw { syncActiveToolBounds() }
    }

    private fun syncActiveToolBounds() {
        val bounds = imageDisplayRect()
        when (tool) {
            Tool.CROP -> {
                binding.cropView.setImageBounds(bounds)
                binding.cropView.magnifierSource = working
                binding.cropView.visibility = View.VISIBLE
            }
            Tool.PERSPECTIVE -> {
                binding.quadView.setImageBounds(bounds)
                binding.quadView.magnifierSource = working
                binding.quadView.visibility = View.VISIBLE
            }
            Tool.DRAW -> {
                binding.drawView.setImageBounds(bounds)
                binding.drawView.visibility = View.VISIBLE
            }
            Tool.ADJUST,
            null -> Unit
        }
    }

    private fun exitTool() {
        tool = null
        binding.cropView.magnifierSource = null
        binding.quadView.magnifierSource = null
        binding.cropView.visibility = View.GONE
        binding.quadView.visibility = View.GONE
        binding.drawView.visibility = View.GONE
        binding.drawView.clear()
        binding.editImage.clearColorFilter()
        binding.toolActionBar.visibility = View.GONE
        binding.toolBar.visibility = View.VISIBLE
    }

    private fun cancelTool() = exitTool()

    private fun applyTool() {
        when (tool) {
            Tool.CROP -> binding.cropView.normalizedSelection()?.let {
                addOp(EditOp.Crop(it.left, it.top, it.right, it.bottom))
            }
            Tool.PERSPECTIVE -> binding.quadView.normalizedCorners()?.let {
                addOp(EditOp.Perspective(it))
            }
            Tool.DRAW -> {
                val strokes = binding.drawView.exportStrokes()
                if (strokes.isNotEmpty()) addOp(EditOp.Draw(strokes))
            }
            Tool.ADJUST -> {
                val b = (binding.seekBrightness.progress - 100).toFloat()
                val c = (binding.seekContrast.progress - 100).toFloat()
                val s = binding.seekSaturation.progress.toFloat()
                if (b != 0f || c != 0f || s != 100f) addOp(EditOp.Adjust(b, c, s))
            }
            null -> {}
        }
        exitTool()
    }

    private fun updateAdjustPreview() {
        val cm = PhotoEditOps.colorMatrix(
            brightness = (binding.seekBrightness.progress - 100).toFloat(),
            contrast = (binding.seekContrast.progress - 100).toFloat(),
            saturation = binding.seekSaturation.progress.toFloat()
        )
        binding.editImage.colorFilter = ColorMatrixColorFilter(cm)
    }

    private fun rotateWorking(degrees: Float) {
        binding.cropView.magnifierSource = null   // old working is about to be recycled
        addOp(EditOp.Rotate(degrees))
        if (tool == Tool.CROP) {
            binding.cropView.setAspect(null)
            syncActiveToolBoundsAfterLayout()
        }
    }

    private fun flipWorking() {
        binding.cropView.magnifierSource = null
        addOp(EditOp.Flip)
        if (tool == Tool.CROP) {
            syncActiveToolBoundsAfterLayout()
        }
    }

    private fun undo() {
        if (ops.isEmpty()) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            return
        }
        if (tool != null) cancelTool()
        ops.removeAt(ops.lastIndex)
        edited = ops.isNotEmpty()
        rebuildPreview()
    }

    // ------------------------------------------------------------------ save

    private fun showSaveMenu() {
        if (working == null) return
        val menu = androidx.appcompat.widget.PopupMenu(this, binding.saveBtn)
        menu.menu.add(0, 1, 0, "Save")
        menu.menu.add(0, 2, 1, "Save a copy")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { performOverwrite(afterConsent = false); true }
                2 -> { performSaveCopy(); true }
                else -> false
            }
        }
        menu.show()
    }

    /** Replays the ops on the full-resolution source, degrading resolution only if memory forces it. */
    private fun renderFullRes(): Bitmap {
        var sample = 1
        while (true) {
            var base: Bitmap? = null
            try {
                base = decodeSampled(sourceUri, sample) ?: throw IllegalStateException("decode failed")
                val out = replay(base, ops)
                if (out !== base) base.recycle()
                return out
            } catch (oom: OutOfMemoryError) {
                base?.recycle()
                sample *= 2
                if (sample > 16) throw oom
            }
        }
    }

    private fun performOverwrite(afterConsent: Boolean) {
        binding.editorLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = if (afterConsent) pendingSaveBitmap
            else withContext(Dispatchers.IO) { runCatching { renderFullRes() }.getOrNull() }
            if (bmp == null) {
                binding.editorLoading.visibility = View.GONE
                Toast.makeText(this@PhotoEditorActivity, "Save failed", Toast.LENGTH_LONG).show()
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { MediaImageSaver.overwrite(this@PhotoEditorActivity, sourceUri, bmp) }
            }
            binding.editorLoading.visibility = View.GONE
            result.onSuccess {
                pendingSaveBitmap = null
                bmp.recycle()
                finishSaved()
            }.onFailure { error ->
                val recoverable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    error is RecoverableSecurityException
                when {
                    !afterConsent && recoverable -> {
                        pendingSaveBitmap = bmp
                        writeRequestLauncher.launch(
                            IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build()
                        )
                    }
                    !afterConsent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        pendingSaveBitmap = bmp
                        val pi = android.provider.MediaStore.createWriteRequest(contentResolver, listOf(sourceUri))
                        writeRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    }
                    else -> {
                        pendingSaveBitmap = null
                        bmp.recycle()
                        Toast.makeText(this@PhotoEditorActivity, "Save failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun performSaveCopy() {
        binding.editorLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = renderFullRes()
                    val uri = MediaImageSaver.saveCopy(this@PhotoEditorActivity, bmp, displayName)
                    bmp.recycle()
                    uri
                }
            }
            binding.editorLoading.visibility = View.GONE
            result.onSuccess {
                Toast.makeText(this@PhotoEditorActivity, "Saved a copy", Toast.LENGTH_SHORT).show()
                finishSaved()
            }.onFailure {
                Toast.makeText(this@PhotoEditorActivity, "Save failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishSaved() {
        setResult(RESULT_OK, Intent().putExtra(ExtraEdited, true))
        finish()
    }

    private fun confirmDiscardAndExit() {
        if (!edited) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Discard changes?")
            .setMessage("Your edits haven't been saved.")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton("Keep editing", null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    /** On-screen rect of the actual ImageView drawable, in editArea/overlay coordinates. */
    private fun imageDisplayRect(): RectF {
        val bmp = working ?: return RectF()
        val drawable = binding.editImage.drawable
        if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            val rect = RectF(
                0f,
                0f,
                drawable.intrinsicWidth.toFloat(),
                drawable.intrinsicHeight.toFloat()
            )
            binding.editImage.imageMatrix.mapRect(rect)
            rect.offset(binding.editImage.left.toFloat(), binding.editImage.top.toFloat())
            return rect
        }

        val vw = binding.editImage.width.toFloat()
        val vh = binding.editImage.height.toFloat()
        if (vw <= 0f || vh <= 0f) return RectF(0f, 0f, vw, vh)
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDestroy() {
        super.onDestroy()
        binding.editImage.setImageDrawable(null)
        working?.let { if (it !== basePreview) it.recycle() }
        basePreview?.recycle()
        pendingSaveBitmap?.recycle()
        working = null
        basePreview = null
        pendingSaveBitmap = null
    }

    companion object {
        const val ExtraUri = "editor_uri"
        const val ExtraName = "editor_name"
        const val ExtraEdited = "editor_edited"
        private const val EDIT_MAX_DIM = 2560
    }
}
