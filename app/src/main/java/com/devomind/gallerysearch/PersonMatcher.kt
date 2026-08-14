package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import com.devomind.gallerysearch.db.FaceDao
import com.devomind.gallerysearch.db.FaceEntity
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.PersonDao
import com.devomind.gallerysearch.db.PersonEntity
import com.devomind.gallerysearch.db.PersonMergeLogDao
import com.devomind.gallerysearch.db.PersonMergeLogEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phase 2 person matching.
 *
 * Algorithm, per new face:
 *  1. Decode the new face embedding and classify the face as good/bad.
 *  2. Walk every visible person and compare the new embedding against *all* stored faces for that
 *     person. The person-level support is the max cosine over any member face (single-linkage).
 *  3. Assign to the best person iff that support clears the per-face threshold. Otherwise create a
 *     new Person.
 *
 * This mirrors Ente's incremental linear clustering much more closely than the old
 * centroid-plus-exemplar gate: a new face only needs to match one prior face, not the mean of a
 * curated subset. We still keep exemplars for UI cover quality, but they are no longer used as the
 * matching decision surface.
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
    private val mergeLogDao: PersonMergeLogDao = database.personMergeLogDao()
    private val vectorIndex: FaceVectorIndex =
        (context.applicationContext as GallerySearchApp).faceVectorIndex
    private val matchMutex = Mutex()

    // ── In-memory caches (rebuilt lazily, invalidated on person membership changes) ─────────
    private val cacheLock = ReentrantLock()
    @Volatile private var personsCache: List<PersonEntity>? = null
    @Volatile private var personFaceIdsCache: HashMap<Long, List<Long>>? = null

    /** Outcome of processing one detected face in the indexing pass. */
    data class MatchOutcome(
        /** Person id when assigned / created. 0 if the face failed to embed. */
        val personId: Long,
        /** Confidence of the assignment (best cosine to any already-clustered face). */
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

        val persons = persons()
        if (persons.isEmpty()) {
            return createPerson(newFace, newEmbedding)
        }

        val threshold = matchThresholdFor(newFace)
        val faceIdsByPerson = personFaceIds()
        var bestPerson: PersonEntity? = null
        var bestSupport = Float.NEGATIVE_INFINITY
        for (person in persons) {
            val faceIds = faceIdsByPerson[person.personId].orEmpty()
            if (faceIds.isEmpty()) continue
            var support = Float.NEGATIVE_INFINITY
            for (faceId in faceIds) {
                val emb = readEmbedding(faceId) ?: continue
                val sim = cosine(newEmbedding, emb)
                if (sim > support) support = sim
            }
            if (support > bestSupport) {
                bestSupport = support
                bestPerson = person
            }
        }

        return if (bestPerson != null && bestSupport >= threshold) {
            assignFaceToPerson(newFace, newEmbedding, bestPerson.personId, bestSupport)
        } else {
            createPerson(newFace, newEmbedding)
        }
    }

    /**
     * Heal already-split identities after an index pass. We iteratively merge the pair of person
     * clusters with the highest mean-embedding cosine, weighting merges by cluster size so the
     * resulting mean tracks the full cluster rather than a single exemplar.
     */
    suspend fun reconcileSplits(): Int = matchMutex.withLock {
        invalidateCaches()
        val persons = persons()
        if (persons.size < 2) return@withLock 0

        val countsByPerson = HashMap<Long, Int>(persons.size)
        val clusters = ArrayList<MergeCluster>(persons.size)
        for (person in persons) {
            val embeddings = mutableListOf<FloatArray>()
            for (face in faceDao.findByPerson(person.personId)) {
                if (face.embeddingJson != null &&
                    face.embeddingModelVersion == FaceEmbedder.ModelVersion
                ) {
                    readEmbedding(face.faceId)?.let { embeddings.add(it) }
                }
            }
            if (embeddings.isEmpty()) continue
            countsByPerson[person.personId] = embeddings.size
            val sum = FloatArray(FaceEmbedder.EmbeddingDim)
            for (embedding in embeddings) {
                for (i in sum.indices) sum[i] += embedding[i]
            }
            clusters += MergeCluster(
                memberPersonIds = mutableListOf(person.personId),
                summedEmbedding = sum,
                embeddingCount = embeddings.size
            )
        }
        if (clusters.size < 2) return@withLock 0

        val blockedPairs = blockedAutoMergePairs()
        while (true) {
            var bestI = -1
            var bestJ = -1
            var bestSim = Float.NEGATIVE_INFINITY
            for (i in 0 until clusters.lastIndex) {
                for (j in i + 1 until clusters.size) {
                    if (hasBlockedPair(clusters[i], clusters[j], blockedPairs)) continue
                    val sim = cosine(normalizedClusterMean(clusters[i]), normalizedClusterMean(clusters[j]))
                    if (sim > bestSim) {
                        bestSim = sim
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (bestI < 0 || bestSim < MeanMergeThreshold) break
            val left = clusters[bestI]
            val right = clusters[bestJ]
            for (i in left.summedEmbedding.indices) {
                left.summedEmbedding[i] += right.summedEmbedding[i]
            }
            left.embeddingCount += right.embeddingCount
            left.memberPersonIds += right.memberPersonIds
            clusters.removeAt(bestJ)
        }

        val mergedClusters = clusters.filter { it.memberPersonIds.size > 1 }
        if (mergedClusters.isEmpty()) return@withLock 0

        val byId = persons.associateBy { it.personId }
        var merged = 0
        for (cluster in mergedClusters) {
            val ranked = cluster.memberPersonIds.mapNotNull { id ->
                val person = byId[id] ?: return@mapNotNull null
                val count = countsByPerson[id] ?: 0
                Triple(person, count, !person.nameLabel.isNullOrBlank())
            }.sortedWith(
                compareByDescending<Triple<PersonEntity, Int, Boolean>> { it.third }
                    .thenByDescending { it.second }
                    .thenBy { it.first.personId }
            )
            val winner = ranked.firstOrNull()?.first ?: continue
            for (loser in ranked.drop(1)) {
                faceDao.reassignPerson(loser.first.personId, winner.personId)
                personDao.hide(loser.first.personId)
                mergeLogDao.insert(
                    PersonMergeLogEntity(
                        eventKind = PersonMergeLogEntity.Event.MERGE,
                        personId = winner.personId,
                        otherPersonId = loser.first.personId,
                        origin = PersonMergeLogEntity.Origin.SYSTEM
                    )
                )
                merged++
                Log.i(Tag, "Merged person ${loser.first.personId} into ${winner.personId}")
            }
        }
        invalidateCaches()
        merged
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
        invalidateCaches()
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

    // ── Cache management ───────────────────────────────────────────────────────────────────

    private fun invalidateCaches() = cacheLock.withLock {
        personsCache = null
        personFaceIdsCache = null
    }

    private suspend fun persons(): List<PersonEntity> {
        personsCache?.let { return it }
        val loaded = personDao.all().filter { !it.isHidden }
        cacheLock.withLock { personsCache = loaded }
        return loaded
    }

    private suspend fun personFaceIds(): Map<Long, List<Long>> {
        personFaceIdsCache?.let { return it }
        val personsList = persons()
        val map = HashMap<Long, List<Long>>(personsList.size)
        for (person in personsList) {
            val ids = faceDao.findByPerson(person.personId)
                .filter {
                    it.embeddingJson != null &&
                        it.embeddingModelVersion == FaceEmbedder.ModelVersion
                }
                .map { it.faceId }
            if (ids.isNotEmpty()) map[person.personId] = ids
        }
        cacheLock.withLock { personFaceIdsCache = map }
        return map
    }

    private fun decodeEmbedding(json: String): FloatArray? =
        runCatching {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        }.getOrNull()?.takeIf { it.size == FaceEmbedder.EmbeddingDim }

    private suspend fun readEmbedding(faceId: Long): FloatArray? =
        vectorIndex.get(faceId)
            ?: faceDao.findById(faceId)?.embeddingJson?.let { decodeEmbedding(it) }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        check(a.size == b.size) { "cosine: shape mismatch ${a.size} vs ${b.size}" }
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun matchThresholdFor(face: FaceEntity): Float =
        if (isBadFace(face)) BadFaceMatchThreshold else GoodFaceMatchThreshold

    private fun isBadFace(face: FaceEntity): Boolean =
        face.isLowQuality ||
            abs(face.yaw) >= SidewaysYawDegrees ||
            abs(face.pitch) >= SidewaysPitchDegrees ||
            abs(face.roll) >= SidewaysRollDegrees

    private suspend fun blockedAutoMergePairs(): Set<Pair<Long, Long>> {
        val events = mergeLogDao.allOldestFirst()
        val byId = events.associateBy { it.id }
        val blocked = HashSet<Pair<Long, Long>>()
        for (event in events) {
            if (event.origin != PersonMergeLogEntity.Origin.USER) continue
            when (event.eventKind) {
                PersonMergeLogEntity.Event.SPLIT -> {
                    if (event.personId > 0 && event.otherPersonId > 0) {
                        blocked += orderedPair(event.personId, event.otherPersonId)
                    }
                }
                PersonMergeLogEntity.Event.UNDO_MERGE -> {
                    val original = byId[event.refEventId] ?: continue
                    if (original.personId > 0 && original.otherPersonId > 0) {
                        blocked += orderedPair(original.personId, original.otherPersonId)
                    }
                }
            }
        }
        return blocked
    }

    private fun hasBlockedPair(
        left: MergeCluster,
        right: MergeCluster,
        blockedPairs: Set<Pair<Long, Long>>
    ): Boolean {
        for (leftId in left.memberPersonIds) {
            for (rightId in right.memberPersonIds) {
                if (orderedPair(leftId, rightId) in blockedPairs) return true
            }
        }
        return false
    }

    private fun orderedPair(a: Long, b: Long): Pair<Long, Long> =
        if (a <= b) a to b else b to a

    private fun normalizedClusterMean(cluster: MergeCluster): FloatArray =
        l2Normalize(cluster.summedEmbedding.copyOf())

    private data class MergeCluster(
        val memberPersonIds: MutableList<Long>,
        val summedEmbedding: FloatArray,
        var embeddingCount: Int
    )

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).takeIf { it > 1e-6f } ?: return FloatArray(v.size)
        return FloatArray(v.size) { i -> v[i] / norm }
    }

    private companion object {
        private const val Tag = "PersonMatcher"

        /** Ente-style good-face incremental clustering threshold. */
        private const val GoodFaceMatchThreshold = 0.76f

        /** Ente-style stricter threshold for low-quality or sideways faces. */
        private const val BadFaceMatchThreshold = 0.84f

        /** Cap on per-person exemplar set: grow up to this many, then rotate by quality. */
        private const val MaxExemplarsPerPerson = 10

        /** Mean-cluster merge threshold from Ente's complete-clustering reconciliation pass. */
        private const val MeanMergeThreshold = 0.70f

        private const val SidewaysYawDegrees = 18f
        private const val SidewaysPitchDegrees = 20f
        private const val SidewaysRollDegrees = 20f
    }
}

private inline fun <T> ReentrantLock.withLock(action: () -> T): T {
    lock()
    try { return action() } finally { unlock() }
}
