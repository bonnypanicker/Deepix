package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import com.devomind.gallerysearch.db.FaceEntity
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.PersonEntity
import com.devomind.gallerysearch.db.PersonMergeLogEntity
import org.json.JSONArray
import kotlin.math.sqrt

/**
 * Phase 3 maintenance: split detection, merge detection, PersonMergeLog logging, and undo.
 *
 * Reads from [FaceVectorIndex] (the Phase 2 memory-mapped embedding store) with JSON fallback so
 * it's safe to run even immediately after Phase 1 without the vector index warmed.
 *
 * Designed so it can run **on device** without any UI — candidates are surfaced via the
 * append-only PersonMergeLog; a user-facing screen in Phase 4 will read from there.
 */
object ClusterMaintenance {

    data class SplitSuggestion(
        val personId: Long,
        /** Distance from the lowest intrapair cosine — roughly how bimodal this person is. */
        val spread: Float,
        /** The two most divergent exemplar faces within this person. */
        val faceAId: Long,
        val faceBId: Long,
        val faceASimilarity: Float,
        val faceBSimilarity: Float,
    )

    data class MergeSuggestion(
        val personAId: Long,
        val personBId: Long,
        val centroidSimilarity: Float,
    )

    data class MaintenanceResult(
        val splits: List<SplitSuggestion>,
        val merges: List<MergeSuggestion>,
        val newLogEntries: Int,
        val skippedExisting: Int
    )

    /**
     * Walk every face-cluster (Person) currently stored, compute intrapair cosines across its
     * exemplar set, and surface (split, merge) suggestions. Writes non-duplicate suggestions to
     * the PersonMergeLog:
     *   - identical (personId, otherPersonId, kindSameKind) tuple already suggested → skipped
     *   - the system legbit includes the original detection time so the scheduler can retest
     *     after enough time passes.
     */
    suspend fun analyze(context: Context): MaintenanceResult {
        val db = GalleryDatabase.getInstance(context)
        val personDao = db.personDao()
        val faceDao = db.faceDao()
        val logDao = db.personMergeLogDao()

        val persons = personDao.all().filter { !it.isHidden }
        if (persons.size < 2) {
            // Need at least two persons before anything makes sense to merge or split.
            return MaintenanceResult(emptyList(), emptyList(), 0, 0)
        }

        // Pull all person faces once, with embeddings (mmap + JSON fallback).
        val vectorIndex = (context.applicationContext as GallerySearchApp).faceVectorIndex
        val facesByPerson = persons.associate { person ->
            val faces = faceDao.findByPerson(person.personId)
                .filter {
                    it.embeddingJson != null &&
                        it.embeddingModelVersion == FaceEmbedder.ModelVersion &&
                        it.isExemplar
                }
                .sortedByDescending { it.qualityScore }
                .take(MaxExemplarsPerPerson)
            person.personId to faces
        }

        // Compute per-person centroid + pairwise exemplar similarity matrix
        val personCentroids = mutableMapOf<Long, FloatArray>()
        val personExemplarEmbeddings = mutableMapOf<Long, List<FloatArray>>()
        for ((personId, faces) in facesByPerson) {
            if (faces.isEmpty()) continue
            val embeddings = faces.mapNotNull { f ->
                vectorIndex.get(f.faceId)
                    ?: runCatching { decodeEmbedding(f.embeddingJson!!) }.getOrNull()
                        ?.takeIf { it.size == FaceEmbedder.EmbeddingDim }
            }
            if (embeddings.isEmpty()) continue
            personExemplarEmbeddings[personId] = embeddings
            personCentroids[personId] = meanNormalized(embeddings)
        }

        // ── Split candidates: a person whose own exemplars are far from the centroid ──────
        val splits = ArrayList<SplitSuggestion>()
        for ((personId, embeddings) in personExemplarEmbeddings) {
            if (embeddings.size < 2) continue
            var minSim = Float.POSITIVE_INFINITY
            var aIdx = -1
            var bIdx = -1
            for (i in 0 until embeddings.size) {
                for (j in i + 1 until embeddings.size) {
                    val sim = cosine(embeddings[i], embeddings[j])
                    if (sim < minSim) { minSim = sim; aIdx = i; bIdx = j }
                }
            }
            if (minSim >= SplitThreshold || aIdx < 0) continue
            val faces = facesByPerson[personId].orEmpty()
            val faceAId = faces.getOrNull(aIdx)?.faceId ?: continue
            val faceBId = faces.getOrNull(bIdx)?.faceId ?: continue
            splits += SplitSuggestion(
                personId = personId,
                spread = 1f - minSim, // larger = more divergent
                faceAId = faceAId,
                faceBId = faceBId,
                faceASimilarity = cosine(personCentroids.getValue(personId), embeddings[aIdx]),
                faceBSimilarity = cosine(personCentroids.getValue(personId), embeddings[bIdx])
            )
        }

        // ── Merge candidates: two persons whose centroids match closely ───────────────
        val merges = ArrayList<MergeSuggestion>()
        for ((personA, centroidA) in personCentroids) {
            for ((personB, centroidB) in personCentroids) {
                if (personA >= personB) continue
                val sim = cosine(centroidA, centroidB)
                if (sim >= MergeThreshold) {
                    merges += MergeSuggestion(personAId = personA, personBId = personB, centroidSimilarity = sim)
                }
            }
        }

        // ── PersonMergeLog: dedupe + insert ───────────────────────────────────────────────
        val recentSystem = logDao.systemSuggestionsSince(
            System.currentTimeMillis() - SuggestionWindowMillis
        )
        val suggestedKeys = recentSystem.mapTo(HashSet()) { Triple(it.eventKind, it.personId, it.otherPersonId) }

        var inserted = 0
        var skipped = 0

        splits.forEach { s ->
            val key = Triple(PersonMergeLogEntity.Event.SUGGEST_SPLIT, s.personId, 0L)
            if (key in suggestedKeys) { skipped++; return@forEach }
            logDao.insert(
                PersonMergeLogEntity(
                    eventKind = PersonMergeLogEntity.Event.SUGGEST_SPLIT,
                    personId = s.personId,
                    otherPersonId = 0,
                    metricJson = JSONArray().apply {
                        put(s.faceAId)
                        put(s.faceBId)
                        put(s.faceASimilarity.toDouble())
                        put(s.faceBSimilarity.toDouble())
                        put(s.spread.toDouble())
                    }.toString(),
                    origin = PersonMergeLogEntity.Origin.SYSTEM
                )
            )
            inserted++
            suggestedKeys += key
        }

        merges.forEach { m ->
            // Direction-normalize the key so (A,B) equals (B,A) for dedupe.
            val (lo, hi) = if (m.personAId < m.personBId) m.personAId to m.personBId else m.personBId to m.personAId
            val key = Triple(PersonMergeLogEntity.Event.SUGGEST_MERGE, lo, hi)
            if (key in suggestedKeys) { skipped++; return@forEach }
            logDao.insert(
                PersonMergeLogEntity(
                    eventKind = PersonMergeLogEntity.Event.SUGGEST_MERGE,
                    personId = m.personAId,
                    otherPersonId = m.personBId,
                    metricJson = JSONArray().apply { put(m.centroidSimilarity.toDouble()) }.toString(),
                    origin = PersonMergeLogEntity.Origin.SYSTEM
                )
            )
            inserted++
            suggestedKeys += key
        }

        Log.d(
            Tag,
            "analyze complete: ${splits.size} split, ${merges.size} merge suggestions ($inserted new, $skipped duplicate)"
        )
        return MaintenanceResult(splits, merges, inserted, skipped)
    }

    /**
     * Undo-ready: insert an UNDO_* event referencing [originalEventId] without mutating whatever
     * the original event mutated. The actual state is reconstructed by replaying all events.
     */
    suspend fun enqueueUndo(context: Context, originalEventId: Long): Boolean {
        val db = GalleryDatabase.getInstance(context)
        val logDao = db.personMergeLogDao()
        val original = logDao.byId(originalEventId) ?: return false
        val undoKind = when (original.eventKind) {
            PersonMergeLogEntity.Event.MERGE -> PersonMergeLogEntity.Event.UNDO_MERGE
            PersonMergeLogEntity.Event.SPLIT -> PersonMergeLogEntity.Event.UNDO_SPLIT
            else -> return false // cannot undo a pure suggestion; treat as no-op
        }
        logDao.insert(
            PersonMergeLogEntity(
                eventKind = undoKind,
                personId = original.personId,
                otherPersonId = original.otherPersonId,
                metricJson = original.metricJson,
                refEventId = originalEventId,
                origin = PersonMergeLogEntity.Origin.USER
            )
        )
        return true
    }

    // ────────────────────────────────────────────────────────────────────────────────────────
    // Math
    // ────────────────────────────────────────────────────────────────────────────────────────

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        check(a.size == b.size)
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun meanNormalized(vectors: List<FloatArray>): FloatArray {
        if (vectors.isEmpty()) return FloatArray(FaceEmbedder.EmbeddingDim)
        val dim = vectors.first().size
        val out = FloatArray(dim)
        for (v in vectors) for (i in 0 until dim) out[i] += v[i]
        for (i in 0 until dim) out[i] /= vectors.size
        return l2Normalize(out)
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum).takeIf { it > 1e-6f } ?: return FloatArray(v.size)
        return FloatArray(v.size) { i -> v[i] / n }
    }

    private fun decodeEmbedding(json: String): FloatArray {
        val arr = JSONArray(json)
        return FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
    }

    private const val Tag = "ClusterMaintenance"

    /** Below this intrapair cosine, a cluster is considered bimodal (ArcFace default ~0.7). */
    private const val SplitThreshold = 0.70f

    /** Above this centroid-to-centroid cosine, two persons should merge (ArcFace default ~0.65–0.7). */
    private const val MergeThreshold = 0.65f

    /** Cap on exemplar faces considered per person when computing divergence. */
    private const val MaxExemplarsPerPerson = 10

    /** Re-surface the same suggestion only after this window passes: 24h. */
    private const val SuggestionWindowMillis = 24L * 60 * 60 * 1000
}
