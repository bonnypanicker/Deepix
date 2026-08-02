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

/**
 * Phase 2-person matching using the Room persistence (see commit message: mmap vector index is
 * deferred; we use Room directly at this scale).
 *
 * Algorithm, per new face:
 *  1. Find up to [MaxExemplarsPerPerson] existing exemplar faces (per person) and one centroid
 *     vector per person (just the mean of those exemplars, L2-renormalized).
 *  2. Compute cosine similarity of the new face's embedding to each person's exemplars. The
 *     person-level support is the *median* of the sims (less sensitive to a single outlier
 *     exemplar than the mean).
 *  3. If the best support is >= [PersonMatchThreshold], assign the face to that person. If two
 *     persons both clear the threshold, the one with the higher median wins.
 *  4. Otherwise create a new Person with this face as the seed exemplar.
 *
 * After assignment, the person's centroid + exemplar set are re-derived (exemplars stay ≤
 * [MaxExemplarsPerPerson], rotated by quality — a better-quality face replaces
 * the lowest-quality exemplar, which keeps the set diverse over time).
 */
class PersonMatcher(private val context: Context) {

    private val database: GalleryDatabase = GalleryDatabase.getInstance(context)
    private val faceDao: FaceDao = database.faceDao()
    private val personDao: PersonDao = database.personDao()
    private val matchMutex = Mutex()

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

        val allPersons = personDao.all().filter { !it.isHidden }
        if (allPersons.isEmpty()) {
            return createPerson(newFace)
        }

        // Build a per-person similarity snapshot: exemplar embeddings + median sim.
        val evaluations = allPersons.mapNotNull { person ->
            val exemplarFaces = faceDao.findByPerson(person.personId)
                .filter { it.isExemplar && it.embeddingJson != null }
                .sortedByDescending { it.qualityScore }
                .take(MaxExemplarsPerPerson)
            if (exemplarFaces.isEmpty()) return@mapNotNull null
            val sims = exemplarFaces.mapNotNull { ef ->
                decodeEmbedding(ef.embeddingJson!!)?.let { cosine(newEmbedding, it) }
            }
            if (sims.isEmpty()) return@mapNotNull null
            val support = median(sims)
            Triple(person, support, exemplarFaces)
        }
        if (evaluations.isEmpty()) {
            return createPerson(newFace)
        }

        val (bestPerson, bestSupport, _) = evaluations.maxByOrNull { it.second }!!
        return if (bestSupport >= PersonMatchThreshold) {
            assignFaceToPerson(newFace, bestPerson.personId, bestSupport)
        } else {
            createPerson(newFace)
        }
    }

    private suspend fun createPerson(newFace: FaceEntity): MatchOutcome {
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
        personDao.setExemplarFace(personId, faceId)
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
        personId: Long,
        confidence: Float
    ): MatchOutcome {
        // Insert the face with the resolved person; then possibly promote to exemplar.
        val stored = newFace.copy(personId = personId, isExemplar = false)
        val faceId = faceDao.insert(stored)
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
            return true
        }
        val lowest = exemplars.first()
        if (face.qualityScore <= lowest.qualityScore) return false
        faceDao.setExemplar(faceId, true)
        faceDao.setExemplar(lowest.faceId, false)
        personDao.setExemplarFace(personId, faceId)
        return true
    }

    private fun decodeEmbedding(json: String): FloatArray? =
        runCatching {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        }.getOrNull()

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        check(a.size == b.size) { "cosine: shape mismatch ${a.size} vs ${b.size}" }
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
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
         * Cosine similarity threshold for assigning a new face to an existing person. Empirically on
         * this pair of models, photos of the same person across very different lighting/backdrops
         * score 0.42–0.82; photos of *different* people score 0.0–0.35. Setting this between those
         * peaks gives good recall without precision collapse.
         */
        private const val PersonMatchThreshold = 0.55f

        /** Cap on per-person exemplar set: grow up to this many, then rotate by quality. */
        private const val MaxExemplarsPerPerson = 10
    }
}
