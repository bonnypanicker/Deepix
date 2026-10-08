package com.devomind.gallerysearch

/**
 * Decides which members of a suspected-duplicate group may be offered for deletion.
 *
 * CLIP grouping is cosine-based and therefore *transitive*: A matches B at 0.97 and B matches C at
 * 0.97, so C joins the group even when A and C are two different moments in the same scene — and
 * cosine alone also rates a re-shot frame and a reposted image as near-identical. Deleting on that
 * evidence loses a photo. So a group earns nothing until each member is measured, pixel-wise, against
 * the one file the app would keep: a star around the largest copy, not a chain.
 *
 * Pure and hash-supplied, so the caller decides where a dHash comes from — a cached column or a
 * decode — and the rule needs no bitmap, database or device to test. Anything the measurement cannot
 * answer comes back unconfirmed: a member without a hash, or a group whose kept file has none, is not
 * called a duplicate. Declining to offer a deletion is cheap; offering a wrong one is not.
 */
object DuplicateVerifier {

    /** The file that stays, and the members proven to be copies of it. */
    data class Verdict<T>(val keep: T?, val confirmed: Set<T>)

    /**
     * The largest of [group] by [sizeOf] is the copy worth keeping, and every other member is a
     * duplicate only while its dHash sits within [threshold] bits of that file's. Members the group
     * cannot measure are left out of [Verdict.confirmed] rather than defaulted in.
     */
    fun <T> verify(
        group: List<T>,
        sizeOf: (T) -> Long,
        dhashOf: (T) -> Long?,
        threshold: Int = PhashUtils.NearDuplicateHammingThreshold
    ): Verdict<T> {
        val keep = group.maxByOrNull(sizeOf)
        val pivotHash = keep?.let(dhashOf) ?: return Verdict(keep, emptySet())
        val confirmed = LinkedHashSet<T>()
        for (member in group) {
            if (member == keep) continue
            val hash = dhashOf(member) ?: continue
            if (PhashUtils.distance(pivotHash, hash) <= threshold) confirmed.add(member)
        }
        return Verdict(keep, confirmed)
    }
}
