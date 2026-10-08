package com.devomind.gallerysearch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.devomind.gallerysearch.databinding.ActivityFaceValidationBinding
import com.devomind.gallerysearch.db.GalleryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Phase 1 verification UI: pick a photo → YuNet detection + quality + pose + embedding.
 * Lets you visually confirm bboxes and landmarks, inspect per-face crops, and test cross-photo
 * similarity between any two photos, without waiting for Phases 2–5. Now also surfaces the
 * Phase 2 people-index status (running stats + person/face counters). */
class FaceValidationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceValidationBinding

    private var analyzer: FaceAnalyzer? = null

    private var firstPhotoResult: FaceAnalyzer.PhotoResult? = null
    private var firstPhotoBitmap: Bitmap? = null
    private var comparePhotoResult: FaceAnalyzer.PhotoResult? = null

    /** True once we've started observing FaceIndexWorker's status line. */
    private var observingFaceIndex = false

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { runAnalysis(it, isCompare = false) }
    }

    private val pickComparePhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { runAnalysis(it, isCompare = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentPalette.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        binding = ActivityFaceValidationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnPickPhoto.setOnClickListener { pickPhoto.launch("image/*") }
        binding.btnPickSecond.setOnClickListener {
            if (firstPhotoResult == null) {
                android.widget.Toast.makeText(
                    this,
                    "pick a primary photo first",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                pickComparePhoto.launch("image/*")
            }
        }

        // Phase 2 manual kick: let you fire the people-index pass from the harness; live status
        // appears in the monospace line below.
        binding.btnRunPeopleIndex.setOnClickListener {
            FaceIndexWorker.enqueue(this, replaceExisting = true)
            observingFaceIndex() // triggers the observer below if not already
            binding.peopleIndexStatus.text = "workers queued — watching for progress…"
        }
        observingFaceIndex() // start observing immediately too, so if work is already running we show it
    }

    /** Once-per-activity observer of FaceIndexWorker progress + status. */
    private fun observingFaceIndex() {
        if (observingFaceIndex) return
        observingFaceIndex = true

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(FaceIndexWorker.WorkName)
            .observe(this) { infos ->
                val work = infos.firstOrNull() ?: return@observe
                if (work.state != WorkInfo.State.RUNNING && work.state != WorkInfo.State.SUCCEEDED) return@observe
                val visited = work.progress.getInt(FaceIndexWorker.ProgressVisitedKey, -1)
                val total = work.progress.getInt(FaceIndexWorker.ProgressTotalKey, -1)
                val faces = work.progress.getInt(FaceIndexWorker.StatsFacesKey, 0)
                val persons = work.progress.getInt(FaceIndexWorker.StatsPersonsKey, 0)
                val assigned = work.progress.getInt(FaceIndexWorker.StatsAssignedKey, 0)
                val gated = work.progress.getInt(FaceIndexWorker.StatsGatedKey, 0)
                val skipped = work.progress.getInt(FaceIndexWorker.StatsSkippedKey, 0)
                binding.peopleIndexStatus.text = buildString {
                    if (total > 0 && visited >= 0) append("idx %3d%% · %d/%d · ".format(visited * 100 / total, visited, total))
                    append(
                        "faces=%d persons=%d assigned=%d gated=%d skipped=%d"
                            .format(faces, persons, assigned, gated, skipped)
                    )
                    append(" · ${work.state.name}")
                }
            }
    }

    override fun onDestroy() {
        analyzer?.close()
        firstPhotoBitmap?.recycle()
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.validationRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top)
            binding.root.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun runAnalysis(uri: Uri, isCompare: Boolean) {
        binding.loading.visibility = View.VISIBLE
        binding.btnPickPhoto.isEnabled = false
        binding.btnPickSecond.isEnabled = false

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    val current = analyzer ?: FaceAnalyzer(applicationContext).also { analyzer = it }
                    val repository = GalleryRepository(applicationContext)
                    // Must match FaceAnalyzer's decode path: detection coordinates are in the
                    // face-detection bitmap's space, and we draw them straight onto this bitmap.
                    val bitmap = repository.loadBitmapForFaceDetection(uri) ?: error("decode failed")
                    val result = current.analyze(
                        uri, persist = !isCompare, includeAlignedCrops = true, decoded = bitmap
                    )
                    bitmap to result
                }
            }

            binding.loading.visibility = View.GONE
            binding.btnPickPhoto.isEnabled = true
            binding.btnPickSecond.isEnabled = true

            outcome.fold(
                onSuccess = { (bitmap, result) ->
                    if (isCompare) {
                        comparePhotoResult?.faces?.forEach { it.alignedCrop?.recycle() }
                        bitmap.recycle()
                        comparePhotoResult = result
                        updateComparison()
                    } else {
                        firstPhotoBitmap?.recycle()
                        firstPhotoResult?.faces?.forEach { it.alignedCrop?.recycle() }
                        firstPhotoBitmap = bitmap
                        firstPhotoResult = result
                        comparePhotoResult = null
                        renderPrimaryResult(result)
                        binding.compareHeader.visibility = View.GONE
                        binding.compareBody.visibility = View.GONE
                    }
                },
                onFailure = { error ->
                    binding.summaryHeader.visibility = View.VISIBLE
                    binding.summaryBody.visibility = View.VISIBLE
                    binding.summaryBody.text = "analysis failed: ${error.message}"
                }
            )
        }
    }

    private fun renderPrimaryResult(result: FaceAnalyzer.PhotoResult) {
        val bitmap = firstPhotoBitmap ?: return
        binding.photoUriLabel.visibility = View.VISIBLE
        binding.photoUriLabel.text =
            getString(R.string.face_validation_photo_label, result.photoUri.takeLast(48))

        // Render the source photo with bbox + landmark overlay.
        binding.sourceImage.setImageBitmap(overlayDetections(bitmap, result.faces))

        binding.perfRow.visibility = View.VISIBLE
        binding.perfRow.text =
            getString(R.string.face_validation_perf, result.decodeMs, result.detectMs, result.embedMs)

        // --- Summary block -------------------------------------------------
        binding.summaryHeader.visibility = View.VISIBLE
        binding.summaryBody.visibility = View.VISIBLE
        binding.summaryBody.text = buildString {
            appendLine("image size: ${bitmap.width} × ${bitmap.height}")
            appendLine("faces found: ${result.faces.size}")
            if (result.faces.isNotEmpty()) {
                val avgQ = result.faces.map { it.quality }.average()
                val avgC = result.faces.map { it.detection.confidence }.average()
                appendLine("avg quality: %.2f".format(avgQ))
                appendLine("avg detector confidence: %.2f".format(avgC))
            }

            // Cross-face similarity within the same photo (diagnostic).
            if (result.faces.size >= 2) {
                val sims = mutableListOf<String>()
                for (i in 0 until result.faces.size - 1) {
                    for (j in i + 1 until result.faces.size) {
                        val a = result.faces[i].embedding
                        val b = result.faces[j].embedding
                        if (a != null && b != null) {
                            sims += "%d↔%d: %.3f".format(i + 1, j + 1,
                                FaceEmbedder.cosineSimilarity(a, b))
                        }
                    }
                }
                if (sims.isNotEmpty()) {
                    appendLine("intra-photo similarity:")
                    sims.forEach { appendLine("  face$it") }
                }
            }
        }

        // --- Per-face detail list ------------------------------------------
        binding.facesHeader.visibility = if (result.faces.isEmpty()) View.GONE else View.VISIBLE
        binding.facesContainer.removeAllViews()
        result.faces.forEachIndexed { index, face ->
            binding.facesContainer.addView(faceRow(index, face))
        }
    }

    private fun faceRow(index: Int, face: FaceAnalyzer.AnalyzedFace): View {
        val ctx = this
        val padH = resources.displayMetrics.density * 12
        val padV = resources.displayMetrics.density * 10
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, padV.toInt(), 0, padV.toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        face.alignedCrop?.let { crop ->
            val imageSize = (resources.displayMetrics.density * 96).toInt()
            val imageView = android.widget.ImageView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(imageSize, imageSize)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setImageBitmap(crop)
                setBackgroundColor(0x33FFFFFF)
            }
            row.addView(imageView)
        }

        val text = android.widget.TextView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = padH.toInt() }
            setTextColor(resources.getColor(R.color.metroTextPrimary, theme))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            text = formatFaceDetail(index, face)
        }
        row.addView(text)
        return row
    }

    private fun formatFaceDetail(index: Int, face: FaceAnalyzer.AnalyzedFace): String = buildString {
        val d = face.detection
        appendLine("face #${index + 1}")
        appendLine("  bbox=[%.0f,%.0f %.0f×%.0f]".format(d.left, d.top, d.width, d.height))
        appendLine("  confidence: %.3f".format(d.confidence))
        appendLine("  quality: %.2f %s".format(
            face.quality,
            if (!face.recognitionEligible) "(excluded from person matching)" else ""
        ))
        appendLine("  pose → yaw %.1f°  pitch %.1f°  roll %.1f°".format(
            face.pose.yaw, face.pose.pitch, face.pose.roll
        ))
        face.embedding?.let { emb ->
            // L2 norm should be ~1.0 (sanity check), mean ~0.
            var norm = 0f
            var mean = 0f
            for (v in emb) { norm += v * v; mean += v }
            norm = kotlin.math.sqrt(norm)
            mean /= emb.size
            appendLine("  embedding: %d dims, ‖x‖=%.3f, μ=%.4f".format(emb.size, norm, mean))
            val head = emb.take(5).joinToString(" ") { "%.3f".format(Locale.US, it) }
            appendLine("  emb[0..4] = [$head]")
        } ?: appendLine("  embedding: FAILED")
    }

    private fun updateComparison() {
        val first = firstPhotoResult ?: return
        val second = comparePhotoResult ?: return

        binding.compareHeader.visibility = View.VISIBLE
        binding.compareBody.visibility = View.VISIBLE
        val header = buildString {
            appendLine("photo A: ${first.faces.size} face(s)")
            appendLine("photo B: ${second.faces.size} face(s)")
            appendLine("")
        }

        val pairs = StringBuilder()
        val maxFaces = maxOf(first.faces.size, second.faces.size)
        if (maxFaces == 0) {
            pairs.append("no faces in either photo")
        } else {
            var bestPair: Triple<Int, Int, Float>? = null
            first.faces.forEachIndexed { i, a ->
                second.faces.forEachIndexed { j, b ->
                    val ea = a.embedding
                    val eb = b.embedding
                    if (ea != null && eb != null) {
                        val sim = FaceEmbedder.cosineSimilarity(ea, eb)
                        pairs.appendLine("A#%d  ×  B#%d   cos=%.3f  %s".format(
                            i + 1, j + 1, sim,
                            when {
                                sim >= FaceEmbedder.MatchThresholdCosine -> "◀ same person?"
                                sim <= 0.30f -> "(likely different)"
                                else -> ""
                            }
                        ))
                        if (bestPair == null || sim > bestPair!!.third) {
                            bestPair = Triple(i, j, sim)
                        }
                    }
                }
            }
            bestPair?.let { (i, j, sim) ->
                pairs.insert(0, "best pair: A#${i + 1} × B#${j + 1}  cos=%.3f\n\n".format(sim))
            }
        }
        binding.compareBody.text = header + pairs.toString()
    }

    private fun overlayDetections(source: Bitmap, faces: List<FaceAnalyzer.AnalyzedFace>): Bitmap {
        val out = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val bboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = 0xFF4CAF50.toInt()
        }
        val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFEB3B.toInt()
        }
        val lmLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFEB3B.toInt()
            textSize = 22f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4CAF50.toInt()
            textSize = 32f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        faces.forEach { face ->
            val d = face.detection
            canvas.drawRect(RectF(d.left, d.top, d.right, d.bottom), bboxPaint)
            // Labels are in canonical order: 0:LE 1:RE 2:N 3:LM 4:RM. If these sit on the wrong
            // physical feature, the detector's delivered landmark order is inverted — flip the
            // reorder in YuNetDetector.decodeStride. (Embeddings stay correct either way: the
            // aligner auto-detects the order that best fits the canonical template.)
            val lmLabels = arrayOf("0:LE", "1:RE", "2:N", "3:LM", "4:RM")
            d.landmarks.forEachIndexed { i, pt ->
                canvas.drawCircle(pt[0], pt[1], 6f, landmarkPaint)
                canvas.drawText(lmLabels.getOrElse(i) { "$i" }, pt[0] + 8, pt[1] - 8, lmLabelPaint)
            }
            canvas.drawText("%.2f · q=%.2f".format(d.confidence, face.quality), d.left + 4, d.top - 8, labelPaint)
        }
        return out
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, FaceValidationActivity::class.java))
        }
    }
}
