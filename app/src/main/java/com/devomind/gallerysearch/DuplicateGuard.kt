package com.devomind.gallerysearch

import com.devomind.gallerysearch.db.PersonPhotoEntity
import kotlin.math.abs

/**
 * Near-duplicate detection across burst sequences. Two photos are "the same burst" when:
 *
 *   1. Δ-timestamp < [PhashUtils.BurstWindowMillis]   (2s default)
 *   2. Their aspect fits within [PhashUtils.DimensionDeltaPercent]  (10% sizing delta)
 *   3. Their dHash Hamming distance <= [PhashUtils.NearDuplicateHammingThreshold]  (8 bits)
 *
 * A photo joining an existing burst does NOT get skipped at face time (that's the Phase 1
 * pipeline's job). Instead its highest-quality face is allowed to *replace* the exemplar for the
 * burst — bursts commonly contain one sharper/better-expressed frame, and we'd permanently pin a
 * worse exemplar otherwise.
 */
object DuplicateGuard {

    data class Request(
        val candidatePhoto: PersonPhotoEntity,
        val candidateWidth: Int,
        val candidateHeight: Int,
        val candidateFaceProportions: List<Float>,
        val candidateMaxQuality: Float,
        val siblingsInWindow: List<PersonPhotoEntity>,
        val siblingWidths: Map<String, Int>,
        val siblingHeights: Map<String, Int>,
        val siblingFaceProportions: Map<String, List<Float>>
    )

    /**
     * Result of checking [candidate] against prior photos. Either:
     * - [NotDuplicate]: no prior burst-room-ish match, run the pipeline normally.
     * - [DuplicateOf]: a burst sibling exists. Quality is still evaluated; the burst exemplar is
     *   updated if [candidate]'s best face beats the incumbent's.
     */
    sealed class Outcome {
        data class NotDuplicate(val candidatePhoto: PersonPhotoEntity) : Outcome()
        data class DuplicateOf(
            val candidatePhoto: PersonPhotoEntity,
            val incumbentExemplar: PersonPhotoEntity,
            val burstMemberUris: List<String>,
            /** True when [candidatePhoto] should become the new burst exemplar — i.e. its max
             * face quality exceeds the incumbent's. Caller is expected to call
             * `markAsBurstExemplar` and, via PersonMatcher, re-pin the person's exemplar face. */
            val shouldReplaceExemplar: Boolean,
            /** Whether the face *content* plausibly matches the incumbent (count & bbox areas
             * within tolerance). When true, the caller may skip re-embedding faces if the photo
             * is not replacing the exemplar. */
            val contentLikelySameFaces: Boolean
        ) : Outcome()
    }

    /**
     * All-decision data for a candidate photo. Caller is expected to have already:
     *  - decoded [candidate]'s bitmap (or pulled its metadata) to a [PhashUtils] dHash,
     *  - set its dhash/creation/location fields onto [candidatePhoto].  The CandidatePhoto fields
     *    this depends on: dhash, createdAt (ms).
     *
     * The check compares [candidatePhoto] against sibling photos in the DAO's burst window.
     */
    fun classify(request: Request): Outcome = with(request) {
        // No perceptual hash stored → can't compare, treat as standalone.
        if (candidatePhoto.dhash == 0L) return Outcome.NotDuplicate(candidatePhoto)

        val matchingSiblings = mutableListOf<PersonPhotoEntity>()
        var bestIncumbent: PersonPhotoEntity? = null
        var bestIncumbentDistance = Int.MAX_VALUE
        for (sibling in siblingsInWindow) {
            if (sibling.uri == candidatePhoto.uri) continue
            if (sibling.dhash == 0L) continue
            val dist = PhashUtils.distance(candidatePhoto.dhash, sibling.dhash)
            if (dist > PhashUtils.NearDuplicateHammingThreshold) continue
            val siblingWidth = siblingWidths[sibling.uri] ?: continue
            val siblingHeight = siblingHeights[sibling.uri] ?: continue
            val dimensionsMatch =
                PhashUtils.dimensionsMatch(
                    candidateWidth, candidateHeight, siblingWidth, siblingHeight
                )
            if (!dimensionsMatch.match) continue
            matchingSiblings += sibling
            // Found at least one burst sibling. Pick the closest hash match as the reference.
            if (dist < bestIncumbentDistance) {
                bestIncumbentDistance = dist
                bestIncumbent = sibling
            }
        }
        val incumbent = bestIncumbent ?: return Outcome.NotDuplicate(candidatePhoto)

        // Exemplar incumbent: only a verified burst sibling can be the reference.
        val exemplar = matchingSiblings.maxByOrNull { it.exemplarQuality } ?: incumbent
        val shouldReplace = candidateMaxQuality > exemplar.exemplarQuality

        // Whether the *content* (face count + total bbox area as fraction of image) plausibly
        // matches. Used by the caller to skip re-embedding when not replacing the exemplar.
        // We don't have incumbent's face-area data here yet — formalized below as a ratio.
        val candidateAreas = candidateFaceProportions.sorted()
        val incumbentAreas = siblingFaceProportions[exemplar.uri].orEmpty().sorted()
        val plausibleContentMatch =
            candidateAreas.size == exemplar.faceCount &&
                candidateAreas.size == incumbentAreas.size &&
                candidateAreas.zip(incumbentAreas).all { (candidate, incumbentArea) ->
                    abs(candidate - incumbentArea) <= FaceAreaTolerance
                }

        return Outcome.DuplicateOf(
            candidatePhoto = candidatePhoto,
            incumbentExemplar = exemplar,
            burstMemberUris = matchingSiblings.map { it.uri },
            shouldReplaceExemplar = shouldReplace,
            contentLikelySameFaces = plausibleContentMatch,
        )
    }

    /** Face-count guard strength: allow ±1 disagreement between duplicates before re-certifying. */
    private const val FaceAreaTolerance = 0.08f
}
