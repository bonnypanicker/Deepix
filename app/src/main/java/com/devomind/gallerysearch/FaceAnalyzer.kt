package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.devomind.gallerysearch.db.FaceEntity
import com.devomind.gallerysearch.db.GalleryDatabase
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 1 orchestrator: photo → detected faces → quality / pose / aligned crop → face embedding
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

    /** One face bundled for the UI — detection + derived metadata + optionally the aligned crop.
     * [detection] is always in the original (EXIF-upright) photo's coordinate space. */
    data class AnalyzedFace(
        val detection: YuNetDetector.FaceDetection,
        val quality: Float,
        val pose: FacePoseEstimator.Pose,
        /** Whether the detection is eligible to contribute an identity embedding / person match. */
        val recognitionEligible: Boolean,
        val embedding: FloatArray?,
        val alignedCrop: Bitmap?,
        /** Clockwise quarter-turn of the frame the face cleared the accept confidence in (0 = upright). */
        val rotationDegrees: Int = 0
    )

    data class PhotoResult(
        val photoUri: String,
        val faces: List<AnalyzedFace>,
        val decodeMs: Long,
        val detectMs: Long,
        val embedMs: Long
    )

    /**
     * A face that survived the acceptance policy. [original] is in the upright photo's coordinate
     * space (persisted / drawn), [source] the frame it cleared the accept confidence in — the
     * input bitmap itself when the upright pass accepted it, otherwise a quarter-turn rotation.
     * [ownedSource] is non-null when the retry loop created [source]; the caller recycles it once
     * the aligned crops exist.
     */
    private data class ResolvedFace(
        val original: YuNetDetector.FaceDetection,
        val source: Bitmap,
        val oriented: YuNetDetector.FaceDetection,
        val ownedSource: Bitmap?,
        /** Clockwise quarter-turn of [source] relative to the original (0 = upright pass). */
        val rotationDegCw: Int
    )

    /**
     * Apply the acceptance policy to [bitmap]: faces at or above [YuNetDetector.AcceptConfidence]
     * are taken as-is; faces in [YuNetDetector.RotationRetryFloor, [YuNetDetector.AcceptConfidence])
     * are retried once per quarter-turn (+90°, −90°, 180°) and kept only if the rotated pass
     * re-detects them at or above the accept confidence; everything else is dropped.
     *
     * Efficiency: rotated passes run at most three times per photo, only while unrescued faces
     * remain, and a single pass settles every pending face at once. Photos whose detections are
     * all strong (the common case) cost exactly the one upright pass they cost today.
     */
    private fun resolveDetections(bitmap: Bitmap): List<ResolvedFace> {
        val primary = detector.detectFaces(bitmap, YuNetDetector.RotationRetryFloor)
        val resolved = ArrayList<ResolvedFace>(primary.size)
        val retry = ArrayList<YuNetDetector.FaceDetection>()
        for (det in primary) {
            if (det.confidence >= YuNetDetector.AcceptConfidence) {
                resolved += ResolvedFace(
                    original = det,
                    source = bitmap,
                    oriented = det,
                    ownedSource = null,
                    rotationDegCw = 0
                )
            } else {
                retry += det
            }
        }
        for (rotation in RetryRotations) {
            if (retry.isEmpty()) break
            val rotated = rotate(bitmap, rotation)
            val rotatedDetections = detector.detectFaces(rotated, YuNetDetector.AcceptConfidence)
            var matched = false
            val claimed = HashSet<YuNetDetector.FaceDetection>()
            val iterator = retry.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val expected = mapDetection(candidate, bitmap.width, bitmap.height, rotation, inverse = false)
                var best: YuNetDetector.FaceDetection? = null
                var bestIou = YuNetDetector.RotationMatchIoU
                for (rotatedDetection in rotatedDetections) {
                    if (rotatedDetection in claimed) continue
                    val iou = intersectionOverUnion(expected, rotatedDetection)
                    if (iou >= bestIou) {
                        bestIou = iou
                        best = rotatedDetection
                    }
                }
                if (best != null) {
                    claimed += best
                    matched = true
                    iterator.remove()
                    resolved += ResolvedFace(
                        original = mapDetection(best, bitmap.width, bitmap.height, rotation, inverse = true),
                        source = rotated,
                        oriented = best,
                        ownedSource = rotated,
                        rotationDegCw = rotation
                    )
                }
            }
            if (!matched) rotated.recycle()
        }
        return resolved
    }

    /** [degreesCw]-clockwise quarter-turn copy of [source]; the caller owns the result. */
    private fun rotate(source: Bitmap, degreesCw: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degreesCw.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Map a box + landmarks between the original frame and the frame produced by rotating the
     * image [degreesCw] clockwise ([inverse] = rotated → original). A face's axis-aligned box
     * stays axis-aligned under quarter-turns, so mapping two corners suffices.
     */
    private fun mapDetection(
        det: YuNetDetector.FaceDetection,
        srcW: Int,
        srcH: Int,
        degreesCw: Int,
        inverse: Boolean
    ): YuNetDetector.FaceDetection {
        fun mapX(x: Float, y: Float): Float = when (degreesCw) {
            90 -> if (inverse) y else srcH - y
            270 -> if (inverse) srcW - y else y
            else -> srcW - x // 180
        }

        fun mapY(x: Float, y: Float): Float = when (degreesCw) {
            90 -> if (inverse) srcH - x else x
            270 -> if (inverse) x else srcW - x
            else -> srcH - y // 180
        }
        val left = mapX(det.left, det.top)
        val top = mapY(det.left, det.top)
        val right = mapX(det.right, det.bottom)
        val bottom = mapY(det.right, det.bottom)
        return YuNetDetector.FaceDetection(
            left = min(left, right),
            top = min(top, bottom),
            width = abs(right - left),
            height = abs(bottom - top),
            confidence = det.confidence,
            landmarks = Array(det.landmarks.size) { i ->
                floatArrayOf(
                    mapX(det.landmarks[i][0], det.landmarks[i][1]),
                    mapY(det.landmarks[i][0], det.landmarks[i][1])
                )
            }
        )
    }

    private fun intersectionOverUnion(
        first: YuNetDetector.FaceDetection,
        second: YuNetDetector.FaceDetection
    ): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = (first.width * first.height + second.width * second.height - intersection).coerceAtLeast(1e-6f)
        return intersection / union
    }

    /**
     * Run the full Phase 1 pipeline for one photo. If [persist] is true the faces are written to
     * Room (with their embeddings). [includeAlignedCrops] keeps the 112×112 bitmaps for UI display.
     *
     * Detection filtering: only faces at or above [YuNetDetector.AcceptConfidence] survive the
     * upright pass; faces in [YuNetDetector.RotationRetryFloor, [YuNetDetector.AcceptConfidence])
     * survive only if a quarter-turn re-detection (+90°/−90°/180°) reaches the accept confidence —
     * those are aligned and embedded from the rotated frame, while their stored box and landmarks
     * stay in the original photo's coordinate space.
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
        val resolved = resolveDetections(bitmap)
        val detectMs = SystemClock() - detectStart

        val embedStart = SystemClock()
        val analyzed = ArrayList<AnalyzedFace>(resolved.size)
        val entities = ArrayList<FaceEntity>(resolved.size)

        for (face in resolved) {
            // Alignment, quality, pose and the MobileFaceNet embedding all run in the frame the
            // face cleared the accept confidence in — a sideways face becomes upright there.
            val aligned = FaceAligner.align(face.source, face.oriented.landmarks)
            val qualityRaw = FaceQualityScorer.score(face.source, face.oriented)
            // Belt-and-braces sanitize: NaN propagates through Score → Room's NOT NULL bind.
            val quality = if (qualityRaw.isNaN()) 0f else qualityRaw.coerceIn(0f, 1f)
            val pose = FacePoseEstimator.estimate(face.oriented)
            val recognitionEligible = FaceRecognizabilityGate.isEligible(face.oriented, quality, pose)

            val embedding = if (recognitionEligible) {
                runCatching { embedder.embed(aligned) }
                    .onFailure { Log.w(Tag, "Embedding failed for face", it) }
                    .getOrNull()
            } else {
                null
            }

            entities += FaceEntity(
                photoUri = photoUri.toString(),
                bboxJson = bboxToJson(face.original),
                landmarksJson = landmarksToJson(face.original.landmarks),
                embeddingJson = embedding?.let { embeddingToJson(it) },
                embeddingModelVersion = FaceEmbedder.ModelVersion,
                qualityScore = quality,
                yaw = pose.yaw,
                pitch = pose.pitch,
                roll = pose.roll,
                rotationDegrees = face.rotationDegCw,
                isLowQuality = !recognitionEligible
            )

            analyzed += AnalyzedFace(
                detection = face.original,
                quality = quality,
                pose = pose,
                recognitionEligible = recognitionEligible,
                embedding = embedding,
                alignedCrop = if (includeAlignedCrops) aligned else aligned.also { it.recycle() },
                rotationDegrees = face.rotationDegCw
            )
        }
        val embedMs = SystemClock() - embedStart
        // Rotation retry frames are only needed until the aligned crops exist.
        resolved.forEach { it.ownedSource?.recycle() }

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

        /** Clockwise quarter-turn retry order: +90° first, then −90°, then 180°. */
        val RetryRotations = intArrayOf(90, 270, 180)
    }
}
