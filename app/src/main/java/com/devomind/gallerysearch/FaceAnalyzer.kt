package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.devomind.gallerysearch.db.FaceEntity
import com.devomind.gallerysearch.db.GalleryDatabase
import org.json.JSONArray

/**
 * Phase 1 orchestrator: photo → detected faces → quality / pose / aligned crop → 512-d embedding
 * → persisted into Room. Returns a per-photo result bundle the validation UI can render.
 */
class FaceAnalyzer(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val database = GalleryDatabase.getInstance(appContext)
    // Both ORT sessions are shared process-wide via SharedEncoders: the Phase 1 spec asks for a
    // single OrtEnvironment with 2–4 intra-op threads, and the Phase 2 indexing loop constructs a
    // FaceAnalyzer per photo — a per-instance session would re-read the model asset every image.
    private val detector: YuNetDetector
        get() = (appContext as GallerySearchApp).sharedEncoders.getFaceDetector()
    private val embedder: FaceEmbedder
        get() = (appContext as GallerySearchApp).sharedEncoders.getFaceEmbedder()

    /** One face bundled for the UI — detection + derived metadata + optionally the aligned crop. */
    data class AnalyzedFace(
        val detection: YuNetDetector.FaceDetection,
        val quality: Float,
        val pose: FacePoseEstimator.Pose,
        val embedding: FloatArray?,
        val alignedCrop: Bitmap?
    )

    data class PhotoResult(
        val photoUri: String,
        val faces: List<AnalyzedFace>,
        val decodeMs: Long,
        val detectMs: Long,
        val embedMs: Long
    )

    /**
     * Run the full Phase 1 pipeline for one photo. If [persist] is true the faces are written to
     * Room (with their embeddings). [includeAlignedCrops] keeps the 112×112 bitmaps for UI display.
     *
     * [decoded] lets a caller that already holds a face-detection-scale bitmap hand it in rather
     * than paying a second decode; detection coordinates come back in that bitmap's space, so it
     * must have come from [GalleryRepository.loadBitmapForFaceDetection]. Ownership stays with the
     * caller — this method never recycles a bitmap it did not decode.
     */
    suspend fun analyze(
        photoUri: Uri,
        persist: Boolean,
        includeAlignedCrops: Boolean = true,
        decoded: Bitmap? = null
    ): PhotoResult {
        val decodeStart = SystemClock()
        // Face-specific decode cap (1536px): detection coordinates are in this bitmap's space,
        // so any UI drawing boxes over the photo must decode via the same path.
        val bitmap = decoded
            ?: GalleryRepository(appContext).loadBitmapForFaceDetection(photoUri)
            ?: error("Could not decode $photoUri")
        val decodeMs = SystemClock() - decodeStart

        val detectStart = SystemClock()
        val detections = detector.detectFaces(bitmap)
        val detectMs = SystemClock() - detectStart

        val embedStart = SystemClock()
        val analyzed = ArrayList<AnalyzedFace>(detections.size)
        val entities = ArrayList<FaceEntity>(detections.size)

        for (det in detections) {
            val aligned = FaceAligner.align(bitmap, det.landmarks)
            val qualityRaw = FaceQualityScorer.score(bitmap, det)
            // Belt-and-braces sanitize: NaN propagates through Score → Room's NOT NULL bind.
            val quality = if (qualityRaw.isNaN()) 0f else qualityRaw.coerceIn(0f, 1f)
            val pose = FacePoseEstimator.estimate(det)
            val isLowQuality = quality < LowQualityThreshold

            val embedding = runCatching { embedder.embed(aligned) }
                .onFailure { Log.w(Tag, "Embedding failed for face", it) }
                .getOrNull()

            entities += FaceEntity(
                photoUri = photoUri.toString(),
                bboxJson = bboxToJson(det),
                landmarksJson = landmarksToJson(det.landmarks),
                embeddingJson = embedding?.let { embeddingToJson(it) },
                embeddingModelVersion = FaceEmbedder.ModelVersion,
                qualityScore = quality,
                yaw = pose.yaw,
                pitch = pose.pitch,
                roll = pose.roll,
                isLowQuality = isLowQuality
            )

            analyzed += AnalyzedFace(
                detection = det,
                quality = quality,
                pose = pose,
                embedding = embedding,
                alignedCrop = if (includeAlignedCrops) aligned else aligned.also { it.recycle() }
            )
        }
        val embedMs = SystemClock() - embedStart

        if (persist && entities.isNotEmpty()) {
            database.faceDao().insertAll(entities)
        }

        return PhotoResult(
            photoUri = photoUri.toString(),
            faces = analyzed,
            decodeMs = decodeMs,
            detectMs = detectMs,
            embedMs = embedMs
        )
    }

    /** Cosine similarity between two stored / in-memory embeddings. */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float = FaceEmbedder.cosineSimilarity(a, b)

    /** All embeddings previously persisted — lets the UI show cross-photo similarity checks. */
    suspend fun allStoredEmbeddings(): List<Pair<Long, FloatArray>> {
        return database.faceDao().findAllWithEmbeddings().mapNotNull { face ->
            face.embeddingJson?.takeIf { face.embeddingModelVersion == FaceEmbedder.ModelVersion }?.let { json ->
                runCatching { jsonToEmbedding(json) }.getOrNull()?.let { face.faceId to it }
            }
        }
    }

    override fun close() {
        // Both the YuNet detector and the FaceEmbedder are shared via SharedEncoders — leave them
        // open for other callers. FaceAnalyzer itself holds no per-instance native resources.
    }

    private fun bboxToJson(det: YuNetDetector.FaceDetection): String =
        JSONArray().apply {
            put(det.left); put(det.top); put(det.width); put(det.height)
        }.toString()

    private fun landmarksToJson(landmarks: Array<FloatArray>): String =
        JSONArray().apply {
            landmarks.forEach { pt -> put(JSONArray().apply { put(pt[0]); put(pt[1]) }) }
        }.toString()

    private fun embeddingToJson(embedding: FloatArray): String =
        JSONArray().apply { embedding.forEach { put(it.toDouble()) } }.toString()

    private fun jsonToEmbedding(json: String): FloatArray {
        val arr = JSONArray(json)
        return FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
    }

    private inline fun SystemClock(): Long = android.os.SystemClock.elapsedRealtime()

    private companion object {
        const val Tag = "FaceAnalyzer"
        const val LowQualityThreshold = 0.35f
    }
}
