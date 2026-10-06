package com.devomind.gallerysearch

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * The app's own count of the time it has spent inside a `dataSync` foreground service, and the rule
 * that keeps a long job inside the platform's allowance.
 *
 * Android 14+ permits 6 h of that time per rolling 24 h for the *whole app* — one pool that indexing,
 * compression and cleanup all draw from, through the single foreground service WorkManager runs. At
 * the cap the system calls `Service.onTimeout`, and WorkManager 2.9.1 does not implement that callback:
 * the service stays standing, and a few seconds later the process dies with
 * `RemoteServiceException: A foreground service of type dataSync did not stop within its timeout`.
 * Bounding the pass here is therefore the only fix available without a dependency bump, and it is the
 * one that keeps working whichever version the library ends up on.
 *
 * Usage is recorded per hour because the window rolls instead of resetting at midnight: an hour of
 * foreground time becomes spendable again exactly 24 h after it was used, so the moment a deferred pass
 * may resume is derived from the ledger instead of guessed from a clock boundary.
 *
 * The arithmetic is deliberately separate from the storage. Getting a rolling window wrong is an
 * off-by-one on the boundary hour, which either stops a pass a day early or lets it run into the cap —
 * and both have to be checked without a device attached.
 */
object ForegroundBudget {

    /** The platform allowance, written out rather than derived: a dropped factor here is a silent 60x. */
    val CapMillis = TimeUnit.HOURS.toMillis(6)

    /**
     * Room a pass insists on before starting, and the room it stops on while running. The span between
     * two ledger writes is a batch of inference, so the check that stops a pass mid-run has to fire
     * early enough for the batch in flight to land inside the cap.
     */
    val StopReserveMillis = TimeUnit.MINUTES.toMillis(20)

    const val WindowHours = 24L

    private const val PrefName = "foreground_budget"
    private const val KeyHourlyMillis = "hourly_millis"

    /** The bucket an absolute moment falls in: hours since the epoch. */
    fun hourIndex(wallMillis: Long): Long = TimeUnit.MILLISECONDS.toHours(wallMillis)

    /** Hours whose record still counts against [nowMillis] — the 24 buckets ending with its own. */
    private fun inWindow(buckets: Map<Long, Long>, nowMillis: Long): Map<Long, Long> {
        val now = hourIndex(nowMillis)
        return buckets.filterKeys { now - it in 0 until WindowHours }
    }

    /** Foreground millis spent in the rolling 24 h ending at [nowMillis]. */
    fun usedMillis(buckets: Map<Long, Long>, nowMillis: Long, liveMillis: Long = 0L): Long =
        inWindow(withLive(buckets, liveMillis, nowMillis), nowMillis).values.sum()

    fun remainingMillis(buckets: Map<Long, Long>, nowMillis: Long, liveMillis: Long = 0L): Long =
        (CapMillis - usedMillis(buckets, nowMillis, liveMillis)).coerceAtLeast(0L)

    /** True while a pass may still hold — or take — the foreground without reaching the cap. */
    fun hasRoom(buckets: Map<Long, Long>, nowMillis: Long, liveMillis: Long = 0L): Boolean =
        remainingMillis(buckets, nowMillis, liveMillis) > StopReserveMillis

    /**
     * When the window rolls far enough for [StopReserveMillis] to open up again, or null when there is
     * already room. Hours age out oldest-first and the first one to go may not free enough on its own,
     * so this walks the ledger and stops at the hour that crosses the line — a single wake instead of a
     * retry that wakes, finds nothing, and waits again.
     */
    fun refillAtMillis(buckets: Map<Long, Long>, nowMillis: Long, liveMillis: Long = 0L): Long? {
        if (hasRoom(buckets, nowMillis, liveMillis)) return null
        val counted = inWindow(withLive(buckets, liveMillis, nowMillis), nowMillis).filterValues { it > 0L }
        val used = counted.values.sum()
        var freed = 0L
        // Hours age out oldest-first, so the wait ends at the first hour whose exit — with the ones
        // before it — leaves the reserve standing up again.
        val exitHour = counted.keys.sorted().firstOrNull {
            freed += counted.getValue(it)
            CapMillis - (used - freed) > StopReserveMillis
        }
        return TimeUnit.HOURS.toMillis((exitHour ?: hourIndex(nowMillis)) + WindowHours)
    }

    /** The live, not-yet-recorded span belongs to the hour it is being measured in. */
    private fun withLive(buckets: Map<Long, Long>, liveMillis: Long, nowMillis: Long): Map<Long, Long> {
        if (liveMillis <= 0L) return buckets
        val hour = hourIndex(nowMillis)
        return buckets + mapOf(hour to (buckets[hour] ?: 0L) + liveMillis)
    }

    /** `hour:millis` pairs. Kept as text so the parse and the prune are testable without a device. */
    fun encode(buckets: Map<Long, Long>): String =
        buckets.entries.filter { it.value > 0L }.joinToString(",") { "${it.key}:${it.value}" }

    /** Records written by an older build, or a truncated one, must not cost the whole ledger. */
    fun decode(text: String?): Map<Long, Long> {
        if (text.isNullOrEmpty()) return emptyMap()
        return text.split(",")
            .mapNotNull { record ->
                val parts = record.split(":")
                val hour = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val millis = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                if (millis <= 0L) null else hour to millis
            }
            .toMap()
    }

    // ---- storage ----

    private fun prefs(context: Context) =
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)

    private fun load(context: Context, nowMillis: Long): Map<Long, Long> =
        inWindow(decode(prefs(context).getString(KeyHourlyMillis, null)), nowMillis)

    fun usedMillis(context: Context, liveMillis: Long = 0L, nowMillis: Long = System.currentTimeMillis()): Long =
        usedMillis(load(context, nowMillis), nowMillis, liveMillis)

    fun hasRoom(context: Context, liveMillis: Long = 0L, nowMillis: Long = System.currentTimeMillis()): Boolean =
        hasRoom(load(context, nowMillis), nowMillis, liveMillis)

    /** Millis until [hasRoom] can flip true, or 0 when it already holds. */
    fun millisUntilRoom(context: Context, liveMillis: Long = 0L, nowMillis: Long = System.currentTimeMillis()): Long =
        (refillAtMillis(load(context, nowMillis), nowMillis, liveMillis)?.minus(nowMillis) ?: 0L).coerceAtLeast(0L)

    /**
     * Adds one finished stretch of foreground time. Stale hours are dropped on the way in, so the stored
     * string stays bounded by the window instead of growing for the life of the install.
     *
     * A pass that deferred before taking the foreground measures milliseconds; recording those would put
     * debt on an hour that spent none, so the floor is a second rather than zero.
     */
    fun addForegroundTime(context: Context, elapsedMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
        if (elapsedMillis < 1_000L) return
        val hour = hourIndex(nowMillis)
        val current = load(context, nowMillis)
        val merged = current + mapOf(hour to (current[hour] ?: 0L) + elapsedMillis)
        prefs(context).edit().putString(KeyHourlyMillis, encode(merged)).apply()
    }
}
