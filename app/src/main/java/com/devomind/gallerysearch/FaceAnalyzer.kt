package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.devomind.gallerysearch.db.FaceEntity
import com.devomind.gallerysearch.db.GalleryDatabase
import org.json.JSONArray
import kotlin.math.sqrt

/**
 * Phase 1 orchestrator: photo → detected faces → quality / pose / aligned crop → 512-d embedding
 * → persisted into Room. Returns a per-photo result bundle the validation UI can render.
 */
class FaceAnalyzer(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val database = GalleryDatabase.getInstance(appContext)
    private val detector = YuNetDetector(appContext)
    // Shared process-wide MobileFaceNet session satisfies the Phase 1 spec: single OrtEnvironment
    // plus 2–4 intra-op threads for the MobileFaceNet session, no separate process.
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
     */
    suspend fun analyze(photoUri: Uri, persist: Boolean, includeAlignedCrops: Boolean = true): PhotoResult {
        val decodeStart = SystemClock()
        val bitmap = GalleryRepository(appContext).loadBitmap(photoUri)
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
            val quality = FaceQualityScorer.score(bitmap, det)
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
            face.embeddingJson?.let { json ->
                runCatching { jsonToEmbedding(json) }.getOrNull()?.let { face.faceId to it }
            }
        }
    }

    override fun close() {
        detector.close()
        // FaceEmbedder is shared via SharedEncoders — leave it open for other callers.
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
        const val ThreadCount = 2
        const val LowQualityThreshold = 0.35f
    }
}
