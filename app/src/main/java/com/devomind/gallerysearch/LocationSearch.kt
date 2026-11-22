package com.devomind.gallerysearch

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.util.Log
import com.devomind.gallerysearch.db.GpsPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Place-aware photo lookup for the search "Locations" section — deliberately independent of the
 * semantic index: a photo matches because its EXIF GPS sits within [RadiusMeters] of the place the
 * query names, not because of what the image looks like.
 *
 * Two Android-Geocoder lookups, each cached to disk so repeats work offline:
 *  - forward: query text -> a coordinate ("goa" -> 15.3, 74.1). Known misses are cached as "".
 *  - the photos' own coordinates come from the exif_metadata table, filled progressively (bounded
 *    per search) by reading EXIF for library photos that were never opened in the viewer.
 *
 * Everything here fails soft: any error yields an empty match, never a failed search.
 */
object LocationSearch {

    private const val Tag = "LocationSearch"
    private const val CacheFileName = "location_geocode_cache.json"
    private const val RadiusMeters = 50_000.0
    private const val MaxExifExtractionsPerSearch = 150

    @Volatile private var cache: MutableMap<String, String>? = null

    /**
     * URIs of library photos located within [RadiusMeters] of the place [query] names.
     *
     * Runs on [Dispatchers.IO] because the pass reads MediaProvider (EXIF for unindexed photos), the
     * GPS table and the geocode cache. Its caller is a results render on the Main dispatcher.
     *
     * The place lookup comes first: a query that names no place resolves to a cached miss and
     * returns before a single photo is read, which is what every ordinary word search hits.
     */
    suspend fun matchingUris(
        context: Context,
        query: String,
        db: DbRepository,
        libraryUris: List<Uri>
    ): Set<String> = withContext(Dispatchers.IO) {
        val cleaned = query.trim().lowercase(Locale.getDefault())
        if (cleaned.isEmpty() || !Geocoder.isPresent()) return@withContext emptySet()
        try {
            val target = resolveTarget(context, cleaned) ?: return@withContext emptySet()
            fillMissingExif(context, db, libraryUris)
            val gps = db.gpsPoints()
            if (gps.isEmpty()) return@withContext emptySet()
            gps.filter { point ->
                haversineMeters(point.lat, point.lng, target.first, target.second) <= RadiusMeters
            }.mapTo(HashSet()) { it.uri }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(Tag, "Location search failed (non-fatal)", error)
            emptySet()
        }
    }

    /**
     * Reads EXIF (incl. GPS) for unread library photos, then continues the one-way repair sweep over
     * rows that already exist. See [planExifFill] for why the sweep must not restart at the top.
     */
    private suspend fun fillMissingExif(context: Context, db: DbRepository, libraryUris: List<Uri>) {
        if (libraryUris.isEmpty()) return
        val all = libraryUris.map { it.toString() }
        val existing = db.existingExifUris(all)
        val canReadOriginal = StoragePermissions.canReadOriginalExif(context)
        val hadPermission = IndexPreferences.hadExifRepairPermission(context)
        if (canReadOriginal && !hadPermission) {
            // A fresh grant: rows written while it was missing may hold a redacted GPS block, so the
            // sweep starts over from the first stored row.
            IndexPreferences.setExifRevisitCursor(context, 0)
            IndexPreferences.setExifRepairPermission(context, true)
        } else if (!canReadOriginal && hadPermission) {
            // Marked unseen so a later re-grant starts the repair again rather than trusting a sweep
            // that ran against stripped reads.
            IndexPreferences.setExifRepairPermission(context, false)
        }
        val plan = planExifFill(
            libraryUris = all,
            existing = existing,
            revisitCursor = IndexPreferences.exifRevisitCursor(context),
            limit = MaxExifExtractionsPerSearch,
            revisitsAllowed = canReadOriginal
        )
        for (raw in plan.uris) {
            runCatching { ExifExtractor.extract(context, Uri.parse(raw)) }
                .getOrNull()
                ?.let { db.upsertExif(raw, it) }
        }
        if (plan.revisits.isNotEmpty()) {
            IndexPreferences.setExifRevisitCursor(context, plan.nextCursor)
        }
    }

    /** Query text -> (lat, lng): a disk-cached hit, else one forward-geocode call (misses cached too). */
    private suspend fun resolveTarget(context: Context, query: String): Pair<Double, Double>? {
        cache(context)[query]?.let { cached -> return parsePoint(cached) }
        val hit = withContext(Dispatchers.IO) {
            runCatching {
                Geocoder(context, Locale.getDefault())
                    .getFromLocationName(query, 1)
                    ?.firstOrNull()
            }.getOrNull()
        }
        val point = hit?.let { it.latitude to it.longitude }
        cache(context)[query] = point?.let { "${it.first},${it.second}" }.orEmpty()
        saveCache(context)
        return point
    }

    private fun cache(context: Context): MutableMap<String, String> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val map = runCatching {
                val obj = JSONObject(File(context.filesDir, CacheFileName).readText())
                val m = linkedMapOf<String, String>()
                for (key in obj.keys()) m[key] = obj.optString(key)
                m
            }.getOrDefault(linkedMapOf())
            cache = map
            return map
        }
    }

    private fun saveCache(context: Context) {
        val map = cache ?: return
        runCatching {
            val obj = JSONObject()
            for ((key, value) in map) obj.put(key, value)
            File(context.filesDir, CacheFileName).writeText(obj.toString())
        }
    }

    private fun parsePoint(raw: String): Pair<Double, Double>? {
        if (raw.isBlank()) return null
        val lat = raw.split(",").getOrNull(0)?.toDoubleOrNull() ?: return null
        val lng = raw.split(",").getOrNull(1)?.toDoubleOrNull() ?: return null
        return lat to lng
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** Which photos one search is allowed to read EXIF for. */
internal data class ExifFillPlan(
    /** Library photos with no stored EXIF row yet: read once each, newest-first in pool order. */
    val fresh: List<String>,
    /** Already-stored rows re-read to repair a redacted GPS block, in a pool-order-stable order. */
    val revisits: List<String>,
    /** Where the next search's repair sweep resumes; never moves backwards. */
    val nextCursor: Int
) {
    val uris: List<String> get() = fresh + revisits
}

/**
 * Bounds one search's EXIF work. Unread photos come first because they are progress; stored rows are
 * revisited only because older builds saved GPS as redacted, and only while [revisitsAllowed] — a
 * re-read without the location permission hands back a stripped block and would overwrite good GPS
 * with nulls.
 *
 * The cursor makes that repair a single sweep: `fillMissingExif` used to put the existing rows after
 * the unread ones and `.take(150)` from the top, so once every row existed it re-read the same 150
 * files on every keystroke, forever. A sorted candidate list keeps the sweep independent of the pool
 * order, which changes with filters and album scope.
 */
internal fun planExifFill(
    libraryUris: List<String>,
    existing: Set<String>,
    revisitCursor: Int,
    limit: Int,
    revisitsAllowed: Boolean
): ExifFillPlan {
    val cursor = revisitCursor.coerceAtLeast(0)
    if (limit <= 0 || libraryUris.isEmpty()) return ExifFillPlan(emptyList(), emptyList(), cursor)

    val fresh = libraryUris.filter { it !in existing }.take(limit)
    val candidates = libraryUris.filter { it in existing }.distinct().sorted()
    val start = cursor.coerceAtMost(candidates.size)
    val revisits = if (revisitsAllowed && fresh.size < limit) {
        val room = limit - fresh.size
        candidates.subList(start, minOf(start + room, candidates.size))
    } else {
        emptyList()
    }
    return ExifFillPlan(fresh, revisits, start + revisits.size)
}
