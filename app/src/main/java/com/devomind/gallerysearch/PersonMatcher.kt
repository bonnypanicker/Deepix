package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import com.devomind.gallerysearch.db.FaceDao
import com.devomind.gallerysearch.db.FaceEntity
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.PersonDao
import com.devomind.gallerysearch.db.PersonEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.sqrt

/**
 * Phase 2 person matching.
 *
 * Algorithm, per new face:
 *  1. **Centroid pre-filter:** compute the cosine of the new embedding against each person's
 *     centroid (mean of that person's exemplar embeddings, L2-renormalized) — one dot product
 *     per person, cheap. Keep only the top [CentroidCandidates] whose centroid sim is at least
 *     [CentroidPreFilterFloor]. If none clear the floor, skip straight to creating a new person.
 *  2. **Exemplar-vote:** for each surviving candidate, compute cosine to its top
 *     [MaxExemplarsPerPerson] exemplar faces; the person-level support is the *median* of those
 *     sims (less sensitive to a single outlier exemplar than the mean).
 *  3. If the best support ≥ [PersonMatchThreshold], assign; otherwise create a new Person.
 *
 * After assignment, exemplars are rotated by quality (a better face evicts the lowest-quality
 * exemplar, keeping the set diverse). The person's centroid + exemplar caches are invalidated
 * on any exemplar change so the next face sees fresh state.
 *
 * Persistence: embeddings live in the Room `faces.embeddingJson` column (durable source through
 * Phase 2) **and** are mirrored into the memory-mapped [FaceVectorIndex] (fast-read overlay).
 * The embedding is staged to the vector index immediately after the Face row is committed; the
 * worker flushes it to disk periodically, and [FaceIndexConsistency] backfills any vectors lost
 * to a crash on the next cold start. Reading goes through the vector index with a JSON fallback.
 */
class PersonMatcher(private val context: Context) {

    private val database: GalleryDatabase = GalleryDatabase.getInstance(context)
    private val faceDao: FaceDao = database.faceDao()
    private val personDao: PersonDao = database.personDao()
    private val vectorIndex: FaceVectorIndex =
        (context.applicationContext as GallerySearchApp).faceVectorIndex
    private val matchMutex = Mutex()

    // ── In-memory caches (rebuilt lazily, invalidated on person/exemplar changes) ──────────
    private val cacheLock = ReentrantLock()
    @Volatile private var personsCache: List<PersonEntity>? = null
    @Volatile private var exemplarIdsCache: HashMap<Long, List<Long>>? = null
    @Volatile private var centroidCache: HashMap<Long, FloatArray>? = null

    /** Outcome of processing one detected face in the indexing pass. */
    data class MatchOutcome(
        /** Person id when assigned / created. 0 if the face failed to embed. */
        val personId: Long,
        /** Confidence of the assignment (median similarity to the supporting person's exemplars). */
        val confidence: Float,
        /** True when a new Person was created for this face. */
        val createdNewPerson: Boolean,
        /** True when this face is now the person's exemplar (cover) face. */
        val becamePersonExemplar: Boolean
    )

    /** Process a single face — assigns it to an existing Person or creates a new one. */
    suspend fun match(newFace: FaceEntity): MatchOutcome? = matchMutex.withLock {
        matchLocked(newFace)
    }

    private suspend fun matchLocked(newFace: FaceEntity): MatchOutcome? {
        val embeddingJson = newFace.embeddingJson ?: return null
        val newEmbedding = decodeEmbedding(embeddingJson) ?: return null

        val persons = persons() ?: emptyList()
        if (persons.isEmpty()) {
            return createPerson(newFace, newEmbedding)
        }

        // ── centroid pre-filter ──────────────────────────────────────────────────────
        val centroids = centroids() ?: emptyMap()
        val candidates = persons
            .mapNotNull { person ->
                val centroid = centroids[person.personId] ?: return@mapNotNull null
                person to cosine(newEmbedding, centroid)
            }
            .sortedByDescending { it.second }
            .takeWhile { it.second >= CentroidPreFilterFloor }
            .take(CentroidCandidates)

        if (candidates.isEmpty()) {
            return createPerson(newFace, newEmbedding)
        }

        // ── exemplar-vote on the surviving candidates ─────────────────────────────────
        val exemplarIds = exemplarIds() ?: emptyMap()
        var bestPerson: PersonEntity? = null
        var bestSupport = Float.NEGATIVE_INFINITY
        var secondSupport = Float.NEGATIVE_INFINITY
        for ((person, _) in candidates) {
            val faceIds = exemplarIds[person.personId].orEmpty()
            if (faceIds.isEmpty()) continue
            val sims = faceIds.mapNotNull { fid -> vectorIndex.get(fid)?.let { cosine(newEmbedding, it) } }
            if (sims.isEmpty()) continue
            val support = median(sims)
            if (support > bestSupport) {
                secondSupport = bestSupport
                bestSupport = support
                bestPerson = person
            } else if (support > secondSupport) {
                secondSupport = support
            }
        }

        // Margin guard: when the top two candidates are nearly tied, the face is ambiguous between
        // two identities — a classic cross-identity false positive. Reject the assignment and
        // create a new person instead of forcing a choice. Only applies when there are 2+ real
        // candidates (secondSupport > -Inf).
        val ambiguous = secondSupport > Float.NEGATIVE_INFINITY &&
            (bestSupport - secondSupport) < AssignmentMargin

        return if (bestPerson != null && bestSupport >= PersonMatchThreshold && !ambiguous) {
            assignFaceToPerson(newFace, newEmbedding, bestPerson.personId, bestSupport)
        } else {
            createPerson(newFace, newEmbedding)
        }
    }

    private suspend fun createPerson(newFace: FaceEntity, embedding: FloatArray): MatchOutcome {
        val person = PersonEntity(
            nameLabel = null,
            exemplarFaceId = 0L,
            isHidden = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val personId = personDao.insert(person)
        val stored = newFace.copy(personId = personId, isExemplar = true)
        val faceId = faceDao.insert(stored)
        vectorIndex.put(faceId, embedding)
        personDao.setExemplarFace(personId, faceId)
        invalidateCaches()
        Log.i(Tag, "Created person id=$personId for face id=$faceId")
        return MatchOutcome(
            personId = personId,
            confidence = 1.0f,
            createdNewPerson = true,
            becamePersonExemplar = true
        )
    }

    private suspend fun assignFaceToPerson(
        newFace: FaceEntity,
        embedding: FloatArray,
        personId: Long,
        confidence: Float
    ): MatchOutcome {
        val stored = newFace.copy(personId = personId, isExemplar = false)
        val faceId = faceDao.insert(stored)
        vectorIndex.put(faceId, embedding)
        val becameExemplar = maybeRotateExemplar(personId, faceId, stored)
        Log.d(Tag, "Assigned face id=$faceId → person id=$personId (conf=%.3f)".format(confidence))
        return MatchOutcome(
            personId = personId,
            confidence = confidence,
            createdNewPerson = false,
            becamePersonExemplar = becameExemplar
        )
    }

    /** Promote every good early sample, then rotate only after the fixed exemplar pool is full. */
    private suspend fun maybeRotateExemplar(personId: Long, faceId: Long, face: FaceEntity): Boolean {
        val exemplars = faceDao.findByPerson(personId)
            .filter { it.isExemplar }
            .sortedBy { it.qualityScore }
        if (exemplars.size < MaxExemplarsPerPerson) {
            faceDao.setExemplar(faceId, true)
            if (exemplars.lastOrNull()?.qualityScore ?: Float.NEGATIVE_INFINITY < face.qualityScore) {
                personDao.setExemplarFace(personId, faceId)
            }
            invalidateCaches()
            return true
        }
        val lowest = exemplars.first()
        if (face.qualityScore <= lowest.qualityScore) return false
        faceDao.setExemplar(faceId, true)
        faceDao.setExemplar(lowest.faceId, false)
        personDao.setExemplarFace(personId, faceId)
        invalidateCaches()
        return true
    }

    // ── Cache management ───────────────────────────────────────────────────────────────────

    private fun invalidateCaches() = cacheLock.withLock {
        personsCache = null
        exemplarIdsCache = null
        centroidCache = null
    }

    private suspend fun persons(): List<PersonEntity> {
        personsCache?.let { return it }
        val loaded = personDao.all().filter { !it.isHidden }
        cacheLock.withLock { personsCache = loaded }
        return loaded
    }

    private suspend fun exemplarIds(): Map<Long, List<Long>>? {
        exemplarIdsCache?.let { return it }
        val personsList = persons() ?: return null
        val map = HashMap<Long, List<Long>>(personsList.size)
        for (person in personsList) {
            val ids = faceDao.findByPerson(person.personId)
                .filter { it.isExemplar && it.embeddingJson != null }
                .sortedByDescending { it.qualityScore }
                .take(MaxExemplarsPerPerson)
                .map { it.faceId }
            if (ids.isNotEmpty()) map[person.personId] = ids
        }
        cacheLock.withLock { exemplarIdsCache = map }
        return map
    }

    /**
     * Per-person centroid = mean of the top [MaxExemplarsPerPerson] exemplar embeddings,
     * L2-renormalized. Embeddings are read from the vector index (mmap) with a JSON fallback so
     * a cold start before the first flush still works.
     */
    private suspend fun centroids(): Map<Long, FloatArray>? {
        centroidCache?.let { return it }
        val ids = exemplarIds() ?: return null
        val map = HashMap<Long, FloatArray>(ids.size)
        val accum = FloatArray(FaceEmbedder.EmbeddingDim)
        for ((personId, faceIds) in ids) {
            java.util.Arrays.fill(accum, 0f)
            var count = 0
            for (fid in faceIds) {
                val emb = vectorIndex.get(fid)
                    ?: faceDao.findById(fid)?.embeddingJson?.let { decodeEmbedding(it) }
                    ?: continue
                for (i in accum.indices) accum[i] += emb[i]
                count++
            }
            if (count == 0) continue
            for (i in accum.indices) accum[i] /= count
            map[personId] = l2Normalize(accum.copyOf())
        }
        cacheLock.withLock { centroidCache = map }
        return map
    }

    private fun decodeEmbedding(json: String): FloatArray? =
        runCatching {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        }.getOrNull()?.takeIf { it.size == FaceEmbedder.EmbeddingDim }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        check(a.size == b.size) { "cosine: shape mismatch ${a.size} vs ${b.size}" }
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).takeIf { it > 1e-6f } ?: return FloatArray(v.size)
        return FloatArray(v.size) { i -> v[i] / norm }
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val midpoint = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[midpoint - 1] + sorted[midpoint]) / 2f
        else sorted[midpoint]
    }

    private companion object {
        private const val Tag = "PersonMatcher"

        /**
         * Cosine similarity threshold for assigning a new face to an existing person.
         *
         * The OpenCV Zoo published 0.363 decision threshold is calibrated for the **fp32** SFace
         * export. We ship the **int8 block-quantized** model (`face_recognition_sface_2021dec_int8bq`),
         * which compresses the cosine distribution: same-person cosines dip slightly and cross-
         * identity cosines rise, narrowing the decision gap. 0.38 (the previous value, only +0.017
         * above the fp32 floor) let cross-identity pairs through — false positives.
         *
         * 0.42 sits in the middle of the narrowed int8 gap: same-person pairs on SFace int8
         * typically score 0.48–0.85, while different-person pairs rarely exceed 0.40. An earlier
         * attempt at 0.45 over-rejected same-person matches and spawned singletons, so the operating
         * point stays below that.
         */
        private const val PersonMatchThreshold = 0.42f

        /**
         * Minimum margin between the top and second-best candidate support to accept an assignment.
         * When two persons are nearly equidistant from a face, the match is ambiguous — forcing a
         * choice there is the classic cross-identity false positive. 0.06 is wide enough to reject
         * hard ambiguous cases but narrow enough not to reject a clear winner sitting just above a
         * distant second.
         */
        private const val AssignmentMargin = 0.06f

        /** Cap on per-person exemplar set: grow up to this many, then rotate by quality. */
        private const val MaxExemplarsPerPerson = 10

        /**
         * Loose floor for the centroid pre-filter — well below [PersonMatchThreshold] so it only
         * discards clearly-unrelated persons; borderline cases still go to the full exemplar-vote.
         * Raised from 0.20 (which passed nearly every person as a candidate) to 0.28: still catches
         * genuine same-person centroids on SFace int8 (which sit ~0.35–0.60) while dropping clearly
         * unrelated persons, shrinking the candidate set that the margin guard has to reason about.
         */
        private const val CentroidPreFilterFloor = 0.28f

        /** Max persons passed from the centroid pre-filter into the exemplar-vote. */
        private const val CentroidCandidates = 8
    }
}

private inline fun <T> ReentrantLock.withLock(action: () -> T): T {
    lock()
    try { return action() } finally { unlock() }
}
